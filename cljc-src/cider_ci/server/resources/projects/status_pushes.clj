(ns cider-ci.server.resources.projects.status-pushes
  (:require
    [cider-ci.server.status-pushes.main :as status-pushes]
    [next.jdbc :as jdbc]))

(defn handler
  "Push statuses now: the jobs of the project's current branch heads (or of
   one branch) are posted to GitHub again, whatever their age."
  [{{{project-id :project-id branch-name :branch-name} :path-params
     {route-name :name} :data} :route tx :tx}]
  (if-not (jdbc/execute-one! tx ["SELECT 1 FROM repositories WHERE id = ?" project-id])
    {:status 404 :body "Project not found"}
    (let [jobs (if (= route-name :project-branch-push-statuses)
                 (status-pushes/repost-branch! tx project-id branch-name)
                 (status-pushes/repost-project! tx project-id))]
      {:status 202 :body {:jobs jobs}})))
