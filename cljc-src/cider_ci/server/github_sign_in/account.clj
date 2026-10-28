(ns cider-ci.server.github-sign-in.account
  "Creates or updates the account of a GitHub sign-in: the user (matched by
  the GitHub user id), its verified email addresses and its public GPG keys.
  Rows imported from GitHub carry source = 'github'; each sign-in replaces
  exactly those and leaves manually added ones alone.

  Runs inside the request transaction: collisions (an address or key that
  belongs to another account) are resolved with ON CONFLICT, never by catching
  SQL errors, which would abort the transaction."
  (:require
    [cider-ci.server.projects.repositories.commits :as repo-commits]
    [cider-ci.utils.core :refer [presence]]
    [cider-ci.utils.git-gpg :as git-gpg]
    [cider_ci.server.entities.users :as users]
    [clojure.string :as str]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error]]))


;;; user ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- upsert-user! [tx {github-id :id login :login gh-name :name} admin?]
  (let [gh-name (presence gh-name)]
    (if-let [{id :id} (jdbc/execute-one! tx ["SELECT id FROM users WHERE github_id = ?" github-id])]
      (do (jdbc/execute-one!
            tx (-> (sql/update :users)
                   (sql/set (cond-> {:github_login login
                                     :is_admin admin?
                                     :updated_at [:now]}
                              gh-name (assoc :name gh-name)))
                   (sql/where [:= :id id])
                   sql-format))
          id)
      (:id (jdbc/execute-one!
             tx (-> (sql/insert-into :users)
                    (sql/values [{:github_id github-id
                                  :github_login login
                                  :name (or gh-name login)
                                  :is_admin admin?}])
                    (sql/returning :id)
                    sql-format))))))


;;; email addresses ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sync-email-addresses! [tx user-id emails]
  (let [wanted (set (map str/lower-case emails))]
    (doseq [{:keys [id email_address]}
            (jdbc/execute! tx ["SELECT id, email_address FROM email_addresses
                                WHERE user_id = ? AND source = 'github'" user-id])]
      (when-not (wanted (str/lower-case email_address))
        (jdbc/execute-one! tx ["DELETE FROM email_addresses WHERE id = ?" id])))
    (doseq [email emails]
      (when-not (jdbc/execute-one!
                  tx ["INSERT INTO email_addresses (email_address, user_id, source)
                       VALUES (?, ?, 'github')
                       ON CONFLICT ((lower(email_address))) DO NOTHING
                       RETURNING id" email user-id])
        (when-not (jdbc/execute-one!
                    tx ["SELECT id FROM email_addresses
                         WHERE lower(email_address) = lower(?) AND user_id = ?" email user-id])
          (warn (str "GitHub sign-in of user " user-id
                     ": a verified GitHub email address belongs to another account, skipped")))))
    (when-let [primary (first emails)]
      (jdbc/execute-one!
        tx ["UPDATE email_addresses SET is_primary = true
             WHERE user_id = ? AND lower(email_address) = lower(?)
             AND NOT EXISTS (SELECT 1 FROM email_addresses
                             WHERE user_id = ? AND is_primary)"
            user-id primary user-id]))))


;;; gpg keys ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parse-gpg-key [{raw-key :raw_key key-id :key_id revoked :revoked}]
  (when (and (presence raw-key) (not revoked))
    (try (when-let [fingerprint (some-> raw-key git-gpg/pub-keys first git-gpg/hex-fingerprint)]
           {:fingerprint fingerprint :ascii_key raw-key :key_id key-id})
         (catch Exception e
           (warn "GitHub sign-in: cannot parse the GPG key" key-id ":" (.getMessage e))
           nil))))

(defn- sync-gpg-keys! [tx user-id login gpg-keys]
  (let [parsed (keep parse-gpg-key gpg-keys)
        wanted (set (map :fingerprint parsed))]
    (doseq [{:keys [id fingerprint]}
            (jdbc/execute! tx ["SELECT id, fingerprint FROM gpg_keys
                                WHERE user_id = ? AND source = 'github'" user-id])]
      (when-not (wanted fingerprint)
        (repo-commits/revoke-fingerprint! tx fingerprint id)
        (jdbc/execute-one! tx ["DELETE FROM gpg_keys WHERE id = ?" id])))
    (doseq [{:keys [fingerprint ascii_key key_id]} parsed]
      ;; a key added on GitHub again (e.g. with new subkeys) updates the row;
      ;; a fingerprint added manually or owned by another account is kept
      (when-not (jdbc/execute-one!
                  tx ["INSERT INTO gpg_keys (fingerprint, name, ascii_key, description, user_id, source)
                       VALUES (?, ?, ?, ?, ?, 'github')
                       ON CONFLICT (fingerprint) DO UPDATE
                         SET ascii_key = EXCLUDED.ascii_key, updated_at = now()
                         WHERE gpg_keys.user_id = EXCLUDED.user_id AND gpg_keys.source = 'github'
                       RETURNING id"
                      fingerprint (str "GitHub " (or key_id fingerprint)) ascii_key
                      (str "Imported from the GitHub account " login) user-id])
        (info (str "GitHub sign-in of user " user-id ": the GPG key " fingerprint
                   " is already configured, skipped"))))
    (doseq [{:keys [ascii_key fingerprint]}
            (jdbc/execute! tx ["SELECT ascii_key, fingerprint FROM gpg_keys
                                WHERE user_id = ? AND source = 'github'" user-id])]
      (repo-commits/retroactive-verify! tx ascii_key fingerprint user-id))))


;;; sync ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sync!
  "Creates or updates the account and returns the user (as for sessions).
  gpg-keys nil means they could not be fetched: the imported keys are kept."
  [tx {:keys [user emails gpg-keys]} {admin? :admin}]
  (let [user-id (upsert-user! tx user admin?)]
    (sync-email-addresses! tx user-id emails)
    (when gpg-keys
      (sync-gpg-keys! tx user-id (:login user) gpg-keys))
    (jdbc/execute-one! tx (-> users/base-query
                              (sql/where [:= :users.id user-id])
                              sql-format))))
