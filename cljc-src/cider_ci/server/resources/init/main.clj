(ns cider-ci.server.resources.init.main
  (:require
    [cider-ci.utils.core :refer [presence]]
    [next.jdbc :as jdbc]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [taoensso.timbre :refer [debug info warn error]]
    ))

(defn assert-no-admin [tx]
  (assert (empty?  (-> (sql/select 1)
                       (sql/from :users)
                       (sql/where [:= :users.is_admin true])
                       (sql-format)
                       (#(jdbc/execute! tx %))))
          "Expected no existing admin"))

(defn handler [{{email :email password :password} :body
                {{route-name :name} :data} :route
                method :request-method
                tx :tx :as request}]
  (assert (= method :put) "Expected put request")
  (assert (= route-name :init) "Expected init route")
  (assert (presence email) "Expected non empty email")
  (assert (presence password) "Expected non empty password")
  (assert-no-admin tx)
  (warn "TODO " request))
