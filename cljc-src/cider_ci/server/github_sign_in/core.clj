(ns cider-ci.server.github-sign-in.core
  "Pure rules of the GitHub sign-in (docs/github-sign-in.md).

  The configuration is a YAML file (see `cider-ci.server.github-sign-in.main`):

    client_id: ...
    client_secret: ...
    oauth_base_url: https://github.com/login/oauth   # optional
    api_endpoint: https://api.github.com             # optional
    callback_base_url: https://ci.example.org        # optional, default
                                                     # external_base_url
    rules:                                           # ordered, first match
      - {organization: example-org, team: ci-admins, admin: true}
      - {organization: example-org}

  A rule matches an active member of the organization; with `team` the user
  must also be a member of that team (slug or display name). Organization and
  team are compared case-insensitively. The first matching rule decides whether
  the account is an admin; no matching rule means no sign-in."
  (:require
    [cider-ci.utils.core :refer [presence]]
    [clojure.string :as str]
    [ring.util.codec :as codec]))


(def scope "read:org user:email")

(defn- lower [s] (some-> s str str/trim str/lower-case presence))

(defn normalize-config
  "The effective configuration, or nil when the sign-in is not configured
  (no client id, client secret or rules)."
  [config]
  (let [rules (->> (:rules config)
                   (map (fn [{:keys [organization team admin]}]
                          {:organization (lower organization)
                           :team (lower team)
                           :admin (true? admin)}))
                   (filter :organization)
                   vec)]
    (when (and (-> config :client_id str presence)
               (-> config :client_secret str presence)
               (seq rules))
      {:client_id (-> config :client_id str str/trim)
       :client_secret (-> config :client_secret str str/trim)
       :oauth_base_url (-> (or (presence (:oauth_base_url config))
                               "https://github.com/login/oauth")
                           (str/replace #"/+$" ""))
       :api_endpoint (-> (or (presence (:api_endpoint config))
                             "https://api.github.com")
                         (str/replace #"/+$" ""))
       :callback_base_url (some-> (:callback_base_url config) presence
                                  (str/replace #"/+$" ""))
       :rules rules})))

(defn callback-url [config external-base-url]
  (str (or (:callback_base_url config)
           (str/replace (str external-base-url) #"/+$" ""))
       "/sign-in/github/callback"))

(defn authorize-url [config redirect-uri state]
  (str (:oauth_base_url config) "/authorize?"
       (codec/form-encode {:client_id (:client_id config)
                           :redirect_uri redirect-uri
                           :scope scope
                           :state state})))

(defn safe-return-to
  "A local path to return to after the sign-in; anything else (absolute or
  protocol-relative URLs) is dropped to prevent open redirects."
  [return-to]
  (when-let [r (presence return-to)]
    (when (and (str/starts-with? r "/")
               (not (str/starts-with? r "//"))
               (not (str/includes? r "\\")))
      r)))

(defn memberships
  "The organizations and teams of the user from the GitHub responses of
  /user/memberships/orgs and /user/teams; only active organization
  memberships count (a pending invitation is not a membership)."
  [org-memberships teams]
  {:organizations (->> org-memberships
                       (filter #(= "active" (:state %)))
                       (keep #(-> % :organization :login lower))
                       set)
   :teams (->> teams
               (keep (fn [{:keys [slug name organization]}]
                       (when-let [org (-> organization :login lower)]
                         {:organization org :slug (lower slug) :name (lower name)})))
               set)})

(defn rule-matches? [{:keys [organizations teams]} {:keys [organization team]}]
  (and (contains? organizations organization)
       (or (nil? team)
           (some #(and (= organization (:organization %))
                       (or (= team (:slug %)) (= team (:name %))))
                 teams))))

(defn matching-rule
  "The first rule matching the memberships, or nil."
  [rules memberships]
  (first (filter #(rule-matches? memberships %) rules)))

(defn verified-emails
  "The verified email addresses of the /user/emails response, primary first,
  without duplicates (case-insensitive)."
  [emails]
  (->> emails
       (filter :verified)
       (sort-by (comp not :primary))
       (keep #(some-> % :email str str/trim presence))
       (reduce (fn [acc e]
                 (if (some #(= (str/lower-case e) (str/lower-case %)) acc)
                   acc (conj acc e)))
               [])))

(defn next-link
  "The rel=\"next\" URL of a GitHub Link header, or nil."
  [link-header]
  (some->> (some-> link-header (str/split #","))
           (some #(when (str/includes? % "rel=\"next\"")
                    (second (re-find #"<([^>]+)>" %))))))
