(ns cider-ci.executor.trials
  (:require
    [cider-ci.executor.git :as git]
    [cider-ci.executor.ports :as ports]
    [cider-ci.executor.scripts :as scripts]
    [cheshire.core :as json]
    [org.httpkit.client :as http-client]
    [taoensso.timbre :refer [info warn]])
  (:import [java.io File FileInputStream]))

;; Maps trial-id (string) → load (double) for all actively running trials.
(defonce ^:private active-trials* (atom {}))

(defn active-trial-ids [] (vec (keys @active-trials*)))
(defn active-load [] (reduce + 0.0 (vals @active-trials*)))

(defn abort-executing-trial!
  "Signals abort for a running trial (kills its running script processes)."
  [trial-id]
  (when (contains? @active-trials* trial-id)
    (info "Aborting trial" trial-id)
    (scripts/abort-trial! trial-id)))

(defn- put-attachment! [{:keys [id] :as _trial} {:keys [server-url token]} attachment-name content-type content]
  (let [url  (str server-url "/executor/trials/" id "/attachments/" attachment-name)
        resp @(http-client/put url
                {:headers {"Authorization" (str "Bearer " token)
                           "Content-Type"  content-type}
                 :body    content
                 :timeout 30000})]
    (when-not (#{200 201 204} (:status resp))
      (warn "PUT" url "returned HTTP" (:status resp) (:body resp)))))


(defn- patch-trial! [{:keys [patch_path] :as _trial} {:keys [server-url token]} state extra]
  (let [url  (str server-url patch_path)
        body (json/generate-string (merge {:state state} extra))
        resp @(http-client/patch url
                {:headers {"Authorization" (str "Bearer " token)
                           "Content-Type"  "application/json"
                           "Accept"        "application/json"}
                 :body    body
                 :timeout 10000})]
    (when-not (#{200 201 204} (:status resp))
      (warn "PATCH" url "returned HTTP" (:status resp) (:body resp)))
    resp))


(defn- delete-dir! [^File dir]
  (when (.exists dir)
    (doseq [^File f (.listFiles dir)]
      (if (.isDirectory f)
        (delete-dir! f)
        (.delete f)))
    (.delete dir)))

(defn sweep-working-dirs!
  "Deletes stale cider-ci working dirs left over from a previous crashed executor.
   Skipped when running inside a CIDER-CI trial to avoid deleting the parent
   trial's working directory."
  []
  (when-not (System/getenv "CIDER_CI")
    (let [tmpdir (File. (System/getProperty "java.io.tmpdir"))]
      (doseq [^File f (.listFiles tmpdir)
              :when (and (.isDirectory f)
                         (.startsWith (.getName f) "cider-ci-"))]
        (info "Sweeping stale working dir:" (.getName f))
        (delete-dir! f)))))


(defn- upload-trial-attachments! [trial opts ^File work-dir task-spec]
  (when-let [attachments (:trial_attachments task-spec)]
    (let [work-path (.toPath work-dir)]
      (doseq [[key-kw spec] attachments
              :let [key-str      (name key-kw)
                    pattern      (re-pattern (:include_match spec))
                    content-type (or (:content_type spec) "application/octet-stream")]
              ^File f (file-seq work-dir)
              :when   (.isFile f)]
        (let [rel-str (str (.relativize work-path (.toPath f)))]
          (when (re-find pattern rel-str)
            (put-attachment! trial opts (str key-str "/" rel-str)
                             content-type (FileInputStream. f))))))))


(defn- upload-script-logs! [trial opts script-results]
  (doseq [[key-str result] script-results]
    (when-let [^File log-file (:log-file result)]
      (when (.exists log-file)
        (put-attachment! trial opts (str "scripts/" key-str) "text/plain"
                         (FileInputStream. log-file))
        (.delete log-file)))))


(defn- strip-log-files [script-results]
  (into {} (for [[k v] script-results] [k (dissoc v :log-file)])))


(defn execute! [{:keys [id git_url commit_id task_spec] :as trial} opts]
  (info "Executing trial" id)
  (let [trial-load (double (or (:load task_spec) 1.0))
        work-dir   (File. (System/getProperty "java.io.tmpdir") (str "cider-ci-" id))
        port-env   (when (seq (:ports task_spec))
                     (ports/reserve! (:ports task_spec)))
        env-vars   (merge {"CIDER_CI"             "true"
                           "CONTINUOUS_INTEGRATION" "true"
                           "CIDER_CI_TRIAL_ID"     id
                           "CIDER_CI_WORKING_DIR"  (.getAbsolutePath work-dir)}
                          (:environment_variables task_spec)
                          port-env)]
    (swap! active-trials* assoc id trial-load)
    (try
      (patch-trial! trial opts "executing" {})

      (git/prepare-working-dir! git_url commit_id work-dir (:git_options task_spec) (:token opts))

      (let [{:keys [trial-state scripts]} (scripts/run-all! (.getAbsolutePath work-dir) task_spec env-vars id)]
        (info "Trial" id "finished with" trial-state)
        (upload-script-logs! trial opts scripts)
        (upload-trial-attachments! trial opts work-dir task_spec)
        (patch-trial! trial opts trial-state
                      {:scripts_results (strip-log-files scripts)}))

      (catch Exception e
        (warn "Trial" id "failed with exception:" (.getMessage e))
        (patch-trial! trial opts "defective" {:error (.getMessage e)}))

      (finally
        (scripts/clear-abort! id)
        (when port-env (ports/release! port-env))
        (swap! active-trials* dissoc id)
        (delete-dir! work-dir)))))
