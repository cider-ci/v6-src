(ns cider-ci.server.resources.projects.commit
  (:require
    [cider-ci.server.projects.submodule-resolutions :as submodule-resolutions]
    [cider-ci.server.status-pushes.github :as github]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]))


(defn- commit-sql [commit-id]
  (-> (sql/select
        [:c.id            :id]
        [:c.tree_id       :tree_id]
        [:c.depth         :depth]
        [:c.author_name   :author_name]
        [:c.author_email  :author_email]
        [[:md5 [:lower :c.author_email]] :author_email_hash]
        [:c.author_date   :author_date]
        [:c.committer_name  :committer_name]
        [:c.committer_email :committer_email]
        [[:md5 [:lower :c.committer_email]] :committer_email_hash]
        [:c.committer_date  :committer_date]
        [:c.subject :subject]
        [:c.body    :body]
        [:c.created_at :created_at]
        [[:case [:not= :c.signature nil] true :else false] :is_signed]
        [:c.signature_fingerprint :signature_fingerprint]
        [:gk.name :signing_key_name]
        [:gk.user_id :signing_key_user_id]
        [:u.name :signing_key_user_name]
        [[:coalesce :u.login :u.github_login] :signing_key_user_login])
      (sql/from [:commits :c])
      (sql/left-join [:gpg_keys :gk] [:= :gk.fingerprint :c.signature_fingerprint])
      (sql/left-join [:users :u] [:= :u.id :gk.user_id])
      (sql/where [:= :c.id commit-id])))


(defn- parents-sql [commit-id]
  (-> (sql/select :parent_id)
      (sql/from :commit_arcs)
      (sql/where [:= :child_id commit-id])
      (sql/order-by [:parent_id :asc])))

(defn- tree-attachments-sql [tree-id]
  (-> (sql/select :path :content_type)
      (sql/from :tree_attachments)
      (sql/where [:= :tree_id tree-id])
      (sql/order-by [:path :asc])))


(defn- submodules-handler
  "GET: the stored submodule resolution of the commit (computed when absent).
   POST: 'Check now' - resolve again and store."
  [tx project-id commit-id request-method]
  (case request-method
    :get  {:body (submodule-resolutions/get-or-check! tx project-id commit-id)}
    :post {:body (submodule-resolutions/check! tx project-id commit-id)}
    {:status 405 :body "Method not allowed"}))

(defn- repository [tx project-id]
  (jdbc/execute-one! tx
    ["SELECT id, git_url, send_status_notifications, remote_api_endpoint,
             remote_api_namespace, remote_api_name, remote_api_type,
             remote_api_token IS NOT NULL AS remote_api_token_set
      FROM repositories WHERE id = ?" project-id]))

(def ^:private attempts-limit 20)

(defn- status-pushes
  "Where this project's job states go (or why nowhere) and the last attempts
   for this commit, failed ones included."
  [tx {:keys [send_status_notifications remote_api_token_set] :as repo} commit-id]
  (let [remote   (github/remote (assoc repo :remote_api_token (when remote_api_token_set "set")))
        attempts (jdbc/execute! tx
                   ["SELECT a.id, a.job_id, a.job_state, a.github_state, a.context, a.target,
                            a.success, a.error, a.created_at, j.key AS job_key, j.name AS job_name
                     FROM status_push_attempts a JOIN jobs j ON j.id = a.job_id
                     WHERE a.project_id = ? AND a.commit_id = ?
                     ORDER BY a.created_at DESC LIMIT ?"
                    (:id repo) commit-id attempts-limit])]
    (merge {:enabled  (boolean (and send_status_notifications remote_api_token_set))
            :disabled (not send_status_notifications)
            :error    (:error remote)
            :attempts attempts}
           (when-not (:error remote)
             {:repository (str (:owner remote) "/" (:name remote))}))))

(defn- commit-handler [tx project-id commit-id]
  (if-let [commit (jdbc/execute-one! tx (sql-format (commit-sql commit-id)))]
    (let [repo             (repository tx project-id)
          parents          (->> (sql-format (parents-sql commit-id))
                                (jdbc/execute! tx)
                                (map :parent_id))
          tree-attachments (jdbc/execute! tx (sql-format (tree-attachments-sql (:tree_id commit))))]
      {:body (-> commit
                 (assoc :parents parents)
                 (assoc :tree_attachments tree-attachments)
                 (assoc :origin_url (github/commit-url (:git_url repo) commit-id))
                 (assoc :status_pushes (when repo (status-pushes tx repo commit-id))))})
    {:status 404 :body "Commit not found"}))


(defn handler [{{{commit-id :commit-id project-id :project-id} :path-params} :route
                route-name     :route-name
                request-method :request-method
                tx             :tx}]
  (if (= route-name :project-commit-submodules)
    (submodules-handler tx project-id commit-id request-method)
    (commit-handler tx project-id commit-id)))
