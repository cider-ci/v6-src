(ns cider-ci.server.resources.jobs.main
  (:require
    [cider-ci.utils.query-params :as query-params]
    [clojure.string :as str]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug]]))


(defn- build-query [{:keys [project state]}]
  (let [clauses (cond-> []
                  (not (str/blank? project)) (conj "j.project_id = ?")
                  (not (str/blank? state))   (conj "j.state = ?"))
        where   (if (seq clauses)
                  (str "WHERE " (str/join " AND " clauses))
                  "")
        params  (cond-> []
                  (not (str/blank? project)) (conj project)
                  (not (str/blank? state))   (conj state))]
    (into [(format "SELECT j.id::text, j.key, j.name, j.state,
                           j.created_at, j.project_id, j.commit_id,
                           r.name AS project_name
                    FROM jobs j
                    JOIN repositories r ON r.id = j.project_id
                    %s
                    ORDER BY j.created_at DESC
                    LIMIT 100"
                   where)]
          params)))


(defn handler
  [{route-name     :route-name
    request-method :request-method
    query-string   :query-string
    tx             :tx}]
  (debug 'jobs-handler route-name request-method)
  (case route-name
    :jobs
    (case request-method
      :get (let [params (query-params/decode query-string)
                 jobs   (jdbc/execute! tx (build-query params))]
             {:status 200
              :body   {:jobs    jobs
                       :filters (select-keys params [:project :state])}})
      {:status 405 :body "Method not allowed"})
    {:status 500 :body "Unresolved route"}))
