(ns cider-ci.server.resources.commits.main
  (:require
    [cider-ci.utils.query-params :as query-params]
    [clojure.string :as str]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug]]))


(defn- build-query [{:keys [project branch]}]
  (let [base  "WITH page AS (
     SELECT DISTINCT
       c.id, c.subject, c.author_name, c.committer_name, c.committer_date, c.depth
     FROM commits c
     JOIN branches b ON b.current_commit_id = c.id
     JOIN repositories r ON r.id = b.repository_id
     %s
     ORDER BY c.committer_date DESC NULLS LAST
     LIMIT 50
   )
   SELECT
     page.id,
     page.subject,
     page.author_name,
     page.committer_name,
     page.committer_date,
     (SELECT json_agg(json_build_object(
        'name',            b.name,
        'repository_id',   r.id,
        'repository_name', r.name
      ))
      FROM branches b
      JOIN repositories r ON r.id = b.repository_id
      WHERE b.current_commit_id = page.id
     ) AS branches,
     (SELECT json_agg(json_build_object(
        'name',            b.name,
        'repository_id',   r.id,
        'repository_name', r.name,
        'distance',        COALESCE(hc.depth - page.depth, 0)
      ) ORDER BY COALESCE(hc.depth - page.depth, 0) ASC, b.name ASC)
      FROM branches_commits bc
      JOIN branches b ON b.id = bc.branch_id
      JOIN repositories r ON r.id = b.repository_id
      JOIN commits hc ON hc.id = b.current_commit_id
      WHERE bc.commit_id = page.id
        AND b.current_commit_id != page.id
     ) AS ancestor_branches,
     (SELECT json_agg(json_build_object(
        'id',         j.id::text,
        'key',        j.key,
        'name',       j.name,
        'state',      j.state,
        'project_id', j.project_id
      ) ORDER BY j.created_at)
      FROM jobs j
      WHERE j.commit_id = page.id
     ) AS jobs
   FROM page
   ORDER BY page.committer_date DESC NULLS LAST"
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
