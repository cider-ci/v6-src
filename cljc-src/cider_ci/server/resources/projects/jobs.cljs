(ns cider-ci.server.resources.projects.jobs
  (:require
   ["@dagrejs/dagre" :as dagre]
   ["js-yaml" :as js-yaml]
   [cider-ci.server.html.icons :as icons]
   [cider-ci.server.http.anti-csrf.main :as anti-csrf]
   [cider-ci.server.http.client.main :as http-client]
   [cider-ci.server.resources.projects.scripts-dag :as scripts-dag]
   [cider-ci.server.routes :refer [path]]
   [cider-ci.server.state :as state]
   [cljs.core.async :refer [go-loop <! chan]]
   [cljs.core.async :as async]
   [cljs.pprint :refer [pprint]]
   [clojure.string :as str]
   [reagent.core :as reagent]))


(defonce _data* (reagent/atom {}))

(def data* (reagent/reaction (get @_data* (:route @state/routing*))))

(def terminal-states #{"passed" "failed" "aborted" "defective"})

(defonce _job-fetch-id* (atom nil))


(defn- fetch-data [& _]
  (http-client/route-cached-fetch _data* :reload true :reload-delay 10000))


(defn- start-job-polling! [& _]
  (let [route (:route @state/routing*)
        my-id (random-uuid)]
    (reset! _job-fetch-id* my-id)
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
            (when (= @_job-fetch-id* my-id)
              (let [s     (-> @data* :state)
                    delay (if (= "executing" s) 3000 10000)]
                (when-not (terminal-states s)
                  (<! (async/timeout delay))
                  (when (= @_job-fetch-id* my-id)
                    (recur))))))))))


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
              "skipped"   "bg-light text-dark"
              "defective" "bg-dark"
              "bg-secondary")]
    [:span.badge {:class cls} s]))


;;; Jobs DAG

(def ^:private jdag-node-w 160)
(def ^:private jdag-node-h 36)

(def ^:private jdag-state-fill
  {"passed"    "#198754"
   "failed"    "#dc3545"
   "executing" "#0d6efd"
   "pending"   "#dee2e6"
   "aborted"   "#dee2e6"
   "defective" "#212529"})

(def ^:private jdag-state-text
  {"passed"    "#fff"
   "failed"    "#fff"
   "executing" "#fff"
   "pending"   "#6c757d"
   "aborted"   "#6c757d"
   "defective" "#fff"})

(defn- jdag-points->d [points]
  (let [pts (map (fn [^js p] [(.-x p) (.-y p)]) points)]
    (str "M " (str/join " L " (map #(str (first %) "," (second %)) pts)))))

(defn- jdag-build-graph [jobs]
  (let [^js dmod     dagre
        ^js graphlib (.-graphlib dmod)
        ^js g        (new (.-Graph graphlib))]
    (.setGraph g #js {:rankdir "LR" :nodesep 20 :ranksep 60 :marginx 20 :marginy 16})
    (.setDefaultEdgeLabel g (fn [] #js {}))
    (doseq [j jobs]
      (.setNode g (:key j) #js {:width jdag-node-w :height jdag-node-h}))
    (doseq [j jobs
            dep-key (:dep_job_keys j)
            :when (seq dep-key)]
      (.setEdge g dep-key (:key j) #js {}))
    ((.-layout dmod) g)
    g))

(defn- jobs-dag [jobs]
  (when (some #(seq (:dep_job_keys %)) jobs)
    (let [^js g  (jdag-build-graph jobs)
          ^js gi (.graph g)
          svg-w  (+ (.-width gi) 4)
          svg-h  (+ (.-height gi) 4)]
      [:<>
       [:h5.mt-3 "Job Dependencies"]
       [:svg {:viewBox (str "0 0 " svg-w " " svg-h)
              :width svg-w :height svg-h
              :style {:display "block" :max-width "100%"}}
        [:defs
         [:marker {:id "jdag-arrow" :markerWidth 8 :markerHeight 6
                   :refX 7 :refY 3 :orient "auto"}
          [:polygon {:points "0,0 8,3 0,6" :fill "#888"}]]]
        (doall
          (for [^js e (.edges g)]
            (let [pts (.-points (.edge g e))]
              ^{:key (str (.-v e) "→" (.-w e))}
              [:path {:d            (jdag-points->d pts)
                      :fill         "none"
                      :stroke       "#888"
                      :stroke-width 1.5
                      :marker-end   "url(#jdag-arrow)"}])))
        (doall
          (for [j jobs]
            (let [key-str (:key j)
                  ^js nd  (.node g key-str)
                  nx      (- (.-x nd) (/ jdag-node-w 2))
                  ny      (- (.-y nd) (/ jdag-node-h 2))
                  state   (when (:has_instance j) (:state j))
                  fill    (get jdag-state-fill state "#dee2e6")
                  tcol    (get jdag-state-text state "#6c757d")]
              ^{:key key-str}
              [:g {:transform (str "translate(" nx "," ny ")")}
               [:rect {:width jdag-node-w :height jdag-node-h :rx 4
                       :fill fill :stroke "#ced4da" :stroke-width 1}]
               [:text {:x           (/ jdag-node-w 2)
                       :y           (/ jdag-node-h 2)
                       :dy          "0.35em"
                       :text-anchor "middle"
                       :font-size   11
                       :font-family "sans-serif"
                       :fill        tcol}
                (:name j)]])))]])))


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


(defn- available-jobs-panel [jobs created]
  (let [created-by-key (into {} (map (fn [j] [(:key j) j]) created))]
    [:<>
     [:h4.mt-3 "Available Jobs"]
     (if (seq jobs)
       [:table.table.table-sm
        [:thead
         [:tr [:th "Key"] [:th "Name"] [:th ""]]]
        [:tbody
         (for [j jobs]
           (let [existing (get created-by-key (:key j))]
             ^{:key (:key j)}
             [:tr
              [:td [:code (:key j)]]
              [:td (:name j)]
              [:td
               (cond
                 (:has_instance j)
                 [:a.btn.btn-sm.btn-outline-secondary
                  {:href (path :project-job {:project-id (project-id)
                                             :commit-id  (commit-id)
                                             :job-id     (:id existing)})}
                  [state-badge (:state existing)] " View"]

                 (seq (:unmet_deps j))
                 [:span.text-muted.small
                  "Waiting for: " (str/join ", " (:unmet_deps j))]

                 :else
                 [:button.btn.btn-sm.btn-outline-primary
                  {:on-click #(trigger-job (:key j))}
                  [icons/play] " Run"])]]))]]
       [:p.text-muted "No jobs defined in cider-ci.yml for this commit."])]))



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
      [jobs-dag (:available @data*)]
      [available-jobs-panel (:available @data*) (:created @data*)]
      [created-jobs-panel (:created @data*)]
      (when @state/debug?*
        [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])])])


;;; Job detail page (route :project-job)

(defn- trial-btn-cls [s]
  (case s
    "passed"    "btn-outline-success"
    "failed"    "btn-outline-danger"
    "executing" "btn-outline-primary"
    "pending"   "btn-outline-secondary"
    "aborted"   "btn-outline-warning"
    "defective" "btn-outline-dark"
    "skipped"   "btn-outline-secondary"
    "btn-outline-secondary"))


(defn- retry-task! [task-id]
  (-> (js/fetch (path :project-job-task-retry {:project-id (project-id)
                                               :commit-id  (commit-id)
                                               :job-id     (job-id)
                                               :task-id    task-id})
                (clj->js {:method      "POST"
                           :credentials "same-origin"
                           :headers     {"content-type" "application/json"
                                         "accept"       "application/json"
                                         "x-csrf-token" (anti-csrf/token)}}))
      (.then (fn [_] (fetch-data)))))


(defn- trial-config-badges [spec]
  (let [eager (or (:eager_trials spec) 1)
        max-t (or (:max_trials spec) 2)]
    [:<>
     (when (> eager 1)
       [:span.badge.bg-secondary.ms-1 (str eager "× eager")])
     (when (> max-t 2)
       [:span.badge.bg-light.text-dark.border.ms-1 (str "max " max-t)])]))


(defn- task-row [t]
  ^{:key (:id t)}
  [:tr
   [:td
    [:a {:href (path :project-job-task {:project-id (project-id)
                                        :commit-id  (commit-id)
                                        :job-id     (job-id)
                                        :task-id    (:id t)})}
     [:code (:name t)]]
    [trial-config-badges (:spec t)]]
   [:td [state-badge (:state t)]]
   [:td
    (for [trial (:trials t)]
      ^{:key (:id trial)}
      [:a.btn.btn-sm.ms-1
       {:class (trial-btn-cls (:state trial))
        :href  (path :trial {:trial-id (:id trial)})}
       (:state trial)])
    [:button.btn.btn-sm.btn-outline-secondary.ms-2
     {:on-click #(retry-task! (:id t))}
     [icons/retry] " Retry"]]])


(defn- tasks-panel [tasks]
  [:<>
   [:h4.mt-3 "Tasks"]
   (if (seq tasks)
     [:table.table.table-sm
      [:thead
       [:tr [:th "Name"] [:th "State"] [:th "Trials"]]]
      [:tbody
       (for [t tasks] [task-row t])]]
     [:p.text-muted "No tasks for this job."])])


(defn- post-job-action! [route-kw]
  (-> (js/fetch (path route-kw {:project-id (project-id)
                                :commit-id  (commit-id)
                                :job-id     (job-id)})
                (clj->js {:method      "POST"
                           :credentials "same-origin"
                           :headers     {"content-type" "application/json"
                                         "accept"       "application/json"
                                         "x-csrf-token" (anti-csrf/token)}}))
      (.then (fn [_] (fetch-data)))))

(defn- retry-job! [] (post-job-action! :project-job-retry))
(defn- abort-job! [] (post-job-action! :project-job-abort))


(defn- job-detail-page []
  [:div.page.job
   [state/hidden-routing-state-component :did-change start-job-polling!]
   (if-not (seq @data*)
     [:div "Loading..."]
     (let [job        @data*
           abortable? (#{"pending" "executing"} (:state job))]
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
        [:h3 (:name job) " " [state-badge (:state job)]]
        [:div.mb-3
         (when abortable?
           [:button.btn.btn-sm.btn-outline-warning.me-2
            {:on-click abort-job!}
            [icons/stop] " Abort"])
         [:button.btn.btn-sm.btn-outline-secondary
          {:on-click retry-job!}
          [icons/retry] " Retry"]]
        [tasks-panel (:tasks job)]
        (when @state/debug?*
          [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])]))])


;;; Task detail page (route :project-job-task)

(defn- task-id-param []
  (-> @state/routing* :path-params :task-id))

(defn- scripts-panel [scripts]
  (when (seq scripts)
    [:<>
     [:h5.mt-3 "Scripts"]
     (for [[k script] scripts]
       ^{:key (name k)}
       [:div.mb-3
        [:div.d-flex.align-items-center.mb-1
         [:code.fw-bold.me-2 (name k)]
         (when-let [to (:timeout script)]
           [:span.badge.bg-light.text-dark.border.me-1 to])
         (when-let [deps (seq (:start_when script))]
           [:span.text-muted.small
            "after: "
            (str/join ", " (map (fn [[_ d]] (or (:script_key d) (str d))) deps))])]
        [:pre.bg-light.p-2.rounded.small
         {:style {:max-height "200px" :overflow-y "auto" :white-space "pre"}}
         (or (:body script) "")]])]))

(defn- env-vars-panel [env-vars]
  (when (seq env-vars)
    [:<>
     [:h5.mt-3 "Environment Variables"]
     [:table.table.table-sm.table-bordered
      [:tbody
       (for [[k v] env-vars]
         ^{:key (name k)}
         [:tr
          [:td [:code (name k)]]
          [:td (if (string? v)
                 [:code v]
                 [:code.text-muted (str v)])]])]]]))

(defn- traits-panel [traits]
  (when (seq traits)
    (let [trait-names (->> (if (map? traits) (map name (keys traits)) (map name traits))
                           sort)
          filter-url  (str (path :executors {}) "?traits=" (str/join "," trait-names))]
      [:<>
       [:h5.mt-3 "Traits"]
       [:div.d-flex.align-items-center.flex-wrap.gap-1
        (for [t trait-names]
          ^{:key t}
          [:span.badge.bg-secondary t])
        [:a.btn.btn-sm.btn-outline-secondary.ms-1
         {:href filter-url}
         [icons/server] " Match executors"]]])))

(defn- ports-panel [ports]
  (when (seq ports)
    [:<>
     [:h5.mt-3 "Ports"]
     [:table.table.table-sm
      [:thead [:tr [:th "Name"] [:th "Range"]]]
      [:tbody
       (for [[k v] ports]
         ^{:key (name k)}
         [:tr
          [:td [:code (name k)]]
          [:td (str (:min v) "–" (:max v))]])]]]))

(defn- task-trials-panel [trials task-id]
  [:<>
   [:div.d-flex.align-items-center.gap-2.mt-3.mb-1
    [:h5.mb-0 "Trials"]
    [:button.btn.btn-sm.btn-outline-secondary
     {:on-click #(retry-task! task-id)}
     [icons/retry] " Retry"]]
   (if (seq trials)
     [:div
      (for [trial trials]
        ^{:key (:id trial)}
        [:a.btn.btn-sm.ms-1.mb-1
         {:class (trial-btn-cls (:state trial))
          :href  (path :trial {:trial-id (:id trial)})}
         (:state trial)])]
     [:p.text-muted "No trials yet."])])

(defn- scripts-dag-panel [spec]
  (when (and (seq (:scripts spec))
             (some #(seq (:start_when (second %))) (:scripts spec)))
    [:<>
     [:h5.mt-3 "Script Dependencies"]
     [scripts-dag/scripts-dag spec {}]]))

(defn- spec-yaml-panel [spec]
  [:<>
   [:h5.mt-3 "Task Configuration"]
   [:pre.bg-light.p-2.rounded.small
    {:style {:max-height "500px" :overflow-y "auto" :white-space "pre"}}
    (.dump js-yaml (clj->js spec))]])

(defn- task-detail-page []
  [:div.page.task
   [state/hidden-routing-state-component :did-change #(fetch-data)]
   (if-not (seq @data*)
     [:div "Loading..."]
     (let [task @data*
           spec (:spec task)]
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
         [:a {:href (path :project-job {:project-id (project-id) :commit-id (commit-id) :job-id (job-id)})}
          [:code (subs (job-id) 0 8)]]
         " / "
         [:code (:name task)]]
        [:h3 (:name task) " " [state-badge (:state task)]]
        [task-trials-panel (:trials task) (:id task)]
        [traits-panel (:traits spec)]
        [env-vars-panel (:environment_variables spec)]
        [ports-panel (:ports spec)]
        [scripts-dag-panel spec]
        [scripts-panel (:scripts spec)]
        [spec-yaml-panel spec]
        (when @state/debug?*
          [:div.debug [:hr] [:pre.bg-light [:code (with-out-str (pprint @data*))]]])]))])


(defn page []
  (case (:name @state/routing*)
    :project-job      [job-detail-page]
    :project-job-task [task-detail-page]
    :project-jobs     [jobs-list-page]
    [:div "Unknown route"]))


(def components {:page page})
