(ns cider-ci.utils.accepted-gpg-keys-test
  (:require
    [cider-ci.utils.accepted-gpg-keys :as agk]
    [clojure.test :refer [deftest is testing]]))


(def fixture-key (slurp "spec/fixtures/gpg/test_public_key.asc"))
(def fixture-fingerprint "247047366DDD1587BF63A6043F8629952915F2B7")
(def fixture-subkey-fingerprint "CE23B55AE633FFEBF9FF999D4C83049B7EBF2BA9")


(deftest parse-keys-test
  (testing "a key yields its primary fingerprint, all fingerprints and user ids"
    (let [[entry :as entries] (agk/parse-keys [fixture-key])]
      (is (= 1 (count entries)))
      (is (= fixture-fingerprint (:fingerprint entry)))
      (is (= [fixture-fingerprint fixture-subkey-fingerprint] (:fingerprints entry)))
      (is (= "3F8629952915F2B7" (:key_id entry)))
      (is (= ["Test User <test@cider-ci.example>"] (:user_ids entry)))))
  (testing "garbage becomes an error entry without fingerprints"
    (let [[entry] (agk/parse-keys ["not a key"])]
      (is (:error entry))
      (is (nil? (:fingerprints entry))))))

(deftest normalize-entries-test
  (is (= [{:fingerprints ["AB" "CD"] :fingerprint "AB" :user_ids ["x"]}
          {:fingerprints [] :error "e"}]
         (agk/normalize-entries [{"fingerprint" "ab" "fingerprints" ["ab" "cd"] "user_ids" ["x"] "junk" 1}
                                 {:error "e"}
                                 "ignored"]))))

(deftest signature-accepted-test
  (let [entries (agk/normalize-entries (agk/parse-keys [fixture-key]))]
    (testing "no keys accept everything"
      (is (agk/accept-all? []))
      (is (agk/signature-accepted? [] nil))
      (is (agk/signature-accepted? [] "ANY")))
    (testing "with keys the verified fingerprint must be a primary or subkey fingerprint"
      (is (agk/signature-accepted? entries fixture-fingerprint))
      (is (agk/signature-accepted? entries (clojure.string/lower-case fixture-subkey-fingerprint)))
      (is (not (agk/signature-accepted? entries nil)))
      (is (not (agk/signature-accepted? entries "0000000000000000000000000000000000000000"))))
    (testing "an error entry matches nothing"
      (is (not (agk/signature-accepted? [{:error "x" :fingerprints []}] fixture-fingerprint))))))

(deftest assert-signed-test
  (let [unsigned "tree 4b825dc642cb6eb9a060e54bf8d69288fbee4904\nauthor A <a@example.org> 1 +0000\ncommitter A <a@example.org> 1 +0000\n\nmsg\n"]
    (testing "no keys: anything passes"
      (is (nil? (agk/assert-signed! [] unsigned "abc"))))
    (testing "with keys an unsigned commit is rejected"
      (let [e (try (agk/assert-signed! [fixture-key] unsigned "abc") nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (agk/rejected? e))
        (is (re-find #"unsigned" (ex-message e)))))))
