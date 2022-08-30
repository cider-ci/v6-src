(ns cider-ci.utils.uuid
  (:import
    [java.util UUID]))

(defprotocol Uuid
  (uuid [x]))

(extend-protocol Uuid
  java.lang.String
  (uuid [s] (UUID/fromString s))
  java.util.UUID
  (uuid [uuid] uuid))
