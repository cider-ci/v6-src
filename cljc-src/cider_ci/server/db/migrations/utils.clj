(ns cider-ci.server.db.migrations.utils
  (:require
    [next.jdbc :as jdbc]
    [cider-ci.server.db.core :as db]
    [clj-yaml.core :as yaml]
    [clojure.pprint :refer [pprint]]
    [clojure.java.io :as io]
    [clojure.tools.cli :as cli :refer [parse-opts]]
    [environ.core :refer [env]]
    [taoensso.timbre :refer [debug info warn error]]
    ))

(defn resource-sql [path]
  (-> path io/resource slurp))

(defn exec-resource-sql! [path ds]
  (jdbc/execute! ds [(resource-sql path)]))


