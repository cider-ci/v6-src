(ns cider_ci.server.db.tables.passwords
  (:require
    [cider-ci.server.db.core :as db]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error]]
    ))

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
      (sql/values [{:user_id user-id :password_hash (gen-crypt-expr password)}])
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

(defn password-authenticated-user-statement [email password]
  (-> (sql/select :users.*)
      (sql/from :users)
      (sql/join :passwords [:= :passwords.user_id :users.id])
      (sql/where [:= :users.email [:lower [:trim email]]])
      (sql/where (pw-check-exp password))))

(comment (-> (password-user-check-statement
               "admin@localhost" "secret")
             (sql-format {:inline true})))

(defn password-authenticated-user [tx email password]
  (jdbc/execute-one!
    tx (-> (password-authenticated-user-statement email password)
           sql-format)))

(comment (password-authenticated-user @db/ds* "admin@localhost" "secret" ))
