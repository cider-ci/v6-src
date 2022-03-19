(ns cider-ci.utils.query-params
  (:refer-clojure :exclude [str keyword encode decode])
  (:require
    [cider-ci.utils.core :refer [keyword str presence]]
    [cider-ci.utils.json :refer [to-json from-json try-parse-json]]
    #?(:clj [ring.util.codec])
    [clojure.walk :refer [keywordize-keys]]
    [clojure.string :as string]))

(def decode-primitive
  #?(:cljs js/decodeURIComponent
     :clj ring.util.codec/url-decode))

(def encode-primitive
  #?(:cljs js/encodeURIComponent
     :clj ring.util.codec/url-encode))

(defn decode [query-string & {:keys [parse-json?]
                              :or {parse-json? false}}]
  (let [parser (if parse-json? try-parse-json identity)]
    (->> (if-not (presence query-string) [] (string/split query-string #"&"))
         (reduce
           (fn [m part]
             (let [[k v] (string/split part #"=" 2)]
               (assoc m (-> k decode-primitive keyword)
                      (-> v decode-primitive parser))))
           {})
         keywordize-keys)))

(defn encode [params]
  (->> params
       (map (fn [[k v]]
              (str (-> k str encode-primitive)
                   "="
                   (-> (if (coll? v) (to-json v) v)
                       str encode-primitive))))
       (clojure.string/join "&")))
