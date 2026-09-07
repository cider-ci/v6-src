(ns cider-ci.server.resources.users.main
  (:require
    [cider_ci.server.entities.passwords :as passwords]
    [cider_ci.server.entities.users :as users]
    [clojure.string :as str]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]))

(defn- list-users [tx]
  {:body (jdbc/execute!
           tx (-> users/base-query
                  (sql-format {:inline false})))})

(defn- create-user [tx {login :login uname :name is-admin :is_admin password :password}]
  (when (str/blank? login)
    (throw (ex-info "Login is required" {:status 422})))
  (when (str/blank? password)
    (throw (ex-info "Password is required" {:status 422})))
  (try
    (let [res (-> (sql/insert-into :users)
                  (sql/values [{:login     login
                                :name      uname
                                :is_admin  (boolean is-admin)}])
                  (sql/returning :*)
                  (sql-format)
                  (#(jdbc/execute-one! tx %)))]
      (when-not (:id res)
        (throw (ex-info "User creation failed" {:status 422})))
      (passwords/upsert tx password (:id res))
      {:body res})
    (catch java.sql.SQLException e
      (if (#{"23505" "23514"} (.getSQLState e))
        (throw (ex-info "Login already taken or invalid format" {:status 422}))
        (throw e)))))

(defn handler [{tx :tx request-method :request-method data :body :as request}]
  (case request-method
    :get  (list-users tx)
    :post (create-user tx data)))
