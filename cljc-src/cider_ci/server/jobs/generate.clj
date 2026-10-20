(ns cider-ci.server.jobs.generate
  (:require
    [cider-ci.server.jobs.decompose :refer [tasks->map key->str]]
    [cider-ci.utils.core :refer [deep-merge]]
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

(defn merge-generated-tasks
  "Legacy semantics (builder.jobs.tasks-generator): an explicit `tasks:` entry
   with the same key as a generated task (the file path) is deep-merged INTO
   the generated one, e.g. `spec/x_spec.rb: {priority: 1}` keeps the
   generated CIDER_CI_TASK_FILE. Keys are compared as strings: the generated
   keys are file paths, the explicit ones keywordized YAML keys (namespaced
   when they contain a slash)."
  [generated explicit]
  (reduce (fn [acc [k v]]
            (let [k (key->str k)]
              (assoc acc k (deep-merge (get acc k {}) v))))
          generated
          explicit))

(defn- expand-context [context project-id commit-id]
  (let [ctx (if-let [gen-spec (:generate_tasks context)]
              (let [files     (file-list project-id commit-id gen-spec)
                    generated (->> files
                                   (map (fn [f] [f {:environment_variables {:CIDER_CI_TASK_FILE f}}]))
                                   (into {}))
                    tasks     (merge-generated-tasks generated (tasks->map (:tasks context)))]
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
