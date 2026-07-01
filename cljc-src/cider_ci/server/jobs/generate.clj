(ns cider-ci.server.jobs.generate
  (:require
    [cider-ci.server.projects.repositories.git.repositories :as git]
    [taoensso.timbre :refer [warn]]))

(defn- file-list [project-id commit-id generate-spec]
  (let [include-match (or (:include_match generate-spec) "")
        exclude-match (:exclude_match generate-spec)]
    (try
      (git/ls-tree project-id commit-id include-match exclude-match)
      (catch Exception e
        (warn "generate_tasks ls-tree failed:" (.getMessage e))
        []))))

(defn- expand-context [context project-id commit-id]
  (let [ctx (if-let [gen-spec (:generate_tasks context)]
              (let [files     (file-list project-id commit-id gen-spec)
                    generated (->> files
                                   (map (fn [f] [f {:environment_variables {:CIDER_CI_TASK_FILE f}}]))
                                   (into {}))
                    tasks     (if-let [existing (:tasks context)]
                                (merge generated existing)
                                generated)]
                (-> context
                    (assoc :tasks tasks)
                    (dissoc :generate_tasks)))
              context)]
    (cond-> ctx
      (map? (:contexts ctx))
      (update :contexts
        (fn [ctxs]
          (->> ctxs
               (map (fn [[k c]] [k (expand-context c project-id commit-id)]))
               (into {}))))
      (map? (:subcontexts ctx))
      (update :subcontexts
        (fn [ctxs]
          (->> ctxs
               (map (fn [[k c]] [k (expand-context c project-id commit-id)]))
               (into {})))))))

(defn expand [project-id commit-id job-spec]
  (if-let [ctx (:context job-spec)]
    (assoc job-spec :context (expand-context ctx project-id commit-id))
    job-spec))
