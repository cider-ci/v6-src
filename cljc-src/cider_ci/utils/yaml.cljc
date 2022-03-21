(ns cider-ci.utils.yaml
  (:require
    #?(:cljs ["js-yaml" :as yaml])
    [clojure.walk]
    ))

(defn parse [s]
  (clojure.walk/keywordize-keys
    #?(:clj (throw (ex-info "YMAL not implement yet" {}))
       :cljs (-> s yaml/load js->clj))))
