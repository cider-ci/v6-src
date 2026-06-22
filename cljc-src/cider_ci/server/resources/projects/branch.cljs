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
        (when-let [cid (:current_commit_id b)]
          [:p
           "Current commit: "
           [:a {:href (path :project-commit
                            {:project-id (project-id) :commit-id cid})}
            [:code (subs cid 0 8)]]])
        [:h3.mt-4 "Recent commits"]
        [commits-table]
        (when @state/debug?*
          [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])]))])


(def components {:page page})
