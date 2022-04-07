(ns cider-ci.server.resources.init.http
  (:require
    [cider-ci.utils.core :refer [presence]]
    [cider_ci.server.entities.users :as users]
    [cider_ci.server.entities.passwords :as passwords]
    [cuerdas.core :as string]
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


(defn email? [s] (string/includes? s "@"))

(defn create-admin [tx uid password]
  (let [admin (jdbc/execute-one!
                tx (-> (sql/insert-into :users)
                       (sql/values [{:login (when-not (email? uid) uid)
                                     :is_admin true}])
                       sql-format)
                {:return-keys true})]
    (when (email? uid)
      (jdbc/execute-one!
        tx (-> (sql/insert-into :email_addresses)
               (sql/values [{:email uid
                             :is_primary true
                             :user_id (:id admin)}])
               sql-format)))
    (assert (passwords/upsert tx password (:id admin)))
    (jdbc/execute-one! tx (-> users/base-query
                              (sql/where [:= :users.id (:id admin)])
                              sql-format))))

(defn handler [{{{email-or-login :email_or_login password :password} :initial_admin} :body
                {{route-name :name} :data} :route
                method :request-method
                tx :tx :as request}]
  (assert (= method :put) "Expected put request")
  (assert (= route-name :init) "Expected init route")
  (assert (presence email-or-login) "Expected non empty email-or-login")
  (assert (presence password) "Expected non empty password")
  (assert-no-admin tx)
  (let [admin (create-admin tx email-or-login password)]
    (assert admin)
    {:body admin}))
