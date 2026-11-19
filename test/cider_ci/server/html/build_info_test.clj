(ns cider-ci.server.html.build-info-test
  (:require
    [cider-ci.server.html.build-info :as build-info]
    [clojure.test :refer [deftest is]]))

(def sha (apply str (repeat 40 "a")))
(def tree (apply str (repeat 40 "b")))

(deftest summary-test
  (is (= {:commit_id sha :commit_url (str "https://github.com/cider-ci/cider-ci_v6/commit/" sha)
          :tree_id tree :dirty false :built_at "t"}
         (build-info/summary {:commit_id sha :tree_id tree :dirty false
                              :origin_url "git@github.com:cider-ci/cider-ci_v6.git" :built_at "t"})))
  (is (true? (:dirty (build-info/summary {:commit_id sha :tree_id tree :dirty true :origin_url "local"}))))
  (is (nil? (:commit_url (build-info/summary {:commit_id sha :tree_id tree :dirty true :origin_url "local"}))))
  (is (nil? (build-info/summary nil))))
