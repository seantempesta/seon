(ns seon.runner-test
  "bin/test's and bin/prove's plan (test-runner) refuses a selection that cannot select: `bin/test
  --reach` once took --reach for a namespace, ran nothing and passed (a check that could not fail)."
  (:require [clojure.test :refer [deftest is]] [test-runner] [clojure.edn] [clojure.string] [seon.config] [seon.lib] [seon.fx] [seon.edn] [seon.program] [seon.system] [seon.proofs]))

;; docs/source.md#tests-run-by-reach: a run's shards key each green by what it read, so the plan hands them its reads index and each
;; test's static reads (write-reads!), which a shard reads back whole from beside its shard file (shard-reads); a run that runs
;; nothing writes none, and its shards key nothing
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-runs-reads-reach-its-shards-whole
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

;; docs/merging.md#the-test-service: a shard warms the shared root only for a test of its own that starts one: a test that reaches
;; the root's var and the binary, the plan's rule for making the process store the root starts on. Found 2026-11-13: a docs-only
;; land's shard of doc-tests reached the shared root's var, warmed it, and died before its first test, :seon.test-store/no-process-store
;; (the plan made no store: none of them reaches the binary). Estimate: two shard files written and read, ~1M ticks.
(deftest ^{:seon.test/estimate {:ticks 1000000}} a-shard-warms-the-shared-root-only-for-a-test-that-starts-one
  (let [out (str (System/getProperty "java.io.tmpdir") "/seon-warm-" (random-uuid))
        reach {:seon.test/fixture #{'a/store-test} :seon.test/shard-fixture #{'a/doc-test 'a/process-test}}
        warm-of (fn [tests binary]
                  (seon.lib/remove-tree! out) (.mkdirs (java.io.File. ^String out))
                  (#'test-runner/write-shards! out tests (zipmap tests (repeat "d")) "t" {} 1 {} #{} (#'test-runner/warm-by reach binary))
                  (:warm (clojure.edn/read-string (slurp (str out "/shard-0.edn")))))]
    (try
      (is (= #{} (warm-of ['a/doc-test] #{})) "doc-tests that reach the root's var but not the binary: no root warmed, as no store is made")
      (is (= #{:seon.test/shard-fixture} (warm-of ['a/doc-test 'a/process-test] #{'a/process-test})) "a test that starts the root: warmed")
      (is (= #{:seon.test/fixture} (warm-of ['a/store-test] #{})) "the test store is warmed for every test that reaches it")
      (finally (seon.lib/remove-tree! out)))))

;; docs/source.md#tests-run-by-reach: a tree's kept program is keyed by the importer's code, every file seon.source and
;; seon.program require, derived from their ns forms. The program a tree imports is a function of that code, so a fix anywhere
;; in it (2026-11-13: refresh-tx's process dependence, in seon.source) must retire every branch it built. Red before: the key
;; read seon.source and seon.program alone, so a fix in a namespace they require left stale branches in use. Estimate: three
;; ns forms read, two digests: ~100k ticks.
(deftest ^{:seon.test/estimate {:ticks 200000}} a-kept-programs-key-moves-with-any-of-the-importers-code
  (let [files {"src/seon/source.clj" "(ns seon.source (:require [seon.lib :as lib] [clojure.string :as str]))"
               "src/seon/program.clj" "(ns seon.program (:require [seon.lib]))"
               "src/seon/lib.clj" "(ns seon.lib)\n(defn f [] 1)"
               "src/seon/web.clj" "(ns seon.web)"}
        prefix (fn [fs] (#'test-runner/prefix-of #(get fs %)))]
    (is (= ["src/seon/lib.clj" "src/seon/program.clj" "src/seon/source.clj"] (vec (#'test-runner/importer-files #(get files %))))
        "the importer's files: the two and what they require, under src/ (a library is its pin's)")
    (is (not= (prefix files) (prefix (assoc files "src/seon/lib.clj" "(ns seon.lib)\n(defn f [] 2)"))) "a fix in a required namespace moves the key")
    (is (= (prefix files) (prefix (assoc files "src/seon/web.clj" "(ns seon.web)\n(defn g [] 1)"))) "a namespace the importer doesn't reach doesn't")))
