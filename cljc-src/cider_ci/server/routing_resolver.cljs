(ns cider-ci.server.routing-resolver
  (:require
    [cider-ci.server.resources.init.page :as init]
    [cider-ci.server.resources.commits.main :as commits]
    [cider-ci.server.resources.projects.main :as projects]
    [cider-ci.server.resources.root :as root]
    [cider-ci.server.resources.sign-in.page :as sign-in]
    [cider-ci.server.resources.users.user.email-addresses :as user-email-addresses]
    [cider-ci.server.resources.users.user.main :as user]
    [cider-ci.server.resources.users.user.password :as user-password]
    [cider-ci.server.routes :as routes]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(def route-page-table
  {:root root/components
   :init init/components
   :commits commits/components
   :projects projects/components
   :sign-in sign-in/components
   :user user/components
   :user-email-addresses user-email-addresses/components
   :user-password user-password/components})
