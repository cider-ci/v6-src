(ns cider-ci.server.jobs.dep-trigger
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.server.jobs.auto-trigger :as auto-trigger]
    [cider-ci.server.jobs.dependencies :as dependencies]
    [cider-ci.server.projects.repositories.project-configuration.direct :as config]
    [cider-ci.server.projects.repositories.shared :as repo-shared]
    [cider-ci.utils.daemon :refer [defdaemon]]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set]
    [taoensso.timbre :refer [info warn]]))


(defn- read-job-configs [repo commit-id]
  (try
    (let [cfg      (config/build repo (str commit-id))
          jobs-map (or (:jobs cfg) {})]
      (mapv (fn [[k v]] {:key (name k) :name (or (:name v) (name k)) :spec v}) jobs-map))
    (catch clojure.lang.ExceptionInfo e
      (if (= 404 (:status (ex-data e)))
        []
        (throw e)))))


(defn- all-deps-satisfied? [ds repo commit-id spec existing-by-key]
  (empty? (dependencies/unmet ds repo commit-id spec existing-by-key)))


(defn- job-run-when-entries
  "run_when entries of type `job` (map or list form)."
  [spec]
  (let [rw (:run_when spec)]
    (->> (cond (map? rw) (vals rw) (sequential? rw) rw :else [])
         (filter #(= "job" (some-> (:type %) name))))))


(defn- head-branch-names
  "Names of this project's branches whose head is commit-id and which pass
   the repository's branch trigger filter."
  [ds project-id commit-id]
  (->> (jdbc/execute! ds
         ["SELECT b.name, r.branch_trigger_include_match, r.branch_trigger_exclude_match
           FROM branches b JOIN repositories r ON r.id = b.repository_id
           WHERE b.repository_id = ? AND b.current_commit_id = ?"
          project-id commit-id]
         {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps})
       (filter (fn [{:keys [name branch_trigger_include_match branch_trigger_exclude_match]}]
                 (auto-trigger/repo-allows-branch?
                   (or branch_trigger_include_match "^.*$")
                   (or branch_trigger_exclude_match "")
                   name)))
       (map :name)))


(defn- triggered?
  "Legacy semantics: a job is only ever started by one of its run_when
   entries, and only if its depends_on are fulfilled as well; depends_on alone
   never triggers (madek's deploy jobs depend on the preflight check but must
   only be started manually).
   - type `job` (builder.jobs.triggers.tree-ids.job-update): the job reached
     one of the given states;
   - type `branch`: the commit is the head of a matching branch (jobs with
     depends_on are not created by the push itself, see auto-trigger)."
  [ds repo project-id commit-id spec existing-by-key]
  (boolean
    (and (or (some #(dependencies/satisfied? ds repo commit-id % existing-by-key)
                   (job-run-when-entries spec))
             (and (seq (:depends_on spec))
                  (some #(auto-trigger/branch-run-when-matches? spec %)
                        (head-branch-names ds project-id commit-id))))
         (all-deps-satisfied? ds repo commit-id spec existing-by-key))))


(defn- trigger-dependents! [ds project-id commit-id]
  (try
    (with-open [repo (repo-shared/file-repository (repo-shared/path {:project-id project-id}))]
      (let [job-configs   (read-job-configs repo commit-id)
            existing-jobs (jdbc/execute! ds
                            ["SELECT key, state FROM jobs WHERE project_id = ? AND commit_id = ?"
                             project-id commit-id])
            by-key        (into {} (map (fn [j] [(:key j) j]) existing-jobs))
            to-trigger    (filter (fn [{:keys [key spec]}]
                                    (and (not (contains? by-key key))
                                         (triggered? ds repo project-id commit-id spec by-key)))
                                  job-configs)]
        (when (seq to-trigger)
          (info "dep-trigger: triggering" (map :key to-trigger)
                "for" project-id commit-id
                "- existing job states:" (into {} (map (fn [j] [(:key j) (:state j)]) existing-jobs))))
        (doseq [job-config to-trigger]
          (try
            (jdbc/with-transaction [tx ds]
              (auto-trigger/create-job-with-tasks! tx project-id commit-id job-config))
            (catch Exception e
              (warn "dep-trigger: failed to trigger" (:key job-config)
                    "for" project-id commit-id ":" (.getMessage e)))))))
    (catch Exception e
      (warn "dep-trigger: error for" project-id commit-id ":" (.getMessage e)))))


(defn- active-commits
  "Commits with jobs updated within the given interval (SQL interval literal)."
  [ds interval]
  (jdbc/execute! ds
    ["SELECT DISTINCT project_id, commit_id FROM jobs
      WHERE updated_at >= NOW() - CAST(? AS interval)"
     interval]))

;; Every cycle looks at commits whose jobs changed in the last 2 minutes. A
;; trigger missed in that window (job creation failed, server down) would
;; otherwise be lost for good — leihs' manage-scenarios stayed uncreated after
;; the list-form tasks bug was fixed. So every 30th cycle (~5 min) the sweep
;; widens to the last 24 hours; `not (contains? by-key key)` keeps it idempotent.
(defonce ^:private cycle-counter* (atom 0))

(defdaemon "job-dep-trigger" 10
  (try
    (let [n        (swap! cycle-counter* inc)
          interval (if (zero? (mod n 30)) "24 hours" "2 minutes")]
      (doseq [{:keys [project_id commit_id]} (active-commits (get-ds) interval)]
        (trigger-dependents! (get-ds) project_id commit_id)))
    (catch Exception e
      (warn "job-dep-trigger daemon error:" (.getMessage e)))))


(defn init []
  (start-job-dep-trigger))
