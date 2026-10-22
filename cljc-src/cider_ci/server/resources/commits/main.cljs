(ns cider-ci.server.resources.commits.main
  (:require
    ["date-fns" :as date-fns]
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.html.job-states :as job-states]
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


(def ^:private filter-keys [:project :branch :author :mine])


(defn- current-filters []
  (let [qp (-> @state/routing* :query-params)]
    (into {} (for [k filter-keys] [k (str (get qp k ""))]))))


(defn- apply-filters
  "Navigates to the commits page with the given filters; blank values (and
   :mine other than \"true\") are dropped from the query string."
  [filters]
  (let [qp (into {} (for [[k v] filters
                          :let [v (str v)]
                          :when (if (= k :mine) (= v "true") (not (str/blank? v)))]
                      [k v]))]
    (navigate! (path :commits {} qp))))


(defn- update-filters [& kvs]
  (apply-filters (apply assoc (current-filters) kvs)))


(defn- regex-quote [s]
  (str/replace (str s) #"[.*+?^${}()|\[\]\\]" "\\$&"))


(defn- jobs-summary-cell
  "One count badge per job state group (failed incl. defective/aborted,
   passed, executing, pending), each linking to the commit's jobs page
   filtered by that group; a dash when the commit has no jobs."
  [c project-id]
  (let [jobs   (:jobs c)
        counts (job-states/count-by-group jobs)]
    [:td.jobs-summary.text-nowrap
     (if (empty? jobs)
       [:a.text-muted.text-decoration-none
        {:href  (path :project-jobs {:project-id project-id :commit-id (:id c)})
         :title "no jobs yet: view / trigger jobs"}
        "—"]
       (for [[k label _ cls] job-states/groups
             :let [n (get counts k 0)]
             :when (pos? n)]
         ^{:key k}
         [:a.badge.text-decoration-none.me-1
          {:href  (path :project-jobs {:project-id project-id :commit-id (:id c)} {:state (name k)})
           :class cls
           :data-state (name k)
           :title (str n " " label (if (= n 1) " job" " jobs"))}
          n]))]))


(defn- project-cell [branches]
  (let [projects (->> branches
                      (map (fn [b] {:id (:repository_id b) :name (:repository_name b)}))
                      distinct
                      (sort-by :name))]
    [:td
     (for [{:keys [id name]} projects]
       ^{:key id}
       [:span.me-2.text-nowrap
        [:a.me-1 {:href (path :project {:project-id id})} name]
        [:a.me-1.text-muted
         {:href     "#"
          :title    (str "Filter by " name)
          :on-click (fn [e]
                      (.preventDefault e)
                      (update-filters :project id))}
         [icons/filter-icon]]
        [:a {:href (path :project {:project-id id}) :title "Project page"}
         [icons/config]]])]))


(defn- branch-tag [b]
  ^{:key (str (:repository_id b) "/" (:name b))}
  [:span.badge.bg-light.text-dark.border.me-1
   {:title (:repository_name b)}
   [:a.me-1 {:href (path :project-branch {:project-id  (:repository_id b)
                                           :branch-name (:name b)})}
    (:name b)]
   [:a.text-muted
    {:href     "#"
     :title    (str "Filter by branch " (:name b))
     :on-click (fn [e]
                 (.preventDefault e)
                 (update-filters :branch (str "^" (regex-quote (:name b)) "$")))}
    [icons/filter-icon]]])


(defn- ancestor-branch-tag [b]
  ^{:key (str "anc-" (:repository_id b) "/" (:name b))}
  [:span.badge.bg-light.border.me-1.text-muted
   {:title (str (:repository_name b) " (+" (:distance b) " commits ahead)")}
   [:sub.me-1 (:distance b)]
   [:a.me-1.text-muted {:href (path :project-branch {:project-id  (:repository_id b)
                                                      :branch-name (:name b)})}
    (:name b)]
   [:a.text-muted
    {:href     "#"
     :title    (str "Filter by branch " (:name b))
     :on-click (fn [e]
                 (.preventDefault e)
                 (update-filters :branch (str "^" (regex-quote (:name b)) "$")))}
    [icons/filter-icon]]])


(defn- author-cell [c]
  (let [author (:author_name c)]
    [:td.author.text-nowrap
     [:span.me-1 {:title (:author_email c)} author]
     (when-not (str/blank? author)
       [:a.text-muted
        {:href     "#"
         :title    (str "Filter by author " author)
         :on-click (fn [e]
                     (.preventDefault e)
                     (update-filters :author (str "^" (regex-quote author) "$")))}
        [icons/filter-icon]])]))


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
     [jobs-summary-cell c project-id]
     [:td
      [:a {:href (path :project-commit {:project-id project-id
                                        :commit-id  (:id c)})}
       [:code short-id]]]
     [:td
      [:span {:title (:subject c)}
       (let [s (:subject c)]
         (if (> (count s) 72) (str (subs s 0 72) "…") s))]]
     [project-cell (:branches c)]
     [author-cell c]
     [:td [:span.text-muted {:title (str (:committer_date c))} date-str]]
     [:td
      (for [b (:branches c)] [branch-tag b])
      (for [b (:ancestor_branches c)] [ancestor-branch-tag b])]]))


(defn- text-filter [label placeholder value*]
  [:div.col-auto
   [:label.form-label.small.text-muted label]
   [:input.form-control.form-control-sm
    {:type        "text"
     :placeholder placeholder
     :value       @value*
     :on-change   #(reset! value* (.. % -target -value))}]])


(defn- filter-bar []
  (reagent/with-let
    [project* (reagent/atom "")
     branch*  (reagent/atom "")
     author*  (reagent/atom "")
     mine*    (reagent/atom false)
     _sync    (reagent/track!
                #(let [{:keys [project branch author mine]} (current-filters)]
                   (reset! project* project)
                   (reset! branch*  branch)
                   (reset! author*  author)
                   (reset! mine*    (= mine "true"))))]
    (let [active? (or (some (comp not str/blank?) [@project* @branch* @author*])
                      @mine*)]
      [:form.row.g-2.mb-3.align-items-end
       {:on-submit (fn [e]
                     (.preventDefault e)
                     (apply-filters {:project @project* :branch @branch*
                                     :author @author* :mine (str @mine*)}))}
       [text-filter "Project" "project-id" project*]
       [text-filter "Branch (regex)" "^master$" branch*]
       [text-filter "Author (regex)" "name or email" author*]
       [:div.col-auto
        [:div.form-check.mb-1
         {:title "Commits authored or committed with one of your email addresses"}
         [:input.form-check-input
          {:type      "checkbox"
           :id        "commits-filter-mine"
           :checked   @mine*
           :on-change #(reset! mine* (.. % -target -checked))}]
         [:label.form-check-label.small {:for "commits-filter-mine"} "My commits"]]]
       [:div.col-auto
        [:button.btn.btn-sm.btn-outline-primary {:type "submit"} "Filter"]]
       (when active?
         [:div.col-auto
          [:button.btn.btn-sm.btn-outline-secondary
           {:type     "button"
            :on-click #(apply-filters {})}
           "Clear"]])])
    (finally
      (reagent/dispose! _sync))))


(defn- commits-table [commits]
  (if (empty? commits)
    [:p.text-muted.mt-3 "No branch-head commits found."]
    [:table.table.table-sm.table-hover
     [:thead
      [:tr
       [:th {:title "jobs: failed | passed | executing | pending"} "CI"]
       [:th "Commit"] [:th "Subject"] [:th "Project"] [:th "Author"]
       [:th "Date"] [:th "Branches"]]]
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
