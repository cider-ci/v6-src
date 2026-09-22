(ns cider-ci.server.projects.repositories.project-configuration.submodules
  "Resolves `submodule:` references in project configuration (include /
   read_and_replace_with / generate_tasks), porting the legacy semantics of
   cider-ci.server.repository.project-configuration.expansion:

     include:
       - path: cider-ci/task-components/database.yml
         submodule: [database]          ; chain: submodule of a submodule of ...

   For each path segment the segment's GITLINK entry in the current commit's
   tree yields the submodule commit, and `.gitmodules` yields its URL. The URL
   is matched (canonicalized) against the configured repositories; as a
   fallback any candidate repository that actually contains the commit is
   accepted — notably the parent repository itself for self-referential
   submodules (as used by the demo project). Legacy used a `submodules` DB
   table populated on import; v6 reads git directly via JGit instead."
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.server.projects.repositories.shared :as shared]
    [clojure.string :as str]
    [next.jdbc :as jdbc])
  (:import
    [org.eclipse.jgit.lib FileMode Repository]
    [org.eclipse.jgit.revwalk RevWalk]
    [org.eclipse.jgit.treewalk TreeWalk]
    [org.eclipse.jgit.treewalk.filter PathFilter]))


;;; git helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn repo-id
  "The repository id of a JGit repo. Bare repos live at
   <repositories-dir>/<id>, so the git dir's name is the id."
  [^Repository repo]
  (.getName (.getDirectory repo)))

(defn- tree-entry
  "Returns {:object-id ObjectId :mode FileMode} for path in commit-id, or nil."
  [^Repository repo commit-id ^String path]
  (when-let [object-id (.resolve repo (str commit-id "^{commit}"))]
    (let [revwalk (RevWalk. repo)
          tree    (.getTree (.parseCommit revwalk object-id))
          tw      (TreeWalk. repo)]
      (try
        (.setRecursive tw false)
        (.addTree tw tree)
        (.setFilter tw (PathFilter/create path))
        (loop []
          (when (.next tw)
            (cond
              (= (.getPathString tw) path) {:object-id (.getObjectId tw 0)
                                            :mode      (.getFileMode tw 0)}
              (.isSubtree tw)              (do (.enterSubtree tw) (recur))
              :else                        (recur))))
        (finally (.close tw) (.close revwalk))))))

(defn- read-string-at
  "UTF-8 content of a blob at path in commit-id, or nil when absent."
  [^Repository repo commit-id path]
  (when-let [{:keys [object-id mode]} (tree-entry repo commit-id path)]
    (when-not (= mode FileMode/GITLINK)
      (String. (.getBytes (.open repo object-id)) "UTF-8"))))

(defn gitlink-commit
  "Commit SHA (string) the submodule at path points to in commit-id, or nil
   when path is not a submodule (GITLINK) entry there."
  [^Repository repo commit-id path]
  (when-let [{:keys [object-id mode]} (tree-entry repo commit-id path)]
    (when (= mode FileMode/GITLINK)
      (.getName object-id))))

(defn parse-gitmodules
  "Parses .gitmodules INI content into {path url}."
  [s]
  (->> (str/split-lines (or s ""))
       (reduce (fn [[acc cur] line]
                 (let [line (str/trim line)]
                   (cond
                     (re-matches #"\[submodule\s+\".*\"\]" line)
                     [(cond-> acc (and (:path cur) (:url cur)) (assoc (:path cur) (:url cur))) {}]
                     :else
                     (if-let [[_ k v] (re-matches #"(\w+)\s*=\s*(.*)" line)]
                       [acc (assoc cur (keyword k) (str/trim v))]
                       [acc cur]))))
               [{} {}])
       ((fn [[acc cur]] (cond-> acc (and (:path cur) (:url cur)) (assoc (:path cur) (:url cur)))))))

(defn submodule-url
  "URL declared in .gitmodules for the submodule at path, or nil."
  [^Repository repo commit-id path]
  (get (parse-gitmodules (read-string-at repo commit-id ".gitmodules")) path))

(defn repo-contains-commit? [^Repository repo sha]
  (boolean (try (.resolve repo (str sha "^{commit}")) (catch Exception _ nil))))


;;; repository lookup ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn canonic-git-url
  "host/owner/repo, lowercased, without scheme, user, port, `.git` or a
   trailing slash — so https://, ssh:// and scp-like git@host:owner/repo.git
   forms compare equal."
  [url]
  (when-let [u (some-> url str/trim not-empty)]
    (let [u (str/lower-case u)
          u (str/replace u #"^[a-z+]+://" "")            ; scheme
          u (str/replace u #"^[^@/]+@" "")                ; user@
          u (str/replace u #"^([^/:]+):(?!\d)" "$1/")     ; scp-like host:owner -> host/owner
          u (str/replace u #"^([^/]+):\d+/" "$1/")        ; host:port/ -> host/
          u (str/replace u #"/+$" "")
          u (str/replace u #"\.git$" "")]
      u)))

(defn- repositories-matching-url [url]
  (let [target (canonic-git-url url)]
    (when target
      (->> (jdbc/execute! (get-ds) ["SELECT id, git_url FROM repositories"])
           (filter #(= target (canonic-git-url (:git_url %))))
           (mapv :id)))))


;;; resolution ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn resolve-submodule
  "Resolves one submodule path segment from ctx {:repo :commit-id} to
   {:repo-id :commit-id} of the submodule. Candidate repositories are those
   whose git_url canonically matches the .gitmodules url, then the parent
   repository itself (self-referential submodules); the first that contains
   the gitlink commit wins. Throws ex-info {:status 422} otherwise. Note: the
   parent ctx's repo is NOT opened again; callers open sub repos by id."
  [{:keys [^Repository repo commit-id]} segment]
  (let [sub-commit (gitlink-commit repo commit-id segment)
        url        (submodule-url repo commit-id segment)
        parent-id  (repo-id repo)]
    (when-not sub-commit
      (throw (ex-info (str "Submodule `" segment "` is not a submodule (gitlink) entry in commit "
                           commit-id " of repository `" parent-id "`.")
                      {:status 422 :submodule segment :commit-id commit-id :repository parent-id})))
    (let [by-url     (when url (repositories-matching-url url))
          candidates (distinct (concat by-url [parent-id]))
          found      (some (fn [id]
                             (with-open [^Repository r (shared/file-repository (shared/path {:id id}))]
                               (when (repo-contains-commit? r sub-commit) id)))
                           candidates)]
      (when-not found
        (throw (ex-info (str "Cannot resolve submodule `" segment "` (url " (or url "?") ", commit "
                             sub-commit "): no configured repository contains that commit. "
                             "Configure the submodule's repository as a project"
                             (when (seq by-url) (str " (url matched: " (str/join ", " by-url) ")"))
                             ".")
                        {:status 422 :submodule segment :url url :submodule-commit sub-commit
                         :candidates candidates})))
      {:repo-id found :commit-id sub-commit})))

(defn resolve-submodule-chain
  "Follows a chain of submodule path segments starting from ctx {:repo :commit-id}.
   Returns {:repo-id :commit-id} of the final submodule (or the parent's own
   id/commit when segments is empty). Intermediate repositories are opened and
   closed here."
  [{:keys [^Repository repo commit-id] :as ctx} segments]
  (if (empty? segments)
    {:repo-id (repo-id repo) :commit-id commit-id}
    (loop [cur   (resolve-submodule ctx (first segments))
           rest' (rest segments)]
      (if (empty? rest')
        cur
        (recur (with-open [^Repository r (shared/file-repository (shared/path {:id (:repo-id cur)}))]
                 (resolve-submodule {:repo r :commit-id (:commit-id cur)} (first rest')))
               (rest rest'))))))

(defn open-repo
  "Opens the bare repository with the given id. Caller must close it."
  ^Repository [repo-id]
  (shared/file-repository (shared/path {:id repo-id})))
