(ns cider-ci.server.resources.commits.main
  (:refer-clojure :exclude [keyword str])
  (:require
    ["date-fns" :as date-fns]
    ["react-bootstrap" :as bs]
    [cider-ci.utils.core :refer [str keyword]]
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.html.utils.forms :as forms]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [cider-ci.server.state :as state :refer [routing*] :rename {routing* routing-state*}]
    [cljs.core.async :refer [go]]
    [cljs.pprint :refer [pprint]]
    [reagent.core :as reagent :refer [reaction]]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(defonce data* (reagent/atom {}))

(defn commits []
  [:div.commits
   [state/hidden-routing-state-component
    :did-change #(http-client/route-cached-fetch
                   data* :reload true :reload-delay (* 3 1000))]
   [:h2 [icons/commits] " Commits"]
   ])


(defn page-nav []
  [:<>])


(defn page []
  [:div.page
   [commits] ])

(def components {:page page
                 :page-nav page-nav})
