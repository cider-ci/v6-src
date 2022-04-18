(ns cider-ci.server.resources.users.user.main
  (:refer-clojure :exclude [keyword str])
  (:require
    ["react-bootstrap" :as bs]
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

(def data* (reagent/atom nil))

(def user-data* (reagent/reaction (get @data* (:route @state/routing*))))

(defn fetch-data [& _]
  (http-client/route-cached-fetch data*))

(defn user-data-component []
  [:div.page-debug
   [icons/password]
   [:pre.bg-light
    [:code
     (with-out-str (pprint @data*))]]
   [:pre.bg-light
    [:code
     (with-out-str (pprint @user-data*))]]])


(defn center-nav []
  [:<>
   [:> bs/Navbar.Collapse  {:class "justify-content-center"}
    [:> bs/Nav.Item
     [:> bs/Nav.Link {:href (path :user
                                  {:user-id (:id @user-data*)})}
      "Me"]]
    [:> bs/Nav.Item
     [:> bs/Nav.Link {:class "btn btn-outline-secondary btn-sm"
                      :href (path :user-password
                                  {:user-id (:id @user-data*)})}

      [:span [icons/password] " " "Reset password"]]]
    [:> bs/Nav.Item
     [:> bs/Nav.Link {:class "btn btn-outline-secondary btn-sm"
                      :href (path :user-email-addresses
                                  {:user-id (:id @user-data*)})}
      [:span [icons/password] " " "Manage email-addresses"]]]]])



(defn page []
  [:div.page
   [state/hidden-routing-state-component
    :did-change #(fetch-data)]
   [:h2.mt-3 "User"]
   [user-data-component]])


(def components {:page page
                 :center-nav center-nav})


