(ns cider_ci.server.entities.users
  (:require
    [cider-ci.server.db.core :as db]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]))


(def email-sub
  (-> (sql/select
        [[:raw " json_agg(email_addresses.email ORDER BY is_primary DESC, email DESC) "]])
      (sql/from :email_addresses)
      (sql/where [:= :email_addresses.user_id :users.id])
      (sql/group-by :email_addresses.user_id)
      ))


(def has-password-sub
  (-> (sql/select true)
      (sql/from :passwords)
      (sql/where [:= :passwords.user_id :users.id])
      ))

(def base-query
  (-> (sql/select :users.*)
      (sql/from :users)
      (sql/select [email-sub :email_addresses])
      (sql/select [[:exists  has-password-sub] :has_password])))


(comment
  (jdbc/execute!
    @db/ds* (sql-format users-base-query)))
