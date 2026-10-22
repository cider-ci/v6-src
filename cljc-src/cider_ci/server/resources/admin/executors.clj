(ns cider-ci.server.resources.admin.executors
  (:require
    [cider-ci.utils.core :refer [presence]]
    [clojure.string :as str]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]))


(defn- sha256 [^String s]
  (format "%064x"
    (BigInteger. 1
      (.digest (java.security.MessageDigest/getInstance "SHA-256")
               (.getBytes s "UTF-8")))))


(defn- parse-traits [s]
  (if (str/blank? s)
    []
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?)
         (map str/lower-case))))


(defn- traits-literal [traits]
  (str "{" (str/join "," traits) "}"))


(def ^:private active-states "('dispatching', 'executing', 'aborting')")


(defn- fetch-list [tx]
  (jdbc/execute! tx
    [(str "SELECT e.id, e.name, e.token_part,
             array_to_string(e.traits, ',') AS traits,
             e.max_load, e.enabled, e.last_seen_at, e.created_at,
             COALESCE(a.current_load, 0) AS current_load,
             COALESCE(a.trials_count, 0) AS trials_count
      FROM executors e
      LEFT JOIN (SELECT t.executor_id,
                        sum(tsk.load) AS current_load,
                        count(*)      AS trials_count
                 FROM trials t
                 JOIN tasks tsk ON tsk.id = t.task_id
                 WHERE t.state IN " active-states "
                 GROUP BY t.executor_id) a ON a.executor_id = e.id
      ORDER BY e.name ASC")]))


(defn- fetch-current-trials
  "The trials the executor is currently working on, oldest dispatch first."
  [tx executor-id]
  (jdbc/execute! tx
    [(str "SELECT t.id, t.state, t.dispatched_at, t.started_at,
             tsk.id AS task_id, tsk.name AS task_name, tsk.load,
             j.id AS job_id, j.name AS job_name,
             j.project_id, j.commit_id
      FROM trials t
      JOIN tasks tsk ON tsk.id = t.task_id
      JOIN jobs j    ON j.id = tsk.job_id
      WHERE t.executor_id = ?::uuid
        AND t.state IN " active-states "
      ORDER BY t.dispatched_at ASC NULLS LAST, t.created_at ASC")
     executor-id]))


(defn- list-resp [tx]
  {:body (fetch-list tx)})


(defn handler [{{{executor-id :executor-id} :path-params
                 {route-name :name} :data} :route
                body   :body
                method :request-method
                tx     :tx}]
  (case route-name

    :executors
    (case method
      :get  (list-resp tx)
      :post (let [token    (str (java.util.UUID/randomUUID))
                  name     (-> body :name presence
                               (or (throw (ex-info "name is required" {:status 422}))))
                  traits   (parse-traits (or (:traits body) ""))
                  max-load (double (or (:max_load body) 4.0))]
              (jdbc/execute-one! tx
                ["INSERT INTO executors (name, token_hash, token_part, max_load, traits)
                  VALUES (?, ?, ?, ?, CAST(? AS text[]))"
                 name (sha256 token) (subs token 0 8) max-load (traits-literal traits)])
              {:body {:token     token
                      :executors (fetch-list tx)}}))

    :executor-detail
    (case method
      :get (if-let [ex (jdbc/execute-one! tx
                         ["SELECT id, name, token_part,
                                   array_to_string(traits, ',') AS traits,
                                   max_load, enabled, last_seen_at
                            FROM executors WHERE id = ?::uuid"
                          executor-id])]
             (let [trials (fetch-current-trials tx executor-id)]
               {:body (assoc ex
                             :current_trials trials
                             :current_load (reduce + 0 (map #(double (or (:load %) 0)) trials)))})
             {:status 404 :body "Executor not found"})
      :patch (let [enabled  (:enabled body)
                   new-name (presence (:name body))
                   traits   (when (contains? body :traits)
                               (parse-traits (or (:traits body) "")))]
               (when (or (some? enabled) new-name)
                 (let [updates (cond-> {:updated_at [:raw "now()"]}
                                 (some? enabled) (assoc :enabled enabled)
                                 new-name        (assoc :name new-name))]
                   (jdbc/execute-one! tx
                     (-> (sql/update :executors)
                         (sql/set updates)
                         (sql/where [:= :id [:cast executor-id :uuid]])
                         sql-format))))
               (when traits
                 (jdbc/execute-one! tx
                   ["UPDATE executors SET traits = CAST(? AS text[]), updated_at = now() WHERE id = ?::uuid"
                    (traits-literal traits) executor-id]))
               (list-resp tx))
      :delete (do
                (jdbc/execute-one! tx
                  (-> (sql/delete-from :executors)
                      (sql/where [:= :id [:cast executor-id :uuid]])
                      sql-format))
                (list-resp tx)))))
