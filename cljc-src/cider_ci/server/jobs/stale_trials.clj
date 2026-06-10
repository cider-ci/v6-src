(ns cider-ci.server.jobs.stale-trials
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
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


(defdaemon "stale-trial-recovery" 60
  (try
    (reset-stale-dispatching! (get-ds))
    (catch Exception e
      (warn "Stale trial recovery error:" (.getMessage e)))))


(defn init []
  (start-stale-trial-recovery))
