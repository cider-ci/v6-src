(ns cider-ci.server.resources.trials
  (:require
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.http.client.main :as http-client]
    [cider-ci.server.resources.projects.scripts-dag :as scripts-dag]
    [cider-ci.server.routes :refer [path]]
    [cider-ci.server.state :as state]
    [cljs.core.async :refer [go-loop <! chan]]
    [cljs.core.async :as async]
    [cljs.pprint :refer [pprint]]
    [reagent.core :as reagent]))


(defonce _data* (reagent/atom {}))

(def data* (reagent/reaction (get @_data* (:route @state/routing*))))

(def ^:private terminal-states #{"passed" "failed" "aborted" "defective"})

(defonce ^:private _fetch-id* (atom nil))


(defn- start-polling! []
  (let [route (:route @state/routing*)
        my-id (random-uuid)]
    (reset! _fetch-id* my-id)
    (go-loop []
      (let [ch              (chan)
            already-loaded? (some? (get @_data* route))
            _               (http-client/request {:url                     route
                                                  :chan                     ch
                                                  :modal-on-response-error (not already-loaded?)})
            resp            (<! ch)]
        (when (= (:route @state/routing*) route)
          (when (< (:status resp) 300)
            (swap! _data* assoc route (:body resp)))
          (when (= @_fetch-id* my-id)
            (let [s (-> @data* :trial_state)]
              (when-not (terminal-states s)
                (<! (async/timeout (if (= "executing" s) 3000 5000)))
                (when (= @_fetch-id* my-id)
                  (recur))))))))))


(defn- state-badge [s]
  (let [cls (case s
              "passed"    "bg-success"
              "failed"    "bg-danger"
              "executing" "bg-primary"
              "pending"   "bg-secondary"
              "aborted"   "bg-warning"
              "skipped"   "bg-light text-dark"
              "defective" "bg-dark"
              "bg-secondary")]
    [:span.badge {:class cls} s]))


(defn- trial-duration [trial]
  (when (and (:started_at trial) (:finished_at trial))
    (let [ms (- (js/Date. (:finished_at trial))
                (js/Date. (:started_at trial)))
          s  (.round js/Math (/ ms 1000))]
      (if (>= s 60)
        (str (.floor js/Math (/ s 60)) "m " (mod s 60) "s")
        (str s "s")))))


(defn- script-row [[key result] trial-id]
  (let [key-str (name key)]
    ^{:key key-str}
    [:tr
     [:td [:code key-str]]
     [:td [state-badge (:state result)]]
     [:td (when-let [exit (:exit_status result)]
            [:span.text-muted (str exit)])]
     [:td [:a {:href   (str "/trials/" trial-id "/attachments/scripts/" key-str)
               :target "_blank"}
           "Log"]]
     [:td (when-let [err (:error result)]
            [:span.text-danger.small err])]]))


(defn page []
  [:div.page.trial
   [state/hidden-routing-state-component :did-change start-polling!]
   (if-not (seq @data*)
     [:div "Loading..."]
     (let [trial     @data*
           trial-id  (:trial_id trial)
           task-spec (:task_spec trial)
           scripts   (-> trial :result :scripts)]
       [:<>
        [:nav.mb-3
         [:a {:href (path :project {:project-id (:project_id trial)})}
          [icons/projects] " " (:project_id trial)]
         " / "
         [:a {:href (path :project-commit {:project-id (:project_id trial)
                                           :commit-id  (:commit_id trial)})}
          [:code (subs (:commit_id trial) 0 8)]]
         " / "
         [:a {:href (path :project-jobs {:project-id (:project_id trial)
                                         :commit-id  (:commit_id trial)})}
          "Jobs"]
         " / "
         [:a {:href (path :project-job {:project-id (:project_id trial)
                                        :commit-id  (:commit_id trial)
                                        :job-id     (:job_id trial)})}
          [:code (:job_key trial)]]
         " / "
         (:task_name trial)]
        [:h3
         (:task_name trial) " "
         [state-badge (:trial_state trial)]
         (when-let [dur (trial-duration trial)]
           [:span.text-muted.ms-2.small dur])]
        (when-let [err (:error trial)]
          [:div.alert.alert-danger.mt-3 err])
        (when (seq scripts)
          [:<>
           [:h5.mt-4 "Scripts"]
           [:table.table.table-sm
            [:thead
             [:tr [:th "Script"] [:th "State"] [:th "Exit"] [:th "Log"] [:th "Error"]]]
            [:tbody
             (for [entry (seq scripts)]
               [script-row entry trial-id])]]])
        (when task-spec
          (try
            (when-let [dag (scripts-dag/scripts-dag task-spec scripts)]
              [:div.mt-3 dag])
            (catch :default e
              [:div.text-danger.small (str "DAG error: " (.-message e))])))
        (when @state/debug?*
          [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])]))])


(def components {:page page})
