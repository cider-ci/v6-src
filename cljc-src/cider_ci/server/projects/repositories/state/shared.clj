(ns cider-ci.server.projects.repositories.state.shared
  (:require
    [clojure.set :refer [difference]]
    [logbug.debug :as debug]
    ))

(defn- remove-rows [now-rows update-rows]
  (apply dissoc now-rows (difference (-> now-rows keys set) (-> update-rows keys set))))

(defn update-rows-in-db [db-state sub-key default-row rows]
  (assoc db-state sub-key
         (as-> db-state db-rows
           (get db-rows sub-key)
           (remove-rows db-rows rows)
           (map (fn [[k row]] [k (merge (get db-rows k default-row) row)]) rows)
           (sort db-rows)
           (into {} db-rows))))

;#### debug ###################################################################
;(debug/debug-ns *ns*)
