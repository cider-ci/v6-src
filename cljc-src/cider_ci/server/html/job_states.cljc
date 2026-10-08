(ns cider-ci.server.html.job-states
  "Job state groups as shown on the commits page (count badges) and used as
   `?state=` filter on a commit's jobs page (legacy parity)."
  (:require [clojure.string :as str]))

(def groups
  "[key label states badge-class] in display order."
  [[:failed    "failed"    #{"failed" "defective" "aborted"} "bg-danger"]
   [:passed    "passed"    #{"passed"}                       "bg-success"]
   [:executing "executing" #{"executing" "aborting"}        "bg-primary"]
   [:pending   "pending"   #{"pending"}                      "bg-secondary"]])

(defn group-states
  "The job states of the group named by key (a keyword or string), nil when
   unknown."
  [k]
  (when-let [k (some-> k name str/lower-case keyword)]
    (some (fn [[gk _ states _]] (when (= gk k) states)) groups)))

(defn count-by-group [jobs]
  (into {} (for [[k _ states _] groups]
             [k (count (filter #(contains? states (:state %)) jobs))])))
