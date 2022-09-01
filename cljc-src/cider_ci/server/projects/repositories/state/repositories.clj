; Copyright © 2013 - 2016 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.state.repositories
  (:refer-clojure :exclude [str keyword])
  (:require
    ;[cider-ci.server.repository.branch-updates.db-schema :as branch-updates.db-schema]
    [cider-ci.server.projects.repositories.fetch-and-update.db-schema :as fetch-and-update.db-schema]
    ;[cider-ci.server.repository.push-hooks.db-schema :as push-hooks.db-schema]
    ;[cider-ci.server.repository.push-notifications.db-schema :as push-notifications.db-schema]
    ;[cider-ci.server.repository.status-pushes.db-schema :as status-pushes.db-schema]
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.server.db.row-events :as row-events]
    [cider-ci.server.projects.repositories.state.db :as db]
    [cider-ci.server.projects.repositories.state.shared :refer [update-rows-in-db]]
    [cider-ci.utils.core :refer [keyword str]]
    [cider-ci.utils.daemon :as daemon :refer [defdaemon]]
    [logbug.debug :as debug]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]))



(defn update-repositories []
  (->> ["SELECT * from repositories"]
       (jdbc/execute! (get-ds))
       (map (fn [repo] [(-> repo :id keyword) repo]))
       spy
       (into {})
       spy
       (swap! db/db* update-rows-in-db :repositories
              {
               ; TODO
               ;:branch-updates (branch-updates.db-schema/default)
               :fetch-and-update (fetch-and-update.db-schema/default)
               ;:push-notification (push-notifications.db-schema/default)
               ;:push-hook (push-hooks.db-schema/default)
               ;:status-pushes (status-pushes.db-schema/default)
               })))

(def ^:private last-processed-repository-event (atom nil))

(defdaemon "update-repositories" 1
  (row-events/process "repository_events" last-processed-repository-event
                      (fn [_] (update-repositories))))

(defn initialize []
  (update-repositories)
  (start-update-repositories))

;#### debug ###################################################################
;(logging-config/set-logger! :level :debug)
;(logging-config/set-logger! :level :info)
(debug/debug-ns *ns*)
