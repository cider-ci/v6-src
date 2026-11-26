(ns cider-ci.server.jobs.abort
  "Aborting a job: pending trials are aborted at once, dispatching and
   executing ones are set to aborting and killed by their executor at its
   next sync; tasks and the job follow. Used by the job page's abort button
   and by the branch update when a commit falls off every branch head."
  (:require
    [next.jdbc :as jdbc]))

(def final-states ["passed" "failed" "defective" "aborted"])

(defn abort-job!
  "Within tx. reason (optional) is recorded on the job."
  [tx job-uuid & {:keys [reason]}]
  (jdbc/execute! tx
    ["UPDATE trials t SET state = 'aborted', finished_at = now(), updated_at = now()
      FROM tasks tsk WHERE t.task_id = tsk.id AND tsk.job_id = ?
        AND t.state = 'pending'"
     job-uuid])
  (jdbc/execute! tx
    ["UPDATE trials t SET state = 'aborting', updated_at = now()
      FROM tasks tsk WHERE t.task_id = tsk.id AND tsk.job_id = ?
        AND t.state IN ('dispatching', 'executing')"
     job-uuid])
  (jdbc/execute! tx
    ["UPDATE tasks
      SET state = CASE
        WHEN EXISTS (SELECT 1 FROM trials WHERE task_id = tasks.id AND state = 'aborting')
        THEN 'aborting'
        ELSE 'aborted'
      END,
      updated_at = now()
      WHERE job_id = ?
        AND state NOT IN ('passed', 'failed', 'defective', 'aborted', 'aborting')"
     job-uuid])
  (jdbc/execute-one! tx
    ["UPDATE jobs SET state = 'aborting', abort_reason = COALESCE(?, abort_reason), updated_at = now()
      WHERE id = ? AND state NOT IN ('passed', 'failed', 'defective', 'aborted')"
     reason job-uuid]))

(defn active-reachable-commits
  "The commits of the project's unfinished jobs that are reachable from a
   branch head right now (any repository): the candidates for revocation
   by the branch update about to happen."
  [tx project-id]
  (->> (jdbc/execute! tx
         ["SELECT DISTINCT j.commit_id
           FROM jobs j
           WHERE j.project_id = ? AND j.state NOT IN ('passed', 'failed', 'defective', 'aborted', 'aborting')
             AND EXISTS (SELECT 1 FROM branches_commits bc WHERE bc.commit_id = j.commit_id)"
          project-id]
         {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps})
       (map :commit_id)
       set))

(defn revoke-unreachable!
  "After a branch update: of the commits given (reachable before), those in
   no branch any more have their unfinished jobs aborted. Returns the
   aborted job ids. Legacy parity (dispatcher.abort, detached jobs), event
   based instead of a sweep so that jobs started by hand on a superseded
   commit later are left alone."
  [tx project-id commit-ids]
  (if (empty? commit-ids)
    []
    (let [jobs (jdbc/execute! tx
                 ["SELECT j.id, j.key, j.commit_id
                   FROM jobs j
                   WHERE j.project_id = ? AND j.commit_id = ANY (?)
                     AND j.state NOT IN ('passed', 'failed', 'defective', 'aborted', 'aborting')
                     AND NOT EXISTS (SELECT 1 FROM branches_commits bc WHERE bc.commit_id = j.commit_id)"
                  project-id (into-array String commit-ids)]
                 {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps})]
      (doseq [{:keys [id]} jobs]
        (abort-job! tx id :reason "the commit is no longer reachable from any branch head"))
      (mapv :id jobs))))
