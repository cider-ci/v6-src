(ns cider-ci.server.routing
  (:require
    [cider-ci.server.routes]
    [taoensso.timbre :refer [debug info warn error]]
    ))


(defn not-found-handler [request]
  {:status 404
   :body "Not Found"})

(defn build-routes [options]
  (-> not-found-handler

      ))


(defn init [options]
  (info "initializing routing " options)
  (let [routes (build-routes options)]
    (info "initialized routing")
    routes))
