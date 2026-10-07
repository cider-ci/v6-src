(ns cider-ci.executor.git-test
  (:require [clojure.test :refer [deftest is testing]]
            [cider-ci.executor.git :as git]))

(def ^:private with-git-defaults @#'cider-ci.executor.git/with-git-defaults)

(deftest with-git-defaults-test
  (testing "git commands get auto-gc/maintenance disabled right after the binary"
    (let [cmd (with-git-defaults ["git" "submodule" "update" "--init" "db"])]
      (is (= "git" (first cmd)))
      (is (= ["-c" "gc.auto=0" "-c" "gc.autoDetach=false" "-c" "maintenance.auto=false"]
             (subvec cmd 1 7)))
      (is (= ["submodule" "update" "--init" "db"] (subvec cmd 7)))))
  (testing "existing -c options are kept"
    (is (= ["git" "-c" "gc.auto=0" "-c" "gc.autoDetach=false" "-c" "maintenance.auto=false"
            "-c" "http.x/.extraheader=Authorization: Bearer t" "fetch"]
           (with-git-defaults ["git" "-c" "http.x/.extraheader=Authorization: Bearer t" "fetch"]))))
  (testing "non-git commands are untouched"
    (is (= ["ls" "-la"] (with-git-defaults ["ls" "-la"])))))


(deftest redact-test
  (is (= "git -c http.https://ci/.extraheader=Authorization: Bearer <redacted> fetch"
         (git/redact "git -c http.https://ci/.extraheader=Authorization: Bearer 7071b9de-d7da fetch")))
  (is (= "" (git/redact nil))))

(deftest canonical-url-test
  (is (= (git/canonical-url "https://github.com/leihs/leihs_database")
         (git/canonical-url "https://github.com/leihs/leihs_database.git")
         (git/canonical-url "https://github.com/leihs/leihs_database.git/"))))

(deftest resolve-submodule-url-test
  (testing "absolute URLs are kept"
    (is (= "https://github.com/leihs/leihs_database.git"
           (git/resolve-submodule-url "https://github.com/leihs/leihs.git" "https://github.com/leihs/leihs_database.git"))))
  (testing "../ resolves against the superproject's remote URL"
    (is (= "https://github.com/leihs/leihs_database.git"
           (git/resolve-submodule-url "https://github.com/leihs/leihs.git" "../leihs_database.git")))
    (is (= "https://github.com/other/x.git"
           (git/resolve-submodule-url "https://github.com/leihs/leihs.git/" "../../other/x.git")))
    (is (= "git@github.com:leihs/leihs_database.git"
           (git/resolve-submodule-url "git@github.com:leihs/leihs.git" "../leihs_database.git"))))
  (testing "./ is relative to the superproject URL itself (git semantics)"
    (is (= "https://host/org/super.git/sub.git"
           (git/resolve-submodule-url "https://host/org/super.git" "./sub.git")))))

(deftest run-error-includes-output-test
  (let [e (try (#'git/run! ["git" "no-such-git-command"] nil) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (re-find #"git command failed \(exit \d+\)" (.getMessage e)))
    (is (re-find #"no-such-git-command" (.getMessage e)))
    (is (re-find #"(?i)not a git command" (.getMessage e)))))


;;; nested submodules from the per-URL cache ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sh! [dir & args]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec args)) (.redirectErrorStream true))]
    (when dir (.directory pb (java.io.File. ^String dir)))
    (let [p (.start pb) out (slurp (.getInputStream p))]
      (when-not (zero? (.waitFor p))
        (throw (ex-info (str "command failed: " (pr-str args) "\n" out) {})))
      out)))

(defn- git! [dir & args]
  (apply sh! dir "git" "-c" "user.name=t" "-c" "user.email=t@example.com" "-c" "commit.gpgsign=false"
         "-c" "protocol.file.allow=always" args))

(defn- make-bare-repo!
  "A bare repo at <root>/<name>.git with one commit containing <name>.txt and
   the given .gitmodules + gitlinks ([[path url sha] ...]); returns its HEAD."
  [root name gitlinks]
  (let [work (str root "/" name "-work")]
    (.mkdirs (java.io.File. work))
    (git! work "init" "-q" "-b" "master")
    (spit (str work "/" name ".txt") name)
    (git! work "add" ".")
    (when (seq gitlinks)
      (spit (str work "/.gitmodules")
            (apply str (for [[path url _] gitlinks]
                         (str "[submodule \"" path "\"]\n\tpath = " path "\n\turl = " url "\n"))))
      (git! work "add" ".gitmodules")
      (doseq [[path _ sha] gitlinks]
        (git! work "update-index" "--add" "--cacheinfo" (str "160000," sha "," path))))
    (git! work "commit" "-q" "-m" name)
    (git! nil "clone" "-q" "--bare" work (str root "/" name ".git"))
    (clojure.string/trim (git! work "rev-parse" "HEAD"))))

(deftest nested-submodules-from-cache-test
  (let [root   (str (java.nio.file.Files/createTempDirectory "git-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        cache  (str root "/cache")
        _      (git/set-cache-root! cache)
        subsub (make-bare-repo! root "subsub" [])
        ;; relative URL: resolved against the superproject's remote URL
        sub    (make-bare-repo! root "sub" [["subsub" "../subsub.git" subsub]])
        super  (make-bare-repo! root "super" [["sub" (str root "/sub.git") sub]
                                              ["excluded" "../subsub.git" subsub]])
        work   (java.io.File. (str root "/work"))]
    (testing "submodules and nested submodules are checked out, filters apply"
      (git/prepare-working-dir! (str root "/super.git") super work
                                {:submodules {:exclude_match "^excluded$"}} nil nil)
      (is (.exists (java.io.File. work "super.txt")))
      (is (.exists (java.io.File. work "sub/sub.txt")))
      (is (.exists (java.io.File. work "sub/subsub/subsub.txt")))
      (is (not (.exists (java.io.File. work "excluded/subsub.txt"))) "excluded by exclude_match")
      (is (= (str root "/subsub.git")
             (clojure.string/trim (git! (str work "/sub/subsub") "remote" "get-url" "origin")))
          "origin of a nested submodule is its resolved real URL"))
    (testing "every repository has exactly one cache entry, keyed by canonical URL"
      (is (= 3 (count (.listFiles (java.io.File. cache))))))
    (testing "without git_options.submodules nothing is checked out (legacy parity)"
      (let [work2 (java.io.File. (str root "/work2"))]
        (git/prepare-working-dir! (str root "/super.git") super work2 {} nil nil)
        (is (.exists (java.io.File. work2 "super.txt")))
        (is (not (.exists (java.io.File. work2 "sub/sub.txt"))))))))


(deftest submodule-via-git-proxy-test
  (let [root  (str (java.nio.file.Files/createTempDirectory "git-proxy-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        _     (git/set-cache-root! (str root "/cache"))
        sub   (make-bare-repo! root "sub" [])
        ;; the declared URL does not exist (private / unpublished submodule)
        super (make-bare-repo! root "super" [["sub" (str root "/does-not-exist.git") sub]])
        work  (java.io.File. (str root "/work"))]
    (testing "without a proxy the checkout fails with git's output in the error"
      (let [e (try (git/prepare-working-dir! (str root "/super.git") super work
                                             {:submodules {:include_match "^.*$"}} nil nil)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (re-find #"does-not-exist" (.getMessage e)))))
    (testing "git_proxies maps the submodule commit to a reachable URL; origin stays the declared URL"
      (let [work2 (java.io.File. (str root "/work2"))]
        (git/prepare-working-dir! (str root "/super.git") super work2
                                  {:submodules {:include_match "^.*$"}} nil nil
                                  {(keyword sub) (str root "/sub.git")})
        (is (.exists (java.io.File. work2 "sub/sub.txt")))
        (is (= (str root "/does-not-exist.git")
               (clojure.string/trim (git! (str work2 "/sub") "remote" "get-url" "origin"))))))))
