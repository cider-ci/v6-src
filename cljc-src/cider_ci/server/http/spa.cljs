(ns cider-ci.server.http.spa
  (:refer-clojure :exclude [keyword str])
  (:require
    [cider-ci.server.state :refer [routing-state*]]
    [reagent.dom :as rdom]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(defn not-found-page []
  [:div.page
   [:h1.text-danger "Page Not-Found"]
   ])

(defn html []
  [:div.container
   (if-let [page (:page @routing-state*)]
     [page]
     [not-found-page])])

(defn mount []
  (info "mounting application")
  (when-let [app (.getElementById js/document "app")]
    (rdom/render [html] app))
  (info "mounted application"))
