(ns cider-ci.server.resources.commits.main
  (:require
    ["date-fns" :as date-fns]
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [cider-ci.server.state :as state]
    [cljs.pprint :refer [pprint]]
    [clojure.string :as str]
    [reagent.core :as reagent]))


(defonce _data* (reagent/atom {}))

(def data* (reagent/reaction (get @_data* (:route @state/routing*))))


(defn- fetch-data []
  (http-client/route-cached-fetch _data* :reload true :reload-delay 15000))


(defn- current-filters []
  (let [qp (-> @state/routing* :query-params)]
    {:project (or (:project qp) "")
     :branch  (or (:branch qp) "")}))


(defn- apply-filters [project branch]
  (let [qp (cond-> {}
              (not (str/blank? project)) (assoc :project project)
              (not (str/blank? branch))  (assoc :branch branch))]
    (navigate! (path :commits {} qp))))


(defn- state-cls [s]
  (case s
    "passed"    "bg-success"
    "failed"    "bg-danger"
    "executing" "bg-primary"
    "pending"   "bg-secondary"
    "aborted"   "bg-warning"
    "bg-secondary"))


(defn- job-badge [commit-id job]
  ^{:key (:id job)}
  [:a.badge.text-decoration-none.me-1
   {:href  (path :project-job {:project-id (:project_id job)
                               :commit-id  commit-id
                               :job-id     (:id job)})
    :class (state-cls (:state job))
    :title (:name job)}
   (:key job)])


(defn- branch-tag [b]
  ^{:key (str (:repository_id b) "/" (:name b))}
  [:span.badge.bg-light.text-dark.border.me-1
   {:title (:repository_name b)}
   [:a {:href (path :project-branch {:project-id  (:repository_id b)
                                     :branch-name (:name b)})}
    (:name b)]])


(defn- commit-row [c]
  (let [short-id   (subs (:id c) 0 7)
        project-id (-> c :branches first :repository_id)
        date-str   (when (:committer_date c)
                     (try
                       (date-fns/formatDistanceToNow
                         (js/Date. (:committer_date c))
                         #js{:addSuffix true})
                       (catch js/Error _ (str (:committer_date c)))))]
    ^{:key (:id c)}
    [:tr
     [:td
      [:a {:href (path :project-commit {:project-id project-id
                                        :commit-id  (:id c)})}
       [:code short-id]]]
     [:td
      [:span {:title (:subject c)}
       (let [s (:subject c)]
         (if (> (count s) 72) (str (subs s 0 72) "…") s))]]
     [:td (:author_name c)]
     [:td [:span.text-muted {:title (str (:committer_date c))} date-str]]
     [:td (for [b (:branches c)] [branch-tag b])]
     [:td
      (for [j (:jobs c)] [job-badge (:id c) j])
      [:a.ms-1 {:href  (path :project-jobs {:project-id project-id
                                             :commit-id  (:id c)})
                :title "View / trigger jobs for this commit"}
       [icons/create]]]]))


(defn- filter-bar []
  (let [filters (current-filters)
        project* (reagent/atom (:project filters))
        branch*  (reagent/atom (:branch filters))]
    (fn []
      [:form.row.g-2.mb-3.align-items-end
       {:on-submit (fn [e]
                     (.preventDefault e)
                     (apply-filters @project* @branch*))}
       [:div.col-auto
        [:label.form-label.small.text-muted "Project"]
        [:input.form-control.form-control-sm
         {:type        "text"
          :placeholder "project-id"
          :value       @project*
          :on-change   #(reset! project* (.. % -target -value))}]]
       [:div.col-auto
        [:label.form-label.small.text-muted "Branch (regex)"]
        [:input.form-control.form-control-sm
         {:type        "text"
          :placeholder "^master$"
          :value       @branch*
          :on-change   #(reset! branch* (.. % -target -value))}]]
       [:div.col-auto
        [:button.btn.btn-sm.btn-outline-primary {:type "submit"} "Filter"]]
       (when (or (not (str/blank? (:project filters)))
                 (not (str/blank? (:branch filters))))
         [:div.col-auto
          [:button.btn.btn-sm.btn-outline-secondary
           {:type     "button"
            :on-click #(do (reset! project* "") (reset! branch* "")
                           (apply-filters "" ""))}
           "Clear"]])])))


(defn- commits-table [commits]
  (if (empty? commits)
    [:p.text-muted.mt-3 "No branch-head commits found."]
    [:table.table.table-sm.table-hover
     [:thead
      [:tr
       [:th "Commit"] [:th "Subject"] [:th "Author"]
       [:th "Date"] [:th "Branches"] [:th "CI"]]]
     [:tbody
      (for [c commits] [commit-row c])]]))


(defn page []
  [:div.page.commits
   [state/hidden-routing-state-component :did-change fetch-data]
   [:h2.mb-3 [icons/commits] " Commits"]
   [filter-bar]
   (if-not (seq @data*)
     [:div "Loading..."]
     [commits-table (:commits @data*)])
   (when @state/debug?*
     [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])])


(defn page-nav [] [:<>])


(def components {:page page
                 :page-nav page-nav})
