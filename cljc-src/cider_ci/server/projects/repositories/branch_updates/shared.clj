; Copyright © 2013 - 2016 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.branch-updates.shared
  (:refer-clojure :exclude [str keyword])
  (:require [cider-ci.utils.core :refer [keyword str]])
  (:require
    [cider-ci.server.projects.repositories.branch-updates.db-schema :as db-schema]
    [cider-ci.server.projects.repositories.state.main :as state]
    [logbug.debug :as debug]
    [tick.core :refer [now]]
    [schema.core :as schema]))


(defn db-update-branch-updates [id fun]
  (state/update-in-repository
    id (fn [repository]
         (let [updated-repo
               (-> repository
                   (update-in [:branch-updates] fun)
                   (update-in [:branch-updates] #(assoc % :updated_at (now))))]
           (schema/validate db-schema/schema (:branch-updates updated-repo))
           updated-repo))))

(defn db-get-branch-updates [id]
  (-> (state/get-db) :repositories (get (keyword id)) :branch-updates))

;### Debug ####################################################################
(debug/debug-ns *ns*)
