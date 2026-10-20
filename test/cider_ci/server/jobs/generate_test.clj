(ns cider-ci.server.jobs.generate-test
  (:require [clojure.test :refer [deftest is testing]]
            [cider-ci.server.jobs.generate :as generate]))

(deftest merge-generated-tasks-test
  (let [generated {"spec/a_spec.rb" {:environment_variables {:CIDER_CI_TASK_FILE "spec/a_spec.rb"}}
                   "spec/models/b_spec.rb" {:environment_variables {:CIDER_CI_TASK_FILE "spec/models/b_spec.rb"}}}]
    (testing "an explicit entry for a generated file is deep-merged into it (keeps CIDER_CI_TASK_FILE)"
      (let [tasks (generate/merge-generated-tasks
                    generated {(keyword "spec/models/b_spec.rb") {:priority 1}})]
        (is (= #{"spec/a_spec.rb" "spec/models/b_spec.rb"} (set (keys tasks)))
            "no extra task from the namespaced keyword key")
        (is (= {:priority 1 :environment_variables {:CIDER_CI_TASK_FILE "spec/models/b_spec.rb"}}
               (get tasks "spec/models/b_spec.rb")))))
    (testing "explicit tasks that match no generated file are added as they are"
      (let [tasks (generate/merge-generated-tasks generated {:extra {:scripts {:main {:body "x"}}}})]
        (is (= {:scripts {:main {:body "x"}}} (get tasks "extra")))
        (is (= 3 (count tasks)))))
    (testing "explicit values win over generated ones on conflict"
      (let [tasks (generate/merge-generated-tasks
                    generated {(keyword "spec/a_spec.rb") {:environment_variables {:CIDER_CI_TASK_FILE "other"}}})]
        (is (= "other" (get-in tasks ["spec/a_spec.rb" :environment_variables :CIDER_CI_TASK_FILE])))))))
