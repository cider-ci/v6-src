(ns cider-ci.server.resources.users.user.password
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

(defn handler [{{password :password} :body
                {{user-id :user-id} :path-params} :route
                tx :tx :as request}]
  (assert (passwords/upsert tx password user-id))
  {:status 204})
