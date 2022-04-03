(ns cider-ci.server.routing
  (:require
    [cider-ci.server.db.core :refer [wrap-tx]]
    [cider-ci.server.html.spa :as spa]
    [cider-ci.server.html.static-resources :as static-resources]
    [cider-ci.server.http.authentication :as authentication]
    [cider-ci.server.routing-resolver :as routing-resolver]
    [logbug.debug :as debug :refer [I>]]
    [logbug.ring :refer [wrap-handler-with-logging]]
    [ring.middleware.accept]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.cookies]
    [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(def options* (atom nil))

(defn not-found-handler [request]
  {:status 404
   :body "Not Found"})

(defn wrap-resource-dispatch [handler]
  (fn [request]
    (if-let [route-handler (:route-handler request)]
      (route-handler request)
      (handler request))))


(defn wrap-accept [handler]
  (ring.middleware.accept/wrap-accept
    handler
    {:mime
     ["application/json" :qs 1 :as :json
      "image/apng" :qs 0.8 :as :apng
      "text/css" :qs 1 :as :css
      "text/html" :qs 1 :as :html]}))


(defn static-resources-wrap [handler]
  (static-resources/wrap
    handler
    "" {:allow-symlinks? true
        :cache-bust-paths []
        :never-expire-paths
        [#".*[^\/]*\d+\.\d+\.\d+.+"  ; match semver in the filename
         #".+\.[0-9a-fA-F]{32,}\..+"] ; match MD5, SHAx, ... in the filename
        :cache-enabled? (->  @options* :dev-mode not)}))

(defn wrap-catch [hander]
  (fn [request]
    (try (hander request)
         (catch java.lang.AssertionError a
           (warn (.getMessage a))
           {:status 422
            :body (.getMessage a)})
         (catch Exception e
           (error e)
           {:status 500
            :body "Internal Server Error"}))))

(defn wrap-debug [hander]
  (fn [request]
    (debug 'wraped-handler hander)
    (debug 'request request)
    (let [res (hander request)]
      (debug 'res res)
      res)))

(defn build-routes [options]
  (-> not-found-handler
      wrap-resource-dispatch
      (wrap-json-body {:keywords? true})
      wrap-json-response
      spa/wrap
      routing-resolver/wrap
      ; wrap-debug
      authentication/wrap
      ; wrap-debug
      ring.middleware.cookies/wrap-cookies
      wrap-tx
      wrap-accept
      static-resources-wrap
      wrap-catch
      wrap-content-type))


(defn init [options]
  (info "initializing routing " options)
  (reset! options* options)
  (let [routes (build-routes options)]
    (info "initialized routing")
    routes))
