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
(defonce ^:private _script-logs* (reagent/atom {}))


(defn- fetch-script-logs! [trial-id scripts]
  (doseq [[key result] (seq scripts)
          :let [key-str (name key)]
          :when (= "executing" (:state result))]
    (let [url (str "/trials/" trial-id "/attachments/scripts/" key-str)
          ch  (chan)]
      (http-client/request {:url                     url
                            :chan                     ch
                            :modal-on-response-error false})
      (go (let [resp (<! ch)]
            (when (= 200 (:status resp))
              (swap! _script-logs* assoc key-str (:body resp))))))))


(defn- start-polling! []
  (let [route (:route @state/routing*)
        my-id (random-uuid)]
    (reset! _fetch-id* my-id)
    (reset! _script-logs* {})
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
              (when (= "executing" s)
                (fetch-script-logs! (-> @data* :trial_id) (-> @data* :result :scripts)))
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


(defn- script-row [[key result] trial-id script-logs]
  (let [key-str     (name key)
        executing?  (= "executing" (:state result))
        log-content (get script-logs key-str)]
    ^{:key key-str}
    [:<>
     [:tr
      [:td [:code key-str]]
      [:td [state-badge (:state result)]]
      [:td (when-let [exit (:exit_status result)]
             [:span.text-muted (str exit)])]
      [:td [:a {:href   (str "/trials/" trial-id "/attachments/scripts/" key-str)
                :target "_blank"}
            "Log"]]
      [:td (when-let [err (:error result)]
             [:span.text-danger.small err])]]
     (when (and executing? log-content)
       [:tr {:key (str key-str "-log")}
        [:td {:col-span 5 :style {:padding 0}}
         [:pre.mb-0.p-2
          {:style {:background "#1e1e1e" :color "#d4d4d4"
                   :font-size "0.78rem" :max-height "300px"
                   :overflow-y "auto" :white-space "pre-wrap"
                   :border-radius "0"}}
          log-content]]])]))


(defn- attachment-item [trial-id {:keys [path content_type]}]
  (let [url (str "/trials/" trial-id "/attachments/" path)]
    [:div.mb-2
     (if (clojure.string/starts-with? (or content_type "") "image/")
       [:div
        [:a {:href url :target "_blank"}
         [:img {:src url :alt path :style {:max-width "100%" :max-height "300px"
                                           :border "1px solid #dee2e6" :border-radius "4px"}}]]
        [:div.small.text-muted.mt-1 [:code path]]]
       [:a {:href url :target "_blank"}
        [icons/file-code] " " [:code path]])]))


(defn- attachments-panel [trial-id attachments]
  (let [non-script (remove #(clojure.string/starts-with? (:path %) "scripts/") attachments)]
    (when (seq non-script)
      [:<>
       [:h5.mt-4 "Attachments"]
       (for [a non-script]
         ^{:key (:path a)}
         [attachment-item trial-id a])])))


(defn- tree-attachment-item [tree-id {:keys [path content_type]}]
  (let [url (str "/tree-attachments/" tree-id "/" path)]
    [:div.mb-2
     (if (clojure.string/starts-with? (or content_type "") "image/")
       [:div
        [:a {:href url :target "_blank"}
         [:img {:src url :alt path :style {:max-width "100%" :max-height "300px"
                                           :border "1px solid #dee2e6" :border-radius "4px"}}]]
        [:div.small.text-muted.mt-1 [:code path]]]
       [:a {:href url :target "_blank"}
        [icons/file-code] " " [:code path]])]))


(defn- tree-attachments-panel [tree-id attachments]
  (when (seq attachments)
    [:<>
     [:h5.mt-4 "Tree Attachments"]
     (for [a attachments]
       ^{:key (:path a)}
       [tree-attachment-item tree-id a])]))


(defn page []
  [:div.page.trial
   [state/hidden-routing-state-component :did-change start-polling!]
   (if-not (seq @data*)
     [:div "Loading..."]
     (let [trial            @data*
           trial-id         (:trial_id trial)
           task-spec        (:task_spec trial)
           scripts          (-> trial :result :scripts)
           script-logs      @_script-logs*
           attachments      (:attachments trial)
           tree-id          (:tree_id trial)
           tree-attachments (:tree_attachments trial)]
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
               [script-row entry trial-id script-logs])]]])
        [attachments-panel trial-id attachments]
        [tree-attachments-panel tree-id tree-attachments]
        (when task-spec
          (try
            (when-let [dag (scripts-dag/scripts-dag task-spec scripts)]
              [:div.mt-3 dag])
            (catch :default e
              [:div.text-danger.small (str "DAG error: " (.-message e))])))
        (when @state/debug?*
          [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])]))])


(def components {:page page})
