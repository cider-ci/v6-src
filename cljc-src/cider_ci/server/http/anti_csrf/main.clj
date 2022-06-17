(ns cider-ci.server.http.anti-csrf.main
  (:refer-clojure :exclude [str keyword])
  (:require
    [madek.media-service.authentication.token :refer [find-token-secret-in-header]]
    [madek.media-service.utils.core :refer [keyword presence str]]
    [madek.media-service.utils.http.shared :refer [ANTI_CRSF_TOKEN_COOKIE_NAME HTTP_UNSAFE_METHODS HTTP_SAFE_METHODS]]
    [logbug.debug]
    )
  (:import
    [java.util UUID]
    ))

(defn http-safe? [request]
  (boolean (-> request :request-method HTTP_SAFE_METHODS)))

(def http-unsafe? (complement http-safe?))

(defn not-authenticated? [request]
  (-> request
      :authenticated-entity
      boolean not))

(defn- token-in-header? [request]
  (-> request
      find-token-secret-in-header))

(defn x-csrf-token! [request]
  (or (some-> request (get-in [:headers ANTI_CRSF_TOKEN_COOKIE_NAME]) presence)
      (some-> request :form-params :csrf-token presence)
      (throw (ex-info "The x-csrf-token has not been sent!" {:status 403}))))

(defn anti-csrf-token [request]
  (-> request :cookies
      (get ANTI_CRSF_TOKEN_COOKIE_NAME nil)
      :value presence))

(defn anti-csrf-middleware [handler request]
  (let [anti-csrf-token (anti-csrf-token request)]
    (when (and (http-unsafe? request) (not token-in-header?))
      (when-not (presence anti-csrf-token)
        (throw (ex-info "The anti-csrf-token cookie value is not set." {:status 403})))
      (when-not (= anti-csrf-token (x-csrf-token! request))
        (throw (ex-info (str "The x-csrf-token is not equal to the "
                             "anti-csrf cookie value.") {:status 403}))))
    (let [response (handler request)]
      (if (and (not anti-csrf-token)
               (some-> response :headers (get "Content-Type") (clojure.string/starts-with? "text/html")))
        (assoc-in response [:cookies ANTI_CRSF_TOKEN_COOKIE_NAME]
                  {:value (str (UUID/randomUUID))
                   :http-only false
                   :path "/"
                   :secure false})
        response))))

(defn wrap [handler]
  (fn [request]
    (anti-csrf-middleware handler request)))

(logbug.debug/debug-ns *ns*)
