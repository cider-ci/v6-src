(ns cider-ci.server.resources.admin.settings
  (:require
    [cider-ci.server.db.settings :refer [get-settings]]
    [clojure.core.memoize :as memoize]
    [next.jdbc :as jdbc]))


(defn- fetch [tx]
  (jdbc/execute-one! tx
    ["SELECT external_base_url,
             trial_dispatch_timeout::text AS trial_dispatch_timeout
      FROM settings WHERE id = 0"]))


(defn handler [{{{route-name :name} :data} :route
                body   :body
                method :request-method
                tx     :tx}]
  (case route-name
    :admin-settings
    (case method
      :get  {:body (fetch tx)}
      :patch (let [{:keys [external_base_url trial_dispatch_timeout]} body]
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
                     (throw (ex-info "Invalid interval value" {:status 422})))))
               (memoize/memo-clear! get-settings)
               {:body (fetch tx)}))))
