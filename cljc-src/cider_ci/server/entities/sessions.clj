(ns cider_ci.server.entities.sessions
  (:require
    [cider-ci.server.db.core :as db]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]])
  (:import
    [java.util UUID]
    ))


(def COOKIE-NAME "cider-ci-session")

(defn digest-statement [o]
  [:encode [:digest (str o) "sha256"] "base64"])


;;; create ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn insert-session-statement [token user-id request]
  (-> (sql/insert-into :sessions)
      (sql/values [{:user_id user-id
                    :token_digest (digest-statement token)
                    :data [:lift {:user_agent (get-in request [:headers "user-agent"])
                                  :remote_addr (get-in request [:remote-addr])}]}])
      (sql/returning :*)))


(defn insert-session [token user-id {tx :tx :as request}]
  (jdbc/execute-one! tx (sql-format (insert-session-statement token user-id request))))

(defn create [user {:as request}]
  (let [token (java.util.UUID/randomUUID)
        session (insert-session token (:id user) request)]
    (assoc session :token token)))


;;; get ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn valid-session-user-query [token]
  (-> (sql/select :users.*)
      (sql/from :users)
      (sql/join :sessions [:= :sessions.user_id :users.id])
      (sql/select [:sessions.id :session_id]
                  [:sessions.valid_until :session_valid_until])
      (sql/where [:= :sessions.token_digest (digest-statement token)])
      (sql/where [:<= [:now] :sessions.valid_until])))

(defn valid-session-user [tx token]
  (assert (uuid? (UUID/fromString token)) "A tokens must always be a UUID")
  (jdbc/execute-one! tx (-> token valid-session-user-query sql-format)))


;;; delete ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn delete-statement [session-id]
  (-> (sql/delete-from :sessions)
      (sql/where [:= :id session-id])))

(defn delete [tx session-id]
  (jdbc/execute-one! tx (sql-format (delete-statement session-id))))


