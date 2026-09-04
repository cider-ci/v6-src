(ns cider-ci.server.resources.jobs.main
  (:require
    [cider-ci.utils.query-params :as query-params]
    [clojure.string :as str]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug]]))


(def ^:private page-size 25)

(defn- build-where [{:keys [project state]}]
  (let [clauses (cond-> []
                  (not (str/blank? project)) (conj "j.project_id = ?")
                  (not (str/blank? state))   (conj "j.state = ?"))
        params  (cond-> []
                  (not (str/blank? project)) (conj project)
                  (not (str/blank? state))   (conj state))]
    {:where  (if (seq clauses)
               (str "WHERE " (str/join " AND " clauses))
               "")
     :params params}))

(defn- order-clause [sort]
  (if (= sort "trial")
    "(SELECT MAX(t.created_at) FROM trials t JOIN tasks tk ON t.task_id = tk.id WHERE tk.job_id = j.id) DESC NULLS LAST, j.id DESC"
    "j.created_at DESC, j.id DESC"))

(defn- build-queries [{:keys [project state sort page]}]
  (let [{:keys [where params]} (build-where {:project project :state state})
        page-n  (try (max 1 (Integer/parseInt (or page "1"))) (catch Exception _ 1))
        offset  (* (dec page-n) page-size)
        order   (order-clause sort)]
    {:list-query  (into [(format "SELECT j.id::text, j.key, j.name, j.state,
                                         j.created_at, j.project_id, j.commit_id,
                                         r.name AS project_name,
                                         (SELECT MAX(t.created_at) FROM trials t
                                          JOIN tasks tk ON t.task_id = tk.id
                                          WHERE tk.job_id = j.id) AS last_trial_at
                                  FROM jobs j
                                  JOIN repositories r ON r.id = j.project_id
                                  %s
                                  ORDER BY %s
                                  LIMIT %d OFFSET %d"
                                 where order page-size offset)]
                        params)
     :count-query (into [(format "SELECT COUNT(*) AS total FROM jobs j
                                  JOIN repositories r ON r.id = j.project_id
                                  %s"
                                 where)]
                        params)
     :page        page-n}))


(defn handler
  [{route-name     :route-name
    request-method :request-method
    query-string   :query-string
    tx             :tx}]
  (debug 'jobs-handler route-name request-method)
  (case route-name
    :jobs
    (case request-method
      :get (let [params                                (query-params/decode query-string)
                 {:keys [list-query count-query page]} (build-queries params)
                 jobs                                  (jdbc/execute! tx list-query)
                 total                                 (:total (jdbc/execute-one! tx count-query))]
             {:status 200
              :body   {:jobs    jobs
                       :total   total
                       :page    page
                       :filters (select-keys params [:project :state :sort])}})
      {:status 405 :body "Method not allowed"})
    {:status 500 :body "Unresolved route"}))
