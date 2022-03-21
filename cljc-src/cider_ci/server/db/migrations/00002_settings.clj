(ns cider-ci.server.db.migrations.00002-settings
  (:require
    [cider-ci.server.db.core :as db]
    [clojure.java.io :as io]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error]]
    ))

(defn up [ds]
  (-> "migrations/00002_settings_up.sql"
      io/resource
      slurp
      (->> (jdbc/execute! ds))))

(defn down [ds]
  (-> "migrations/00002_settings_down.sql"
      io/resource
      slurp
      (->> (jdbc/execute! ds))))
