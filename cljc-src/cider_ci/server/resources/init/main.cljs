(ns cider-ci.server.resources.init.main
  (:refer-clojure :exclude [keyword str])
  (:require
    [cljs.core.async :refer [go]]
    [cider-ci.server.html.utils.forms :as forms]
    [cider-ci.server.http.client.main :as http-client]
    [reagent.core :as reagent]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(defn put [data]
  (go (let [req (-> {:json-params data
                     :method :put}
                    http-client/request
                    )]
        (info 'req req)
        (if (-> req :chan <! http-client/filter-success)
          (warn "todo redirect to sign in")
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

