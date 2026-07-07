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

(defn- ensure-cache! [git-url commit-id]
  (let [cache (File. cache-root (sha1-hex git-url))]
    (locking (repo-lock git-url)
      (when-not (.exists cache)
        (.mkdirs cache)
        (info "Initialising bare clone cache for" git-url)
        (run! ["git" "clone" "--bare" git-url (.getAbsolutePath cache)] nil))
      (when-not (commit-present? cache commit-id)
        (info "Fetching" git-url "for commit" (subs commit-id 0 8))
        (run! ["git" "fetch" "--force" "--tags" git-url "+refs/*:refs/*"] cache)
        (when-not (commit-present? cache commit-id)
          (throw (ex-info "Commit not found in repository after fetch"
                          {:git-url git-url :commit-id commit-id})))))
    cache))


(defn prepare-working-dir!
  "Clones commit-id into work-dir via the local bare-clone cache.
   Initialises submodules if .gitmodules is present."
  [git-url commit-id ^File work-dir]
  (let [cache (ensure-cache! git-url commit-id)]
    (run! ["git" "clone" "--shared" "--no-checkout"
           (.getAbsolutePath cache) (.getAbsolutePath work-dir)] nil)
    (run! ["git" "checkout" commit-id] work-dir)
    (run! ["git" "remote" "set-url" "origin" git-url] work-dir)
    (when (.exists (File. work-dir ".gitmodules"))
      (info "Initialising submodules")
      (run! ["git" "submodule" "update" "--init" "--recursive"] work-dir))))
