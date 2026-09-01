(ns cider-ci.server.jobs.dep-trigger
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.server.jobs.auto-trigger :as auto-trigger]
    [cider-ci.server.projects.repositories.project-configuration.direct :as config]
    [cider-ci.server.projects.repositories.shared :as repo-shared]
    [cider-ci.utils.daemon :refer [defdaemon]]
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


(defn- dep-satisfied? [dep existing-by-key]
  (let [dep-type (some-> dep :type name)
        job-key  (some-> dep :job_key name)
        states   (set (map name (or (:states dep) [])))]
    (cond
      (and dep-type (not= dep-type "job")) false
      (seq (:submodule dep))               false
      :else
      (let [existing (get existing-by-key job-key)]
        (and existing (contains? states (:state existing)))))))


(defn- all-deps-satisfied? [spec existing-by-key]
  (every? (fn [[_ dep]] (dep-satisfied? dep existing-by-key))
          (:depends_on spec)))


(defn- trigger-dependents! [ds project-id commit-id]
  (try
    (with-open [repo (repo-shared/file-repository (repo-shared/path {:project-id project-id}))]
      (let [job-configs   (read-job-configs repo commit-id)
            existing-jobs (jdbc/execute! ds
                            ["SELECT key, state FROM jobs WHERE project_id = ? AND commit_id = ?"
                             project-id commit-id])
            by-key        (into {} (map (fn [j] [(:key j) j]) existing-jobs))
            to-trigger    (filter (fn [{:keys [key spec]}]
                                    (and (seq (:depends_on spec))
                                         (not (contains? by-key key))
                                         (all-deps-satisfied? spec by-key)))
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


(defn- recently-active-commits [ds]
  (jdbc/execute! ds
    ["SELECT DISTINCT project_id, commit_id FROM jobs
      WHERE updated_at >= NOW() - INTERVAL '2 minutes'"]))


(defdaemon "job-dep-trigger" 10
  (try
    (doseq [{:keys [project_id commit_id]} (recently-active-commits (get-ds))]
      (trigger-dependents! (get-ds) project_id commit_id))
    (catch Exception e
      (warn "job-dep-trigger daemon error:" (.getMessage e)))))


(defn init []
  (start-job-dep-trigger))
