(ns cider-ci.server.state
  (:refer-clojure :exclude [keyword str])
  (:require
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [cider-ci.server.db.core :refer [ds*]]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(def state-query
  (sql/select
    [(-> (sql/select :true)
         (sql/from :users)
         (sql/where [:= true :is_admin]))
     :admin_exists]))


(defn db-state [ds]
  (jdbc/execute! ds (sql-format state-query)))


(comment (jdbc/execute! @ds* ["SELECT true"]))

(comment (jdbc/execute! @ds* ["SELECT (SELECT true FROM users WHERE TRUE = is_admin) AS admin_exists"]))

(comment (db-state @ds*))
