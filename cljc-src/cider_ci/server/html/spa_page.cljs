(ns cider-ci.server.html.spa-page
  (:refer-clojure :exclude [keyword str])
  (:require
    ["react-bootstrap" :as bs]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [cider-ci.server.state :as state :refer []]
    [cljs.core.async :refer [go]]
    [reagent.core :as reagent]
    [reagent.dom :as rdom]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(defn sign-out [& args]
  (go (let [resp (-> {:url (path :sign-out {} {:foo :bar})
                      :method :post}
                     http-client/request
                     :chan <!)]
        (navigate! (path :root) nil :reload true))))

(defn header []
  [:> bs/Navbar {:bg :light}
   [:> bs/Container {}
    [:> bs/Navbar.Brand {:href (path :root)} "Cider-CI"]
    [:> bs/Navbar.Collapse {:class "justify-content-end"}
     (when-let [user (-> @state/user*)]
       [:> bs/NavDropdown {:title
                           (reagent/as-element
                             [:<>
                              [:span (:email user)]])}
        [:> bs/NavDropdown.Item {:class "btn btn-warning"
                                 :on-click sign-out}
         [:span "Sign out"]]])]]])

(defn footer []
  [:> bs/Navbar {:bg :light}
   [:> bs/Navbar.Collapse {:class_name "justify-content-end"}]
   [:> bs/Form {:inline true :class "px-2"}
    [:> bs/Form.Group {:control-id "debug"}
     [:> bs/Form.Check {:type "checkbox" :label "Debug"
                        :checked @state/debug?*
                        :on-change #(swap! state/debug?* (fn [b] (not b)))}]]]])
