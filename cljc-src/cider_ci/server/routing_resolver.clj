(ns cider-ci.server.routing-resolver
  (:require
    [cider-ci.server.resources.commits.main :as commits]
    [cider-ci.server.resources.init.http :as init]
    [cider-ci.server.resources.projects.main :as projects]
    [cider-ci.server.resources.sign-in.password-authentication :as password-authentication]
    [cider-ci.server.resources.sign-out :as sign-out]
    [cider-ci.server.resources.users.main :as users]
    [cider-ci.server.resources.users.user.email-addresses :as user-email-addresses]
    [cider-ci.server.resources.users.user.main :as user]
    [cider-ci.server.resources.users.user.password :as user-password]
    [cider-ci.server.routes :as routes]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(def route-resource-table
  {:init #'init/handler
   :commits #'commits/handler
   :projects #'projects/handler
   :sign-in-authenticate-password #'password-authentication/handler
   :sign-out #'sign-out/handler
   :users #'users/handler
   :user #'user/handler
   :user-email-address #'user-email-addresses/handler
   :user-email-addresses #'user-email-addresses/handler
   :user-password #'user-password/handler })

(defn route-resolve [handler {uri :uri :as request}]
  (if-let [route (routes/route uri)]
    (let [{{route-name :name} :data} route]
      (debug "matched route " uri " -> " route)
      (handler (-> request
                   (assoc
                     :route route
                     :route-name route-name
                     :route-handler (route-resource-table route-name))
                   (update-in [:params] #(merge {} % (:path-params route))))))
    (do (warn "unresolved route for " uri)
        (handler request))))

(defn wrap [handler]
  (fn [request]
    (route-resolve handler request)))

