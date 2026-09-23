(ns seon.test-cache-test
  "The cache shares declared inputs and worker sizing with publication."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.test.cache :as cache]))

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
                  "script/seon/operator.clj"
                  "reference-code/sci"
                  "reference-code/malli/src/malli/core.cljc"]]
      (is (true? (cache/widening-path? path)) path))))

