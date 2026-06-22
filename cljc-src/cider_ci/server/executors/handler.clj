(ns cider-ci.server.executors.handler
  (:require
    [cider-ci.server.projects.repositories.shared :as repo-shared]
    [clojure.string :as str]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [next.jdbc.sql :as jdbc-sql]
    [taoensso.timbre :refer [warn]])
  (:import [java.io InputStream]))


(defn- sha256 [^String s]
  (format "%064x"
    (BigInteger. 1
      (.digest (java.security.MessageDigest/getInstance "SHA-256")
               (.getBytes s "UTF-8")))))


(defn- find-executor [tx auth-header]
  (when (and auth-header (str/starts-with? auth-header "Bearer "))
    (let [token      (subs auth-header 7)
          token-hash (sha256 token)]
      (first (jdbc-sql/query tx
               (-> (sql/select :*)
                   (sql/from :executors)
                   (sql/where [:= :token_hash token-hash])
                   (sql/where [:= :enabled true])
                   sql-format))))))


(defn- dispatch-trials [tx executor available-load]
  (let [limit  (max 1 (int (or available-load 1.0)))
        trials (jdbc/execute! tx
                 ["SELECT t.id, t.token, t.task_id,
                          tsk.name     AS task_name,
                          tsk.spec     AS task_spec,
                          j.id         AS job_id,
                          j.commit_id,
                          j.project_id
                   FROM trials t
                   JOIN tasks    tsk ON tsk.id = t.task_id
                   JOIN jobs     j   ON j.id   = tsk.job_id
                   JOIN executors e  ON e.id   = ?::uuid
                   WHERE t.state = 'pending'
                     AND tsk.traits <@ e.traits
                     AND tsk.load <= ?
                   LIMIT ?
                   FOR UPDATE OF t SKIP LOCKED"
                  (:id executor) available-load limit])]
    (doseq [trial trials]
      (jdbc/execute-one! tx
        ["UPDATE trials
          SET state = 'dispatching', executor_id = ?, dispatched_at = now(), updated_at = now()
          WHERE id = ?"
         (:id executor) (:id trial)]))
    (mapv (fn [t]
            {:id         (str (:id t))
             :token      (str (:token t))
             :task_id    (str (:task_id t))
             :task_name  (:task_name t)
             :task_spec  (:task_spec t)
             :job_id     (str (:job_id t))
             :commit_id  (:commit_id t)
             :project_id (:project_id t)
             :git_url    (str "file://" (repo-shared/repository-fs-path (:project_id t)))
             :patch_path (str "/executor/trials/" (:id t))})
          trials)))


(def ^:private terminal-states #{"passed" "failed" "defective" "aborted"})

(defn- terminal-new-state [states]
  (cond
    (not-every? terminal-states states) nil
    (every? #(= "passed" %) states)     "passed"
    :else                               "failed"))


(defn- propagate-from-task [tx task-id]
  (let [task      (first (jdbc-sql/query tx
                           (-> (sql/select :job_id)
                               (sql/from :tasks)
                               (sql/where [:= :id task-id])
                               sql-format)))
        job-id    (:job_id task)
        all-tasks (jdbc-sql/query tx
                    (-> (sql/select :state)
                        (sql/from :tasks)
                        (sql/where [:= :job_id job-id])
                        sql-format))
        states    (map :state all-tasks)
        new-state (terminal-new-state states)]
    (when new-state
      (jdbc/execute-one! tx
        ["UPDATE jobs SET state = ?, updated_at = now() WHERE id = ?"
         new-state job-id]))))


(defn- advance-to-executing [tx task-id]
  (let [updated (jdbc/execute-one! tx
                  ["UPDATE tasks SET state = 'executing', updated_at = now()
                    WHERE id = ? AND state = 'pending'"
                   task-id])]
    (when (pos? (:next.jdbc/update-count updated))
      (let [task (first (jdbc-sql/query tx
                          (-> (sql/select :job_id)
                              (sql/from :tasks)
                              (sql/where [:= :id task-id])
                              sql-format)))]
        (jdbc/execute-one! tx
          ["UPDATE jobs SET state = 'executing', updated_at = now()
            WHERE id = ? AND state = 'pending'"
           (:job_id task)])))))


(defn- propagate-from-trial [tx trial-id]
  (let [trial      (first (jdbc-sql/query tx
                            (-> (sql/select :task_id)
                                (sql/from :trials)
                                (sql/where [:= :id trial-id])
                                sql-format)))
        task-id    (:task_id trial)
        all-trials (jdbc-sql/query tx
                     (-> (sql/select :state)
                         (sql/from :trials)
                         (sql/where [:= :task_id task-id])
                         sql-format))
        states     (map :state all-trials)
        new-state  (terminal-new-state states)]
    (if new-state
      (do
        (jdbc/execute-one! tx
          ["UPDATE tasks SET state = ?, updated_at = now() WHERE id = ?"
           new-state task-id])
        (propagate-from-task tx task-id))
      (when (some #(= "executing" %) states)
        (advance-to-executing tx task-id)))))


(defn- handle-trial-attachment-put [tx trial-id attachment-path request]
  (let [trial-uuid   (java.util.UUID/fromString trial-id)
        content-type (get-in request [:headers "content-type"] "application/octet-stream")
        body         (:body request)
        content      (if (instance? InputStream body)
                       (.readAllBytes ^InputStream body)
                       (.getBytes ^String (str body) "UTF-8"))]
    (jdbc/execute-one! tx
      ["INSERT INTO trial_attachments (trial_id, path, content_type, content)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (trial_id, path)
        DO UPDATE SET content = EXCLUDED.content,
                      content_type = EXCLUDED.content_type,
                      updated_at = now()"
       trial-uuid attachment-path content-type content])
    {:status 201 :body {:path attachment-path}}))


(defn- handle-sync [tx executor body]
  (jdbc/execute-one! tx
    ["UPDATE executors SET last_seen_at = now(), updated_at = now() WHERE id = ?"
     (:id executor)])
  (let [available-load (or (:available_load body) 1.0)
        to-execute     (dispatch-trials tx executor available-load)]
    {:status 200
     :body   {:trials_to_execute      to-execute
              :trials_being_processed []}}))


(defn- handle-trial-patch [tx trial-id body]
  (let [new-state  (:state body)
        error-msg  (:error body)
        trial-uuid (java.util.UUID/fromString trial-id)]
    (when-not new-state
      (throw (ex-info "Missing state" {:status 400})))
    (let [terminal? #{"passed" "failed" "defective" "aborted"}
          set-map   (cond-> {:state new-state :updated_at [:raw "now()"]}
                      (#{"executing"} new-state)   (assoc :started_at  [:raw "now()"])
                      (terminal? new-state)         (assoc :finished_at [:raw "now()"])
                      error-msg                     (assoc :error error-msg))
          result    (jdbc/execute-one! tx
                      (-> (sql/update :trials)
                          (sql/set set-map)
                          (sql/where [:= :id trial-uuid])
                          sql-format))]
      (when (zero? (:next.jdbc/update-count result))
        (throw (ex-info "Trial not found" {:status 404}))))
    (propagate-from-trial tx trial-uuid)
    {:status 200
     :body   {:state new-state}}))


(defn handler [{tx             :tx
                route-name     :route-name
                request-method :request-method
                body           :body
                headers        :headers
                {{:keys [trial-id attachment-path]} :path-params} :route
                :as request}]
  (let [auth-header (get headers "authorization")]
    (if-let [executor (find-executor tx auth-header)]
      (case route-name
        :executor-sync
        (case request-method
          :post (handle-sync tx executor body)
          {:status 405 :body "Method not allowed"})

        :executor-trial
        (case request-method
          :patch (handle-trial-patch tx trial-id body)
          {:status 405 :body "Method not allowed"})

        :executor-trial-attachment
        (case request-method
          :put (handle-trial-attachment-put tx trial-id attachment-path request)
          {:status 405 :body "Method not allowed"})

        {:status 500 :body "Unresolved route"})
      {:status 401 :body "Unauthorized: valid executor token required"})))
