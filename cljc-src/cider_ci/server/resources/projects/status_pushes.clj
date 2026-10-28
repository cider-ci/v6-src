(ns cider-ci.server.resources.projects.status-pushes
  (:require
    [next.jdbc :as jdbc]))

(defn handler
  "Push now (legacy parity): forgets what was posted for the project's jobs
   of the last 24 hours, the status pushes daemon posts them again."
  [{{{project-id :project-id} :path-params} :route tx :tx}]
  (if-not (jdbc/execute-one! tx ["SELECT 1 FROM repositories WHERE id = ?" project-id])
    {:status 404 :body "Project not found"}
    (let [result (jdbc/execute-one! tx
                   ["DELETE FROM job_status_pushes
                     WHERE job_id IN (SELECT id FROM jobs
                                      WHERE project_id = ?
                                        AND updated_at > now() - interval '24 hours')"
                    project-id])]
      {:status 202 :body {:jobs (:next.jdbc/update-count result)}})))
