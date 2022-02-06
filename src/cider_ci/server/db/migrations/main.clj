(ns cider-ci.server.db.migrations.main
  (:require
    [cider-ci.server.db.core :as db]
    [cider-ci.server.db.migrations.migrations :as migrations]
    [clj-yaml.core :as yaml]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.set :refer [difference]]
    [clojure.tools.cli :as cli :refer [parse-opts]]
    [environ.core :refer [env]]
    [next.jdbc :as jdbc]
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
      :default (apply max (migrations/available))
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

(defn init [options]
  (info 'init)
  (apply (-> migrations/migrations (get 0) (get :up)) [@db/ds*])
  (->> ["SELECT * FROM migrations ORDER BY id;"]
       (jdbc/execute! @db/ds*)
       (map :migrations/id)))

(defn migrate-downs! [ds downs]

  )

(defn migrate-ups! [ds ups]
  (doseq [up ups]


    ))

(defn migrate [options]
  (info 'migrate options)
  (db/init options)
  (let [target (:target options)
        migrated (init options)
        available (migrations/available)
        start (or (:start options)
                  (apply max migrated))
        downs (->> migrated
                   (filter #(< % start))
                   (into (sorted-set)))
        ups (->> (difference available migrated)
                 (filter #(<= % target))
                 (into (sorted-set)))]
    (info {'migrated migrated
           'available available
           'start start
           'target target
           'downs downs
           'ups ups})
    (jdbc/with-transaction [tx @db/ds*]
      (migrate-downs! tx downs)
      (migrate-up! tx ups))))

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


