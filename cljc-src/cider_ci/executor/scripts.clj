(ns cider-ci.executor.scripts
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))


;; Maps trial-id → set of running Processes (for timeout/abort).
(defonce ^:private running-procs* (atom {}))
;; Set of trial-ids that have been asked to abort.
(defonce ^:private aborting-trials* (atom #{}))
;; Maps exclusive-resource-name → Clojure agent (for serial execution per resource).
(defonce ^:private exclusive-resource-agents* (atom {}))

(defn abort-trial!
  "Signals abort for trial-id and forcibly destroys all its running processes."
  [trial-id]
  (swap! aborting-trials* conj trial-id)
  (doseq [^Process proc (get @running-procs* trial-id #{})]
    (.destroyForcibly proc)))

(defn clear-abort! [trial-id]
  (swap! aborting-trials* disj trial-id)
  (swap! running-procs* dissoc trial-id))

(defn- aborting? [trial-id]
  (contains? @aborting-trials* trial-id))

(defn evaluate-states [states]
  (cond
    (empty? states)              "defective"
    (every? #{"passed"} states)  "passed"
    (every? #{"aborted"} states) "aborted"
    (some #{"defective"} states) "defective"
    (some #{"failed"} states)    "failed"
    :else                        "defective"))


(defn- normalize-start-when
  "Handles both legacy map format {\"cond-name\": {:script_key ...}} and
   array format [{:script_key ...}] — returns a seq of condition maps."
  [start-when]
  (cond
    (nil? start-when)        []
    (map? start-when)        (vals start-when)
    (sequential? start-when) start-when
    :else                    []))


(defn- start-when-satisfied? [{:keys [script_key states]} script-results]
  (let [required  (set (map name (or states ["passed"])))
        dep-state (get-in script-results [(name script_key) :state])]
    (contains? required dep-state)))


(defn- can-start? [_key spec script-results]
  (every? #(start-when-satisfied? % script-results)
          (normalize-start-when (:start_when spec))))


(defn- terminal-state? [s]
  (contains? #{"passed" "failed" "skipped" "defective" "aborted"} s))


(defn- unsatisfiable? [_key spec script-results]
  (some (fn [{:keys [script_key states]}]
          (let [required  (set (map name (or states ["passed"])))
                dep-state (get-in script-results [(name script_key) :state])]
            (and (terminal-state? dep-state)
                 (not (contains? required dep-state)))))
        (normalize-start-when (:start_when spec))))


(declare run-one!)

(defn- get-exclusive-agent! [resource-name]
  (get (swap! exclusive-resource-agents*
              (fn [m]
                (if (get m resource-name)
                  m
                  (assoc m resource-name (agent nil :error-mode :continue)))))
       resource-name))

(defn- dispatch-script!
  "Dispatches a single script execution. If exclusive_executor_resource is set,
   serializes via a per-resource Clojure agent; otherwise runs in a future."
  [trial-id key-str spec env-vars work-dir]
  (if-let [resource-name (:exclusive_executor_resource spec)]
    (let [p   (promise)
          agt (get-exclusive-agent! resource-name)]
      (send-off agt (fn [_]
                      (deliver p (run-one! trial-id key-str spec env-vars work-dir))
                      nil))
      p)
    (future (run-one! trial-id key-str spec env-vars work-dir))))


(defn- run-one! [trial-id key-str spec env-vars work-dir]
  (try
    (let [timeout-sec (or (:timeout spec) 600)
          log-file    (File. (System/getProperty "java.io.tmpdir")
                             (str "cider-ci-script-" key-str ".log"))
          script-file (File. ^String work-dir (str "cider-ci-" key-str ".sh"))]
      (spit script-file (or (:body spec) ""))
      (.setExecutable script-file true)
      (let [pb   (doto (ProcessBuilder. ["bash" (.getAbsolutePath script-file)])
                   (.directory (File. ^String work-dir))
                   (.redirectErrorStream true)
                   (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo log-file)))
            _    (when env-vars
                   (let [env (.environment pb)]
                     (doseq [[k v] env-vars]
                       (.put env (name k) (str v)))))
            proc (.start pb)]
        (swap! running-procs* update trial-id (fnil conj #{}) proc)
        (try
          (cond
            (not (.waitFor proc timeout-sec TimeUnit/SECONDS))
            (do (.destroyForcibly proc)
                {:state "defective"
                 :error (str "Script timed out after " timeout-sec "s")
                 :log-file log-file})
            :else
            {:state       (if (zero? (.exitValue proc)) "passed" "failed")
             :exit_status (.exitValue proc)
             :log-file    log-file})
          (finally
            (swap! running-procs* update trial-id disj proc)))))
    (catch Exception e
      {:state "defective"
       :error (.getMessage e)})))


(defn run-all!
  "Executes all scripts in task-spec respecting start_when DAG.
   env-vars is the fully merged environment map (task env-vars + ports etc.).
   Returns {:trial-state \"passed\"|..., :scripts {\"key\" {:state ..., :log-file ...}}}"
  [work-dir task-spec env-vars trial-id]
  (let [scripts (seq (:scripts task-spec))]
    (if-not scripts
      {:trial-state "defective" :scripts {}}
      (loop [results (into {} (for [[k _] scripts] [(name k) {:state "pending"}]))]
        (if (aborting? trial-id)
          ; Abort requested — skip all remaining pending scripts
          (let [results (reduce #(assoc-in %1 [(name (first %2)) :state] "skipped")
                                results
                                (filter #(= "pending" (get-in results [(name (first %)) :state])) scripts))]
            {:trial-state "aborted" :scripts results})
          ; Normal execution
          (let [pending   (filter #(= "pending" (get-in results [(name (first %)) :state])) scripts)
                to-skip   (filter #(unsatisfiable? (name (first %)) (second %) results) pending)
                results   (reduce #(assoc-in %1 [(name (first %2)) :state] "skipped") results to-skip)
                pending   (filter #(= "pending" (get-in results [(name (first %)) :state])) scripts)
                startable (filter #(can-start? (name (first %)) (second %) results) pending)]
            (if (seq startable)
              (let [futs    (mapv (fn [[k spec]]
                                    [(name k) (dispatch-script! trial-id (name k) spec env-vars work-dir)])
                                  startable)
                    results (reduce #(assoc-in %1 [(first %2) :state] "executing") results futs)
                    results (reduce (fn [acc [key-str fut]] (assoc acc key-str @fut))
                                    results futs)]
                (recur results))
              (let [still-pending (filter #(= "pending" (get-in results [(name (first %)) :state])) scripts)]
                (if (empty? still-pending)
                  (let [non-ignored-states (->> scripts
                                                (remove #(get (second %) :ignore_state))
                                                (map #(get-in results [(name (first %)) :state]))
                                                (filter identity))]
                    {:trial-state (evaluate-states non-ignored-states)
                     :scripts     results})
                  (recur (reduce #(assoc-in %1 [(name (first %2)) :state] "skipped")
                                 results still-pending)))))))))))
