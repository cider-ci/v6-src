(ns cider-ci.server.executors.auth
  (:require
    [clojure.string :as str]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc.sql :as jdbc-sql])
  (:import [java.math BigInteger]))


(defn- sha256 [^String s]
  (format "%064x"
    (BigInteger. 1
      (.digest (java.security.MessageDigest/getInstance "SHA-256")
               (.getBytes s "UTF-8")))))


(defn find-executor
  "Returns the executor row for a valid 'Authorization: Bearer <token>' header,
   or nil if the header is absent, malformed, or the token is not recognised.
   A disabled executor authenticates like any other: it keeps syncing (last
   seen, traits, load) and may finish its running trials; it merely gets no
   new trials (see handler/handle-sync)."
  [tx auth-header]
  (when (and auth-header (str/starts-with? auth-header "Bearer "))
    (let [token      (subs auth-header 7)
          token-hash (sha256 token)]
      (first (jdbc-sql/query tx
               (-> (sql/select :*)
                   (sql/from :executors)
                   (sql/where [:= :token_hash token-hash])
                   sql-format))))))
