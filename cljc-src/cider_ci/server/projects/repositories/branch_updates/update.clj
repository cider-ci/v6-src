; Copyright © 2013 - 2022 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.branch-updates.update
  (:refer-clojure :exclude [str keyword update])
  (:require
   [cider-ci.server.db.core :refer [get-ds]]
   [cider-ci.server.projects.repositories.branch-updates.shared :refer :all]
   [cider-ci.server.projects.repositories.branches :as branches]
   [cider-ci.server.projects.repositories.git.repositories :as git.repositories]
   [cider-ci.server.jobs.auto-trigger :as auto-trigger]
   [cider-ci.server.projects.repositories.shared :refer [repository-fs-path]]
   [cider-ci.utils.core :refer [deep-merge keyword str]]
   [cider-ci.utils.system :as system]
   [honey.sql :refer [format] :rename {format sql-format}]
   [honey.sql.helpers :as sql]
   [logbug.debug :as debug :refer [I> I>> identity-with-logging]]
   [next.jdbc :as jdbc]
   [clojure.string]
   [tick.core :refer [now]]
   [taoensso.timbre :refer [debug info warn error spy]])
  (:import
   [java.util.concurrent Executors ExecutorService Callable]))


;### delete branches ##########################################################

(defn- branches-delete-query [git-url existing-branches-names]
  (-> (sql/delete-from :branches)
      (sql/using :repositories)
      (sql/where [:= :repositories.id :branches.repository_id])
      (sql/where [:= :repositories.git-url git-url])
      (sql/where [:not-in :branches.name existing-branches-names])
      (sql/returning :branches.name :repositories.id :repositories.git_url)
      sql-format))

(defn- delete-removed-branches [tx keep-git-branches git-url]
  (let [keep-branch-names (map :name keep-git-branches)
        query (branches-delete-query git-url keep-branch-names)
        res (jdbc/execute! tx query)]
    (debug "deleted " res " branches")
    res))


;### branches #################################################################

(defn- get-git-branches [repository-path]
  (I>> identity-with-logging
       (->
       ; I> identity-with-logging
        (system/exec!
         ["git" "branch" "--list" "--no-abbrev" "--no-color" "-v"]
         {:timeout "1 Minutes", :dir repository-path, :env {"TERM" "VT-100"}})
        :out
        (clojure.string/split #"\n"))
       (map (fn [line]
              (let [[_ branch-name current-commit-id]
                    (re-find #"^?\s+(.+)\s+([0-9a-f]{40})\s+(.*)$" line)]
                {:name (clojure.string/trim branch-name)
                 :current_commit_id current-commit-id})))))

(defn- update-branches [repository path]
  (db-update-branch-updates (:id repository) #(assoc % :state "updating"))
  (Thread/sleep 1000)
  (jdbc/with-transaction [tx (get-ds)]
    (let [git-branches (get-git-branches path)
          ;canonic-id (git.repositories/canonic-id repository)
          ]
      {:created (branches/create-new tx git-branches (:id repository) path)
       :updated (branches/update-outdated tx git-branches (:id repository) path)
       :deleted (delete-removed-branches tx git-branches (:git_url repository))})))


;### update branches in db ####################################################


(defn repo-branches-query [repo-id]
  (-> (sql/select [:%count.* :branches_count]
                  [[:max :commits.committer_date] :last_commited_at])
      (sql/from :repositories)
      (sql/where [:= :repositories.id repo-id])
      (sql/join :branches [:= :repositories.id :branches.repository_id])
      (sql/join :commits [:= :branches.current_commit_id :commits.id])
      (sql/group-by :repositories.id)))

(comment (-> "test"
             repo-branches-query (sql-format :inline true)
             (->> (jdbc/execute-one! (get-ds)))
             :last_commited_at type))


(defn update [repository]
  (debug 'update repository)
  (let [repo-id     (:id repository)
        path        (repository-fs-path repository)
        update-info (update-branches repository path)
        params      (-> repo-id repo-branches-query sql-format
                        (->> (jdbc/execute-one! (get-ds))))]
    (when params
      (db-update-branch-updates
       repo-id #(deep-merge % params
                            {:update_info update-info
                             :branches_updated_at (now)
                             :state "ok"})))
    ;; Fire-and-forget: trigger jobs for every branch whose HEAD changed
    (let [changed (concat (:created update-info) (:updated update-info))]
      (when (seq changed)
        (future
          (doseq [branch changed]
            (when-let [commit-id (:current_commit_id branch)]
              (auto-trigger/trigger-for-commit! (get-ds) repo-id commit-id))))))))


;### Debug ####################################################################
;(logging-config/set-logger! :level :debug)
;(logging-config/set-logger! :level :info)
;(debug/debug-ns *ns*)
