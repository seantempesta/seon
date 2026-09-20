(ns seon.test-cache-test
  "The cache shares declared inputs and worker sizing with publication."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [seon.test.cache :as cache]))

(deftest isolated-worker-count-obeys-the-declared-pool-and-selection-bounds
  (is (= 3 (cache/worker-count 32 nil 0)))
  (is (= 1 (cache/worker-count 32 nil 1)))
  (is (= 1 (cache/worker-count 1 nil 0)))
  (is (= 5 (cache/worker-count 32 "5" 1)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be positive"
                        (cache/worker-count 32 "0" 0))))

(deftest resolved-classpath-preserves-order-and-rebases-only-checkout-roots
  (let [basis {:seon.test/classpath-root "/original"
               :seon.test/classpath-roots ["/cache/classes" "src" "." "/deps/library.jar"]}]
    (is (= (str/join java.io.File/pathSeparator
                    ["/cache/classes" "/worker/src" "/worker/." "/deps/library.jar"])
           (cache/classpath basis "/worker")))))

(deftest a-documentation-edit-never-widens-a-gate
  (testing "Program-graph paths select by reach; they are not widening inputs."
    (is (false? (cache/widening-path? "src/seon/db.clj")))
    (is (false? (cache/widening-path? "test/seon/db_test.clj"))))
  (testing "Paths the gate neither loads nor reads never widen."
    (doseq [path ["docs/prds/steward-platform/plan/README.md"
                  "docs/seon/issues/some-note.md"
                  "AGENTS.md"
                  "tmp/probe/scratch.clj"
                  "logs/current-source-failure.log"
                  ".claude/seon-hook.edn"]]
      (is (false? (cache/widening-path? path)) path)))
  (testing "Declared inputs outside the program graph widen."
    (doseq [path ["deps.edn" "bin/test" "bin/test-fast"
                  "config/default.edn"
                  "resources/seon/schemas/seon.db.edn"
                  "script/seon/fresh_operator.clj"
                  "reference-code/sci"
                  "reference-code/malli/src/malli/core.cljc"]]
      (is (true? (cache/widening-path? path)) path))))
