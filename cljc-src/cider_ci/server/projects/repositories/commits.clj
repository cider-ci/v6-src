; Copyright © 2013 - 2016 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.commits
  (:refer-clojure :exclude [str keyword import])
  (:require [cider-ci.utils.core :refer [keyword str]])
  (:require
    [cider-ci.server.projects.repositories.git.commits :as git-commits]
    [cider-ci.server.projects.repositories.sql.commits :as sql.commits]
    [cider-ci.utils.git-gpg :as git-gpg]
    [cider-ci.utils.system :as system]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as jdbc-rs]
    [next.jdbc.sql :refer [insert! query]]
    ))

;### create / update ###########################################################

(defn insert-submodules [tx id repository-path]
  (doseq [submodule (git-commits/get-submodules id repository-path)]
    (insert! tx :submodules
             (assoc submodule :commit_id id))))

(defn- verify-and-store-gpg-fingerprint! [tx params repository-path]
  (when (:signature params)
    (let [id (:id params)
          trusted-keys (jdbc/execute! tx
                         [(str "SELECT ascii_key FROM gpg_keys "
                               "WHERE user_id IS NULL "
                               "OR user_id IN ("
                               "  SELECT user_id FROM email_addresses "
                               "  WHERE lower(email_address) IN (lower(?), lower(?)))")
                          (:author_email params) (:committer_email params)]
                         {:builder-fn jdbc-rs/as-unqualified-lower-maps})
          cat-file-commit (:out (system/exec! ["git" "cat-file" "-p" id]
                                              {:dir repository-path}))]
      (when-let [fp (->> trusted-keys
                         (map :ascii_key)
                         (some #(git-gpg/valid-signature-fingerprint cat-file-commit %)))]
        (sql.commits/update! tx {:signature_fingerprint fp} ["id = ?" id])))))

(defn- create [tx id repository-path]
  (let [params (git-commits/get id repository-path)
        commit (sql.commits/create! tx params)]
    (insert-submodules tx id repository-path)
    (verify-and-store-gpg-fingerprint! tx params repository-path)
    commit))

;### arcs ######################################################################

(defn find-arc [ds arc]
  (first (query ds ["SELECT * FROM commit_arcs
                    WHERE child_id = ? AND parent_id = ?",
                    (:child_id arc), (:parent_id arc)])))

(defn find-or-create-arc! [ds arc]
  (or
    (find-arc  ds arc)
    (insert! ds :commit_arcs arc)))

(defn- create-arcs [tx id repository-path]
  (loop [to-be-imported-arcs (git-commits/arcs-to-parents id repository-path)]
    (when-let [current-arc (first to-be-imported-arcs)]
      (let [parent-id (:parent_id current-arc)
            discovered-arcs (if (sql.commits/find tx parent-id)
                              []
                              (do (create tx parent-id repository-path)
                                (git-commits/arcs-to-parents parent-id repository-path)))]
        (find-or-create-arc! tx current-arc)
        (recur (concat (rest to-be-imported-arcs) discovered-arcs))))))

;### import-recursively #######################################################

(defn import-recursively [tx id repository-path]
  (or (sql.commits/find tx id)
      (let [commit (create tx id repository-path)]
        (create-arcs tx id repository-path)
        (sql.commits/update-depths tx)
        commit)))


;#### debug ###################################################################
;(logging-config/set-logger! :level :debug)
;(logging-config/set-logger! :level :info)
;(debug/debug-ns *ns*)
