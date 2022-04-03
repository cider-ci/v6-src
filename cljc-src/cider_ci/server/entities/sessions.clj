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


(def COOKIE-NAME "cider-ci-session")


(defn digest-statement [o]
  [:encode [:digest (str o) "sha256"] "base64"])

(comment
  (jdbc/execute-one!
    @db/ds*
    (sql-format
      (sql/select [(digest-statement "foo") :token_digest])
      )))

(defn insert-session-statement [token user-id request]
  (-> (sql/insert-into :sessions)
      (sql/values [{:user_id user-id
                    :token_digest (digest-statement token)
                    :data [:lift {:user_agent (get-in request [:headers "user-agent"])
                                  :remote_addr (get-in request [:remote-addr])}]}])
      (sql/returning :*)))


(comment (-> (insert-session-statement "token" "id" {})
             (sql-format )))

(defn insert-session [token user-id {tx :tx :as request}]
  (jdbc/execute-one! tx (sql-format (insert-session-statement token user-id request))))

(defn create [user {:as request}]
  (let [token (java.util.UUID/randomUUID)
        session (insert-session token (:id user) request)]
    (info {'token token 'session session})
    token))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn valid-session-user-query [token]
  (-> (sql/select :users.*)
      (sql/from :users)
      (sql/join :sessions [:= :sessions.user_id :users.id])
      (sql/select [:sessions.id :session_id]
                  [:sessions.valid_until :session_valid_until])
      (sql/where [:= :sessions.token_digest (digest-statement token)])
      (sql/where [:<= [:now] :sessions.valid_until])
      ))

(comment (jdbc/execute-one!
           @db/ds*
           (-> (valid-session-user-query "13fc5e13-ce32-4575-b711-7b57b2b8e0e2")
               (sql-format :inline true))))


(defn valid-session-user [tx token]
  (jdbc/execute-one! tx (-> token valid-session-user-query sql-format)))
