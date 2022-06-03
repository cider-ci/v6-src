(ns cider-ci.server.state
  (:refer-clojure :exclude [keyword str])
  (:require
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [cider-ci.utils.json :as json]
    [cider-ci.server.db.core :refer [get-ds]]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as jdbc-rs]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(def needs-init
  [:case [:exists
          (-> (sql/select true)
              (sql/from :users)
              (sql/where [:= true :is_admin]))] false
   :else true])


(comment (jdbc/execute! (get-ds)
                        (sql-format (sql/select [needs-init :needs_init]))))

(def state-query
  (-> (sql/select :settings.external_base_url)
      (sql/from :settings)
      (sql/select [needs-init :needs_init])))


(defn db-state [ds]
  (jdbc/execute-one! ds (sql-format state-query)))


(comment (db-state (get-ds)))
