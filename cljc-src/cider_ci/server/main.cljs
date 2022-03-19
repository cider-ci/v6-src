(ns cider-ci.server.main
  (:require
    [cider-ci.shared.logging :as logging]
    [taoensso.timbre :refer [debug info warn error]]
    ))


(defn ^:dev/after-load init [& args]
  (js/console.log "(re-)initializing application ...")

  )
