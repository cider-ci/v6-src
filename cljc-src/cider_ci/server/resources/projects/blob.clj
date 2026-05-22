(ns cider-ci.server.resources.projects.blob
  (:require
    [cider-ci.server.projects.repositories.core :as repo-core]
    [cider-ci.server.projects.repositories.shared :as shared]))

(defn handler [{{{project-id :project-id
                  commit-id  :commit-id
                  blob-path  :blob-path} :path-params} :route}]
  (with-open [repo (shared/file-repository (shared/path {:project-id project-id}))]
    (if-let [content (repo-core/path-content repo commit-id blob-path)]
      {:status 200
       :body   {:content (String. ^bytes content "UTF-8")
                :path    blob-path}}
      {:status 404 :body "File not found"})))
