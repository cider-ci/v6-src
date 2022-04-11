(ns cider-ci.server.resources.users.user.html
  (:refer-clojure :exclude [keyword str])
  (:require
    [cider-ci.server.html.utils.forms :as forms]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [cider-ci.server.state :as state]
    [cljs.core.async :refer [go]]
    [cljs.pprint :refer [pprint]]
    [reagent.core :as reagent :refer [reaction]]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(def data* (reagent/atom nil))

(def user-data* (reagent/reaction (get @data* (:path @state/routing*))))

(defn fetch-data [& _]
  (http-client/route-cached-fetch data*))

(defn user-data-component []
  [:div.page-debug
   [:pre.bg-light
    [:code
     (with-out-str (pprint @user-data*))]]])

(defn nav-items []
  [:<>])

(defn page []
  [:div.page
   [state/hidden-routing-state-component
    :did-change #(fetch-data)]
   [:h2.mt-3 "User"]
   [user-data-component]])



