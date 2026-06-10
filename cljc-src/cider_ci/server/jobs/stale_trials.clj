(ns cider-ci.server.jobs.stale-trials
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.utils.daemon :refer [defdaemon]]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [info warn]]))


(def ^:private terminal-states #{"passed" "failed" "defective" "aborted"})

(defn- propagate-task! [tx task-id]
  (let [states (map :state (jdbc/execute! tx
                             ["SELECT state FROM trials WHERE task_id = ?" task-id]))]
    (when (every? terminal-states states)
      (let [new-state (if (every? #(= "passed" %) states) "passed" "failed")
            task-row  (first (jdbc/execute! tx
                               ["UPDATE tasks SET state = ?, updated_at = now()
                                 WHERE id = ? RETURNING job_id" new-state task-id]))]
        (when task-row
          (let [job-states (map :state (jdbc/execute! tx
                                         ["SELECT state FROM tasks WHERE job_id = ?"
                                          (:job_id task-row)]))]
            (when (every? terminal-states job-states)
              (let [job-new-state (if (every? #(= "passed" %) job-states) "passed" "failed")]
                (jdbc/execute! tx
                  ["UPDATE jobs SET state = ?, updated_at = now() WHERE id = ?"
                   job-new-state (:job_id task-row)])))))))))


(defn- reset-stale-dispatching! [ds]
  (let [rows (jdbc/execute! ds
               ["UPDATE trials
                 SET state = 'pending', executor_id = NULL,
                     dispatched_at = NULL, updated_at = now()
                 WHERE state = 'dispatching'
                   AND dispatched_at < now() - interval '5 minutes'
                 RETURNING id"])]
    (when (seq rows)
      (info "Reset" (count rows) "stale dispatching trial(s) to pending"))))


(defn- reset-stale-executing! [ds]
  (jdbc/with-transaction [tx ds]
    (let [rows (jdbc/execute! tx
                 ["UPDATE trials
                   SET state = 'defective',
                       error = 'Execution timed out after 60 minutes',
                       finished_at = now(), updated_at = now()
                   WHERE state = 'executing'
                     AND started_at < now() - interval '60 minutes'
                   RETURNING task_id"])]
      (when (seq rows)
        (info "Timed out" (count rows) "executing trial(s)")
        (doseq [{:keys [task_id]} rows]
          (propagate-task! tx task_id))))))


(defdaemon "stale-trial-recovery" 60
  (try
    (reset-stale-dispatching! (get-ds))
    (reset-stale-executing! (get-ds))
    (catch Exception e
      (warn "Stale trial recovery error:" (.getMessage e)))))


(defn init []
  (start-stale-trial-recovery))
