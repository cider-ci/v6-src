(ns cider-ci.executor.accepted-repositories
  "The executor's accepted repositories: env
   CIDER_CI_EXECUTOR_ACCEPTED_REPOSITORIES (whitespace separated, regexes may
   contain commas), else /etc/cider-ci/accepted-repositories (one entry per
   line, # comments), else accept all. Read on every use, like the traits, so
   config changes apply without a restart."
  (:require
    [cider-ci.utils.accepted-repositories :as ar]
    [clojure.string :as str]))


(def ^:private config-file "/etc/cider-ci/accepted-repositories")

(defn current []
  (if-let [env-value (System/getenv "CIDER_CI_EXECUTOR_ACCEPTED_REPOSITORIES")]
    (->> (str/split (str/trim env-value) #"\s+") (remove str/blank?) vec)
    (try
      (->> (str/split-lines (slurp config-file))
           (map str/trim)
           (remove #(or (str/blank? %) (str/starts-with? % "#")))
           vec)
      (catch java.io.FileNotFoundException _
        ar/accept-all))))

(defn assert-accepted! [url]
  (ar/assert-accepted! (current) url))
