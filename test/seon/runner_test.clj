(ns seon.runner-test
  "bin/test's and bin/prove's plan (test-runner) refuses a selection that cannot select: `bin/test
  --reach` once took --reach for a namespace, ran nothing and passed (a check that could not fail)."
  (:require [clojure.test :refer [deftest is testing]] [test-runner]))

(deftest a-selection-that-cannot-select-is-refused
  (is (re-find #"no flag --reach" (str (#'test-runner/refused-names ["--reach"]))) "a flag it does not know")
  (is (re-find #"no flag --all" (str (#'test-runner/refused-names ["seon.runner-test" "--all"]))) "--all after a name")
  (is (re-find #"no test namespace no.such-test" (str (#'test-runner/refused-names ["no.such-test"]))) "a name that is no test namespace")
  (is (nil? (#'test-runner/refused-names ["seon.runner-test"])) "a test namespace")
  (is (nil? (#'test-runner/refused-names [])) "every test")
  (is (= 1 (#'test-runner/prove! "tests" false "target/tmp/runner-test" ["--reach"])) "the plan's exit status: bin/test ends on TEST FAILED")
  (is (= 1 (#'test-runner/prove! "tests" false "target/tmp/runner-test" ["no.such-test"]))))

(defn- suite
  "Tests and their recorded ms shaped like Seon's suite (notes/test-time-2026-10-23.md: one
  47 s property test, a 19 s namespace of cost proofs, then a long tail of small namespaces)."
  []
  (let [nss (into [['r3.inc-test 1 47291] ['seon.program-cost-test 6 19153] ['seon.merge-test 14 10128]
                   ['seon.source-test 12 8146] ['seon.cost-test 13 6865] ['seon.db-test 7 4788]]
                  (for [i (range 30)] [(symbol (str "seon.small" i "-test")) 4 (+ 100 (* 37 i))]))]
    (into {} (for [[n k total] nss i (range k)] [(symbol (str n) (str "t" i)) (quot total k)]))))

(deftest shards-are-dealt-by-recorded-time-a-namespace-whole
  (let [ms (suite)
        tests (sort-by str (keys ms))
        sh (test-runner/shards tests 4 ms)
        load (map :ms sh)
        ns-of #(symbol (namespace %))]
    (is (= 4 (count sh)))
    (is (= (set tests) (set (mapcat :tests sh))) "every test dealt once")
    (is (= (count tests) (count (mapcat :tests sh))))
    (is (every? #(= 1 (count (filter (fn [s] (some (fn [t] (= % (ns-of t))) (:tests s))) sh))) (map ns-of tests))
        "a namespace's tests in one shard")
    (is (= ['r3.inc-test/t0] (:tests (first (filter #(some #{'r3.inc-test/t0} (:tests %)) sh))))
        "the 47 s test runs alone: every other shard takes the rest")
    (is (= 47291 (apply max load)) "the critical path is the longest test, the least it can be")
    (is (= (reduce + (vals ms)) (reduce + load)) "each shard's :ms is the time dealt to it"))
  (testing "a test with no recorded time counts as the median of those recorded"
    (let [ms {'a-test/x 10 'a-test/y 30 'b-test/x 20}
          [s1 s2] (test-runner/shards '[a-test/x a-test/y b-test/x c-test/new] 2 ms)]
      (is (= [40 40] [(:ms s1) (:ms s2)]) "c-test/new is dealt 20, the median, beside b-test's 20")))
  (is (= [{:tests '[a-test/x] :ms 1}] (test-runner/shards '[a-test/x] 4 {})) "nothing recorded: one ms a test, no empty shard"))

(deftest a-namespaces-recorded-times-are-read-back
  (let [dir (str (System/getProperty "user.dir") "/target/tmp/runner-ms-" (random-uuid))]
    (.mkdirs (java.io.File. (str dir "/ms")))
    (spit (str dir "/ms/a-test.edn") (pr-str {'a-test/x 12 'a-test/y 7}))
    (is (= {'a-test/x 12 'a-test/y 7} (test-runner/durations dir '[a-test/x b-test/z])) "b-test has none recorded")
    (is (= {} (test-runner/durations dir '[c-test/q])))))
