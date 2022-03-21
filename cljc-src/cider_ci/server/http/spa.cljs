(ns cider-ci.server.http.spa
  (:refer-clojure :exclude [keyword str])
  (:require
    [cider-ci.server.http.spa-page :refer [header]]
    [cider-ci.server.state :as state]
    [reagent.dom :as rdom]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(defn not-found-page []
  [:div.page
   [:h1.text-danger "Page Not-Found"]
   ])

(defn html []
  [:div.container
   [header]
   (if-let [page (:page @state/routing*)]
     [page]
     [not-found-page])
   [state/debug-ui-component]
   ])

(defn mount []
  (info "mounting application")
  (when-let [app (.getElementById js/document "app")]
    (rdom/render [html] app))
  (info "mounted application"))
