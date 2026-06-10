(ns cider-ci.server.resources.projects.jobs
  (:require
   [cider-ci.server.html.icons :as icons]
   [cider-ci.server.http.anti-csrf.main :as anti-csrf]
   [cider-ci.server.http.client.main :as http-client]
   [cider-ci.server.routes :refer [path]]
   [cider-ci.server.state :as state]
   [cljs.pprint :refer [pprint]]
   [reagent.core :as reagent]))


(defonce _data* (reagent/atom {}))

(def data* (reagent/reaction (get @_data* (:route @state/routing*))))


(defn- fetch-data [& _]
  (http-client/route-cached-fetch _data* :reload true :reload-delay 10000))


(defn- project-id []
  (-> @state/routing* :path-params :project-id))

(defn- commit-id []
  (-> @state/routing* :path-params :commit-id))

(defn- job-id []
  (-> @state/routing* :path-params :job-id))


(defn- trigger-job [job-key]
  (-> (js/fetch (path :project-jobs {:project-id (project-id)
                                     :commit-id  (commit-id)})
                (clj->js {:method      "POST"
                           :credentials "same-origin"
                           :headers     {"content-type" "application/json"
                                         "accept"       "application/json"
                                         "x-csrf-token" (anti-csrf/token)}
                           :body        (.stringify js/JSON (clj->js {:key job-key}))}))
      (.then (fn [_] (http-client/route-cached-fetch _data* :reload true)))))


(defn- state-badge [s]
  (let [cls (case s
              "passed"    "bg-success"
              "failed"    "bg-danger"
              "executing" "bg-primary"
              "pending"   "bg-secondary"
              "aborted"   "bg-warning"
              "bg-secondary")]
    [:span.badge {:class cls} s]))


;;; Jobs list page (route :project-jobs)

(defn- job-row [j]
  ^{:key (:id j)}
  [:tr
   [:td
    [:a {:href (path :project-job {:project-id (project-id)
                                   :commit-id  (commit-id)
                                   :job-id     (:id j)})}
     [:code (:key j)]]]
   [:td (:name j)]
   [:td [state-badge (:state j)]]
   [:td [:span.text-muted (str (:created_at j))]]])


(defn- created-jobs-panel [jobs]
  (when (seq jobs)
    [:<>
     [:h4.mt-4 "Recorded Jobs"]
     [:table.table.table-sm
      [:thead
       [:tr
        [:th "Key"] [:th "Name"] [:th "State"] [:th "Created"]]]
      [:tbody
       (for [j jobs] [job-row j])]]]))


(defn- available-jobs-panel [jobs]
  [:<>
   [:h4.mt-3 "Available Jobs"]
   (if (seq jobs)
     [:table.table.table-sm
      [:thead
       [:tr [:th "Key"] [:th "Name"] [:th ""]]]
      [:tbody
       (for [j jobs]
         ^{:key (:key j)}
         [:tr
          [:td [:code (:key j)]]
          [:td (:name j)]
          [:td
           [:button.btn.btn-sm.btn-outline-primary
            {:on-click #(trigger-job (:key j))}
            [:i.fas.fa-play] " Run"]]])]]
     [:p.text-muted "No jobs defined in cider-ci.yml for this commit."])])


(defn- jobs-list-page []
  [:div.page.jobs
   [state/hidden-routing-state-component :did-change #(fetch-data)]
   (if-not (seq @data*)
     [:div "Loading..."]
     [:<>
      [:nav.mb-3
       [:a {:href (path :project {:project-id (project-id)})}
        [icons/projects] " " (project-id)]
       " / "
       [:a {:href (path :project-commit {:project-id (project-id) :commit-id (commit-id)})}
        [:code (subs (commit-id) 0 8)]]
       " / Jobs"]
      [available-jobs-panel (:available @data*)]
      [created-jobs-panel (:created @data*)]
      (when @state/debug?*
        [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])])])


;;; Job detail page (route :project-job)

(defn- task-row [t]
  ^{:key (:id t)}
  [:tr
   [:td [:code (:name t)]]
   [:td [state-badge (:state t)]]
   [:td [:span.text-muted (str (:created_at t))]]
   [:td
    (for [trial (:trials t)]
      ^{:key (:id trial)}
      [:a {:href   (str "/trials/" (:id trial) "/attachments/log")
           :target "_blank"
           :class  "me-2"}
       "Log"])]])


(defn- tasks-panel [tasks]
  [:<>
   [:h4.mt-3 "Tasks"]
   (if (seq tasks)
     [:table.table.table-sm
      [:thead
       [:tr [:th "Name"] [:th "State"] [:th "Created"] [:th "Log"]]]
      [:tbody
       (for [t tasks] [task-row t])]]
     [:p.text-muted "No tasks for this job."])])


(defn- retry-job! []
  (-> (js/fetch (path :project-job-retry {:project-id (project-id)
                                          :commit-id  (commit-id)
                                          :job-id     (job-id)})
                (clj->js {:method      "POST"
                           :credentials "same-origin"
                           :headers     {"content-type" "application/json"
                                         "accept"       "application/json"
                                         "x-csrf-token" (anti-csrf/token)}}))
      (.then (fn [_] (fetch-data)))))


(defn- job-detail-page []
  [:div.page.job
   [state/hidden-routing-state-component :did-change #(fetch-data)]
   (if-not (seq @data*)
     [:div "Loading..."]
     (let [job   @data*
           retry? (#{"failed" "defective" "aborted"} (:state job))]
       [:<>
        [:nav.mb-3
         [:a {:href (path :project {:project-id (project-id)})}
          [icons/projects] " " (project-id)]
         " / "
         [:a {:href (path :project-commit {:project-id (project-id) :commit-id (commit-id)})}
          [:code (subs (commit-id) 0 8)]]
         " / "
         [:a {:href (path :project-jobs {:project-id (project-id) :commit-id (commit-id)})}
          "Jobs"]
         " / "
         [:code (:key job)]]
        [:h3 (:name job) " " [state-badge (:state job)]
         (when retry?
           [:button.btn.btn-sm.btn-outline-secondary.ms-2
            {:on-click retry-job!}
            [:i.fas.fa-rotate-right] " Retry"])]
        [tasks-panel (:tasks job)]
        (when @state/debug?*
          [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])]))])


(defn page []
  (case (:name @state/routing*)
    :project-job  [job-detail-page]
    :project-jobs [jobs-list-page]
    [:div "Unknown route"]))


(def components {:page page})
