(ns cider-ci.server.db.row-events
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.utils.uuid :refer [uuid]]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [logbug.catcher :as catcher :refer [snatch]]
    [logbug.debug :as debug :refer [I> I>> identity-with-logging]]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]
    [tick.core :as tick]
    ))


(defn last-processed-row-query [table-name]
  (-> (sql/from (keyword table-name))
      (sql/select :*)
      (sql/order-by [:created_at :desc]
                    [:id])
      (sql/limit 1)))

(comment (-> (last-processed-row-query "users")
             (sql-format)))

(defn process-last-row [table-name state-atom row-handler]
  (swap! state-atom
         (fn [_]
           (when-let [last-row (-> table-name last-processed-row-query
                                   (sql-format)
                                   (#(jdbc/execute-one! (get-ds) %)))]
             (snatch {} (row-handler last-row))
             last-row))))



; 1. we compare to the timestamp of the row with the given id instead of
;   the saved created_at because the latter might not be exactly the same
;   (loosing bits due to coercion)
; 2. this row might not even exist anymore; e.g. because it has been removed
;     by the sweeper or something else (truncate); in that case we query all
;     events of the last 24 hours as a fallback

(defn process-new-rows-query [table-name {last-id :id :as last-processed-row}]
  (-> (sql/select :*)
      (sql/from (keyword table-name))
      (sql/where [:> :created_at
                  (->
                    (sql/select [[:coalesce [:max :created_at]
                                  [:raw "NOW() - INTERVAL '24 hours'"]] :foo])
                    (sql/from (keyword table-name))
                    (sql/where [:= :id (uuid  last-id)])
                    )])
      (sql/order-by [:created_at :asc])
      (sql/limit 1000)))

(comment (-> (process-new-rows-query "users" {:id "5613aef9-f0d9-441d-bb42-e18e202b8e74"})
             (sql-format :inline true)))

(defn process-new-rows [table-name state-atom row-handler]
  (swap! state-atom
         (fn [last-processed-row]
           (or (-> (process-new-rows-query table-name last-processed-row)
                   (sql-format :inline false)
                   (->> (jdbc/execute! (get-ds))
                        (map (fn [row] (snatch {} (row-handler row)) row))
                        last))
               last-processed-row))))

(defn process
  [table-name state-atom row-handler]
  (locking state-atom
    (if-not @state-atom
      (process-last-row table-name state-atom row-handler)
      (process-new-rows table-name state-atom row-handler))))
