(ns cider-ci.server.jobs.stale-trials
  (:require
    [cider-ci.server.db.core :refer [builder-fn-options-default get-ds]]
    [cider-ci.server.db.settings :refer [get-settings]]
    [cider-ci.server.jobs.propagation :as propagation]
    [cider-ci.utils.daemon :refer [defdaemon]]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [info warn]]))


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


(defn- reset-lost-on-executor! [ds]
  (jdbc/with-transaction [raw-tx ds]
    (let [tx   (jdbc/with-options raw-tx builder-fn-options-default)
          rows (jdbc/execute! tx
                 ["UPDATE trials
                   SET state = 'defective',
                       error = 'Trial lost: executor did not send a heartbeat',
                       finished_at = now(), updated_at = now()
                   WHERE state = 'executing'
                     AND updated_at < now() - interval '3 minutes'
                   RETURNING id"])]
      (when (seq rows)
        (info "Marked" (count rows) "trial(s) as defective (lost on executor)")
        (doseq [{:keys [id]} rows]
          (propagation/propagate-from-trial tx id))))))


(defn- reset-stale-executing! [ds]
  (jdbc/with-transaction [raw-tx ds]
    (let [tx   (jdbc/with-options raw-tx builder-fn-options-default)
          rows (jdbc/execute! tx
                 ["UPDATE trials
                   SET state = 'defective',
                       error = 'Execution timed out after 60 minutes',
                       finished_at = now(), updated_at = now()
                   WHERE state = 'executing'
                     AND started_at < now() - interval '60 minutes'
                   RETURNING id"])]
      (when (seq rows)
        (info "Timed out" (count rows) "executing trial(s)")
        (doseq [{:keys [id]} rows]
          (propagation/propagate-from-trial tx id))))))


(defn- abort-pending-timed-out! [ds]
  (jdbc/with-transaction [raw-tx ds]
    (let [tx      (jdbc/with-options raw-tx builder-fn-options-default)
          timeout (:settings/trial_dispatch_timeout (get-settings))
          rows    (jdbc/execute! tx
                   ;; Cast explicitly: an unadorned `now() - ?` makes PostgreSQL
                   ;; type ? as timestamptz, the difference becomes an interval
                   ;; and `created_at < interval` fails. This silently broke the
                   ;; whole recovery cycle (one try block) until 2026-09-22.
                   ["UPDATE trials
                     SET state = 'aborted', updated_at = now()
                     WHERE state = 'pending'
                       AND created_at < now() - CAST(? AS interval)
                     RETURNING id"
                    (str timeout)])]
      (when (seq rows)
        (info "Aborted" (count rows) "pending trial(s) that exceeded dispatch timeout")
        (doseq [{:keys [id]} rows]
          (propagation/propagate-from-trial tx id))))))


(defn- reconcile-stuck-tasks! [ds]
  (jdbc/with-transaction [raw-tx ds]
    (let [tx   (jdbc/with-options raw-tx builder-fn-options-default)
          rows (jdbc/execute! tx
                 ["SELECT DISTINCT ON (tr.task_id) tr.id
                   FROM trials tr
                   JOIN tasks t ON t.id = tr.task_id
                   WHERE t.state NOT IN ('passed','failed','defective','aborted')
                     AND tr.state IN ('passed','failed','defective','aborted')
                     AND NOT EXISTS (
                       SELECT 1 FROM trials tr2
                       WHERE tr2.task_id = tr.task_id
                         AND tr2.state NOT IN ('passed','failed','defective','aborted')
                     )
                   ORDER BY tr.task_id, tr.created_at DESC"])]
      (when (seq rows)
        (info "Reconciling" (count rows) "stuck task(s)")
        (doseq [{:keys [id]} rows]
          (propagation/propagate-from-trial tx id))))))


(defn- guarded [step-name f]
  ;; Each step is isolated so one failing query cannot skip the others.
  (try (f (get-ds))
       (catch Exception e
         (warn "Stale trial recovery error in" step-name ":" (.getMessage e)))))

(defdaemon "stale-trial-recovery" 60
  (guarded "reset-stale-dispatching" reset-stale-dispatching!)
  (guarded "reset-lost-on-executor" reset-lost-on-executor!)
  (guarded "reset-stale-executing" reset-stale-executing!)
  (guarded "abort-pending-timed-out" abort-pending-timed-out!)
  (guarded "reconcile-stuck-tasks" reconcile-stuck-tasks!))


(defn init []
  (start-stale-trial-recovery))
