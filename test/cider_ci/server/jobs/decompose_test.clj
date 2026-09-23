(ns cider-ci.server.jobs.decompose-test
  (:require [clojure.test :refer [deftest is testing]]
            [cider-ci.server.jobs.decompose :as decompose :refer [decompose tasks->map]]))

(deftest tasks->map-test
  (testing "map form is returned as is"
    (is (= {:a {:scripts {}}} (tasks->map {:a {:scripts {}}}))))
  (testing "list form (leihs scenario task files) is keyed by :name"
    (is (= {(keyword "features/login.feature:8") {:name "features/login.feature:8" :scripts {:test {:body "x"}}}}
           (tasks->map [{:name "features/login.feature:8" :scripts {:test {:body "x"}}}]))))
  (testing "list entries without a name are keyed by position; strings allowed"
    (is (= {:task-0 "echo 1" :task-1 {:scripts {:main {:body "echo 2"}}}}
           (tasks->map ["echo 1" {:scripts {:main {:body "echo 2"}}}]))))
  (testing "nil -> {}"
    (is (= {} (tasks->map nil)))))

(deftest decompose-list-form-tasks-test
  (let [job {:context {:task_defaults {:traits {:Bash true}}
                       :tasks [{:name "features/a.feature:1" :scripts {:test {:body "a"}}}
                               {:name "features/b.feature:2" :scripts {:test {:body "b"}}}]}}
        tasks (decompose job)]
    (testing "one task per list entry, task_defaults merged, name preserved"
      (is (= ["features/a.feature:1" "features/b.feature:2"] (sort (map :name tasks))))
      (is (every? #(= {:Bash true} (:traits %)) tasks))
      (is (= "a" (get-in (first (filter #(= "features/a.feature:1" (:name %)) tasks))
                         [:scripts :test :body]))))))

(deftest decompose-map-form-tasks-test
  (let [tasks (decompose {:tasks {:one {:scripts {:main {:body "1"}}}
                                  :two "echo 2"}})]
    (is (= #{"one" "two"} (set (map :name tasks))))
    (is (= "echo 2" (get-in (first (filter #(= "two" (:name %)) tasks)) [:scripts :main :body])))))
