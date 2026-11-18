(ns cider-ci.server.status-pushes.main
  "Posts the state of jobs as GitHub commit statuses (legacy parity: every
   state change, pending included). Polls jobs updated within the last 24
   hours whose state differs from the one last handled in job_status_pushes;
   failed posts are retried with backoff. Only projects with a token and
   send_status_notifications push."
  (:require
    [cider-ci.server.db.core :refer [get-ds]]
    [cider-ci.server.db.settings :as settings]
    [cider-ci.server.status-pushes.github :as github]
    [cider-ci.utils.daemon :refer [defdaemon]]
    [cheshire.core :as json]
    [next.jdbc :as jdbc]
    [org.httpkit.client :as http-client]
    [taoensso.timbre :refer [info warn]]))


(def ^:private batch-size 50)

(defn- jobs-to-push [ds]
  (jdbc/execute! ds
    ["SELECT j.id, j.project_id, j.commit_id, j.key, j.name, j.state,
             r.git_url, r.remote_api_endpoint, r.remote_api_namespace,
             r.remote_api_name, r.remote_api_type, r.remote_api_token,
             r.github_status_context
      FROM jobs j
      JOIN repositories r ON r.id = j.project_id
      LEFT JOIN job_status_pushes p ON p.job_id = j.id
      WHERE r.send_status_notifications
        AND r.remote_api_token IS NOT NULL
        AND (j.updated_at > now() - interval '24 hours' OR p.repost_requested_at IS NOT NULL)
        AND (p.job_id IS NULL OR p.job_state IS DISTINCT FROM j.state)
        AND (p.next_attempt_at IS NULL OR p.next_attempt_at <= now())
      ORDER BY j.updated_at ASC
      LIMIT ?" batch-size]))

(defn- record-posted! [ds job-id job-state github-state]
  (jdbc/execute-one! ds
    ["INSERT INTO job_status_pushes (job_id, job_state, github_state, posted_at)
      VALUES (?, ?, ?, now())
      ON CONFLICT (job_id) DO UPDATE
      SET job_state = EXCLUDED.job_state, github_state = EXCLUDED.github_state,
          posted_at = now(), last_error = NULL, last_error_at = NULL,
          attempts = 0, next_attempt_at = NULL, repost_requested_at = NULL, updated_at = now()"
     job-id job-state github-state]))

(defn- record-skipped!
  "The project cannot push (e.g. no endpoint): not retried until the job
   state or the project's push settings change."
  [ds job-id job-state error]
  (jdbc/execute-one! ds
    ["INSERT INTO job_status_pushes (job_id, job_state, last_error, last_error_at)
      VALUES (?, ?, ?, now())
      ON CONFLICT (job_id) DO UPDATE
      SET job_state = EXCLUDED.job_state, last_error = EXCLUDED.last_error,
          last_error_at = now(), attempts = 0, next_attempt_at = NULL,
          repost_requested_at = NULL, updated_at = now()"
     job-id job-state error]))

(defn- record-failed!
  "Keeps the last handled state so the post is retried, after 10 s doubling
   up to 1 h."
  [ds job-id error]
  (jdbc/execute-one! ds
    ["INSERT INTO job_status_pushes (job_id, attempts, last_error, last_error_at, next_attempt_at)
      VALUES (?, 1, ?, now(), now() + interval '10 seconds')
      ON CONFLICT (job_id) DO UPDATE
      SET attempts = job_status_pushes.attempts + 1, last_error = EXCLUDED.last_error,
          last_error_at = now(),
          next_attempt_at = now() + make_interval(secs => least(10 * power(2, job_status_pushes.attempts), 3600)),
          updated_at = now()"
     job-id error]))

;;; re-posting ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request-repost!
  "Marks the jobs of the current branch heads (filters: project, branch) of
   the projects that push for re-posting, whatever their age; the daemon
   posts their current state again within seconds. Returns how many jobs."
  [ds & {:keys [project-id branch-name]}]
  (let [sql (str "INSERT INTO job_status_pushes (job_id, repost_requested_at)
                  SELECT DISTINCT j.id, now()
                  FROM jobs j
                  JOIN branches b ON b.repository_id = j.project_id AND b.current_commit_id = j.commit_id
                  JOIN repositories r ON r.id = j.project_id
                  WHERE r.send_status_notifications AND r.remote_api_token IS NOT NULL"
                 (when project-id " AND j.project_id = ?")
                 (when branch-name " AND b.name = ?")
                 " ON CONFLICT (job_id) DO UPDATE
                  SET job_state = NULL, attempts = 0, next_attempt_at = NULL,
                      repost_requested_at = now(), updated_at = now()")
        params (cond-> [] project-id (conj project-id) branch-name (conj branch-name))]
    (-> (jdbc/execute-one! ds (into [sql] params))
        :next.jdbc/update-count)))

(defn repost-project! [ds project-id]
  (request-repost! ds :project-id project-id))

(defn repost-branch! [ds project-id branch-name]
  (request-repost! ds :project-id project-id :branch-name branch-name))

(defn repost-all!
  "Every project that pushes; after changing the status context."
  [ds]
  (request-repost! ds))

(defn- record-attempt!
  "The history of posts for the commit page; skipped pushes (the project
   cannot push at all) are not attempts."
  [ds job remote payload success? error]
  (jdbc/execute-one! ds
    ["INSERT INTO status_push_attempts
      (job_id, project_id, commit_id, job_state, github_state, context, target, success, error)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
     (:id job) (:project_id job) (:commit_id job) (:state job) (:state payload)
     (:context payload) (str (:owner remote) "/" (:name remote)) success? error]))

(defn- prune-attempts!
  "Attempts older than 30 days are of no interest any more."
  [ds]
  (jdbc/execute-one! ds
    ["DELETE FROM status_push_attempts WHERE created_at < now() - interval '30 days'"]))

(defn- error-message
  "Status and GitHub's message; never includes the token."
  [{:keys [status body error]}]
  (if error
    (str "Request failed: " (.getMessage ^Throwable error))
    (let [message (try (some-> body json/parse-string (get "message"))
                       (catch Exception _ nil))]
      (str "GitHub responded " status (when message (str ": " message))))))

(defn- post! [remote job payload]
  @(http-client/post (github/statuses-url remote (:commit_id job))
                     {:headers {"Authorization"        (str "Bearer " (:token remote))
                                "Accept"               "application/vnd.github+json"
                                "X-GitHub-Api-Version" "2022-11-28"
                                "Content-Type"         "application/json"}
                      :body    (json/generate-string payload)
                      :timeout 15000}))

(defn push-job! [ds job external-base-url & [setting-context]]
  (let [remote (github/remote job)]
    (if-let [error (:error remote)]
      (record-skipped! ds (:id job) (:state job) error)
      (let [prefix   (github/context-prefix (:github_status_context job) setting-context external-base-url)
            payload  (github/payload job external-base-url prefix)
            response (post! remote job payload)]
        (if (= 201 (:status response))
          (do (info "Status push" (:state payload) (:context payload) "to"
                    (str (:owner remote) "/" (:name remote) "@" (:commit_id job)))
              (record-attempt! ds job remote payload true nil)
              (record-posted! ds (:id job) (:state job) (:state payload)))
          (let [error (error-message response)]
            (warn "Status push failed for job" (:id job) ":" error)
            (record-attempt! ds job remote payload false error)
            (record-failed! ds (:id job) error)))))))

(defn push-pending! []
  (let [ds (get-ds)
        base-url (settings/get-setting! :external_base_url)
        context  (settings/get-setting! :github_status_context)
        jobs (jobs-to-push ds)]
    (doseq [job jobs]
      (try
        (push-job! ds job base-url context)
        (catch Exception e
          (warn "Status push failed for job" (:id job) ":" (.getMessage e))
          (record-failed! ds (:id job) (str "Push failed: " (.getMessage e))))))
    (when (seq jobs) (prune-attempts! ds))))

(defdaemon "status-pushes" 2
  (try (push-pending!)
       (catch Exception e (warn "Status pushes failed:" (.getMessage e)))))

(defn init [] (start-status-pushes))
