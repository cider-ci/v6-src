(ns cider-ci.server.status-pushes.github
  "GitHub commit statuses for jobs (legacy parity): which GitHub API a
   project pushes to and what is posted. The token is configured per project
   (repositories.remote_api_token); there is no server-wide default."
  (:require
    [clojure.string :as str]))


(def default-endpoint "https://api.github.com")

;; GitHub limits the description to 140 characters
(def ^:private max-description-length 140)

(defn github-state
  "Legacy mapping: aborting, aborted, defective (and anything unknown) are
   reported as error."
  [job-state]
  (case job-state
    ("pending" "dispatching" "executing") "pending"
    "passed" "success"
    "failed" "failure"
    "error"))

(defn parse-git-url
  "{:host :owner :name} of https, ssh and scp-style (git@host:owner/name.git)
   git urls; owner and name are the last two path segments."
  [git-url]
  (when-let [[_ host path] (re-matches #"^(?:[a-zA-Z][a-zA-Z0-9+.-]*://)?(?:[^@/]+@)?([^/:]+)(?::\d+)?[:/](.+)$"
                                       (str/trim (or git-url "")))]
    (let [segments (->> (str/split (str/replace path #"(\.git)?/*$" "") #"/")
                        (remove str/blank?))]
      (when (>= (count segments) 2)
        {:host  (str/lower-case host)
         :owner (nth segments (- (count segments) 2))
         :name  (last segments)}))))

(defn remote
  "Where and how the statuses of repo are posted: {:endpoint :owner :name
   :token}, or {:error reason} when the project cannot push. The endpoint is
   remote_api_endpoint (e.g. GitHub Enterprise) or api.github.com for
   github.com urls; owner and name are remote_api_namespace/remote_api_name
   or derived from the git url."
  [{:keys [git_url remote_api_endpoint remote_api_namespace remote_api_name
           remote_api_type remote_api_token]}]
  (let [parsed   (parse-git-url git_url)
        endpoint (or (not-empty remote_api_endpoint)
                     (when (= "github.com" (:host parsed)) default-endpoint))
        owner    (or (not-empty remote_api_namespace) (:owner parsed))
        name     (or (not-empty remote_api_name) (:name parsed))]
    (cond
      (and remote_api_type (not= "github" remote_api_type))
      {:error (str "Status pushes are only implemented for GitHub, not " remote_api_type ".")}
      (str/blank? remote_api_token) {:error "No GitHub token is configured for this project."}
      (nil? endpoint) {:error (str "No GitHub API endpoint for " git_url "; set the API endpoint.")}
      (or (str/blank? owner) (str/blank? name))
      {:error (str "Cannot derive the GitHub repository from " git_url "; set owner and name.")}
      :else {:endpoint (str/replace endpoint #"/+$" "")
             :owner    owner
             :name     name
             :token    remote_api_token})))

(defn statuses-url [{:keys [endpoint owner name]} commit-id]
  (str endpoint "/repos/" owner "/" name "/statuses/" commit-id))

(defn- host-of [url]
  (or (some-> url java.net.URI. .getHost) "localhost"))

(defn- truncate [s n]
  (if (> (count s) n) (str (subs s 0 (- n 1)) "…") s))

(defn job-url [external-base-url {:keys [project_id commit_id id]}]
  (str (str/replace (or external-base-url "") #"/+$" "")
       "/projects/" project_id "/commits/" commit_id "/jobs/" id))

(defn payload
  "Legacy format; the context names this server, so the statuses of several
   CIDER-CI instances (e.g. the legacy one) can coexist on a commit."
  [{job-name :name job-key :key job-state :state :as job} external-base-url]
  (let [label (or (not-empty job-name) job-key)]
    {:state       (github-state job-state)
     :context     (str "Cider-CI@" (host-of external-base-url) " - " label)
     :description (truncate (str job-state " - " label) max-description-length)
     :target_url  (job-url external-base-url job)}))
