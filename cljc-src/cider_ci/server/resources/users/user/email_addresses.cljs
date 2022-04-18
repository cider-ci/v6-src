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


(defn delete [row]
  (warn "TODO delete " row)

  )

(defn debug-component []
  (if @state/debug?*
    [:div.debug
     [:hr]
     [:h3 "Page Debug"]
     [:pre.bg-light
      [:code
       (with-out-str (pprint @_data*))]]]
    [:<>]))


(defn email-addresses-component []
  [:ol.list-group
   (for [row @data*]
     (let [email (:email row)]
       ^{:key (:email row)}
       [:li.list-group-item.flex-column
        [:div.d-flex.justify-content-between
         [:div [:a {:href (str "mailto:" email)}
                [:span [icons/email] " " email]]]
         [:div
          [:div.btn-group
           [:form
            [:button.btn.btn-sm.btn-secondary
             {:type :submit
              :disabled (:is_primary row)}
             "Set as primary"]]
           [:form
            {:on-submit (fn [e] (.preventDefault e) (delete row))}
            [forms/submit-component
             :outer-classes [:mx-1]
             :btn-classes [:btn-warning :btn-sm]
             :inner [:span [icons/delete] " Delete"]]]]]]]))])

(defn page []
  [:div.page
   [state/hidden-routing-state-component
    :did-change #(fetch-data)]
   [:h2 "Email addresses"]
   [email-addresses-component]
   [debug-component]])

(def components
  {:page page})
