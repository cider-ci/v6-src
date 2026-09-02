(ns cider-ci.server.resources.admin.settings
  (:require
    [cider-ci.server.db.settings :refer [get-settings]]
    [clojure.core.memoize :as memoize]
    [next.jdbc :as jdbc]))


(defn- fetch [tx]
  (jdbc/execute-one! tx
    ["SELECT external_base_url,
             trial_dispatch_timeout::text AS trial_dispatch_timeout,
             branch_trigger_max_commit_age_default::text AS branch_trigger_max_commit_age_default
      FROM settings WHERE id = 0"]))


(defn handler [{{{route-name :name} :data} :route
                body   :body
                method :request-method
                tx     :tx}]
  (case route-name
    :admin-settings
    (case method
      :get  {:body (fetch tx)}
      :patch (let [{:keys [external_base_url trial_dispatch_timeout
                            branch_trigger_max_commit_age_default]} body]
               (when external_base_url
                 (jdbc/execute-one! tx
                   ["UPDATE settings SET external_base_url = ? WHERE id = 0"
                    external_base_url]))
               (when trial_dispatch_timeout
                 (try
                   (jdbc/execute-one! tx
                     ["UPDATE settings SET trial_dispatch_timeout = ?::interval WHERE id = 0"
                      trial_dispatch_timeout])
                   (catch Exception _
                     (throw (ex-info "Invalid interval value for trial_dispatch_timeout" {:status 422})))))
               (when (contains? body :branch_trigger_max_commit_age_default)
                 (if (or (nil? branch_trigger_max_commit_age_default)
                         (= "" branch_trigger_max_commit_age_default))
                   (jdbc/execute-one! tx
                     ["UPDATE settings SET branch_trigger_max_commit_age_default = NULL WHERE id = 0"])
                   (try
                     (jdbc/execute-one! tx
                       ["UPDATE settings SET branch_trigger_max_commit_age_default = ?::interval WHERE id = 0"
                        branch_trigger_max_commit_age_default])
                     (catch Exception _
                       (throw (ex-info "Invalid interval value for branch_trigger_max_commit_age_default" {:status 422}))))))
               (memoize/memo-clear! get-settings)
               {:body (fetch tx)}))))
