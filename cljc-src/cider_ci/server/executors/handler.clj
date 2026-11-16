(ns cider-ci.server.executors.handler
  (:require
    [cheshire.core :as json]
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.server.executors.auth :as auth]
    [cider-ci.server.jobs.propagation :as propagation]
    [cider-ci.server.projects.repositories.project-configuration.submodules :as submodules]
    [cider-ci.server.projects.repositories.shared :as repo-shared]
    [cider-ci.utils.accepted-gpg-keys :as accepted-gpg-keys]
    [cider-ci.utils.accepted-repositories :as accepted-repositories]
    [clojure.string :as str]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [warn]])
  (:import [java.io InputStream]))


(defonce ^:private git-proxies-cache* (atom {}))

(defn- git-proxies-for
  "{submodule-commit server-git-url} for the trial's commit (see
   submodules/git-proxies), so executors fetch submodules from this server
   whenever a configured repository holds them. Computed once per (project,
   commit): concurrent requests (a restarted executor asks for a whole
   batch at once) share one delay instead of walking the tree each. Cached
   for 5 minutes: a new project can make more submodules resolvable. Not
   called inside a request transaction (see db.core/no-tx-paths)."
  [project-id commit-id server-base-url]
  (let [k   [project-id commit-id]
        now (System/currentTimeMillis)
        hit (get @git-proxies-cache* k)]
    (if (and hit (< (- now (:at hit)) (* 5 60 1000)))
      (force (:value hit))
      (let [d (delay
                (let [m (try
                          (with-open [repo (repo-shared/file-repository (repo-shared/path {:project-id project-id}))]
                            (submodules/git-proxies repo commit-id))
                          (catch Exception e
                            (warn "git proxies for" project-id commit-id "failed:" (.getMessage e))
                            {}))]
                  (into {} (map (fn [[sha id]] [sha (str server-base-url "/projects/" id "/git")]) m))))]
        (when (> (count @git-proxies-cache*) 500) (reset! git-proxies-cache* {}))
        ;; whoever gets the entry in first wins; everyone forces that delay
        (force (:value (get (swap! git-proxies-cache* update k #(or % {:at now :value d})) k)))))))

(def ^:private candidates-query
  "The pending trials this executor may run, in dispatch order (legacy
   parity): highest job priority first, then the oldest job, then task
   priority and name, then the oldest trial. SKIP LOCKED makes concurrent
   syncs take the next trials in that order."
  "SELECT t.id, t.token, t.task_id,
          tsk.name AS task_name,
          tsk.spec AS task_spec,
          tsk.load AS task_load,
          j.id     AS job_id,
          j.key    AS job_key,
          j.commit_id,
          c.tree_id,
          j.project_id,
          r.git_url AS repository_git_url
   FROM trials t
   JOIN tasks    tsk ON tsk.id = t.task_id
   JOIN jobs     j   ON j.id   = tsk.job_id
   LEFT JOIN commits c ON c.id = j.commit_id
   JOIN repositories r ON r.id = j.project_id
   JOIN executors e  ON e.id   = ?::uuid
   WHERE t.state = 'pending'
     AND tsk.traits <@ e.traits
     AND cider_ci_trial_accepted(r.id, r.git_url, j.commit_id, j.key, e.accepted_repositories)
     AND cider_ci_signature_accepted(c.signature_fingerprint, e.accepted_gpg_keys)
     AND tsk.load <= ?
     AND (
       tsk.spec->>'dispatch_storm_delay_seconds' IS NULL
       OR NOT EXISTS (
         SELECT 1 FROM trials t2
         WHERE t2.task_id = t.task_id
           AND t2.dispatched_at > now() - (tsk.spec->>'dispatch_storm_delay_seconds')::float * interval '1 second'
       )
     )
   ORDER BY j.priority DESC, j.created_at ASC,
            tsk.priority DESC, tsk.name ASC,
            t.created_at ASC
   LIMIT ?
   FOR UPDATE OF t SKIP LOCKED")

(defn- dispatch-trials [tx executor available-load server-base-url]
  ;; One query for the candidates (the dispatch query scans every pending
  ;; trial, ~0.3 s with a few hundred of them: one per trial made a full
  ;; batch exceed the executor's sync timeout), then as many as fit the
  ;; load budget, in order. Whatever does not fit now comes with a later
  ;; sync.
  (let [budget     (double (or available-load 0.0))
        candidates (if (< budget 0.5)
                     []
                     (jdbc/execute! tx [candidates-query (:id executor) budget
                                        (int (Math/ceil budget))]))
        raw-trials (loop [remaining budget, candidates candidates, trials []]
                     (if-let [trial (first candidates)]
                       (let [load (double (or (:task_load trial) 1.0))]
                         (if (<= load remaining)
                           (do
                             (jdbc/execute-one! tx
                               ["UPDATE trials
                                 SET state = 'dispatching', executor_id = ?, dispatched_at = now(), updated_at = now()
                                 WHERE id = ?"
                                (:id executor) (:id trial)])
                             (recur (- remaining load) (rest candidates) (conj trials trial)))
                           (recur remaining (rest candidates) trials)))
                       trials))]
    (mapv (fn [t]
            {:id         (str (:id t))
             :token      (str (:token t))
             :task_id    (str (:task_id t))
             :task_name  (:task_name t)
             :task_spec  (:task_spec t)
             :job_id     (str (:job_id t))
             :job_key    (:job_key t)
             :commit_id  (:commit_id t)
             :tree_id    (:tree_id t)
             :project_id (:project_id t)
             ;; git_url: where the executor fetches from (server proxy, token
             ;; authenticated). repository_git_url: the project's real remote,
             ;; used as `origin` of the working dir (legacy parity) so trial
             ;; scripts can `git fetch origin ...` without executor credentials.
             :git_url    (str server-base-url "/projects/" (:project_id t) "/git")
             :repository_git_url (:repository_git_url t)
             ;; git_proxies_path: GET gives {submodule-commit git-url-on-this-server}
             ;; for the submodules a configured repository holds (recursively);
             ;; the executor fetches those from here instead of their
             ;; .gitmodules host. Fetched by the executor before the clone,
             ;; not computed here: it walks the submodule tree, too slow for
             ;; the sync when the cache is cold.
             :git_proxies_path (str "/executor/trials/" (:id t) "/git-proxies")
             :patch_path (str "/executor/trials/" (:id t))})
          raw-trials)))


(defn- handle-trial-git-proxies-get
  "Outside the request transaction (db.core/no-tx-paths): a short lookup,
   then the (possibly slow, shared) proxies computation without holding a
   pooled connection."
  [trial-id server-base-url]
  (if-let [{:keys [project_id commit_id]}
           (jdbc/execute-one! (get-ds)
             ["SELECT j.project_id, j.commit_id
               FROM trials t JOIN tasks tsk ON tsk.id = t.task_id JOIN jobs j ON j.id = tsk.job_id
               WHERE t.id = ?::uuid" trial-id])]
    {:status 200 :body (git-proxies-for project_id commit_id server-base-url)}
    {:status 404 :body "Trial not found"}))


;;; reconciliation ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; A trial dispatched this long ago that the executor does not report is lost
;; (the sync response never arrived, or the executor restarted). Shorter
;; and a trial still on its way into the executor's bookkeeping would count.
(def ^:private lost-after "30 seconds")

(defn- reconcile-lost-trials!
  "The trials assigned to the executor that it does not know about (not in
   trials_being_processed): dispatching ones go back to pending and are
   dispatched again at once, executing ones are defective, aborting ones
   aborted. Without this a batch lost to a timed-out sync blocked the
   executor's load for minutes and was retried only by the stale-trial
   daemon."
  [tx executor reported-ids]
  (let [known  (into-array String (map str reported-ids))
        reset  (jdbc/execute! tx
                 [(str "UPDATE trials
                        SET state = 'pending', executor_id = NULL, dispatched_at = NULL, updated_at = now()
                        WHERE executor_id = ?::uuid AND state = 'dispatching'
                          AND dispatched_at < now() - interval '" lost-after "'
                          AND NOT (id::text = ANY (?))
                        RETURNING id")
                  (:id executor) known])
        lost   (jdbc/execute! tx
                 [(str "UPDATE trials
                        SET state = CASE WHEN state = 'aborting' THEN 'aborted' ELSE 'defective' END,
                            error = 'Trial lost: the executor does not know it (restarted, or the dispatch never arrived)',
                            finished_at = now(), updated_at = now()
                        WHERE executor_id = ?::uuid AND state IN ('executing', 'aborting')
                          AND dispatched_at < now() - interval '" lost-after "'
                          AND NOT (id::text = ANY (?))
                        RETURNING id")
                  (:id executor) known])]
    (when (seq reset)
      (warn "Executor" (:name executor) "never received" (count reset) "dispatched trial(s); pending again"))
    (when (seq lost)
      (warn "Executor" (:name executor) "lost" (count lost) "running trial(s)")
      (doseq [{:keys [id]} lost]
        (propagation/propagate-from-trial tx id)))))

(defn- assigned-load
  "What the server has already given this executor (dispatching, executing
   or aborting), by its own books."
  [tx executor]
  (-> (jdbc/execute-one! tx
        ["SELECT coalesce(sum(tsk.load), 0) AS load
          FROM trials t JOIN tasks tsk ON tsk.id = t.task_id
          WHERE t.executor_id = ?::uuid AND t.state IN ('dispatching', 'executing', 'aborting')"
         (:id executor)])
      :load double))


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


(defn- handle-trial-tree-attachment-put [tx trial-id attachment-path request]
  (let [content-type (get-in request [:headers "content-type"] "application/octet-stream")
        body         (:body request)
        content      (if (instance? InputStream body)
                       (.readAllBytes ^InputStream body)
                       (.getBytes ^String (str body) "UTF-8"))
        tree-id      (-> (jdbc/execute! tx
                           ["SELECT c.tree_id
                             FROM trials t
                             JOIN tasks tsk ON tsk.id = t.task_id
                             JOIN jobs j    ON j.id   = tsk.job_id
                             JOIN commits c ON c.id   = j.commit_id
                             WHERE t.id = ?::uuid"
                            trial-id])
                         first
                         :tree_id)]
    (when-not tree-id
      (throw (ex-info "Trial not found" {:status 404})))
    (jdbc/execute-one! tx
      ["INSERT INTO tree_attachments (tree_id, path, content_type, content)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (tree_id, path)
        DO UPDATE SET content = EXCLUDED.content,
                      content_type = EXCLUDED.content_type,
                      updated_at = now()"
       tree-id attachment-path content-type content])
    {:status 201 :body {:path attachment-path}}))


(defn- traits-literal [traits]
  (str "{" (str/join "," (map str/lower-case traits)) "}"))


(defn- handle-sync [tx executor body server-base-url]
  (if-let [traits (seq (:traits body))]
    (jdbc/execute-one! tx
      ["UPDATE executors SET last_seen_at = now(), updated_at = now(), traits = CAST(? AS text[]) WHERE id = ?"
       (traits-literal traits) (:id executor)])
    (jdbc/execute-one! tx
      ["UPDATE executors SET last_seen_at = now(), updated_at = now() WHERE id = ?"
       (:id executor)]))
  ;; The executor's configured maximum load (CIDER_CI_EXECUTOR_MAX_LOAD); the
  ;; value given at registration is only a placeholder until the first sync.
  (when (number? (:max_load body))
    (let [max-load (double (:max_load body))]
      (jdbc/execute-one! tx
        ["UPDATE executors SET max_load = ? WHERE id = ? AND max_load IS DISTINCT FROM ?"
         max-load (:id executor) max-load])))
  (when (sequential? (:accepted_repositories body))
    (let [entries (json/generate-string
                    (accepted-repositories/normalize-entries (:accepted_repositories body)))]
      (jdbc/execute-one! tx
        ["UPDATE executors SET accepted_repositories = ?::jsonb
          WHERE id = ? AND accepted_repositories IS DISTINCT FROM ?::jsonb"
         entries (:id executor) entries])))
  (when (sequential? (:accepted_gpg_keys body))
    (let [entries (json/generate-string
                    (accepted-gpg-keys/normalize-entries (:accepted_gpg_keys body)))]
      (jdbc/execute-one! tx
        ["UPDATE executors SET accepted_gpg_keys = ?::jsonb
          WHERE id = ? AND accepted_gpg_keys IS DISTINCT FROM ?::jsonb"
         entries (:id executor) entries])))
  (when (sequential? (:trials_being_processed body))
    (reconcile-lost-trials! tx executor (:trials_being_processed body)))
  (let [reported-load  (double (or (:available_load body) 1.0))
        max-load       (double (or (:max_load body) (:max_load executor) 0.0))
        ;; The executor reports what it thinks is free; the server caps that
        ;; by its own books so that an executor that forgot its trials
        ;; (restart) or never got them (timed-out sync) cannot be given more
        ;; than its maximum.
        available-load (min reported-load (max 0.0 (- max-load (assigned-load tx executor))))
        ;; Disabled: stays in touch (the bookkeeping above) and may still
        ;; abort/finish what it runs, but is not given new trials.
        to-execute     (if (:enabled executor)
                         (dispatch-trials tx executor available-load server-base-url)
                         [])
        to-abort       (mapv (comp str :id)
                             (jdbc/execute! tx
                               ["SELECT t.id FROM trials t
                                 WHERE t.executor_id = ?::uuid
                                   AND t.state = 'aborting'"
                                (:id executor)]))]
    {:status 200
     :body   {:trials_to_execute      to-execute
              :trials_to_abort        to-abort
              :trials_being_processed (or (:trials_being_processed body) [])}}))


(defn- handle-trial-patch [tx trial-id body]
  (let [new-state       (:state body)
        error-msg       (:error body)
        scripts-results (:scripts_results body)
        exec-info       (:exec_info body)
        trial-uuid      (java.util.UUID/fromString trial-id)]
    (when-not new-state
      (throw (ex-info "Missing state" {:status 400})))
    (let [terminal?    #{"passed" "failed" "defective" "aborted"}
          ;; Merge into the existing result (jsonb ||) so intermediate patches
          ;; (live script states, exec_info) survive later ones.
          result-patch (cond-> {}
                         scripts-results (assoc :scripts scripts-results)
                         exec-info       (assoc :exec_info exec-info))
          set-map   (cond-> {:state new-state :updated_at [:raw "now()"]}
                      (#{"executing"} new-state)   (assoc :started_at  [:raw "COALESCE(started_at, now())"])
                      (terminal? new-state)         (assoc :finished_at [:raw "now()"])
                      error-msg                     (assoc :error error-msg)
                      (seq result-patch)            (assoc :result
                                                           [:|| [:coalesce :result [:lift {}]]
                                                                [:lift result-patch]]))
          result    (jdbc/execute-one! tx
                      (-> (sql/update :trials)
                          (sql/set set-map)
                          (sql/where [:= :id trial-uuid])
                          sql-format))]
      (when (zero? (:next.jdbc/update-count result))
        (throw (ex-info "Trial not found" {:status 404}))))
    (propagation/propagate-from-trial tx trial-uuid)
    {:status 200
     :body   {:state new-state}}))


(defn handler [{tx             :tx
                route-name     :route-name
                request-method :request-method
                body           :body
                headers        :headers
                scheme         :scheme
                server-name    :server-name
                server-port    :server-port
                {{:keys [trial-id attachment-path]} :path-params} :route
                :as request}]
  (let [auth-header      (get headers "authorization")
        forwarded-proto  (some-> headers (get "x-forwarded-proto") keyword)
        public-scheme    (or forwarded-proto scheme :http)
        ; When behind a proxy, trust the proxy owns the port mapping — omit it.
        ; Without a proxy, omit port only when it matches the scheme default.
        default-port?    (or (some? forwarded-proto)
                             (and (= public-scheme :https) (= server-port 443))
                             (and (= public-scheme :http)  (= server-port 80)))
        server-base-url  (str (name public-scheme) "://" server-name
                              (when-not default-port? (str ":" server-port)))]
    ;; no tx for the no-tx paths (db.core): the lookup takes its own connection
    (if-let [executor (auth/find-executor (or tx (get-ds)) auth-header)]
      (case route-name
        :executor-sync
        (case request-method
          :post (handle-sync tx executor body server-base-url)
          {:status 405 :body "Method not allowed"})

        :executor-trial
        (case request-method
          :patch (handle-trial-patch tx trial-id body)
          {:status 405 :body "Method not allowed"})

        :executor-trial-git-proxies
        (case request-method
          :get (handle-trial-git-proxies-get trial-id server-base-url)
          {:status 405 :body "Method not allowed"})

        :executor-trial-attachment
        (case request-method
          :put (handle-trial-attachment-put tx trial-id attachment-path request)
          {:status 405 :body "Method not allowed"})

        :executor-trial-tree-attachment
        (case request-method
          :put (handle-trial-tree-attachment-put tx trial-id attachment-path request)
          {:status 405 :body "Method not allowed"})

        {:status 500 :body "Unresolved route"})
      {:status 401 :body "Unauthorized: valid executor token required"})))
