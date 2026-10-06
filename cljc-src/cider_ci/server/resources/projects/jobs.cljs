(ns cider-ci.server.resources.projects.jobs
  (:require
   ["elkjs/lib/elk.bundled.js" :as ELK]
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


;;; Jobs DAG (ELK layered layout: no edge crossings where avoidable, edge
;;; labels with dependency / trigger details, multi-line node labels)

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

(def ^:private jdag-font-px 11)     ; node label
(def ^:private jdag-label-px 9)     ; edge label
(def ^:private jdag-max-line-chars 24)

(defn- jdag-wrap
  "Greedy word wrap into lines of at most max-chars (long words stay whole)."
  [s max-chars]
  (let [words (str/split (str s) #"\s+")]
    (->> words
         (reduce (fn [lines w]
                   (let [cur (peek lines)]
                     (if (and cur (<= (+ (count cur) 1 (count w)) max-chars))
                       (conj (pop lines) (str cur " " w))
                       (conj lines w))))
                 [])
         (remove str/blank?)
         vec)))

(defn- jdag-text-width [lines px]
  ;; ~0.58em per character for a sans-serif face; good enough for layout
  (* 0.58 px (apply max 1 (map count lines))))

(defn- jdag-node-size [lines]
  {:width  (max 110 (+ 24 (jdag-text-width lines jdag-font-px)))
   :height (+ 14 (* (count lines) (+ jdag-font-px 3)))})

(def ^:private jdag-label-max-chars 22)

(defn- jdag-edge-label-lines
  "Lines of one edge label for a job dependency / job trigger entry (wrapped
   narrowly: ELK puts labels between the layers, wide labels widen the graph)."
  [{:keys [kind name states]}]
  (->> (concat (jdag-wrap (str kind ": " name) jdag-label-max-chars)
               (when (seq states) (jdag-wrap (str "states: " (str/join ", " states)) jdag-label-max-chars)))
       vec))

(defn- jdag-trigger-lines
  "Node label of a branch / cron trigger source."
  [{:keys [type include_match exclude_match value]}]
  (case type
    "branch" (cond-> [(str "push " (or include_match "^.*$"))]
               (seq exclude_match) (conj (str "except " exclude_match)))
    "cron"   [(str "cron " value)]
    [type]))

(defn- jdag-source-key
  "Graph node id of a dependency / trigger source. Job entries in a
   submodule get their own node (database/merged-to-master, as in the
   legacy UI); identical branch / cron triggers share one source node."
  [_job-key {:keys [type job_key submodule include_match exclude_match value]}]
  (case type
    "job" (str/join "/" (conj (vec submodule) job_key))
    (str "trigger:" type ":" include_match ":" exclude_match ":" value)))

(defn- jdag-elk-graph
  "Builds the ELK input graph from the available jobs. Returns
   {:graph js-graph :nodes {id {...}} :edges [{...}]}."
  [jobs]
  (let [job-keys  (set (map :key jobs))
        entries   (for [j jobs
                        e (concat (:depends_on j) (:run_when j))
                        ;; a job-type run_when duplicating a depends_on entry
                        ;; is folded into the same edge label below
                        :when (not (and (= "trigger" (:kind e)) (= "job" (:type e))
                                        (some #(and (= (:job_key %) (:job_key e))
                                                    (= (:submodule %) (:submodule e)))
                                              (:depends_on j))))]
                    (assoc e :target (:key j) :source (jdag-source-key (:key j) e)))
        ;; one edge per (source,target), labels merged
        edges     (->> entries
                       (group-by (juxt :source :target))
                       (map (fn [[[src tgt] es]]
                              {:id     (str src "->" tgt)
                               :source src :target tgt
                               ;; branch/cron triggers carry their text on the
                               ;; source node, only job entries label the edge
                               :lines  (vec (mapcat jdag-edge-label-lines
                                                    (filter #(= "job" (:type %)) es)))})))
        trigger-by-src (into {} (for [e entries :when (not= "job" (:type e))] [(:source e) e]))
        job-nodes (for [j jobs]
                    (let [lines (jdag-wrap (:name j) jdag-max-line-chars)]
                      (merge {:id (:key j) :kind :job :job j :lines lines}
                             (jdag-node-size lines))))
        ext-nodes (for [src (distinct (map :source edges))
                        :when (not (job-keys src))]
                    (let [lines (if-let [t (get trigger-by-src src)]
                                  (mapcat #(jdag-wrap % jdag-max-line-chars) (jdag-trigger-lines t))
                                  (jdag-wrap src jdag-max-line-chars))
                          lines (vec lines)]
                      (merge {:id src :kind (if (get trigger-by-src src) :trigger :external) :lines lines}
                             (jdag-node-size lines))))
        nodes     (into {} (map (fn [n] [(:id n) n]) (concat job-nodes ext-nodes)))
        graph     (clj->js
                    {:id "root"
                     :layoutOptions {"elk.algorithm" "layered"
                                     "elk.direction" "RIGHT"
                                     "elk.spacing.nodeNode" "18"
                                     "elk.layered.spacing.nodeNodeBetweenLayers" "40"
                                     "elk.layered.spacing.edgeNodeBetweenLayers" "24"
                                     "elk.spacing.edgeLabel" "4"
                                     "elk.edgeLabels.inline" "false"
                                     "elk.layered.crossingMinimization.thoroughness" "30"
                                     "elk.padding" "[top=8,left=8,bottom=8,right=8]"}
                     :children (for [n (vals nodes)]
                                 {:id (:id n) :width (:width n) :height (:height n)})
                     :edges    (for [e edges]
                                 {:id (:id e) :sources [(:source e)] :targets [(:target e)]
                                  :labels (when (seq (:lines e))
                                            [{:text   (str/join "\n" (:lines e))
                                              :width  (jdag-text-width (:lines e) jdag-label-px)
                                              :height (* (count (:lines e)) (+ jdag-label-px 2))}])})})]
    {:graph graph :nodes nodes :edges edges}))

(defonce ^:private jdag-layout* (reagent/atom nil))   ; {:key k :result js}
(defonce ^:private jdag-elk (new ELK))

(defn- jdag-ensure-layout! [jobs]
  (let [k (hash (map (juxt :key :depends_on :run_when :name) jobs))]
    (when (not= k (:key @jdag-layout*))
      (let [{:keys [graph nodes edges]} (jdag-elk-graph jobs)]
        (reset! jdag-layout* {:key k :nodes nodes :edges edges :result nil})
        (-> (.layout jdag-elk graph)
            (.then (fn [res] (swap! jdag-layout* #(if (= k (:key %)) (assoc % :result res) %))))
            (.catch (fn [e] (js/console.error "ELK layout failed" e))))))))

(defn- jdag-section->d [^js section]
  (let [pts (concat [(.-startPoint section)]
                    (array-seq (or (.-bendPoints section) #js []))
                    [(.-endPoint section)])]
    (str "M " (str/join " L " (map (fn [^js p] (str (.-x p) "," (.-y p))) pts)))))

(defn- jdag-multiline-text [x y lines px fill anchor]
  (let [lh (+ px 3)
        y0 (- y (/ (* lh (dec (count lines))) 2))]
    [:text {:x x :y y0 :dy "0.35em" :text-anchor anchor :font-size px
            :font-family "sans-serif" :fill fill}
     (for [[i l] (map-indexed vector lines)]
       ^{:key i} [:tspan {:x x :dy (if (zero? i) "0.35em" lh)} l])]))

(defn- jdag-bounds
  "Content bounds [min-x min-y w h] of an ELK result: everything that is drawn
   (nodes, edge points, labels) plus a small margin."
  [^js result]
  (let [xs-ys (concat
                (mapcat (fn [^js c] [[(.-x c) (.-y c)] [(+ (.-x c) (.-width c)) (+ (.-y c) (.-height c))]])
                        (array-seq (.-children result)))
                (mapcat (fn [^js e]
                          (concat
                            (mapcat (fn [^js s] (map (fn [^js p] [(.-x p) (.-y p)])
                                                     (concat [(.-startPoint s)] (array-seq (or (.-bendPoints s) #js [])) [(.-endPoint s)])))
                                    (array-seq (.-sections e)))
                            (map (fn [^js l] [(+ (.-x l) (.-width l)) (+ (.-y l) (.-height l))]) (array-seq (or (.-labels e) #js [])))))
                        (array-seq (.-edges result))))
        max-x (+ 12 (apply max 0 (map first xs-ys)))
        max-y (+ 12 (apply max 0 (map second xs-ys)))
        min-x (min 0 (apply min 0 (map first xs-ys)))
        min-y (min 0 (apply min 0 (map second xs-ys)))]
    [min-x min-y (- max-x min-x) (- max-y min-y)]))

(defn- jdag-layout-ready? [{:keys [edges ^js result]}]
  (and result (seq edges) (some (fn [e] (seq (:lines e))) edges)))

(defn- jdag-svg
  "The graph SVG. :view-box overrides the content bounds (overlay zoom/pan);
   :fit? scales it to the container width (inline), otherwise it is rendered at
   natural size; :attrs are merged into the svg element (event handlers, ref)."
  [{:keys [created view-box fit? attrs]}]
  (let [{:keys [nodes edges ^js result] :as layout} @jdag-layout*]
    (when (jdag-layout-ready? layout)
      (let [created-by-key   (into {} (map (fn [j] [(:key j) j]) created))
            by-id            (into {} (map (fn [^js c] [(.-id c) c]) (array-seq (.-children result))))
            [bx by bw bh]    (jdag-bounds result)
            [vx vy vw vh]    (or view-box [bx by bw bh])]
        [:svg.jobs-dag
         (merge {:viewBox (str vx " " vy " " vw " " vh)
                 :width bw :height bh
                 :preserveAspectRatio "xMidYMid meet"
                 :style (if fit?
                          {:display "block" :max-width "100%" :height "auto"}
                          {:display "block"})}
                attrs)
         [:defs
          [:marker {:id "jdag-arrow" :markerWidth 8 :markerHeight 6
                    :refX 7 :refY 3 :orient "auto"}
           [:polygon {:points "0,0 8,3 0,6" :fill "#888"}]]]
         (doall
           (for [^js e (array-seq (.-edges result))]
             ^{:key (.-id e)}
             [:g
              (for [[i ^js s] (map-indexed vector (array-seq (.-sections e)))]
                ^{:key i}
                [:path {:d (jdag-section->d s) :fill "none" :stroke "#888"
                        :stroke-width 1.5 :marker-end "url(#jdag-arrow)"}])
              (for [[i ^js l] (map-indexed vector (array-seq (or (.-labels e) #js [])))]
                (let [lines (str/split (.-text l) #"\n")]
                  ^{:key (str "l" i)}
                  [:g
                   [:rect {:x (- (.-x l) 2) :y (- (.-y l) 1) :width (+ 4 (.-width l)) :height (+ 2 (.-height l))
                           :fill "#fff" :fill-opacity 0.85 :rx 2}]
                   (jdag-multiline-text (+ (.-x l) (/ (.-width l) 2)) (+ (.-y l) (/ (.-height l) 2))
                                        lines jdag-label-px "#6c757d" "middle")]))]))
         (doall
           (for [[id n] nodes]
             (let [^js c (get by-id id)]
               (when c
                 ;; job node, external (submodule) job node, or trigger source
                 (let [state (:state (get created-by-key id))
                       job?  (= :job (:kind n))
                       fill  (if job? (get jdag-state-fill state "#dee2e6") "#fff")
                       tcol  (if job? (get jdag-state-text state "#6c757d") "#6c757d")
                       px    (if (= :trigger (:kind n)) jdag-label-px jdag-font-px)]
                   ^{:key id}
                   [:g {:transform (str "translate(" (.-x c) "," (.-y c) ")")}
                    [:rect {:width (.-width c) :height (.-height c) :rx (if job? 4 10)
                            :fill fill :stroke "#ced4da" :stroke-width 1
                            :stroke-dasharray (when-not job? "4 2")}]
                    (jdag-multiline-text (/ (.-width c) 2) (/ (.-height c) 2)
                                         (:lines n) px tcol "middle")])))))]))))


;;; Overlay: fullscreen modal with wheel zoom, drag pan, +/-/fit buttons

(defonce ^:private jdag-overlay* (reagent/atom nil))   ; {:view-box [x y w h] :bounds [...] :drag {...}}

(defn- jdag-open-overlay! []
  (when-let [^js result (:result @jdag-layout*)]
    (let [b (jdag-bounds result)]
      (reset! jdag-overlay* {:view-box b :bounds b}))))

(defn- jdag-close-overlay! [] (reset! jdag-overlay* nil))

(defn- jdag-zoom-view-box
  "Scales [x y w h] by f around the point (px py) in viewBox coordinates."
  [[x y w h] f px py]
  [(- px (* (- px x) f)) (- py (* (- py y) f)) (* w f) (* h f)])

(defn- jdag-client->view
  "Maps a client (pixel) position on the svg to viewBox coordinates, honouring
   preserveAspectRatio xMidYMid meet."
  [^js svg [x y w h] client-x client-y]
  (let [r     (.getBoundingClientRect svg)
        scale (min (/ (.-width r) w) (/ (.-height r) h))
        ox    (/ (- (.-width r) (* w scale)) 2)
        oy    (/ (- (.-height r) (* h scale)) 2)]
    [(+ x (/ (- client-x (.-left r) ox) scale))
     (+ y (/ (- client-y (.-top r) oy) scale))
     scale]))

(defn- jdag-zoom-by! [f]
  (swap! jdag-overlay*
         (fn [{:keys [view-box] :as o}]
           (let [[x y w h] view-box]
             (assoc o :view-box (jdag-zoom-view-box view-box f (+ x (/ w 2)) (+ y (/ h 2))))))))

(defn- jdag-wheel! [^js e]
  (.preventDefault e)
  (let [^js svg (.-currentTarget e)
        {:keys [view-box]} @jdag-overlay*
        [px py] (jdag-client->view svg view-box (.-clientX e) (.-clientY e))
        f       (if (pos? (.-deltaY e)) 1.15 (/ 1 1.15))]
    (swap! jdag-overlay* assoc :view-box (jdag-zoom-view-box view-box f px py))))

(defn- jdag-drag-start! [^js e]
  (when (zero? (.-button e))
    (.preventDefault e)
    (swap! jdag-overlay* assoc :drag {:x (.-clientX e) :y (.-clientY e) :view-box (:view-box @jdag-overlay*)})))

(defn- jdag-drag-move! [^js e]
  (when-let [{:keys [x y view-box]} (:drag @jdag-overlay*)]
    (let [^js svg (.-currentTarget e)
          [_ _ scale] (jdag-client->view svg view-box 0 0)
          [vx vy vw vh] view-box]
      (swap! jdag-overlay* assoc :view-box
             [(- vx (/ (- (.-clientX e) x) scale)) (- vy (/ (- (.-clientY e) y) scale)) vw vh]))))

(defn- jdag-drag-end! [_] (swap! jdag-overlay* dissoc :drag))

(defn- jdag-overlay-svg
  "The overlay's svg: a class component so the wheel listener can be registered
   non-passive (React registers onWheel passive, preventDefault would be ignored
   and the page behind the modal would scroll)."
  [created]
  (let [svg-el (atom nil)]
    (reagent/create-class
      {:display-name "jobs-dag-overlay-svg"
       :component-did-mount
       (fn [_] (when-let [^js el @svg-el] (.addEventListener el "wheel" jdag-wheel! #js {:passive false})))
       :component-will-unmount
       (fn [_] (when-let [^js el @svg-el] (.removeEventListener el "wheel" jdag-wheel!)))
       :reagent-render
       (fn [created]
         (let [{:keys [view-box drag]} @jdag-overlay*]
           [jdag-svg {:created created :view-box view-box :fit? false
                      :attrs {:ref            (fn [el] (reset! svg-el el))
                              :width          "100%" :height "100%"
                              :style          {:display "block" :width "100%" :height "100%"
                                               :touch-action "none"
                                               :cursor (if drag "grabbing" "grab")}
                              :on-mouse-down  jdag-drag-start!
                              :on-mouse-move  jdag-drag-move!
                              :on-mouse-up    jdag-drag-end!
                              :on-mouse-leave jdag-drag-end!}}]))})))

(defn- jdag-overlay [created]
  (let [on-key (fn [^js e] (when (= "Escape" (.-key e)) (jdag-close-overlay!)))]
    (reagent/create-class
      {:display-name "jobs-dag-overlay"
       :component-did-mount    (fn [_] (.addEventListener js/document "keydown" on-key))
       :component-will-unmount (fn [_] (.removeEventListener js/document "keydown" on-key))
       :reagent-render
       (fn [created]
         (when-let [{:keys [bounds]} @jdag-overlay*]
           [:<>
            [:div.modal.jobs-dag-overlay {:style {:display "block" :z-index 1055} :tab-index -1
                                          :role "dialog" :aria-modal true}
             [:div.modal-dialog.modal-fullscreen
              [:div.modal-content
               [:div.modal-header.py-2
                [:h5.modal-title "Job Dependencies " [:code (project-id)] " / " [:code (subs (commit-id) 0 8)]]
                [:div.btn-group.btn-group-sm.ms-3 {:role "group"}
                 [:button.btn.btn-outline-secondary {:type "button" :title "Zoom in"
                                                     :on-click #(jdag-zoom-by! (/ 1 1.25))} "+"]
                 [:button.btn.btn-outline-secondary {:type "button" :title "Zoom out"
                                                     :on-click #(jdag-zoom-by! 1.25)} "−"]
                 [:button.btn.btn-outline-secondary {:type "button" :title "Fit"
                                                     :on-click #(swap! jdag-overlay* assoc :view-box bounds)} "fit"]]
                [:a.btn.btn-sm.btn-outline-secondary.ms-2
                 {:href   (path :project-jobs-graph {:project-id (project-id) :commit-id (commit-id)})
                  :target "_blank" :rel "noopener noreferrer"}
                 "open in new tab"]
                [:span.ms-3.text-muted.small "wheel: zoom · drag: pan · Esc: close"]
                [:button.btn-close.ms-auto {:type "button" :aria-label "Close"
                                            :on-click jdag-close-overlay!}]]
               [:div.modal-body.p-0 {:style {:overflow "hidden"}}
                [jdag-overlay-svg created]]]]]
            [:div.modal-backdrop.show {:on-click jdag-close-overlay!}]]))})))

(defn- jobs-dag [available created]
  (jdag-ensure-layout! available)
  (when (jdag-layout-ready? @jdag-layout*)
    [:<>
     [:div.d-flex.align-items-baseline.mt-3
      [:h5.mb-0 "Job Dependencies"]
      [:button.btn.btn-link.btn-sm {:type "button" :on-click jdag-open-overlay!} "zoom"]
      [:a.btn.btn-link.btn-sm {:href   (path :project-jobs-graph {:project-id (project-id) :commit-id (commit-id)})
                               :target "_blank" :rel "noopener noreferrer"}
       "open in new tab"]]
     [jdag-svg {:created created :fit? true
                :attrs {:on-click jdag-open-overlay!
                        :style {:display "block" :max-width "100%" :height "auto" :cursor "zoom-in"}}}]
     [jdag-overlay created]]))


;;; Standalone graph page (route :project-jobs-graph)

(defonce ^:private graph-page-data* (reagent/atom nil))
(defonce ^:private graph-page-fit?* (reagent/atom false))

(defn- fetch-graph-page-data! []
  (-> (js/fetch (path :project-jobs {:project-id (project-id) :commit-id (commit-id)})
                (clj->js {:credentials "same-origin"
                          :headers     {"accept" "application/json"}}))
      (.then (fn [^js r] (.json r)))
      (.then (fn [d] (reset! graph-page-data* (js->clj d :keywordize-keys true))))))

(defn- jobs-graph-page []
  [:div.page.jobs-graph
   [state/hidden-routing-state-component :did-change
    #(do (reset! graph-page-data* nil) (fetch-graph-page-data!))]
   [:nav.mb-3
    [:a {:href (path :project {:project-id (project-id)})} [icons/projects] " " (project-id)]
    " / "
    [:a {:href (path :project-commit {:project-id (project-id) :commit-id (commit-id)})}
     [:code (subs (commit-id) 0 8)]]
    " / "
    [:a {:href (path :project-jobs {:project-id (project-id) :commit-id (commit-id)})} "Jobs"]
    " / Dependencies"]
   (if-let [{:keys [available created]} @graph-page-data*]
     (do (jdag-ensure-layout! available)
         [:<>
          [:div.mb-2
           [:button.btn.btn-sm.btn-outline-secondary
            {:type "button" :on-click #(swap! graph-page-fit?* not)}
            (if @graph-page-fit?* "natural size" "fit to width")]]
          (if (jdag-layout-ready? @jdag-layout*)
            [jdag-svg {:created created :fit? @graph-page-fit?*}]
            [:p.text-muted "This commit has no job dependencies."])])
     [:div "Loading..."])])


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
   [:td.text-end (:priority j)]
   [:td [:span.text-muted (str (:created_at j))]]])


(defn- created-jobs-panel [jobs]
  (when (seq jobs)
    [:<>
     [:h4.mt-4 "Recorded Jobs"]
     [:table.table.table-sm
      [:thead
       [:tr
        [:th "Key"] [:th "Name"] [:th "State"] [:th.text-end "Priority"] [:th "Created"]]]
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
      [jobs-dag (:available @data*) (:created @data*)]
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


(defn- trial-fail-rate [tasks]
  (let [all-trials (mapcat :trials tasks)
        terminal   (filter #(terminal-states (:state %)) all-trials)
        n-total    (count terminal)
        n-failed   (count (remove #(= "passed" (:state %)) terminal))]
    (when (pos? n-total)
      (let [pct (Math/round (* 100.0 (/ n-failed n-total)))]
        [:p.small.mb-2
         {:class (if (zero? n-failed) "text-muted" "text-danger")}
         (str n-failed "/" n-total " trials failed (" pct "%)")]))))


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


(defn- set-job-priority! [priority]
  (-> (js/fetch (path :project-job-priority {:project-id (project-id)
                                             :commit-id  (commit-id)
                                             :job-id     (job-id)})
                (clj->js {:method      "POST"
                           :credentials "same-origin"
                           :headers     {"content-type" "application/json"
                                         "accept"       "application/json"
                                         "x-csrf-token" (anti-csrf/token)}
                           :body        (.stringify js/JSON (clj->js {:priority priority}))}))
      (.then (fn [_] (fetch-data)))))

(defn- job-priority-form
  "Run-time override of the job priority (configured via `priority:` in the
  job's cider-ci configuration, default 0). Higher is dispatched first;
  only trials still pending are affected."
  [job]
  (reagent/with-let [value* (reagent/atom nil)]
    (let [current (or (:priority job) 0)
          value   (or @value* (str current))]
      [:form.d-inline-flex.align-items-center.ms-3
       {:on-submit (fn [e]
                     (.preventDefault e)
                     (let [n (js/parseInt value 10)]
                       (when-not (js/isNaN n)
                         (set-job-priority! n)
                         (reset! value* nil))))}
       [:label.form-label.mb-0.me-2 {:for "job-priority"} "Priority"]
       [:input.form-control.form-control-sm.me-2
        {:id        "job-priority"
         :name      "priority"
         :type      "number"
         :step      1
         :style     {:width "6em"}
         :value     value
         :on-change #(reset! value* (-> % .-target .-value))}]
       [:button.btn.btn-sm.btn-outline-secondary
        {:type     "submit"
         :disabled (= (str current) (str/trim value))}
        "Set priority"]])))


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
        [trial-fail-rate (:tasks job)]
        [:div.mb-3
         (when abortable?
           [:button.btn.btn-sm.btn-outline-warning.me-2
            {:on-click abort-job!}
            [icons/stop] " Abort"])
         [:button.btn.btn-sm.btn-outline-secondary
          {:on-click retry-job!}
          [icons/retry] " Retry"]
         [job-priority-form job]]
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
    :project-jobs-graph [jobs-graph-page]
    [:div "Unknown route"]))


(def components {:page page})
