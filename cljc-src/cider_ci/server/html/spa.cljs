(ns cider-ci.server.html.spa
  (:refer-clojure :exclude [keyword str])
  (:require
   [cider-ci.server.html.spa-page :refer [header page-nav footer]]
   [cider-ci.server.http.client.modals :as http-client-modals]
   [cider-ci.server.routes :as routes :refer [path navigate!]]
   [cider-ci.server.state :as state]
   [cljs.pprint :refer [pprint]]
   [reagent.dom.client :as rdom]
   [taoensso.timbre :refer [debug info warn error spy]]))

(defn not-found-page []
  [:div.page
   [:h1.text-danger "Page Not-Found"]])

(defn html []
  [:div.container
   [header]
   [http-client-modals/modal-component]
   [:div.mt-3
    (if-let [page (:page @state/routing*)]
      [:<>
       [page-nav]
       [page]]
      [not-found-page])]
   [state/debug-ui-component]
   [:div.debug.router-debug
    (when @state/debug?*
      [:<>
       [:hr]
       [:h4 "Routes "]
       [:pre.bg-light
        [:code
         (with-out-str (pprint routes/routes-flattened))]]])]
   [footer]])

(defonce root (atom nil))

(defn mount []
  (info "mounting application")
  (when-let [app (.getElementById js/document "app")]
    (when-not @root
      (reset! root (rdom/create-root app)))
    (rdom/render @root [html]))
  (info "mounted application"))
