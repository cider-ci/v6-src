(ns cider-ci.server.jobs.dependencies
  "Evaluates `depends_on` entries of a job spec (legacy parity with
   cider-ci.server.builder.jobs.dependencies).

     some name:
       type: job
       job_key: merged-to-master
       submodule: [database]        ; optional chain of submodule paths
       states: [passed]

   A plain job dependency is satisfied by a job of the same project/commit.
   A submodule-qualified one is resolved through the gitlink chain of the
   current commit (see project-configuration.submodules) and satisfied by a
   job with that key on the submodule's commit — in whichever project that
   commit was tested (legacy matched by tree id across projects)."
  (:require
    [cider-ci.server.projects.repositories.project-configuration.submodules :as submodules]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug warn]]))

(defn- latest-job-state
  "State of the most recent job with key on commit-id in any project, or nil."
  [ds commit-id job-key]
  (:state (jdbc/execute-one! ds
            ["SELECT state FROM jobs WHERE commit_id = ? AND key = ?
              ORDER BY created_at DESC LIMIT 1"
             commit-id job-key]
            {:builder-fn next.jdbc.result-set/as-unqualified-lower-maps})))

(defn satisfied?
  "ds: datasource; repo: open JGit repository of the job's project; commit-id:
   the job's commit; dep: the depends_on entry; existing-by-key: {key job-row}
   of this project/commit (job rows with at least :state)."
  [ds repo commit-id dep existing-by-key]
  (let [dep-type   (some-> dep :type name)
        job-key    (some-> dep :job_key name)
        states     (set (map name (or (:states dep) [])))
        submodules (mapv name (or (:submodule dep) []))]
    (cond
      (and dep-type (not= dep-type "job")) false
      (nil? job-key)                       false
      (empty? submodules)
      (let [existing (get existing-by-key job-key)]
        (boolean (and existing (contains? states (name (:state existing))))))
      :else
      (try
        (let [{sub-commit :commit-id} (submodules/resolve-submodule-chain
                                        {:repo repo :commit-id commit-id} submodules)
              state (latest-job-state ds sub-commit job-key)]
          (debug "submodule dependency" submodules job-key "->" sub-commit state)
          (boolean (and state (contains? states state))))
        (catch Exception e
          (warn "Cannot evaluate submodule dependency" (pr-str dep) "for commit" commit-id ":" (.getMessage e))
          false)))))

(defn unmet
  "Names of the depends_on entries of spec that are not satisfied."
  [ds repo commit-id spec existing-by-key]
  (->> (:depends_on spec)
       (remove (fn [[_ dep]] (satisfied? ds repo commit-id dep existing-by-key)))
       (map (fn [[dep-name _]] (name dep-name)))
       vec))
