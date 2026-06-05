(ns cider-ci.server.resources.commits.main
  (:require
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug]]))


(def ^:private commits-query
  "SELECT
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
   LIMIT 25")


(defn handler
  [{route-name    :route-name
    request-method :request-method
    tx             :tx}]
  (debug 'commits-handler route-name request-method)
  (case route-name
    :commits
    (case request-method
      :get {:status 200
            :body   {:commits (jdbc/execute! tx [commits-query])}}
      {:status 405 :body "Method not allowed"})
    {:status 500 :body "Unresolved route"}))
