(ns cider-ci.executor.scripts
  (:import [java.io File]))


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


(defn- run-one! [key-str spec env-vars work-dir]
  (try
    (let [log-file    (File. (System/getProperty "java.io.tmpdir")
                             (str "cider-ci-script-" key-str ".log"))
          script-file (File. ^String work-dir (str "cider-ci-" key-str ".sh"))]
      (spit script-file (or (:body spec) ""))
      (.setExecutable script-file true)
      (let [pb (doto (ProcessBuilder. ["bash" (.getAbsolutePath script-file)])
                 (.directory (File. ^String work-dir))
                 (.redirectErrorStream true)
                 (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo log-file)))
            _  (when env-vars
                 (let [env (.environment pb)]
                   (doseq [[k v] env-vars]
                     (.put env (name k) (str v)))))
            proc      (.start pb)
            exit-code (.waitFor proc)]
        {:state       (if (zero? exit-code) "passed" "failed")
         :exit_status exit-code
         :log-file    log-file}))
    (catch Exception e
      {:state "defective"
       :error (.getMessage e)})))


(defn run-all!
  "Executes all scripts in task-spec respecting start_when DAG.
   Scripts with no start_when run immediately in parallel.
   Returns {:trial-state \"passed\"|..., :scripts {\"key\" {:state ..., :log-file ...}}}"
  [work-dir task-spec]
  (let [scripts  (seq (:scripts task-spec))
        env-vars (:environment_variables task-spec)]
    (if-not scripts
      {:trial-state "defective" :scripts {}}
      (loop [results (into {} (for [[k _] scripts] [(name k) {:state "pending"}]))]
        ; 1. Skip unsatisfiable scripts (dep finished in wrong state)
        (let [pending    (filter #(= "pending" (get-in results [(name (first %)) :state])) scripts)
              to-skip    (filter #(unsatisfiable? (name (first %)) (second %) results) pending)
              results    (reduce #(assoc-in %1 [(name (first %2)) :state] "skipped") results to-skip)
              ; 2. Find scripts that can start now
              pending    (filter #(= "pending" (get-in results [(name (first %)) :state])) scripts)
              startable  (filter #(can-start? (name (first %)) (second %) results) pending)]
          (if (seq startable)
            ; Launch all eligible scripts concurrently
            (let [futs    (mapv (fn [[k spec]]
                                  [(name k) (future (run-one! (name k) spec env-vars work-dir))])
                                startable)
                  results (reduce #(assoc-in %1 [(first %2) :state] "executing") results futs)
                  results (reduce (fn [acc [key-str fut]] (assoc acc key-str @fut))
                                  results futs)]
              (recur results))
            ; Nothing startable
            (let [still-pending (filter #(= "pending" (get-in results [(name (first %)) :state])) scripts)]
              (if (empty? still-pending)
                ; All scripts done — compute trial state
                (let [non-ignored-states (->> scripts
                                              (remove #(get (second %) :ignore_state))
                                              (map #(get-in results [(name (first %)) :state]))
                                              (filter identity))]
                  {:trial-state (evaluate-states non-ignored-states)
                   :scripts     results})
                ; Stuck (circular deps) — skip remaining pending
                (recur (reduce #(assoc-in %1 [(name (first %2)) :state] "skipped")
                               results still-pending))))))))))
