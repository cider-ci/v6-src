(ns cider-ci.server.http.authorization
  (:require
    [cider-ci.utils.core :refer [presence]]
    [cider-ci.server.http.core :refer [HTTP_SAVE_METHODS HTTP_UNSAVE_METHODS]]
    [cider_ci.server.entities.passwords :refer [password-authenticated-user]]
    [cider_ci.server.entities.sessions :as sessions]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error]]
    ))

(defn check-admin [request]
  (-> request :user :is_admin))

(defn check-self [{{{user-id :user-id} :path-params} :route
                   {id :id} :user :as request}]
  (= (str user-id) (str id)))

(defn check [request auth]
  (info 'check auth request)
  (case auth
    :admin (check-admin request)
    :self (check-self request)))

(defn check! [auths request]
  (or (some (partial check request) auths)
      (throw (ex-info "Authorization not satisfied" {:status 403}))))

(defn wrap [handler]
  (fn [{request-method :request-method
        {{auth-read :auth-read auth-write :auth-write} :data} :route
        :as request}]
    (debug request)
    (condp contains? request-method
      HTTP_SAVE_METHODS (check! auth-read request)
      HTTP_UNSAVE_METHODS (check! auth-write request))
    (handler request)))
