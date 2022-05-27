; Copyright © 2013 - 2022 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.core
  (:refer-clojure :exclude [str keyword resolve])
  (:require
    [cider-ci.server.db.core :as db :refer [get-ds]]
    [cider-ci.utils.core :refer [keyword str presence]]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))



; an atom of atoms!
(defonce projects* (atom {}))

(defn- resolve-query [git-id]
  (-> (sql/select [:projects.id :project_id] :commits.committer_date)
      (sql/from :projects)
      (sql/join :branches [:= :branches.project_id :projects.id])
      (sql/join :branches_commits [:= :branches_commits.branch_id :branches.id])
      (sql/join :commits [:= :commits.id :branches_commits.commit_id])
      (sql/where [:or
                  [:= :commits.id git-id]
                  [:= :commits.tree_id git-id]])
      (sql/limit 1)
      (sql/order-by [:commits.committer_date :desc])
      sql-format))

(defn resolve-project [git-id & [{tx :tx
                                  :or {tx @db/ds*}}]]
  "Returns a project given a sha1 commit-id or tree-id."
  (->> git-id
       resolve-query
       (jdbc/execute-one! tx)
       :project_id
       (get @projects*)
       deref))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resolve-commit-id-query [git-id]
  (-> (sql/select [:commits.id :commit_id] :commits.committer_date)
      (sql/from :commits)
      (sql/where [:or
                        [:= :commits.id git-id]
                        [:= :commits.tree_id git-id]])
      (sql/limit 1)
      (sql/order-by [:commits.committer_date :desc])
      sql-format))

(defn resolve-commit-id [git-id & [{tx :tx
                                    :or {tx @db/ds*}}]]
  "Returns a commit-id for a given tree-id or commit-id."
  (-> git-id
      resolve-commit-id-query
      (#(jdbc/execute-one! tx %))
      :commit_id))


