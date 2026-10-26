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
          {:url "c" :branches ["master" "^release/.+$"]}]
         (ar/normalize-entries ["a"
                                {:url "b" :branches "^master$"}
                                {"url" "c" "branches" ["master" "^release/.+$"]}
                                {:branches "x"}
                                42]))))

(deftest origin-policy-test
  (let [entries (ar/normalize-entries
                  [{:url "^https://github\\.com/leihs/.+$" :branches "^master$"}
                   {:url url :branches ["^release/.+$"]}
                   "^https://github\\.com/Madek/.+$"])]
    (is (= {:reachable? false :head-branches ["^master$" "^release/.+$"]}
           (ar/origin-policy entries url)))
    (is (= {:reachable? true :head-branches []}
           (ar/origin-policy entries "https://github.com/Madek/Madek.git")))
    (is (nil? (ar/origin-policy entries "https://example.com/x.git")))
    (is (= {:reachable? true :head-branches []} (ar/origin-policy ar/accept-all url)))
    (is (nil? (ar/origin-policy [] url)) "an empty list accepts nothing")))

(deftest assert-accepted!-test
  (is (nil? (ar/assert-accepted! ar/accept-all url)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not included in the accepted repositories"
                        (ar/assert-accepted! [{:url "^https://example\\.com/.*$"}] url)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no repository url"
                        (ar/assert-accepted! ar/accept-all ""))))
