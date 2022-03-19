(ns cider-ci.server.db.main
  (:require
    [cider-ci.server.db.core :as db]
    [cider-ci.server.db.migrations.main :as migrations]
    [clj-yaml.core :as yaml]
    [clojure.pprint :refer [pprint]]
    [clojure.tools.cli :as cli :refer [parse-opts]]
    [environ.core :refer [env]]
    [taoensso.timbre :refer [debug info warn error]]
    ))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def cli-options
  (concat
    [["-h" "--help"]]))

(defn main-usage [options-summary & more]
  (->> ["Cider-ci"
        ""
        "usage: cider-ci [<opts>] server [<server-opts>] db [<db-opts>] COMMAND ..."
        ""
        "Commands: migrate "
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


(defn main [gopts args]
  (debug 'main {'gopts gopts 'args args})
  (let [{:keys [options arguments
                errors summary]} (cli/parse-opts args cli-options :in-order true)
        cmd (some-> arguments first keyword)
        pass-on-args (->> (rest arguments) flatten (into []))
        options (merge gopts options)
        print-summary #(println (main-usage summary {:args args :options options}))]
    (info {'args args 'options options 'cmd cmd 'pass-on-args pass-on-args})
    (cond
      (:help options) (print-summary)
      :else (case cmd
              :migrate (migrations/main options pass-on-args)
              (print-summary)))))


