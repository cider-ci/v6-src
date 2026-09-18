(ns cider-ci.server.html.spa
  (:refer-clojure :exclude [keyword str])
  (:require
    [cider-ci.utils.cli :refer [long-opt-for-key]]
    [cider-ci.utils.core :refer [keyword presence str]]
    [cider-ci.utils.json :as json]
    [cider-ci.server.routes :as routes]
    [cider-ci.server.state :as state]
    [cider-ci.utils.url :as url]
    [clojure.java.io :as io]
    [hiccup.page :refer [html5 include-js include-css]]
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
  (->> js-manifest seq
       (map :output-name)
       (map #(str "/cider-ci/public/js/" %))
       (map hiccup.page/include-js)))

(defn server-state [{tx :tx :as request}]
  (state/db-state tx))

(defn html-handler [{user :user :as request}]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (html5
           (head)
           [:body {:data-user (-> user json/encode url/encode)
                   :data-server-state (-> request server-state json/encode url/encode)}
            [:div#app
             [:div.container
              [:h1 "Cider-CI"]
              [:p "Loading application ..."]]]]
           js-includes)})

(defn redirect-to-sign-in [request]
  (let [return-to (str (:uri request)
                       (when-let [qs (presence (:query-string request))]
                         (str "?" qs)))]
    {:status  302
     :headers {"Location" (routes/path :sign-in {} {:return-to return-to})}}))

(defn dispatch [root-handler {user :user route :route :as request}]
  (if (and (-> route :data :bypass-spa not)
           (= :html (-> request :accept :mime)))
    ;; Serve the SPA shell for a browser page load — but if the route requires
    ;; authentication for reading and nobody is signed in, redirect to the
    ;; sign-in page, remembering the requested URL so we can return afterwards.
    (if (and route
             (empty? user)
             (not (routes/readable-without-auth? (:data route))))
      (redirect-to-sign-in request)
      (html-handler request))
    (root-handler request)))

(defn wrap [handler]
  (fn [request]
    (dispatch handler request)))

