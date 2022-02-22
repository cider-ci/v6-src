(ns cider-ci.http.server
  (:refer-clojure :exclude [str keyword])
  (:require
    [clojure.tools.logging :as logging]
    [environ.core :refer [env]]
    [madek.media-service.utils.cli-options :refer [long-opt-for-key]]
    [madek.media-service.utils.core :refer [keyword presence str]]
    [org.httpkit.server :as http-kit]))


;;; cli-options ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce options* (atom nil))


(def ncpus (.availableProcessors (Runtime/getRuntime)))
(def http-server-port-key :http-server-port)
(def http-server-bind-key :http-server-bind)
(def http-server-threads-key :http-server-threads)
(def options-keys [http-server-port-key http-server-bind-key http-server-threads-key])

(def cli-options
  [[nil (long-opt-for-key http-server-port-key)
    :default (or (some-> http-server-port-key env Integer/parseInt) 3180)
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
    (logging/info "stopping HTTP server" @server*)
    (@server* :timeout 100)
    (reset! server* nil)))

(defn init [handler all-options]
  (reset! options* (select-keys all-options options-keys))
  (stop)
  (logging/info "starting HTTP server " @options* " ...")
  (reset! server*
          (http-kit/run-server
            handler
            {:ip (http-server-bind-key @options*)
             :port (http-server-port-key @options*)
             :thread (http-server-threads-key @options*)
             :worker-name-prefix "http-server-worker-"}))
  (logging/info "started HTTP server"))
