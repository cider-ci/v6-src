(ns cider-ci.server.jobs.auto-trigger
  (:require
    [cider-ci.server.jobs.decompose :as decompose]
    [cider-ci.server.jobs.generate :as generate]
    [cider-ci.server.projects.repositories.project-configuration.direct :as config]
    [cider-ci.server.projects.repositories.shared :as repo-shared]
    [clojure.string :as str]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [info warn]]))


(defn- matches-pattern? [pattern branch-name]
  (when-not (str/blank? pattern)
    (some? (re-find (re-pattern pattern) branch-name))))

(defn- repo-allows-branch? [repo-include repo-exclude branch-name]
  (and (or (str/blank? repo-include)
           (matches-pattern? repo-include branch-name))
       (or (str/blank? repo-exclude)
           (not (matches-pattern? repo-exclude branch-name)))))


(defn- read-job-configs [repo commit-id]
  (try
    (let [cfg      (config/build repo (str commit-id))
          jobs-map (or (:jobs cfg) {})]
      (mapv (fn [[k v]] {:key (name k) :name (or (:name v) (name k)) :spec v}) jobs-map))
    (catch clojure.lang.ExceptionInfo e
      (if (= 404 (:status (ex-data e)))
        []
        (throw e)))))


(defn task-traits-literal
  "PostgreSQL text[] literal of a task spec's traits, lowercased. Accepts the
   map form {Trait: true, Other: false} (only truthy entries count) and the
   list form [Trait Other] (all entries). Anything else yields the empty set,
   which matches every executor — so both forms must be handled or trait
   gating silently disappears (e.g. leihs `traits: [asdf]`)."
  [task-spec]
  (let [traits (:traits task-spec)
        names  (cond
                 (map? traits)        (->> traits (filter (comp true? val)) (map key))
                 (sequential? traits) traits
                 :else                [])]
    (str "{" (->> names (map (comp str/lower-case str/trim name)) (str/join ",")) "}")))


(defn spec-priority
  "The `priority:` of a job or task spec as an integer, 0 when absent or
  unparsable (legacy: `jobs.priority integer DEFAULT 0`)."
  [spec]
  (let [p (:priority spec)]
    (cond
      (integer? p) (int p)
      (number? p)  (int (Math/round (double p)))
      (string? p)  (or (parse-long (str/trim p)) 0)
      :else        0)))


(defn insert-task!
  "Inserts one task row for job-id from a decomposed task spec, deriving the
   traits (text[]) and load columns from the spec. Shared by the auto-trigger
   and the UI create-job path so trait gating cannot silently diverge between
   them (the UI path used to omit traits entirely, so its tasks matched any
   executor regardless of the required traits)."
  [tx job-id task-id task-spec]
  (jdbc/execute-one! tx
    ["INSERT INTO tasks (id, job_id, name, state, spec, traits, load, priority)
      VALUES (?, ?, ?, 'pending', ?, CAST(? AS text[]), ?, ?)"
     task-id job-id (:name task-spec) task-spec
     (task-traits-literal task-spec)
     (double (or (:load task-spec) 1.0))
     (spec-priority task-spec)]))


(defn create-job-with-tasks! [tx project-id commit-id {:keys [key name spec]}]
  (let [expanded   (generate/expand project-id (str commit-id) spec)
        new-job-id (java.util.UUID/randomUUID)
        result     (jdbc/execute-one! tx
                     ["INSERT INTO jobs (id, project_id, commit_id, key, name, state, spec, priority)
                       VALUES (?, ?, ?, ?, ?, 'pending', ?, ?)
                       ON CONFLICT (project_id, commit_id, key) DO NOTHING"
                      new-job-id project-id (str commit-id) key name expanded
                      (spec-priority expanded)])]
    (when (= 1 (:next.jdbc/update-count result))
      (info "Auto-triggered job" key "for" project-id commit-id)
      (doseq [task-spec (decompose/decompose expanded)]
        (let [new-task-id (java.util.UUID/randomUUID)]
          (insert-task! tx new-job-id new-task-id task-spec)
          (jdbc/execute-one! tx
            ["INSERT INTO trials (task_id, state) VALUES (?, 'pending')"
             new-task-id]))))))


(defn- commit-within-age? [ds commit-id max-age]
  (if (nil? max-age)
    true
    (let [result (jdbc/execute-one! ds
                   ["SELECT committer_date IS NULL
                       OR committer_date >= NOW() - CAST(? AS INTERVAL) AS within_age
                     FROM commits WHERE id = ?"
                    max-age (str commit-id)])]
      (not (false? (:within_age result))))))


(defn- branch-run-when-entries
  "Branch-type run_when entries of a job spec. Accepts the map form
   {name {type: branch ...}} and the list form [{type: branch ...}]; the older
   `trigger: {branch: {include_match ...}}` shorthand is treated as one entry."
  [spec]
  (let [rw      (:run_when spec)
        entries (cond (map? rw) (vals rw) (sequential? rw) rw :else [])
        legacy  (when-let [b (get-in spec [:trigger :branch])] [(assoc b :type "branch")])]
    (->> (concat entries legacy)
         (filter #(= "branch" (some-> (get % :type) name))))))

(defn- job-should-trigger?
  "Legacy semantics (builder.jobs.triggers.tree-ids.branch-update): a branch
   update triggers a job only if it has a run_when entry of type `branch`
   whose include_match matches the branch and whose exclude_match does not.
   Jobs without such an entry are never auto-triggered by a push — they run
   via the dep-trigger (depends_on / run_when type job), cron, or manually.
   (v6 used to fire every job lacking trigger configuration; that is why e.g.
   leihs' `all-broken-scenarios` ran on every push here but not on the old CI.)"
  [{:keys [spec]} branch-name]
  (boolean
    (and (empty? (:depends_on spec))
         (some (fn [{:keys [include_match exclude_match]}]
                 (and (or (str/blank? include_match)
                          (matches-pattern? include_match branch-name))
                      (not (and (not (str/blank? exclude_match))
                                (matches-pattern? exclude_match branch-name)))))
               (branch-run-when-entries spec)))))


(defn- record-pending!
  "Remember a branch update whose configuration could not be evaluated so
   the branch-trigger-retry daemon tries again (e.g. after the submodule
   commit it needs has been pushed and fetched)."
  [ds project-id commit-id branch-name error]
  (jdbc/execute-one! ds
    ["INSERT INTO pending_branch_triggers (repository_id, commit_id, branch_name, error)
      VALUES (?, ?, ?, ?)
      ON CONFLICT (repository_id, commit_id, branch_name) DO UPDATE
        SET error = EXCLUDED.error,
            attempts = pending_branch_triggers.attempts + 1,
            updated_at = now()"
     project-id (str commit-id) branch-name error]))

(defn- clear-pending! [ds project-id commit-id branch-name]
  (jdbc/execute-one! ds
    ["DELETE FROM pending_branch_triggers
      WHERE repository_id = ? AND commit_id = ? AND branch_name = ?"
     project-id (str commit-id) branch-name]))

(defn trigger-for-commit!
  "Evaluates the job configuration of `commit-id` and creates the jobs a
   branch update of `branch-name` triggers (run_when type branch). Returns
   :skipped (branch or age filter), :ok, or :pending when the configuration
   could not be evaluated or a job could not be created; the latter is
   recorded in pending_branch_triggers for the retry daemon."
  [ds project-id commit-id branch-name
   & {:keys [repo-include repo-exclude repo-max-age]
      :or   {repo-include "^.*$" repo-exclude "" repo-max-age nil}}]
  (if-not (and (repo-allows-branch? repo-include repo-exclude branch-name)
               (commit-within-age? ds commit-id repo-max-age))
    :skipped
    (let [errors (atom [])]
      (try
        (with-open [repo (repo-shared/file-repository (repo-shared/path {:project-id project-id}))]
          (let [job-configs (read-job-configs repo commit-id)]
            (doseq [job-config job-configs]
              (when (job-should-trigger? job-config branch-name)
                (try
                  (jdbc/with-transaction [tx ds]
                    (create-job-with-tasks! tx project-id commit-id job-config))
                  (catch Exception e
                    (warn "Failed to auto-trigger job" (:key job-config)
                          "for" project-id commit-id "on" branch-name ":" (.getMessage e))
                    (swap! errors conj (str "job `" (:key job-config) "`: " (.getMessage e)))))))))
        (catch Exception e
          (warn "Auto-trigger failed for" project-id commit-id "on" branch-name ":" (.getMessage e))
          (swap! errors conj (.getMessage e))))
      (if (seq @errors)
        (do (record-pending! ds project-id commit-id branch-name (str/join "\n" @errors))
            :pending)
        (do (clear-pending! ds project-id commit-id branch-name)
            :ok)))))


(defn retry-pending!
  "Re-evaluates every recorded pending branch trigger. Entries whose commit
   is older than the repository's branch_trigger_max_commit_age (or the
   settings default) are dropped. `settings-default-age` is the fallback
   interval; `min-age-seconds` avoids retrying an entry that was just
   recorded."
  [ds & {:keys [min-age-seconds] :or {min-age-seconds 5}}]
  (let [default-age (jdbc/execute-one! ds
                      ["SELECT branch_trigger_max_commit_age_default::text AS age FROM settings WHERE id = 0"])
        pending     (jdbc/execute! ds
                      ["SELECT p.repository_id, p.commit_id, p.branch_name,
                              r.branch_trigger_include_match, r.branch_trigger_exclude_match,
                              r.branch_trigger_max_commit_age
                         FROM pending_branch_triggers p
                         JOIN repositories r ON r.id = p.repository_id
                        WHERE p.updated_at < now() - CAST(? AS interval)
                        ORDER BY p.created_at"
                       (str min-age-seconds " seconds")])]
    (doseq [{:keys [repository_id commit_id branch_name
                    branch_trigger_include_match branch_trigger_exclude_match
                    branch_trigger_max_commit_age]} pending]
      (let [max-age (or branch_trigger_max_commit_age (:age default-age))]
        (if-not (commit-within-age? ds commit_id max-age)
          (do (info "branch-trigger-retry: dropping" repository_id commit_id branch_name
                    "- commit older than" max-age)
              (clear-pending! ds repository_id commit_id branch_name))
          (let [result (trigger-for-commit! ds repository_id commit_id branch_name
                                            :repo-include (or branch_trigger_include_match "^.*$")
                                            :repo-exclude (or branch_trigger_exclude_match "")
                                            :repo-max-age max-age)]
            (when (= :skipped result)
              ;; branch filter changed meanwhile: nothing to retry
              (clear-pending! ds repository_id commit_id branch_name))
            (when (= :ok result)
              (info "branch-trigger-retry: resolved" repository_id commit_id branch_name))))))))
