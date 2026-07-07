(ns cider-ci.executor.sync
  (:require
    [cider-ci.executor.trials :as trials]
    [cheshire.core :as json]
    [clojure.string :as str]
    [org.httpkit.client :as http-client]
    [taoensso.timbre :refer [info warn]]))


(def ^:private traits-file "/etc/cider-ci/traits")

(defn- read-traits []
  (try
    (->> (str/split-lines (slurp traits-file))
         (map str/trim)
         (remove str/blank?))
    (catch Exception _
      ["bash"])))


(defn- do-sync! [{:keys [server-url token max-load] :as opts}]
  (try
    (let [active-ids     (trials/active-trial-ids)
          used-load      (trials/active-load)
          available-load (max 0.0 (- (double max-load) used-load))
          resp           @(http-client/post
                            (str server-url "/executor/sync")
                            {:headers {"Authorization" (str "Bearer " token)
                                       "Content-Type"  "application/json"
                                       "Accept"        "application/json"}
                             :body    (json/generate-string {:available_load         available-load
                                                             :traits                 (read-traits)
                                                             :trials_being_processed active-ids})
                             :timeout 10000})]
      (if (= 200 (:status resp))
        (let [body (json/parse-string (:body resp) true)]
          (doseq [trial (:trials_to_execute body)]
            (future (trials/execute! trial opts))))
        (warn "Sync returned HTTP status" (:status resp) (:body resp))))
    (catch Exception e
      (warn "Sync error:" (.getMessage e)))))


(defn start! [opts]
  ;; println goes to stdout so the spec's log-file poll can detect startup.
  (println "Executor sync loop starting")
  (info "Executor sync loop starting, server:" (:server-url opts)
        "token-prefix:" (subs (or (:token opts) "") 0 (min 8 (count (or (:token opts) "")))))
  (loop []
    (do-sync! opts)
    (Thread/sleep 2000)
    (recur)))
