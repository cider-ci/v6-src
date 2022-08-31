(ns cider-ci.server.db.settings
  (:refer-clojure :exclude [str keyword])
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.utils.daemon :as daemon :refer [defdaemon]]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [logbug.thrown :as thrown]
    [next.jdbc :as jdbc]
    [tick.core :refer [now]]
    [taoensso.timbre :refer [debug info warn error spy]]))


(defn- query-settings []
  (-> (sql/from :settings)
      (sql/select :*)
      (sql-format)
      (#(jdbc/execute-one! (get-ds) %))
      (assoc :refreshed-at (now))))


(def get-settings
  (clojure.core.memoize/ttl query-settings :ttl/threshold (* 10 1000)))


(defn wrap [handler]
  (fn [request]
    (handler (assoc request :settings (get-settings)))))
