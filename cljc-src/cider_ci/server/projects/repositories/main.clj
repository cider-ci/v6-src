(ns cider-ci.server.projects.repositories.main
  (:refer-clojure :exclude [str keyword])
  (:require
    [cider-ci.server.projects.repositories.fetch-and-update.main :as repositories-fetch-and-update]
    [cider-ci.server.projects.repositories.state.main :as repositories-state]
    [cider-ci.utils.core :refer [keyword str]]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))


(defn init [opts]
  (repositories-state/init opts)
  (repositories-fetch-and-update/init opts))

