(ns cider-ci.executor.main
  (:require
    [cider-ci.executor.sync :as sync]
    [cider-ci.shared.logging :as logging]
    [clojure.tools.cli :as cli :refer [parse-opts]]
    [environ.core :refer [env]]
    [taoensso.timbre :refer [info error]])
  (:gen-class))


(def cli-options
  [["-h" "--help"]
   [nil "--server-url URL" "Server base URL"
    :default (or (:cider-ci-server-url env) "http://localhost:3838")]
   [nil "--token TOKEN" "Executor bearer token"
    :default (:cider-ci-executor-token env)]
   [nil "--max-load N" "Maximum concurrent trials"
    :default (or (some-> :cider-ci-executor-max-load env Integer/parseInt) 4)
    :parse-fn #(Integer/parseInt %)]])


(defn main [args]
  (logging/init)
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (when errors
      (doseq [e errors] (println e))
      (System/exit 1))
    (when-not (:token options)
      (error "No executor token provided. Use --token or set CIDER_CI_EXECUTOR_TOKEN.")
      (System/exit 1))
    (info "Starting executor, connecting to" (:server-url options))
    (sync/start! options)))


(defn -main [& args]
  (main args))
