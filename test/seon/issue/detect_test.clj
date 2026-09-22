(ns seon.issue.detect-test
  "The two first-task standards: a public function declares a contract, and a
  test reaches it.

  Both detectors are read-only derivations over program facts, so these
  regressions seed ONE fixture namespace into the canonical population and
  assert what each detector says about exactly those declarations. The
  canonical population carries its own real subjects; asserting on its totals
  would make these tests a census of the tree. Every seed goes through
  `seon.db/transact!` and its report is checked: a fixture that ignores its
  own transaction report reads a refusal as behaviour."
  (:require [clojure.string]
            [clojure.test]
            [seon.db]
            [seon.fn]
            [seon.issue.detect]
            [seon.test-support]))

(def ^:private source-ns 'seon.detect-fixture)
(def ^:private helper-ns 'seon.detect-fixture-helpers)

(defn- sym-in [namespace-name simple] (symbol (str namespace-name) simple))

(def ^:private complete-fn (sym-in source-ns "complete"))
(def ^:private uncontracted-fn (sym-in source-ns "uncontracted"))
(def ^:private untested-fn (sym-in source-ns "untested"))
(def ^:private helper-fn (sym-in helper-ns "helper"))

(def ^:private fixture-symbols #{complete-fn uncontracted-fn untested-fn helper-fn})

(defn- digest
  "A 64-character digest, the width `:seon.fn.file/digest` declares."
  [character]
  (apply str (repeat 64 character)))

(defn- function-row [function-symbol namespace-name file extra]
  (merge {:seon.fn/sym function-symbol
          :seon.fn/ns [:seon.ns/name namespace-name]
          :seon.fn/file [:seon.fn.file/relative-path file]
          :seon.fn/private? false
          :seon.fn/source (str "(defn " (name (symbol function-symbol)) " [x] x)")
          :seon.fn/doc "Seeded by seon.issue.detect-test."
          :seon.schema.admission/source :core}
         extra))

(defn- seed!
  "One namespace under `src` with three declarations telling the two standards
  apart, plus one under `test` that only the unscoped run may name."
  [connection]
  (let [source-file "src/seon/detect_fixture.clj"
        helper-file "test/seon/detect_fixture_helpers.clj"
        report
        (seon.db/transact!
         connection
         [{:seon.ns/name source-ns :seon.schema.admission/source :core}
          {:seon.ns/name helper-ns :seon.schema.admission/source :core}
          {:seon.fn.file/relative-path source-file
           :seon.fn.file/digest (digest "a")
           :seon.fn.file/relative-root "src"}
          {:seon.fn.file/relative-path helper-file
           :seon.fn.file/digest (digest "b")
           :seon.fn.file/relative-root "test"}
          (function-row complete-fn source-ns source-file
                        {:seon.fn/spec "[:=> [:cat :int] :int]"})
          (function-row uncontracted-fn source-ns source-file {})
          (function-row untested-fn source-ns source-file
                        {:seon.fn/spec "[:=> [:cat :int] :int]"})
          (function-row helper-fn helper-ns helper-file {})
          {:seon.test/sym (sym-in 'seon.detect-fixture-test "covers")
           :seon.schema.admission/source :core
           :seon.fn/calls #{complete-fn uncontracted-fn}}])]
    (clojure.test/is (let [observed report] (or (some? (:db-after observed)) (nat-int? (:seon.issue/count observed)) (string? (:seon.issue/id observed)))) (pr-str report))
    report))

(defn- named
  "The fixture symbols one detector call names, as a set."
  [subjects]
  (clojure.test/is (vector? subjects) (pr-str subjects))
  (into #{} (comp (map :seon.fn/sym) (filter fixture-symbols)) subjects))

(clojure.test/deftest the-contract-standard-names-only-the-declaration-without-a-spec
  (seon.test-support/with-database
    (fn [connection]
      (seed! connection)
      (let [database (seon.db/db connection)
            production (named (seon.issue.detect/public-without-contract
                               database {:seon.fn.file/relative-root "src"}))
            unscoped (named (seon.issue.detect/public-without-contract database))]
        (clojure.test/is (= #{uncontracted-fn} production)
                         "the contracted declarations are not subjects, the uncontracted one is")
        (clojure.test/is (= #{uncontracted-fn helper-fn} unscoped)
                         "the unscoped run is the honest over-report, test helpers included")
        (clojure.test/is (not (contains? production helper-fn))
                         "the src scope joins on :seon.fn.file/relative-root, so a test-root declaration is out")))))

(clojure.test/deftest the-reaching-test-standard-names-only-the-declaration-no-test-reaches
  (seon.test-support/with-database
    (fn [connection]
      (seed! connection)
      (let [database (seon.db/db connection)
            production (named (seon.issue.detect/public-without-reaching-test
                               database {:seon.fn.file/relative-root "src"}))
            unscoped (named (seon.issue.detect/public-without-reaching-test database))]
        (clojure.test/is (seq (seon.fn/tests-reaching database complete-fn))
                         "the seeded test reaches the covered declarations through :seon.fn/calls")
        (clojure.test/is (= #{untested-fn} production)
                         "only the declaration no stored edge reaches is a subject")
        (clojure.test/is (= #{untested-fn helper-fn} unscoped))
        (clojure.test/is (not (contains? production helper-fn))
                         "the src scope joins on :seon.fn.file/relative-root, so a test-root declaration is out")))))

(clojure.test/deftest the-reaching-test-standard-reports-its-own-evidence
  ;; P3 of the test-system PRD: a missing call fact is UNKNOWN, never "no test
  ;; needed". The call graph under-reports today, so the generated problem text
  ;; must name the basis the reach was derived at and say so.
  (seon.test-support/with-database
    (fn [connection]
      (seed! connection)
      (let [database (seon.db/db connection)
            subject (first (filter (comp #{untested-fn} :seon.fn/sym)
                                   (seon.issue.detect/public-without-reaching-test
                                    database {:seon.fn.file/relative-root "src"})))
            problem (:seon.issue/problem subject)]
        (clojure.test/is (some? subject) untested-fn)
        (clojure.test/is (= #{source-ns} (:seon.issue/namespaces subject))
                         "the finding cites the namespace responsible for it")
        (clojure.test/is (clojure.string/includes? problem (str (seon.db/basis-t database)))
                         (str "the problem names the basis :t the reach was computed at: " problem))
        (clojure.test/is (clojure.string/includes? problem "seon.fn/tests-reaching")
                         (str "the problem names the derivation: " problem))
        (clojure.test/is (clojure.string/includes? problem "incomplete")
                         (str "the problem says the graph may be incomplete: " problem))
        (clojure.test/is (clojure.string/includes?
                          problem "seon.issue.detect/public-without-reaching-test")
                         (str "the problem names the detector that resolves it: " problem))))))

(clojure.test/deftest the-standards-exclude-a-declaration-that-does-not-own-its-form
  ;; A `defrecord`'s constructors share one `:seon.fn/form-span` in one file,
  ;; and no contract can be written on them individually. The exclusion is that
  ;; stored span, never a name pattern.
  (seon.test-support/with-database
    (fn [connection]
      (seed! connection)
      (let [source-file "src/seon/detect_fixture.clj"
            shared (sym-in source-ns "->Shared")
            sibling (sym-in source-ns "map->Shared")
            report (seon.db/transact!
                    connection
                    [(function-row shared source-ns source-file {:seon.fn/form-span [10 20]})
                     (function-row sibling source-ns source-file {:seon.fn/form-span [10 20]})])
            _ (clojure.test/is (let [observed report] (or (some? (:db-after observed)) (nat-int? (:seon.issue/count observed)) (string? (:seon.issue/id observed)))) (pr-str report))
            database (seon.db/db connection)
            contract (into #{} (map :seon.fn/sym)
                           (seon.issue.detect/public-without-contract
                            database {:seon.fn.file/relative-root "src"}))]
        (clojure.test/is (not (contains? contract shared)) shared)
        (clojure.test/is (not (contains? contract sibling)) sibling)
        (clojure.test/is (contains? contract uncontracted-fn)
                         "a declaration owning its own form is still a subject")))))

(clojure.test/deftest the-contract-standard-excludes-a-var-no-author-gave-a-body
  ;; A `deftype`/`defrecord` positional constructor and a `defprotocol` method
  ;; signature are vars nobody wrote a body for: there is no `defn` to hang
  ;; `:malli/schema` on and nothing for instrumentation to arm, so a missing
  ;; contract is not a defect there. The exclusion reads clj-kondo's own
  ;; `:seon.fn/defined-by` fact the indexer records, never the symbol's shape.
  (seon.test-support/with-database
    (fn [connection]
      (seed! connection)
      (let [source-file "src/seon/detect_fixture.clj"
            constructor (sym-in source-ns "->Bodiless")
            method (sym-in source-ns "bodiless-method")
            defined (sym-in source-ns "defined-by-defn")
            report (seon.db/transact!
                    connection
                    [(function-row constructor source-ns source-file
                                   {:seon.fn/defined-by 'clojure.core/deftype
                                    :seon.fn/form-span [30 40]})
                     (function-row method source-ns source-file
                                   {:seon.fn/defined-by 'clojure.core/defprotocol
                                    :seon.fn/form-span [50 60]})
                     (function-row defined source-ns source-file
                                   {:seon.fn/defined-by 'clojure.core/defn
                                    :seon.fn/form-span [70 80]})])
            _ (clojure.test/is (let [observed report] (or (some? (:db-after observed)) (nat-int? (:seon.issue/count observed)) (string? (:seon.issue/id observed)))) (pr-str report))
            database (seon.db/db connection)
            contract (into #{} (map :seon.fn/sym)
                           (seon.issue.detect/public-without-contract
                            database {:seon.fn.file/relative-root "src"}))]
        (clojure.test/is (not (contains? contract constructor))
                         "a deftype constructor has no body to contract")
        (clojure.test/is (not (contains? contract method))
                         "a defprotocol method signature has no body to contract")
        (clojure.test/is (contains? contract defined)
                         "a defn the author wrote is still a subject")
        (clojure.test/is (contains? contract uncontracted-fn)
                         "and so is a declaration with no defined-by fact")))))
