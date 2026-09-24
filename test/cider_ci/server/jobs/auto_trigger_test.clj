(ns cider-ci.server.jobs.auto-trigger-test
  (:require [clojure.test :refer [deftest is testing]]
            [cider-ci.server.jobs.auto-trigger]))

(def ^:private should-trigger? @#'cider-ci.server.jobs.auto-trigger/job-should-trigger?)

(defn- job [spec] {:key "j" :name "j" :spec spec})

(deftest job-should-trigger-test
  (testing "no run_when: never auto-triggered by a branch update (legacy parity)"
    (is (false? (should-trigger? (job {:context {:task "true"}}) "master"))))
  (testing "run_when branch with include_match"
    (let [j (job {:run_when {:any {:type "branch" :include_match "^master$"}}})]
      (is (true?  (should-trigger? j "master")))
      (is (false? (should-trigger? j "feature")))))
  (testing "run_when branch with exclude_match (leihs: no-ci / hotspot branches)"
    (let [j (job {:run_when {:always {:type "branch" :include_match "^.*$"
                                      :exclude_match "^.*(no-ci|hotspot).*$"}}})]
      (is (true?  (should-trigger? j "master")))
      (is (false? (should-trigger? j "tom/hotspot-x")))))
  (testing "blank include_match matches every branch; list form accepted"
    (is (true? (should-trigger? (job {:run_when [{:type "branch"}]}) "whatever"))))
  (testing "run_when of other types (job, cron) do not fire on branch updates"
    (is (false? (should-trigger? (job {:run_when {:dep {:type "job" :job_key "x" :states ["passed"]}}}) "master")))
    (is (false? (should-trigger? (job {:run_when {:c {:type "cron" :value "* * * * *"}}}) "master"))))
  (testing "older trigger.branch shorthand still works"
    (let [j (job {:trigger {:branch {:include_match "^master$"}}})]
      (is (true?  (should-trigger? j "master")))
      (is (false? (should-trigger? j "feature")))))
  (testing "jobs with depends_on are left to the dep-trigger"
    (is (false? (should-trigger? (job {:depends_on {:d {:type "job" :job_key "a" :states ["passed"]}}
                                       :run_when {:any {:type "branch"}}}) "master")))))
