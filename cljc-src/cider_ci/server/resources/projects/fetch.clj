(ns cider-ci.server.resources.projects.fetch
  (:require
    [cider-ci.server.projects.repositories.fetch-and-update.shared :refer [fetch-and-update]]
    [cider-ci.server.projects.repositories.state.main :as state]))

(defn handler [{{{project-id :project-id} :path-params} :route}]
  (if-let [repository (-> (state/get-db) :repositories (get (keyword project-id)))]
    (do (fetch-and-update repository)
        {:status 202 :body {:status "pending"}})
    {:status 404 :body "Project not found"}))
