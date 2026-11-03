(ns cider-ci.server.html.gpg-key
  "The detail of a stored public GPG key (a user's or a global one): what
   it contains (user ids, expiration, revocation, subkeys) and the armored
   key to copy or download."
  (:refer-clojure :exclude [str keyword])
  (:require
    [cider-ci.server.html.clipboard :as clipboard]
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.html.utils.forms :as forms]
    [cider-ci.utils.core :refer [str presence]]
    [clojure.string :as string]
    [reagent.core :as reagent]))


(defn- date [s]
  (when (presence s) (subs s 0 10)))

(defn- validity
  "expires <date> / expired <date> / never expires, with revocation."
  [{:keys [expires_at expired revoked]}]
  [:<>
   (cond
     revoked    [:span.badge.bg-danger.me-2 "revoked"]
     expired    [:span.badge.bg-danger.me-2 "expired " (date expires_at)]
     expires_at [:span (str "expires " (date expires_at))]
     :else      [:span "never expires"])])

(defn- status-badges [{:keys [expired revoked]}]
  [:<>
   (when revoked [:span.badge.bg-danger.ms-2 "revoked"])
   (when (and expired (not revoked)) [:span.badge.bg-danger.ms-2 "expired"])])

(defn- subkeys-table [subkeys]
  (if (empty? subkeys)
    [:span.text-muted "—"]
    [:table.table.table-sm.mb-0.subkeys
     [:thead [:tr [:th "Fingerprint"] [:th "Usage"] [:th "Algorithm"] [:th "Created"] [:th "Validity"]]]
     [:tbody
      (for [{:keys [fingerprint usage algorithm bits created_at] :as sk} subkeys]
        ^{:key fingerprint}
        [:tr
         [:td [:code.small fingerprint]]
         [:td usage]
         [:td algorithm " " bits]
         [:td (date created_at)]
         [:td [validity sk]]])]]))

(defn- armored-key [ascii-key file-name]
  (let [copied?* (reagent/atom false)]
    (fn [ascii-key file-name]
      [:div.armored-key
       [:div.d-flex.align-items-center.gap-2.mb-2
        [:h4.mb-0 "Public key"]
        [:button.btn.btn-outline-secondary.btn-sm.copy-key
         {:type "button"
          :on-click (fn [_]
                      (clipboard/copy! ascii-key
                                       (fn []
                                         (reset! copied?* true)
                                         (js/setTimeout #(reset! copied?* false) 2000))))}
         (if @copied?*
           [:span.text-success [icons/signed] " Copied"]
           [:span [icons/clipboard] " Copy"])]
        [:a.btn.btn-outline-secondary.btn-sm.download-key
         {:href (str "data:application/pgp-keys;charset=utf-8," (js/encodeURIComponent ascii-key))
          :download file-name}
         [icons/download] " Download"]]
       [:textarea.form-control.font-monospace.small
        {:read-only true :rows 12 :value ascii-key
         :on-focus #(.select (.-target %))}]])))

(defn details-panel
  "The key row as returned by the detail resource: {:name :description
   :fingerprint :source :created_at :ascii_key :details [...]}."
  [{:keys [name description fingerprint source created_at ascii_key details] :as k}]
  (let [primary (first details)]
    [:div.gpg-key-detail
     [:h2 [icons/gpg-key] " " name
      (when (= "github" source)
        [:span.badge.bg-secondary.ms-2.source-github
         {:title "Imported from GitHub; updated at each sign-in with GitHub"} "GitHub"])
      (when primary [status-badges primary])]
     (when (presence description) [:p.text-muted description])
     (if-not primary
       [:div.alert.alert-warning "The stored key cannot be parsed."]
       [:dl.row
        [:dt.col-sm-3 "Fingerprint"] [:dd.col-sm-9 [:code fingerprint]]
        [:dt.col-sm-3 "Key id"]      [:dd.col-sm-9 [:code (:key_id primary)]]
        [:dt.col-sm-3 "Algorithm"]   [:dd.col-sm-9 (:algorithm primary) " " (:bits primary) " bits"]
        [:dt.col-sm-3 "Created"]     [:dd.col-sm-9 (date (:created_at primary))]
        [:dt.col-sm-3 "Validity"]    [:dd.col-sm-9.validity [validity primary]]
        [:dt.col-sm-3 "User ids"]
        [:dd.col-sm-9.user-ids
         (if (seq (:user_ids primary))
           (for [uid (:user_ids primary)] ^{:key uid} [:div uid])
           [:span.text-muted "—"])]
        [:dt.col-sm-3 "Subkeys"]     [:dd.col-sm-9 [subkeys-table (:subkeys primary)]]
        (when (> (count details) 1)
          [:<>
           [:dt.col-sm-3 "Further keys"]
           [:dd.col-sm-9 (for [d (rest details)]
                           ^{:key (:fingerprint d)}
                           [:div [:code.small (:fingerprint d)] " "
                            (string/join ", " (:user_ids d))])]])
        [:dt.col-sm-3 "Added"]       [:dd.col-sm-9 (date created_at)]])
     [armored-key ascii_key (str (string/lower-case (or fingerprint "key")) ".asc")]]))

(defn remove-button [on-remove]
  [:form.mt-3 {:on-submit (fn [e] (.preventDefault e) (on-remove))}
   [forms/submit-component
    :btn-classes [:btn-danger :btn-sm]
    :inner [:span [icons/delete] " Remove this key"]]])
