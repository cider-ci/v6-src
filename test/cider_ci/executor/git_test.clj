(ns cider-ci.executor.git-test
  (:require [clojure.test :refer [deftest is testing]]
            [cider-ci.executor.git]))

(def ^:private with-git-defaults @#'cider-ci.executor.git/with-git-defaults)

(deftest with-git-defaults-test
  (testing "git commands get auto-gc/maintenance disabled right after the binary"
    (let [cmd (with-git-defaults ["git" "submodule" "update" "--init" "db"])]
      (is (= "git" (first cmd)))
      (is (= ["-c" "gc.auto=0" "-c" "gc.autoDetach=false" "-c" "maintenance.auto=false"]
             (subvec cmd 1 7)))
      (is (= ["submodule" "update" "--init" "db"] (subvec cmd 7)))))
  (testing "existing -c options are kept"
    (is (= ["git" "-c" "gc.auto=0" "-c" "gc.autoDetach=false" "-c" "maintenance.auto=false"
            "-c" "http.x/.extraheader=Authorization: Bearer t" "fetch"]
           (with-git-defaults ["git" "-c" "http.x/.extraheader=Authorization: Bearer t" "fetch"]))))
  (testing "non-git commands are untouched"
    (is (= ["ls" "-la"] (with-git-defaults ["ls" "-la"])))))
