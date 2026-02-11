; Copyright © 2013 - 2018 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.shared
  (:refer-clojure :exclude [str keyword])
  (:require
   [cider-ci.utils.core :refer [keyword str presence]]
   [cider-ci.utils.nio :as nio]
   [clojure.string :as string :refer [blank? split trim]]
   [taoensso.timbre :refer [debug info warn error spy]])
  (:import
   [org.eclipse.jgit.storage.file FileRepositoryBuilder]
   [java.nio.file Files FileSystems]
   [java.io File]))


; TODO cli arg and init
(def ^:dynamic repositories-dir-path
  (nio/path
   (System/getProperty "user.dir")
   "data"
   "repositories"))

(defn path [project-params]
  (let [project-id (or (:project-id project-params)
                       (:id project-params))]
    (assert (presence project-id))
    (nio/path repositories-dir-path project-id)))

(defn file-repository [path]
  (.build (doto (new FileRepositoryBuilder)
            (.setGitDir (.toFile path))
            (.setBare))))

(defn repository-fs-path [repository-or-id]
  (let [id (if (map? repository-or-id)
             (str (:id repository-or-id))
             repository-or-id)]
    (assert (not (blank? id)))
    (str repositories-dir-path File/separator id)))

