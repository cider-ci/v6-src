(ns cider-ci.executor.accepted-gpg-keys
  "The executor's accepted GPG keys: a YAML (or JSON) list of ASCII armored
   public keys from env CIDER_CI_EXECUTOR_ACCEPTED_GPG_KEYS, else from
   /etc/cider-ci/accepted-gpg-keys.yml, else none (every commit accepted). See
   cider-ci.utils.accepted-gpg-keys.
   Read on every use, like the accepted repositories, so config changes
   apply without a restart; parsing is cached by the config text. A file
   that is not a list yields one error entry, which accepts nothing."
  (:require
    [cider-ci.utils.accepted-gpg-keys :as agk]
    [clj-yaml.core :as yaml]
    [clojure.java.io :as io]
    [taoensso.timbre :refer [warn]]))


(def ^:private config-file "/etc/cider-ci/accepted-gpg-keys.yml")

(defonce ^:private cache* (atom nil))

(defn- parse [source text]
  (try
    (let [data (yaml/parse-string text)]
      (cond
        (nil? data) []
        (sequential? data) (vec data)
        :else (do (warn "Accepted GPG keys in" source "are not a list; accepting nothing")
                  ::invalid)))
    (catch Exception e
      (warn "Cannot parse accepted GPG keys in" source ":" (.getMessage e) "; accepting nothing")
      ::invalid)))

(defn- config-text []
  (if-let [env-value (System/getenv "CIDER_CI_EXECUTOR_ACCEPTED_GPG_KEYS")]
    ["CIDER_CI_EXECUTOR_ACCEPTED_GPG_KEYS" env-value]
    (when (.exists (io/file config-file))
      [config-file (slurp config-file)])))

(defn current
  "{:ascii-keys [...] :entries [...]}; without keys both are empty."
  []
  (let [[source text] (config-text)
        cached @cache*]
    (if (and cached (= (:text cached) text))
      (:value cached)
      (let [ascii-keys (when text (parse source text))
            value (if (= ::invalid ascii-keys)
                    {:ascii-keys [] :entries [{:error (str "invalid configuration in " source)}]}
                    {:ascii-keys (mapv str ascii-keys)
                     :entries    (agk/parse-keys ascii-keys)})]
        (reset! cache* {:text text :value value})
        value))))

(defn entries [] (:entries (current)))

(defn assert-signed!
  "Fails closed: an invalid configuration accepts nothing."
  [{:keys [ascii-keys entries]} cat-file-commit commit-id]
  (when (some :error entries)
    (throw (ex-info (str "The accepted GPG keys of this executor are not valid; refusing the commit " commit-id ".")
                    {:commit-id commit-id :cider-ci.utils.accepted-gpg-keys/rejected true})))
  (agk/assert-signed! ascii-keys cat-file-commit commit-id))
