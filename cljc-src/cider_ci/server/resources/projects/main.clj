(ns cider-ci.server.resources.projects.main
  (:require
    [cider-ci.utils.core :refer [presence]]
    [cider_ci.server.entities.users :as users]
    [cider_ci.server.entities.passwords :as passwords]
    [cuerdas.core :as string]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(defn handler [{{{user-id :user-id} :path-params
                 {route-name :name} :data} :route
                request-method :request-method
                tx :tx :as request}]

  (warn request)
  (case route-name
    :projects (case request-method
                :post (warn "CREATE PROJECT")

                )))
