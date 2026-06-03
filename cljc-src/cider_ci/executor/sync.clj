(ns cider-ci.executor.sync
  (:require
    [cider-ci.executor.trials :as trials]
    [cheshire.core :as json]
    [org.httpkit.client :as http-client]
    [taoensso.timbre :refer [info warn]]))


(defn- do-sync! [{:keys [server-url token max-load] :as opts}]
  (try
    (let [resp @(http-client/post
                  (str server-url "/executor/sync")
                  {:headers {"Authorization" (str "Bearer " token)
                             "Content-Type"  "application/json"
                             "Accept"        "application/json"}
                   :body    (json/generate-string {:available_load (double max-load)
                                                   :trials         []})
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
