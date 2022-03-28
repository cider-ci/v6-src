(ns cider-ci.server.resources.init.http
  (:require
    [cider-ci.utils.core :refer [presence]]
    [cider_ci.server.db.tables.passwords :as passwords]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error]]
    ))

(defn assert-no-admin [tx]
  (assert (empty? (-> (sql/select 1)
                      (sql/from :users)
                      (sql/where [:= :users.is_admin true])
                      (sql-format)
                      (#(jdbc/execute! tx %))))
          "Expected no existing admin"))


(defn insert-admin [email tx]
  (jdbc/execute-one! tx
                     (-> (sql/insert-into :users)
                         (sql/values [{:email email
                                       :is_admin true}])
                         (sql-format {:inline true :pretty true}))
                     {:return-keys true}))

(defn handler [{{{email :email password :password} :initial_admin} :body
                {{route-name :name} :data} :route
                method :request-method
                tx :tx :as request}]
  (assert (= method :put) "Expected put request")
  (assert (= route-name :init) "Expected init route")
  (assert (presence email) "Expected non empty email")
  (assert (presence password) "Expected non empty password")
  (assert-no-admin tx)
  (let [admin (insert-admin email tx)
        pw (passwords/upsert tx password (:id admin))]
    (assert admin)
    (assert pw)
    {:status 204}))
