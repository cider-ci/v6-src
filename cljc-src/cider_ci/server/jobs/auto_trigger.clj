(ns cider-ci.server.jobs.auto-trigger
  (:require
    [cider-ci.server.jobs.decompose :as decompose]
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


(defn- task-traits-literal [task-spec]
  (let [traits (:traits task-spec)]
    (if (map? traits)
      (str "{"
           (->> traits
                (filter (comp true? val))
                (map (comp name key))
                (str/join ","))
           "}")
      "{}")))


(defn- create-job-with-tasks! [tx project-id commit-id {:keys [key name spec]}]
  (let [new-job-id (java.util.UUID/randomUUID)
        result     (jdbc/execute-one! tx
                     ["INSERT INTO jobs (id, project_id, commit_id, key, name, state, spec)
                       VALUES (?, ?, ?, ?, ?, 'pending', ?)
                       ON CONFLICT (project_id, commit_id, key) DO NOTHING"
                      new-job-id project-id (str commit-id) key name spec])]
    (when (= 1 (:next.jdbc/update-count result))
      (info "Auto-triggered job" key "for" project-id commit-id)
      (doseq [task-spec (decompose/decompose spec)]
        (let [new-task-id (java.util.UUID/randomUUID)]
          (jdbc/execute-one! tx
            ["INSERT INTO tasks (id, job_id, name, state, spec, traits, load)
              VALUES (?, ?, ?, 'pending', ?, CAST(? AS text[]), ?)"
             new-task-id new-job-id (:name task-spec) task-spec
             (task-traits-literal task-spec)
             (double (or (:load task-spec) 1.0))])
          (jdbc/execute-one! tx
            ["INSERT INTO trials (task_id, state) VALUES (?, 'pending')"
             new-task-id]))))))


(defn- commit-within-age? [ds commit-id max-age]
  (if (str/blank? max-age)
    true
    (let [result (jdbc/execute-one! ds
                   ["SELECT committer_date IS NULL
                       OR committer_date >= NOW() - CAST(? AS INTERVAL) AS within_age
                     FROM commits WHERE id = ?"
                    max-age (str commit-id)])]
      (not (false? (:within_age result))))))


(defn- job-should-trigger? [{:keys [spec]} branch-name]
  (let [trigger (get spec :trigger)]
    (or (nil? trigger)
        (let [include (get-in trigger [:branch :include_match])]
          (if (str/blank? include)
            true
            (matches-pattern? include branch-name))))))


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
