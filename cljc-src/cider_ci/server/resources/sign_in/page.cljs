(ns cider-ci.server.resources.sign-in.page
  (:refer-clojure :exclude [keyword str])
  (:require
    [cider-ci.server.html.utils.forms :as forms]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [cider-ci.server.state :refer [routing*] :rename {routing* routing-state*}]
    [cljs.core.async :refer [go]]
    [reagent.core :as reagent]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(defn password-sign-in [data]
  (go (let [req (-> {:json-params data
                     :url (path :sign-in-password-authentication)
                     :method :post}
                    http-client/request
                    )]
        (if-let [body (some-> req :chan <! http-client/filter-success :body)]
          (navigate! (path :root) nil :reload true)
          (error "request failed")))))

(defn form []
  (let [data* (reagent/atom {})]
    (reagent/create-class
      {:component-did-mount #(when-let [email (some-> @routing-state* :query-params :email)]
                               (info 'email email)
                               (reset! data* {:email email}))
       :reagent-render
       (fn []
         [:form
          {:on-submit (fn [e]
                        (.preventDefault e)
                        (password-sign-in @data*))}
          [forms/input-component data* [:email]
           :label "E-mail address:"]
          [forms/input-component data* [:password]
           :type :password
           :label "Password"]
          [forms/submit-component :inner [:span "Submit"]]])})))

(defn page []
  [:div
   [:h2.mt-3 "Sign-in"]
   [form]
   ])
