(ns cider-ci.server.resources.users.user.main
  (:require
    [cider-ci.utils.core :refer [presence]]
    [cider_ci.server.entities.users :as users]
    [clojure.string :as str]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]))

(def ^:private gpg-keys-sub
  (-> (sql/select
        [[:raw " json_agg(json_build_object('id', id, 'name', name, 'fingerprint', fingerprint, 'source', source) ORDER BY created_at) "]])
      (sql/from :gpg_keys)
      (sql/where [:= :gpg_keys.user_id :users.id])))

(defn- get-user [tx user-id]
  (if-let [user (jdbc/execute-one!
                  tx (-> users/base-query
                         (sql/select [gpg-keys-sub :gpg_keys])
                         (sql/where [:= :users.id [:cast user-id :uuid]])
                         (sql-format {:inline false})))]
    {:body user}
    {:status 404 :body "user not found"}))

(defn- patch-user [tx user-id {login :login uname :name is-admin :is_admin}]
  (when (str/blank? login)
    (throw (ex-info "Login is required" {:status 422})))
  (try
    (let [updated (jdbc/execute-one!
                    tx (sql-format
                         (-> (sql/update :users)
                             (sql/set {:login    login
                                       :name     uname
                                       :is_admin (boolean is-admin)})
                             (sql/where [:= :id [:cast user-id :uuid]])
                             (sql/returning :id))))]
      (if updated
        {:status 200 :body {:id user-id}}
        {:status 404 :body "user not found"}))
    (catch java.sql.SQLException e
      (if (#{"23505" "23514"} (.getSQLState e))
        (throw (ex-info "Login already taken or invalid format" {:status 422}))
        (throw e)))))

(defn- delete-user [tx user-id]
  (let [deleted (jdbc/execute-one!
                  tx (sql-format
                       (-> (sql/delete-from :users)
                           (sql/where [:= :id [:cast user-id :uuid]])
                           (sql/returning :id))))]
    (if deleted
      {:status 200 :body {:id user-id}}
      {:status 404 :body "user not found"})))

(defn handler [{{{user-id :user-id} :path-params} :route
                tx :tx
                request-method :request-method
                body :body}]
  (case request-method
    :get    (get-user tx user-id)
    :patch  (patch-user tx user-id body)
    :delete (delete-user tx user-id)))
