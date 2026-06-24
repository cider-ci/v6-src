(ns cider-ci.server.resources.projects.branch
  (:require
   ["date-fns" :as date-fns]
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


(defn- state-badge [s]
  (let [cls (case s
              "passed"    "bg-success"
              "failed"    "bg-danger"
              "executing" "bg-primary"
              "pending"   "bg-secondary"
              "aborted"   "bg-warning"
              "bg-secondary")]
    [:span.badge {:class cls} s]))


(defn- relative-time [s]
  (when-let [d (presence s)]
    (date-fns/formatDistance (js/Date. d) (js/Date.) (clj->js {:addSuffix true}))))


(defn- signature-cell [fingerprint]
  (if fingerprint
    [:span.text-success {:title fingerprint} [:i.fas.fa-check-circle] " signed"]
    [:span.text-muted "—"]))


(defn- overall-state [jobs]
  (let [states (set (map :state jobs))]
    (cond
      (some states ["failed" "defective"]) "failed"
      (some #{"executing"} states)         "executing"
      (some #{"pending"} states)           "pending"
      (some #{"aborted"} states)           "aborted"
      (seq jobs)                           "passed"
      :else                                nil)))


(defn- status-border [s]
  (case s
    "passed"    "border-success"
    "failed"    "border-danger"
    "executing" "border-primary"
    "pending"   "border-secondary"
    "aborted"   "border-warning"
    "border-secondary"))


(defn- tip-commit-panel []
  (let [b    @data*
        tc   (:tip_commit b)
        jobs (or (:jobs tc) [])
        s    (overall-state jobs)]
    (when tc
      [:div.card.mb-4 {:class (str "border-2 " (status-border s))}
       [:div.card-header.d-flex.align-items-center.gap-2
        (when s [state-badge s])
        [:a {:href (path :project-commit {:project-id (project-id) :commit-id (:id tc)})}
         [:code.small (subs (:id tc) 0 8)]]
        [:span.text-muted.text-truncate {:style {:max-width "40em"}} (:subject tc)]]
       [:div.card-body.py-2
        (if (seq jobs)
          [:div.d-flex.flex-wrap.gap-2
           (for [j jobs]
             ^{:key (:id j)}
             [:a.text-decoration-none
              {:href (path :project-job {:project-id (project-id)
                                         :commit-id  (:id tc)
                                         :job-id     (:id j)})}
              [state-badge (:state j)]
              [:span.ms-1.small.text-body (:name j)]])]
          [:span.text-muted "No jobs for this commit. "
           [:a {:href (path :project-jobs {:project-id (project-id) :commit-id (:id tc)})}
            "Trigger a job →"]])]])))


(defn- commits-table []
  (let [commits (:commits @data*)
        limit   (:commits_limit @data*)]
    (if (empty? commits)
      [:p.text-muted "No commits in this branch."]
      [:<>
       [:table.table.table-sm.table-hover.commits
        [:thead
         [:tr [:th "Commit"] [:th "Date"] [:th "Author"] [:th "Signed"] [:th "Jobs"] [:th "Subject"]]]
        [:tbody
         (for [c commits]
           ^{:key (:id c)}
           [:tr
            [:td [:a {:href (path :project-commit
                                  {:project-id (project-id) :commit-id (:id c)})}
                  [:code.small (subs (:id c) 0 8)]]]
            [:td [:small (or (relative-time (:committer_date c)) "—")]]
            [:td (:author_name c)]
            [:td [signature-cell (:signature_fingerprint c)]]
            [:td
             (if (seq (:jobs c))
               (for [j (:jobs c)]
                 ^{:key (:id j)}
                 [:a.me-1 {:href (path :project-job {:project-id (project-id)
                                                      :commit-id  (:id c)
                                                      :job-id     (:id j)})}
                  [state-badge (:state j)]])
               [:span.text-muted "—"])]
            [:td.text-truncate {:style {:max-width "28em"}} (:subject c)]])]]
       (when (>= (count commits) limit)
         [:p.text-muted.small (str "Showing the most recent " limit " commits.")])])))


(defn page []
  [:div.page.branch
   [state/hidden-routing-state-component :did-change #(fetch-data)]
   (if-not (seq @data*)
     [:div "Loading..."]
     (let [b @data*]
       [:<>
        [:nav.mb-3
         [:a {:href (path :project {:project-id (project-id)})}
          [icons/projects] " " (project-id)]]
        [:h2 [:i.fas.fa-code-branch] " " (:name b)]
        [tip-commit-panel]
        [:h3.mt-4 "Recent commits"]
        [commits-table]
        (when @state/debug?*
          [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])]))])


(def components {:page page})
