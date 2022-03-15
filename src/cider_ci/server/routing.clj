(ns cider-ci.server.routing
  (:require
    [cider-ci.server.http.spa :as spa]
    [cider-ci.server.routes]
    [ring.middleware.accept]
    [taoensso.timbre :refer [debug info warn error]]
    ))


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


(defn build-routes [options]
  (-> not-found-handler
      spa/wrap
      wrap-accept
      ))


(defn init [options]
  (info "initializing routing " options)
  (let [routes (build-routes options)]
    (info "initialized routing")
    routes))
