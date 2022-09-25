(ns cider-ci.shared.logging
  (:require
    #?(:clj [taoensso.timbre.tools.logging])
    [taoensso.timbre :as timbre :refer [debug info]]))

(def LOGGING_CONFIG
  {:min-level [[#{
                  ;"cider-ci.server.resources.users.user.email-addresses"
                  ;"cider-ci.server.routes"
                  ;"cider-ci.server.projects.*"
                  ;"cider-ci.server.db.migrations.main"
                  ; "cider-ci.server.http.authorization"
                  ;"cider-ci.server.projects.repositories.git.commits"
                  ;"cider-ci.server.projects.repositories.branch-updates.*"
                  ;"cider-ci.server.projects.repositories.git-sql"
                  ;"cider-ci.server.projects.repositories.sql.commits.depth"
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
   #?(:clj (taoensso.timbre.tools.logging/use-timbre))
   (info "initialized logging " (pr-str timbre/*config*))))
