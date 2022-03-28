(ns cider-ci.server.resources.init.page
  (:refer-clojure :exclude [keyword str])
  (:require
    [cljs.core.async :refer [go]]
    [cider-ci.server.html.utils.forms :as forms]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [reagent.core :as reagent]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(defn put [data]
  (go (let [req (-> {:json-params data
                     :method :put}
                    http-client/request
                    )]
        (info 'req req)
        (if-let [body (some-> req :chan <! http-client/filter-success :body)]
          (let [p (path :sign-in {} {:email (:email body)})]
            (warn 'p p)
            (navigate! p))
          (error "request failed")
          ))))

(defn form []
  (let [data* (reagent/atom {})]
    [:form
     {:on-submit (fn [e]
                   (.preventDefault e)
                   (put @data*))}
     [forms/input-component data* [:initial_admin :email]
      :label "Initial admin e-mail address:"
      :placeholder "admin@localhost"]
     [forms/input-component data* [:initial_admin :password]
      :type :password
      :label "Initial admin password"]
     [forms/submit-component :inner [:span "Submit"]]
     ]))

(defn page []
  [:div
   [:h2.mt-3 "Initial Setup"]
   [form]
   ])

