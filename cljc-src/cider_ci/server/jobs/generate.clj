(ns cider-ci.server.jobs.generate
  (:require
    [cider-ci.server.jobs.decompose :refer [tasks->map]]
    [cider-ci.server.projects.repositories.git.repositories :as git]
    [cider-ci.server.projects.repositories.project-configuration.submodules :as submodules]
    [taoensso.timbre :refer [warn]]))

(defn- resolve-source
  "Where to list files: the project's own repository/commit, or — when the
   generate_tasks spec carries `submodule: [seg ...]` — the submodule's
   repository/commit resolved through .gitmodules + gitlinks (legacy parity)."
  [project-id commit-id generate-spec]
  (let [segments (let [s (:submodule generate-spec)]
                   (cond (sequential? s) (vec s)
                         (string? s)     [s]
                         :else           []))]
    (if (empty? segments)
      {:repo-id project-id :commit-id commit-id}
      (with-open [repo (submodules/open-repo project-id)]
        (submodules/resolve-submodule-chain {:repo repo :commit-id commit-id} segments)))))

(defn- file-list [project-id commit-id generate-spec]
  (let [include-match (or (:include_match generate-spec) "")
        exclude-match (:exclude_match generate-spec)]
    (try
      (let [{:keys [repo-id commit-id]} (resolve-source project-id commit-id generate-spec)]
        (git/ls-tree repo-id commit-id include-match exclude-match))
      (catch clojure.lang.ExceptionInfo e
        ;; a bad submodule reference is a configuration error — surface it
        (if (= 422 (:status (ex-data e)))
          (throw e)
          (do (warn "generate_tasks ls-tree failed:" (.getMessage e)) [])))
      (catch Exception e
        (warn "generate_tasks ls-tree failed:" (.getMessage e))
        []))))

(defn- expand-context [context project-id commit-id]
  (let [ctx (if-let [gen-spec (:generate_tasks context)]
              (let [files     (file-list project-id commit-id gen-spec)
                    generated (->> files
                                   (map (fn [f] [f {:environment_variables {:CIDER_CI_TASK_FILE f}}]))
                                   (into {}))
                    ;; explicit tasks (map or list form) win over generated ones
                    tasks     (merge generated (tasks->map (:tasks context)))]
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
