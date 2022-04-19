(ns cider-ci.server.resources.users.user.email-addresses
  (:require
    [cider-ci.utils.core :refer [presence]]
    [cider_ci.server.entities.passwords :as passwords]
    [cider_ci.server.entities.users :as users]
    [cuerdas.core :as string]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(defn email-addresses-where-sql [query user-id]
  (-> query (sql/where [:= :user_id [:cast user-id :uuid]])))

(defn email-address-where-sql [query user-id email-address]
  (-> query (email-addresses-where-sql user-id)
      (sql/where [:= [:lower :email_address] [:lower email-address]])))

(defn email-addresses-sql [user-id]
  (-> (sql/from :email_addresses)
      (sql/select :*)
      (email-addresses-where-sql user-id)
      (sql/order-by [:is_primary :desc] [[:lower :email_address] :asc])))

(defn delete-email-address-sql [user-id email-address]
  (-> (sql/delete-from :email_addresses)
      (email-address-where-sql user-id email-address)))

(defn upsert-sql [user-id data]
  (-> (sql/insert-into :email_addresses)
      (sql/values [(-> data
                       (select-keys [:is_primary
                                     :email_address])
                       (assoc :user_id [:cast user-id :uuid]))])
      (sql/on-conflict :email_address)
      (sql/do-update-set :is_primary)))

(defn update-sql [user-id email-address data])

(defn handler [{{{user-id :user-id
                  email-address :email-address} :path-params
                 {route-name :name} :data} :route
                data :body
                method :request-method
                tx :tx :as request}]
  (case route-name

    :user-email-addresses
    {:body (jdbc/execute!
             tx (-> user-id email-addresses-sql
                    (sql-format {:inline false})))}

    :user-email-address
    (case method
      :delete (do (->> [user-id email-address]
                       (apply delete-email-address-sql)
                       sql-format (jdbc/execute! tx))
                  {:body (->> [user-id] (apply email-addresses-sql)
                              sql-format (jdbc/execute! tx))})

      :patch (do (when (:is_primary data)
                   (->> (-> (sql/update :email_addresses)
                            (sql/set {:is_primary false})
                            (email-addresses-where-sql user-id)
                            sql-format)
                        (jdbc/execute! tx)))
                 (->> (-> (sql/update :email_addresses)
                          (sql/set (select-keys data [:is_primary]))
                          (email-address-where-sql user-id email-address)
                          sql-format)
                      (jdbc/execute! tx))
                 {:body (->> [user-id] (apply  email-addresses-sql)
                             sql-format (jdbc/execute! tx))}))))



