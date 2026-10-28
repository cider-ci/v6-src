(ns cider-ci.utils.core-test
  (:require
    [cider-ci.utils.core :refer [redact-secrets]]
    [clojure.test :refer [deftest is]]))


(deftest redact-secrets-test
  (is (= {:db-host "localhost" :db-password "***" :token "***" :client-secret "***"
          :db-user "ci" :empty-password nil}
         (redact-secrets {:db-host "localhost" :db-password "pw" :token "t" :client-secret "s"
                          :db-user "ci" :empty-password nil})))
  (is (sorted? (redact-secrets (sorted-map :a 1)))))
