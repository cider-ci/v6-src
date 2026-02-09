(ns cider-ci.server.resources.users.main
  (:refer-clojure :exclude [keyword str])
  (:require
    ["react-bootstrap" :as bs]
    [cider-ci.utils.core :refer [str keyword]]
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :as routes :refer [path navigate!]]
    [cider-ci.server.state :as state :refer [routing*] :rename {routing* routing-state*}]
    [cljs.pprint :refer [pprint]]
    [reagent.core :as reagent]
    [taoensso.timbre :refer [debug info warn error spy]]))


;;; USERS INDEX ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce data* (reagent/atom {}))

(defn user-emails [user]
  (when-let [emails (:email_addresses user)]
    [:span
     (for [email (take 3 emails)]
       ^{:key email}
       [:div email])
     (when (> (count emails) 3)
       [:div.text-muted (str "and " (- (count emails) 3) " more")])]))

(defn users-component []
  [:div.users
   [:h2 [icons/users] " Users"]
   [state/hidden-routing-state-component
    :did-change #(http-client/route-cached-fetch
                   data* :reload true :reload-delay 500)]

   [:<> (when @state/debug?*
          [:div.pre (with-out-str (pprint @data*))]
          )]

   (if-not (contains? @data* (:route @routing-state*))
     [:div "Spinner..."]
     (let [users (seq (get @data* (:route @routing-state*)))]
       (if-not users
         [:div "No users found."]
         [:table.table.table-sm.table-striped.users
          [:thead
           [:tr
            [:th "ID"]
            [:th "Email Addresses"] 
            [:th "Has Password"]]]
          [:tbody
           (for [user users]
             ^{:key (:id user)}
             [:tr.user
              [:td.id (:id user)]
              [:td.emails [user-emails user]]
              [:td.has-password 
               [:span.badge
                {:class (if (:has_password user) "bg-success" "bg-secondary")}
                (if (:has_password user) "Yes" "No")]]])]])))])

(defn page-nav []
  [:<>
   [:> bs/Nav.Item
    [:button.btn.btn-outline-primary.btn-sm
     {:on-click #(navigate! (path :sign-in))}
     [icons/sign-in] " Add user"]]])

(defn page []
  [:div.page
   [users-component]])

(def components {:page page
                 :page-nav page-nav})