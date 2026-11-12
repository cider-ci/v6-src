(ns cider-ci.server.html.utils.times
  "Timestamps in the UI: relative ('3 minutes ago') with the absolute local
   time in the tooltip, and durations."
  (:require
    ["date-fns" :as date-fns]
    [clojure.string :as str]))


(defn- date [v]
  (when v
    (let [d (js/Date. v)]
      (when-not (js/isNaN (.getTime d)) d))))

(defn absolute
  "Local time, e.g. 2026-11-12 10:00:18."
  [v]
  (some-> (date v) (date-fns/format "yyyy-MM-dd HH:mm:ss")))

(defn relative
  "e.g. '3 minutes ago' or 'in 2 hours'."
  [v]
  (some-> (date v) (date-fns/formatDistance (js/Date.) (clj->js {:addSuffix true}))))

(defn duration
  "Seconds between from and to (to defaults to now) as '12s', '3m 4s' or
   '2h 5m'."
  ([from] (duration from (js/Date.)))
  ([from to]
   (when-let [f (date from)]
     (when-let [t (date to)]
       (let [s (max 0 (js/Math.round (/ (- (.getTime t) (.getTime f)) 1000)))
             h (quot s 3600)
             m (quot (mod s 3600) 60)]
         (cond
           (>= s 3600) (str h "h " m "m")
           (>= s 60)   (str m "m " (mod s 60) "s")
           :else       (str s "s")))))))

(defn stamp
  "Relative time with the absolute time as tooltip; a dash when unset."
  [v & {:keys [class]}]
  (if-let [a (absolute v)]
    [:span.timestamp {:class class :title a} (relative v)]
    [:span.text-muted "—"]))

(defn stamps-line
  "One line of labelled timestamps: [[label value] ...]; unset values are
   left out, e.g. 'created 5 minutes ago · dispatched 4 minutes ago'."
  [pairs]
  (let [shown (filter (comp absolute second) pairs)]
    (when (seq shown)
      (into [:span.timestamps]
            (interpose [:span.text-muted " · "]
                       (for [[label v] shown]
                         [:span [:span.text-muted (str label " ")] [stamp v]]))))))

(defn stamps-title
  "The same as a plain tooltip text."
  [pairs]
  (->> pairs
       (keep (fn [[label v]] (when-let [a (absolute v)] (str label " " a))))
       (str/join ", ")
       not-empty))
