(ns cider-ci.server.resources.projects.configuration
  (:require
    [cider-ci.server.projects.repositories.project-configuration.direct :as config]
    [cider-ci.server.projects.repositories.shared :as shared]))


(defn handler [{{{:keys [project-id commit-id]} :path-params} :route}]
  (with-open [repo (shared/file-repository (shared/path {:project-id project-id}))]
    (try
      {:status 200 :body (config/build repo commit-id)}
      (catch clojure.lang.ExceptionInfo e
        {:status (or (:status (ex-data e)) 500)
         :body   (ex-message e)}))))
