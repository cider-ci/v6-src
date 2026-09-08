(ns cider-ci.server.resources.users.user.main
  (:refer-clojure :exclude [keyword str])
  (:require
    ["react-bootstrap" :as bs]
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [cider-ci.server.state :as state]
    [cider-ci.utils.core :refer [presence]]
    [cljs.core.async :refer [go <!]]
    [reagent.core :as reagent]))

(defonce _data* (reagent/atom nil))

(defn- user-id-param []
  (-> @state/routing* :path-params :user-id))

(defn- user-url []
  (path :user {:user-id (user-id-param)}))

(defn- fetch! [& _]
  (go (when-let [res (-> {:method :get :url (user-url)}
                         http-client/request :chan <! http-client/filter-success :body)]
        (reset! _data* res))))

(defn- admin? []
  (-> @state/user* :is_admin))


;;; Detail page ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- user-metadata []
  (let [u @_data*]
    [:dl.row
     [:dt.col-sm-3 "Login"]    [:dd.col-sm-9 (:login u)]
     [:dt.col-sm-3 "Name"]     [:dd.col-sm-9 (or (:name u) [:span.text-muted "—"])]
     [:dt.col-sm-3 "Admin"]
     [:dd.col-sm-9
      [:span.badge {:class (if (:is_admin u) "bg-danger" "bg-secondary")}
       (if (:is_admin u) "Yes" "No")]]
     [:dt.col-sm-3 "Email Addresses"]
     [:dd.col-sm-9
      (if-let [emails (seq (:email_addresses u))]
        (for [e emails] ^{:key e} [:div e])
        [:span.text-muted "—"])]
     [:dt.col-sm-3 "Has Password"]
     [:dd.col-sm-9
      [:span.badge {:class (if (:has_password u) "bg-success" "bg-secondary")}
       (if (:has_password u) "Yes" "No")]]]))

(defn- detail-page []
  (fn []
    [:div.page
     [state/hidden-routing-state-component :did-change
      #(do (reset! _data* nil) (fetch!))]
     (if-not @_data*
       [:div "Loading..."]
       [:<>
        [:div.d-flex.align-items-center.gap-2.mb-3
         [:h2.mb-0 [icons/user] " " (or (:login @_data*) (user-id-param))]
         (when (admin?)
           [:a.btn.btn-sm.btn-outline-secondary
            {:href (path :user-edit {:user-id (user-id-param)})}
            [icons/edit] " Edit"])]
        [user-metadata]
        [:div.mt-3.d-flex.gap-2
         [:a.btn.btn-sm.btn-outline-secondary
          {:href (path :user-password {:user-id (user-id-param)})}
          [icons/password] " Reset password"]
         [:a.btn.btn-sm.btn-outline-secondary
          {:href (path :user-email-addresses {:user-id (user-id-param)})}
          "Manage email addresses"]
         [:a.btn.btn-sm.btn-outline-secondary
          {:href (path :user-gpg-keys {:user-id (user-id-param)})}
          "Manage GPG keys"]]
        (when (admin?)
          [:div.mt-3
           [:button.btn.btn-sm.btn-danger
            {:on-click (fn [_]
                         (go (when (-> {:method :delete :url (user-url)}
                                       http-client/request :chan <!
                                       http-client/filter-success)
                               (navigate! (path :users)))))}
            [icons/delete] " Delete user"]])])]))


;;; Edit page ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- save-user! [form*]
  (go (when (-> {:method      :patch
                 :url         (user-url)
                 :json-params @form*}
                http-client/request :chan <! http-client/filter-success)
        (navigate! (user-url)))))

(defn- login-field [form*]
  [:div.mb-3
   [:label.form-label {:html-for "login"} "Login"]
   [:input.form-control
    {:id        "login"
     :type      "text"
     :required  true
     :value     (:login @form*)
     :on-change #(swap! form* assoc :login (.. % -target -value))}]])

(defn- name-field [form*]
  [:div.mb-3
   [:label.form-label {:html-for "name"} "Name"]
   [:input.form-control
    {:id        "name"
     :type      "text"
     :value     (:name @form*)
     :on-change #(swap! form* assoc :name (.. % -target -value))}]])

(defn- is-admin-field [form*]
  [:div.mb-3.form-check
   [:input.form-check-input
    {:id        "is_admin"
     :type      "checkbox"
     :checked   (boolean (:is_admin @form*))
     :on-change #(swap! form* assoc :is_admin (.. % -target -checked))}]
   [:label.form-check-label {:html-for "is_admin"} "Admin"]])

(defn- edit-form [form*]
  [:div.col-md-6
   [:h2 "Edit User"]
   [:form {:on-submit (fn [e] (.preventDefault e) (save-user! form*))}
    [login-field form*]
    [name-field form*]
    [is-admin-field form*]
    [:div.d-flex.gap-2
     [:button.btn.btn-primary {:type "submit"} "Save"]
     [:a.btn.btn-secondary {:href (user-url)} "Cancel"]]]])

(defn- edit-page []
  (let [form* (reagent/atom nil)]
    (fn []
      [:div.page
       [state/hidden-routing-state-component :did-change
        #(do (reset! _data* nil) (reset! form* nil) (fetch!))]
       [:nav.mb-3
        [:a {:href (path :users)} "Users"]
        " / "
        (when @_data*
          [:<>
           [:a {:href (user-url)} (or (:login @_data*) (user-id-param))]
           " / "])
        "Edit"]
       (if-not @_data*
         [:div "Loading..."]
         (let [u @_data*]
           (when (nil? @form*)
             (reset! form* {:login    (or (:login u) "")
                            :name     (or (:name u) "")
                            :is_admin (boolean (:is_admin u))}))
           [edit-form form*]
           )  ; closes (let [u @_data*] ...)
         )  ; closes (if-not @_data* ...)
       ]  ; closes [:div.page ...]
       )  ; closes (fn [] ...)
    )  ; closes (let [form* ...] ...)
  )  ; closes (defn- edit-page [] ...)


;;; New page ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- create-user! [form*]
  (go (when-let [res (-> {:method      :post
                          :url         (path :users)
                          :json-params @form*}
                         http-client/request :chan <! http-client/filter-success :body)]
        (navigate! (path :user {:user-id (:id res)})))))

(defn- password-field [form*]
  [:div.mb-3
   [:label.form-label {:html-for "password"} "Password"]
   [:input.form-control
    {:id        "password"
     :type      "password"
     :required  true
     :value     (:password @form*)
     :on-change #(swap! form* assoc :password (.. % -target -value))}]])

(defn- new-form [form*]
  [:div.col-md-6
   [:form {:on-submit (fn [e] (.preventDefault e) (create-user! form*))}
    [login-field form*]
    [name-field form*]
    [password-field form*]
    [is-admin-field form*]
    [:div.d-flex.gap-2
     [:button.btn.btn-primary
      {:type     "submit"
       :disabled (not (and (presence (:login @form*))
                           (presence (:password @form*))))}
      [icons/create] " Create User"]
     [:a.btn.btn-secondary {:href (path :users)} "Cancel"]]]])

(defn- new-page []
  (let [form* (reagent/atom {:login "" :name "" :password "" :is_admin false})]
    (fn []
      [:div.page
       [:nav.mb-3
        [:a {:href (path :users)} "Users"]
        " / New"]
       [:h2 "New User"]
       [new-form form*]])))


;;; Page dispatch ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn page []
  (case (:name @state/routing*)
    :user      [detail-page]
    :user-edit [edit-page]
    :user-new  [new-page]
    [:div "Unknown route"]))

(defn center-nav []
  [:<>
   [:> bs/Navbar.Collapse {:class "justify-content-center"}
    (when-let [u @_data*]
      [:> bs/Nav.Item
       [:> bs/Nav.Link {:href (user-url)} (:login u)]])]])

(def components {:page page :center-nav center-nav})
