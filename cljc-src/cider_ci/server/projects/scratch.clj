(ns cider-ci.server.projects.scratch
  (:refer-clojure :exclude [str keyword resolve])
  (:require
    [cider-ci.server.db.core :as db :refer [get-ds]]
    [cider-ci.server.projects.repositories :as repositories]
    [cider-ci.utils.core :refer [keyword str presence]]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))





(defn doit []
  (let [query (-> (sql/select :*)
                  (sql/from :repositories)
                  (sql-format :inline true))
        project (jdbc/execute-one! (get-ds) query)
        ]
    (info project)
    ; initialized but not yet fetchted
    (repositories/init project)
    ))
