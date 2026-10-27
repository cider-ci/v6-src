(ns cider-ci.utils.accepted-repositories
  "Which repositories, branches and jobs an executor accepts trials for. An
   entry is {:url pattern :branches [pattern ...] :jobs [pattern ...]};
   :branches and :jobs are optional, a plain string entry is {:url string}.
   All given parts of one entry must match; a trial is accepted when some
   entry matches. A pattern is either an exact value or, when
   it starts with `^` and ends with `$`, a regex that must match the whole
   value (legacy semantics). The server applies the same rules in SQL
   (`cider_ci_trial_accepted`).

   Without :branches any commit reachable on the repository's origin is
   accepted; with :branches the commit must be the head of a matching branch
   (deploy executors: leverages the git host's branch protection).

   :jobs matches the job key (the key in cider-ci.yml, e.g. deploy_staging).
   This is a SOFT restriction, unlike :url (verified on the origin) and
   :branches (verified on the origin, backed by branch protection): anyone
   who can push can rename a job, and the executor learns the job key only
   from the server. Use it for routing, not for security; on deploy
   executors put it on the same entry as :branches. See
   docs/executors/accepted-repositories.md."
  (:require
    [clojure.string :as str]
    [taoensso.timbre :refer [warn]]))


(def accept-all [{:url "^.*$"}])

(defn regex-pattern? [^String pattern]
  (boolean (re-matches #"^\^.*\$$" (or pattern ""))))

(defn- regex-matches? [pattern s]
  (try
    (boolean (re-matches (re-pattern pattern) s))
    (catch java.util.regex.PatternSyntaxException _
      (warn "Ignoring invalid accepted repositories regex" pattern)
      false)))

(defn matches? [pattern s]
  (boolean
    (and (string? pattern) (string? s)
         (or (= pattern s)
             (and (regex-pattern? pattern) (regex-matches? pattern s))))))

(defn- normalize-patterns [entry k patterns]
  (cond-> entry
    (string? patterns)     (assoc k [patterns])
    (sequential? patterns) (assoc k (mapv str patterns))))

(defn- normalize-entry [entry]
  (cond
    (string? entry) {:url entry}
    (map? entry) (-> {:url (str (or (:url entry) (get entry "url")))}
                     (normalize-patterns :branches (or (:branches entry) (get entry "branches")))
                     (normalize-patterns :jobs (or (:jobs entry) (get entry "jobs"))))
    :else nil))

(defn normalize-entries [entries]
  (->> entries (keep normalize-entry) (remove #(str/blank? (:url %))) vec))

(defn- job-matches?
  "No :jobs accepts any job (soft restriction, see the ns docstring)."
  [entry job-key]
  (or (nil? (:jobs entry))
      (boolean (some #(matches? % job-key) (:jobs entry)))))

(defn matching-entries
  "Entries matching url and, when given, the job key. The job must be
   filtered before the origin policy is derived: an entry for other jobs
   must not relax the branch restriction of a deploy job's entry."
  ([entries url]
   (filterv #(matches? (:url %) url) entries))
  ([entries url job-key]
   (filterv #(job-matches? % job-key) (matching-entries entries url))))

(defn origin-policy
  "What the origin of url must confirm for a commit of the job job-key, or
   nil when no entry accepts url and job: :reachable? (an entry without
   branches matches) and :head-branches (branch patterns of which the commit
   must be a head)."
  [entries url job-key]
  (when-let [ms (seq (matching-entries entries url job-key))]
    {:reachable?    (boolean (some #(nil? (:branches %)) ms))
     :head-branches (vec (distinct (mapcat :branches ms)))}))

(defn assert-accepted! [entries url job-key]
  (when (str/blank? url)
    (throw (ex-info "The trial has no repository url to check against the accepted repositories."
                    {:accepted_repositories entries})))
  (when (empty? (matching-entries entries url))
    (throw (ex-info (str "The repository " url " is not included in the accepted repositories of this executor.")
                    {:repository_url url :accepted_repositories entries})))
  (when (empty? (matching-entries entries url job-key))
    (throw (ex-info (str "The job " (pr-str job-key) " of " url " is not included in the accepted jobs of this executor.")
                    {:repository_url url :job_key job-key :accepted_repositories entries}))))
