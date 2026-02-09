(ns cider-ci.server.resources.users.main
  (:require
    [cider_ci.server.entities.users :as users]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]))

(defn handler [{tx :tx :as request}]
  (let [users (jdbc/execute!
                tx (-> users/base-query
                       (sql-format {:inline false})))]
    {:body users}))