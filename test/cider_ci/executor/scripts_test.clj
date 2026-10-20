(ns cider-ci.executor.scripts-test
  (:require [clojure.test :refer [deftest is testing]]
            [cider-ci.executor.scripts]))

(def ^:private env-str-map            @#'cider-ci.executor.scripts/env-str-map)
(def ^:private apply-templates        @#'cider-ci.executor.scripts/apply-templates)
(def ^:private template-resource-name @#'cider-ci.executor.scripts/template-resource-name)

(deftest env-str-map-test
  (testing "keyword keys become strings"
    (is (= {"FOO" "bar"} (env-str-map {:FOO "bar"}))))
  (testing "numeric values coerced to strings"
    (is (= {"PORT" "8080"} (env-str-map {"PORT" 8080}))))
  (testing "mixed key types"
    (is (= {"A" "1" "B" "2"} (env-str-map {:A 1 "B" 2})))))

(deftest apply-templates-test
  (testing "resolves direct reference"
    (is (= {"A" "42" "B" "42"}
           (apply-templates {"A" "42" "B" "{{A}}"}))))
  (testing "resolves three-step chain T1->T2->T3"
    (is (= {"T1" "7" "T2" "7" "T3" "7"}
           (apply-templates {"T1" "{{T2}}" "T2" "{{T3}}" "T3" "7"}))))
  (testing "leaves unresolvable reference as literal {{KEY}}"
    (is (= {"A" "{{MISSING}}"} (apply-templates {"A" "{{MISSING}}"}))))
  (testing "no-op when no placeholders"
    (is (= {"A" "x"} (apply-templates {"A" "x"}))))
  (testing "partial resolution when some refs are unresolvable"
    (let [result (apply-templates {"A" "{{B}}" "B" "{{GONE}}"})]
      (is (= "{{GONE}}" (get result "A"))))))

(deftest template-resource-name-test
  (testing "resolves {{KEY}} from string-keyed env map"
    (is (= "resource-8080"
           (template-resource-name {"PORT" "8080"} "resource-{{PORT}}"))))
  (testing "resolves {{KEY}} from keyword-keyed env map"
    (is (= "resource-9000"
           (template-resource-name {:PORT 9000} "resource-{{PORT}}"))))
  (testing "leaves unresolvable key as literal"
    (is (= "resource-{{MISSING}}"
           (template-resource-name {} "resource-{{MISSING}}"))))
  (testing "no-op when no placeholders"
    (is (= "plain-resource"
           (template-resource-name {"X" "y"} "plain-resource"))))
  (testing "multiple placeholders in one name"
    (is (= "host-1.2.3.4-port-8080"
           (template-resource-name {"HOST" "1.2.3.4" "PORT" "8080"}
                                   "host-{{HOST}}-port-{{PORT}}")))))


;;; shebangs ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private run-one! @#'cider-ci.executor.scripts/run-one!)

(defn- run-body [body]
  (let [dir (str (java.nio.file.Files/createTempDirectory "scripts-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        r   (run-one! (str "t-" (System/nanoTime)) "k" {:body body} {} dir)]
    (assoc r :log (slurp (:log-file r)))))

(deftest script-command-test
  (let [f (java.io.File. "/tmp/x.sh")]
    (is (= ["bash" "-c" "exec \"$0\"" "/tmp/x.sh"]
           (cider-ci.executor.scripts/script-command f "#!/usr/bin/env ruby\nputs 1")))
    (is (= ["bash" "/tmp/x.sh"] (cider-ci.executor.scripts/script-command f "echo 1")))
    (is (= ["bash" "/tmp/x.sh"] (cider-ci.executor.scripts/script-command f nil)))))

(deftest shebang-is-honoured-test
  (testing "a non-bash interpreter named in the shebang runs the script (madek api-database-configure is Ruby)"
    (let [r (run-body "#!/usr/bin/env python3\nprint('from python', 1 + 1)\n")]
      (is (= "passed" (:state r)) (:log r))
      (is (re-find #"from python 2" (:log r)))))
  (testing "a bash shebang still runs with bash"
    (let [r (run-body "#!/usr/bin/env bash\nset -euo pipefail\n[[ 1 == 1 ]] && echo bash-ok\n")]
      (is (= "passed" (:state r)) (:log r))
      (is (re-find #"bash-ok" (:log r)))))
  (testing "no shebang: bash as before (bashisms allowed)"
    (let [r (run-body "[[ 1 == 1 ]] && echo plain-ok\n")]
      (is (= "passed" (:state r)) (:log r))
      (is (re-find #"plain-ok" (:log r)))))
  (testing "a failing interpreter script fails the script"
    (let [r (run-body "#!/usr/bin/env python3\nimport sys; sys.exit(3)\n")]
      (is (= "failed" (:state r)))
      (is (= 3 (:exit_status r))))))
