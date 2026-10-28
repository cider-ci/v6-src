(ns cider-ci.server.github-sign-in.main
  "Sign in with GitHub (OAuth App), restricted by organization and team
  membership; see docs/github-sign-in.md and `cider-ci.server.github-sign-in.core`.

  There is no sync: every GitHub sign-in (i.e. about weekly, when the session
  expires) refreshes the account, its verified email addresses and its public
  GPG keys. The GitHub token is used during the sign-in only and not stored.

  The configuration file is re-read when it changes; without it (or without
  client id, secret or rules) the sign-in is not offered."
  (:refer-clojure :exclude [keyword str])
  (:require
    [cheshire.core :as json]
    [cider-ci.server.db.settings :as settings]
    [cider-ci.server.github-sign-in.account :as account]
    [cider-ci.server.github-sign-in.core :as core]
    [cider-ci.server.routes :as routes]
    [cider-ci.utils.core :refer [keyword presence str]]
    [cider-ci.utils.query-params :as query-params]
    [cider_ci.server.entities.sessions :as sessions]
    [clj-yaml.core :as yaml]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [environ.core :refer [env]]
    [org.httpkit.client :as http-client]
    [ring.util.codec :as codec]
    [taoensso.timbre :refer [debug info warn error]])
  (:import
    [java.security MessageDigest SecureRandom]))


;;; configuration ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def config-file-key :github-sign-in-config)

(def cli-options
  [[nil "--github-sign-in-config PATH"
    "GitHub sign-in configuration (YAML), see docs/github-sign-in.md; falls back to GITHUB_SIGN_IN_CONFIG"
    :default (or (env :github-sign-in-config) "/etc/cider-ci/github-sign-in.yml")]])

(defonce ^:private config-path* (atom "/etc/cider-ci/github-sign-in.yml"))
(defonce ^:private config-cache* (atom nil))

(defn config
  "The effective configuration or nil; the file is re-read when it changed."
  []
  (let [file (io/file @config-path*)
        mtime (when (.isFile file) (.lastModified file))
        {cached-mtime :mtime cached-path :path} @config-cache*]
    (if (and (= mtime cached-mtime) (= cached-path @config-path*))
      (:config @config-cache*)
      (let [cfg (when mtime
                  (try (core/normalize-config (yaml/parse-string (slurp file)))
                       (catch Exception e
                         (warn "GitHub sign-in: cannot read" @config-path* ":" (.getMessage e))
                         nil)))]
        (reset! config-cache* {:path @config-path* :mtime mtime :config cfg})
        cfg))))

(defn enabled? [] (boolean (config)))

(defn init [options]
  (reset! config-path* (get options config-file-key))
  (info "GitHub sign-in" (if (enabled?) "enabled," "not configured,")
        "configuration file" @config-path*))


;;; GitHub API ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Never log a request or response map: they contain the token and secret.

(defn- github-error [message & [data]]
  (ex-info message (merge {::github-error true} data)))

(defn- error-message [status body]
  (let [message (try (some-> body (json/parse-string true) :message) (catch Exception _ nil))]
    (str "GitHub responded " status (when message (str ": " message)))))

(defn- exchange-code
  "Exchanges the OAuth code for an access token."
  [config code redirect-uri]
  (let [{:keys [status body error]}
        @(http-client/post (str (:oauth_base_url config) "/access_token")
                           {:headers {"Accept" "application/json"}
                            :form-params {:client_id (:client_id config)
                                          :client_secret (:client_secret config)
                                          :code code
                                          :redirect_uri redirect-uri}
                            :timeout 15000})]
    (when error (throw (github-error (str "GitHub token exchange failed: " (.getMessage error)))))
    (when-not (= 200 status) (throw (github-error (error-message status body))))
    ;; an invalid or expired code is a 200 with an error
    (let [{token :access_token err :error description :error_description}
          (json/parse-string body true)]
      (or (presence token)
          (throw (github-error (str "GitHub token exchange failed: " (or description err))))))))

(defn- api-get
  "GET the path (or a full URL), following rel=next pages; returns the body,
  for lists all pages concatenated."
  [config token path-or-url]
  (loop [url (if (string/starts-with? path-or-url "http")
               path-or-url (str (:api_endpoint config) path-or-url))
         acc nil
         pages 1]
    (let [{:keys [status body headers error]}
          @(http-client/get url {:headers {"Accept" "application/vnd.github+json"
                                           "Authorization" (str "Bearer " token)
                                           "X-GitHub-Api-Version" "2022-11-28"}
                                 :timeout 15000})]
      (when error (throw (github-error (str "GitHub request failed: " (.getMessage error)))))
      (when-not (= 200 status)
        (throw (github-error (str (error-message status body) " (GET " path-or-url ")")
                             {:status-code status})))
      (let [data (json/parse-string body true)
            next-url (core/next-link (:link headers))]
        (cond
          (not (sequential? data)) data
          (and next-url (< pages 10)) (recur next-url (concat acc data) (inc pages))
          :else (vec (concat acc data)))))))

(defn- fetch-gpg-keys
  "The public GPG keys of the GitHub user, or nil when they cannot be fetched
  (best effort: the sign-in goes on and the imported keys are kept)."
  [config token login]
  (try (api-get config token (str "/users/" (codec/url-encode login) "/gpg_keys?per_page=100"))
       (catch clojure.lang.ExceptionInfo e
         (if (::github-error (ex-data e))
           (do (warn "GitHub sign-in: cannot fetch the GPG keys:" (ex-message e)) nil)
           (throw e)))))


;;; handlers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def STATE-COOKIE "cider-ci-github-sign-in")

(defn- state-cookie [value max-age]
  {STATE-COOKIE {:value value :http-only true :same-site :lax
                 :max-age max-age :path "/sign-in/github"}})

(defn- random-token []
  (let [bytes (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (apply str (map #(format "%02x" %) bytes))))

(defn- redirect-uri [config]
  (core/callback-url config (settings/get-setting! :external_base_url)))

(defn- error-redirect
  "Back to the sign-in page, which shows the message for the error code."
  [code]
  {:status 302
   :headers {"Location" (routes/path :sign-in {} {:github-error code})}
   :cookies (state-cookie "" 0)})

(defn- start [{query-string :query-string}]
  (if-let [config (config)]
    (let [state (random-token)
          return-to (-> query-string query-params/decode :return-to core/safe-return-to)]
      {:status 302
       :headers {"Location" (core/authorize-url config (redirect-uri config) state)}
       :cookies (state-cookie (str state ":" return-to) 600)})
    (error-redirect "not-configured")))

(defn- same-state? [a b]
  (and (presence a) (presence b)
       (MessageDigest/isEqual (.getBytes (str a) "UTF-8") (.getBytes (str b) "UTF-8"))))

(defn- sign-in [config {tx :tx :as request} code return-to]
  (let [redirect-uri (redirect-uri config)
        token (exchange-code config code redirect-uri)
        user (api-get config token "/user")
        memberships (core/memberships
                      (api-get config token "/user/memberships/orgs?state=active&per_page=100")
                      (api-get config token "/user/teams?per_page=100"))]
    (if-let [rule (core/matching-rule (:rules config) memberships)]
      (let [emails (core/verified-emails (api-get config token "/user/emails?per_page=100"))
            gpg-keys (fetch-gpg-keys config token (:login user))
            account (account/sync! tx {:user user :emails emails :gpg-keys gpg-keys} rule)
            session (sessions/create account request)]
        (info (str "GitHub sign-in of user " (:id account) " by the rule "
                   (:organization rule) (some->> (:team rule) (str "/"))
                   (when (:admin rule) " (admin)")))
        {:status 302
         :headers {"Location" (or return-to (routes/path :root))}
         :cookies (merge (sessions/cookie session) (state-cookie "" 0))})
      (do (info "GitHub sign-in refused: GitHub user" (:id user)
                "matches no organization or team rule")
          (error-redirect "not-allowed")))))

(defn- callback [{query-string :query-string cookies :cookies :as request}]
  (let [params (query-params/decode query-string)
        [state return-to] (some-> (get-in cookies [STATE-COOKIE :value]) (string/split #":" 2))
        config (config)]
    (cond
      (nil? config) (error-redirect "not-configured")
      (presence (:error params)) (error-redirect "denied")
      (not (same-state? state (:state params))) (error-redirect "state")
      (not (presence (:code params))) (error-redirect "failed")
      :else (try (sign-in config request (:code params) (core/safe-return-to return-to))
                 (catch clojure.lang.ExceptionInfo e
                   (if (::github-error (ex-data e))
                     (do (warn "GitHub sign-in failed:" (ex-message e))
                         (error-redirect "failed"))
                     (throw e)))))))

(defn handler [{{{route-name :name} :data} :route :as request}]
  (case route-name
    :sign-in-github (start request)
    :sign-in-github-callback (callback request)))
