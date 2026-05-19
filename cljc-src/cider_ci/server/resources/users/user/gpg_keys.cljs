(ns cider-ci.server.resources.users.user.gpg-keys
  (:refer-clojure :exclude [keyword str])
  (:require
   [cider-ci.server.html.icons :as icons]
   [cider-ci.server.html.utils.forms :as forms]
   [cider-ci.server.http.client.main :as http-client]
   [cider-ci.server.routes :refer [path]]
   [cider-ci.server.state :as state]
   [cider-ci.utils.core :refer [str presence]]
   [cljs.core.async :refer [go <!]]
   [cljs.pprint :refer [pprint]]
   [reagent.core :as reagent]
   [taoensso.timbre :refer [debug spy]]))

(defonce _data* (reagent/atom nil))

(def data* (reagent/reaction (get @_data* (:path @state/routing*))))

(defn fetch-data [& _]
  (http-client/route-cached-fetch _data*))

(defn- user-id []
  (-> @state/routing* :path-params :user-id))

(defn- key-url [gpg-key-id]
  (path :user-gpg-key {:user-id (user-id) :gpg-key-id gpg-key-id}))

(defn- delete-key [gpg-key-id]
  (go (when-let [res (-> {:method :delete :url (key-url gpg-key-id)}
                         http-client/request :chan <! spy
                         http-client/filter-success :body)]
        (swap! _data* assoc (:path @state/routing*) res))))


;;; list ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- gpg-keys-list []
  (let [ks @data*]
    [:div.mt-4
     [:h3 "GPG keys"]
     (if (empty? ks)
       [:p.text-muted "No GPG keys configured."]
       [:table.table.table-sm
        [:thead [:tr [:th "Name"] [:th "Fingerprint"] [:th "Added"] [:th]]]
        [:tbody
         (for [k ks]
           ^{:key (:id k)}
           [:tr
            [:td (:name k)]
            [:td [:code.small (:fingerprint k)]]
            [:td [:small (:created_at k)]]
            [:td
             [:form {:on-submit (fn [e] (.preventDefault e) (delete-key (:id k)))}
              [forms/submit-component
               :btn-classes [:btn-danger :btn-sm]
               :inner [:span [icons/delete] " Remove"]]]]])]])]))


;;; add ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- add-key [form-data*]
  (go (when-let [res (-> {:method      :post
                           :url         (path :user-gpg-keys {:user-id (user-id)})
                           :json-params @form-data*}
                         http-client/request :chan <! http-client/filter-success :body)]
        (reset! form-data* {:name "" :description "" :ascii_key ""})
        (swap! _data* assoc (:path @state/routing*) res))))

(defn- add-form []
  (let [form* (reagent/atom {:name "" :description "" :ascii_key ""})]
    (fn []
      [:div.mt-5
       [:h3 "Add a GPG key"]
       [:form {:on-submit (fn [e] (.preventDefault e) (add-key form*))}
        [forms/input-component form* [:name]
         :label "Name"
         :type :text]
        [forms/input-component form* [:description]
         :label "Description (optional)"
         :type :text]
        [forms/input-component form* [:ascii_key]
         :label "ASCII-armored public key"
         :element :textarea
         :rows 8]
        [forms/submit-component
         :btn-classes [:btn :btn-primary]
         :disabled (not (presence (:ascii_key @form*)))
         :inner [:span "Add key"]]]])))


;;; page ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn page []
  [:div.page
   [state/hidden-routing-state-component :did-change #(fetch-data)]
   [:h2 "My GPG keys"]
   [gpg-keys-list]
   [add-form]
   (when @state/debug?*
     [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @_data*))]]])])

(def components {:page page})
