(ns cider-ci.server.db.core
  (:refer-clojure :exclude [str keyword])
  (:require
    ;[ring.util.codec]
    [cider-ci.utils.core :refer [str keyword]]
    [clojure.tools.logging :as logging]
    [cuerdas.core :as string :refer [snake kebab upper human]]
    [environ.core :refer [env]]
    [hikari-cp.core :as hikari]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [logbug.catcher :as catcher]
    [logbug.debug :as debug :refer [I> I>> identity-with-logging]]
    [logbug.ring :refer [wrap-handler-with-logging]]
    [logbug.thrown :as thrown]
    [next.jdbc :as jdbc]
    [pg-types.all]
    [taoensso.timbre :refer [debug info warn error spy]]))


(defonce ds* (atom nil))

(defn get-ds [] @ds*)


;;; CLI ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn long-opt-for-key [k]
  (str "--" (kebab k) " " (-> k snake upper)))

(def db-name-key :db-name)
(def db-port-key :db-port)
(def db-host-key :db-host)
(def db-user-key :db-user)
(def db-password-key :db-password)
(def db-min-pool-size-key :db-min-pool-size)
(def db-max-pool-size-key :db-max-pool-size)
(def options-keys [db-name-key db-port-key db-host-key
                   db-user-key db-password-key
                   db-min-pool-size-key db-max-pool-size-key])

(def cli-options
  [[nil (long-opt-for-key db-name-key) "Database name, falls back to PGDATABASE | cider-ci"
    :default (or (some-> db-name-key env)
                 (some-> :pgdatabase env)
                 "leihs")]
   [nil (long-opt-for-key db-port-key) "Database port, falls back to PGPORT or 5432"
    :default (or (some-> db-port-key env Integer/parseInt)
                 (some-> :pgport env Integer/parseInt)
                 5432)
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be an integer between 0 and 65536"]]
   [nil (long-opt-for-key db-host-key) "Database host, falls back to PGHOST | localhost"
    :default (or (some-> db-host-key env)
                 (some-> :pghost env)
                 "localhost")]
   [nil (long-opt-for-key db-user-key) "Database user, falls back to PGUSER | 'cider-ci'"
    :default (or (some-> db-user-key env)
                 (some-> :pguser env)
                 "leihs")]
   [nil (long-opt-for-key db-password-key) "Database password, falls back to PGPASSWORD |'cider-ci'"
    :default (or (some-> db-password-key env)
                 (some-> :pgpassword env)
                 "leihs")]
   [nil (long-opt-for-key db-min-pool-size-key)
    :default (or (some-> db-min-pool-size-key env Integer/parseInt)
                 2)
    :parse-fn #(Integer/parseInt %)]
   [nil (long-opt-for-key db-max-pool-size-key)
    :default (or (some-> db-max-pool-size-key env Integer/parseInt)
                 16)
    :parse-fn #(Integer/parseInt %)]])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn wrap-tx [handler]
  (fn [request]
    (jdbc/with-transaction [tx @ds*]
      (try
        (let [resp (handler (assoc request :tx tx))]
          (when-let [status (:status resp)]
            (when (>= status 400 )
              (logging/warn "Rolling back transaction because error status " status)
              (.rollback tx)))
          resp)
        (catch Throwable th
          (logging/warn "Rolling back transaction because of " (.getMessage th))
          (debug (.get-cause th))
          (.rollback tx)
          (throw th))))))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn close []
  (when @ds*
    (do
      (logging/info "Closing db pool ...")
      (-> @ds* :datasource hikari/close-datasource)
      (reset! ds* nil)
      (logging/info "Closing db pool done."))))

(defn init-ds [db-options]
  (close)
  (reset!
    ds*
    {:datasource
     (hikari/make-datasource
       {:auto-commit        true
        :read-only          false
        :connection-timeout 30000
        :validation-timeout 5000
        :idle-timeout       (* 1 60 1000) ; 1 minute
        :max-lifetime       (* 1 60 60 1000) ; 1 hour
        :minimum-idle       (get db-options db-min-pool-size-key)
        :maximum-pool-size  (get db-options db-max-pool-size-key)
        :pool-name          "db-pool"
        :adapter            "postgresql"
        :username           (get db-options db-user-key)
        :password           (get db-options db-password-key)
        :database-name      (get db-options db-name-key)
        :server-name        (get db-options db-host-key)
        :port-number        (get db-options db-port-key)
        :register-mbeans    false})}))

(defn init
  ([options]
   (let [db-options (select-keys options options-keys)]
     (info "Initializing db " db-options)
     (init-ds db-options)
     (info "Initialized db " @ds*)
     @ds*)))

