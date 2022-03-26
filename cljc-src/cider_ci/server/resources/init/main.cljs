(ns cider-ci.server.resources.init.main
  (:refer-clojure :exclude [keyword str])
  ([cider-ci.server.html.utils.forms :as forms]
    :require
    ;[cider-ci.server.http.client.main]
    [reagent.core :as reagent]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))



(defn form []
  (let [data* (reagent/atom {})]
    [:form
     {:on-submit (fn [e]
                   (.preventDefault e)
                   (warn "TODO submit"))}
     [forms/input-component data* [:email]
      :label "Initial admin e-mail address:"
      :placeholder "admin@localhost"]
     [forms/input-component data* [:password]
      :type :password
      :label "Initial admin password"]
     [forms/submit-component :inner [:span "Submit"]]
     ]))

(defn page []
  [:div
   [:h2.mt-3 "Initial Setup"]
   [form]
   ])

