(ns cider-ci.utils.gpg-key-info-test
  (:require
    [cider-ci.utils.gpg-key-info :as info]
    [clojure.test :refer [deftest is testing]]))


(def fixture-key (slurp "spec/fixtures/gpg/test_public_key.asc"))

(deftest details-test
  (testing "primary key, user ids and subkeys of the fixture key"
    (let [[k :as ks] (info/details fixture-key)]
      (is (= 1 (count ks)))
      (is (= "247047366DDD1587BF63A6043F8629952915F2B7" (:fingerprint k)))
      (is (= "3F8629952915F2B7" (:key_id k)))
      (is (= "EdDSA" (:algorithm k)))
      (is (= 256 (:bits k)))
      (is (= ["Test User <test@cider-ci.example>"] (:user_ids k)))
      (is (= "2026-05-20T10:45:54Z" (:created_at k)))
      (is (nil? (:expires_at k)) "the fixture key never expires")
      (is (false? (:expired k)))
      (is (false? (:revoked k)))
      (is (= "signing" (:usage k)))
      (let [[sk] (:subkeys k)]
        (is (= "CE23B55AE633FFEBF9FF999D4C83049B7EBF2BA9" (:fingerprint sk)))
        (is (= "encryption" (:usage sk)))
        (is (= "ECDH" (:algorithm sk))))))
  (testing "garbage yields nil"
    (is (nil? (info/details "not a key")))
    (is (nil? (info/details "")))))
