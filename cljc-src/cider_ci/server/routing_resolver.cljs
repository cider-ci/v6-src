(ns cider-ci.server.routing-resolver
  (:require
    [cider-ci.server.resources.admin.gpg-keys :as admin-gpg-keys]
    [cider-ci.server.resources.init.page :as init]
    [cider-ci.server.resources.commits.main :as commits]
    [cider-ci.server.resources.projects.blob :as project-blob]
    [cider-ci.server.resources.projects.branch :as project-branch]
    [cider-ci.server.resources.projects.commit :as project-commit]
    [cider-ci.server.resources.projects.jobs :as project-jobs]
    [cider-ci.server.resources.projects.main :as projects]
    [cider-ci.server.resources.projects.project :as project]
    [cider-ci.server.resources.root :as root]
    [cider-ci.server.resources.sign-in.page :as sign-in]
    [cider-ci.server.resources.users.main :as users]
    [cider-ci.server.resources.users.user.email-addresses :as user-email-addresses]
    [cider-ci.server.resources.users.user.gpg-keys :as user-gpg-keys]
    [cider-ci.server.resources.users.user.main :as user]
    [cider-ci.server.resources.users.user.password :as user-password]
    [cider-ci.server.routes :as routes]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(def route-page-table
  {:admin-gpg-key  admin-gpg-keys/components
   :admin-gpg-keys admin-gpg-keys/components
   :root root/components
   :init init/components
   :commits commits/components
   :project  project/components
   :project-blob project-blob/components
   :project-branch project-branch/components
   :project-commit project-commit/components
   :project-jobs project-jobs/components
   :project-job project-jobs/components
   :projects projects/components
   :sign-in sign-in/components
   :users users/components
   :user user/components
   :user-email-addresses user-email-addresses/components
   :user-gpg-key  user-gpg-keys/components
   :user-gpg-keys user-gpg-keys/components
   :user-password user-password/components})
