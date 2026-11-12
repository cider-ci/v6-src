(ns cider-ci.server.jobs.cron-trigger
  "Creates jobs from `run_when` entries of type `cron` (port of the legacy
   builder.jobs.triggers.cron):

     run_when:
       nightly:
         type: cron
         value: '30 4 * * *'          ; minute hour * * day-of-week, in UTC
         branch_include_match: ^master$
         branch_exclude_match: ^.*wip.*$   ; optional
         rerun: true                       ; optional, default false

   Every 30 seconds the heads of all branches of the repositories with
   `cron_trigger_enabled` are checked; the repository's branch trigger
   include/exclude apply, its max commit age does not (a deploy branch may
   sit on the same commit for months). A job whose expression fired within
   the last 3 minutes and whose branch filter matches the branch is created
   once per commit and key, provided its `depends_on` are fulfilled. As in
   legacy, an entry without `branch_include_match` matches no branch.

   With `rerun: true` an existing job of that key in a final state and not
   updated for 3 minutes is deleted first, with its tasks, trials and
   attachments, so the job runs again every time the cron fires: the nightly
   staging and test resets of madek and leihs rely on that. Nothing is
   persisted about past firings, a server that is down for the whole window
   misses that run (legacy behaviour)."
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.server.jobs.auto-trigger :as auto-trigger]
    [cider-ci.server.jobs.dependencies :as dependencies]
    [cider-ci.server.projects.repositories.shared :as repo-shared]
    [cider-ci.utils.cron :as cron]
    [cider-ci.utils.daemon :refer [defdaemon]]
    [clojure.core.memoize :as memo]
    [clojure.string :as str]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set]
    [taoensso.timbre :refer [debug info warn]]))


(def window-minutes
  "A cron fires when its expression matched within the last 3 minutes; a
   finished job must be as old before `rerun` replaces it."
  3)


;;; selection (pure) ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn cron-entries
  "The run_when entries of type cron of a job spec (map or list form)."
  [spec]
  (let [rw (:run_when spec)]
    (->> (cond (map? rw) (vals rw) (sequential? rw) rw :else [])
         (filter #(= "cron" (some-> (:type %) name))))))

(defn entry-matches-branch?
  "Legacy include/exclude semantics: a blank or missing branch_include_match
   matches nothing, a blank branch_exclude_match excludes nothing."
  [{:keys [branch_include_match branch_exclude_match]} branch-name]
  (boolean
    (and (not (str/blank? branch_include_match))
         (re-find (re-pattern branch_include_match) branch-name)
         (or (str/blank? branch_exclude_match)
             (not (re-find (re-pattern branch_exclude_match) branch-name))))))

(defn- entry-fires? [job-key {:keys [value] :as entry} branch-name now]
  (and (entry-matches-branch? entry branch-name)
       (try
         (cron/fire? value window-minutes now)
         (catch clojure.lang.ExceptionInfo e
           (warn "cron-trigger: job" job-key "ignored:" (ex-message e))
           false))))

(defn due-entry
  "The first cron entry of the job that fires now for branch-name, or nil."
  [{:keys [key cron-entries]} branch-name now]
  (some #(when (entry-fires? key % branch-name now) %) cron-entries))


;;; job configurations ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- cron-jobs-unmemoized
  "The jobs of the commit's configuration that have cron entries. The
   configuration is read for every head every cycle, so this is cached per
   project and commit: a commit's configuration never changes once it could
   be evaluated (a failure, e.g. a submodule commit not fetched yet, is not
   cached)."
  [project-id commit-id]
  (with-open [repo (repo-shared/file-repository (repo-shared/path {:project-id project-id}))]
    (->> (auto-trigger/read-job-configs repo commit-id)
         (keep (fn [{:keys [spec] :as job}]
                 (when-let [entries (seq (cron-entries spec))]
                   (assoc job :cron-entries (vec entries)))))
         vec)))

(def ^:private cron-jobs
  (memo/lru cron-jobs-unmemoized :lru/threshold 1000))


;;; creation ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- delete-finished-job!
  "`rerun`: removes the job of this key when it has reached a final state at
   least window-minutes ago; tasks, trials and attachments cascade."
  [tx project-id commit-id job-key]
  (let [result (jdbc/execute-one! tx
                 ["DELETE FROM jobs
                   WHERE project_id = ? AND commit_id = ? AND key = ?
                     AND state IN ('passed', 'failed', 'aborted', 'defective')
                     AND updated_at < now() - CAST(? AS interval)"
                  project-id commit-id job-key (str window-minutes " minutes")])]
    (when (pos? (:next.jdbc/update-count result))
      (info "cron-trigger: deleted finished job" job-key "of" project-id commit-id "for rerun"))))

(defn- create-job!
  "Creates the job unless one with its key exists or its depends_on are not
   fulfilled; with `rerun` a finished old job is deleted first."
  [ds project-id commit-id branch-name {:keys [key spec] :as job} entry]
  (jdbc/with-transaction [tx ds]
    (when (:rerun entry)
      (delete-finished-job! tx project-id commit-id key))
    ;; with-transaction drops the datasource's builder options (the rows
    ;; would come back with qualified keys, :jobs/key), hence the explicit one
    (let [existing (jdbc/execute! tx
                     ["SELECT key, state FROM jobs WHERE project_id = ? AND commit_id = ?"
                      project-id commit-id]
                     {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps})
          by-key   (into {} (map (fn [j] [(:key j) j]) existing))]
      (when-not (contains? by-key key)
        (let [unmet (with-open [repo (repo-shared/file-repository
                                       (repo-shared/path {:project-id project-id}))]
                      (dependencies/unmet tx repo commit-id spec by-key))]
          (if (seq unmet)
            (debug "cron-trigger: job" key "of" project-id commit-id "waits for" unmet)
            (do (info "cron-trigger: creating job" key "for" project-id commit-id
                      "on" branch-name "(" (:value entry) ")")
                (auto-trigger/create-job-with-tasks! tx project-id commit-id job))))))))


(defn- heads
  "Heads of the branches of the cron enabled repositories that pass the
   repository's branch trigger filter."
  [ds]
  (->> (jdbc/execute! ds
         ["SELECT b.repository_id AS project_id, b.name AS branch_name,
                  b.current_commit_id AS commit_id,
                  r.branch_trigger_include_match, r.branch_trigger_exclude_match
             FROM branches b JOIN repositories r ON r.id = b.repository_id
            WHERE r.cron_trigger_enabled AND b.current_commit_id IS NOT NULL
            ORDER BY b.repository_id, b.name"])
       (filter (fn [{:keys [branch_name branch_trigger_include_match branch_trigger_exclude_match]}]
                 (auto-trigger/repo-allows-branch?
                   (or branch_trigger_include_match "^.*$")
                   (or branch_trigger_exclude_match "")
                   branch_name)))))

(defn run-cycle!
  "One pass over all heads; errors are logged per head or job and never
   abort the cycle."
  ([ds] (run-cycle! ds (cron/now-utc)))
  ([ds now]
   (doseq [{:keys [project_id commit_id branch_name]} (heads ds)]
     (try
       (doseq [job (cron-jobs project_id commit_id)]
         (when-let [entry (due-entry job branch_name now)]
           (try
             (create-job! ds project_id commit_id branch_name job entry)
             (catch Exception e
               (warn "cron-trigger: failed to create job" (:key job)
                     "for" project_id commit_id "on" branch_name ":" (.getMessage e))))))
       (catch Exception e
         (warn "cron-trigger: cannot evaluate the configuration of" project_id commit_id
               "(" branch_name "):" (.getMessage e)))))))


(defdaemon "cron-trigger" 30
  (try
    (run-cycle! (get-ds))
    (catch Exception e
      (warn "cron-trigger daemon error:" (.getMessage e)))))


(defn init []
  (start-cron-trigger))
