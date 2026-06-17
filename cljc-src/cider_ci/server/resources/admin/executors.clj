(ns cider-ci.server.resources.admin.executors
  (:require
    [cider-ci.utils.core :refer [presence]]
    [clojure.string :as str]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]))


(defn- sha256 [^String s]
  (format "%064x"
    (BigInteger. 1
      (.digest (java.security.MessageDigest/getInstance "SHA-256")
               (.getBytes s "UTF-8")))))


(defn- parse-traits [s]
  (if (str/blank? s)
    []
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?))))


(defn- traits-literal [traits]
  (str "{" (str/join "," traits) "}"))


(defn- fetch-list [tx]
  (jdbc/execute! tx
    ["SELECT id, name, token_part,
             array_to_string(traits, ',') AS traits,
             max_load, enabled, last_seen_at, created_at
      FROM executors
      ORDER BY name ASC"]))


(defn- list-resp [tx]
  {:body (fetch-list tx)})


(defn handler [{{{executor-id :executor-id} :path-params
                 {route-name :name} :data} :route
                body   :body
                method :request-method
                tx     :tx}]
  (case route-name

    :admin-executors
    (case method
      :get  (list-resp tx)
      :post (let [token    (str (java.util.UUID/randomUUID))
                  name     (-> body :name presence
                               (or (throw (ex-info "name is required" {:status 422}))))
                  traits   (parse-traits (or (:traits body) ""))
                  max-load (double (or (:max_load body) 4.0))]
              (jdbc/execute-one! tx
                ["INSERT INTO executors (name, token_hash, token_part, max_load, traits)
                  VALUES (?, ?, ?, ?, CAST(? AS text[]))"
                 name (sha256 token) (subs token 0 8) max-load (traits-literal traits)])
              {:body {:token     token
                      :executors (fetch-list tx)}}))

    :admin-executor
    (case method
      :patch (let [enabled  (:enabled body)
                   new-name (presence (:name body))
                   max-load (some-> (:max_load body) double)
                   traits   (when (contains? body :traits)
                               (parse-traits (or (:traits body) "")))]
               (when (or (some? enabled) new-name max-load)
                 (let [updates (cond-> {:updated_at [:raw "now()"]}
                                 (some? enabled) (assoc :enabled enabled)
                                 new-name        (assoc :name new-name)
                                 max-load        (assoc :max_load max-load))]
                   (jdbc/execute-one! tx
                     (-> (sql/update :executors)
                         (sql/set updates)
                         (sql/where [:= :id [:cast executor-id :uuid]])
                         sql-format))))
               (when traits
                 (jdbc/execute-one! tx
                   ["UPDATE executors SET traits = CAST(? AS text[]), updated_at = now() WHERE id = ?::uuid"
                    (traits-literal traits) executor-id]))
               (list-resp tx))
      :delete (do
                (jdbc/execute-one! tx
                  (-> (sql/delete-from :executors)
                      (sql/where [:= :id [:cast executor-id :uuid]])
                      sql-format))
                (list-resp tx)))))
