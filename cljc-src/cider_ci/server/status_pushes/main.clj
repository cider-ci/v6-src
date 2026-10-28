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
             r.remote_api_name, r.remote_api_type, r.remote_api_token
      FROM jobs j
      JOIN repositories r ON r.id = j.project_id
      LEFT JOIN job_status_pushes p ON p.job_id = j.id
      WHERE r.send_status_notifications
        AND r.remote_api_token IS NOT NULL
        AND j.updated_at > now() - interval '24 hours'
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
          attempts = 0, next_attempt_at = NULL, updated_at = now()"
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
          last_error_at = now(), attempts = 0, next_attempt_at = NULL, updated_at = now()"
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

(defn push-job! [ds job external-base-url]
  (let [remote (github/remote job)]
    (if-let [error (:error remote)]
      (record-skipped! ds (:id job) (:state job) error)
      (let [payload  (github/payload job external-base-url)
            response (post! remote job payload)]
        (if (= 201 (:status response))
          (do (info "Status push" (:state payload) (:context payload) "to"
                    (str (:owner remote) "/" (:name remote) "@" (:commit_id job)))
              (record-posted! ds (:id job) (:state job) (:state payload)))
          (let [error (error-message response)]
            (warn "Status push failed for job" (:id job) ":" error)
            (record-failed! ds (:id job) error)))))))

(defn push-pending! []
  (let [ds (get-ds)
        base-url (settings/get-setting! :external_base_url)]
    (doseq [job (jobs-to-push ds)]
      (try
        (push-job! ds job base-url)
        (catch Exception e
          (warn "Status push failed for job" (:id job) ":" (.getMessage e))
          (record-failed! ds (:id job) (str "Push failed: " (.getMessage e))))))))

(defdaemon "status-pushes" 2
  (try (push-pending!)
       (catch Exception e (warn "Status pushes failed:" (.getMessage e)))))

(defn init [] (start-status-pushes))
