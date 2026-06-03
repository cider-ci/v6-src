(ns cider-ci.main
  (:require
    [cider-ci.dev]
    [cider-ci.executor.main :as executor]
    [cider-ci.server.main :as server]
    [cider-ci.shared.logging :as logging]
    [cider-ci.shared.repl :as repl]
    [clj-yaml.core :as yaml]
    [clojure.pprint :refer [pprint]]
    [clojure.tools.cli :as cli :refer [parse-opts]]
    [environ.core :refer [env]]
    [logbug.catcher :as catcher]
    [logbug.debug :as debug]
    [logbug.thrown :as thrown]
    [taoensso.timbre :refer [debug info warn error]])
  (:gen-class))

;(thrown/reset-ns-filter-regex #"^(cider-ci)\..*")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def cli-options
  (concat
    [["-h" "--help"]
     [nil "--dev-mode DEV_MODE" "dev mode"
      :default (or (some-> :dev-mode env yaml/parse-string) false)
      :parse-fn #(yaml/parse-string %)
      :validate [boolean? "Must parse to a boolean"]]]
    repl/cli-options))

(defn main-usage [options-summary & more]
  (->> ["Cider-ci"
        ""
        "usage: cider-ci [<opts>] SCOPE [<scope-opts>] SCOPE|COMMAND [<scope-opts>] ..."
        ""
        "available scopes: server"
        ""
        "Options:"
        options-summary
        ""
        ""
        (when more
          ["-------------------------------------------------------------------"
           (with-out-str (pprint more))
           "-------------------------------------------------------------------"])]
       flatten (clojure.string/join \newline)))


(defn main [args]
  (logging/init)
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args cli-options :in-order true)
        cmd (some-> arguments first keyword)
        pass-on-args (->> (rest arguments) flatten (into []))
        options (into (sorted-map) options)
        print-summary #(println (main-usage summary {:args args :options options}))]
    (info {'args args 'options options 'cmd cmd 'pass-on-args pass-on-args})
    (when (:dev-mode options) (cider-ci.dev/init #'cider-ci.main/main args))
    (repl/init options)
    (cond
      (:help options) (print-summary)
      :else (case cmd
              :executor (executor/main pass-on-args)
              :server   (server/main options pass-on-args)
              (print-summary)))))

;(cider-ci.dev/reload!)

(defonce args* (atom nil))
(when @args* (main @args*))

(defn -main [& args]
  (reset! args* (or args []))
  (main args))
