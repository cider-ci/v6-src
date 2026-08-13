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
               j.commit_id
             FROM trials t
             JOIN tasks tsk ON tsk.id = t.task_id
             JOIN jobs j    ON j.id   = tsk.job_id
             WHERE t.id = ?::uuid"
            trial-id])))


(defn handler [{tx             :tx
                route-name     :route-name
                request-method :request-method
                {{:keys [trial-id attachment-path]} :path-params} :route}]
  (case route-name
    :trial
    (case request-method
      :get (if-let [trial (get-trial tx trial-id)]
             {:status 200 :body trial}
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

    {:status 500 :body "Unresolved route"}))
