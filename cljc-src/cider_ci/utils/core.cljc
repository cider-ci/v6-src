(ns cider-ci.utils.core
  (:refer-clojure :exclude [str keyword])
  (:require #?@(:clj [[clojure.tools.logging :as log]
                      [clojure.pprint :refer [code-dispatch pprint with-pprint-dispatch]]] )
            [clojure.string :refer [trim-newline]]))

(defn str
  "Like clojure.core/str but maps keywords to strings without preceding colon."
  ([] "")
  ([x]
   (if (keyword? x)
     (subs (clojure.core/str x) 1)
     (clojure.core/str x)))
  ([x & yx]
   (apply clojure.core/str  (concat [(str x)] (apply str yx)))))

(defn keyword
  "Like clojure.core/keyword but coerces an unknown single argument x
  with (-> x cider-ci.utils.core/str cider-ci.utils.core/keyword).
  In contrast clojure.core/keyword will return nil for anything
  not being a String, Symbol or a Keyword already (including
  java.util.UUID, Integer)."
  ([name] (cond (keyword? name) name
                :else (clojure.core/keyword (str name))))
  ([ns name] (clojure.core/keyword ns name)))

(defn deep-merge [& vals]
  (if (every? map? vals)
    (apply merge-with deep-merge vals)
    (last vals)))

(defn presence [v]
  "Returns nil if v is a blank string or if v is an empty collection.
   Returns v otherwise."
  (cond
    (string? v) (if (clojure.string/blank? v) nil v)
    (coll? v) (if (empty? v) nil v)
    :else v))

(defn presence! [v]
  "Pipes v through presence returns the result of that iff it is not nil.
  Throws an IllegalStateException otherwise. "
  (or (-> v presence)
      (throw
        (new
          #?(:clj IllegalStateException
             :cljs js/Error)
          "The argument must neither be nil, nor an empty string nor an empty collection."))))

(defn flip [f]
  (fn [& xs]
    (apply f (reverse xs))))

#?(:clj
   (defmacro spy-with
     "Like clojure.tools.logging/spy but takes a function which will be applied before logging."
     ([func expr]
      `(spy-with :debug ~func ~expr))
     ([level func expr]
      `(let [x# ~expr]
         (log/log ~level
                  (let [s# (with-out-str
                             (with-pprint-dispatch code-dispatch
                               (pprint '(~func ~expr))
                               (print "=> ")
                               (pprint (~func x#))))]
                    (trim-newline s#)))
         x#))))

(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn remove-nils
  "Dissociate all keys from the map where the value is nil."
  [m]
  (into {} (filter second m)))

(defn remove-blanks
  "Dissociate all keys from the map where the value is blank."
  [m]
  (->> m
       (filter (fn [[_ v]] (presence v)))
       (into {})))

(defn update-vals "Apply function to all values in map."
  [f m]
  (reduce-kv (fn [memo k v]
               (assoc memo k (f v)))
             {}
             m))

(defn raise
  ([msg] (throw (ex-info msg {})))
  ([msg m] (throw (ex-info msg m)))
  ([msg m c] (throw (ex-info msg m c))))
