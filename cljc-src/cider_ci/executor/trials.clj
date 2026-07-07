(ns cider-ci.executor.trials
  (:require
    [cider-ci.executor.scripts :as scripts]
    [cheshire.core :as json]
    [org.httpkit.client :as http-client]
    [taoensso.timbre :refer [info warn]])
  (:import [java.io File FileInputStream]))

;; Maps trial-id (string) → load (double) for all actively running trials.
(defonce ^:private active-trials* (atom {}))

(defn active-trial-ids [] (vec (keys @active-trials*)))
(defn active-load [] (reduce + 0.0 (vals @active-trials*)))


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
        work-dir   (File. (System/getProperty "java.io.tmpdir") (str "cider-ci-" id))]
    (swap! active-trials* assoc id trial-load)
    (try
      (patch-trial! trial opts "executing" {})

      (let [clone-proc (-> (ProcessBuilder. ["git" "clone" git_url (.getAbsolutePath work-dir)])
                           .start)]
        (when-not (zero? (.waitFor clone-proc))
          (throw (ex-info "git clone failed" {:git-url git_url}))))

      (let [co-proc (-> (ProcessBuilder. ["git" "checkout" commit_id])
                        (.directory work-dir)
                        .start)]
        (when-not (zero? (.waitFor co-proc))
          (throw (ex-info "git checkout failed" {:commit-id commit_id}))))

      (let [{:keys [trial-state scripts]} (scripts/run-all! (.getAbsolutePath work-dir) task_spec)]
        (info "Trial" id "finished with" trial-state)
        (upload-script-logs! trial opts scripts)
        (patch-trial! trial opts trial-state
                      {:scripts_results (strip-log-files scripts)}))

      (catch Exception e
        (warn "Trial" id "failed with exception:" (.getMessage e))
        (patch-trial! trial opts "defective" {:error (.getMessage e)}))

      (finally
        (swap! active-trials* dissoc id)
        (delete-dir! work-dir)))))
