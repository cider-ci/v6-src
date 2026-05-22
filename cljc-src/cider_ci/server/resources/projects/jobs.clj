(ns cider-ci.server.resources.projects.jobs
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.server.projects.repositories.project-configuration.direct :as config]
    [cider-ci.server.projects.repositories.shared :as shared]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.sql :as jdbc-sql]
    [taoensso.timbre :refer [warn]]))

(defn- available-jobs [repo commit-id]
  (try
    (let [cfg      (config/build repo commit-id)
          jobs-map (or (:jobs cfg) {})]
      (->> jobs-map
           (map (fn [[k v]]
                  {:key         (name k)
                   :name        (or (:name v) (name k))
                   :description (:description v)}))
           vec))
    (catch clojure.lang.ExceptionInfo e
      (if (= 404 (:status (ex-data e)))
        []
        (throw e)))))

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
    (let [available (available-jobs repo commit-id)
          job-spec  (some #(when (= (:key %) job-key) %) available)]
      (if-not job-spec
        {:status 404 :body "Job not found in configuration"}
        (let [created-by (get-in session [:user :id])
              result     (jdbc/execute-one! (get-ds)
                           (-> (sql/insert-into :jobs)
                               (sql/values [{:project_id  project-id
                                             :commit_id   commit-id
                                             :jobs/key    job-key
                                             :jobs/name   (:name job-spec)
                                             :description (:description job-spec)
                                             :state       "pending"
                                             :spec        [:lift job-spec]
                                             :created_by  created-by}])
                               (sql/returning :id :jobs/key :jobs/name :state)
                               sql-format))]
          {:status 201 :body result})))))

(defn handler [{{{:keys [project-id commit-id]} :path-params} :route
                request-method :request-method
                body           :body
                session        :session}]
  (with-open [repo (shared/file-repository (shared/path {:project-id project-id}))]
    (case request-method
      :get  {:status 200
             :body   {:available (available-jobs repo commit-id)
                      :created   (created-jobs project-id commit-id)}}
      :post (create-job repo project-id commit-id body session)
      {:status 405 :body "Method not allowed"})))
