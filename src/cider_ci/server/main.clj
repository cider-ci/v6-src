(ns cider-ci.server.main
  (:require
    [cider-ci.server.db.core :as db]
    [clj-yaml.core :as yaml]
    [clojure.pprint :refer [pprint]]
    [clojure.tools.cli :as cli :refer [parse-opts]]
    [environ.core :refer [env]]
    [taoensso.timbre :refer [debug info warn error]]
    )
  (:gen-class))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def cli-options
  (concat
    [["-h" "--help"]]
    db/cli-options
    ))

(defn main-usage [options-summary & more]
  (->> ["Cider-ci"
        ""
        "usage: cider-ci [<opts>] server [<server-opts>] SCOPE|COMMAND [<scope-opts>] ..."
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


(defonce args* (atom nil))

(defn main [gopts args]
  (let [args @args*
        {:keys [options arguments errors summary]}
        (cli/parse-opts args cli-options :in-order true)
        pass-on-args (->> [options (rest arguments)]
                          flatten (into []))
        cmd (some-> arguments first keyword)
        options (merge gopts options)
        print-summary #(println (main-usage summary {:args args :options options}))]
    (info *ns* {'args args 'options options 'cmd cmd})
    (cond
      (:help options) (print-summary)
      :else (case cmd
              (print-summary)))))


