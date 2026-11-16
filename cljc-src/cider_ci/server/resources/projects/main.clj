(ns cider-ci.server.resources.projects.main
  (:require
    [cider-ci.server.projects.repositories.state.db :refer [db*] :rename {db* repo-state-db*}]
    [cider-ci.server.status-pushes.github :as github]
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
                (#(jdbc/execute-one! tx %)))]
    (when-not (:id res)
      (throw (ex-info "Project creation failed" {:status 422})))
    {:body res}))


(def ^:private projects-query
  "The projects with the outcome of their last status push attempt."
  "SELECT r.id, r.name, r.git_url, r.send_status_notifications,
          r.remote_api_endpoint, r.remote_api_namespace, r.remote_api_name, r.remote_api_type,
          r.remote_api_token IS NOT NULL AS remote_api_token_set,
          a.success AS last_push_success, a.error AS last_push_error, a.created_at AS last_push_at
   FROM repositories r
   LEFT JOIN LATERAL (SELECT success, error, created_at FROM status_push_attempts
                      WHERE project_id = r.id ORDER BY created_at DESC LIMIT 1) a ON true
   ORDER BY r.id")

(defn- status-pushes
  "For the listing: off, or enabled with the outcome of the last push (or
   why the project cannot push at all)."
  [{:keys [send_status_notifications remote_api_token_set
           last_push_success last_push_error last_push_at] :as repo}]
  (let [enabled (boolean (and send_status_notifications remote_api_token_set))
        remote  (github/remote (assoc repo :remote_api_token (when remote_api_token_set "set")))]
    (cond-> {:enabled enabled}
      (and enabled (:error remote)) (assoc :error (:error remote))
      (some? last_push_success) (assoc :last_success last_push_success
                                       :last_error last_push_error
                                       :last_at last_push_at))))

(defn get-projects [tx]
  {:body (->> (jdbc/execute! tx [projects-query])
              (map (fn [{id :id :as repo}]
                     (merge
                       (select-keys repo [:id :name])
                       {:status_pushes (status-pushes repo)}
                       (some->
                         @repo-state-db*
                         (get-in [:repositories (keyword id)])
                         (select-keys [:fetch-and-update :branch-updates]))))))})

(defn handler
  [{{{user-id :user-id} :path-params
     {route-name :name} :data} :route
    request-method :request-method
    data :body tx :tx :as request}]
  (case route-name
    :projects (case request-method
                :post (create-project tx data)
                :get (get-projects tx))))
