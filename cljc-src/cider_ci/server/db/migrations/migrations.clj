(ns cider-ci.server.db.migrations.migrations
  (:require
    [next.jdbc :as jdbc]
    [cider-ci.server.db.core :as db]
    [clj-yaml.core :as yaml]
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
    1 {:up (partial exec-resource-sql! "migrations/00001_settings_up.sql")
       :down (partial exec-resource-sql! "migrations/00001_settings_down.sql")}
    2 {:up (partial exec-resource-sql! "migrations/00002_users_up.sql")
       :down (partial exec-resource-sql! "migrations/00002_users_down.sql")}
    10 {:up (partial exec-resource-sql! "migrations/00010_git_up.sql")
        :down (partial exec-resource-sql! "migrations/00010_git_down.sql")}
    11 {:up (partial exec-resource-sql! "migrations/00011_gpg_up.sql")
        :down (partial exec-resource-sql! "migrations/00011_gpg_down.sql")}
    ))

(defn available []
  (-> migrations keys
      (->> (into (sorted-set)))))
