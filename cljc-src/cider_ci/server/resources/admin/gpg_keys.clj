(ns cider-ci.server.resources.admin.gpg-keys
  (:require
    [cider-ci.utils.core :refer [presence]]
    [cider-ci.utils.git-gpg :as git-gpg]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]))


(defn- keys-sql []
  (-> (sql/select :id :fingerprint :name :description :created_at :updated_at)
      (sql/from :gpg_keys)
      (sql/where [:= :user_id nil])
      (sql/order-by [:created_at :asc])))

(defn- resp [tx]
  {:body (jdbc/execute! tx (sql-format (keys-sql) {:inline false}))})

(defn- extract-fingerprint [ascii-key]
  (try
    (some-> ascii-key git-gpg/pub-keys first git-gpg/hex-fingerprint)
    (catch Exception _
      (throw (ex-info "Invalid GPG key" {:status 422})))))

(defn handler [{{{gpg-key-id :gpg-key-id} :path-params
                 {route-name :name} :data} :route
                body   :body
                method :request-method
                tx     :tx}]
  (case route-name

    :admin-gpg-keys
    (case method
      :get  (resp tx)
      :post (let [ascii-key   (-> body :ascii_key presence
                                  (or (throw (ex-info "ascii_key is required" {:status 422}))))
                  fingerprint (extract-fingerprint ascii-key)
                  row         {:user_id     nil
                               :fingerprint fingerprint
                               :name        (-> body :name presence
                                                (or (throw (ex-info "name is required" {:status 422}))))
                               :ascii_key   ascii-key
                               :description (-> body :description)}]
              (jdbc/execute-one! tx (-> (sql/insert-into :gpg_keys)
                                        (sql/values [row])
                                        sql-format))
              (resp tx)))

    :admin-gpg-key
    (case method
      :get    (or (jdbc/execute-one! tx (-> (keys-sql)
                                             (sql/where [:= :id [:cast gpg-key-id :uuid]])
                                             (sql-format {:inline false})))
                  {:status 404 :body "GPG key not found"})
      :delete (do (jdbc/execute-one! tx (-> (sql/delete-from :gpg_keys)
                                             (sql/where [:= :id [:cast gpg-key-id :uuid]])
                                             (sql/where [:= :user_id nil])
                                             sql-format))
                  (resp tx)))))
