(ns cider-ci.server.resources.projects.jobs
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.server.jobs.decompose :as decompose]
    [cider-ci.server.projects.repositories.project-configuration.direct :as config]
    [cider-ci.server.projects.repositories.shared :as shared]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.sql :as jdbc-sql]
    [taoensso.timbre :refer [warn]]))

(defn- full-job-configs [repo commit-id]
  (try
    (let [cfg      (config/build repo commit-id)
          jobs-map (or (:jobs cfg) {})]
      (->> jobs-map
           (map (fn [[k v]]
                  {:key       (name k)
                   :name      (or (:name v) (name k))
                   :full-spec v}))
           vec))
    (catch clojure.lang.ExceptionInfo e
      (if (= 404 (:status (ex-data e)))
        []
        (throw e)))))

(defn- available-jobs [repo commit-id]
  (->> (full-job-configs repo commit-id)
       (map #(select-keys % [:key :name]))))

(defn- created-jobs [project-id commit-id]
  (->> (-> (sql/select :id :key :name :state :created_at)
           (sql/from :jobs)
           (sql/where [:= :project_id project-id])
           (sql/where [:= :commit_id commit-id])
           (sql/order-by [:created_at :desc])
           sql-format)
       (jdbc-sql/query (get-ds))))

(defn- create-job [repo project-id commit-id body session]
  (let [job-key (:key body)]
    (when-not (seq job-key)
      {:status 400 :body "Missing job key"})
    (let [all-configs (full-job-configs repo commit-id)
          job-entry   (some #(when (= (:key %) job-key) %) all-configs)]
      (if-not job-entry
        {:status 404 :body "Job not found in configuration"}
        (let [created-by (get-in session [:user :id])
              full-spec  (:full-spec job-entry)
              task-specs (decompose/decompose full-spec)
              new-job-id (java.util.UUID/randomUUID)]
          (jdbc/with-transaction [tx (get-ds)]
            (jdbc/execute-one! tx
              (-> (sql/insert-into :jobs)
                  (sql/values [{:id          new-job-id
                                :project_id  project-id
                                :commit_id   commit-id
                                :jobs/key    job-key
                                :jobs/name   (:name job-entry)
                                :state       "pending"
                                :spec        [:lift full-spec]
                                :created_by  created-by}])
                  sql-format))
            (doseq [task-spec task-specs]
              (let [new-task-id (java.util.UUID/randomUUID)]
                (jdbc/execute-one! tx
                  (-> (sql/insert-into :tasks)
                      (sql/values [{:id         new-task-id
                                    :job_id     new-job-id
                                    :tasks/name (:name task-spec)
                                    :state      "pending"
                                    :spec       [:lift task-spec]}])
                      sql-format))
                (jdbc/execute-one! tx
                  (-> (sql/insert-into :trials)
                      (sql/values [{:task_id new-task-id
                                    :state   "pending"}])
                      sql-format)))))
          {:status 201 :body {:id    new-job-id
                              :key   job-key
                              :name  (:name job-entry)
                              :state "pending"}})))))

(defn- get-job-with-tasks [project-id job-id]
  (when-let [job (first (jdbc-sql/query (get-ds)
                          (-> (sql/select :*)
                              (sql/from :jobs)
                              (sql/where [:= :id (java.util.UUID/fromString job-id)])
                              (sql/where [:= :project_id project-id])
                              sql-format)))]
    (let [tasks (jdbc-sql/query (get-ds)
                  (-> (sql/select :id :name :state :created_at)
                      (sql/from :tasks)
                      (sql/where [:= :job_id (:id job)])
                      (sql/order-by [:created_at :asc])
                      sql-format))]
      (assoc job :tasks tasks))))

(defn handler [{{{:keys [project-id commit-id job-id]} :path-params} :route
                route-name    :route-name
                request-method :request-method
                body           :body
                session        :session}]
  (case route-name
    :project-job
    (case request-method
      :get (if-let [result (get-job-with-tasks project-id job-id)]
             {:status 200 :body result}
             {:status 404 :body "Job not found"})
      {:status 405 :body "Method not allowed"})

    :project-jobs
    (with-open [repo (shared/file-repository (shared/path {:project-id project-id}))]
      (case request-method
        :get  {:status 200
               :body   {:available (available-jobs repo commit-id)
                        :created   (created-jobs project-id commit-id)}}
        :post (create-job repo project-id commit-id body session)
        {:status 405 :body "Method not allowed"}))

    {:status 500 :body "Unresolved route"}))
