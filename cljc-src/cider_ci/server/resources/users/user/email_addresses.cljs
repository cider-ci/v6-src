(ns cider-ci.server.resources.users.user.email-addresses
  (:refer-clojure :exclude [keyword str])
  (:require
    ["react-bootstrap" :as bs]
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.html.utils.forms :as forms]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [cider-ci.server.state :as state]
    [cider-ci.utils.core :refer [str]]
    [cljs.core.async :refer [go]]
    [cljs.pprint :refer [pprint]]
    [cuerdas.core :as string]
    [reagent.core :as reagent :refer [reaction]]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(defonce _data* (reagent/atom nil))

(def data* (reagent/reaction (get @_data* (:path @state/routing*))))

(defn fetch-data [& _]
  (http-client/route-cached-fetch _data*))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn delete [row]
  (go (when-let
        [res (-> {:method :delete
                  :url (->> [:user-email-address
                             {:user-id (-> @state/routing* :path-params :user-id)
                              :email-address (:email_address row)}]
                            (apply path))}
                 http-client/request
                 :chan <! spy http-client/filter-success :body)]
        (swap! _data* assoc (-> @state/routing* :path) res))))

(defn set-as-primary [row]
  (go (when-let
        [res (-> {:json-params (assoc row :is_primary true)
                  :method :patch
                  :url (->> [:user-email-address
                             {:user-id (-> @state/routing* :path-params :user-id)
                              :email-address (:email_address row)}]
                            (apply path))}
                 http-client/request
                 :chan <! spy http-client/filter-success :body)]
        (swap! _data* assoc (-> @state/routing* :path) res))))

(defn email-addresses-component []
  [:div.mt-5
   [:h3 "Email addresses"]
   [:ol.list-group
    (for [row @data*]
      (let [email-address (:email_address row)]
        ^{:key email-address}
        [:li.list-group-item.flex-column
         [:div.d-flex.justify-content-between
          [:div [:a {:href (str "mailto:" email-address)}
                 [:span [icons/email] " " email-address]]]
          [:div
           [:div.btn-group
            [:form
             {:on-submit (fn [e] (.preventDefault e) (set-as-primary row))}
             [:button.btn.btn-sm.btn-secondary
              {:type :submit
               :disabled (:is_primary row)}
              "Set as primary"]]
            [:form
             {:on-submit (fn [e] (.preventDefault e) (delete row))}
             [forms/submit-component
              :outer-classes [:mx-1]
              :btn-classes [:btn-warning :btn-sm]
              :inner [:span [icons/delete] " Delete"]]]]]]]))]])


;;; add ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn put [data*]
  (go (when-let
        [res (-> {:json-params {}
                  :method :put
                  :url (->> [:user-email-address
                             {:user-id (-> @state/routing* :path-params :user-id)
                              :email-address (:email_address @data*)}]
                            (apply path))}
                 http-client/request :chan <! http-client/filter-success :body)]
        (swap! data* assoc :email_address nil)
        (swap! _data* assoc (-> @state/routing* :path) res))))

(defn add-component []
  (let [add-data* (reagent/atom {:email_address ""
                                 :disabled true})]
    (fn []
      [:div.mt-5
       [:h3 "Add a new email address"]
       [:form
        {:on-submit (fn [e]
                      (.preventDefault e)
                      (put add-data*))}
        [forms/input-component add-data* [:email_address]
         :type :text
         :post-change (fn [v]
                        (swap!
                          add-data* assoc
                          :disabled (if (string/includes? v "@")
                                      false true))
                        v)
         :input-classes [(when (:is_invalid @add-data*) :is-invalid)]
         :label "New email address"]
        [forms/submit-component
         :btn-classes [:btn :btn-primary]
         :disabled (:disabled @add-data*)
         :inner [:span "Add"]]]])))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn debug-component []
  (if @state/debug?*
    [:div.debug
     [:hr]
     [:h3 "Page Debug"]
     [:pre.bg-light
      [:code
       (with-out-str (pprint @_data*))]]]
    [:<>]))

(defn page []
  [:div.page
   [state/hidden-routing-state-component
    :did-change #(fetch-data)]
   [:h2 "Mange email addresses"]
   [email-addresses-component]
   [add-component]
   [debug-component]])

(def components
  {:page page})
