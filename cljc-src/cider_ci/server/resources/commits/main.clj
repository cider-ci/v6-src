(ns cider-ci.server.resources.commits.main
  (:require
    [cider-ci.server.projects.repositories.state.db :refer [db*] :rename {db* repo-state-db*}]
    [cider-ci.utils.core :refer [presence]]
    [cider_ci.server.entities.passwords :as passwords]
    [cider_ci.server.entities.users :as users]
    [cuerdas.core :as string]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(defn get-commits
  [{{{user-id :user-id} :path-params
     {route-name :name} :data} :route
    request-method :request-method
    data :body tx :tx :as request}]
  (debug request)
  {})


(defn handler
  [{{{user-id :user-id} :path-params
     {route-name :name} :data} :route
    request-method :request-method
    data :body tx :tx :as request}]
  (case route-name
    :commits (case request-method
               :get (get-commits request))))
