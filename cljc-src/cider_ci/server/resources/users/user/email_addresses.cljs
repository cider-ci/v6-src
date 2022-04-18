(ns cider-ci.server.resources.users.user.email-addresses
  (:refer-clojure :exclude [keyword str])
  (:require
    ["react-bootstrap" :as bs]
    [cider-ci.utils.core :refer [str]]
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

(defonce _data* (reagent/atom nil))

(def data* (reagent/reaction (get @_data* (:path @state/routing*))))

(defn fetch-data [& _]
  (http-client/route-cached-fetch _data*))

(defn page []
  [:div.page
   [state/hidden-routing-state-component
    :did-change #(fetch-data)]
   [:h2 "Email addresses"]
   [:ol.list-group
    (for [row @data*]
      (let [email (:email row)]
        ;^:key row
        [:li.list-group-item.flex-column
         [:div.d-flex.justify-content-between
          [:div [:a {:href (str "mailto:" email)}
                 [:span [icons/email] " " email]]]
          [:div
           [:div.btn-group
           [:button.btn.btn-sm.btn-primary
            {:disabled (:is_primary row)}
            "Set as primary"]
           [:button.btn.btn-sm.btn-warning
            [icons/delete] " Delete"]]]]])
      )]
   [:pre.bg-light
    [:code
     (with-out-str (pprint @_data*))
     ]]
   [:pre.bg-light
    [:code
     (with-out-str (pprint @data*))]]])

(def components
  {:page page})
