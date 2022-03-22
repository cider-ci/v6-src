(ns cider-ci.server.main
  (:require
    [cider-ci.server.html.spa :as spa]
    [cider-ci.server.routing :as routing]
    [cider-ci.shared.logging :as logging]
    [cider-ci.server.state :as state]
    [taoensso.timbre :refer [debug info warn error]]
    ))

(defn ^:dev/after-load init [& args]
  (js/console.log "(re-)initializing application ...")
  (logging/init)
  (state/init)
  (spa/mount)
  (routing/init)

  )
