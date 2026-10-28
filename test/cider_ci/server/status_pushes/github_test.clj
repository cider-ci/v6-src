(ns cider-ci.server.status-pushes.github-test
  (:require
    [cider-ci.server.status-pushes.github :as github]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))


(deftest github-state-test
  (is (= "pending" (github/github-state "pending")))
  (is (= "pending" (github/github-state "executing")))
  (is (= "success" (github/github-state "passed")))
  (is (= "failure" (github/github-state "failed")))
  (doseq [s ["aborting" "aborted" "defective" "whatever"]]
    (is (= "error" (github/github-state s)) s)))

(deftest parse-git-url-test
  (let [leihs {:host "github.com" :owner "leihs" :name "leihs"}]
    (is (= leihs (github/parse-git-url "https://github.com/leihs/leihs.git")))
    (is (= leihs (github/parse-git-url "https://github.com/leihs/leihs")))
    (is (= leihs (github/parse-git-url "https://github.com/leihs/leihs/")))
    (is (= leihs (github/parse-git-url "git@github.com:leihs/leihs.git")))
    (is (= leihs (github/parse-git-url "ssh://git@github.com/leihs/leihs.git")))
    (is (= leihs (github/parse-git-url "https://GitHub.com/leihs/leihs.git"))))
  (is (= {:host "ghe.example.org" :owner "team" :name "repo"}
         (github/parse-git-url "https://ghe.example.org:8443/group/team/repo.git")))
  (is (nil? (github/parse-git-url "local")))
  (is (nil? (github/parse-git-url nil))))

(deftest remote-test
  (let [repo {:git_url "https://github.com/leihs/leihs-admin.git" :remote_api_token "t"}]
    (is (= {:endpoint "https://api.github.com" :owner "leihs" :name "leihs-admin" :token "t"}
           (github/remote repo)))
    (testing "explicit endpoint, owner and name"
      (is (= {:endpoint "https://ghe.example.org/api/v3" :owner "o" :name "n" :token "t"}
             (github/remote (assoc repo
                                   :remote_api_endpoint "https://ghe.example.org/api/v3/"
                                   :remote_api_namespace "o" :remote_api_name "n")))))
    (testing "the project cannot push"
      (is (re-find #"No GitHub token" (:error (github/remote (dissoc repo :remote_api_token)))))
      (is (re-find #"No GitHub token" (:error (github/remote (assoc repo :remote_api_token " ")))))
      (is (re-find #"No GitHub API endpoint"
                   (:error (github/remote (assoc repo :git_url "https://gitlab.com/o/n.git")))))
      (is (= "https://gitlab.example/api"
             (:endpoint (github/remote (assoc repo :git_url "https://gitlab.com/o/n.git"
                                              :remote_api_endpoint "https://gitlab.example/api")))))
      (is (re-find #"Cannot derive" (:error (github/remote (assoc repo :git_url "local"
                                                                   :remote_api_endpoint "http://x")))))
      (is (re-find #"only implemented for GitHub"
                   (:error (github/remote (assoc repo :remote_api_type "gitlab"))))))))

(deftest statuses-url-test
  (is (= "https://api.github.com/repos/leihs/leihs/statuses/abc"
         (github/statuses-url {:endpoint "https://api.github.com" :owner "leihs" :name "leihs"} "abc"))))

(deftest payload-test
  (let [job {:id "j1" :project_id "leihs" :commit_id "c1" :key "test" :name "Test" :state "passed"}]
    (is (= {:state       "success"
            :context     "Cider-CI@ci.example.org - Test"
            :description "passed - Test"
            :target_url  "https://ci.example.org/projects/leihs/commits/c1/jobs/j1"}
           (github/payload job "https://ci.example.org/")))
    (is (= "Cider-CI@ci.example.org - test"
           (:context (github/payload (dissoc job :name) "https://ci.example.org"))) "key without name")
    (let [d (:description (github/payload (assoc job :name (str/join (repeat 200 "x")))
                                          "https://ci.example.org"))]
      (is (= 140 (count d)))
      (is (str/ends-with? d "…")))))
