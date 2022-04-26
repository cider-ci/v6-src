(ns cider-ci.server.html.spa-page
  (:refer-clojure :exclude [keyword str])
  (:require
    ["react-bootstrap" :as bs]
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.html.utils.forms :as forms]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [cider-ci.server.state :as state :refer [routing*] :rename {routing* routing-state*}]
    [cljs.core.async :refer [go]]
    [cuerdas.core :as string]
    [reagent.core :as reagent]
    [reagent.dom :as rdom]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(defn append []
  [:button.btn.btn-primary
   {:type :submit}
   "Sign in"])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sign-in-form []
  (let [data* (reagent/atom {})]
    (reagent/create-class
      {:component-did-mount
       (fn []
         (swap! data* assoc :login
                (some-> @routing-state* :query-params :login)))
       :render
       (fn []
         [:form.d-flex
          {:on-submit (fn [e]
                        (.preventDefault e)
                        (navigate! (path :sign-in {} {:login (:login @data*)})))}
          [forms/input-component data* [:login]
           :label :none
           :outer-classes ""
           :placeholder "email or login"
           :append append]])})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn user-uid-component [user]
  [:span.user-uid
   (or (some-> user :login)
       (some-> user :email_addresses first)
       (some-> user :id (string/split "-") first))])

(defn sign-out [& args]
  (go (when (-> {:url (path :sign-out {} {:foo :bar})
                 :method :post}
                http-client/request
                :chan <! http-client/filter-success)
        (navigate! (path :root) nil :reload true))))

(defn user-component[user]
  [:<>
   [:span.user-icon
    (if (:is_admin user)
      [icons/user-admin]
      [icons/user])
    " "]
   [user-uid-component user]])

(defn navbar-user [user]
  [:> bs/NavDropdown {:title (reagent/as-element [user-component user])}
   [:> bs/NavDropdown.Item
    {:href (path :user {:user-id (-> @state/user* :id)})}
    "My account"]
   [:> bs/NavDropdown.Item
    {:class "btn btn-warning"
     :on-click sign-out}
    [:span "Sign out"]]])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn header []
  [:> bs/Navbar {:bg :light}
   [:> bs/Container {:class "justify-content-start"}
    [:> bs/Navbar.Brand {:href (path :root)} "Cider-CI"]]
   [:<> (when-let [center-nav (:center-nav @state/routing*)]
          [center-nav])]
   [:> bs/Container {:class "justify-content-end"}
    (if-let [user (-> @state/user*)]
      [navbar-user user]
      [:<> (when-not  (-> @routing-state* :data :no-sign-in-page)
             [sign-in-form])])]])


(defn footer []
  [:> bs/Navbar {:bg :light}
   [:> bs/Navbar.Collapse {:class_name "justify-content-end"}]
   [:> bs/Form {:inline "true" :class "px-2"}
    [:> bs/Form.Group {:control-id "debug"}
     [:> bs/Form.Check {:type "checkbox" :label "Debug"
                        :checked @state/debug?*
                        :on-change #(swap! state/debug?* (fn [b] (not b)))}]]]])
