(ns cider-ci.server.jobs.decompose
  (:require [cider-ci.utils.core :refer [deep-merge]]))

(def ^:private context-keys
  [:task :tasks :task_defaults :script_defaults :contexts :subcontexts])

(defn- lift-to-context [spec]
  (if (:context spec)
    spec
    (assoc spec :context (select-keys spec context-keys))))

(defn- string->scripts [s]
  {:scripts {:main {:body s}}})

(defn- normalize-task-value [v]
  (cond
    (string? v) (string->scripts v)
    (and (map? v) (:body v) (not (:scripts v)))
    (-> v (assoc :scripts {:main {:body (:body v)}}) (dissoc :body))
    :else v))

(defn- apply-script-defaults [task-spec script-defaults]
  (if (empty? script-defaults)
    task-spec
    (update task-spec :scripts
            (fn [scripts]
              (when scripts
                (into {} (for [[k v] scripts]
                           [k (merge script-defaults v)])))))))

(declare collect-from-context)

(defn tasks->map
  "Normalises the `tasks` value to a {key task-spec} map. YAML allows both a
   map (`tasks: {key: spec}`) and a list (`tasks: [{name: ..., scripts: ...}]`,
   e.g. leihs' generated scenario task files); legacy keyed list entries by
   their :name. Plain strings in a list become a task with that body, keyed
   by position."
  [tasks]
  (cond
    (nil? tasks)        {}
    (map? tasks)        tasks
    (sequential? tasks) (->> tasks
                             (map-indexed
                               (fn [i t]
                                 (let [k (or (and (map? t) (some-> (:name t) str not-empty))
                                             (str "task-" i))]
                                   [(keyword k) t])))
                             (into {}))
    :else (throw (ex-info (str "tasks must be a map or a list, got " (type tasks))
                          {:status 422 :tasks tasks}))))

(defn- from-tasks-map [tasks inherited-task-defaults script-defaults]
  (->> (tasks->map tasks)
       (map (fn [[k v]]
              (let [norm (normalize-task-value v)]
                (-> (deep-merge inherited-task-defaults
                                norm
                                {:name (or (:name norm) (name k))})
                    (apply-script-defaults script-defaults)))))))

(defn- seq-of-contexts
  "Returns a seq of context maps from a contexts value that may be either
   a map (YAML dict with named sub-contexts) or a seq (YAML list)."
  [cs]
  (when cs
    (if (map? cs) (vals cs) cs)))


(defn collect-from-context [context inherited-task-defaults inherited-script-defaults]
  (let [task-defaults   (deep-merge inherited-task-defaults (:task_defaults context {}))
        script-defaults (deep-merge inherited-script-defaults (:script_defaults context {}))
        from-task       (when-let [t (:task context)]
                          [(-> (deep-merge task-defaults
                                           (string->scripts t)
                                           {:name "main"})
                               (apply-script-defaults script-defaults))])
        from-tasks      (when-let [ts (:tasks context)]
                          (from-tasks-map ts task-defaults script-defaults))
        sub-contexts    (concat (seq-of-contexts (:contexts context))
                                (seq-of-contexts (:subcontexts context)))
        from-contexts   (mapcat #(collect-from-context % task-defaults script-defaults) sub-contexts)]
    (concat from-task from-tasks from-contexts)))

(defn decompose
  "Returns a vec of task maps from a job spec. Handles:
   - task: <body> shorthand → one task named 'main'
   - tasks: { key: spec } map
   - context: { tasks: ..., contexts: [...] } recursive
   - task_defaults / script_defaults inherited through nested contexts"
  [job-spec]
  (let [lifted (lift-to-context job-spec)]
    (vec (collect-from-context (:context lifted) {} {}))))
