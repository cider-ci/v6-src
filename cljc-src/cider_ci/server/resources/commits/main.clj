(ns cider-ci.server.resources.commits.main
  (:require
    [cider-ci.utils.query-params :as query-params]
    [clojure.string :as str]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug]]))


(defn- build-query [{:keys [project branch]}]
  (let [base  "SELECT
     c.id,
     c.subject,
     c.author_name,
     c.committer_name,
     c.committer_date,
     b_agg.branches,
     j_agg.jobs
   FROM commits c
   JOIN (
     SELECT b.current_commit_id,
       json_agg(json_build_object(
         'name',            b.name,
         'repository_id',   r.id,
         'repository_name', r.name
       )) AS branches
     FROM branches b
     JOIN repositories r ON r.id = b.repository_id
     %s
     GROUP BY b.current_commit_id
   ) b_agg ON b_agg.current_commit_id = c.id
   LEFT JOIN (
     SELECT j.commit_id,
       json_agg(json_build_object(
         'id',         j.id::text,
         'key',        j.key,
         'name',       j.name,
         'state',      j.state,
         'project_id', j.project_id
       ) ORDER BY j.created_at) AS jobs
     FROM jobs j
     GROUP BY j.commit_id
   ) j_agg ON j_agg.commit_id = c.id
   ORDER BY c.committer_date DESC NULLS LAST
   LIMIT 50"
        clauses  (cond-> []
                   (not (str/blank? project)) (conj "r.id = ?")
                   (not (str/blank? branch))  (conj "b.name ~ ?"))
        repo-where (if (seq clauses)
                     (str "WHERE " (str/join " AND " clauses))
                     "")
        params   (cond-> []
                   (not (str/blank? project)) (conj project)
                   (not (str/blank? branch))  (conj branch))]
    (into [(format base repo-where)] params)))


(defn handler
  [{route-name     :route-name
    request-method :request-method
    query-string   :query-string
    tx             :tx}]
  (debug 'commits-handler route-name request-method)
  (case route-name
    :commits
    (case request-method
      :get (let [params  (query-params/decode query-string)
                 commits (jdbc/execute! tx (build-query params))]
             {:status 200
              :body   {:commits commits
                       :filters (select-keys params [:project :branch])}})
      {:status 405 :body "Method not allowed"})
    {:status 500 :body "Unresolved route"}))
