(ns cider-ci.server.db.migrations.main
  (:require
    [cider-ci.server.db.core :as db :refer [get-ds]]
    [cider-ci.server.db.migrations.migrations :as migrations]
    [clj-yaml.core :as yaml]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.set :refer [difference union]]
    [clojure.tools.cli :as cli :refer [parse-opts]]
    [environ.core :refer [env]]
    [next.jdbc :as jdbc]
    [next.jdbc.sql :as jdbc-sql]
    [logbug.debug :as debug]
    [taoensso.timbre :refer [debug info warn error spy]]
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
  (apply (-> migrations/migrations (get 0) (get :up)) [(get-ds)])
  (debug "applied migration 0")
  (->> ["SELECT * FROM migrations ORDER BY id;"]
       (jdbc/execute! (get-ds))
       spy
       (map :id)
       spy
       (apply sorted-set)
       spy
       ))

(defn migrate-downs! [ds downs]
  (doseq [down (reverse downs)]
    (info "migrating down " down " ... ")
    ((-> migrations/migrations (get down) (get :down)) ds)
    (jdbc-sql/delete! ds :migrations {:id down})
    (info "migrated down " down)))

(defn migrate-ups! [ds ups]
  (doseq [up ups]
    (info "migrating up " up " ... ")
    ((-> migrations/migrations (get up) (get :up)) ds)
    (jdbc-sql/insert! ds :migrations {:id up})
    (info "migrated up " up)))

(defn migrate [options]
  (info 'migrate options)
  (db/init options)
  (let [target (:target options)
        migrated (init options)
        available (migrations/available)
        start (or (:start options)
                  (apply max migrated))
        downs (->> migrated
                   (filter #(> % start))
                   spy
                   (into (sorted-set)))
        ups (->> (difference available migrated)
                 (union downs)
                 (filter #(<= % target))
                 spy
                 (into (sorted-set)))]
    (info {'migrated migrated
           'available available
           'start start
           'target target
           'downs downs
           'ups ups})
    (jdbc/with-transaction [tx (get-ds)]
      (migrate-downs! tx downs)
      (migrate-ups! tx ups))))

(defn main [gopts args]
  (debug 'main {'gopts gopts 'args args})
  (try
    (let [{:keys [options arguments errors summary]}
          (cli/parse-opts args cli-options :in-order true)
          cmd (some-> arguments first keyword)
          pass-on-args (->> (rest arguments) flatten (into []))
          options (merge gopts options)
          print-summary #(println (main-usage summary {:args args :options options}))]
      (info {'args args 'options options 'cmd cmd 'pass-on-args pass-on-args})
      (if
        (:help options) (print-summary)
        (migrate options)))
    (catch Exception e
      (warn e))))


;#### debug ###################################################################
;(debug/debug-ns *ns*)

