(ns cider-ci.server.resources.projects.main
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



(defn create-project [tx data]
  (let [res (-> (sql/insert-into :repositories)
                (sql/values [data])
                (sql/returning :*)
                (sql-format)
                (#(jdbc/execute-one! tx % {:return-keys true})))]
    (when-not (:id res)
      (throw (ex-info "Project creation failed" {:status 422})))
    res))


(defn get-projects [tx]
  {:body (-> (sql/select :id :name)
             (sql/from :repositories)
             (sql-format)
             (->> (jdbc/execute! tx)
                  (map (fn [{id :id :as repo}]
                         (merge
                           repo
                           (some->
                             @repo-state-db*
                             (get-in [:repositories (keyword id)])
                             (select-keys [:fetch-and-update :branch-updates])))))))})

(defn handler
  [{{{user-id :user-id} :path-params
     {route-name :name} :data} :route
    request-method :request-method
    data :body tx :tx :as request}]
  (case route-name
    :projects (case request-method
                :post (create-project tx data)
                :get (get-projects tx))))
