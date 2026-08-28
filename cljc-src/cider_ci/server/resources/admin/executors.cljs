(ns cider-ci.server.resources.admin.executors
  (:refer-clojure :exclude [keyword str])
  (:require
   [cider-ci.server.html.icons :as icons]
   [cider-ci.server.html.utils.forms :as forms]
   [cider-ci.server.http.client.main :as http-client]
   [cider-ci.server.routes :refer [path navigate!]]
   [cider-ci.server.state :as state]
   [cider-ci.utils.core :refer [str presence]]
   [cljs.core.async :refer [go <!]]
   [cljs.pprint :refer [pprint]]
   [clojure.string :as string]
   [reagent.core :as reagent]))


(defonce _data*   (reagent/atom nil))
(def     data*    (reagent/reaction (get @_data* (:route @state/routing*))))
(defonce _single* (reagent/atom nil))
(defonce _filter* (reagent/atom ""))
(defonce new-token* (reagent/atom nil))


(defn- executor-id-param []
  (-> @state/routing* :path-params :executor-id))

(defn- executor-url [id]
  (path :admin-executor {:executor-id id}))

(defn- traits-from-url []
  (or (some-> js/window.location.search js/URLSearchParams. (.get "traits")) ""))

(defn fetch-data [& _]
  (http-client/route-cached-fetch _data*))

(defn- fetch-single [& _]
  (go (when-let [res (-> {:method :get
                           :url    (executor-url (executor-id-param))}
                         http-client/request :chan <! http-client/filter-success :body)]
        (reset! _single* res))))


;;; Shared components

(defn- enabled-badge [enabled]
  [:span.badge {:class (if enabled "bg-success" "bg-secondary")}
   (if enabled "enabled" "disabled")])

(defn- token-alert []
  (when-let [token @new-token*]
    [:div.alert.alert-success.mt-3
     [:strong "Executor created. Copy this token now — it will not be shown again:"]
     [:br]
     [:code.d-block.my-2.p-2.bg-light token]
     [:button.btn.btn-sm.btn-outline-secondary
      {:on-click #(reset! new-token* nil)}
      "Dismiss"]]))


;;; List page (route :admin-executors)

(defn- matches-filter? [traits-str filter-str]
  (if (string/blank? filter-str)
    true
    (let [required (->> (string/split filter-str #",") (map string/trim) (remove string/blank?))
          have     (->> (string/split (or traits-str "") #",") (map string/trim) (remove string/blank?) set)]
      (every? have required))))

(defn- list-page []
  [:div.page
   [state/hidden-routing-state-component :did-change
    #(do (fetch-data) (reset! _filter* (traits-from-url)))]
   [:div.d-flex.align-items-center.justify-content-between.mb-3
    [:h2 "Admin: Executors"]
    [:a.btn.btn-primary {:href (path :admin-executor-new {})}
     [icons/create] " Add Executor"]]
   [token-alert]
   [:div.mb-3.col-md-5
    [:div.input-group
     [:span.input-group-text [icons/filter-icon]]
     [:input.form-control
      {:type        "text"
       :placeholder "Filter by traits (comma-separated)"
       :value       @_filter*
       :on-change   #(reset! _filter* (-> % .-target .-value))}]
     (when-not (string/blank? @_filter*)
       [:button.btn.btn-outline-secondary {:on-click #(reset! _filter* "")} "×"])]]
   (if (nil? @data*)
     [:p.text-muted "Loading..."]
     (let [filtered (filter #(matches-filter? (:traits %) @_filter*) @data*)]
       (if (empty? @data*)
         [:p.text-muted "No executors configured."]
         [:<>
          (when (and (not (string/blank? @_filter*)) (empty? filtered))
            [:p.text-muted "No executors match the trait filter."])
          (when (seq filtered)
            [:table.table.table-sm.table-hover
             [:thead
              [:tr [:th "Name"] [:th "Traits"] [:th "Max Load"] [:th "Status"] [:th "Last Seen"]]]
             [:tbody
              (for [ex filtered]
                ^{:key (:id ex)}
                [:tr
                 [:td [:a {:href (path :admin-executor-edit {:executor-id (:id ex)})}
                       [icons/edit] " " (:name ex)]]
                 [:td [:small (if (string/blank? (:traits ex)) "—" (:traits ex))]]
                 [:td (:max_load ex)]
                 [:td [enabled-badge (:enabled ex)]]
                 [:td [:small (or (:last_seen_at ex) "–")]]])]])])))
   (when @state/debug?*
     [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @_data*))]]])])


;;; Add page (route :admin-executor-new)

(defn- create-executor! [form*]
  (go (when-let [res (-> {:method      :post
                           :url         (path :admin-executors {})
                           :json-params @form*}
                         http-client/request :chan <! http-client/filter-success :body)]
        (reset! new-token* (:token res))
        (navigate! (path :admin-executors {})))))

(defn- add-page []
  (let [form* (reagent/atom {:name "" :traits "" :max_load 4.0})]
    (fn []
      [:div.page
       [:nav.mb-3
        [:a {:href (path :admin-executors {})} "Admin: Executors"]
        " / New"]
       [:h2 "Add Executor"]
       [:div.col-md-6
        [:form {:on-submit (fn [e] (.preventDefault e) (create-executor! form*))}
         [forms/input-component form* [:name]
          :label "Name"
          :type :text]
         [forms/input-component form* [:traits]
          :label "Traits (comma-separated)"
          :type :text]
         [forms/input-component form* [:max_load]
          :label "Max load"
          :type :number]
         [forms/submit-component
          :btn-classes [:btn :btn-primary]
          :disabled (not (presence (:name @form*)))
          :inner [:span "Add Executor"]]]]])))


;;; Edit page (route :admin-executor-edit)

(defn- save-executor! [form*]
  (go (when-let [_res (-> {:method      :patch
                             :url         (executor-url (executor-id-param))
                             :json-params @form*}
                           http-client/request :chan <! http-client/filter-success :body)]
        (fetch-single))))

(defn- toggle-enabled! [current-enabled]
  (go (when-let [_res (-> {:method      :patch
                             :url         (executor-url (executor-id-param))
                             :json-params {:enabled (not current-enabled)}}
                           http-client/request :chan <! http-client/filter-success :body)]
        (fetch-single))))

(defn- delete-executor! [name]
  (when (js/confirm (str "Delete executor "" name ""?"))
    (go (when-let [_res (-> {:method :delete
                               :url    (executor-url (executor-id-param))}
                             http-client/request :chan <! http-client/filter-success :body)]
          (navigate! (path :admin-executors {}))))))

(defn- edit-page []
  (let [form* (reagent/atom nil)]
    (fn []
      [:div.page
       [state/hidden-routing-state-component :did-change
        #(do (reset! _single* nil) (reset! form* nil) (fetch-single))]
       [:nav.mb-3
        [:a {:href (path :admin-executors {})} "Admin: Executors"]
        " / Edit"]
       (if-not @_single*
         [:div "Loading..."]
         (let [ex @_single*]
           (when (nil? @form*)
             (reset! form* {:name (:name ex) :traits (:traits ex) :max_load (:max_load ex)}))
           (if (nil? @form*)
             [:div "Loading..."]
             [:<>
              [:h2 (:name ex) " " [enabled-badge (:enabled ex)]]
              [:div.col-md-6
               [:form.mb-4 {:on-submit (fn [e] (.preventDefault e) (save-executor! form*))}
                [forms/input-component form* [:name]
                 :label "Name"
                 :type :text]
                [forms/input-component form* [:traits]
                 :label "Traits (comma-separated)"
                 :type :text]
                [forms/input-component form* [:max_load]
                 :label "Max load"
                 :type :number]
                [forms/submit-component
                 :btn-classes [:btn :btn-primary]
                 :disabled (not (presence (:name @form*)))
                 :inner [:span "Save"]]]
               [:div.d-flex.gap-2
                [:button.btn.btn-outline-secondary
                 {:on-click #(toggle-enabled! (:enabled ex))}
                 (if (:enabled ex) "Disable" "Enable")]
                [:button.btn.btn-outline-danger
                 {:on-click #(delete-executor! (:name ex))}
                 [icons/delete] " Delete"]]]
              [:dl.row.mt-4
               [:dt.col-sm-3 "Token prefix"]
               [:dd.col-sm-9 [:code (str (:token_part ex) "…")]]
               [:dt.col-sm-3 "Last seen"]
               [:dd.col-sm-9 (or (:last_seen_at ex) "–")]]])))])))


(defn page []
  (case (:name @state/routing*)
    :admin-executors     [list-page]
    :admin-executor-new  [add-page]
    :admin-executor-edit [edit-page]
    [:div "Unknown route"]))

(def components {:page page})
