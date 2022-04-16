(ns cider-ci.server.http.client.modals
  (:refer-clojure :exclude [str keyword send-off])
    (:require
    [cider-ci.server.html.clipboard :as clipboard]
    [cider-ci.server.http.client.main :refer [requests* dismiss]]
    [cider-ci.server.http.client.shared :refer [wait-component]]
    [cider-ci.server.http.core :refer [HTTP_UNSAVE_METHODS]]
    [cider-ci.utils.core :refer [keyword presence str]]
    [cljs-http.client :as http.client]
    [cljs.core.async :as async :refer [timeout go]]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [goog.string :as gstring]
    [goog.string.format]
    [reagent.core :as reagent]
    [reagent.ratom :as ratom :refer [reaction]]
    [taoensso.timbre :refer [debug info warn error spy]]))

(defn status [request]
  (cond (empty? (-> request :response)) :pending
        (-> request :response :success) :success
        :else :error))

(def current-modal-request*
  (reaction
    (->> @requests*
         (map second)
         (sort-by :timestamp)
         (filter (fn [req]
                   (case (status req)
                     :pending (:modal-on-request req)
                     :success (:modal-on-response-success req)
                     :error (:modal-on-response-error req))))
         first)))

(defn bootstrap-status [modal-status]
  (case modal-status
    :pending :default
    :success :success
    :error :danger))


(defn dismiss-button-component
  ([request]
   (dismiss-button-component request {}))
  ([request opts]
   [:button.btn
    {:class (str "btn-" (-> request status bootstrap-status)
                 " " (:class opts))
     :on-click #(dismiss (:id request))}
    [:i.fas.fa-times] " Dismiss "]))

(defn modal-body-inner [body]
  [:div
   (if (string? body)
     [:blockquote.blockquote [:p body]]
     [:pre (with-out-str (pprint body))])])

(defn modal-body [request status bootstrap-status]
  [:div.modal-body {:key (str "modal-body_" (:id request))}
   [:div
    [:p [:small.text-monospace
         (some->> request :id str (take 8) string/join string/upper-case) " "
         (some-> request :method str string/upper-case) " "
         (some-> request :url str) " "]]]
   (when-not (some-> request :response presence)
     [wait-component request] )
   (when (>= (-> request :response :status) 400)
     [:<>
      (when-let [body (some-> request :response :body presence)]
        [:div
         [:p "The response message reads:"]
         [modal-body-inner body]
         [:hr]])
      [:small
       (if (HTTP_UNSAVE_METHODS (:method request))
         [:p "Please try to send the data again. If that fails use reload button of your browser and send again. "]
         [:p "Please try to reload this page with the reload button of your browser. "])
       [:p [:span
            "Contact your administrator or file a bug report if this problem persists. "
            "Please provide the details for this request: "
            [clipboard/button (with-out-str (pprint request))]]]]])])

(defn modal-component []
  (when-let [request @current-modal-request*]
    (let [status (status request)
          bootstrap-status (bootstrap-status status)]
      (info "##################" {'request request 'status status 'bootstrap-status bootstrap-status})
      (if false
        [:<>]
        [:div {:style {:opacity (case status :pending "1.0" "1.0") :z-index 10000}}
         [:div.modal.request {:style {:display "block" :z-index 10000}}
          [:div.modal-dialog.modal-lg
           [:div.modal-content
            [:div.modal-header {:class (str "text-" bootstrap-status)}
             [:h4 (str " Request "
                       (when-let [title (-> request :meta :title)]
                         (str " \"" title "\" "))
                       (case status
                         :error " ERROR "
                         :success " SUCCESS "
                         :pending " PENDING "
                         nil))
              (when-not (= status :success)
                (-> request :response :status))]]
            [modal-body request status bootstrap-status]
            [:div.modal-footer {:key (str "modal-footer_" (:id request))}
             [:div.clearfix]
             [dismiss-button-component request]]]]]
         [:div.modal-backdrop {:style {:opacity "0.5"}}]]))))
