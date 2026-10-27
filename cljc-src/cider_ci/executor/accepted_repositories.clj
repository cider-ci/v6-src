(ns cider-ci.executor.accepted-repositories
  "The executor's accepted repositories: YAML (or JSON) from env
   CIDER_CI_EXECUTOR_ACCEPTED_REPOSITORIES, else from
   /etc/cider-ci/accepted-repositories.yml, else accept all. A list of url
   patterns or {url, branches, jobs} maps, see cider-ci.utils.accepted-repositories
   (jobs is a soft restriction only).
   Read on every use, like the traits, so config changes apply without a
   restart; unreadable config accepts nothing."
  (:require
    [cider-ci.utils.accepted-repositories :as ar]
    [clj-yaml.core :as yaml]
    [clojure.java.io :as io]
    [taoensso.timbre :refer [warn]]))


(def ^:private config-file "/etc/cider-ci/accepted-repositories.yml")

(defn- parse [source text]
  (try
    (let [data (yaml/parse-string text)]
      (if (sequential? data)
        (ar/normalize-entries data)
        (do (warn "Accepted repositories in" source "are not a list; accepting nothing")
            [])))
    (catch Exception e
      (warn "Cannot parse accepted repositories in" source ":" (.getMessage e) "; accepting nothing")
      [])))

(defn current []
  (if-let [env-value (System/getenv "CIDER_CI_EXECUTOR_ACCEPTED_REPOSITORIES")]
    (parse "CIDER_CI_EXECUTOR_ACCEPTED_REPOSITORIES" env-value)
    (if (.exists (io/file config-file))
      (parse config-file (slurp config-file))
      ar/accept-all)))

(defn assert-accepted! [entries url job-key]
  (ar/assert-accepted! entries url job-key))

(defn origin-policy [entries url job-key]
  (ar/origin-policy entries url job-key))
