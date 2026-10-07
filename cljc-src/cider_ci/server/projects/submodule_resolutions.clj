(ns cider-ci.server.projects.submodule-resolutions
  "Can the whole submodule tree of a commit be resolved through the
   configured projects? A forgotten `git push` of a submodule (or a submodule
   that is no project here) breaks the evaluation of the CI configuration and
   forces executors to the submodule's host. The result per commit is stored
   in commit_submodule_resolutions: computed on branch updates, re-checked for
   unresolved commits whenever a repository fetch brings new commits, and on
   demand from the commit page."
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.server.projects.repositories.project-configuration.submodules :as submodules]
    [cider-ci.server.projects.repositories.shared :as repo-shared]
    [cider-ci.utils.daemon :refer [defdaemon]]
    [clojure.data.json :as json]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [info warn]]))


(defn- row->map [row]
  (when row
    (update row :submodules
            #(cond (string? %) (json/read-str % :key-fn keyword)
                   (instance? org.postgresql.util.PGobject %) (json/read-str (.getValue ^org.postgresql.util.PGobject %) :key-fn keyword)
                   :else %))))

(defn get-resolution [ds project-id commit-id]
  (row->map
    (jdbc/execute-one! ds
      ["SELECT repository_id, commit_id, state, submodules::text AS submodules, total, unresolved,
               error, checked_at, created_at
          FROM commit_submodule_resolutions
         WHERE repository_id = ? AND commit_id = ?"
       project-id (str commit-id)])))

(defn check!
  "Resolves the submodule tree of commit-id now, stores and returns the result."
  [ds project-id commit-id]
  (let [commit-id (str commit-id)
        result    (try
                    (with-open [repo (repo-shared/file-repository (repo-shared/path {:project-id project-id}))]
                      (if-not (.resolve repo (str commit-id "^{commit}"))
                        {:state "error" :submodules [] :error (str "commit " commit-id " not present in the repository")}
                        (let [tree  (submodules/submodule-tree repo commit-id)
                              flat  (submodules/flatten-tree tree)
                              total (count flat)
                              unres (count (remove :resolved flat))]
                          {:state      (cond (zero? total) "none" (zero? unres) "resolved" :else "unresolved")
                           :submodules tree
                           :total      total
                           :unresolved unres})))
                    (catch Exception e
                      (warn "submodule resolution of" project-id commit-id "failed:" (.getMessage e))
                      {:state "error" :submodules [] :error (.getMessage e)}))]
    (jdbc/execute-one! ds
      ["INSERT INTO commit_submodule_resolutions
          (repository_id, commit_id, state, submodules, total, unresolved, error, checked_at)
        VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, ?, now())
        ON CONFLICT (repository_id, commit_id) DO UPDATE
          SET state = EXCLUDED.state, submodules = EXCLUDED.submodules, total = EXCLUDED.total,
              unresolved = EXCLUDED.unresolved, error = EXCLUDED.error, checked_at = now()"
       project-id commit-id (:state result) (json/write-str (:submodules result))
       (or (:total result) 0) (or (:unresolved result) 0) (:error result)])
    (get-resolution ds project-id commit-id)))

(defn get-or-check! [ds project-id commit-id]
  (or (get-resolution ds project-id commit-id)
      (check! ds project-id commit-id)))

(defn recheck-unresolved!
  "Re-evaluates commits whose tree could not be resolved (or errored) within
   the last 14 days: a submodule pushed or fetched later may now resolve."
  [ds]
  (doseq [{:keys [repository_id commit_id]}
          (jdbc/execute! ds
            ["SELECT repository_id, commit_id FROM commit_submodule_resolutions
               WHERE state IN ('unresolved', 'error')
                 AND created_at > now() - interval '14 days'"])]
    (let [r (check! ds repository_id commit_id)]
      (when (= "resolved" (:state r))
        (info "submodule tree of" repository_id commit_id "now resolves")))))


(defn backfill-branch-tips!
  "Resolution rows for current branch tips that have none yet (commits
   from before this feature, or whose branch update failed), newest first,
   at most `limit` per call so a large installation fills in gradually."
  [ds & {:keys [limit] :or {limit 20}}]
  (doseq [{:keys [repository_id current_commit_id]}
          (jdbc/execute! ds
            ["SELECT DISTINCT b.repository_id, b.current_commit_id, c.committer_date
                FROM branches b
                JOIN commits c ON c.id = b.current_commit_id
                LEFT JOIN commit_submodule_resolutions r
                       ON r.repository_id = b.repository_id AND r.commit_id = b.current_commit_id
               WHERE r.commit_id IS NULL
                 AND c.committer_date > now() - interval '90 days'
               ORDER BY c.committer_date DESC
               LIMIT ?"
             limit])]
    (check! ds repository_id current_commit_id)))


;; Besides the hooks (branch update, after every repository fetch) a slow
;; cycle keeps the marks complete: backfill of branch tips and the re-check of
;; unresolved commits.
(defdaemon "submodule-resolutions" 60
  (try
    (backfill-branch-tips! (get-ds))
    (recheck-unresolved! (get-ds))
    (catch Exception e
      (warn "submodule-resolutions daemon error:" (.getMessage e)))))

(defn init []
  (start-submodule-resolutions))
