(ns cider-ci.server.resources.projects.scripts-dag
  (:require
   ["@dagrejs/dagre" :as dagre]
   [clojure.string :as str]))


(def ^:private node-w 130)
(def ^:private node-h 36)

(def ^:private state-fill
  {"passed"    "#198754"
   "failed"    "#dc3545"
   "executing" "#0d6efd"
   "skipped"   "#dee2e6"
   "defective" "#212529"
   "pending"   "#dee2e6"})

(def ^:private state-text
  {"passed"    "#fff"
   "failed"    "#fff"
   "executing" "#fff"
   "skipped"   "#212529"
   "defective" "#fff"
   "pending"   "#6c757d"})


(defn- build-graph [scripts]
  (let [g (new (.. dagre -graphlib -Graph))]
    (.setGraph g #js {:rankdir "LR" :nodesep 30 :ranksep 60 :marginx 20 :marginy 16})
    (.setDefaultEdgeLabel g (fn [] #js {}))
    (doseq [[k _] scripts]
      (.setNode g (name k) #js {:width node-w :height node-h}))
    (doseq [[k spec] scripts
            :when (seq (:start_when spec))
            {:keys [script_key states]} (:start_when spec)]
      (.setEdge g
                (str script_key)
                (name k)
                #js {:label (str/join ", " (or states ["passed"]))}))
    ((.-layout dagre) g)
    g))


(defn- points->d [points]
  (let [pts (map (fn [p] [(.-x p) (.-y p)]) points)]
    (str "M " (str/join " L " (map #(str (first %) "," (second %)) pts)))))


(defn scripts-dag
  "Renders scripts and their start_when dependencies as a DAG.
   task-spec: the task's :spec map (contains :scripts with :start_when)
   script-results: map of script-key → {:state ...} from trial :result"
  [task-spec script-results]
  (let [scripts  (seq (:scripts task-spec))
        has-deps (some #(seq (:start_when (second %))) scripts)]
    (when (and (>= (count scripts) 2) has-deps)
      (let [g          (build-graph scripts)
            gi         (.graph g)
            svg-w      (+ (.-width gi) 4)
            svg-h      (+ (.-height gi) 4)]
        [:svg {:viewBox (str "0 0 " svg-w " " svg-h)
               :width svg-w :height svg-h
               :style {:display "block" :max-width "100%"}}
         [:defs
          [:marker {:id "dag-arrow" :markerWidth 8 :markerHeight 6
                    :refX 7 :refY 3 :orient "auto"}
           [:polygon {:points "0,0 8,3 0,6" :fill "#888"}]]]
         ;; Edges
         (doall
          (for [e (.edges g)]
            (let [pts   (-> (.edge g e) .-points)
                  label (-> (.edge g e) .-label)]
              ^{:key (str (.-v e) "→" (.-w e))}
              [:g
               [:path {:d           (points->d pts)
                       :fill        "none"
                       :stroke      "#888"
                       :stroke-width 1.5
                       :marker-end  "url(#dag-arrow)"}]
               (when label
                 (let [mid (nth pts (quot (count pts) 2))]
                   [:text {:x          (.-x mid)
                           :y          (- (.-y mid) 5)
                           :text-anchor "middle"
                           :font-size  9
                           :fill       "#888"}
                    label]))])))
         ;; Nodes
         (doall
          (for [[k _] scripts]
            (let [key-str (name k)
                  node    (.node g key-str)
                  nx      (- (.-x node) (/ node-w 2))
                  ny      (- (.-y node) (/ node-h 2))
                  state   (get-in script-results [(keyword key-str) :state])
                  fill    (get state-fill state "#dee2e6")
                  tcol    (get state-text state "#212529")]
              ^{:key key-str}
              [:g {:transform (str "translate(" nx "," ny ")")}
               [:rect {:width node-w :height node-h :rx 4
                       :fill fill :stroke "#ced4da" :stroke-width 1}]
               [:text {:x           (/ node-w 2)
                       :y           (/ node-h 2)
                       :dy          "0.35em"
                       :text-anchor "middle"
                       :font-size   11
                       :font-family "monospace"
                       :fill        tcol}
                key-str]])))]))))
