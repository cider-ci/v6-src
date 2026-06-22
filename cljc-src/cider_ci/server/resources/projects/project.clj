(ns cider-ci.server.resources.projects.project
  (:require
    [cider-ci.server.projects.repositories.state.db :refer [db*] :rename {db* repo-state-db*}]
    [clojure.string :as str]
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

(defn- patch-handler [tx project-id body]
  (let [name     (some-> body :name str/trim)
        git-url  (some-> body :git_url str/trim)
        include  (get body :branch_trigger_include_match)
        exclude  (get body :branch_trigger_exclude_match)
        max-age  (some-> body :branch_trigger_max_commit_age str/trim)
        interval (some-> body :remote_fetch_interval str/trim)]
    (when (str/blank? name)
      (throw (ex-info "Name is required" {:status 422})))
    (when (str/blank? git-url)
      (throw (ex-info "Git URL is required" {:status 422})))
    (when (and (some? max-age) (str/blank? max-age))
      (throw (ex-info "branch_trigger_max_commit_age must not be blank" {:status 422})))
    (try
      (let [updates (cond-> {:name name :git_url git-url}
                      (some? include)  (assoc :branch_trigger_include_match include)
                      (some? exclude)  (assoc :branch_trigger_exclude_match exclude)
                      (some? max-age)  (assoc :branch_trigger_max_commit_age max-age)
                      (some? interval) (assoc :remote_fetch_interval interval))
            updated (jdbc/execute-one! tx
                      (sql-format
                        (-> (sql/update :repositories)
                            (sql/set updates)
                            (sql/where [:= :id project-id])
                            (sql/returning :id))))]
        (if updated
          {:status 200 :body {:id project-id}}
          {:status 404 :body "Project not found"}))
      (catch java.sql.SQLException e
        (if (= "23505" (.getSQLState e))
          (throw (ex-info "Git URL already used by another project" {:status 422}))
          (throw e))))))

(defn handler [{{{project-id :project-id} :path-params} :route
                tx :tx
                request-method :request-method
                body :body}]
  (case request-method
    :get    (get-handler tx project-id)
    :patch  (patch-handler tx project-id body)
    :delete (delete-handler tx project-id)))
