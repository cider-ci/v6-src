(ns cider-ci.server.html.server
  (:refer-clojure :exclude [str keyword])
  (:require
    [cider-ci.utils.cli :refer [long-opt-for-key]]
    [environ.core :refer [env]]
    [org.httpkit.server :as http-kit]
    [taoensso.timbre :refer [debug info warn error]]))


;;; cli-options ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce options* (atom nil))


(def ncpus (.availableProcessors (Runtime/getRuntime)))
(def http-server-port-key :http-server-port)
(def http-server-bind-key :http-server-bind)
(def http-server-threads-key :http-server-threads)
(def options-keys [http-server-port-key http-server-bind-key http-server-threads-key])

(def cli-options
  [[nil (long-opt-for-key http-server-port-key)
    :default (or (some-> http-server-port-key env Integer/parseInt) 3838)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   [nil (long-opt-for-key http-server-bind-key)
    :default (or (some-> http-server-bind-key env) "localhost")]
   [nil (long-opt-for-key http-server-threads-key)
    :default (or (some-> http-server-threads-key env Integer/parseInt)
                 (-> ncpus (/ 4) Math/ceil int))
    :parse-fn #(Integer/parseInt %)
    :validate [#(<= 1 % ncpus) "Must be an integer <= num cpus"]]])


;;; server ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce server* (atom nil))

(defn stop []
  (when-not (nil? @server*)
    (info "stopping HTTP server" @server*)
    (@server* :timeout 100)
    (reset! server* nil)))

(defn init [handler all-options]
  (reset! options* (select-keys all-options options-keys))
  (stop)
  (info "starting HTTP server " @options* " ...")
  (reset! server*
          (http-kit/run-server
            handler
            {:ip (http-server-bind-key @options*)
             :port (http-server-port-key @options*)
             :thread (http-server-threads-key @options*)
             :worker-name-prefix "http-server-worker-"}))
  (info "started HTTP server"))
