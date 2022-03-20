(ns cider-ci.server.main
  (:require
    [cider-ci.server.http.spa :as spa]
    [cider-ci.server.routing :as routing]
    [cider-ci.shared.logging :as logging]
    [taoensso.timbre :refer [debug info warn error]]
    ))

(defn ^:dev/after-load init [& args]
  (js/console.log "(re-)initializing application ...")
  (logging/init)
  (routing/init)
  (spa/mount))
