(ns cider-ci.server.state
  (:require
    [reagent.core :as reagent]
    [cljs.pprint :refer [pprint]]
    [cider-ci.server.html.utils.dom :as dom]
    [taoensso.timbre :refer [debug info warn error spy]])
  (:require-macros
    [reagent.ratom :as ratom :refer [reaction]]))


(def routing* (reagent/atom {}))

(def debug?* (reagent/atom false))

(def server* (reagent/atom {}))

(def state* (reaction
                  {:debug @debug?*
                   :routing @routing*
                   :server @server*
                   }))

(defn debug-ui-component []
  [:div.debug.state-debug
   (when @debug?*
     [:<>
      [:hr]
      [:h4 "Debug global " [:code "@state*"]]
      [:pre.bg-light
       [:code
        (with-out-str (pprint @state*))
        ]]])])


(defn init []
  (info "initializing state ...")
  (info (dom/data-attribute "body" "server-state"))
  (swap! server* merge (dom/data-attribute "body" "server-state"))
  (info "initialized state")
  )
