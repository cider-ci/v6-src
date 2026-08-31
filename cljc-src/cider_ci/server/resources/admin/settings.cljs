(ns cider-ci.server.resources.admin.settings
  (:refer-clojure :exclude [keyword str])
  (:require
   [cider-ci.server.http.client.main :as http-client]
   [cider-ci.server.routes :refer [path]]
   [cider-ci.server.state :as state]
   [cider-ci.utils.core :refer [str]]
   [cljs.core.async :refer [go <!]]
   [reagent.core :as reagent]))


(defonce _data* (reagent/atom nil))

(defn- fetch! []
  (go (when-let [res (-> {:method :get
                           :url    (path :admin-settings)}
                         http-client/request :chan <! http-client/filter-success :body)]
        (reset! _data* res))))


(defn- save! [form*]
  (go (when-let [res (-> {:method      :patch
                           :url         (path :admin-settings)
                           :json-params @form*}
                         http-client/request :chan <! http-client/filter-success :body)]
        (reset! _data* res)
        (reset! form* nil))))


(defn- settings-page []
  (let [form* (reagent/atom nil)]
    (fn []
      [:div.page
       [state/hidden-routing-state-component :did-change
        #(do (reset! _data* nil) (reset! form* nil) (fetch!))]
       [:nav.mb-3 "Admin / Settings"]
       [:h2 "Settings"]
       (if-not @_data*
         [:p "Loading..."]
         (let [s @_data*]
           (when (nil? @form*)
             (reset! form* {:external_base_url      (:settings/external_base_url s)
                            :trial_dispatch_timeout (:settings/trial_dispatch_timeout s)}))
           [:div.col-md-6
            [:form {:on-submit (fn [e] (.preventDefault e) (save! form*))}
             [:div.mb-3
              [:label.form-label "External base URL"]
              [:input.form-control
               {:type      "text"
                :value     (or (:external_base_url @form*) "")
                :on-change #(swap! form* assoc :external_base_url (.. % -target -value))}]
              [:div.form-text
               "Base URL used in links sent externally (e.g. status badges, notifications)."]]
             [:div.mb-3
              [:label.form-label "Trial dispatch timeout"]
              [:input.form-control
               {:type      "text"
                :value     (or (:trial_dispatch_timeout @form*) "")
                :on-change #(swap! form* assoc :trial_dispatch_timeout (.. % -target -value))}]
              [:div.form-text
               "PostgreSQL interval — how long a pending trial waits before being aborted. "
               "Default: " [:code "30 minutes"]
               ". Examples: " [:code "1 hour"] ", " [:code "45 minutes"] "."]]
             [:button.btn.btn-primary {:type "submit"} "Save"]]
            (when-let [saved (:settings/trial_dispatch_timeout @_data*)]
              [:p.mt-3.text-muted "Current timeout: " [:code saved]])]))])))


(defn page []
  [settings-page])

(def components {:page page})
