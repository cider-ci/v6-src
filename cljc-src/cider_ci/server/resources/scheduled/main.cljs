(ns cider-ci.server.resources.scheduled.main
  "The next cron firings over all projects: when, where, what, and whether
   the firing will create a job."
  (:require
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.html.utils.times :as times]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.routes :refer [path navigate!]]
    [cider-ci.server.state :as state]
    [cljs.pprint :refer [pprint]]
    [clojure.string :as str]
    [reagent.core :as reagent]))


(defonce _data* (reagent/atom {}))

(def data* (reagent/reaction (get @_data* (:route @state/routing*))))

(defn- fetch-data []
  (http-client/route-cached-fetch _data* :reload true :reload-delay 30000))

(defn- limit-select []
  (let [current (or (:limit @data*) 20)]
    [:select.form-select.form-select-sm.w-auto.d-inline-block.ms-2.scheduled-limit
     {:value current
      :on-change #(navigate! (str (path :scheduled) "?limit=" (.. % -target -value)))}
     (for [n [20 50 100 200]]
       ^{:key n} [:option {:value n} (str "next " n)])]))

(defn- effect-cell
  "What the firing will do: create the job, replace it (rerun), nothing
   because it exists, or wait for dependencies."
  [{:keys [rerun existing_state existing_created_at unmet_deps]}]
  (cond
    (seq unmet_deps)
    [:span.text-warning [icons/warning] " waits for " (str/join ", " unmet_deps)]

    (and existing_state rerun)
    [:span.text-muted "replaces the " existing_state " job"
     (when existing_created_at [:<> " of " [times/stamp existing_created_at]])]

    existing_state
    [:span.text-muted "nothing: job exists (" existing_state ")"]

    :else
    [:span.text-success "creates the job"]))

(defn- row [{:keys [project_id branch_name commit_id job_key job_name cron
                    cron_description rerun next_at] :as r}]
  ^{:key (str project_id branch_name commit_id job_key cron)}
  [:tr.scheduled-row
   [:td.text-nowrap [times/stamp next_at]
    [:div.small.text-muted (subs next_at 0 16) "Z"]]
   [:td [:code {:title cron_description} cron]
    (when rerun [:span.badge.bg-secondary.ms-1 {:title "an existing finished job is replaced"} "rerun"])]
   [:td [:a {:href (path :project {:project-id project_id})} project_id]]
   [:td [:a {:href (path :project-branch {:project-id project_id :branch-name branch_name})} branch_name]]
   [:td [:a {:href (path :project-jobs {:project-id project_id :commit-id commit_id})}
         [:code (subs commit_id 0 8)]]]
   [:td job_name [:span.text-muted.small " " job_key]]
   [:td.effect [effect-cell r]]])

(defn page []
  [:div.page.scheduled
   [state/hidden-routing-state-component :did-change #(fetch-data)]
   [:div.d-flex.align-items-center.mb-3
    [:h2.mb-0 [icons/clock] " Scheduled"]
    [limit-select]]
   (if-not (seq @data*)
     [:div "Loading..."]
     (let [{:keys [rows errors now]} @data*]
       [:<>
        [:p.text-muted.small
         "The next cron firings over all projects with cron triggers enabled, soonest first "
         "(times in UTC; as of " (subs (or now "") 0 16) "Z). A firing only creates a job when "
         "none with that key exists on the branch head, or the entry says rerun, and the job's "
         "dependencies are fulfilled; the server does not catch up on firings it was down for."]
        (if (empty? rows)
          [:p.text-muted "Nothing scheduled."]
          [:table.table.table-sm.table-hover.scheduled-table
           [:thead [:tr [:th "When"] [:th "Cron"] [:th "Project"] [:th "Branch"] [:th "Head"] [:th "Job"] [:th "Effect"]]]
           [:tbody (for [r rows] [row r])]])
        (when (seq errors)
          [:div.alert.alert-warning.scheduled-errors
           [:strong "Heads whose configuration could not be read:"]
           [:ul.mb-0
            (for [{:keys [project_id branch_name commit_id error]} errors]
              ^{:key (str project_id branch_name)}
              [:li project_id " / " branch_name " @ " [:code (subs commit_id 0 8)] ": " error])]])
        (when @state/debug?*
          [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])]))])

(def components {:page page})
