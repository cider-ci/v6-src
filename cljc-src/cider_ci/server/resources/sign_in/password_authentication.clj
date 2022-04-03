(ns cider-ci.server.resources.sign-in.password-authentication
  (:require
    [cider-ci.utils.core :refer [presence]]
    [cider_ci.server.entities.passwords :refer [password-authenticated-user]]
    [cider_ci.server.entities.sessions :as sessions]
    [honey.sql :refer [format] :rename {format sql-format}]
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
    (let [session-token (sessions/create user request)]
      {:cookies {sessions/COOKIE-NAME
                 {:value session-token
                  :http-only true
                  :max-age (* 10 356 24 60 60)
                  :path "/"}}
       :body {:session-token session-token}})
    {:status 403}))
