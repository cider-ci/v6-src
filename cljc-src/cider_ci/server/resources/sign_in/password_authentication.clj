(ns cider-ci.server.resources.sign-in.password-authentication
  (:require
   [cider-ci.utils.core :refer [presence]]
   [cider_ci.server.entities.passwords :refer [password-authenticated-user]]
   [cider_ci.server.entities.sessions :as sessions]
   [honey.sql :refer [format] :rename {format sql-format}]
   [honey.sql.helpers :as sql]
   [next.jdbc :as jdbc]
   [taoensso.timbre :refer [debug info warn error]])
  (:import [java.time Instant Duration]))

(defn handler [{{login :login password :password} :body
                {{route-name :name} :data} :route
                method :request-method
                tx :tx :as request}]
  (assert (= route-name :sign-in-authenticate-password) "Expected password-authentication route")
  (assert (presence login) "Expected non empty login")
  (assert (presence password) "Expected non empty password")
  (if-let [user (password-authenticated-user tx login password)]
    (let [session (sessions/create user request)]
      (def valid-until (:valid_until session))
      {:cookies {sessions/COOKIE-NAME
                 {:value (:token session)
                  :http-only true
                  :max-age (.getSeconds
                            (Duration/between
                             (Instant/now)
                             (:valid_until session)))
                  :path "/"}}
       :body (dissoc session :token :token_digest)})
    {:status 403}))



