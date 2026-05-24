(ns cider-ci.server.jobs.decompose)

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

(declare collect-from-context)

(defn- from-tasks-map [tasks inherited-defaults]
  (->> tasks
       (map (fn [[k v]]
              (let [norm (normalize-task-value v)]
                (merge inherited-defaults
                       norm
                       {:name (or (:name norm) (name k))}))))))

(defn collect-from-context [context inherited-defaults]
  (let [task-defaults (merge inherited-defaults (:task_defaults context))
        from-task     (when-let [t (:task context)]
                        [(merge task-defaults
                                (string->scripts t)
                                {:name "main"})])
        from-tasks    (when-let [ts (:tasks context)]
                        (from-tasks-map ts task-defaults))
        sub-contexts  (concat (:contexts context) (:subcontexts context))
        from-contexts (mapcat #(collect-from-context % task-defaults) sub-contexts)]
    (concat from-task from-tasks from-contexts)))

(defn decompose
  "Returns a vec of task maps from a job spec. Handles:
   - task: <body> shorthand → one task named 'main'
   - tasks: { key: spec } map
   - context: { tasks: ..., contexts: [...] } recursive"
  [job-spec]
  (let [lifted (lift-to-context job-spec)]
    (vec (collect-from-context (:context lifted) {}))))
