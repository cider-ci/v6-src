(ns cider-ci.server.jobs.auto-trigger
  (:require
    [cider-ci.server.jobs.decompose :as decompose]
    [cider-ci.server.projects.repositories.project-configuration.direct :as config]
    [cider-ci.server.projects.repositories.shared :as repo-shared]
    [next.jdbc :as jdbc]
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
            ["INSERT INTO tasks (id, job_id, name, state, spec) VALUES (?, ?, ?, 'pending', ?)"
             new-task-id new-job-id (:name task-spec) task-spec])
          (jdbc/execute-one! tx
            ["INSERT INTO trials (task_id, state) VALUES (?, 'pending')"
             new-task-id]))))))


(defn trigger-for-commit! [ds project-id commit-id]
  (try
    (with-open [repo (repo-shared/file-repository (repo-shared/path {:project-id project-id}))]
      (let [job-configs (read-job-configs repo commit-id)]
        (doseq [job-config job-configs]
          (try
            (jdbc/with-transaction [tx ds]
              (create-job-with-tasks! tx project-id commit-id job-config))
            (catch Exception e
              (warn "Failed to auto-trigger job" (:key job-config)
                    "for" project-id commit-id ":" (.getMessage e)))))))
    (catch Exception e
      (warn "Auto-trigger failed for" project-id commit-id ":" (.getMessage e)))))
