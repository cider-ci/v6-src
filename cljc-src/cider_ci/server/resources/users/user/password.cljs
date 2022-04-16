(ns cider-ci.server.resources.users.user.password
  (:refer-clojure :exclude [keyword str])
  (:require
    ["react-bootstrap" :as bs]
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

(defn put [data*]
  (go (when (-> {:json-params (select-keys @data* [:password])
                 :method :put}
                http-client/request
                :chan <! http-client/filter-success)
        (swap! data* assoc :disaled true))))


(defn form []
  (let [data* (reagent/atom {;:disabled false
                             :password nil})]
    [:form
     {:on-submit (fn [e]
                   (.preventDefault e)
                   (put data*))}
     [forms/input-component data* [:password]
      :type :password
      ;:disabled (:disabled @data*)
      :label "New password"]
     [forms/submit-component
      ;:disabled (:disabled @data*)
      :inner [:span "Submit"]]
     ;[:pre.bg-light [:code (with-out-str (pprint @data*))]]
     ]))


(defn page []
  [:div.page
   [:h2 "Reset Password"]
   [form]])

(def components
  {:page page})
