; Copyright © 2013 - 2016 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.state.main
  (:refer-clojure :exclude [str keyword])
  (:require
    [cider-ci.server.projects.repositories.state.db :as db]
    [cider-ci.server.projects.repositories.state.repositories :as state.repositories]
    [cider-ci.utils.core :refer [keyword str]]
    [clj-time.core :as time]
    [logbug.catcher :as catcher :refer [snatch]]
    [logbug.debug :as debug]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(def update-repositories  state.repositories/update-repositories)

(defn update-in-repository [id fun]
  "Applies `fun` within `swap!` to the current state of the
  repository in the database. Updates the value of :modified_at
  afterwards.  "
  (swap! db/db*
         (fn [db id fun]
           (if-not (-> db :repositories (get id) map?)
             (do (warn "To be updated repository " id
                               " is not present in the database.")
                 db)
             (-> db
                 (update-in [:repositories id] fun)
                 (assoc-in [:repositories id :modified_at] (time/now)))))
         (keyword id) fun))

(defn get-db [] @db/db*)

(defn watch-db [k fun]
  (apply add-watch [db/db* k fun]))


;### initialize ###############################################################

(defn initialize []
  (state.repositories/initialize))


;#### debug ###################################################################
;(logging-config/set-logger! :level :debug)
;(logging-config/set-logger! :level :info)
;(debug/debug-ns *ns*)
