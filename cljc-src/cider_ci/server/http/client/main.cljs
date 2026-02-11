(ns cider-ci.server.http.client.main
  (:refer-clojure :exclude [str keyword send-off])
  (:require-macros
   [reagent.ratom :as ratom :refer [reaction]])
  (:require
   [cljs.core.async :refer [go go-loop <! >! timeout]]
   [cider-ci.server.http.anti-csrf.main  :as anti-csrf]
   [cider-ci.server.http.client.shared :refer [wait-component]]
   [cider-ci.server.http.core :refer [ANTI_CRSF_TOKEN_COOKIE_NAME HTTP_SAFE_METHODS]]
   [cider-ci.server.routes :refer [path]]
   [cider-ci.utils.core :refer [str keyword presence]]
   [cider-ci.server.state :as state :refer [state* routing*] :rename {routing* routing-state*}]
   [cljs-http.client :as http-client]
   [cljs.core.async :as async :refer [timeout]]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [goog.string :as gstring]
   [goog.string.format]
   [reagent.core :as reagent]
   [taoensso.timbre :refer [debug info warn error spy]]))

(def base-delay* (reagent/atom 0))

(defonce requests* (reagent/atom {}))

(defn dismiss [request-id]
  (swap! requests* dissoc request-id))

(defn set-defaults [data]
  (-> data
      (update :chan (fn [chan] (or chan (async/chan))))
      (update :delay #(+ (or % 0) @base-delay*))
      (assoc :id (random-uuid))
      (update :method #(or % :get))
      (assoc  :timestamp (js/Date.))
      (update :url (fn [url] (or url (-> @routing-state* :route))))
      (update-in [:headers "accept"]
                 #(or % "application/json"))
      (update-in [:headers ANTI_CRSF_TOKEN_COOKIE_NAME]
                 #(or % (anti-csrf/token)))
      (update :modal-on-response-error #(if-not (nil? %) % true))
      (as-> data
            (update data :modal-on-request
                    #(if-not (nil? %) %
                             (if (HTTP_SAFE_METHODS (:method data))
                               false true)))
        (update data :modal-on-response-success
                #(if-not (nil? %) %
                         (:modal-on-request data))))))

(defn request
  ([] (request {}))
  ([data]
   (let [req (set-defaults data)
         id (:id req)]
     (swap! requests* assoc id req)
     (go (<! (timeout (:delay req)))
         (let [resp (<! (-> req
                            (select-keys [:url :method :headers :json-params :body])
                            http-client/request))]
           (when (:success resp)
             (if (:modal-on-response-success req)
               (go (<! (timeout 1000))
                   (dismiss id))
               (dismiss id)))
           (when (get @requests* id)
             (swap! requests* assoc-in [id :response] resp))
           (when-let [chan (:chan req)] (>! chan resp))))
     req)))


(defn filter-success [response]
  (when (:success response) response))

(defn filter-success! [response]
  (if (:success response)
    response
    (throw (ex-info "Response is not success." response))))

;;; UI ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn error-component [resp req]
  [:<>
   (let [status (:status resp)]
     [:div.alert
      {:class (if (< status 500)
                "alert-warning"
                "alert-danger")}
      [:h3.alert-heading
       [:span "Request Error - Status "
        [:span.text-monospace status]]]
      [:hr]
      [:div.request
       [:pre  (-> req :method str str/upper-case) " " (:url req)]]
      (when-let [body (-> resp :body presence)]
        [:div.body [:pre body]])
      [:hr]
      (when (>= status 500)
        [:div
         [:span "Please try to reload this page with the reload button of your browser. "]
         [:span "Contact your administrator or file a bug report if the problem persists."]])])])

(defn request-response-component [req-opts inner]
  (let [req* (reagent/atom nil)
        resp* (reagent/atom nil)]
    (fn [req-opts inner]
      [:div.request-response-component
       [state/hidden-routing-state-component
        :did-mount (fn [& _]
                     (if (:chan req-opts)
                       (error ":chan may not be set for managed request-response-component")
                       (let [chan (async/chan)]
                         (reset! req* (request (assoc req-opts
                                                      :chan chan
                                                      :modal-on-request false
                                                      :modal-on-response-error false
                                                      :modal-on-response-success false)))
                         (go (let [resp (<! chan)]
                               (reset! resp* resp))))))]
       (if-let [resp @resp*]
         (if (>= (:status resp) 400)
           [error-component resp @req*]
           [inner (:body resp)])
         [wait-component @req*])])))

(def route-cached-fetch-ids* (reagent/atom {}))

(defn route-cached-fetch
  [data* & {:keys [reload reload-delay]
            :or {reload false
                 reload-delay (* 3 60 1000)}}]
  (let [route (:route @routing-state*)
        chan (async/chan)
        id (random-uuid)]
    (swap! route-cached-fetch-ids* assoc route id)
    (go-loop [do-fetch true]
      (when do-fetch
        (let [req (request {:chan chan
                            :url route})
              resp (<! chan)]
          (when (< (:status resp) 300)
            (swap! data* assoc route (-> resp :body)))))
      (<! (async/timeout reload-delay))
      (if (= (:route @routing-state*) route)
        (when (= id (get @route-cached-fetch-ids* route))
          (recur reload))
        (swap! data* dissoc route)))))
