(ns cider-ci.server.http.spa-page
  (:refer-clojure :exclude [keyword str])
  (:require
    ["react-bootstrap" :as bs]
    [cider-ci.server.routes :refer [path]]
    [cider-ci.server.state :refer []]
    [reagent.dom :as rdom]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(defn header []
  [:> bs/Navbar {:bg :light :expand :lg}
   [:> bs/Container {}
    [:> bs/Navbar.Brand {:href (path :root)} "Cider-CI"]
    [:text "Hallo Helena!!!!!"]
    [:text "Velofahren jetzt !"]

    ]])



