(ns cider-ci.server.resources.projects.jobs
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.server.jobs.decompose :as decompose]
    [cider-ci.server.jobs.generate :as generate]
    [cider-ci.server.projects.repositories.project-configuration.direct :as config]
    [cider-ci.server.projects.repositories.shared :as shared]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.sql :as jdbc-sql]
    [taoensso.timbre :refer [warn]])
  (:import [org.eclipse.jgit.revwalk RevWalk]))

(defn- full-job-configs [repo commit-id]
  (try
    (let [cfg      (config/build repo commit-id)
          jobs-map (or (:jobs cfg) {})]
      (->> jobs-map
           (map (fn [[k v]]
                  {:key       (name k)
                   :name      (or (:name v) (name k))
                   :full-spec v}))
           vec))
    (catch clojure.lang.ExceptionInfo e
      (if (= 404 (:status (ex-data e)))
        []
        (throw e)))))

(defn- dep-satisfied? [dep created-by-key]
  (let [dep-type (some-> dep :type name)
        job-key  (:job_key dep)
        states   (set (map name (:states dep)))]
    (cond
      (or (and dep-type (not= dep-type "job"))
          (seq (:submodule dep)))
      false
      :else
      (let [existing (get created-by-key (name job-key))]
        (and existing (contains? states (:state existing)))))))

(defn- annotate-job [job-entry created-by-key]
  (let [key-str       (:key job-entry)
        depends-on    (:depends_on (:full-spec job-entry))
        has-instance? (contains? created-by-key key-str)
        unmet-deps    (when (and (not has-instance?) depends-on)
                        (->> depends-on
                             (remove (fn [[_ dep]] (dep-satisfied? dep created-by-key)))
                             (map (fn [[dep-name _]] (name dep-name)))
                             vec))
        runnable?     (and (not has-instance?) (empty? unmet-deps))
        dep-job-keys  (->> depends-on
                           vals
                           (filter #(= "job" (:type %)))
                           (keep :job_key)
                           (map name)
                           vec)]
    {:key          key-str
     :name         (:name job-entry)
     :runnable     runnable?
     :has_instance has-instance?
     :unmet_deps   (or unmet-deps [])
     :dep_job_keys dep-job-keys}))

(defn- available-jobs [repo commit-id created]
  (let [created-by-key (into {} (map (fn [j] [(:key j) j]) created))]
    (->> (full-job-configs repo commit-id)
         (map #(annotate-job % created-by-key)))))

(defn- created-jobs [project-id commit-id]
  (->> (-> (sql/select :id :key :name :state :created_at)
           (sql/from :jobs)
           (sql/where [:= :project_id project-id])
           (sql/where [:= :commit_id commit-id])
           (sql/order-by [:created_at :desc])
           sql-format)
       (jdbc-sql/query (get-ds))))

(defn- create-job [repo project-id commit-id body session]
  (let [job-key (:key body)]
    (when-not (seq job-key)
      {:status 400 :body "Missing job key"})
    (let [all-configs (full-job-configs repo commit-id)
          job-entry   (some #(when (= (:key %) job-key) %) all-configs)]
      (if-not job-entry
        {:status 404 :body "Job not found in configuration"}
        (let [created-by (get-in session [:user :id])
              full-spec  (generate/expand project-id commit-id (:full-spec job-entry))
              task-specs (decompose/decompose full-spec)
              new-job-id (java.util.UUID/randomUUID)]
          ;; Ensure the commit row exists with tree_id so tree attachments work.
          ;; Done outside the job transaction to avoid deadlock with the fetch daemon.
          (when-let [oid (.resolve repo (str commit-id "^{commit}"))]
            (let [tree-id (.. (RevWalk. repo) (parseCommit oid) getTree getName)]
              (jdbc/execute-one! (get-ds)
                ["INSERT INTO commits (id, tree_id) VALUES (?, ?)
                  ON CONFLICT (id) DO UPDATE SET tree_id = EXCLUDED.tree_id
                  WHERE commits.tree_id IS NULL"
                 commit-id tree-id])))
          (try
            (jdbc/with-transaction [tx (get-ds)]
              (jdbc/execute-one! tx
                (-> (sql/insert-into :jobs)
                    (sql/values [{:id          new-job-id
                                  :project_id  project-id
                                  :commit_id   commit-id
                                  :jobs/key    job-key
                                  :jobs/name   (:name job-entry)
                                  :state       "pending"
                                  :spec        [:lift full-spec]
                                  :created_by  created-by}])
                    sql-format))
              (doseq [task-spec task-specs]
                (let [new-task-id (java.util.UUID/randomUUID)]
                  (jdbc/execute-one! tx
                    (-> (sql/insert-into :tasks)
                        (sql/values [{:id         new-task-id
                                      :job_id     new-job-id
                                      :tasks/name (:name task-spec)
                                      :state      "pending"
                                      :spec       [:lift task-spec]}])
                        sql-format))
                  (let [eager    (or (:eager_trials task-spec) 1)
                        max-t    (or (:max_trials task-spec) 2)
                        n-trials (max 1 (min eager max-t))]
                    (doseq [_ (range n-trials)]
                      (jdbc/execute-one! tx
                        (-> (sql/insert-into :trials)
                            (sql/values [{:task_id new-task-id
                                          :state   "pending"}])
                            sql-format)))))))
            {:status 201 :body {:id    new-job-id
                                :key   job-key
                                :name  (:name job-entry)
                                :state "pending"}}
            (catch org.postgresql.util.PSQLException e
              (if (= "23505" (.getSQLState e))
                {:status 409 :body "A job with this key already exists for this commit"}
                (throw e)))))))))

(defn- get-trials-for-task [task-id]
  (jdbc-sql/query (get-ds)
    (-> (sql/select :id :state :started_at :finished_at :error :result)
        (sql/from :trials)
        (sql/where [:= :task_id task-id])
        (sql/order-by [:created_at :asc])
        sql-format)))

(defn- get-job-with-tasks [project-id job-id]
  (when-let [job (first (jdbc-sql/query (get-ds)
                          (-> (sql/select :*)
                              (sql/from :jobs)
                              (sql/where [:= :id (java.util.UUID/fromString job-id)])
                              (sql/where [:= :project_id project-id])
                              sql-format)))]
    (let [tasks (mapv (fn [t]
                        (assoc t :trials (get-trials-for-task (:id t))))
                      (jdbc-sql/query (get-ds)
                        (-> (sql/select :id :name :state :created_at :spec)
                            (sql/from :tasks)
                            (sql/where [:= :job_id (:id job)])
                            (sql/order-by [:created_at :asc])
                            sql-format)))]
      (assoc job :tasks tasks))))

(defn- get-task-with-trials [project-id job-id task-id]
  (when-let [task (first (jdbc-sql/query (get-ds)
                           (-> (sql/select :t.id :t.name :t.state :t.spec :t.created_at)
                               (sql/from [:tasks :t])
                               (sql/join [:jobs :j] [:= :j.id :t.job_id])
                               (sql/where [:= :t.id (java.util.UUID/fromString task-id)])
                               (sql/where [:= :t.job_id (java.util.UUID/fromString job-id)])
                               (sql/where [:= :j.project_id project-id])
                               sql-format)))]
    (assoc task :trials (get-trials-for-task (:id task)))))


(defn- abort-job! [project-id job-id]
  (let [job-uuid (java.util.UUID/fromString job-id)]
    (when-not (first (jdbc-sql/query (get-ds)
                       (-> (sql/select :id)
                           (sql/from :jobs)
                           (sql/where [:= :id job-uuid])
                           (sql/where [:= :project_id project-id])
                           sql-format)))
      (throw (ex-info "Job not found" {:status 404})))
    (jdbc/with-transaction [tx (get-ds)]
      ;; Pending trials have no executor — abort them immediately.
      (jdbc/execute! tx
        ["UPDATE trials t SET state = 'aborted', updated_at = now()
          FROM tasks tsk WHERE t.task_id = tsk.id AND tsk.job_id = ?
            AND t.state = 'pending'"
         job-uuid])
      ;; Active trials (dispatching/executing) — signal the executor to abort.
      (jdbc/execute! tx
        ["UPDATE trials t SET state = 'aborting', updated_at = now()
          FROM tasks tsk WHERE t.task_id = tsk.id AND tsk.job_id = ?
            AND t.state IN ('dispatching', 'executing')"
         job-uuid])
      ;; Propagate aborting/aborted state up to tasks.
      (jdbc/execute! tx
        ["UPDATE tasks
          SET state = CASE
            WHEN EXISTS (SELECT 1 FROM trials WHERE task_id = tasks.id AND state = 'aborting')
            THEN 'aborting'
            ELSE 'aborted'
          END,
          updated_at = now()
          WHERE job_id = ?
            AND state NOT IN ('passed', 'failed', 'defective', 'aborted', 'aborting')"
         job-uuid])
      (jdbc/execute-one! tx
        ["UPDATE jobs SET state = 'aborting', updated_at = now()
          WHERE id = ? AND state NOT IN ('passed', 'failed', 'defective', 'aborted')"
         job-uuid]))
    {:status 200 :body {:status "aborting"}}))


(defn- retry-job! [project-id job-id]
  (let [job-uuid (java.util.UUID/fromString job-id)]
    (when-not (first (jdbc-sql/query (get-ds)
                       (-> (sql/select :id)
                           (sql/from :jobs)
                           (sql/where [:= :id job-uuid])
                           (sql/where [:= :project_id project-id])
                           sql-format)))
      (throw (ex-info "Job not found" {:status 404})))
    (jdbc/with-transaction [tx (get-ds)]
      (jdbc/execute! tx
        ["UPDATE trials t
          SET state = 'pending', executor_id = NULL, dispatched_at = NULL,
              started_at = NULL, finished_at = NULL, error = NULL, result = NULL, updated_at = now()
          FROM tasks tsk
          WHERE t.task_id = tsk.id AND tsk.job_id = ?
            AND t.state NOT IN ('pending', 'passed')"
         job-uuid])
      (jdbc/execute! tx
        ["UPDATE tasks SET state = 'pending', updated_at = now()
          WHERE job_id = ? AND state NOT IN ('pending', 'passed')"
         job-uuid])
      (jdbc/execute! tx
        ["UPDATE jobs SET state = 'pending', updated_at = now()
          WHERE id = ? AND state NOT IN ('pending', 'passed')"
         job-uuid]))
    {:status 200 :body {:status "retrying"}}))


(defn- retry-task! [project-id job-id task-id]
  (let [task-uuid (java.util.UUID/fromString task-id)
        job-uuid  (java.util.UUID/fromString job-id)]
    (when-not (first (jdbc-sql/query (get-ds)
                       (-> (sql/select :id)
                           (sql/from :tasks)
                           (sql/where [:= :id task-uuid])
                           (sql/where [:= :job_id job-uuid])
                           sql-format)))
      (throw (ex-info "Task not found" {:status 404})))
    (jdbc/with-transaction [tx (get-ds)]
      (jdbc/execute-one! tx
        (-> (sql/insert-into :trials)
            (sql/values [{:task_id task-uuid :state "pending"}])
            sql-format))
      (jdbc/execute-one! tx
        ["UPDATE tasks SET state = 'pending', updated_at = now()
          WHERE id = ? AND state NOT IN ('pending', 'passed')"
         task-uuid])
      (jdbc/execute-one! tx
        ["UPDATE jobs SET state = 'executing', updated_at = now()
          WHERE id = ? AND state IN ('failed', 'defective', 'aborted')"
         job-uuid]))
    {:status 200 :body {:status "retrying"}}))


(defn handler [{{{:keys [project-id commit-id job-id task-id]} :path-params} :route
                route-name    :route-name
                request-method :request-method
                body           :body
                session        :session}]
  (case route-name
    :project-job
    (case request-method
      :get (if-let [result (get-job-with-tasks project-id job-id)]
             {:status 200 :body result}
             {:status 404 :body "Job not found"})
      {:status 405 :body "Method not allowed"})

    :project-job-task
    (case request-method
      :get (if-let [result (get-task-with-trials project-id job-id task-id)]
             {:status 200 :body result}
             {:status 404 :body "Task not found"})
      {:status 405 :body "Method not allowed"})

    :project-job-abort
    (case request-method
      :post (abort-job! project-id job-id)
      {:status 405 :body "Method not allowed"})

    :project-job-retry
    (case request-method
      :post (retry-job! project-id job-id)
      {:status 405 :body "Method not allowed"})

    :project-job-task-retry
    (case request-method
      :post (retry-task! project-id job-id task-id)
      {:status 405 :body "Method not allowed"})

    :project-jobs
    (with-open [repo (shared/file-repository (shared/path {:project-id project-id}))]
      (case request-method
        :get  (let [created (created-jobs project-id commit-id)]
                {:status 200
                 :body   {:available (available-jobs repo commit-id created)
                          :created   created}})
        :post (create-job repo project-id commit-id body session)
        {:status 405 :body "Method not allowed"}))

    {:status 500 :body "Unresolved route"}))
