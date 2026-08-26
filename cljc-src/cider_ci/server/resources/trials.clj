(ns cider-ci.server.resources.trials
  (:require
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.sql :as jdbc-sql]))


(defn- get-trial [tx trial-id]
  (first (jdbc/execute! tx
           ["SELECT
               t.id::text      AS trial_id,
               t.state         AS trial_state,
               t.started_at,
               t.finished_at,
               t.error,
               t.result,
               t.task_id::text,
               tsk.name        AS task_name,
               tsk.spec        AS task_spec,
               j.id::text      AS job_id,
               j.name          AS job_name,
               j.key           AS job_key,
               j.project_id,
               j.commit_id,
               c.tree_id
             FROM trials t
             JOIN tasks   tsk ON tsk.id = t.task_id
             JOIN jobs    j   ON j.id   = tsk.job_id
             LEFT JOIN commits c   ON c.id   = j.commit_id
             WHERE t.id = ?::uuid"
            trial-id])))


(defn- get-trial-attachments [tx trial-id]
  (jdbc/execute! tx
    ["SELECT path, content_type
      FROM trial_attachments
      WHERE trial_id = ?::uuid
      ORDER BY path"
     trial-id]))

(defn- get-tree-attachments [tx tree-id]
  (jdbc/execute! tx
    ["SELECT path, content_type
      FROM tree_attachments
      WHERE tree_id = ?
      ORDER BY path"
     tree-id]))


(defn handler [{tx             :tx
                route-name     :route-name
                request-method :request-method
                {path-params   :path-params} :route}]
  (let [{:keys [trial-id attachment-path tree-id]} path-params]
    (case route-name
      :trial
      (case request-method
        :get (if-let [trial (get-trial tx trial-id)]
               {:status 200
                :body   (-> trial
                            (assoc :attachments      (get-trial-attachments tx trial-id))
                            (assoc :tree_attachments (get-tree-attachments  tx (:tree_id trial))))}
               {:status 404 :body "Trial not found"})
        {:status 405 :body "Method not allowed"})

      :trial-attachment
      (case request-method
        :get
        (let [trial-uuid (java.util.UUID/fromString trial-id)
              row        (first (jdbc-sql/query tx
                                  (-> (sql/select :content :content_type)
                                      (sql/from :trial_attachments)
                                      (sql/where [:= :trial_id trial-uuid])
                                      (sql/where [:= :path attachment-path])
                                      sql-format)))]
          (if row
            {:status  200
             :headers {"Content-Type" (:content_type row)}
             :body    (:content row)}
            {:status 404 :body "Attachment not found"}))
        {:status 405 :body "Method not allowed"})

      :tree-attachment
      (case request-method
        :get
        (let [row (first (jdbc-sql/query tx
                           (-> (sql/select :content :content_type)
                               (sql/from :tree_attachments)
                               (sql/where [:= :tree_id tree-id])
                               (sql/where [:= :path attachment-path])
                               sql-format)))]
          (if row
            {:status  200
             :headers {"Content-Type" (:content_type row)}
             :body    (:content row)}
            {:status 404 :body "Tree attachment not found"}))
        {:status 405 :body "Method not allowed"})

      {:status 500 :body "Unresolved route"})))
