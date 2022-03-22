(ns cider-ci.server.run
  (:require
    [cider-ci.server.html.server :as http-server]
    [cider-ci.server.db.core :as db]
    [cider-ci.server.routing :as routing]
    [clojure.pprint :refer [pprint]]
    [clojure.tools.cli :as cli :refer [parse-opts]]
    [environ.core :refer [env]]
    [taoensso.timbre :refer [debug info warn error]]
    ))

(def cli-options
  (concat
    [["-h" "--help"]]
    db/cli-options
    http-server/cli-options
    ))


(defn main-usage [options-summary & more]
  (->> ["Cider-ci"
        ""
        "usage: cider-ci [<opts>] server [<server-opts>] run [<run-opts>]"
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


(defn run [options]
  (info "run with " options)
  (db/init options)
  (let [routes (routing/init options)]
    (http-server/init routes options)
    ))


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
      :else (run options))))
