(ns cider-ci.server.main
  (:require
    [cider-ci.server.http.spa :as spa]
    [cider-ci.server.routing :as routing]
    [cider-ci.shared.logging :as logging]
    [cider-ci.server.state :as state]
    [taoensso.timbre :refer [debug info warn error]]
    ))

(defn ^:dev/after-load init [& args]
  (js/console.log "(re-)initializing application ...")
  (logging/init)
  (state/init)
  (routing/init)
  (spa/mount))
