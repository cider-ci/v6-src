(ns cider-ci.server.resources.trials
  (:require
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc.sql :as jdbc-sql]))


(defn handler [{tx             :tx
                route-name     :route-name
                request-method :request-method
                {{:keys [trial-id attachment-path]} :path-params} :route}]
  (case route-name
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
