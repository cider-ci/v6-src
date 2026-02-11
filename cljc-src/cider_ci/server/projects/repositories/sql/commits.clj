; Copyright © 2013 - 2016 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.sql.commits
  (:refer-clojure :exclude [str keyword find])
  (:require
   [cider-ci.server.projects.repositories.sql.commits.depth :as depth]
   [cider-ci.utils.core :refer [keyword str]]
   [next.jdbc.sql :refer [insert! query]]))

(defn create! [ds params]
  (insert! ds :commits params))

(defn update! [ds params where-clause]
  (next.jdbc.sql/update! ds :commits params where-clause))

(defn find [ds id]
  (first (query ds ["SELECT * FROM commits WHERE id = ?", id])))

(defn find! [ds id]
  (or
   (find ds id)
   (throw (IllegalStateException. (str "Could not find repository with id = " id)))))

(defn update-depths [ds]
  (depth/update-depths ds))
