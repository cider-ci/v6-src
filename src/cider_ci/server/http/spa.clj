(ns cider-ci.server.http.spa
  (:refer-clojure :exclude [keyword str])
  (:require
    [hiccup.page :refer [html5 include-js include-css]]
    [clojure.tools.logging :as logging :refer [debug info warn error]]
    [cider-ci.utils.query-params :refer [encode-primitive]]
    [clojure.java.io :as io]
    [logbug.debug :refer [debug-ns]]
    [cider-ci.utils.json :as json]
    [cider-ci.utils.core :refer [keyword presence str]]
    [cider-ci.utils.cli :refer [long-opt-for-key]]))

(defn head []
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport"
           :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
   (include-css "/cider-ci/public/bootstrap-4.3.1.min.css")])

(def js-manifest
  (some-> "cider-ci/public/js/manifest.edn"
          io/resource
          slurp
          read-string))

(def js-includes
  (->> js-manifest seq
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
