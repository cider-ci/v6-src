(ns cider-ci.server.jobs.propagation
  (:require
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.sql :as jdbc-sql]))


(def terminal-states #{"passed" "failed" "defective" "aborted"})

(defn- task-state-from-trials
  "Task passes as soon as ANY trial passes (resilience semantics).
   Task aborted when all trials are aborted.
   Task fails/defective only when all trials are terminal and none passed."
  [states]
  (cond
    (empty? states)                              "defective"
    (some #{"passed"} states)                    "passed"
    (some #{"executing" "dispatching"} states)   "executing"
    (some #{"aborting"} states)                  "aborting"
    (some #{"pending"} states)                   "pending"
    (every? #{"aborted"} states)                 "aborted"
    (every? #{"defective"} states)               "defective"
    (some #{"failed"} states)                    "failed"
    :else                                        "defective"))

(defn- job-state-from-tasks
  "Job passes only when ALL tasks pass. Returns nil when not yet decided.
   job-state is the current job state; when aborting, terminal non-passed tasks
   resolve to 'aborted' rather than 'failed'/'defective'."
  [states job-state]
  (cond
    (not-every? terminal-states states)  nil
    (every? #{"passed"} states)          "passed"
    (every? #{"aborted"} states)         "aborted"
    (= job-state "aborting")             "aborted"
    (some #{"defective"} states)         "defective"
    (some #{"aborted"} states)           "failed"
    :else                                "failed"))


(defn propagate-from-task [tx task-id]
  (let [task       (first (jdbc-sql/query tx
                            (-> (sql/select :job_id)
                                (sql/from :tasks)
                                (sql/where [:= :id task-id])
                                sql-format)))
        job-id     (:job_id task)
        job        (first (jdbc-sql/query tx
                            (-> (sql/select :state)
                                (sql/from :jobs)
                                (sql/where [:= :id job-id])
                                sql-format)))
        job-state  (:state job)
        states     (map :state (jdbc-sql/query tx
                                 (-> (sql/select :state)
                                     (sql/from :tasks)
                                     (sql/where [:= :job_id job-id])
                                     sql-format)))]
    (when-let [new-state (job-state-from-tasks states job-state)]
      (jdbc/execute-one! tx
        ["UPDATE jobs SET state = ?, updated_at = now() WHERE id = ?"
         new-state job-id]))))


(defn propagate-from-trial [tx trial-id]
  (let [trial         (first (jdbc-sql/query tx
                               (-> (sql/select :task_id)
                                   (sql/from :trials)
                                   (sql/where [:= :id trial-id])
                                   sql-format)))
        task-id       (:task_id trial)
        task          (first (jdbc-sql/query tx
                               (-> (sql/select :spec)
                                   (sql/from :tasks)
                                   (sql/where [:= :id task-id])
                                   sql-format)))
        spec          (:spec task)
        satisfy-last? (= "satisfy-last" (:aggregate_state spec))
        eager         (or (:eager_trials spec) 1)
        max-trials    (or (:max_trials spec) 2)
        states        (map :state (jdbc-sql/query tx
                                    (-> (sql/select :state)
                                        (sql/from :trials)
                                        (sql/where [:= :task_id task-id])
                                        (sql/order-by [:created_at :asc])
                                        sql-format)))
        prelim        (if satisfy-last?
                        (let [s (or (last states) "defective")]
                          (case s "dispatching" "executing" s))
                        (task-state-from-trials states))
        new-state     (if (and (not satisfy-last?)
                               (terminal-states prelim)
                               (not= prelim "passed")
                               (not= prelim "aborted"))
                        (let [in-progress (count (filter #{"pending" "dispatching" "executing"} states))
                              total       (count states)
                              to-create   (max 0 (min (- eager in-progress) (- max-trials total)))]
                          (doseq [_ (range to-create)]
                            (jdbc/execute-one! tx
                              (-> (sql/insert-into :trials)
                                  (sql/values [{:task_id task-id}])
                                  sql-format)))
                          (if (pos? to-create) "pending" prelim))
                        prelim)]
    (jdbc/execute-one! tx
      ["UPDATE tasks SET state = ?, updated_at = now() WHERE id = ?"
       new-state task-id])
    (cond
      (= new-state "executing")
      (jdbc/execute-one! tx
        ["UPDATE jobs SET state = 'executing', updated_at = now()
          WHERE id = (SELECT job_id FROM tasks WHERE id = ?)
            AND state = 'pending'"
         task-id])

      (terminal-states new-state)
      (propagate-from-task tx task-id))))
