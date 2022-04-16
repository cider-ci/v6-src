(ns cider_ci.server.entities.passwords
  (:require
    [cider-ci.server.db.core :as db]
    [cider_ci.server.entities.users :as users]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn gen-crypt-expr [password]
  [:crypt
   [:cast password :text]
   [:raw " GEN_SALT('bf', 10) "]])

(defn select-password-hash
  [password]
  (-> (sql/select [(gen-crypt-expr password) :pw_hash])
      (sql-format {:inline true})
      ))

(defn upsert-statement [password user-id]
  (-> (sql/insert-into :passwords)
      (sql/values [{:user_id [:cast user-id :uuid]
                    :password_hash (gen-crypt-expr password)}])
      (sql/on-conflict :user_id)
      (sql/do-update-set :password_hash)
      (sql/returning :*)))

(defn upsert [tx password user-id]
  (let [sql (upsert-statement password user-id)
        res (jdbc/execute-one! tx (sql-format sql) {:return-keys true})]
    res))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn pw-check-exp [password]
  [:= :passwords.password_hash
   [:crypt password :passwords.password_hash]])


(comment (sql-format (sql/select [(pw-check-exp "secret") :pw_ok])))

(defn password-authenticated-user-statement [email-or-login password]
  (-> users/base-query
      (sql/where [:or
                  [:= [:lower :users.login] [:lower [:trim email-or-login]]]
                  [:exists (-> (sql/select true)
                               (sql/from :email_addresses)
                               (sql/where [:= [:lower [:trim email-or-login]]
                                           [:lower :email_addresses.email]])
                               (sql/where [:= :users.id :email_addresses.user_id]))]])
      (sql/where [:exists (-> (sql/select true)
                              (sql/from :passwords)
                              (sql/where [:= :users.id :passwords.user_id])
                              (sql/where (pw-check-exp password)))])))

(comment (-> (password-authenticated-user-statement
               "admin@localhost" "secret")
             (sql-format {:inline true})))

(defn password-authenticated-user [tx uid password]
  (jdbc/execute-one!
    tx (-> (password-authenticated-user-statement uid password)
           (->> (spy :warn))
           (sql-format {:inline true}) (->> (spy :warn)))))

(comment (password-authenticated-user @db/ds* "admin@localhost" "secret" ))
