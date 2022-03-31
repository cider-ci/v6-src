(ns cider_ci.server.entities.sessions
  (:require
    [cider-ci.server.db.core :as db]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error]])
  (:import
    [java.util UUID]
    ))


(defn digest-statement [o]
  [:encode [:digest (str o) "sha256"] "base64"])

(comment
  (jdbc/execute-one!
    @db/ds*
    (sql-format
      (sql/select [(digest-statement "foo") :token_digest])
      )))

(defn insert-session-statement [token user-id]
  (-> (sql/insert-into :sessions)
      (sql/values [{:user_id user-id
                    :token_digest (digest-statement token)}])
      (sql/returning :*)))

(defn insert-session [tx token user-id]
  (jdbc/execute-one! tx (sql-format (insert-session-statement token user-id))))

(defn create [tx user]
  (let [token (java.util.UUID/randomUUID)
        session (insert-session tx token (:id user))]
    (info {'token token 'session session})
    token))
