(ns cider-ci.utils.url
  #?(:clj
     (:require
      [ring.util.codec])))

(def decode
  #?(:cljs js/decodeURIComponent
     :clj ring.util.codec/url-decode))

(def encode
  #?(:cljs js/encodeURIComponent
     :clj ring.util.codec/url-encode))


