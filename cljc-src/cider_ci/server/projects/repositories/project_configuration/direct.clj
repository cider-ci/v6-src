; Copyright © 2013 - 2026 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.project-configuration.direct
  (:require
    [cider-ci.server.projects.repositories.project-configuration.submodules :as submodules]
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

;; A ctx is {:repo JGitRepository :commit-id "sha"} — the repository/commit the
;; current spec fragment was read from. Included files are expanded in THEIR
;; OWN ctx (a submodule's repository/commit when `submodule:` is given), so
;; their relative includes resolve inside the submodule — legacy semantics of
;; cider-ci.server.repository.project-configuration.expansion.

(defn- format-include-spec
  "string -> {:path s :submodule []}; map -> needs :path, :submodule defaults []."
  [spec]
  (cond
    (string? spec) {:path spec :submodule []}
    (map? spec)    (if-let [p (:path spec)]
                     {:path p :submodule (vec (or (:submodule spec) []))}
                     (throw (ex-info (str "include: cannot determine :path for " (pr-str spec))
                                     {:status 422 :include spec})))
    :else          (throw (ex-info (str "include: must be a string or a map, got " (pr-str spec))
                                   {:status 422 :include spec}))))

(defn- with-resolved-ctx
  "Calls (f ctx') where ctx' is ctx resolved through the submodule chain
   `segments`; opens/closes the submodule repository as needed."
  [ctx segments f]
  (if (empty? segments)
    (f ctx)
    (let [{sub-id :repo-id sub-commit :commit-id} (submodules/resolve-submodule-chain ctx segments)]
      (with-open [^Repository sub-repo (submodules/open-repo sub-id)]
        (f {:repo sub-repo :commit-id sub-commit})))))

(defn- read-included-file
  "Reads and parses an included config file. Throws 422 when missing — legacy
   parity. Silently dropping a missing include (the previous behaviour) yields
   tasks without the scripts they depend on; e.g. leihs' submodule-included
   database.yml vanished and tasks ran without a database."
  [{:keys [repo commit-id]} path]
  (or (read-file repo commit-id path)
      (throw (ex-info (str "include: file `" path "` not found in commit " commit-id
                           " of repository `" (submodules/repo-id repo) "`.")
                      {:status 422 :path path :commit-id commit-id
                       :repository (submodules/repo-id repo)}))))

(defn- get-inclusion [ctx include-spec]
  (let [{:keys [path submodule]} (format-include-spec include-spec)]
    (with-resolved-ctx ctx submodule
      (fn [ctx']
        (let [content (read-included-file ctx' path)]
          (when-not (map? content)
            (throw (ex-info (str "include: only maps can be included; `" path "` is " (type content))
                            {:status 422 :path path})))
          (expand ctx' content))))))

(defn- include-maps [ctx spec]
  (if-let [raw-includes (:include spec)]
    (let [raw-list (if (sequential? raw-includes) raw-includes [raw-includes])
          merged   (->> raw-list
                        (map #(get-inclusion ctx %))
                        (reduce deep-merge {}))]
      (include-maps ctx (deep-merge merged (dissoc spec :include))))
    (->> spec
         (map (fn [[k v]] [k (expand ctx v)]))
         (into {}))))

(defn- read-and-replace [ctx spec]
  (if-let [rar-spec (:read_and_replace_with spec)]
    (let [{:keys [path submodule]} (format-include-spec rar-spec)]
      (with-resolved-ctx ctx submodule
        (fn [{:keys [repo commit-id]}]
          (if-let [raw (read-bytes repo commit-id path)]
            (clojure.string/trim (String. ^bytes raw "UTF-8"))
            (throw (ex-info "read_and_replace_with: file not found"
                            {:path path :commit-id commit-id :status 422}))))))
    spec))

(defn- expand [ctx spec]
  (cond
    (map? spec)        (read-and-replace ctx (include-maps ctx spec))
    (sequential? spec) (mapv #(expand ctx %) spec)
    :else              spec))

(defn build
  "Reads and expands the project configuration from a JGit repository.
  Handles include: / read_and_replace_with: directives, including
  `submodule: [...]` references resolved via .gitmodules + gitlink entries
  (see the submodules ns). Returns a Clojure map, or throws ex-info with
  :status 404 if no config file is found, :status 422 on a bad include."
  [repo commit-id]
  (or (some #(some->> (read-file repo commit-id %)
                      (expand {:repo repo :commit-id commit-id}))
            config-file-alternatives)
      (throw (ex-info "No project configuration found"
                      {:status 404
                       :description (str "None of "
                                         (clojure.string/join ", " config-file-alternatives)
                                         " found in commit " commit-id ".")}))))
