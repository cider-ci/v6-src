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
    [clojure.java.io :as io]
    [taoensso.timbre :refer [debug warn]]
    )

  (:import
    [java.io DataInputStream File InputStream]
    [java.lang ProcessBuilder]
    ))

;;; http git ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; git http-backend writes CGI style: header lines (Status, Content-Type, ...)
;; up to an empty line, then the body. The body of a git-upload-pack response
;; is the pack a clone or fetch asks for, hundreds of MB for the bigger
;; projects. It must not be held in memory: reading it all exhausted a
;; 512 MB heap when two fresh executors cloned at once (2026-11-12), and
;; streaming it over the http-kit channel was worse, http-kit queues sent
;; chunks without backpressure and a 4 GB heap filled within seconds. So the
;; body is spooled to a temporary file and returned as a File: http-kit
;; memory-maps it and writes it as the client reads, outside the heap. The
;; files are swept after a few minutes; a file still being sent survives
;; the unlink, the mapping keeps it.

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

(def ^:private spool-dir
  (delay (doto (io/file (System/getProperty "java.io.tmpdir") "cider-ci-git-http-backend")
           .mkdirs)))

(def ^:private spool-max-age-ms (* 10 60 1000))

(defn- sweep-spool-dir!
  "Deletes spool files older than spool-max-age-ms."
  []
  (let [limit (- (System/currentTimeMillis) spool-max-age-ms)]
    (doseq [^File f (.listFiles ^File @spool-dir)]
      (when (< (.lastModified f) limit)
        (.delete f)))))

(defn build-response
  "The CGI head as status and headers, the body spooled to a temporary file."
  [^Process process]
  (sweep-spool-dir!)
  (let [pout (DataInputStream. (.getInputStream process))
        head (read-cgi-head pout)
        file (File/createTempFile "git-http-backend-" ".body" @spool-dir)]
    (try
      (with-open [out (io/output-stream file)]
        (.transferTo ^InputStream pout out))
      (.waitFor process)
      (debug "git http-backend: spooled" (.length file) "bytes to" (.getName file))
      (assoc head :body file)
      (catch Throwable th
        (warn "git http-backend: spooling failed:" (.getMessage th))
        (.destroy process)
        (.delete file)
        (throw th)))))

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
      (build-response process))))

