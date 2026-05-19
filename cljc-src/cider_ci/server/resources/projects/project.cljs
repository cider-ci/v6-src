(ns cider-ci.server.resources.projects.project
  (:require
   ["date-fns" :as date-fns]
   [cider-ci.server.html.icons :as icons]
   [cider-ci.server.http.client.main :as http-client]
   [cider-ci.server.state :as state]
   [cider-ci.utils.core :refer [presence]]
   [cljs.pprint :refer [pprint]]
   [reagent.core :as reagent]))


(defonce _data* (reagent/atom {}))

(def data* (reagent/reaction (get @_data* (:path @state/routing*))))


(defn- fetch-data [& _]
  (http-client/route-cached-fetch _data* :reload true :reload-delay 500))


(defn- relative-time [iso-string]
  (when-let [s (presence iso-string)]
    (date-fns/formatDistance (js/Date. s) (js/Date.) (clj->js {:addSuffix true}))))


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
           [:td (:name b)]
           [:td [:code.small (some-> (:current_commit_id b) (subs 0 8))]]
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
      [:h3.mt-4 "Branches"]
      [branches-table]
      (when @state/debug?*
        [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])])])


(def components {:page page})
