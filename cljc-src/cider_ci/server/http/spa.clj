(ns cider-ci.server.http.spa
  (:refer-clojure :exclude [keyword str])
  (:require
    [cider-ci.utils.cli :refer [long-opt-for-key]]
    [cider-ci.utils.core :refer [keyword presence str]]
    [cider-ci.utils.json :as json]
    [cider-ci.utils.query-params :refer [encode-primitive]]
    [clojure.java.io :as io]
    [hiccup.page :refer [html5 include-js include-css]]
    [logbug.debug :as debug :refer [debug-ns]]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(defn head []
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport"
           :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
   (include-css "/cider-ci/public/css/main.css")])

(def js-manifest
  (some-> "cider-ci/public/js/manifest.edn"
          io/resource
          slurp
          read-string))

(def js-includes
  (->> js-manifest spy seq
       (map :output-name)
       (map #(str "/cider-ci/public/js/" %))
       (map hiccup.page/include-js)))

(defn html-handler [{user :authenticated-entity :as request}]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (html5
           (head)
           [:body {:data-user (-> user json/to-json encode-primitive)}
            [:div#app
             [:div.container
              [:h1 "Cider-CI"]
              [:p "Loading application ..."]]]]
           js-includes)})

(defn dispatch [root-handler request]
  (if (and (-> request :route :data :bypass-spa not)
           (= :html (-> request :accept :mime)))
    (html-handler request)
    (root-handler request)))

(defn wrap [handler]
  (fn [request]
    (dispatch handler request)))

;#### debug ###################################################################
;(debug/debug-ns 'cider-ci.utils.shutdown)
;(debug/debug-ns *ns*)
;(logbug.debug/wrap-with-log-debug #'resource)
