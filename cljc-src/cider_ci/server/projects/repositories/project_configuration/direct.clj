; Copyright © 2013 - 2026 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.project-configuration.direct
  (:require
    [cider-ci.utils.core :refer [deep-merge]]
    [clj-yaml.core :as yaml]
    [clojure.data.json :as json])
  (:import
    [org.eclipse.jgit.lib Repository]
    [org.eclipse.jgit.revwalk RevWalk]
    [org.eclipse.jgit.treewalk TreeWalk]
    [org.eclipse.jgit.treewalk.filter PathFilter]))

(def ^:private config-file-alternatives
  ["cider-ci.yml" ".cider-ci.yml"
   "cider-ci.json" ".cider-ci.json"
   "cider-ci_v4.yml" ".cider-ci_v4.yml"])

(defn- parse-bytes [path ^bytes raw]
  (let [content (String. raw "UTF-8")
        lpath   (clojure.string/lower-case path)]
    (cond
      (re-matches #".*(yml|yaml)" lpath) (yaml/parse-string content :keywords true)
      (re-matches #".*json" lpath)       (json/read-str content :key-fn keyword)
      :else (throw (ex-info "Unsupported config format" {:path path :status 422})))))

(defn- read-bytes [^Repository repo commit-id path]
  (let [object-id (.resolve repo (str commit-id "^{commit}"))
        revwalk   (RevWalk. repo)
        revcmt    (.parseCommit revwalk object-id)
        revtree   (.getTree revcmt)
        tw        (TreeWalk. repo)]
    (try
      (.setRecursive tw true)
      (.addTree tw revtree)
      (.setFilter tw (PathFilter/create path))
      (when (.next tw)
        (.getBytes (.open repo (.getObjectId tw 0))))
      (finally (.close tw)))))

(defn- read-file [repo commit-id path]
  (when-let [raw (read-bytes repo commit-id path)]
    (parse-bytes path raw)))

(declare expand)

(defn- include-spec->path [spec]
  (cond
    (string? spec) spec
    (map? spec)    (:path spec)
    :else          nil))

(defn- include-maps [repo commit-id spec]
  (if-let [raw-includes (:include spec)]
    (let [raw-list (if (sequential? raw-includes) raw-includes [raw-includes])
          paths    (->> raw-list (map include-spec->path) (filter some?))
          merged   (->> paths
                        (keep #(read-file repo commit-id %))
                        (map #(expand repo commit-id %))
                        (reduce deep-merge {}))]
      (include-maps repo commit-id (deep-merge merged (dissoc spec :include))))
    (->> spec
         (map (fn [[k v]] [k (expand repo commit-id v)]))
         (into {}))))

(defn- expand [repo commit-id spec]
  (cond
    (map? spec)        (include-maps repo commit-id spec)
    (sequential? spec) (mapv #(expand repo commit-id %) spec)
    :else              spec))

(defn build
  "Reads and expands the project configuration from a JGit repository.
  Handles include: directives within the same commit. Does not support
  submodule includes (Phase 2 scope). Returns a Clojure map, or throws
  ex-info with :status 404 if no config file is found."
  [repo commit-id]
  (or (some #(some->> (read-file repo commit-id %)
                      (expand repo commit-id))
            config-file-alternatives)
      (throw (ex-info "No project configuration found"
                      {:status 404
                       :description (str "None of "
                                         (clojure.string/join ", " config-file-alternatives)
                                         " found in commit " commit-id ".")}))))
