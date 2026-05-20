(ns cider-ci.server.resources.projects.branch
  (:require
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]))


(def ^:private commits-limit 50)


(defn- branch-sql [project-id branch-name]
  (-> (sql/select :id :name :current_commit_id :created_at :updated_at)
      (sql/from :branches)
      (sql/where [:= :repository_id project-id])
      (sql/where [:= :name branch-name])))


(defn- recent-commits-sql [branch-id]
  (-> (sql/select
        [:c.id      :id]
        [:c.subject :subject]
        [:c.author_name    :author_name]
        [:c.author_email   :author_email]
        [:c.committer_date :committer_date]
        [:c.signature_fingerprint :signature_fingerprint])
      (sql/from [:branches_commits :bc])
      (sql/join [:commits :c] [:= :c.id :bc.commit_id])
      (sql/where [:= :bc.branch_id branch-id])
      (sql/order-by [:c.committer_date :desc :nulls-last]
                    [:c.created_at :desc])
      (sql/limit commits-limit)))


(defn handler [{{{project-id  :project-id
                  branch-name :branch-name} :path-params} :route
                tx :tx}]
  (if-let [branch (jdbc/execute-one!
                    tx (sql-format (branch-sql project-id branch-name)))]
    (let [commits (jdbc/execute!
                    tx (sql-format (recent-commits-sql (:id branch))))]
      {:body (assoc branch
                    :project_id project-id
                    :commits commits
                    :commits_limit commits-limit)})
    {:status 404 :body "Branch not found"}))
