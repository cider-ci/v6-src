; Copyright © 2013 - 2018 Dr. Thomas Schank <Thomas.Schank@AlgoCon.ch>
; Licensed under the terms of the GNU Affero General Public License v3.
; See the "LICENSE.txt" file provided with this software.

(ns cider-ci.server.projects.repositories.http-backend
  (:refer-clojure :exclude [str keyword])
  (:require
    [cider-ci.server.executors.auth :as auth]
    [cider-ci.server.projects.repositories.shared :refer [path]]
    [cider-ci.utils.core :refer [keyword str presence]]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [org.httpkit.server :as http-kit]
    [taoensso.timbre :refer [debug warn]]
    )

  (:import
    [java.io ByteArrayInputStream DataInputStream InputStream]
    [java.lang ProcessBuilder]
    [java.util Arrays]
    ))

;;; http git ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; git http-backend writes CGI style: header lines (Status, Content-Type, ...)
;; up to an empty line, then the body. The body of a git-upload-pack response
;; is the pack a clone or fetch asks for, hundreds of MB for the bigger
;; projects, so it is streamed to the client in chunks (chunked transfer
;; encoding) instead of being read into memory: reading it all exhausted a
;; 512 MB heap when two fresh executors cloned at once (2026-11-12), and the
;; handler would hold its DB connection for the whole transfer.

(defn- read-cgi-head
  "Reads the CGI header lines, up to and including the empty line."
  [^DataInputStream pout]
  (loop [response {:status 200 :headers {}}
         line (.readLine pout)]
    (if-not (and line (not (re-matches #"^\s*$" line)))
      response
      (let [[k v] (clojure.string/split line #":\s+" 2)]
        (recur
          (if (re-matches #"(?i)^status" k)
            (let [status (-> v (clojure.string/split #"\s+" 2) first Integer/parseInt)]
              (assoc response :status status))
            (assoc-in response [:headers k] v))
          (.readLine pout))))))

(def ^:private chunk-size (* 64 1024))

(defn- stream-response!
  "Sends the head and then the rest of the process output in chunks over
   the http-kit channel; closes the channel at the end. When the client
   goes away, the git process is killed."
  [ch ^Process process]
  (let [^InputStream pout (.getInputStream process)
        buf (byte-array chunk-size)]
    (try
      (let [head (read-cgi-head (DataInputStream. pout))]
        (http-kit/send! ch (assoc head :body nil) false)
        (loop []
          (let [n (.read pout buf)]
            (when-not (neg? n)
              (if (http-kit/send! ch (ByteArrayInputStream. (Arrays/copyOf buf n)) false)
                (recur)
                (do (debug "git http-backend: client went away, killing the process")
                    (.destroy process)))))))
      (catch Throwable th
        (warn "git http-backend: streaming failed:" (.getMessage th))
        (.destroy process))
      (finally
        (http-kit/close ch)
        (.close pout)))))

(defn- repository-exists? [project-id tx]
  (some? (jdbc/execute-one! tx
           (-> (sql/select :id)
               (sql/from :repositories)
               (sql/where [:= :id project-id])
               sql-format))))

(defn http-handler [{request-method :request-method
                     remote-addr :remote-addr
                     {{:keys [project-id repository-path]} :path-params} :route
                     query-string :query-string
                     headers :headers
                     tx :tx
                     :as request}]
  (cond
    (nil? (auth/find-executor tx (get headers "authorization")))
    {:status 401
     :headers {"WWW-Authenticate" "Bearer realm=\"cider-ci\""}
     :body "Unauthorized: valid executor token required"}

    (not (repository-exists? project-id tx))
    {:status 404 :body "no such repository"}

    :else
    (let [content-length (get-in request [:headers "content-length"])
          env {"PATH" (System/getenv "PATH")
               "GIT_PROJECT_ROOT" (.toString (path {:project-id project-id}))
               "PATH_INFO" (str "/" repository-path)
               "GIT_HTTP_EXPORT_ALL" "true"
               "REMOTE_USER" (or (-> request :authenticated-entity :primary_email_address)
                                 "unknown user")
               "REMOTE_ADDR" (or remote-addr "localhost")
               "CONTENT_TYPE" (get-in request [:headers "content-type"])
               "CONTENT_LENGTH" content-length
               "HTTP_CONTENT_ENCODING" (get-in request [:headers "content-encoding"])
               "GIT_PROTOCOL" (get-in request [:headers "git-protocol"])
               "QUERY_STRING" query-string
               "REQUEST_METHOD" (clojure.string/upper-case (str request-method))}
          process-builder (ProcessBuilder. (into-array String ["git" "http-backend"]))
          process-environment (.environment process-builder)
          _ (.clear process-environment)
          _ (doseq [[k v] env] (when v (.put process-environment k v)))
          process (.start process-builder)
          _ (when-let [is (:body request)]
              (let [os (.getOutputStream process)]
                (future (try (.transferTo is os)
                             (finally (.close is) (.close os))))))]
      ;; the response is streamed after the handler (and its transaction)
      ;; returned; authorization happened above
      (http-kit/as-channel request
        {:on-open (fn [ch] (future (stream-response! ch process)))}))))

