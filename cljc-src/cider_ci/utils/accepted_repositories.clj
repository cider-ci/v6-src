(ns cider-ci.utils.accepted-repositories
  "Which repositories (and branches) an executor accepts trials for. An entry
   is {:url pattern :branches [pattern ...]}; :branches is optional, a plain
   string entry is {:url string}. A pattern is either an exact value or, when
   it starts with `^` and ends with `$`, a regex that must match the whole
   value (legacy semantics). The server applies the same rules in SQL
   (`cider_ci_trial_accepted`).

   Without :branches any commit reachable on the repository's origin is
   accepted; with :branches the commit must be the head of a matching branch
   (deploy executors: leverages the git host's branch protection)."
  (:require
    [clojure.string :as str]
    [taoensso.timbre :refer [warn]]))


(def accept-all [{:url "^.*$"}])

(defn regex-pattern? [^String pattern]
  (boolean (re-matches #"^\^.*\$$" (or pattern ""))))

(defn- regex-matches? [pattern s]
  (try
    (boolean (re-matches (re-pattern pattern) s))
    (catch java.util.regex.PatternSyntaxException _
      (warn "Ignoring invalid accepted repositories regex" pattern)
      false)))

(defn matches? [pattern s]
  (boolean
    (and (string? pattern) (string? s)
         (or (= pattern s)
             (and (regex-pattern? pattern) (regex-matches? pattern s))))))

(defn- normalize-entry [entry]
  (cond
    (string? entry) {:url entry}
    (map? entry) (let [url      (or (:url entry) (get entry "url"))
                       branches (or (:branches entry) (get entry "branches"))]
                   (cond-> {:url (str url)}
                     (string? branches)     (assoc :branches [branches])
                     (sequential? branches) (assoc :branches (mapv str branches))))
    :else nil))

(defn normalize-entries [entries]
  (->> entries (keep normalize-entry) (remove #(str/blank? (:url %))) vec))

(defn matching-entries [entries url]
  (filterv #(matches? (:url %) url) entries))

(defn origin-policy
  "What the origin of url must confirm for a commit, or nil when no entry
   accepts url: :reachable? (an entry without branches matches) and
   :head-branches (branch patterns of which the commit must be a head)."
  [entries url]
  (when-let [ms (seq (matching-entries entries url))]
    {:reachable?    (boolean (some #(nil? (:branches %)) ms))
     :head-branches (vec (distinct (mapcat :branches ms)))}))

(defn assert-accepted! [entries url]
  (when (str/blank? url)
    (throw (ex-info "The trial has no repository url to check against the accepted repositories."
                    {:accepted_repositories entries})))
  (when (empty? (matching-entries entries url))
    (throw (ex-info (str "The repository " url " is not included in the accepted repositories of this executor.")
                    {:repository_url url :accepted_repositories entries}))))
