(ns cider-ci.server.db.settings
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.utils.daemon :as daemon :refer [defdaemon]]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [logbug.thrown :as thrown]
    [next.jdbc :as jdbc]
    [clojure.core.memoize :as memoize]
    [tick.core :refer [now]]
    [taoensso.timbre :refer [debug info warn error spy]]))


(defn- query-settings []
  (-> (sql/from :settings)
      (sql/select :*)
      (sql-format)
      (#(jdbc/execute-one! (get-ds) %))
      (assoc :refreshed-at (now))))


(def get-settings
  (memoize/ttl query-settings :ttl/threshold (* 10 1000)))


(defn get-setting! [k]
  (or (contains? (get-settings) k)
      (throw (ex-info (str "Key " k " not present in settings.") {})))
  (get (get-settings) k))


(defn wrap [handler]
  (fn [request]
    (handler (assoc request :settings (get-settings)))))
