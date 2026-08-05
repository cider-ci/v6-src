(ns cider-ci.executor.git
  (:require
    [clojure.string :as str]
    [taoensso.timbre :refer [info warn]])
  (:import
    [java.io File]
    [java.math BigInteger]
    [java.security MessageDigest]))


(def ^:private cache-root "/var/tmp/cider-ci-git-cache")

;; One lock object per remote URL — serialises bare-clone updates.
(defonce ^:private repo-locks* (atom {}))


(defn- sha1-hex [^String s]
  (format "%040x"
    (BigInteger. 1
      (.digest (MessageDigest/getInstance "SHA-1")
               (.getBytes s "UTF-8")))))

(defn- repo-lock [git-url]
  (get (swap! repo-locks* update git-url #(or % (Object.))) git-url))

(defn- run! [cmd ^File dir]
  (let [pb (ProcessBuilder. ^java.util.List (vec cmd))]
    (when dir (.directory pb dir))
    (doto (.environment pb)
      (.put "GIT_TERMINAL_PROMPT" "0"))
    (let [proc (.start pb)
          exit (.waitFor proc)]
      (when-not (zero? exit)
        (throw (ex-info (str "git command failed: " (str/join " " cmd))
                        {:cmd cmd :exit exit}))))))

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

(defn- ensure-cache! [git-url commit-id token]
  (let [cache (File. cache-root (sha1-hex git-url))
        auth  (auth-args token git-url)]
    (locking (repo-lock git-url)
      (when-not (valid-bare-clone? cache)
        (when (.exists cache)
          (warn "Cache dir exists but is not a valid git repo; deleting" (.getAbsolutePath cache))
          (delete-recursively! cache))
        (.mkdirs cache)
        (info "Initialising bare clone cache for" git-url)
        (run! (vec (concat ["git"] auth ["clone" "--bare" git-url (.getAbsolutePath cache)])) nil))
      (when-not (commit-present? cache commit-id)
        (info "Fetching" git-url "for commit" (subs commit-id 0 8))
        (run! (vec (concat ["git"] auth ["fetch" "--force" "--tags" git-url "+refs/*:refs/*"])) cache)
        (when-not (commit-present? cache commit-id)
          (throw (ex-info "Commit not found in repository after fetch"
                          {:git-url git-url :commit-id commit-id})))))
    cache))


(defn- submodule-paths [^File work-dir]
  (try
    (let [pb   (doto (ProcessBuilder. ["git" "config" "--file" ".gitmodules"
                                       "--get-regexp" "submodule\\..*\\.path"])
                 (.directory work-dir)
                 (.redirectErrorStream true))
          proc (.start pb)
          out  (slurp (.getInputStream proc))]
      (.waitFor proc)
      (->> (str/split-lines out)
           (remove str/blank?)
           (map #(second (str/split % #"\s+" 2)))))
    (catch Exception _ [])))

(defn- matches-pattern? [^String path ^String pattern]
  (boolean (re-find (re-pattern pattern) path)))

(defn- include-submodule? [path submodule-opts]
  (let [{:keys [include_match exclude_match]} submodule-opts]
    (and (or (nil? include_match) (matches-pattern? path include_match))
         (or (nil? exclude_match) (not (matches-pattern? path exclude_match))))))

(defn- init-submodules! [^File work-dir submodule-opts token git-url]
  (when (.exists (File. work-dir ".gitmodules"))
    (let [auth (auth-args token git-url)]
      (if (or (:include_match submodule-opts) (:exclude_match submodule-opts))
        (let [paths   (submodule-paths work-dir)
              matched (filter #(include-submodule? % submodule-opts) paths)]
          (when (seq matched)
            (info "Initialising" (count matched) "of" (count paths) "submodules (filtered)")
            (doseq [path matched]
              (run! (vec (concat ["git"] auth ["submodule" "update" "--init" path])) work-dir))))
        (do
          (info "Initialising submodules")
          (run! (vec (concat ["git"] auth ["submodule" "update" "--init" "--recursive"])) work-dir))))))


(defn prepare-working-dir!
  "Clones commit-id into work-dir via the local bare-clone cache.
   git-options may contain {:submodules {:include_match ... :exclude_match ...}}
   token, when provided, is sent as a Bearer token on HTTP git operations."
  [git-url commit-id ^File work-dir git-options token]
  (let [cache (ensure-cache! git-url commit-id token)]
    (run! ["git" "clone" "--shared" "--no-checkout"
           (.getAbsolutePath cache) (.getAbsolutePath work-dir)] nil)
    (run! ["git" "checkout" commit-id] work-dir)
    (run! ["git" "remote" "set-url" "origin" git-url] work-dir)
    (init-submodules! work-dir (:submodules git-options) token git-url)))
