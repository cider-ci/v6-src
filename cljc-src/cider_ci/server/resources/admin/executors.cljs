(ns cider-ci.server.resources.admin.executors
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
   [clojure.string :as string]
   [reagent.core :as reagent]))


(defonce _data* (reagent/atom nil))
(def data* (reagent/reaction (get @_data* (:route @state/routing*))))
(defonce new-token* (reagent/atom nil))


(defn fetch-data [& _]
  (http-client/route-cached-fetch _data*))


(defn- executor-url [executor-id]
  (path :admin-executor {:executor-id executor-id}))


(defn- toggle-enabled [executor-id enabled]
  (go (when-let [res (-> {:method      :patch
                           :url         (executor-url executor-id)
                           :json-params {:enabled (not enabled)}}
                         http-client/request :chan <! http-client/filter-success :body)]
        (swap! _data* assoc (:route @state/routing*) res))))


(defn- delete-executor [executor-id]
  (go (when-let [res (-> {:method :delete
                           :url    (executor-url executor-id)}
                         http-client/request :chan <! http-client/filter-success :body)]
        (swap! _data* assoc (:route @state/routing*) res))))


(defn- token-alert []
  (when-let [token @new-token*]
    [:div.alert.alert-success.mt-3
     [:strong "Executor created. Copy this token now — it will not be shown again:"]
     [:br]
     [:code.d-block.my-2.p-2.bg-light token]
     [:button.btn.btn-sm.btn-outline-secondary
      {:on-click #(reset! new-token* nil)}
      "Dismiss"]]))


(defn- executors-table []
  (let [exs @data*]
    [:div.mt-4
     [:h3 "Executors"]
     (if (empty? exs)
       [:p.text-muted "No executors configured."]
       [:table.table.table-sm
        [:thead
         [:tr [:th "Name"] [:th "Token"] [:th "Traits"] [:th "Max Load"]
          [:th "Enabled"] [:th "Last Seen"] [:th "Actions"]]]
        [:tbody
         (for [ex exs]
           ^{:key (:id ex)}
           [:tr
            [:td (:name ex)]
            [:td [:code.small (str (:token_part ex) "…")]]
            [:td [:small (if (string/blank? (:traits ex)) "—" (:traits ex))]]
            [:td (:max_load ex)]
            [:td (if (:enabled ex) "✓" "✗")]
            [:td [:small (or (:last_seen_at ex) "–")]]
            [:td
             [:div.d-flex.gap-1
              [:button.btn.btn-sm
               {:class    (if (:enabled ex) "btn-outline-warning" "btn-outline-success")
                :on-click #(toggle-enabled (:id ex) (:enabled ex))}
               (if (:enabled ex) "Disable" "Enable")]
              [:form {:on-submit (fn [e] (.preventDefault e) (delete-executor (:id ex)))}
               [forms/submit-component
                :btn-classes [:btn-danger :btn-sm]
                :inner [:span [icons/delete] " Remove"]]]]]])]])]))


(defn- create-executor [form*]
  (go (when-let [res (-> {:method      :post
                           :url         (path :admin-executors {})
                           :json-params @form*}
                         http-client/request :chan <! http-client/filter-success :body)]
        (reset! new-token* (:token res))
        (reset! form* {:name "" :traits "" :max_load 4.0})
        (swap! _data* assoc (:route @state/routing*) (:executors res)))))


(defn- add-form []
  (let [form* (reagent/atom {:name "" :traits "" :max_load 4.0})]
    (fn []
      [:div.mt-5
       [:h3 "Add executor"]
       [:form {:on-submit (fn [e] (.preventDefault e) (create-executor form*))}
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
         :inner [:span "Add executor"]]]])))


(defn page []
  [:div.page
   [state/hidden-routing-state-component :did-change #(fetch-data)]
   [:h2 "Admin: Executors"]
   [token-alert]
   [executors-table]
   [add-form]
   (when @state/debug?*
     [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @_data*))]]])])


(def components {:page page})
