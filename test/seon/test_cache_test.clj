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


(deftest publication-inventory-includes-the-graph-without-widening-selection
  (let [published {"src/seon/example.clj" "source-v1"
                   "src/seon/shared.cljc" "shared-v1"
                   "test/fixtures/program.edn" "edn-v1"
                   "test/seon/example_test.clj" "test-v1"
                   "resources/seon/schemas/example.edn" "schema-v1"
                   "config/default.edn" "config-v1"
                   "deps.edn" "deps-v1"}
        snapshot (assoc published
                        "docs/example.md" "doc-v1"
                        "test/fixtures/input.txt" "text-v1"
                        "test/resources/program.clj.txt" "template-v1"
                        "test/seon/dev/probe.py" "probe-v1"
                        "test/seon/html_views.cjs" "browser-v1")
        expected (cache/publication-inputs "." snapshot)]
    (is (= published expected))
    (is (= {:seon.test.cache/changed [] :seon.test.cache/removed []}
           (cache/changed-inputs published expected)))
    (testing "source/test changes and deletions remain export mismatches"
      (let [changed (cache/publication-inputs
                     "." (-> snapshot
                             (assoc "src/seon/example.clj" "source-v2")
                             (dissoc "test/seon/example_test.clj")))]
        (is (= {:seon.test.cache/changed ["src/seon/example.clj"]
                :seon.test.cache/removed ["test/seon/example_test.clj"]}
               (cache/changed-inputs published changed)))))
    (testing "graph and documentation changes still do not alter widening inputs"
      (is (= (cache/test-input-digest "." snapshot)
             (cache/test-input-digest
              "." (-> snapshot
                      (assoc "src/seon/example.clj" "source-v2"
                             "docs/example.md" "doc-v2")
                      (dissoc "test/seon/example_test.clj"))))))))

(deftest nonindexed-graph-fixtures-invalidate-the-external-input-signature
  (let [fixtures {"test/fixtures/input.txt" "text-v1"
                  "test/resources/program.clj.txt" "template-v1"
                  "test/seon/dev/probe.py" "python-v1"
                  "test/seon/html_views.cjs" "browser-v1"
                  "src/fixtures/input.txt" "source-fixture-v1"}
        inputs (assoc fixtures
                      "src/example.clj" "source-v1"
                      "test/example_test.clj" "test-v1"
                      "docs/example.md" "doc-v1")
        original (cache/test-input-digest "." inputs)]
    (doseq [path (keys fixtures)]
      (testing path
        (is (true? (cache/widening-path? path)))
        (is (not= original
                  (cache/test-input-digest "." (assoc inputs path "changed"))))
        (is (not= original
                  (cache/test-input-digest "." (dissoc inputs path))))
        (is (not (contains? (cache/publication-inputs "." inputs) path)))))
    (testing "ordinary indexed edits and external documentation retain the input signature"
      (is (= original
             (cache/test-input-digest
              "." (assoc inputs
                         "src/example.clj" "source-v2"
                         "test/example_test.clj" "test-v2"
                         "docs/example.md" "doc-v2")))))))
