(ns cider-ci.server.html.spa-page
  (:refer-clojure :exclude [keyword str])
  (:require
    ["react-bootstrap" :as bs]
    [cider-ci.server.html.utils.forms :as forms]
    [cider-ci.server.state :refer [routing*] :rename {routing* routing-state*}]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [cider-ci.server.state :as state :refer []]
    [cljs.core.async :refer [go]]
    [reagent.core :as reagent]
    [reagent.dom :as rdom]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))




(defn append []
  [:button.btn.btn-primary
   {:type :submit}
   "Sign in"])


(defn sign-in-form []
  (let [data* (reagent/atom {})]
    [:form.d-flex
     {:on-submit (fn [e]
                   (.preventDefault e)
                   (navigate! (path :sign-in {} {:login (:login @data*)})))}
     [forms/input-component data* [:login]
      :label :none
      :outer-classes ""
      :placeholder "email or login"
      :append append]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sign-out [& args]
  (go (let [resp (-> {:url (path :sign-out {} {:foo :bar})
                      :method :post}
                     http-client/request
                     :chan <!)]
        (navigate! (path :root) nil :reload true))))

(defn navbar-user [user]
  [:> bs/NavDropdown {:title
                      (reagent/as-element
                        [:<>
                         [:span (:email user)]])}
   [:> bs/NavDropdown.Item {:class "btn btn-warning"
                            :on-click sign-out}
    [:span "Sign out"]]])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn header []
  [:> bs/Navbar {:bg :light}
   [:> bs/Container {}
    [:> bs/Navbar.Brand {:href (path :root)} "Cider-CI"]
    [:> bs/Navbar.Collapse {:class "justify-content-end"}
     (if-let [user (-> @state/user*)]
       [navbar-user user]
       [sign-in-form])]]])


(defn footer []
  [:> bs/Navbar {:bg :light}
   [:> bs/Navbar.Collapse {:class_name "justify-content-end"}]
   [:> bs/Form {:inline true :class "px-2"}
    [:> bs/Form.Group {:control-id "debug"}
     [:> bs/Form.Check {:type "checkbox" :label "Debug"
                        :checked @state/debug?*
                        :on-change #(swap! state/debug?* (fn [b] (not b)))}]]]])
