(ns cider-ci.executor.trials
  (:require
    [cheshire.core :as json]
    [org.httpkit.client :as http-client]
    [taoensso.timbre :refer [info warn]])
  (:import [java.io File]))


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


(defn- run-scripts! [work-dir task-spec]
  "Run each script in task-spec sequentially. Returns \"passed\" if all succeed,
  \"failed\" on first non-zero exit code."
  (let [scripts (:scripts task-spec)]
    (loop [[[_ script] & rest-scripts] (seq scripts)]
      (if-not script
        "passed"
        (let [body        (:body script)
              script-file (File. ^String work-dir "cider-ci-task.sh")]
          (spit script-file body)
          (.setExecutable script-file true)
          (let [proc      (-> (ProcessBuilder. ["bash" (.getAbsolutePath script-file)])
                              (.directory (File. ^String work-dir))
                              .start)
                exit-code (.waitFor proc)]
            (if (zero? exit-code)
              (recur rest-scripts)
              "failed")))))))


(defn- delete-dir! [^File dir]
  (when (.exists dir)
    (doseq [^File f (.listFiles dir)]
      (if (.isDirectory f)
        (delete-dir! f)
        (.delete f)))
    (.delete dir)))


(defn execute! [{:keys [id git_url commit_id task_spec] :as trial} opts]
  (info "Executing trial" id)
  (let [work-dir (File. (System/getProperty "java.io.tmpdir") (str "cider-ci-" id))]
    (try
      (patch-trial! trial opts "executing" {})

      ;; Clone bare repo to working directory
      (let [clone-proc (-> (ProcessBuilder. ["git" "clone" git_url (.getAbsolutePath work-dir)])
                           .start)]
        (when-not (zero? (.waitFor clone-proc))
          (throw (ex-info "git clone failed" {:git-url git_url}))))

      ;; Checkout the exact commit
      (let [co-proc (-> (ProcessBuilder. ["git" "checkout" commit_id])
                        (.directory work-dir)
                        .start)]
        (when-not (zero? (.waitFor co-proc))
          (throw (ex-info "git checkout failed" {:commit-id commit_id}))))

      ;; Run scripts and report the outcome
      (let [result (run-scripts! (.getAbsolutePath work-dir) task_spec)]
        (info "Trial" id "finished with" result)
        (patch-trial! trial opts result {}))

      (catch Exception e
        (warn "Trial" id "failed with exception:" (.getMessage e))
        (patch-trial! trial opts "defective" {:error (.getMessage e)}))

      (finally
        (delete-dir! work-dir)))))
