(ns cider-ci.server.resources.users.user.gpg-keys
  (:require
    [cider-ci.utils.core :refer [presence]]
    [cider-ci.utils.git-gpg :as git-gpg]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]))


(defn- keys-sql [user-id]
  (-> (sql/select :id :fingerprint :name :description :created_at :updated_at)
      (sql/from :gpg_keys)
      (sql/where [:= :user_id [:cast user-id :uuid]])
      (sql/order-by [:created_at :asc])))

(defn- resp [user-id tx]
  {:body (jdbc/execute! tx (sql-format (keys-sql user-id) {:inline false}))})

(defn- extract-fingerprint [ascii-key]
  (try
    (some-> ascii-key git-gpg/pub-keys first git-gpg/hex-fingerprint)
    (catch Exception e
      (throw (ex-info "Invalid GPG key" {:status 422})))))

(defn handler [{{{user-id    :user-id
                  gpg-key-id :gpg-key-id} :path-params
                 {route-name :name} :data} :route
                body   :body
                method :request-method
                tx     :tx}]
  (case route-name

    :user-gpg-keys
    (case method
      :get  (resp user-id tx)
      :post (let [ascii-key   (-> body :ascii_key presence
                                  (or (throw (ex-info "ascii_key is required" {:status 422}))))
                  fingerprint (extract-fingerprint ascii-key)
                  row         {:user_id     [:cast user-id :uuid]
                               :fingerprint fingerprint
                               :name        (-> body :name presence
                                                (or (throw (ex-info "name is required" {:status 422}))))
                               :ascii_key   ascii-key
                               :description (-> body :description)}]
              (jdbc/execute-one! tx (-> (sql/insert-into :gpg_keys)
                                        (sql/values [row])
                                        sql-format))
              (resp user-id tx)))

    :user-gpg-key
    (case method
      :get    (or (jdbc/execute-one! tx (-> (keys-sql user-id)
                                             (sql/where [:= :id [:cast gpg-key-id :uuid]])
                                             (sql-format {:inline false})))
                  {:status 404 :body "GPG key not found"})
      :delete (do (jdbc/execute-one! tx (-> (sql/delete-from :gpg_keys)
                                             (sql/where [:= :id [:cast gpg-key-id :uuid]])
                                             (sql/where [:= :user_id [:cast user-id :uuid]])
                                             sql-format))
                  (resp user-id tx)))))
