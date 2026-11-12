(ns cider-ci.utils.cron-test
  (:require
    [cider-ci.server.jobs.cron-trigger :as cron-trigger]
    [cider-ci.utils.cron :as cron]
    [clojure.test :refer [deftest is testing]])
  (:import
    [java.time ZonedDateTime ZoneId ZoneOffset]))


(defn- utc [s] (ZonedDateTime/parse s))


(deftest parse-test
  (testing "the expressions of madek and leihs are valid"
    (is (cron/valid? "30 2 * * *"))
    (is (cron/valid? "30 4 * * *"))
    (is (cron/valid? "* * * * *"))
    (is (cron/valid? "0 */6 * * 1-5")))
  (testing "invalid expressions"
    (is (not (cron/valid? "61 * * * *")))
    (is (not (cron/valid? "* * *")))
    (is (not (cron/valid? nil)))
    (is (not (cron/valid? 42))))
  (testing "only weekly timers: month and day of month must be *"
    (is (not (cron/valid? "30 4 1 * *")))
    (is (not (cron/valid? "30 4 * 12 *")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"month field '12'"
                          (cron/parse! "30 4 * 12 *")))
    (is (= 422 (:status (try (cron/parse! "x") (catch clojure.lang.ExceptionInfo e (ex-data e))))))))


(deftest describe-test
  (is (= "at 04:30" (cron/describe "30 4 * * *")))
  (is (= "every minute" (cron/describe "* * * * *"))))


(deftest fire-test
  (testing "fires within the window after the scheduled time, not before or after"
    (is (false? (cron/fire? "30 4 * * *" 3 (utc "2026-11-12T04:29:59Z"))))
    (is (true?  (cron/fire? "30 4 * * *" 3 (utc "2026-11-12T04:30:10Z"))))
    (is (true?  (cron/fire? "30 4 * * *" 3 (utc "2026-11-12T04:32:59Z"))))
    (is (false? (cron/fire? "30 4 * * *" 3 (utc "2026-11-12T04:33:00Z"))))
    (is (false? (cron/fire? "30 4 * * *" 3 (utc "2026-11-12T12:00:00Z")))))
  (testing "every minute always fires"
    (is (true? (cron/fire? "* * * * *" 3 (utc "2026-11-12T12:34:56Z")))))
  (testing "evaluated in UTC whatever the zone of `now` (ZHdK server runs on CET)"
    (let [cet (.withZoneSameInstant (utc "2026-11-12T04:30:10Z") (ZoneId/of "Europe/Zurich"))]
      (is (= 5 (.getHour cet)))
      (is (true?  (cron/fire? "30 4 * * *" 3 cet)))
      (is (false? (cron/fire? "30 5 * * *" 3 cet)))))
  (testing "day of week"
    ;; 2026-11-12 is a Thursday
    (is (true?  (cron/fire? "30 4 * * 4" 3 (utc "2026-11-12T04:30:10Z"))))
    (is (false? (cron/fire? "30 4 * * 3" 3 (utc "2026-11-12T04:30:10Z"))))))


(deftest cron-entries-test
  (testing "map and list form, other types ignored"
    (is (= [{:type "cron" :value "* * * * *"}]
           (cron-trigger/cron-entries {:run_when {:c {:type "cron" :value "* * * * *"}
                                                  :b {:type "branch" :include_match "^.*$"}}})))
    (is (= 1 (count (cron-trigger/cron-entries {:run_when [{:type :cron :value "* * * * *"}]}))))
    (is (empty? (cron-trigger/cron-entries {})))))


(deftest entry-matches-branch-test
  (testing "legacy semantics: no include matches nothing"
    (is (false? (cron-trigger/entry-matches-branch? {} "master")))
    (is (false? (cron-trigger/entry-matches-branch? {:branch_include_match ""} "master"))))
  (testing "include and exclude"
    (is (true?  (cron-trigger/entry-matches-branch? {:branch_include_match "^master$"} "master")))
    (is (false? (cron-trigger/entry-matches-branch? {:branch_include_match "^master$"} "zhdk/staging")))
    (is (true?  (cron-trigger/entry-matches-branch? {:branch_include_match "^zhdk/.*$"} "zhdk/staging")))
    (is (false? (cron-trigger/entry-matches-branch?
                  {:branch_include_match "^zhdk/.*$" :branch_exclude_match "staging"} "zhdk/staging")))))


(deftest due-entry-test
  (let [job {:key "deploy_staging"
             :cron-entries [{:type "cron" :value "30 2 * * *" :branch_include_match "^zhdk/staging$" :rerun true}]}]
    (is (= (first (:cron-entries job))
           (cron-trigger/due-entry job "zhdk/staging" (utc "2026-11-12T02:31:00Z"))))
    (is (nil? (cron-trigger/due-entry job "zhdk/staging" (utc "2026-11-12T02:34:00Z"))))
    (is (nil? (cron-trigger/due-entry job "master" (utc "2026-11-12T02:31:00Z")))))
  (testing "an invalid expression never fires and does not throw"
    (is (nil? (cron-trigger/due-entry {:key "x" :cron-entries [{:value "bogus" :branch_include_match "^.*$"}]}
                                      "master" (ZonedDateTime/now ZoneOffset/UTC))))))
