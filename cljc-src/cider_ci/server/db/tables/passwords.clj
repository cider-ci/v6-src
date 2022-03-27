(ns cider_ci.server.db.tables.passwords
  (:require
    [cider-ci.server.db.core :as db]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error]]
    ))


(defn crypt-expr [password]
  [:crypt
   [:cast password :text]
   [:raw " GEN_SALT('bf', 10) "]])

(defn select-password-hash
  [password]
  (-> (sql/select [ (crypt-expr password) :pw_hash])
      (sql-format {:inline true})
      ))

(comment
  (select-password-hash "foo")
  (jdbc/execute-one! @db/ds* (select-password-hash "foo")))

(defn upsert-statement [password user-id]
  (-> (sql/insert-into :passwords)
      (sql/values [{:user_id user-id :password_hash (crypt-expr password)}])
      (sql/on-conflict :user_id)
      (sql/do-update-set :password_hash)
      (sql/returning :*)))

(comment (-> (upsert-statement "foo" "123")
             (sql-format)))

(defn upsert [tx password user-id]
  (let [sql (upsert-statement password user-id)
        res (jdbc/execute-one! tx (sql-format sql) {:return-keys true})]
    res))
