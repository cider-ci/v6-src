(ns cider-ci.server.resources.projects.commit
  (:require
   ["date-fns" :as date-fns]
   [cider-ci.server.html.icons :as icons]
   [cider-ci.server.http.anti-csrf.main :as anti-csrf]
   [cider-ci.server.http.client.main :as http-client]
   [cider-ci.server.routes :refer [path]]
   [cider-ci.server.state :as state]
   [cider-ci.utils.core :refer [presence]]
   [cljs.pprint :refer [pprint]]
   [clojure.string]
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
       [icons/unsigned] " Unsigned commit"]

      (and signed? (nil? fp))
      [:div.alert.alert-warning
       [icons/unknown-signature] " Signed, but the signing key is not trusted"]

      :else
      [:div.alert.alert-success
       [icons/signed] " Signed by "
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


;;; tree attachments ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- tree-attachment-item [tree-id {:keys [path content_type]}]
  (let [url (str "/tree-attachments/" tree-id "/" path)]
    [:div.mb-2
     (if (clojure.string/starts-with? (or content_type "") "image/")
       [:div
        [:a {:href url :target "_blank"}
         [:img {:src url :alt path :style {:max-width "100%" :max-height "300px"
                                           :border "1px solid #dee2e6" :border-radius "4px"}}]]
        [:div.small.text-muted.mt-1 [:code path]]]
       [:a {:href url :target "_blank"}
        [icons/file-code] " " [:code path]])]))

(defn- tree-attachments-panel [tree-id attachments]
  (when (seq attachments)
    [:<>
     [:h5.mt-4 "Tree Attachments"]
     (for [a attachments]
       ^{:key (:path a)}
       [tree-attachment-item tree-id a])]))


;;; submodules panel ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private submodules* (reagent/atom nil))
(defonce ^:private checking?* (reagent/atom false))

(defn- commit-id []
  (-> @state/routing* :path-params :commit-id))

(defn- submodules-url []
  (path :project-commit-submodules {:project-id (project-id) :commit-id (commit-id)}))

(defn- fetch-submodules! [method]
  (reset! checking?* true)
  (-> (js/fetch (submodules-url)
                (clj->js {:method      method
                           :credentials "same-origin"
                           :headers     {"accept"       "application/json"
                                         "content-type" "application/json"
                                         "x-csrf-token" (anti-csrf/token)}}))
      (.then (fn [resp] (.json resp)))
      (.then (fn [data] (reset! submodules* (js->clj data :keywordize-keys true))))
      (.finally (fn [] (reset! checking?* false)))))

(defn- relative-time [iso-string]
  (when-let [s (presence iso-string)]
    (date-fns/formatDistance (js/Date. s) (js/Date.) (clj->js {:addSuffix true}))))

(defn- submodule-rows [nodes depth]
  ;; the submodule's :path is bound as sub-path: `path` is the route function
  (for [{sub-path :path :keys [url commit repository_id resolved submodules candidates]} nodes]
    ^{:key (str depth sub-path commit)}
    [:<>
     [:tr {:class (when-not resolved "table-warning")}
      [:td {:style {:padding-left (str (+ 0.5 (* 1.5 depth)) "em")}} [:code sub-path]]
      [:td [:code.small url]]
      [:td
       (if resolved
         [:a {:href (path :project-commit {:project-id repository_id :commit-id commit})} [:code (subs commit 0 8)]]
         [:code (subs commit 0 8)])]
      [:td
       (if resolved
         [:a {:href (path :project {:project-id repository_id})} repository_id]
         [:span.text-warning [icons/warning] " not resolvable"
          (if (seq candidates)
            (str ": commit not present in " (clojure.string/join ", " candidates) " (not pushed, or not fetched yet)")
            ": no project with this URL")])]]
     (submodule-rows submodules (inc depth))]))

(defn- submodules-panel []
  (reagent/with-let [_ (fetch-submodules! "GET")]
    (let [{:keys [state submodules total unresolved error checked_at] :as r} @submodules*]
      [:div.submodules.mt-4
       [:div.d-flex.align-items-center.gap-2
        [:h4.mb-0 [icons/submodules] " Submodules"]
        (when r
          (case state
            "resolved"   [:span.badge.bg-success [icons/check-circle] " all " total " resolvable"]
            "unresolved" [:span.badge.bg-warning.text-dark [icons/warning] " " unresolved " of " total " not resolvable"]
            "error"      [:span.badge.bg-danger [icons/warning] " check failed"]
            "none"       [:span.badge.bg-light.text-dark "none"]
            nil))
        [:button.btn.btn-sm.btn-outline-secondary
         {:on-click #(fetch-submodules! "POST") :disabled @checking?*}
         [icons/fetch] (if @checking?* " Checking..." " Check now")]
        (when checked_at [:span.text-muted.small "checked " (relative-time checked_at)])]
       (when error [:p.text-danger.mt-2 error])
       (when (seq submodules)
         [:<>
          [:p.text-muted.small.mt-2.mb-1
           "Whether every submodule commit of the whole tree is present on this server through "
           "the configured projects; unresolved ones break the evaluation of the CI configuration "
           "and send executors to the submodule's host."]
          [:table.table.table-sm.submodules-table
           [:thead [:tr [:th "Path"] [:th "URL"] [:th "Commit"] [:th "Resolved through"]]]
           [:tbody (submodule-rows submodules 0)]]])])))


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
        [:div.mt-3.d-flex.gap-3
         [:a {:href (path :project-blob {:project-id (project-id)
                                         :commit-id  (:id c)
                                         :blob-path  "cider-ci.yml"})}
          [icons/file-code] " cider-ci.yml"]
         [:a {:href (path :project-commit-configuration {:project-id (project-id)
                                                         :commit-id  (:id c)})}
          [icons/code-branch] " Configuration"]
         [:a {:href (path :project-jobs {:project-id (project-id)
                                         :commit-id  (:id c)})}
          [icons/play-circle] " Jobs"]]
        [submodules-panel]
        [tree-attachments-panel (:tree_id c) (:tree_attachments c)]
        (when @state/debug?*
          [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])]))])


(def components {:page page})
