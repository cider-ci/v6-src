(ns scripts.build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/cider-ci.jar")

(defn- basis []
  (b/create-basis {:project "deps.edn"}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [opts]
  (clean opts)
  (println "Copying resources...")
  (b/copy-dir {:src-dirs ["resources"]
               :target-dir class-dir})
  (println "Compiling Clojure...")
  (b/compile-clj {:basis      (basis)
                  :src-dirs   ["cljc-src"]
                  :class-dir  class-dir
                  :ns-compile '[cider-ci.main]})
  (println "Building uberjar...")
  (b/uber {:class-dir        class-dir
           :uber-file        uber-file
           :basis            (basis)
           :main             'cider-ci.main
           :conflict-handlers {"^data_readers.clj[cs]?$" :overwrite}})
  (println (str "Built " uber-file)))
