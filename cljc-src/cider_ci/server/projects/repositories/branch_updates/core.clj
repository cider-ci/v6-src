; Copyright © 2013 - 2016 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.branch-updates.core
  (:refer-clojure :exclude [str keyword update])
  (:require
    [cider-ci.server.db.settings :refer [get-setting!]]
    [cider-ci.server.projects.repositories.branch-updates.shared :as shared :refer [db-get-branch-updates db-update-branch-updates]]
    [cider-ci.server.projects.repositories.branch-updates.update :as update]
    [cider-ci.server.projects.repositories.shared :refer :all]
    [cider-ci.server.projects.repositories.state.main :as state]
    [logbug.debug :as debug]
    [logbug.catcher :as catcher]
    [cider-ci.utils.core :refer [keyword str]]
    [cider-ci.utils.daemon :refer [defdaemon]]
    [taoensso.timbre :refer [debug info warn error]]
    [tick.core :refer [now]])
  (:import
    [java.util.concurrent Executors ExecutorService Callable]))

(defonce branch-updates-pool (atom nil))

(defn- ready-to-be-submited? [repository]
  (contains?
    #{"error" "ok" "initializing"}
    (-> repository :id db-get-branch-updates :state)))

(defn catch-branch-updates-exception [e repository]
  (db-update-branch-updates
    (:id repository)
    #(assoc %
       :last_error_at (now)
       :last_error (str e)
       :state "error")))

(defn- execute-update-branches [repository]
  (let [id (:id repository)]
    (locking (str "fetch-and-update-lock_" id)
      (catcher/snatch
        {:return-fn #(catch-branch-updates-exception % repository)}
        (update/update repository))
      (comment (try
                 (update/update repository)
                 (catch Exception e
                   (warn e)
                   (catch-branch-updates-exception e repository)))))))

(defn- submit-pending-repositories []
  (doseq [repository (map second (:repositories (state/get-db)))]
    (when (and (ready-to-be-submited? repository)
               (-> repository :branch-updates :pending?))
      (db-update-branch-updates (:id repository) #(assoc % :pending? false))
      (let [do-branch-updates (fn [] (execute-update-branches repository))]
        (db-update-branch-updates (:id repository) #(assoc % :state "waiting"))
        (.submit @branch-updates-pool (cast Callable do-branch-updates))))))

;(defdaemon "submit-pending-repositories" 1 (submit-pending-repositories))

(defn start-submit-pending-repositories []
  (state/watch-db
    :submit-to-update
    (fn [_ _ old-state new-state]
      (when (not= old-state new-state)
        (submit-pending-repositories)))))

;##############################################################################

(defn- initialize-branch-updates-pool []
  (let [branch-updates-pool-size (or (get-setting! :git_fetch_and_update_max_concurrent)
                                     (.availableProcessors (Runtime/getRuntime)))]
    (reset! branch-updates-pool
            (Executors/newFixedThreadPool branch-updates-pool-size))))

;##############################################################################

(defn update [repository]
  (db-update-branch-updates
    (:id repository) #(assoc % :pending? true)))

(defn init [opts]
  (initialize-branch-updates-pool)
  (start-submit-pending-repositories)
  (submit-pending-repositories))


;### Debug ####################################################################
;(debug/debug-ns *ns*)

