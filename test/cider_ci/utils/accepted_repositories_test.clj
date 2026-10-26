(ns cider-ci.utils.accepted-repositories-test
  (:require
    [cider-ci.utils.accepted-repositories :as ar]
    [clojure.test :refer [deftest is testing]]))


(def url "https://github.com/leihs/leihs.git")

(deftest accepted?-test
  (testing "exact url"
    (is (ar/accepted? [url] url))
    (is (not (ar/accepted? ["https://github.com/leihs/leihs"] url))))
  (testing "regex entries must be anchored with ^ and $"
    (is (ar/accepted? ["^https://github\\.com/(leihs|Madek)/.+$"] url))
    (is (not (ar/accepted? ["^https://github\\.com/Madek/.+$"] url)))
    (is (not (ar/accepted? ["github\\.com/leihs/.+"] url)) "unanchored string is an exact url"))
  (testing "accept all"
    (is (ar/accepted? ar/accept-all url)))
  (testing "empty list accepts nothing"
    (is (not (ar/accepted? [] url))))
  (testing "an invalid regex is ignored, other entries still apply"
    (is (not (ar/accepted? ["^https://github.com/(leihs$"] url)))
    (is (ar/accepted? ["^https://github.com/(leihs$" url] url))))

(deftest assert-accepted!-test
  (is (nil? (ar/assert-accepted! ar/accept-all url)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not included in the accepted repositories"
                        (ar/assert-accepted! ["^https://example\\.com/.*$"] url)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no repository url"
                        (ar/assert-accepted! ar/accept-all ""))))
