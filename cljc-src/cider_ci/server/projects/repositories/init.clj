; Copyright © 2013 - 2018 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.init
  (:refer-clojure :exclude [str keyword])
  (:require
    [cider-ci.server.projects.repositories.http-backend :as http-backend]
    [cider-ci.server.projects.repositories.shared :as shared]
    [cider-ci.utils.core :refer [keyword str presence]]
    [cider-ci.utils.nio :as nio]
    [cider-ci.utils.system :as system]
    [taoensso.timbre :refer [debug info warn error spy]])
    )


; TODO none of this seems to be used anymore


(def path shared/path)

(def file-repository shared/file-repository)

(defn init [project]
  (let [path (path project)]
    (when-not (nio/dir? path)
      (system/exec! ["git" "init" "--bare" (.toString path)]))
    (let [repository (file-repository path)]
      (assert (.getRef repository "HEAD"))
      repository)))

(defn de-init [project]
  (let [path (path project)]
    (when (nio/dir? path)
      (system/exec! ["rm" "-rf"  (.toString path)])
      ;(nio/rmdir-recursive path)
      )))

(def http-handler http-backend/http-handler)

