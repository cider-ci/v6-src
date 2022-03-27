(ns cider-ci.server.db.migrations.00003-passwords
  (:require
    [cider-ci.server.db.core :as db]
    [clojure.java.io :as io]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error]]
    ))

(defn up [ds]
  (-> "migrations/00003_passwords_up.sql"
      io/resource
      slurp
      (->> (jdbc/execute! ds))))

(defn down [ds]
  (-> "migrations/00003_passwords_down.sql"
      io/resource
      slurp
      (->> (jdbc/execute! ds))))
