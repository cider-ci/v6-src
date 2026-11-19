(ns scripts.build
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/cider-ci.jar")

(defn- git [& args]
  (let [{:keys [exit out]} (apply shell/sh "git" args)]
    (when (zero? exit) (str/trim out))))

(defn- dirty-tree-id
  "The tree of HEAD plus the modifications of tracked files (a temporary
   index; untracked files are ignored, as in `git status -uno`)."
  []
  (let [index (str (System/getProperty "java.io.tmpdir") "/cider-ci-build-index-" (System/nanoTime))
        env   (assoc (into {} (System/getenv)) "GIT_INDEX_FILE" index)
        sh    (fn [& args] (let [{:keys [exit out]} (apply shell/sh (concat args [:env env]))]
                             (when (zero? exit) (str/trim out))))]
    (try
      (sh "git" "read-tree" "HEAD")
      (sh "git" "add" "-u")
      (sh "git" "write-tree")
      (finally (io/delete-file index true)))))

(defn build-info
  "What is being built: the commit, and whether tracked files differ from it
   (then the tree hash of what is actually built). Shown in the footer."
  []
  (let [dirty? (not (str/blank? (git "status" "--porcelain" "--untracked-files=no")))]
    {:commit_id  (git "rev-parse" "HEAD")
     :tree_id    (if dirty? (dirty-tree-id) (git "rev-parse" "HEAD^{tree}"))
     :dirty      dirty?
     :origin_url (git "remote" "get-url" "origin")
     :built_at   (str (java.time.Instant/now))}))

(defn- basis []
  (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [opts]
  (clean opts)
  (println "Copying resources...")
  (b/copy-dir {:src-dirs ["resources"]
               :target-dir class-dir})
  (let [info (build-info)]
    (println "Build info:" (pr-str info))
    (spit (str class-dir "/built-info.edn") (pr-str info)))
  (println "Compiling Clojure...")
  (b/compile-clj {:basis      (basis)
                  :src-dirs   ["cljc-src"]
                  :class-dir  class-dir
                  :ns-compile '[cider-ci.main]})
  (println "Building uberjar...")
  (b/uber {:class-dir        class-dir
           :uber-file        uber-file
           :basis            (basis)
           :main             'cider-ci.main
           :conflict-handlers {"^data_readers.clj[cs]?$" :overwrite}})
  (println (str "Built " uber-file)))
