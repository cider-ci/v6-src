(ns cider-ci.server.http.authorization
  (:require
   [cider-ci.utils.core :refer [presence]]
   [cider-ci.server.http.core :refer [HTTP_SAFE_METHODS HTTP_UNSAFE_METHODS]]
   [cider_ci.server.entities.passwords :refer [password-authenticated-user]]
   [cider_ci.server.entities.sessions :as sessions]
   [honey.sql :refer [format] :rename {format sql-format}]
   [honey.sql.helpers :as sql]
   [next.jdbc :as jdbc]
   [taoensso.timbre :refer [debug info warn error spy]]
   [logbug.debug :as debug]
   [ring.util.request :as req]))

(defn check-admin [request]
  (debug 'check-admin request)
  (debug (-> request :user :is_admin))
  (-> request :user :is_admin))

(defn check-self [{{{user-id :user-id} :path-params} :route
                   {id :id} :user :as request}]
  (= (str user-id) (str id)))

(defn check-user [request]
  (-> request :user empty? not))


(defn check [request auth]
  (debug 'check auth request)
  (case auth
    :public true
    :user (check-user request)
    :admin (check-admin request)
    :self (check-self request)))

(defn check! [auths request]
  (debug 'check! {:auths auths :request request})
  (or (some (partial check request) auths)
      (throw (ex-info "Authorization not satisfied" {:status 403}))))

(defn wrap [handler]
  (fn [{request-method :request-method
        {{auth-http-safe :auth-http-safe
          auth-http-unsafe :auth-http-unsafe} :data} :route
        :as request}]
    (debug request)
    (condp contains? request-method
      HTTP_SAFE_METHODS (check! auth-http-safe request)
      HTTP_UNSAFE_METHODS (check! auth-http-unsafe request))
    (handler request)))
