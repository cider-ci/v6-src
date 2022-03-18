(ns cider-ci.shared.logging
  (:require
    [taoensso.timbre :as timbre :refer [debug info]]))

(def LOGGING_CONFIG
  {:min-level [[#{
                  ;"cider-ci.server.*"
                  ;"cider-ci.server.http.spa"
                  } :debug]
               [#{
                  #?(:clj "com.zaxxer.hikari.*")
                  "cider-ci.*"} :info]
               [#{"*"} :warn]]
   :log-level nil})


(defn init
  ([] (init LOGGING_CONFIG))
  ([logging-config]
   (info "initializing logging " logging-config)
   (timbre/merge-config! logging-config)
   (info "initialized logging " (pr-str timbre/*config*))))
