(ns cider-ci.server.main
  (:require
    [cider-ci.shared.logging :as logging]
    [cider-ci.server.routing :as routing]
    [taoensso.timbre :refer [debug info warn error]]
    ))

(defn ^:dev/after-load init [& args]
  (js/console.log "(re-)initializing application ...")
  (logging/init)
  (routing/init))
