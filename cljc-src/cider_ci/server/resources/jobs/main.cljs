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

(def ^:private page-size 25)


(defn- fetch-data []
  (http-client/route-cached-fetch _data* :reload true :reload-delay 15000))

(defn- current-qp []
  (:query-params @state/routing*))

(defn- apply-filters
  ([project state-val sort-val] (apply-filters project state-val sort-val nil))
  ([project state-val sort-val page]
   (let [qp (cond-> {}
               (not (str/blank? project))   (assoc :project project)
               (not (str/blank? state-val)) (assoc :state state-val)
               (= sort-val "trial")         (assoc :sort "trial")
               (and page (> page 1))        (assoc :page (str page)))]
     (navigate! (path :jobs {} qp)))))


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
      [:a {:href (path :project {:project-id (:project_id j)})}
       (:project_name j)]
      [:button.btn.btn-link.btn-sm.p-0.ms-1.text-muted
       {:on-click #(let [qp (current-qp)]
                     (apply-filters (:project_id j)
                                    (or (:state qp) "")
                                    (or (:sort qp) "")))
        :title    "Filter by this project"
        :type     "button"}
       [icons/filter-icon]]]
     [:td
      [:a {:href (path :project-commit {:project-id (:project_id j)
                                        :commit-id  (:commit_id j)})}
       [:code short-commit]]]
     [:td [:span.text-muted {:title (str (:created_at j))} date-str]]]))


(defn- filter-bar []
  (reagent/with-let
    [project* (reagent/atom "")
     state*   (reagent/atom "")
     sort*    (reagent/atom "")
     _sync    (reagent/track!
                #(let [qp (:query-params @state/routing*)]
                   (reset! project* (or (:project qp) ""))
                   (reset! state*   (or (:state qp) ""))
                   (reset! sort*    (or (:sort qp) ""))))]
    (let [active? (or (not (str/blank? @project*))
                      (not (str/blank? @state*))
                      (not (str/blank? @sort*)))]
      [:form.row.g-2.mb-3.align-items-end
       {:on-submit (fn [e]
                     (.preventDefault e)
                     (apply-filters @project* @state* @sort*))}
       [:div.col-auto
        [:label.form-label.small.text-muted {:html-for "state-filter"} "State"]
        [:select.form-select.form-select-sm
         {:id        "state-filter"
          :value     @state*
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
        [:label.form-label.small.text-muted {:html-for "sort-filter"} "Sort"]
        [:select.form-select.form-select-sm
         {:id        "sort-filter"
          :value     @sort*
          :on-change #(reset! sort* (.. % -target -value))}
         [:option {:value ""} "By creation"]
         [:option {:value "trial"} "By latest trial"]]]
       [:div.col-auto
        [:label.form-label.small.text-muted " "]
        [:button.btn.btn-sm.btn-outline-primary {:type "submit"} "Filter"]]
       (when active?
         [:div.col-auto
          [:label.form-label.small.text-muted " "]
          [:button.btn.btn-sm.btn-outline-secondary
           {:type     "button"
            :on-click #(apply-filters "" "" "")}
           "Clear"]])
       [:div.col-auto
        [:label.form-label.small.text-muted " "]
        [:button.btn.btn-sm.btn-outline-secondary
         {:type     "button"
          :on-click fetch-data
          :title    "Reload"}
         [icons/fetch]]]])
    (finally
      (reagent/dispose! _sync))))


(defn- jobs-table [jobs]
  (if (empty? jobs)
    [:p.text-muted.mt-3 "No jobs found."]
    [:table.table.table-sm.table-hover
     [:thead
      [:tr
       [:th "State"] [:th "Job"] [:th "Project"] [:th "Commit"] [:th "Created"]]]
     [:tbody
      (for [j jobs] [job-row j])]]))


(defn- pagination [total page-n]
  (let [qp          (current-qp)
        project     (or (:project qp) "")
        state-val   (or (:state qp) "")
        sort-val    (or (:sort qp) "")
        total-pages (js/Math.ceil (/ total page-size))]
    (when (> total-pages 1)
      [:div.d-flex.align-items-center.gap-3.mt-3
       [:small.text-muted (str "Page " page-n " of " total-pages " (" total " jobs)")]
       [:div.btn-group.btn-group-sm
        [:button.btn.btn-outline-secondary
         {:disabled (= page-n 1)
          :on-click #(apply-filters project state-val sort-val (dec page-n))}
         "← Prev"]
        [:button.btn.btn-outline-secondary
         {:disabled (= page-n total-pages)
          :on-click #(apply-filters project state-val sort-val (inc page-n))}
         "Next →"]]])))


(defn page []
  [:div.page.jobs
   [state/hidden-routing-state-component :did-change fetch-data]
   [:h2.mb-3 [icons/jobs] " Jobs"]
   [filter-bar]
   (if-not (seq @data*)
     [:div "Loading..."]
     [:<>
      [jobs-table (:jobs @data*)]
      (when (:total @data*)
        [pagination (:total @data*) (or (:page @data*) 1)])])])


(defn page-nav [] [:<>])


(def components {:page     page
                 :page-nav page-nav})
