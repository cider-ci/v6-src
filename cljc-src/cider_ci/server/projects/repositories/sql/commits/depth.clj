; Copyright © 2013 - 2022 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.sql.commits.depth
  (:refer-clojure :exclude [str keyword])
  (:require
    [cider-ci.utils.core :refer [keyword str]]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [logbug.debug :as debug]
    [next.jdbc :as jdbc]
    [next.jdbc.sql :refer [insert! query update!]]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(def ^:private update-root-depths-cmd
  (-> (sql/update :commits)
      (sql/set {:depth 0})
      (sql/where [:not
                  [:exists
                   (-> (sql/select true)
                       (sql/from :commit_arcs)
                       (sql/where [:= :commits.id :commit_arcs.child_id])
                       (sql/where [:= :commits.depth nil]))]])
      (sql-format)))

(defn- update-root-depths [tx]
  (->> update-root-depths-cmd
       (jdbc/execute-one! tx)
       :next.jdbc/update-count))

(def ^:private update-next-non-root-dephts-query
  "UPDATE commits AS children
  SET depth = parents.depth + 1
  FROM commits AS parents,
  commit_arcs
  WHERE children.depth IS NULL
  AND parents.depth IS NOT NULL
  AND children.id = commit_arcs.child_id
  AND parents.id = commit_arcs.parent_id")

(defn- update-next-non-root-dephts [ds]
  (:next.jdbc/update-count
    (jdbc/execute-one! ds [update-next-non-root-dephts-query])))


(defn update-depths
  "Update the commits.depth columns if not null and return
  the number of updated rows."
  [ds]
  (loop [total-count (update-root-depths ds)]
    (let [round-count (update-next-non-root-dephts ds)]
      (if (< 0 round-count)
        (recur (+ total-count round-count))
        total-count))))

;(update-depths (rdbms/get-ds))

;### Debug ####################################################################
;(debug/debug-ns *ns*)
