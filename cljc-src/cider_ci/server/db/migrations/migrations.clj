(ns cider-ci.server.db.migrations.migrations
  (:require
    [next.jdbc :as jdbc]
    [cider-ci.server.db.core :as db]
    [clj-yaml.core :as yaml]
    [cider-ci.server.db.migrations.00000-init :as m00000-init]
    [cider-ci.server.db.migrations.00001-users :as m00001-users]
    [cider-ci.server.db.migrations.utils :refer [exec-resource-sql!]]
    [clojure.pprint :refer [pprint]]
    [clojure.java.io :as io]
    [clojure.tools.cli :as cli :refer [parse-opts]]
    [environ.core :refer [env]]
    [taoensso.timbre :refer [debug info warn error]]
    ))


(def migrations
  (sorted-map
    0 {:up (partial exec-resource-sql! "migrations/00000_up.sql")
       :down (partial exec-resource-sql! "migrations/00000_down.sql") }
    1 {:up (partial exec-resource-sql! "migrations/00001_users_up.sql")
       :down (partial exec-resource-sql! "migrations/00001_users_down.sql")}
    2 {:up (partial exec-resource-sql! "migrations/00002_settings_up.sql")
       :down (partial exec-resource-sql! "migrations/00002_settings_down.sql")}
    3 {:up (partial exec-resource-sql! "migrations/00003_passwords_up.sql")
       :down (partial exec-resource-sql! "migrations/00003_passwords_down.sql")}
    ))

(defn available []
  (-> migrations keys
      (->> (into (sorted-set)))))
