(ns cider-ci.server.resources.users.user.email-addresses
  (:require
    [cider-ci.utils.core :refer [presence]]
    [cider_ci.server.entities.passwords :as passwords]
    [cider_ci.server.entities.users :as users]
    [cuerdas.core :as string]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(defn handler [{{{user-id :user-id} :path-params} :route
                tx :tx :as request}]
  (if-let [user (jdbc/execute-one!
                  tx (-> users/base-query
                         (sql/where [:= :users.id [:cast user-id :uuid]])
                         (sql-format {:inline false})))]
    {:body (:email_addresses user)}
    {:status 404
     :body "user not found"}))
