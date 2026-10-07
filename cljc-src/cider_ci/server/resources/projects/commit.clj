(ns cider-ci.server.resources.projects.commit
  (:require
    [cider-ci.server.projects.submodule-resolutions :as submodule-resolutions]
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
        [:c.author_date   :author_date]
        [:c.committer_name  :committer_name]
        [:c.committer_email :committer_email]
        [:c.committer_date  :committer_date]
        [:c.subject :subject]
        [:c.body    :body]
        [:c.created_at :created_at]
        [[:case [:not= :c.signature nil] true :else false] :is_signed]
        [:c.signature_fingerprint :signature_fingerprint]
        [:gk.name :signing_key_name]
        [:gk.user_id :signing_key_user_id]
        [:u.login :signing_key_user_login])
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

(defn- commit-handler [tx commit-id]
  (if-let [commit (jdbc/execute-one! tx (sql-format (commit-sql commit-id)))]
    (let [parents          (->> (sql-format (parents-sql commit-id))
                                (jdbc/execute! tx)
                                (map :parent_id))
          tree-attachments (jdbc/execute! tx (sql-format (tree-attachments-sql (:tree_id commit))))]
      {:body (-> commit
                 (assoc :parents parents)
                 (assoc :tree_attachments tree-attachments))})
    {:status 404 :body "Commit not found"}))


(defn handler [{{{commit-id :commit-id project-id :project-id} :path-params} :route
                route-name     :route-name
                request-method :request-method
                tx             :tx}]
  (if (= route-name :project-commit-submodules)
    (submodules-handler tx project-id commit-id request-method)
    (commit-handler tx commit-id)))
