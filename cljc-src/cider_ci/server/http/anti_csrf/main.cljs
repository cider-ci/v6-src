(ns cider-ci.server.http.anti-csrf.main
  (:require
   [cider-ci.server.http.core :refer [ANTI_CRSF_TOKEN_COOKIE_NAME]])
  (:import
   [goog.net Cookies]))

(defn token []
  (let [cookies (Cookies. js/document)]
    (.get cookies ANTI_CRSF_TOKEN_COOKIE_NAME)))

(defn hidden-form-group-token-component []
  [:div.form-group
   [:input
    {:name :csrf-token
     :type :hidden
     :value (token)}]])
