(ns cider-ci.executor.scripts
  (:require [clojure.string :as str]
            [cider-ci.utils.duration :as duration])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

(defn- parse-timeout [t]
  (cond
    (nil? t)     600
    (number? t)  (long t)
    (string? t)  (long (duration/parse-string-to-seconds t))
    :else        600))


;; Maps trial-id → {key-str → Process} for all actively running scripts.
(defonce ^:private running-procs* (atom {}))
;; Set of trial-ids that have been asked to abort.
(defonce ^:private aborting-trials* (atom #{}))
;; Maps exclusive-resource-name → Clojure agent (for serial execution per resource).
(defonce ^:private exclusive-resource-agents* (atom {}))

(defn abort-trial!
  "Signals abort for trial-id and forcibly destroys all its running processes."
  [trial-id]
  (swap! aborting-trials* conj trial-id)
  (doseq [^Process proc (vals (get @running-procs* trial-id {}))]
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


(defn- env-str-map
  "Normalises an env map to {string -> string} for ProcessBuilder and templates."
  [env-map]
  (into {} (map (fn [[k v]] [(name k) (str v)]) env-map)))


(defn- apply-templates
  "Fixpoint-iterates {{KEY}} substitution in the values of a string-keyed env map.
   Unresolvable references are left as literal {{KEY}}. Stops after 10 passes."
  [str-env]
  (loop [cur str-env n 10]
    (if (zero? n)
      cur
      (let [next (into {} (map (fn [[k v]]
                                 [k (str/replace v #"\{\{(\w+)\}\}"
                                                 (fn [[_ ref]] (get cur ref (str "{{" ref "}}"))))])
                               cur))]
        (if (= cur next) cur (recur next (dec n)))))))


(defn- template-resource-name
  "Applies single-pass {{KEY}} substitution to a resource name string."
  [env-map rn]
  (let [str-env (env-str-map env-map)]
    (str/replace rn #"\{\{(\w+)\}\}"
                 (fn [[_ k]] (get str-env k (str "{{" k "}}"))))))


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
   serializes via a per-resource Clojure agent; otherwise runs in a future.
   The resource name may contain {{KEY}} references resolved against the merged env."
  [trial-id key-str spec env-vars work-dir]
  (if-let [raw-resource (:exclusive_executor_resource spec)]
    (let [merged-env    (merge env-vars (:environment_variables spec))
          resource-name (template-resource-name merged-env raw-resource)
          p             (promise)
          agt           (get-exclusive-agent! resource-name)]
      (send-off agt (fn [_]
                      (deliver p (run-one! trial-id key-str spec env-vars work-dir))
                      nil))
      p)
    (future (run-one! trial-id key-str spec env-vars work-dir))))


(defn- run-one! [trial-id key-str spec env-vars work-dir]
  (try
    (let [timeout-sec (parse-timeout (:timeout spec))
          ;; Merge script-level environment_variables on top of task-level env.
          merged-env  (merge env-vars (:environment_variables spec))
          ;; Normalise to {string->string}; apply {{KEY}} substitution when opted in.
          final-env   (cond-> (env-str-map merged-env)
                        (:template_environment_variables spec) apply-templates)
          log-file    (File. (System/getProperty "java.io.tmpdir")
                             (str "cider-ci-script-" key-str ".log"))
          script-file (File. ^String work-dir (str "cider-ci-" key-str ".sh"))]
      (spit script-file (or (:body spec) ""))
      (.setExecutable script-file true)
      (let [pb   (doto (ProcessBuilder. ["bash" (.getAbsolutePath script-file)])
                   (.directory (File. ^String work-dir))
                   (.redirectErrorStream true)
                   (.redirectOutput (java.lang.ProcessBuilder$Redirect/appendTo log-file)))
            _    (when (seq final-env)
                   (let [env (.environment pb)]
                     (doseq [[k v] final-env]
                       (.put env k v))))
            proc (.start pb)]
        (swap! running-procs* assoc-in [trial-id key-str] proc)
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
            (swap! running-procs* update trial-id dissoc key-str)))))
    (catch Exception e
      {:state "defective"
       :error (.getMessage e)})))


(defn- terminate-script! [trial-id key-str]
  (when-let [^Process proc (get-in @running-procs* [trial-id key-str])]
    (.destroyForcibly proc)))


(defn- terminate-when-satisfied? [spec script-results]
  (when-let [tw (:terminate_when spec)]
    (every? #(start-when-satisfied? % script-results)
            (normalize-start-when tw))))


(defn- await-first
  "Blocks until any entry in in-flight (non-empty {key-str → future|promise}) is
   realized. Returns [key-str result]. Uses realized? to handle both futures
   (normal scripts) and promises (exclusive_executor_resource scripts)."
  [in-flight]
  (loop []
    (if-let [[k f] (some (fn [[k f]] (when (realized? f) [k f])) in-flight)]
      [k @f]
      (do (Thread/sleep 50) (recur)))))


(defn run-all!
  "Executes all scripts in task-spec respecting start_when DAG.
   env-vars is the fully merged environment map (task env-vars + ports etc.).
   Returns {:trial-state \"passed\"|..., :scripts {\"key\" {:state ..., :log-file ...}}}"
  [work-dir task-spec env-vars trial-id]
  (let [scripts     (seq (:scripts task-spec))
        script-specs (into {} (map (fn [[k sv]] [(name k) sv]) (:scripts task-spec)))]
    (if-not scripts
      {:trial-state "defective" :scripts {}}
      (loop [results  (into {} (for [[k _] scripts] [(name k) {:state "pending"}]))
             in-flight {}]
        (cond
          ;; Abort: skip pending, drain in-flight (processes already killed), return aborted
          (aborting? trial-id)
          (let [results (reduce #(assoc-in %1 [(name (first %2)) :state] "skipped")
                                results
                                (filter #(= "pending" (get-in results [(name (first %)) :state])) scripts))]
            (if (seq in-flight)
              (let [[k result] (await-first in-flight)]
                (recur (assoc results k result) (dissoc in-flight k)))
              {:trial-state "aborted" :scripts results}))

          :else
          (let [pending   (filter #(= "pending" (get-in results [(name (first %)) :state])) scripts)
                to-skip   (filter #(unsatisfiable? (name (first %)) (second %) results) pending)
                results   (reduce #(assoc-in %1 [(name (first %2)) :state] "skipped") results to-skip)
                pending   (filter #(= "pending" (get-in results [(name (first %)) :state])) scripts)
                startable (filter #(can-start? (name (first %)) (second %) results) pending)]
            (if (seq startable)
              ;; Dispatch all newly startable; mark "executing" immediately so dependents can see it
              (let [new-futs (into {} (mapv (fn [[k spec]]
                                              [(name k) (dispatch-script! trial-id (name k) spec env-vars work-dir)])
                                            startable))
                    results  (reduce #(assoc-in %1 [(first %2) :state] "executing") results new-futs)]
                (recur results (merge in-flight new-futs)))

              (if (seq in-flight)
                ;; Check terminate_when for all in-flight scripts, then wait for the next one to finish
                (let [_ (doseq [[k _] in-flight
                                :let [spec (get script-specs k)]
                                :when (terminate-when-satisfied? spec results)]
                          (terminate-script! trial-id k))
                      [done-k result] (await-first in-flight)]
                  (recur (assoc results done-k result) (dissoc in-flight done-k)))

                ;; Nothing in-flight: skip any deadlocked pending scripts, then compute final state
                (let [still-pending (filter #(= "pending" (get-in results [(name (first %)) :state])) scripts)]
                  (if (empty? still-pending)
                    (let [non-ignored-states (->> scripts
                                                  (remove #(get (second %) :ignore_state))
                                                  (map #(get-in results [(name (first %)) :state]))
                                                  (filter identity))]
                      {:trial-state (evaluate-states non-ignored-states)
                       :scripts     results})
                    (recur (reduce #(assoc-in %1 [(name (first %2)) :state] "skipped")
                                   results still-pending)
                           in-flight)))))))))))
