(ns cider-ci.server.resources.jobs.main
  (:require
    ["date-fns" :as date-fns]
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [cider-ci.server.state :as state]
    [clojure.string :as str]
    [reagent.core :as reagent]))


(defonce _data* (reagent/atom {}))

(def data* (reagent/reaction (get @_data* (:route @state/routing*))))


(defn- fetch-data []
  (http-client/route-cached-fetch _data* :reload true :reload-delay 15000))


(defn- current-filters []
  (let [qp (-> @state/routing* :query-params)]
    {:project (or (:project qp) "")
     :state   (or (:state qp) "")}))


(defn- apply-filters [project state-val]
  (let [qp (cond-> {}
              (not (str/blank? project))   (assoc :project project)
              (not (str/blank? state-val)) (assoc :state state-val))]
    (navigate! (path :jobs {} qp))))


(defn- state-cls [s]
  (case s
    "passed"    "bg-success"
    "failed"    "bg-danger"
    "executing" "bg-primary"
    "pending"   "bg-secondary"
    "aborted"   "bg-warning"
    "defective" "bg-warning"
    "bg-secondary"))


(defn- job-row [j]
  (let [short-commit (subs (:commit_id j) 0 7)
        date-str     (when (:created_at j)
                       (try
                         (date-fns/formatDistanceToNow
                           (js/Date. (:created_at j))
                           #js{:addSuffix true})
                         (catch js/Error _ (str (:created_at j)))))]
    ^{:key (:id j)}
    [:tr
     [:td [:span.badge {:class (state-cls (:state j))} (:state j)]]
     [:td
      [:a {:href (path :project-job {:project-id (:project_id j)
                                     :commit-id  (:commit_id j)
                                     :job-id     (:id j)})}
       [:code (:key j)]]
      (when (not= (:name j) (:key j))
        [:span.text-muted.ms-1.small (:name j)])]
     [:td
      [:a {:href (path :projects {:project-id (:project_id j)})}
       (:project_name j)]]
     [:td
      [:a {:href (path :project-commit {:project-id (:project_id j)
                                        :commit-id  (:commit_id j)})}
       [:code short-commit]]]
     [:td [:span.text-muted {:title (str (:created_at j))} date-str]]]))


(defn- filter-bar []
  (let [filters  (current-filters)
        project* (reagent/atom (:project filters))
        state*   (reagent/atom (:state filters))]
    (fn []
      [:form.row.g-2.mb-3.align-items-end
       {:on-submit (fn [e]
                     (.preventDefault e)
                     (apply-filters @project* @state*))}
       [:div.col-auto
        [:label.form-label.small.text-muted "State"]
        [:select.form-select.form-select-sm
         {:value     @state*
          :on-change #(reset! state* (.. % -target -value))}
         [:option {:value ""} "All"]
         (for [s ["pending" "executing" "passed" "failed" "aborted" "defective"]]
           ^{:key s} [:option {:value s} s])]]
       [:div.col-auto
        [:label.form-label.small.text-muted "Project"]
        [:input.form-control.form-control-sm
         {:type        "text"
          :placeholder "project-id"
          :value       @project*
          :on-change   #(reset! project* (.. % -target -value))}]]
       [:div.col-auto
        [:button.btn.btn-sm.btn-outline-primary {:type "submit"} "Filter"]]
       (when (or (not (str/blank? (:project filters)))
                 (not (str/blank? (:state filters))))
         [:div.col-auto
          [:button.btn.btn-sm.btn-outline-secondary
           {:type     "button"
            :on-click #(do (reset! project* "") (reset! state* "")
                           (apply-filters "" ""))}
           "Clear"]])])))


(defn- jobs-table [jobs]
  (if (empty? jobs)
    [:p.text-muted.mt-3 "No jobs found."]
    [:table.table.table-sm.table-hover
     [:thead
      [:tr
       [:th "State"] [:th "Job"] [:th "Project"] [:th "Commit"] [:th "Created"]]]
     [:tbody
      (for [j jobs] [job-row j])]]))


(defn page []
  [:div.page.jobs
   [state/hidden-routing-state-component :did-change fetch-data]
   [:h2.mb-3 [icons/jobs] " Jobs"]
   [filter-bar]
   (if-not (seq @data*)
     [:div "Loading..."]
     [jobs-table (:jobs @data*)])])


(defn page-nav [] [:<>])


(def components {:page     page
                 :page-nav page-nav})
