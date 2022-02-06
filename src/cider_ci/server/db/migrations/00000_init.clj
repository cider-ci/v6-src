(ns cider-ci.server.db.migrations.00000-init
  (:require
    [cider-ci.server.db.core :as db]
    [clojure.java.io :as io]
    [next.jdbc :as jdbc]
    [cider-ci.server.db.migrations.utils :refer [exec-resource-sql]]
    [taoensso.timbre :refer [debug info warn error]]
    ))

(defn up [ds]
  (exec-resource-sql! ds ))

(defn down [ds]
  (exec-resource-sql! ds "migrations/00000_down.sql"))

