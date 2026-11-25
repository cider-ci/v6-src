(ns cider-ci.server.resources.scheduled.main
  "The next cron firings over all projects (route :scheduled)."
  (:require
    [cider-ci.server.jobs.cron-trigger :as cron-trigger]
    [clojure.string :as str]))

(def ^:private default-limit 20)
(def ^:private max-limit 500)

(defn- limit [query-string]
  (let [m (into {} (for [kv (str/split (or query-string "") #"&")
                         :let [[k v] (str/split kv #"=" 2)]
                         :when (and k v)]
                     [k v]))
        n (try (Integer/parseInt (get m "limit" "")) (catch Exception _ default-limit))]
    (-> n (max 1) (min max-limit))))

(defn handler [{query-string :query-string tx :tx}]
  (let [n (limit query-string)]
    {:body (assoc (cron-trigger/scheduled tx n) :limit n)}))
