(ns cider-ci.server.html.build-info
  "What this server was built from (scripts.build writes built-info.edn into
   the jar): the commit, or the tree hash of a dirty working tree, for the
   footer of every page. Absent in a dev server started from source."
  (:require
    [cider-ci.server.status-pushes.github :as github]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [taoensso.timbre :refer [warn]]))

(defn summary
  "The footer's data: the commit (linked on the origin when it is a remote
   url) and, when the build was dirty, the tree hash to show instead."
  [{:keys [commit_id tree_id dirty origin_url built_at] :as info}]
  (when info
    {:commit_id  commit_id
     :commit_url (github/commit-url origin_url commit_id)
     :tree_id    tree_id
     :dirty      (boolean dirty)
     :built_at   built_at}))

(def info
  (delay
    (when-let [r (io/resource "built-info.edn")]
      (try (summary (edn/read-string (slurp r)))
           (catch Exception e (warn "built-info.edn unreadable:" (.getMessage e)) nil)))))
