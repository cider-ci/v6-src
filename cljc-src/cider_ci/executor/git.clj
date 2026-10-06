(ns cider-ci.executor.git
  (:require
    [clojure.string :as str]
    [taoensso.timbre :refer [info warn]])
  (:import
    [java.io File]
    [java.math BigInteger]
    [java.security MessageDigest]))


(def ^:private cache-root*
  (atom (or (System/getenv "CIDER_CI_EXECUTOR_GIT_CACHE_DIR") "/var/tmp/cider-ci-git-cache")))

(defn set-cache-root! [path] (reset! cache-root* path))

;; One lock object per remote URL — serialises bare-clone updates.
(defonce ^:private repo-locks* (atom {}))


(defn- sha1-hex [^String s]
  (format "%040x"
    (BigInteger. 1
      (.digest (MessageDigest/getInstance "SHA-1")
               (.getBytes s "UTF-8")))))

(defn- repo-lock [git-url]
  (get (swap! repo-locks* update git-url #(or % (Object.))) git-url))

(def ^:private no-auto-gc-args
  ;; Never let git start background maintenance in a trial working dir: after
  ;; `git submodule update` (a fetch) git may detach `gc --auto`, which then
  ;; repacks and deletes pack files WHILE trial scripts run — e.g. leihs'
  ;; container-build lost .git/modules/database/objects/pack/*.pack mid
  ;; `incus file push` ("Error: file does not exist"). Throwaway checkouts
  ;; do not need gc at all.
  ["-c" "gc.auto=0" "-c" "gc.autoDetach=false" "-c" "maintenance.auto=false"])

(defn- with-git-defaults [cmd]
  (if (= "git" (first cmd))
    (into ["git"] (concat no-auto-gc-args (rest cmd)))
    (vec cmd)))

(defn redact
  "Hides the executor's bearer token (passed to git as an extraheader) in
   strings that end up in logs or trial errors."
  [^String s]
  (str/replace (or s "") #"(Authorization: Bearer )\S+" "$1<redacted>"))

(defn- tail-str [^String s n]
  (if (> (count s) n) (str "..." (subs s (- (count s) n))) s))

(defn- run!
  "Runs a git command, returns its output (stdout+stderr). Throws on a non-zero
   exit with the (redacted) command and the last lines of the output in the
   message: without them a failed clone/fetch is undiagnosable."
  [cmd ^File dir]
  (let [cmd (with-git-defaults cmd)
        pb  (doto (ProcessBuilder. ^java.util.List (vec cmd))
              (.redirectErrorStream true))]
    (when dir (.directory pb dir))
    (doto (.environment pb)
      (.put "GIT_TERMINAL_PROMPT" "0"))
    (let [proc (.start pb)
          out  (slurp (.getInputStream proc))
          exit (.waitFor proc)]
      (when-not (zero? exit)
        (throw (ex-info (str "git command failed (exit " exit "): " (redact (str/join " " cmd))
                             (when-not (str/blank? out)
                               (str "\n" (redact (tail-str (str/trim out) 2000)))))
                        {:cmd (mapv redact cmd) :exit exit :output (redact out)})))
      out)))

(defn- with-retries
  "Runs f, retrying up to `attempts` times on exceptions (transient network
   problems with the git host, e.g. GitHub throttling); waits 5 s, 10 s, ...
   between attempts."
  [desc attempts f]
  (loop [attempt 1]
    (let [r (try {:ok (f)} (catch Exception e {:error e}))]
      (if-let [e (:error r)]
        (if (< attempt attempts)
          (do (warn desc "failed (attempt" attempt "of" attempts "):"
                    (first (str/split-lines (.getMessage ^Exception e))) "- retrying")
              (Thread/sleep (* attempt 5000))
              (recur (inc attempt)))
          (throw e))
        (:ok r)))))

(defn- commit-present? [^File dir commit-id]
  (zero? (-> (doto (ProcessBuilder. ["git" "cat-file" "-e" commit-id])
               (.directory dir))
             .start
             .waitFor)))

(defn- valid-bare-clone? [^File dir]
  (.exists (File. dir "HEAD")))

(defn- delete-recursively! [^File f]
  (java.nio.file.Files/walkFileTree
    (.toPath f)
    (proxy [java.nio.file.SimpleFileVisitor] []
      (visitFile [file _attrs]
        (java.nio.file.Files/delete file)
        java.nio.file.FileVisitResult/CONTINUE)
      (postVisitDirectory [dir _e]
        (java.nio.file.Files/delete dir)
        java.nio.file.FileVisitResult/CONTINUE))))

(defn- server-origin [git-url]
  (let [uri (java.net.URI/create git-url)]
    (str (.getScheme uri) "://" (.getAuthority uri))))

(defn- auth-args [token git-url]
  ;; Scope the Authorization header to the CIDER-CI server origin only.
  ;; Using http.<url>.extraheader prevents the token from being sent to
  ;; external hosts (e.g. GitHub submodules).
  (if (and token git-url)
    ["-c" (str "http." (server-origin git-url) "/.extraheader=Authorization: Bearer " token)]
    []))

(defn canonical-url
  "Cache key for a git URL: the same repository referenced with or without
   `.git` / a trailing slash shares one cache."
  [^String url]
  (-> url str/trim (str/replace #"/+$" "") (str/replace #"\.git$" "")))

(defn- ensure-cache!
  "A bare clone of git-url (keyed by canonical URL) containing commit-id; only
   touches the network when the commit is missing. Clone and fetch are retried
   on transient failures."
  [git-url commit-id token]
  (let [cache (File. ^String @cache-root* (sha1-hex (canonical-url git-url)))
        auth  (auth-args token git-url)
        clone! (fn []
                 (.mkdirs cache)
                 (with-retries (str "clone of " git-url) 3
                   #(run! (vec (concat ["git"] auth ["clone" "--bare" git-url (.getAbsolutePath cache)])) nil)))]
    (locking (repo-lock (canonical-url git-url))
      (when-not (valid-bare-clone? cache)
        (when (.exists cache)
          (warn "Cache dir exists but is not a valid git repo; deleting" (.getAbsolutePath cache))
          (delete-recursively! cache))
        (info "Initialising bare clone cache for" git-url)
        (clone!))
      (when-not (commit-present? cache commit-id)
        (info "Fetching" git-url "for commit" (subs commit-id 0 8))
        (try
          (with-retries (str "fetch of " git-url) 3
            #(run! (vec (concat ["git"] auth ["fetch" "--force" "--tags" git-url "+refs/*:refs/*"])) cache))
          (catch Exception fetch-e
            ; Fetch failed — cache may have a HEAD file but corrupt/incomplete objects
            ; from a previous interrupted clone. Delete and reclone from scratch.
            (warn "Fetch failed (" (first (str/split-lines (.getMessage fetch-e)))
                  "); deleting cache and recloning" git-url)
            (delete-recursively! cache)
            (clone!)))
        (when-not (commit-present? cache commit-id)
          (throw (ex-info (str "Commit " commit-id " not found in " git-url " after fetch")
                          {:git-url git-url :commit-id commit-id})))))
    cache))

(defn- clone-from-cache!
  "Checks commit-id of git-url out into dir (a `--shared` clone of the cache)
   and points `origin` to origin-url."
  [git-url commit-id ^File dir token origin-url]
  (let [cache (ensure-cache! git-url commit-id token)]
    (run! ["git" "clone" "--shared" "--no-checkout"
           (.getAbsolutePath cache) (.getAbsolutePath dir)] nil)
    (run! ["git" "checkout" commit-id] dir)
    (run! ["git" "remote" "set-url" "origin" (or (not-empty origin-url) git-url)] dir)))


(defn- gitmodules-entries
  "The submodules declared in dir/.gitmodules: [{:name :path :url}] in file
   order."
  [^File dir]
  (try
    (let [pb   (doto (ProcessBuilder. ["git" "config" "--file" ".gitmodules" "--list"])
                 (.directory dir)
                 (.redirectErrorStream true))
          proc (.start pb)
          out  (slurp (.getInputStream proc))]
      (.waitFor proc)
      (->> (str/split-lines out)
           (keep #(when-let [[_ nm k v] (re-matches #"submodule\.(.+)\.(path|url)=(.*)" %)]
                    [nm (keyword k) v]))
           (reduce (fn [acc [nm k v]]
                     (let [i (or (some (fn [[i e]] (when (= (:name e) nm) i))
                                       (map-indexed vector acc))
                                 (count acc))]
                       (assoc acc i (assoc (get acc i {:name nm}) k v))))
                   [])
           (filter #(and (:path %) (:url %)))))
    (catch Exception _ [])))

(defn- gitlink-commit
  "The commit a gitlink at path points to in the checked out tree, nil when
   the path is not a gitlink (e.g. declared but removed)."
  [^File dir path]
  (let [out (run! ["git" "ls-tree" "HEAD" "--" path] dir)]
    (some->> (str/split-lines out)
             (some #(re-find #"^160000 commit ([0-9a-f]{40})\t" %))
             second)))

(defn resolve-submodule-url
  "git semantics for relative submodule URLs: `../x` and `./x` are resolved
   against the superproject's remote URL (not its path)."
  [parent-url ^String url]
  (if-not (or (str/starts-with? url "./") (str/starts-with? url "../"))
    url
    (loop [base (str/replace (str parent-url) #"/+$" "")
           rel  url]
      (cond
        (str/starts-with? rel "../")
        (recur (if-let [i (str/last-index-of base "/")] (subs base 0 i) base) (subs rel 3))

        (str/starts-with? rel "./")
        (recur base (subs rel 2))

        :else (str base "/" rel)))))

(defn- matches-pattern? [^String path ^String pattern]
  (boolean (re-find (re-pattern pattern) path)))

(defn- include-submodule? [path submodule-opts]
  (let [{:keys [include_match exclude_match]} submodule-opts]
    (and (or (nil? include_match) (matches-pattern? path include_match))
         (or (nil? exclude_match) (not (matches-pattern? path exclude_match))))))

(defn- same-origin? [url-a url-b]
  (try (= (server-origin url-a) (server-origin url-b))
       (catch Exception _ false)))

(defn- init-submodules!
  "Legacy semantics (executor.git.submodules/update): in dir, check out every
   submodule whose path (relative to dir, i.e. its own superproject) matches
   include_match / exclude_match, then recurse into it so nested submodules
   (admin/database, lending/shared-clj, ...) are checked out too.

   Each submodule comes from the executor's per-URL bare-clone cache (one
   clone per repository, shared by all trials and all superprojects that
   include it) instead of `git submodule update`, which cloned every
   submodule from its host for every trial: leihs' integration tests fetch
   ~30 submodules per trial, and 32 parallel trials got throttled by GitHub,
   failing a whole job. The executor token is only sent to the CIDER-CI
   server's own origin."
  [^File dir submodule-opts parent-url token server-git-url]
  (when (.exists (File. dir ".gitmodules"))
    (let [entries (gitmodules-entries dir)
          matched (filter #(include-submodule? (:path %) submodule-opts) entries)]
      (when (seq matched)
        (info "Initialising" (count matched) "of" (count entries) "submodules in" (.getName dir))
        (doseq [{:keys [path url]} matched]
          (let [url     (resolve-submodule-url parent-url url)
                commit  (gitlink-commit dir path)
                sub-dir (File. dir ^String path)]
            (when commit
              (clone-from-cache! url commit sub-dir
                                 (when (same-origin? url server-git-url) token)
                                 url)
              (init-submodules! sub-dir submodule-opts url token server-git-url))))))))


(defn prepare-working-dir!
  "Clones commit-id into work-dir via the local bare-clone cache, fetching
   from git-url (the CIDER-CI server's git proxy; token, when provided, is sent
   as a Bearer token). The working dir's `origin` is then set to origin-url —
   the project's real repository URL (legacy parity) — so trial scripts can run
   `git fetch origin ...` without executor credentials; falls back to git-url.
   git-options may contain {:submodules {:include_match ... :exclude_match ...}}"
  [git-url commit-id ^File work-dir git-options token & [origin-url]]
  (clone-from-cache! git-url commit-id work-dir token origin-url)
  (let [submodule-opts (:submodules git-options)]
    ;; legacy parity: submodules are only checked out when git_options.submodules is given
    (when (map? submodule-opts)
      (init-submodules! work-dir submodule-opts (or (not-empty origin-url) git-url) token git-url))))
