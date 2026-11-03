(ns cider-ci.utils.identicon-test
  (:require
    [cider-ci.utils.identicon :as identicon]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))


(deftest md5-hex-test
  (is (= "d41d8cd98f00b204e9800998ecf8427e" (identicon/md5-hex "")))
  (is (= "4c4a9ec6f13f639660e148b432094db5" (identicon/md5-hex "octo@example.org")))
  (is (identicon/valid-hash? (identicon/md5-hex "x")))
  (is (not (identicon/valid-hash? "ABC"))))

(deftest pattern-test
  (testing "even nibbles are foreground, odd ones background, mirrored"
    (let [p (identicon/pattern (apply str (repeat 32 "0")))]
      (is (every? true? (flatten p))))
    (let [p (identicon/pattern (apply str (repeat 32 "f")))]
      (is (every? false? (flatten p))))
    ;; chars 0-4 middle column, 5-9 columns 1 and 3, 10-14 columns 0 and 4
    (let [p (identicon/pattern (str "02468" "13579" "02468" (apply str (repeat 17 "0"))))]
      (is (= [[true false true false true]
              [true false true false true]
              [true false true false true]
              [true false true false true]
              [true false true false true]]
             p))))
  (testing "mirrored for any hash"
    (doseq [hash (map identicon/md5-hex ["a" "b" "c" "d" "e"])
            row (identicon/pattern hash)]
      (is (= row (vec (reverse row)))))))

(deftest colour-test
  (testing "hue from the last 7 chars, saturation 0.7, lightness 0.5"
    (is (= "#d92626" (identicon/foreground-colour (apply str (repeat 32 "0")))))
    (is (= "#26d9d9" (identicon/foreground-colour (str (apply str (repeat 25 "0")) "7ffffff"))))))

(deftest svg-test
  (let [hash (identicon/md5-hex "octo@example.org")
        svg  (identicon/svg hash)]
    (is (str/starts-with? svg "<svg xmlns=\"http://www.w3.org/2000/svg\""))
    (is (str/includes? svg "fill=\"#f0f0f0\""))
    (is (= (count (filter true? (flatten (identicon/pattern hash))))
           (count (re-seq #"<rect x=" svg))))
    (is (= svg (identicon/svg hash)) "deterministic")
    (is (= svg (identicon/svg "octo@example.org")) "a non-hash input is hashed")
    (is (not (str/includes? svg ",")) "locale independent numbers")))
