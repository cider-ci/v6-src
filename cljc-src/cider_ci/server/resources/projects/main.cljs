(ns cider-ci.server.resources.projects.main
  (:refer-clojure :exclude [keyword str])
  (:require
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.html.utils.forms :as forms]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [cider-ci.server.state :as state]
    [cljs.core.async :refer [go]]
    [cljs.pprint :refer [pprint]]
    [reagent.core :as reagent :refer [reaction]]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))




(defn page []
  [:div.page
   [:h2 [icons/projects] " Projects"]
   ])


(def components {:page page})
