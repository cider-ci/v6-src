(ns cider-ci.server.resources.admin.settings
  (:refer-clojure :exclude [keyword str])
  (:require
   [cider-ci.server.http.client.main :as http-client]
   [cider-ci.server.routes :refer [path navigate!]]
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
        (navigate! (path :admin-settings)))))


;;; View page (route :admin-settings) ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- view-page []
  (fn []
    [:div.page
     [state/hidden-routing-state-component :did-change
      #(do (reset! _data* nil) (fetch!))]
     [:nav.mb-3 "Admin / Settings"]
     [:h2 "Settings"]
     (if-not @_data*
       [:p "Loading..."]
       (let [s @_data*]
         [:<>
          [:dl.row.col-md-8
           [:dt.col-sm-4 "External base URL"]
           [:dd.col-sm-8
            (if-let [u (:external_base_url s)]
              [:code u]
              [:span.text-muted "—"])]
           [:dt.col-sm-4 "Trial dispatch timeout"]
           [:dd.col-sm-8
            (if-let [t (:trial_dispatch_timeout s)]
              [:code t]
              [:span.text-muted "default (30 minutes)"])]
           [:dt.col-sm-4 "Default max commit age"]
           [:dd.col-sm-8
            (if-let [a (:branch_trigger_max_commit_age_default s)]
              [:code a]
              [:span.text-muted "no limit"])]]
          (when (-> @state/user* :is_admin)
            [:a.btn.btn-outline-secondary {:href (path :admin-settings-edit)}
             "Edit"])]))]))


;;; Edit page (route :admin-settings-edit) ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- edit-page []
  (let [form* (reagent/atom nil)]
    (fn []
      [:div.page
       [state/hidden-routing-state-component :did-change
        #(do (reset! _data* nil) (reset! form* nil) (fetch!))]
       [:nav.mb-3
        [:a {:href (path :admin-settings)} "Settings"]
        " / Edit"]
       [:h2 "Edit Settings"]
       (if-not @_data*
         [:p "Loading..."]
         (let [s @_data*]
           (when (nil? @form*)
             (reset! form* {:external_base_url                   (or (:external_base_url s) "")
                            :trial_dispatch_timeout               (or (:trial_dispatch_timeout s) "")
                            :branch_trigger_max_commit_age_default (or (:branch_trigger_max_commit_age_default s) "")}))
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
               {:type        "text"
                :placeholder "30 minutes"
                :value       (or (:trial_dispatch_timeout @form*) "")
                :on-change   #(swap! form* assoc :trial_dispatch_timeout (.. % -target -value))}]
              [:div.form-text
               "PostgreSQL interval — how long a pending trial waits before being aborted. "
               "Default: " [:code "30 minutes"]
               ". Examples: " [:code "1 hour"] ", " [:code "45 minutes"] "."]]
             [:div.mb-3
              [:label.form-label "Default max commit age for auto-trigger"]
              [:input.form-control
               {:type        "text"
                :placeholder "no limit"
                :value       (or (:branch_trigger_max_commit_age_default @form*) "")
                :on-change   #(swap! form* assoc :branch_trigger_max_commit_age_default (.. % -target -value))}]
              [:div.form-text
               "PostgreSQL interval — commits older than this are not auto-triggered. "
               "Leave blank for no global limit. Can be overridden per project. "
               "Examples: " [:code "48 hours"] ", " [:code "7 days"] "."]]
             [:div.d-flex.gap-2
              [:button.btn.btn-primary {:type "submit"} "Save"]
              [:a.btn.btn-secondary {:href (path :admin-settings)} "Cancel"]]]]))])))


(defn page []
  (case (:name @state/routing*)
    :admin-settings      [view-page]
    :admin-settings-edit [edit-page]
    [:div "Unknown route"]))

(def components {:page page})
