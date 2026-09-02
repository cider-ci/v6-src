(ns cider-ci.server.resources.projects.project
  (:require
   ["date-fns" :as date-fns]
   [cider-ci.server.html.icons :as icons]
   [cider-ci.server.http.anti-csrf.main :as anti-csrf]
   [cider-ci.server.http.client.main :as http-client]
   [cider-ci.server.routes :refer [path navigate!]]
   [cider-ci.server.state :as state]
   [cider-ci.utils.core :refer [presence]]
   [cljs.core.async :refer [go <!]]
   [cljs.pprint :refer [pprint]]
   [reagent.core :as reagent]))


(defonce _data* (reagent/atom nil))


(defn- project-id []
  (-> @state/routing* :path-params :project-id))

(defn- project-url []
  (path :project {:project-id (project-id)}))

(defn- fetch! [& _]
  (go (when-let [res (-> {:method :get :url (project-url)}
                         http-client/request :chan <! http-client/filter-success :body)]
        (reset! _data* res))))


(defn- relative-time [iso-string]
  (when-let [s (presence iso-string)]
    (date-fns/formatDistance (js/Date. s) (js/Date.) (clj->js {:addSuffix true}))))


;;; Shared helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fetch-request [method url on-success]
  (-> (js/fetch url (clj->js {:method      method
                               :credentials "same-origin"
                               :headers     {"accept"       "application/json"
                                             "x-csrf-token" (anti-csrf/token)}}))
      (.then on-success)))


;;; Branches table ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- signature-cell [fingerprint]
  (if fingerprint
    [:span.text-success {:title fingerprint} [icons/signed] " signed"]
    [:span.text-muted "—"]))

(defonce branches-sort* (reagent/atom {:key :commit_committer_date :dir :desc}))

(defn- sort-icon [col-key]
  (let [{:keys [key dir]} @branches-sort*]
    (when (= key col-key)
      (if (= dir :asc) " ▲" " ▼"))))

(defn- sort-th [col-key label]
  [:th {:style    {:cursor "pointer" :user-select "none"}
        :on-click #(swap! branches-sort*
                          (fn [{:keys [key dir]}]
                            (if (= key col-key)
                              {:key col-key :dir (if (= dir :asc) :desc :asc)}
                              {:key col-key :dir :asc})))}
   label (sort-icon col-key)])

(defn- sorted-branches [branches]
  (let [{:keys [key dir]} @branches-sort*
        sorted (sort-by #(get % key) branches)]
    (if (= dir :desc) (reverse sorted) sorted)))

(defn- branches-table []
  (let [branches (:branches @_data*)]
    (if (empty? branches)
      [:p.text-muted "No branches yet."]
      [:table.table.table-sm.table-hover.branches
       [:thead
        [:tr
         [sort-th :name "Branch"]
         [:th "Last commit"]
         [sort-th :commit_committer_date "Date"]
         [:th "Signed"]
         [:th "Subject"]]]
       [:tbody
        (for [b (sorted-branches branches)]
          ^{:key (:id b)}
          [:tr
           [:td [:a {:href (path :project-branch
                                 {:project-id (project-id)
                                  :branch-name (:name b)})}
                 (:name b)]]
           [:td
            (if-let [cid (:current_commit_id b)]
              [:a {:href (path :project-commit
                               {:project-id (project-id)
                                :commit-id  cid})}
               [:code.small (subs cid 0 8)]]
              "—")]
           [:td [:small (or (relative-time (:commit_committer_date b)) "—")]]
           [:td [signature-cell (:commit_signature_fingerprint b)]]
           [:td.text-truncate {:style {:max-width "32em"}}
            (:commit_subject b)]])]])))


;;; View page (route :project) ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fetch-status-badge [fetch-and-update]
  (let [state (:state fetch-and-update)
        bg    (case state "ok" "bg-success" "bg-warning")]
    [:span.badge {:class bg} (or state "unknown")]))

(defn- project-metadata []
  (let [p @_data*]
    [:dl.row
     [:dt.col-sm-3 "ID"]      [:dd.col-sm-9 [:code (:id p)]]
     [:dt.col-sm-3 "Name"]    [:dd.col-sm-9 (:name p)]
     [:dt.col-sm-3 "Git URL"] [:dd.col-sm-9 [:code (:git_url p)]]
     [:dt.col-sm-3 "Fetch state"]
     [:dd.col-sm-9
      [fetch-status-badge (:fetch-and-update p)]
      (when-let [t (relative-time (:last_fetched_at (:fetch-and-update p)))]
        [:span.ms-2.text-muted "last fetched " t])]
     (when (:branch_trigger_include_match p)
       [:<>
        [:dt.col-sm-3 "Include branches"] [:dd.col-sm-9 [:code (:branch_trigger_include_match p)]]])
     (when (:branch_trigger_exclude_match p)
       [:<>
        [:dt.col-sm-3 "Exclude branches"] [:dd.col-sm-9 [:code (:branch_trigger_exclude_match p)]]])
     (when (:branch_trigger_max_commit_age p)
       [:<>
        [:dt.col-sm-3 "Max commit age"] [:dd.col-sm-9 [:code (:branch_trigger_max_commit_age p)]]])
     (when (:remote_fetch_interval p)
       [:<>
        [:dt.col-sm-3 "Fetch interval"] [:dd.col-sm-9 [:code (:remote_fetch_interval p)]]])]))

(defn- detail-page []
  (fn []
    [:div.page.project
     [state/hidden-routing-state-component :did-change
      #(do (reset! _data* nil) (fetch!))]
     (if-not @_data*
       [:div "Loading..."]
       [:<>
        [:div.d-flex.align-items-center.gap-2.mb-3
         [:h2.mb-0 [icons/projects] " " (or (:name @_data*) (:id @_data*))]
         (when (-> @state/user* :is_admin)
           [:a.btn.btn-sm.btn-outline-secondary
            {:href (path :project-edit {:project-id (project-id)})}
            [icons/edit] " Edit"])]
        [project-metadata]
        (when (-> @state/user* :is_admin)
          [:div.mt-3.d-flex.gap-2
           [:form {:on-submit (fn [e]
                                (.preventDefault e)
                                (fetch-request "POST"
                                               (path :project-fetch {:project-id (project-id)})
                                               (fn [_] (set! js/window.location (project-url)))))}
            [:button.btn.btn-sm.btn-secondary {:type :submit}
             [icons/fetch] " Fetch now"]]
           [:button.btn.btn-sm.btn-danger
            {:on-click (fn [_]
                         (fetch-request "DELETE"
                                        (project-url)
                                        (fn [_] (set! js/window.location (path :projects)))))}
            [icons/delete] " Delete project"]])
        [:h3.mt-4 "Branches"]
        [branches-table]
        (when @state/debug?*
          [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @_data*))]]])])]))


;;; Edit page (route :project-edit) ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- save-project! [form*]
  (go (when-let [_res (-> {:method      :patch
                            :url         (project-url)
                            :json-params @form*}
                           http-client/request :chan <! http-client/filter-success)]
        (navigate! (project-url)))))

(defn- edit-page []
  (let [form* (reagent/atom nil)]
    (fn []
      [:div.page
       [state/hidden-routing-state-component :did-change
        #(do (reset! _data* nil) (reset! form* nil) (fetch!))]
       [:nav.mb-3
        [:a {:href (path :projects)} "Projects"]
        " / "
        [:a {:href (project-url)} (or (:name @_data*) (project-id))]
        " / Edit"]
       (if-not @_data*
         [:div "Loading..."]
         (let [p @_data*]
           (when (nil? @form*)
             (reset! form* {:name                          (or (:name p) "")
                            :git_url                       (or (:git_url p) "")
                            :branch_trigger_include_match  (or (:branch_trigger_include_match p) "")
                            :branch_trigger_exclude_match  (or (:branch_trigger_exclude_match p) "")
                            :branch_trigger_max_commit_age (or (:branch_trigger_max_commit_age p) "")
                            :remote_fetch_interval         (or (:remote_fetch_interval p) "")}))
           [:div.col-md-6
            [:h2 "Edit " (:name p)]
            [:form {:on-submit (fn [e] (.preventDefault e) (save-project! form*))}
             [:div.mb-3
              [:label.form-label "Name"]
              [:input.form-control
               {:type      "text"
                :required  true
                :value     (:name @form*)
                :on-change #(swap! form* assoc :name (.. % -target -value))}]]
             [:div.mb-3
              [:label.form-label "Git URL"]
              [:input.form-control
               {:type      "text"
                :required  true
                :value     (:git_url @form*)
                :on-change #(swap! form* assoc :git_url (.. % -target -value))}]]
             [:div.mb-3
              [:label.form-label "Include branches matching"]
              [:input.form-control
               {:type        "text"
                :placeholder "all branches"
                :value       (:branch_trigger_include_match @form*)
                :on-change   #(swap! form* assoc :branch_trigger_include_match (.. % -target -value))}]
              [:div.form-text "Regular expression. Leave blank to include all branches."]]
             [:div.mb-3
              [:label.form-label "Exclude branches matching"]
              [:input.form-control
               {:type        "text"
                :placeholder "none excluded"
                :value       (:branch_trigger_exclude_match @form*)
                :on-change   #(swap! form* assoc :branch_trigger_exclude_match (.. % -target -value))}]
              [:div.form-text "Regular expression. Leave blank to exclude none."]]
             [:div.mb-3
              [:label.form-label "Max commit age for auto-trigger"]
              [:input.form-control
               {:type        "text"
                :placeholder "use global default"
                :value       (:branch_trigger_max_commit_age @form*)
                :on-change   #(swap! form* assoc :branch_trigger_max_commit_age (.. % -target -value))}]
              [:div.form-text
               "PostgreSQL interval. Leave blank to use the global default from Settings. "
               "Examples: " [:code "48 hours"] ", " [:code "7 days"] "."]]
             [:div.mb-3
              [:label.form-label "Fetch interval"]
              [:input.form-control
               {:type        "text"
                :placeholder "default"
                :value       (:remote_fetch_interval @form*)
                :on-change   #(swap! form* assoc :remote_fetch_interval (.. % -target -value))}]
              [:div.form-text "PostgreSQL interval. How often to fetch from the remote."]]
             [:div.d-flex.gap-2
              [:button.btn.btn-primary {:type "submit"} "Save"]
              [:a.btn.btn-secondary {:href (project-url)} "Cancel"]]]]])])))


;;; New page (route :project-new) ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- create-project! [form*]
  (go (when-let [res (-> {:method      :post
                           :url         (path :projects)
                           :json-params @form*}
                          http-client/request :chan <! http-client/filter-success :body)]
        (navigate! (path :project {:project-id (:id res)})))))

(defn- new-page []
  (let [form* (reagent/atom {:id "" :name "" :git_url ""})]
    (fn []
      [:div.page
       [:nav.mb-3
        [:a {:href (path :projects)} "Projects"]
        " / New"]
       [:h2 "New Project"]
       [:div.col-md-6
        [:form {:on-submit (fn [e] (.preventDefault e) (create-project! form*))}
         [:div.mb-3
          [:label.form-label "Project ID"]
          [:input.form-control
           {:type      "text"
            :required  true
            :value     (:id @form*)
            :on-change #(swap! form* assoc :id (.. % -target -value))}]
          [:div.form-text "Unique identifier. Use lowercase letters, digits, and hyphens."]]
         [:div.mb-3
          [:label.form-label "Name"]
          [:input.form-control
           {:type      "text"
            :required  true
            :value     (:name @form*)
            :on-change #(swap! form* assoc :name (.. % -target -value))}]]
         [:div.mb-3
          [:label.form-label "Git URL"]
          [:input.form-control
           {:type      "text"
            :required  true
            :value     (:git_url @form*)
            :on-change #(swap! form* assoc :git_url (.. % -target -value))}]]
         [:div.d-flex.gap-2
          [:button.btn.btn-primary
           {:type     "submit"
            :disabled (not (and (presence (:id @form*))
                                (presence (:name @form*))
                                (presence (:git_url @form*))))}
           [icons/create] " Create"]
          [:a.btn.btn-secondary {:href (path :projects)} "Cancel"]]]]])))


;;; Page dispatch ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn page []
  (case (:name @state/routing*)
    :project      [detail-page]
    :project-edit [edit-page]
    :project-new  [new-page]
    [:div "Unknown route"]))

(def components {:page page})
