(ns cider-ci.server.github-sign-in.core-test
  (:require
    [cider-ci.server.github-sign-in.core :as core]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))


(def config
  {:client_id "cid" :client_secret "secret"
   :rules [{:organization "Example-Org" :team "CI Admins" :admin true}
           {:organization "example-org"}
           {:organization "other" :team "devs"}]})

(deftest normalize-config-test
  (let [c (core/normalize-config config)]
    (is (= "https://github.com/login/oauth" (:oauth_base_url c)))
    (is (= "https://api.github.com" (:api_endpoint c)))
    (is (= [{:organization "example-org" :team "ci admins" :admin true}
            {:organization "example-org" :team nil :admin false}
            {:organization "other" :team "devs" :admin false}]
           (:rules c))))
  (is (= "http://fake:1/login/oauth"
         (:oauth_base_url (core/normalize-config
                            (assoc config :oauth_base_url "http://fake:1/login/oauth/")))))
  (testing "not configured"
    (is (nil? (core/normalize-config nil)))
    (is (nil? (core/normalize-config (dissoc config :client_secret))))
    (is (nil? (core/normalize-config (assoc config :client_id " "))))
    (is (nil? (core/normalize-config (assoc config :rules []))))
    (is (nil? (core/normalize-config (assoc config :rules [{:team "x"}]))))))

(deftest urls-test
  (let [c (core/normalize-config config)]
    (is (= "https://ci.example.org/sign-in/github/callback"
           (core/callback-url c "https://ci.example.org/")))
    (is (= "http://localhost:7100/sign-in/github/callback"
           (core/callback-url (assoc c :callback_base_url "http://localhost:7100")
                              "https://ci.example.org")))
    (let [url (core/authorize-url c "https://ci.example.org/sign-in/github/callback" "s1")]
      (is (str/starts-with? url "https://github.com/login/oauth/authorize?"))
      (is (str/includes? url "client_id=cid"))
      (is (str/includes? url "scope=read%3Aorg+user%3Aemail"))
      (is (str/includes? url "state=s1"))
      (is (str/includes? url "redirect_uri=https%3A%2F%2Fci.example.org%2Fsign-in%2Fgithub%2Fcallback"))
      (is (not (str/includes? url "secret"))))))

(deftest safe-return-to-test
  (is (= "/projects/x?a=1" (core/safe-return-to "/projects/x?a=1")))
  (doseq [bad [nil "" "https://evil.example" "//evil.example" "/\\evil.example" "evil"]]
    (is (nil? (core/safe-return-to bad)) (pr-str bad))))

(def org-memberships
  [{:state "active" :organization {:login "Example-Org"}}
   {:state "pending" :organization {:login "other"}}])

(def teams
  [{:slug "ci-admins" :name "CI Admins" :organization {:login "example-org"}}
   {:slug "devs" :name "Devs" :organization {:login "other"}}])

(deftest rules-test
  (let [rules (:rules (core/normalize-config config))
        m (core/memberships org-memberships teams)]
    (is (= #{"example-org"} (:organizations m)))
    (testing "the first matching rule wins; team by display name or slug"
      (is (:admin (core/matching-rule rules m)))
      (is (:admin (core/matching-rule
                    (:rules (core/normalize-config
                              (assoc-in config [:rules 0 :team] "CI-ADMINS"))) m))))
    (testing "not in the team: the organization rule"
      (is (= {:organization "example-org" :team nil :admin false}
             (core/matching-rule rules (core/memberships org-memberships (rest teams))))))
    (testing "a team of a pending organization membership does not count"
      (is (nil? (core/matching-rule (drop 2 rules) m))))
    (testing "a team of another organization with the same name does not count"
      (is (nil? (core/matching-rule
                  [{:organization "example-org" :team "devs"}] m))))
    (is (nil? (core/matching-rule rules (core/memberships [] []))))))

(deftest verified-emails-test
  (is (= ["primary@example.org" "second@example.org"]
         (core/verified-emails
           [{:email "second@example.org" :verified true :primary false}
            {:email "unverified@example.org" :verified false :primary false}
            {:email "primary@example.org" :verified true :primary true}
            {:email "Second@example.org" :verified true :primary false}]))))

(deftest next-link-test
  (is (= "https://api.github.com/user/teams?page=2"
         (core/next-link (str "<https://api.github.com/user/teams?page=2>; rel=\"next\", "
                              "<https://api.github.com/user/teams?page=5>; rel=\"last\""))))
  (is (nil? (core/next-link "<https://api.github.com/user/teams?page=1>; rel=\"prev\"")))
  (is (nil? (core/next-link nil))))
