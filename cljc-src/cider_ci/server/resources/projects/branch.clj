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


(defn- tip-commit-sql [project-id commit-id]
  ["SELECT c.id, c.subject, c.author_name, c.committer_date,
           (SELECT json_agg(json_build_object(
                              'id',    j.id::text,
                              'key',   j.key,
                              'name',  j.name,
                              'state', j.state)
                            ORDER BY j.created_at ASC)
            FROM jobs j
            WHERE j.commit_id = c.id AND j.project_id = ?)
            AS jobs
    FROM commits c
    WHERE c.id = ?"
   project-id commit-id])


(defn- recent-commits-sql [branch-id project-id]
  ["SELECT c.id, c.subject, c.author_name, c.author_email,
           c.committer_date, c.signature_fingerprint,
           (SELECT json_agg(json_build_object(
                              'id',    j.id::text,
                              'key',   j.key,
                              'state', j.state)
                            ORDER BY j.created_at DESC)
            FROM jobs j
            WHERE j.commit_id = c.id AND j.project_id = ?) AS jobs
    FROM branches_commits bc
    JOIN commits c ON c.id = bc.commit_id
    WHERE bc.branch_id = ?
    ORDER BY c.committer_date DESC NULLS LAST, c.created_at DESC
    LIMIT ?"
   project-id branch-id commits-limit])


(defn handler [{{{project-id  :project-id
                  branch-name :branch-name} :path-params} :route
                tx :tx}]
  (if-let [branch (jdbc/execute-one!
                    tx (sql-format (branch-sql project-id branch-name)))]
    (let [commits    (jdbc/execute! tx (recent-commits-sql (:id branch) project-id))
          tip-commit (when-let [cid (:current_commit_id branch)]
                       (jdbc/execute-one! tx (tip-commit-sql project-id cid)))]
      {:body (assoc branch
                    :project_id    project-id
                    :tip_commit    tip-commit
                    :commits       commits
                    :commits_limit commits-limit)})
    {:status 404 :body "Branch not found"}))
