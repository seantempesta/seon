(ns seon.runner-test
  "bin/test's and bin/prove's plan (test-runner) refuses a selection that cannot select: `bin/test
  --reach` once took --reach for a namespace, ran nothing and passed (a check that could not fail)."
  (:require [clojure.test :refer [deftest is]] [test-runner] [seon.lib]))

(deftest a-selection-that-cannot-select-is-refused
  (is (re-find #"no flag --reach" (str (#'test-runner/refused-names ["--reach"]))) "a flag it does not know")
  (is (re-find #"no flag --all" (str (#'test-runner/refused-names ["seon.runner-test" "--all"]))) "--all after a name")
  (is (re-find #"no test namespace no.such-test" (str (#'test-runner/refused-names ["no.such-test"]))) "a name that is no test namespace")
  (is (nil? (#'test-runner/refused-names ["seon.runner-test"])) "a test namespace")
  (is (nil? (#'test-runner/refused-names ["seon.runner-test/a-selection-that-cannot-select-is-refused"])) "NS/TEST: one deftest")
  (is (re-find #"no test namespace no.such-test/x" (str (#'test-runner/refused-names ["no.such-test/x"]))) "NS/TEST of no namespace")
  (is (nil? (#'test-runner/refused-names [])) "every test")
  (is (= 1 (#'test-runner/prove! "tests" false "target/tmp/runner-test" ["--reach"])) "the plan's exit status: bin/test ends on TEST FAILED")
  (is (= 1 (#'test-runner/prove! "tests" false "target/tmp/runner-test" ["no.such-test"]))))

;; docs/source.md#tests-run-by-reach: a run's shards key each green by what it read, so the plan hands them its reads index and each
;; test's static reads (write-reads!), which a shard reads back whole from beside its shard file (shard-reads); a run that runs
;; nothing writes none, and its shards key nothing
(deftest a-runs-reads-reach-its-shards-whole
  (let [out (str (System/getProperty "java.io.tmpdir") "/seon-reads-" (random-uuid))
        rd {'a/f "d1" [:config :k] "v" [:tree] "t0" :seon.proofs/covers #{:var :config}}
        plan {:rd (delay rd) :static-reads {'a/t #{[:config :k] [:tree]} 'a/u #{[:tree]}}}]
    (.mkdirs (java.io.File. ^String out))
    (try
      (#'test-runner/write-reads! out plan ['a/t])
      (let [{got :rd static :static} (#'test-runner/shard-reads (str out "/shard-0.edn"))]
        (is (= rd got) "the reads index, whole")
        (is (= {'a/t #{[:config :k] [:tree]}} static) "the static reads of the tests this run runs, and no other's"))
      (seon.lib/remove-tree! out) (.mkdirs (java.io.File. ^String out))
      (#'test-runner/write-reads! out plan [])
      (is (nil? (#'test-runner/shard-reads (str out "/shard-0.edn"))) "a run of nothing writes none")
      (finally (seon.lib/remove-tree! out)))))
