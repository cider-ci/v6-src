(ns cider-ci.server.resources.projects.commit
  (:require
   [cider-ci.server.html.icons :as icons]
   [cider-ci.server.http.client.main :as http-client]
   [cider-ci.server.routes :refer [path]]
   [cider-ci.server.state :as state]
   [cider-ci.utils.core :refer [presence]]
   [cljs.pprint :refer [pprint]]
   [reagent.core :as reagent]))


(defonce _data* (reagent/atom {}))

(def data* (reagent/reaction (get @_data* (:route @state/routing*))))


(defn- fetch-data [& _]
  (http-client/route-cached-fetch _data* :reload true :reload-delay 500))


(defn- project-id []
  (-> @state/routing* :path-params :project-id))


;;; signature panel ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- signature-panel []
  (let [c @data*
        signed?  (:is_signed c)
        fp       (presence (:signature_fingerprint c))
        key-name (presence (:signing_key_name c))
        login    (presence (:signing_key_user_login c))]
    (cond
      (not signed?)
      [:div.alert.alert-secondary
       [:i.fas.fa-circle-xmark] " Unsigned commit"]

      (and signed? (nil? fp))
      [:div.alert.alert-warning
       [:i.fas.fa-question-circle] " Signed, but the signing key is not trusted"]

      :else
      [:div.alert.alert-success
       [:i.fas.fa-check-circle] " Signed by "
       [:strong (or key-name "trusted key")]
       (when login [:span " (" login ")"])
       [:div.small.mt-1.text-monospace fp]])))


;;; metadata ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- author-row [label name email date]
  [:<>
   [:dt.col-sm-3 label]
   [:dd.col-sm-9
    (when name [:span [:strong name]])
    (when email [:span " <" email ">"])
    (when date  [:span.text-muted.ms-2 date])]])


(defn- metadata-panel []
  (let [c @data*]
    [:dl.row
     [author-row "Author"    (:author_name c)    (:author_email c)    (:author_date c)]
     [author-row "Committer" (:committer_name c) (:committer_email c) (:committer_date c)]
     [:dt.col-sm-3 "Tree"]   [:dd.col-sm-9 [:code (:tree_id c)]]
     (when (seq (:parents c))
       [:<>
        [:dt.col-sm-3 "Parents"]
        [:dd.col-sm-9
         (for [pid (:parents c)]
           ^{:key pid}
           [:div
            [:a {:href (path :project-commit
                             {:project-id (project-id) :commit-id pid})}
             [:code (subs pid 0 8)]]])]])]))


;;; page ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn page []
  [:div.page.commit
   [state/hidden-routing-state-component :did-change #(fetch-data)]
   (if-not (seq @data*)
     [:div "Loading..."]
     (let [c @data*]
       [:<>
        [:nav.mb-3
         [:a {:href (path :project {:project-id (project-id)})}
          [icons/projects] " " (project-id)]]
        [:h2 (:subject c)]
        [:p [:code (:id c)]]
        [signature-panel]
        [metadata-panel]
        (when-let [body (presence (:body c))]
          [:<>
           [:h4.mt-4 "Message"]
           [:pre.bg-light.p-3 body]])
        (when @state/debug?*
          [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])]))])


(def components {:page page})
