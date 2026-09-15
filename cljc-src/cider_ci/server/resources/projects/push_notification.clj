(ns cider-ci.server.resources.projects.push-notification
  (:require
    [cider-ci.server.projects.repositories.fetch-and-update.shared :refer [fetch-and-update]]
    [cider-ci.server.projects.repositories.sql.repository :refer [get-repository-by-update-notification-token]]
    [cider-ci.server.projects.repositories.state.main :as state]))

(defn handler [{{{token :token} :path-params} :route request-method :request-method}]
  (if (= request-method :post)
    (if-let [repo (get-repository-by-update-notification-token token)]
      (do
        (state/update-repositories)
        (when-let [repo-state (-> (state/get-db) :repositories (get (keyword (:id repo))))]
          (fetch-and-update repo-state))
        {:status 202 :body {:status "accepted"}})
      {:status 404 :body "Repository not found"})
    {:status 405 :body "Method not allowed"}))
