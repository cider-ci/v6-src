(ns cider-ci.server.projects.repositories.fetch-and-update.shared
  (:refer-clojure :exclude [str keyword])
  (:require
   [cider-ci.server.projects.repositories.fetch-and-update.db-schema :as db-schema]
   [cider-ci.server.projects.repositories.state.main :as state]
   [cider-ci.utils.core :refer [keyword str]]
   [taoensso.timbre :as timbre :refer [debug info]]
   [logbug.debug :as debug]
   [schema.core :as schema]
   [tick.core :as tick]))


(defn git-url [repository]
  (:git_url repository))

(defn db-update-fetch-and-update [id fun]
  (state/update-in-repository
   id (fn [repository]
        (let [updated-repo
              (-> repository
                  (update-in [:fetch-and-update] fun)
                  (update-in [:fetch-and-update] #(assoc % :updated_at (tick/now))))]
          (schema/validate db-schema/schema (:fetch-and-update updated-repo))
          updated-repo))))

(defn db-get-fetch-and-update [id]
  (-> (state/get-db) :repositories (get (keyword id)) :fetch-and-update))

(defn fetch-and-update [repository]
  (db-update-fetch-and-update
   (:id repository) #(assoc % :pending? true)))

;### Debug ####################################################################
;(logging-config/set-logger! :level :debug)
;(logging-config/set-logger! :level :info)
;(debug/debug-ns *ns*)
