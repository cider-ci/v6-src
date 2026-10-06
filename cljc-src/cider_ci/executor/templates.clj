(ns cider-ci.executor.templates
  "File templates of a task spec (legacy parity with
   cider-ci.executor.trials.templates):

     templates:
       shared reverse proxy config:
         src:  deploy/roles/reverse-proxy-leihs/templates/main.conf
         dest: integration-tests/reverse-proxy/conf/shared.conf

   src and dest are relative to the working dir. The source is rendered with
   Selmer (Django-style `{{ VAR }}`, unknown variables render as empty string
   as in legacy) against the trial's environment variables (with {{KEY}}
   references between them resolved, ports included), repeatedly until the
   output no longer changes, and written to dest before any script runs."
  (:require
    [cider-ci.executor.scripts :as scripts]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [selmer.parser :as selmer]
    [selmer.util :as selmer-util]
    [taoensso.timbre :refer [info]])
  (:import [java.io File]))

(defn render-string
  "Renders s against env (string keys) until a fixpoint (max 10 passes)."
  [s env]
  (let [ctx (into {} (map (fn [[k v]] [(keyword k) v]) env))]
    (selmer-util/without-escaping
      (loop [cur s n 10]
        (let [next (selmer/render cur ctx)]
          (if (or (= next cur) (zero? n)) next (recur next (dec n))))))))

(defn- normalize [templates]
  (cond
    (map? templates)        (map (fn [[k v]] (assoc v :name (name k))) templates)
    (sequential? templates) templates
    :else                   []))

(defn render!
  "Renders every template of the task spec into work-dir. Throws ex-info when a
   source file is missing."
  [^File work-dir templates env-vars]
  (let [env (scripts/apply-templates (scripts/env-str-map env-vars))]
    (doseq [{:keys [src dest] :as t} (normalize templates)]
      (let [src-file  (io/file work-dir (str src))
            dest-file (io/file work-dir (str dest))]
        (when-not (.isFile src-file)
          (throw (ex-info (str "Template source `" src "` (" (or (:name t) "template")
                               ") does not exist in the working directory.")
                          {:template t})))
        (info "Rendering template" src "->" dest)
        (.mkdirs (.getParentFile dest-file))
        (spit dest-file (render-string (slurp src-file) env))))))
