(ns cider-ci.server.resources.projects.project
  (:require
    [cider-ci.server.projects.repositories.state.db :refer [db*] :rename {db* repo-state-db*}]
    [cider-ci.server.status-pushes.github :as github]
    [clojure.string :as str]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]))


(defn- repository-sql [project-id]
  (-> (sql/select
        :id :name :git_url
        :update_notification_token
        :branch_trigger_include_match :branch_trigger_exclude_match
        [[:raw "branch_trigger_max_commit_age::text"] :branch_trigger_max_commit_age]
        [[:raw "remote_fetch_interval::text"] :remote_fetch_interval]
        :public_view_permission
        :send_status_notifications
        :remote_api_endpoint :remote_api_namespace :remote_api_name :remote_api_type
        ;; the token itself is never returned
        [[:raw "remote_api_token IS NOT NULL"] :remote_api_token_set]
        :created_at :updated_at)
      (sql/from :repositories)
      (sql/where [:= :id project-id])))


(defn- branches-sql [project-id]
  (-> (sql/select
        :b.id :b.name :b.current_commit_id :b.updated_at
        [:c.committer_date :commit_committer_date]
        [:c.author_date    :commit_author_date]
        [:c.subject        :commit_subject]
        [:c.author_name    :commit_author_name]
        [:c.signature_fingerprint :commit_signature_fingerprint]
        [:r.state      :submodules_state]
        [:r.total      :submodules_total]
        [:r.unresolved :submodules_unresolved])
      (sql/from [:branches :b])
      (sql/left-join [:commits :c] [:= :c.id :b.current_commit_id])
      (sql/left-join [:commit_submodule_resolutions :r]
                     [:and [:= :r.commit_id :b.current_commit_id] [:= :r.repository_id :b.repository_id]])
      (sql/where [:= :b.repository_id project-id])
      (sql/order-by [:c.committer_date :is-null] [:c.committer_date :desc])))


(defn- pending-branch-triggers-sql [project-id]
  (-> (sql/select :branch_name :commit_id :error :attempts :created_at :updated_at)
      (sql/from :pending_branch_triggers)
      (sql/where [:= :repository_id project-id])
      (sql/order-by [:created_at :desc])))

(defn- status-pushes-summary
  "Where the project's job states are posted (or why not) and how the posts
   of the last 24 hours went."
  [tx {:keys [send_status_notifications remote_api_token_set] :as repo}]
  (let [remote (github/remote (assoc repo :remote_api_token (when remote_api_token_set "set")))
        counts (jdbc/execute-one! tx
                 ["SELECT count(*) FILTER (WHERE p.last_error IS NULL) AS posted,
                          count(*) FILTER (WHERE p.last_error IS NOT NULL) AS errors,
                          max(p.posted_at) AS last_posted_at
                   FROM job_status_pushes p JOIN jobs j ON j.id = p.job_id
                   WHERE j.project_id = ? AND p.updated_at > now() - interval '24 hours'"
                  (:id repo)])
        last-error (jdbc/execute-one! tx
                     ["SELECT p.last_error, p.last_error_at, p.attempts, p.next_attempt_at,
                              j.id AS job_id, j.commit_id, j.name AS job_name
                       FROM job_status_pushes p JOIN jobs j ON j.id = p.job_id
                       WHERE j.project_id = ? AND p.last_error IS NOT NULL
                         AND p.updated_at > now() - interval '24 hours'
                       ORDER BY p.last_error_at DESC LIMIT 1"
                      (:id repo)])]
    (merge {:enabled (boolean (and send_status_notifications remote_api_token_set))
            :error   (:error remote)}
           (when-not (:error remote)
             {:endpoint   (:endpoint remote)
              :repository (str (:owner remote) "/" (:name remote))})
           counts
           {:last_error last-error})))

(defn- get-handler [tx project-id]
  (if-let [repo (jdbc/execute-one! tx (sql-format (repository-sql project-id)))]
    (let [branches (jdbc/execute! tx (sql-format (branches-sql project-id)))
          pending  (jdbc/execute! tx (sql-format (pending-branch-triggers-sql project-id)))
          repo-state (some-> @repo-state-db*
                             (get-in [:repositories (keyword project-id)])
                             (select-keys [:fetch-and-update :branch-updates]))]
      {:body (merge repo repo-state {:branches branches
                                     :pending_branch_triggers pending
                                     :status_pushes (status-pushes-summary tx repo)})})
    {:status 404 :body "Project not found"}))

(defn- delete-handler [tx project-id]
  (let [deleted (jdbc/execute-one! tx (sql-format
                                        (-> (sql/delete-from :repositories)
                                            (sql/where [:= :id project-id])
                                            (sql/returning :id))))]
    (if deleted
      {:status 200 :body {:id project-id}}
      {:status 404 :body "Project not found"})))

(def ^:private status-push-keys
  #{:send_status_notifications :remote_api_endpoint :remote_api_namespace
    :remote_api_name :remote_api_token :remote_api_token_clear})

(defn- status-push-updates
  "Blank values become NULL (the columns reject empty strings); the token is
   write-only: a non-blank value sets it, remote_api_token_clear removes it."
  [body]
  (let [text (fn [k] (some-> (get body k) str str/trim not-empty))]
    (cond-> {}
      (contains? body :send_status_notifications)
      (assoc :send_status_notifications (boolean (:send_status_notifications body)))
      (contains? body :remote_api_endpoint)  (assoc :remote_api_endpoint (text :remote_api_endpoint))
      (contains? body :remote_api_namespace) (assoc :remote_api_namespace (text :remote_api_namespace))
      (contains? body :remote_api_name)      (assoc :remote_api_name (text :remote_api_name))
      (text :remote_api_token)               (assoc :remote_api_token (text :remote_api_token))
      (:remote_api_token_clear body)         (assoc :remote_api_token nil))))

(defn- retry-status-push-errors!
  "After the push settings changed: failed or skipped posts are retried now."
  [tx project-id]
  (jdbc/execute-one! tx
    ["UPDATE job_status_pushes SET job_state = NULL, next_attempt_at = NULL, updated_at = now()
      WHERE last_error IS NOT NULL
        AND job_id IN (SELECT id FROM jobs WHERE project_id = ?)" project-id]))

(defn- patch-handler [tx project-id body]
  (let [name     (some-> body :name str/trim)
        git-url  (some-> body :git_url str/trim)
        include  (get body :branch_trigger_include_match)
        exclude  (get body :branch_trigger_exclude_match)
        max-age  (some-> body :branch_trigger_max_commit_age str/trim not-empty)
        interval (some-> body :remote_fetch_interval str/trim not-empty)]
    (when (str/blank? name)
      (throw (ex-info "Name is required" {:status 422})))
    (when (str/blank? git-url)
      (throw (ex-info "Git URL is required" {:status 422})))
    (try
      (let [updates (cond-> {:name name :git_url git-url}
                      (some? include)  (assoc :branch_trigger_include_match include)
                      (some? exclude)  (assoc :branch_trigger_exclude_match exclude)
                      (some? max-age)  (assoc :branch_trigger_max_commit_age max-age)
                      (some? interval) (assoc :remote_fetch_interval interval)
                      true             (merge (status-push-updates body)))
            updated (jdbc/execute-one! tx
                      (sql-format
                        (-> (sql/update :repositories)
                            (sql/set updates)
                            (sql/where [:= :id project-id])
                            (sql/returning :id))))]
        (when (and updated (some #(contains? body %) status-push-keys))
          (retry-status-push-errors! tx project-id))
        (if updated
          {:status 200 :body {:id project-id}}
          {:status 404 :body "Project not found"}))
      (catch java.sql.SQLException e
        (if (= "23505" (.getSQLState e))
          (throw (ex-info "Git URL already used by another project" {:status 422}))
          (throw e))))))

(defn handler [{{{project-id :project-id} :path-params} :route
                tx :tx
                request-method :request-method
                body :body}]
  (case request-method
    :get    (get-handler tx project-id)
    :patch  (patch-handler tx project-id body)
    :delete (delete-handler tx project-id)))
