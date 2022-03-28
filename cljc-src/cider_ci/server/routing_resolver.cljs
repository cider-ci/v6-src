(ns cider-ci.server.routing-resolver
  (:require
    [cider-ci.server.resources.init.page :as init]
    [cider-ci.server.resources.root :as root]
    [cider-ci.server.routes :as routes]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))



(def route-page-table
  {:root #'root/page
   :init #'init/page
   })
