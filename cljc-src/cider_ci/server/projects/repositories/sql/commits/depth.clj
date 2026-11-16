; Copyright © 2013 - 2022 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.sql.commits.depth
  (:refer-clojure :exclude [str keyword])
  (:require
    [cider-ci.utils.core :refer [keyword str]]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.sql :refer [insert! query update!]]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(def ^:private update-root-depths-cmd
  ;; Only commits without a depth yet: with the IS NULL test inside the
  ;; subquery every import reset all depths to 0 (then only the imported
  ;; commits got a depth, 1..n above the flattened rest).
  (-> (sql/update :commits)
      (sql/set {:depth 0})
      (sql/where [:= :commits.depth nil])
      (sql/where [:not
                  [:exists
                   (-> (sql/select true)
                       (sql/from :commit_arcs)
                       (sql/where [:= :commits.id :commit_arcs.child_id]))]])
      (sql-format)))

(defn- update-root-depths [tx]
  (->> update-root-depths-cmd
       (jdbc/execute-one! tx)
       :next.jdbc/update-count))

;; One round: every commit without a depth whose parents all have one gets
;; 1 + the largest parent depth (git's generation number). Waiting for all
;; parents keeps the value independent of the import order; taking the first
;; parent with a depth, as before, gave a merge commit a different depth
;; depending on which of its branches was imported first.
(def ^:private update-next-non-root-depths-query
  "UPDATE commits AS c
   SET depth = p.max_depth + 1
   FROM (SELECT a.child_id,
                max(parents.depth) AS max_depth,
                bool_and(parents.depth IS NOT NULL) AS complete
         FROM commit_arcs a
         JOIN commits parents ON parents.id = a.parent_id
         JOIN commits children ON children.id = a.child_id
         WHERE children.depth IS NULL
         GROUP BY a.child_id) AS p
   WHERE c.id = p.child_id AND p.complete")

(defn- update-next-non-root-depths [ds]
  (:next.jdbc/update-count
    (jdbc/execute-one! ds [update-next-non-root-depths-query])))


(defn update-depths
  "Sets commits.depth where it is still NULL: 0 for a root (no parent), else
   1 + the largest depth of its parents (git's generation number); for a
   linear history that is the number of commits since the root. Returns the
   number of updated rows. Migration 37 recomputes all depths the same way
   (recompute_commit_depths())."
  [ds]
  (loop [total-count (update-root-depths ds)]
    (let [round-count (update-next-non-root-depths ds)]
      (if (< 0 round-count)
        (recur (+ total-count round-count))
        total-count))))

;(update-depths (rdbms/get-ds))

;### Debug ####################################################################
;(debug/debug-ns *ns*)
