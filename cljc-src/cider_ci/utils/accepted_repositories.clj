(ns cider-ci.utils.accepted-repositories
  "Which repositories an executor accepts trials for (legacy parity): an
   entry is either an exact git url or, when it starts with `^` and ends
   with `$`, a regex that must match the whole url. The server applies the
   same rule in SQL (`cider_ci_repository_accepted`)."
  (:require
    [clojure.string :as str]
    [taoensso.timbre :refer [warn]]))


(def accept-all ["^.*$"])

(defn regex-entry? [^String entry]
  (boolean (re-matches #"^\^.*\$$" (or entry ""))))

(defn- regex-matches? [entry url]
  (try
    (boolean (re-matches (re-pattern entry) url))
    (catch java.util.regex.PatternSyntaxException _
      (warn "Ignoring invalid accepted repository regex" entry)
      false)))

(defn accepted? [entries url]
  (boolean
    (some #(or (= % url)
               (and (regex-entry? %) (regex-matches? % url)))
          entries)))

(defn assert-accepted! [entries url]
  (when (str/blank? url)
    (throw (ex-info "The trial has no repository url to check against the accepted repositories."
                    {:accepted_repositories entries})))
  (when-not (accepted? entries url)
    (throw (ex-info (str "The repository " url " is not included in the accepted repositories of this executor.")
                    {:repository_url url :accepted_repositories entries}))))
