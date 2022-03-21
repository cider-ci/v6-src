(ns cider-ci.server.resources.root
  (:refer-clojure :exclude [keyword str])
  (:require
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(defn page []
  [:div
   [:h2.mt-3 "Welcome to Cider-CI"]
   ])


