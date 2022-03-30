(ns cider-ci.server.resources.sign-in.password-authentication
  (:require
    [cider-ci.utils.core :refer [presence]]
    [cider_ci.server.db.tables.passwords :as passwords]
    [honey.sql :refer [format] :rename {format sql-format}]
    [cider_ci.server.db.tables.passwords :refer [password-authenticated-user]]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error]]
    ))


(defn handler [{{email :email password :password} :body
                {{route-name :name} :data} :route
                method :request-method
                tx :tx :as request}]
  (assert (= route-name :sign-in-authenticate-password) "Expected password-authentication route")
  (assert (presence email) "Expected non empty email")
  (assert (presence password) "Expected non empty password")
  (if-let [user (password-authenticated-user tx email password)]
    (do (info "create session")
        {:body {:user user}})
    {:status 403}))
