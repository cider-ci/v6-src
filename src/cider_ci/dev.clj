(ns cider-ci.dev
  (:require
    [cider-ci.server.db.core]
    [cider-ci.shared.repl]
    [clojure.tools.namespace.repl :as ctnr :refer [refresh disable-reload!]]
    [taoensso.timbre :refer [debug info warn error]]))

(defonce enabled?* (atom false))

(defonce args* (atom nil))

(defonce main* (atom nil))

(defn args [] @args*)

(defn enabled? [] @enabled?*)

(defn init [main args]
  (info 'init [main args])
  (disable-reload!)
  (disable-reload! (create-ns 'cider-ci.shared.repl))
  (reset! enabled?* true)
  (reset! args* (or args []))
  (reset! main* main))

(defn reload! []
  (if-not @enabled?*
    (debug "reload not enabled, skipping reload!")
    (do (info "reload! invoked")
        (cider-ci.server.db.core/close)
        (refresh)
        (@main* @args*))))
