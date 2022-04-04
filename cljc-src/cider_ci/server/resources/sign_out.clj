(ns cider-ci.server.resources.sign-out
  (:require
    [cider-ci.utils.core :refer [presence]]
    [cider_ci.server.entities.passwords :refer [password-authenticated-user]]
    [cider_ci.server.entities.sessions :as sessions]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error]]
    )
  (:import [java.time Instant Duration]))

(defn handler [{{session-id :session_id} :user
                tx :tx :as request}]
  (when session-id (sessions/delete session-id tx))
  {:cookies {sessions/COOKIE-NAME
             {:value nil
              :http-only true
              :max-age 0
              :path "/"}}
   :body {}})



