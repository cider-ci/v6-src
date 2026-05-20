(ns cider-ci.server.resources.projects.project
  (:require
    [cider-ci.server.projects.repositories.state.db :refer [db*] :rename {db* repo-state-db*}]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]))


(defn- repository-sql [project-id]
  (-> (sql/select
        :id :name :git_url
        :branch_trigger_include_match :branch_trigger_exclude_match
        :branch_trigger_max_commit_age
        :remote_fetch_interval
        :public_view_permission
        :created_at :updated_at)
      (sql/from :repositories)
      (sql/where [:= :id project-id])))


(defn- branches-sql [project-id]
  (-> (sql/select
        :b.id :b.name :b.current_commit_id :b.updated_at
        [:c.committer_date :commit_committer_date]
        [:c.author_date    :commit_author_date]
        [:c.subject        :commit_subject]
        [:c.author_name    :commit_author_name]
        [:c.signature_fingerprint :commit_signature_fingerprint])
      (sql/from [:branches :b])
      (sql/left-join [:commits :c] [:= :c.id :b.current_commit_id])
      (sql/where [:= :b.repository_id project-id])
      (sql/order-by [:b.name :asc])))


(defn- get-handler [tx project-id]
  (if-let [repo (jdbc/execute-one! tx (sql-format (repository-sql project-id)))]
    (let [branches (jdbc/execute! tx (sql-format (branches-sql project-id)))
          repo-state (some-> @repo-state-db*
                             (get-in [:repositories (keyword project-id)])
                             (select-keys [:fetch-and-update :branch-updates]))]
      {:body (merge repo repo-state {:branches branches})})
    {:status 404 :body "Project not found"}))

(defn- delete-handler [tx project-id]
  (let [deleted (jdbc/execute-one! tx (sql-format
                                        (-> (sql/delete-from :repositories)
                                            (sql/where [:= :id project-id])
                                            (sql/returning :id))))]
    (if deleted
      {:status 200 :body {:id project-id}}
      {:status 404 :body "Project not found"})))

(defn handler [{{{project-id :project-id} :path-params} :route
                tx :tx
                request-method :request-method}]
  (case request-method
    :get    (get-handler tx project-id)
    :delete (delete-handler tx project-id)))
