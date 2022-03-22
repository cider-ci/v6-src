(ns cider-ci.server.html.spa-page
  (:refer-clojure :exclude [keyword str])
  (:require
    ["react-bootstrap" :as bs]
    [cider-ci.server.routes :refer [path]]
    [cider-ci.server.state :as state :refer []]
    [reagent.dom :as rdom]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(defn header []
  [:> bs/Navbar {:bg :light}
   [:> bs/Container {}
    [:> bs/Navbar.Brand {:href (path :root)} "Cider-CI"]
    ]])


(defn footer []
  [:> bs/Navbar {:bg :light}
   [:> bs/Navbar.Collapse {:class_name "justify-content-end"}]
   [:> bs/Form {:inline true :class "px-2"}
    [:> bs/Form.Group {:control-id "debug"}
     [:> bs/Form.Check {:type "checkbox" :label "Debug"
                        :checked @state/debug?*
                        :on-change #(swap! state/debug?* (fn [b] (not b)))}]]]])
