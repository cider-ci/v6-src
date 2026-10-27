(ns cider-ci.utils.accepted-repositories-test
  (:require
    [cider-ci.utils.accepted-repositories :as ar]
    [clojure.test :refer [deftest is testing]]))


(def url "https://github.com/leihs/leihs.git")

(deftest matches?-test
  (testing "exact value"
    (is (ar/matches? url url))
    (is (not (ar/matches? "https://github.com/leihs/leihs" url))))
  (testing "regex patterns must be anchored with ^ and $"
    (is (ar/matches? "^https://github\\.com/(leihs|Madek)/.+$" url))
    (is (not (ar/matches? "^https://github\\.com/Madek/.+$" url)))
    (is (not (ar/matches? "github\\.com/leihs/.+" url)) "unanchored string is an exact value"))
  (testing "an invalid regex matches nothing"
    (is (not (ar/matches? "^https://github.com/(leihs$" url)))))

(deftest normalize-entries-test
  (is (= [{:url "a"}
          {:url "b" :branches ["^master$"]}
          {:url "c" :branches ["master" "^release/.+$"]}
          {:url "d" :jobs ["^deploy_dev.*$"]}
          {:url "e" :branches ["master"] :jobs ["deploy_staging" "deploy_test"]}]
         (ar/normalize-entries ["a"
                                {:url "b" :branches "^master$"}
                                {"url" "c" "branches" ["master" "^release/.+$"]}
                                {:url "d" :jobs "^deploy_dev.*$"}
                                {"url" "e" "branches" "master" "jobs" ["deploy_staging" "deploy_test"]}
                                {:branches "x"}
                                42]))))

(deftest origin-policy-test
  (let [entries (ar/normalize-entries
                  [{:url "^https://github\\.com/leihs/.+$" :branches "^master$"}
                   {:url url :branches ["^release/.+$"]}
                   "^https://github\\.com/Madek/.+$"])]
    (is (= {:reachable? false :head-branches ["^master$" "^release/.+$"]}
           (ar/origin-policy entries url "test")))
    (is (= {:reachable? true :head-branches []}
           (ar/origin-policy entries "https://github.com/Madek/Madek.git" "test")))
    (is (nil? (ar/origin-policy entries "https://example.com/x.git" "test")))
    (is (= {:reachable? true :head-branches []} (ar/origin-policy ar/accept-all url "test")))
    (is (nil? (ar/origin-policy [] url "test")) "an empty list accepts nothing")))

(deftest origin-policy-jobs-test
  (let [entries (ar/normalize-entries
                  [{:url url :jobs "^test.*$"}
                   {:url url :branches "^master$" :jobs ["^deploy_dev.*$" "deploy_staging"]}])]
    (testing "an entry for other jobs does not relax the deploy job's branch restriction"
      (is (= {:reachable? false :head-branches ["^master$"]}
             (ar/origin-policy entries url "deploy_staging")))
      (is (= {:reachable? false :head-branches ["^master$"]}
             (ar/origin-policy entries url "deploy_dev_a_with_data_reset"))))
    (is (= {:reachable? true :head-branches []} (ar/origin-policy entries url "test-deploy")))
    (is (nil? (ar/origin-policy entries url "deploy_staging_no_restore")) "exact job key")
    (is (nil? (ar/origin-policy entries url nil)) "a job restriction does not accept a missing key"))
  (is (= {:reachable? true :head-branches []}
         (ar/origin-policy [{:url url}] url nil))
      "without jobs any job is accepted, also a missing key"))

(deftest assert-accepted!-test
  (is (nil? (ar/assert-accepted! ar/accept-all url "test")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not included in the accepted repositories"
                        (ar/assert-accepted! [{:url "^https://example\\.com/.*$"}] url "test")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not included in the accepted jobs"
                        (ar/assert-accepted! [{:url url :jobs ["^deploy_.*$"]}] url "test")))
  (is (nil? (ar/assert-accepted! [{:url url :jobs ["^deploy_.*$"]}] url "deploy_test")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no repository url"
                        (ar/assert-accepted! ar/accept-all "" "test"))))
