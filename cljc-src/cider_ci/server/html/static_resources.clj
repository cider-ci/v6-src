(ns cider-ci.server.html.static-resources
  (:require
    [clojure.core.memoize :as memoize]
    [digest]
    [logbug.debug :as debug]
    [ring.middleware.resource :as resource]
    [ring.util.codec :as codec]
    [ring.util.request :as request]
    [ring.util.response :as response]
    [taoensso.timbre :refer [debug info warn error spy]]
    ))

(defn- path-matches? [path xp]
  (boolean
    (some (fn [p]
            (if (string? p)
              (= p path)
              (re-find p path)))
          xp)))

;### never expire resource ####################################################

(defn never-expire-resource [path request root-path options]
  (some-> request
          (assoc :path-info (codec/url-encode path))
          (resource/resource-request root-path options)
          (update-in [:headers] dissoc "Last-Modified")
          (assoc-in [:headers "Cache-Control"] "public, max-age=31536000")))


;### busted resource ##########################################################


(defonce cache-bust-path->original-path* (atom {}))
(defonce original-path->cache-bust-path* (atom {}))

(defn- extension [path]
  (->> path
       (re-matches #".*\.([^\.]+)")
       last))

(defn- cache-busted-resource? [path options]
  (or (path-matches? path (:cache-bust-paths options))
      (get @cache-bust-path->original-path* path)))

(defn- cache-bust! [path request root-path options]
  (when-let [response (resource/resource-request request root-path options)]
    (if-not (:body response)
      response
      (let [signature (-> response :body slurp digest/sha1)
            extension (extension path)
            cache-bust-path (str path "_" signature "." extension)]
        (swap! cache-bust-path->original-path* assoc cache-bust-path path)
        (swap! original-path->cache-bust-path* assoc path cache-bust-path)
        (info "cache busted " path " -> " cache-bust-path)
        (ring.util.response/redirect
          (str (:context request) cache-bust-path))))))

(defn- cache-bust [path request root-path options]
  (if-let [original-path (get @cache-bust-path->original-path* path)]
    (never-expire-resource original-path request root-path options)
    (cache-bust! path request root-path options)))


(defn cache-busted-path [path]
  (or (get @original-path->cache-bust-path* path)
      path))

;##############################################################################



(defn- resource [request root-path options]
  (debug 'resource [request root-path options])
  (let [path (-> request request/path-info codec/url-decode)]
    (debug {'path path})
    (cond
      (not (:cache-enabled? options)) (resource/resource-request
                                        request root-path options)

      (cache-busted-resource?
        path options) (cache-bust path request root-path options)

      (path-matches?
        path (:never-expire-paths options)) (never-expire-resource
                                              path request root-path options)
      :else (resource/resource-request request root-path options))))


;##############################################################################

(def default-options
  {:cache-bust-paths []
   :never-expire-paths []
   :cache-enabled? true})

(defn wrap
  "Replacement for ring.middleware.resource/wrap-resource.

  Accepts the following additional options:

  :cache-enabled? - pass directly on to resource/resource-request if set to false,
  regardless of the value of :cache-bust-paths or :never-expire-paths,
  default is true

  :cache-bust-paths - collection, each value is either a string or a regex,
  resources with matching paths will be cache-busted and a redirect
  response to the cache-busted path is send; subsequent calls to
  cache-busted-path will return the cache-busted path.

  :never-expire-paths - collection, each value is either a string or a regex,
  resources with matching paths will be set to never expire

  "

  ([handler root-path]
   (wrap handler root-path default-options))
  ([handler root-path options]
   (let [effectiv-options (merge default-options options)]
     (info " wrapped static-resources routing with " effectiv-options)
     (fn [request]
       (debug 'wrap {'request request 'handler handler 'root-path root-path
                     'effectiv-options effectiv-options})
       (or (resource request root-path effectiv-options)
           (handler request))))))



;#### debug ###################################################################
;(debug/debug-ns 'cider-ci.utils.shutdown)
;(debug/debug-ns *ns*)
;(logbug.debug/wrap-with-log-debug #'resource)
