(ns cider-ci.server.routing
  (:require
    [cider-ci.server.http.spa :as spa]
    [cider-ci.server.http.static-resources :as static-resources]
    [cider-ci.server.db.core :refer [wrap-tx]]
    [cider-ci.server.routes]
    [ring.middleware.accept]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(def options* (atom nil))

(defn not-found-handler [request]
  {:status 404
   :body "Not Found"})

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
         (catch Exception e
           (error e)
           {:status 500
            :body "Internal Server Error"}))))

(defn build-routes [options]
  (-> not-found-handler
      spa/wrap
      wrap-tx
      wrap-accept
      static-resources-wrap
      wrap-catch))


(defn init [options]
  (info "initializing routing " options)
  (reset! options* options)
  (let [routes (build-routes options)]
    (info "initialized routing")
    routes))
