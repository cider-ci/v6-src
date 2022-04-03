(ns cider-ci.server.http.authentication
  (:require
    [cider-ci.utils.core :refer [presence]]
    [cider_ci.server.entities.passwords :refer [password-authenticated-user]]
    [cider_ci.server.entities.sessions :as sessions]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error]]
    ))


(defn wrap [handler]
  (fn [{tx :tx :as  request}]
    (if-let [user (some->> [:cookies sessions/COOKIE-NAME :value]
                           (get-in request)
                           (sessions/valid-session-user tx))]
      (handler (assoc request :user user)))
    (handler request)))
