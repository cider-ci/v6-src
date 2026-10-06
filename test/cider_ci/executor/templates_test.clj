(ns cider-ci.executor.templates-test
  (:require [clojure.test :refer [deftest is testing]]
            [cider-ci.executor.templates :as templates])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest render-string-test
  (testing "legacy-style {{VAR}} and {{ VAR }} with env values, recursively"
    (is (= "x: 42\n" (templates/render-string "x: {{X}}\n" {"X" "42"})))
    (is (= "port 3241" (templates/render-string "port {{ PORT }}" {"PORT" "3241"})))
    (is (= "42" (templates/render-string "{{X}}" {"X" "{{X2}}" "X2" "42"}))))
  (testing "unknown variables render as empty string (Selmer default, as legacy)"
    (is (= "seed= host=" (templates/render-string "seed={{ reverse_proxy_ip_hash_seed }} host={{inventory_hostname}}" {}))))
  (testing "no HTML escaping of values"
    (is (= "a=<b>&\"c\"" (templates/render-string "a={{V}}" {"V" "<b>&\"c\""})))))

(deftest render!-test
  (let [dir (.toFile (Files/createTempDirectory "cider-ci-templates-test" (into-array FileAttribute [])))]
    (spit (java.io.File. dir "t1.tpl") "x: {{X}} p: {{PORT}}\n")
    (testing "renders src into dest (parent dirs created) with env + ports"
      (templates/render! dir {:t1 {:src "t1.tpl" :dest "out/t1.yml"}} {:X "42" "PORT" 6001})
      (is (= "x: 42 p: 6001\n" (slurp (java.io.File. dir "out/t1.yml")))))
    (testing "missing source throws"
      (is (thrown? clojure.lang.ExceptionInfo
                   (templates/render! dir {:t2 {:src "nope.tpl" :dest "x"}} {}))))
    (testing "nil / empty templates are fine"
      (templates/render! dir nil {}) (templates/render! dir {} {}))))
