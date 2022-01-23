(ns cider-ci.server.db.migrations.main
  (:require
    [cider-ci.server.db.core :as db]
    [clj-yaml.core :as yaml]
    [clojure.pprint :refer [pprint]]
    [clojure.tools.cli :as cli :refer [parse-opts]]
    [environ.core :refer [env]]
    [taoensso.timbre :refer [debug info warn error]]
    ))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def cli-options
  (concat
    [["-h" "--help"]
     ["-s" "--start START_VERSION" "Migrate down to START_VERSION before migrating up to TARGET_VERSION"
      :default nil
      :parse-fn yaml/parse-string]
     ["-t" "--target TARGET_VERSION" "Migrate up to TARGET_VERSION, defaults to max avail version"
      :default nil
      :parse-fn yaml/parse-string]]))

(defn main-usage [options-summary & more]
  (->> ["Cider-ci"
        ""
        "usage: cider-ci [<opts>] server [<server-opts>] db [<db-opts>] migrate [<migration-opts>] ..."
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

(defn migrate [options]
  (info 'migrate options))


(defn main [gopts args]
  (debug 'main {'gopts gopts 'args args})
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args cli-options :in-order true)
        cmd (some-> arguments first keyword)
        pass-on-args (->> (rest arguments) flatten (into []))
        options (merge gopts options)
        print-summary #(println (main-usage summary {:args args :options options}))]
    (info {'args args 'options options 'cmd cmd 'pass-on-args pass-on-args})
    (if
      (:help options) (print-summary)
      (migrate options))))


