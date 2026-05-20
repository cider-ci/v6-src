(ns cider-ci.server.resources.projects.project
  (:require
   ["date-fns" :as date-fns]
   [cider-ci.server.html.icons :as icons]
   [cider-ci.server.http.anti-csrf.main :as anti-csrf]
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


(defn- relative-time [iso-string]
  (when-let [s (presence iso-string)]
    (date-fns/formatDistance (js/Date. s) (js/Date.) (clj->js {:addSuffix true}))))


(defn- project-id []
  (-> @state/routing* :path-params :project-id))


;;; actions ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fetch-request [method url on-success]
  (-> (js/fetch url (clj->js {:method method
                               :credentials "same-origin"
                               :headers {"accept" "application/json"
                                         "x-csrf-token" (anti-csrf/token)}}))
      (.then on-success)))

(defn- admin-actions []
  (when (-> @state/user* :is_admin)
    [:div.mt-3.d-flex.gap-2
     [:form {:on-submit (fn [e]
                          (.preventDefault e)
                          (fetch-request "POST"
                                         (path :project-fetch {:project-id (project-id)})
                                         (fn [_] (set! js/window.location
                                                        (path :project {:project-id (project-id)})))))}
      [:button.btn.btn-sm.btn-secondary {:type :submit}
       [:i.fas.fa-rotate] " Fetch now"]]
     [:button.btn.btn-sm.btn-danger
      {:on-click (fn [_]
                   (fetch-request "DELETE"
                                  (path :project {:project-id (project-id)})
                                  (fn [_] (set! js/window.location (path :projects)))))}
      [:i.fas.fa-trash] " Delete project"]]))


;;; project metadata ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fetch-status-badge [fetch-and-update]
  (let [state (:state fetch-and-update)
        bg    (case state "ok" "bg-success" "bg-warning")]
    [:span.badge {:class bg} (or state "unknown")]))

(defn- project-metadata []
  (let [project @data*]
    [:dl.row
     [:dt.col-sm-3 "ID"]      [:dd.col-sm-9 [:code (:id project)]]
     [:dt.col-sm-3 "Name"]    [:dd.col-sm-9 (:name project)]
     [:dt.col-sm-3 "Git URL"] [:dd.col-sm-9 [:code (:git_url project)]]
     [:dt.col-sm-3 "Fetch state"]
     [:dd.col-sm-9
      [fetch-status-badge (:fetch-and-update project)]
      (when-let [t (relative-time (:last_fetched_at (:fetch-and-update project)))]
        [:span.ms-2.text-muted "last fetched " t])]]))


;;; branches table ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- signature-cell [fingerprint]
  (if fingerprint
    [:span.text-success {:title fingerprint} [:i.fas.fa-check-circle] " signed"]
    [:span.text-muted "—"]))

(defn- branches-table []
  (let [branches (:branches @data*)]
    (if (empty? branches)
      [:p.text-muted "No branches yet."]
      [:table.table.table-sm.table-hover.branches
       [:thead
        [:tr [:th "Branch"] [:th "Last commit"] [:th "Date"] [:th "Signed"] [:th "Subject"]]]
       [:tbody
        (for [b branches]
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


;;; page ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn page []
  [:div.page.project
   [state/hidden-routing-state-component :did-change #(fetch-data)]
   (if-not (seq @data*)
     [:div "Loading..."]
     [:<>
      [:h2 [icons/projects] " " (or (:name @data*) (:id @data*))]
      [project-metadata]
      [admin-actions]
      [:h3.mt-4 "Branches"]
      [branches-table]
      (when @state/debug?*
        [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])])])


(def components {:page page})
