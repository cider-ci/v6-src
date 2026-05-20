(ns cider-ci.server.resources.admin.gpg-keys
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
   [taoensso.timbre :refer [spy]]))

(defonce _data* (reagent/atom nil))

(def data* (reagent/reaction (get @_data* (:route @state/routing*))))

(defn fetch-data [& _]
  (http-client/route-cached-fetch _data*))

(defn- key-url [gpg-key-id]
  (path :admin-gpg-key {:gpg-key-id gpg-key-id}))

(defn- delete-key [gpg-key-id]
  (go (when-let [res (-> {:method :delete :url (key-url gpg-key-id)}
                         http-client/request :chan <! spy
                         http-client/filter-success :body)]
        (swap! _data* assoc (:route @state/routing*) res))))


;;; list ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- gpg-keys-list []
  (let [ks @data*]
    [:div.mt-4
     [:h3 "Global GPG keys"]
     [:p.text-muted "These keys are trusted for all commits, regardless of author."]
     (if (empty? ks)
       [:p.text-muted "No global GPG keys configured."]
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
                           :url         (path :admin-gpg-keys {})
                           :json-params @form-data*}
                         http-client/request :chan <! http-client/filter-success :body)]
        (reset! form-data* {:name "" :description "" :ascii_key ""})
        (swap! _data* assoc (:route @state/routing*) res))))

(defn- add-form []
  (let [form* (reagent/atom {:name "" :description "" :ascii_key ""})]
    (fn []
      [:div.mt-5
       [:h3 "Add a global GPG key"]
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
   [:h2 "Admin: Global GPG keys"]
   [gpg-keys-list]
   [add-form]
   (when @state/debug?*
     [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @_data*))]]])])

(def components {:page page})
