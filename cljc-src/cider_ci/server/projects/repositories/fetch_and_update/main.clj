; Copyright © 2013 - 2022 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.fetch-and-update.main
  (:refer-clojure :exclude [str keyword])
  (:require
    [cider-ci.server.db.settings :refer [get-setting!]]
    [cider-ci.server.projects.repositories.branch-updates.core :as branch-updates]
    [cider-ci.server.projects.repositories.fetch-and-update.fetch :as fetch]
    [cider-ci.server.projects.repositories.fetch-and-update.scheduler :as scheduler]
    [cider-ci.server.projects.repositories.fetch-and-update.shared :as shared :refer [db-get-fetch-and-update db-update-fetch-and-update]]
    [cider-ci.server.projects.repositories.state.main :as state]
    [cider-ci.utils.core :refer [keyword str]]
    [cider-ci.utils.daemon :refer [defdaemon]]
    [logbug.catcher :as catcher]
    [logbug.debug :as debug]
    [taoensso.timbre :refer [debug info warn error]]
    [tick.core :refer [now]])
  (:import
    [java.util.concurrent Executors ExecutorService Callable]))

(defonce fetch-and-update-pool (atom nil))

(defn- ready-to-be-submited? [repository]
  (contains?
    #{nil "error" "ok" "initializing"}
    (-> repository :id db-get-fetch-and-update :state)))

(defn catch-fetch-and-update-exception [e repository]
  (db-update-fetch-and-update
    (:id repository)
    #(assoc %
       :last_error_at (now)
       :last_error (str e)
       :state "error")))

(defn execute-fetch-and-update [repository]
  (let [id (:id repository)]
    (locking (str "fetch-and-update-lock_" id)
      (catcher/snatch
        {:return-fn (fn [e] (catch-fetch-and-update-exception e repository))}
        (fetch/fetch repository)
        (branch-updates/update repository)))))

(defn- submit-pending-repositories []
  (doseq [repository (map second (:repositories (state/get-db)))]
    (let [id (:id repository)]
      (when (and (ready-to-be-submited? repository)
                 (-> repository :fetch-and-update :pending?))
        (db-update-fetch-and-update id #(assoc % :pending? false
                                               :state "waiting"))
        (let [do-fetch-and-update (fn [] (execute-fetch-and-update repository))]
          (.submit @fetch-and-update-pool (cast Callable do-fetch-and-update)))))))

;(defdaemon "submit-pending-repositories" 1 (submit-pending-repositories))

(defn start-submit-pending-repositories []
  (state/watch-db
    :submit-to-fetch
    (fn [_ _ old-state new-state]
      (when (not= old-state new-state)
        (submit-pending-repositories)))))

;##############################################################################

(defn- initialize-fetch-and-update-pool []
  (let [fetch-and-update-pool-size (or (get-setting! :git_fetch_and_update_max_concurrent)
                                       (.availableProcessors (Runtime/getRuntime)))]
    (reset! fetch-and-update-pool
            (Executors/newFixedThreadPool fetch-and-update-pool-size))))

;##############################################################################

(def fetch-and-update shared/fetch-and-update)

(defn init [options]
  (info "INIT fetch-and-update >>> ")
  (initialize-fetch-and-update-pool)
  ;TODO
  (start-submit-pending-repositories)
  (submit-pending-repositories)
  (scheduler/initialize)
  (info "INIT fetch-and-update <<< "))


(comment (init {}))


;### Debug ####################################################################


(defn- first-repo []
  (some-> (state/get-db)
          :repositories
          (->> (map second)
               first)))
(comment
  (first-repo)
  (fetch/fetch (first-repo))
  ; TODO make this work
  (future (branch-updates/update (first-repo))))

;(logging-config/set-logger! :level :debug)
;(logging-config/set-logger! :level :info)
;(debug/debug-ns *ns*)
