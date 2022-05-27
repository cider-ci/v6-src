(ns cider-ci.server.db.utils
  (:require
    [next.jdbc :as jdbc]
    [next.jdbc.sql :refer [update! insert! query]]
    ))

(defn insert-or-update [tx table where-clause values]
  (let [[clause & params] where-clause]
    (if (first (query
                 tx
                 (concat [(str "SELECT 1 FROM " table " WHERE " clause)]
                         params)))
      (update! tx table values where-clause)
      (insert! tx table values))))
