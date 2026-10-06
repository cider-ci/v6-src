(ns cider-ci.server.jobs.branch-trigger-retry
  "Retries branch updates whose job configuration could not be evaluated
   (see pending_branch_triggers). The typical case: a project commit points
   to a submodule commit that is pushed only later; once the submodule's
   repository has been fetched the configuration resolves and the jobs that
   a push would have triggered are created. The repository's
   branch_trigger_max_commit_age still applies: older commits are dropped."
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.server.jobs.auto-trigger :as auto-trigger]
    [cider-ci.utils.daemon :refer [defdaemon]]
    [taoensso.timbre :refer [warn]]))


(defdaemon "branch-trigger-retry" 15
  (try
    (auto-trigger/retry-pending! (get-ds))
    (catch Exception e
      (warn "branch-trigger-retry daemon error:" (.getMessage e)))))


(defn init []
  (start-branch-trigger-retry))
