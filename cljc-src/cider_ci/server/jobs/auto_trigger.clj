(ns cider-ci.server.jobs.auto-trigger
  (:require
    [cider-ci.server.jobs.decompose :as decompose]
    [cider-ci.server.jobs.generate :as generate]
    [cider-ci.server.projects.repositories.project-configuration.direct :as config]
    [cider-ci.server.projects.repositories.shared :as repo-shared]
    [clojure.string :as str]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [info warn]]))


(defn- matches-pattern? [pattern branch-name]
  (when-not (str/blank? pattern)
    (some? (re-find (re-pattern pattern) branch-name))))

(defn- repo-allows-branch? [repo-include repo-exclude branch-name]
  (and (or (str/blank? repo-include)
           (matches-pattern? repo-include branch-name))
       (or (str/blank? repo-exclude)
           (not (matches-pattern? repo-exclude branch-name)))))


(defn- read-job-configs [repo commit-id]
  (try
    (let [cfg      (config/build repo (str commit-id))
          jobs-map (or (:jobs cfg) {})]
      (mapv (fn [[k v]] {:key (name k) :name (or (:name v) (name k)) :spec v}) jobs-map))
    (catch clojure.lang.ExceptionInfo e
      (if (= 404 (:status (ex-data e)))
        []
        (throw e)))))


(defn task-traits-literal
  "PostgreSQL text[] literal of a task spec's traits, lowercased. Accepts the
   map form {Trait: true, Other: false} (only truthy entries count) and the
   list form [Trait Other] (all entries). Anything else yields the empty set,
   which matches every executor — so both forms must be handled or trait
   gating silently disappears (e.g. leihs `traits: [asdf]`)."
  [task-spec]
  (let [traits (:traits task-spec)
        names  (cond
                 (map? traits)        (->> traits (filter (comp true? val)) (map key))
                 (sequential? traits) traits
                 :else                [])]
    (str "{" (->> names (map (comp str/lower-case str/trim name)) (str/join ",")) "}")))


(defn insert-task!
  "Inserts one task row for job-id from a decomposed task spec, deriving the
   traits (text[]) and load columns from the spec. Shared by the auto-trigger
   and the UI create-job path so trait gating cannot silently diverge between
   them (the UI path used to omit traits entirely, so its tasks matched any
   executor regardless of the required traits)."
  [tx job-id task-id task-spec]
  (jdbc/execute-one! tx
    ["INSERT INTO tasks (id, job_id, name, state, spec, traits, load)
      VALUES (?, ?, ?, 'pending', ?, CAST(? AS text[]), ?)"
     task-id job-id (:name task-spec) task-spec
     (task-traits-literal task-spec)
     (double (or (:load task-spec) 1.0))]))


(defn create-job-with-tasks! [tx project-id commit-id {:keys [key name spec]}]
  (let [expanded   (generate/expand project-id (str commit-id) spec)
        new-job-id (java.util.UUID/randomUUID)
        result     (jdbc/execute-one! tx
                     ["INSERT INTO jobs (id, project_id, commit_id, key, name, state, spec)
                       VALUES (?, ?, ?, ?, ?, 'pending', ?)
                       ON CONFLICT (project_id, commit_id, key) DO NOTHING"
                      new-job-id project-id (str commit-id) key name expanded])]
    (when (= 1 (:next.jdbc/update-count result))
      (info "Auto-triggered job" key "for" project-id commit-id)
      (doseq [task-spec (decompose/decompose expanded)]
        (let [new-task-id (java.util.UUID/randomUUID)]
          (insert-task! tx new-job-id new-task-id task-spec)
          (jdbc/execute-one! tx
            ["INSERT INTO trials (task_id, state) VALUES (?, 'pending')"
             new-task-id]))))))


(defn- commit-within-age? [ds commit-id max-age]
  (if (nil? max-age)
    true
    (let [result (jdbc/execute-one! ds
                   ["SELECT committer_date IS NULL
                       OR committer_date >= NOW() - CAST(? AS INTERVAL) AS within_age
                     FROM commits WHERE id = ?"
                    max-age (str commit-id)])]
      (not (false? (:within_age result))))))


(defn- job-should-trigger? [{:keys [spec]} branch-name]
  ;; Jobs with depends_on are triggered by the dep-trigger daemon, not by branch push.
  (when-not (seq (:depends_on spec))
  (let [run-when (get spec :run_when)
        trigger  (get spec :trigger)]
    (cond
      ;; New format: run_when with named entries (type: branch/cron/job)
      run-when
      (let [branch-triggers (->> (vals run-when)
                                 (filter #(= "branch" (get % :type))))]
        (if (empty? branch-triggers)
          false
          (some (fn [{:keys [include_match]}]
                  (if (str/blank? include_match)
                    true
                    (matches-pattern? include_match branch-name)))
                branch-triggers)))
      ;; Legacy format: trigger.branch.include_match
      trigger
      (let [include-match (get-in trigger [:branch :include_match])]
        (if (str/blank? include-match)
          true
          (matches-pattern? include-match branch-name)))
      ;; No trigger configuration: always fire on branch push
      :else true))))


(defn trigger-for-commit! [ds project-id commit-id branch-name
                            & {:keys [repo-include repo-exclude repo-max-age]
                               :or   {repo-include "^.*$" repo-exclude "" repo-max-age nil}}]
  (when (and (repo-allows-branch? repo-include repo-exclude branch-name)
             (commit-within-age? ds commit-id repo-max-age))
    (try
      (with-open [repo (repo-shared/file-repository (repo-shared/path {:project-id project-id}))]
        (let [job-configs (read-job-configs repo commit-id)]
          (doseq [job-config job-configs]
            (when (job-should-trigger? job-config branch-name)
              (try
                (jdbc/with-transaction [tx ds]
                  (create-job-with-tasks! tx project-id commit-id job-config))
                (catch Exception e
                  (warn "Failed to auto-trigger job" (:key job-config)
                        "for" project-id commit-id "on" branch-name ":" (.getMessage e))))))))
      (catch Exception e
        (warn "Auto-trigger failed for" project-id commit-id "on" branch-name ":" (.getMessage e))))))
