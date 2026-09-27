(ns seon.issue.detect-test
  "The two first-task standards: a public function declares a contract, and a
  test reaches it.

  Both detectors are read-only derivations over program facts, so these
  regressions seed ONE fixture namespace into the canonical population and
  assert what each detector says about exactly those declarations. The
  canonical population carries its own real subjects; asserting on its totals
  would make these tests a census of the tree. Every seed goes through
  `seon.test-support/transacted!`, which stops setup on a refusal: a fixture
  that ignores its own transaction report reads a refusal as behaviour."
  (:require [clojure.string]
            [clojure.test]
            [seon.db]
            [seon.fn]
            [seon.issue.detect]
            [seon.program]
            [seon.test-support]))

(def ^:private source-ns 'seon.detect-fixture)
(def ^:private helper-ns 'seon.detect-fixture-helpers)

(defn- sym-in [namespace-name simple] (symbol (str namespace-name) simple))

(def ^:private complete-fn (sym-in source-ns "complete"))
(def ^:private uncontracted-fn (sym-in source-ns "uncontracted"))
(def ^:private untested-fn (sym-in source-ns "untested"))
(def ^:private helper-fn (sym-in helper-ns "helper"))
(def ^:private constant-var (sym-in source-ns "constant"))

(def ^:private fixture-symbols #{complete-fn uncontracted-fn untested-fn helper-fn constant-var})

(def ^:private source-file "src/seon/detect_fixture.clj")
(def ^:private helper-file "test/seon/detect_fixture_helpers.clj")

(def ^:private fixture-source
  "The fixture namespace's declarations, analyzed as an author's file is: two
  contracted functions, one without a contract, a literal constant (no body:
  no standard names it) and a test reaching two of them."
  (str "(defn complete \"Doc.\" {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
       "(defn uncontracted \"Doc.\" [x] x)\n"
       "(defn untested \"Doc.\" {:malli/schema [:=> [:cat :int] :int]} [x] x)\n"
       "(def constant 7)\n"
       "(clojure.test/deftest covers (clojure.test/is (= 1 (complete (uncontracted 1)))))"))

(defn- declaration
  "The canonical entity `source` declares for `declared`, analyzed through the
  production source path and digested by the writer's digest owner."
  [database declared source file]
  (seon.test-support/program-entity
   database declared source
   (cond-> {:seon.schema.admission/source :core}
     file (assoc :seon.fn/file [:seon.fn.file/relative-path file]))))

(defn- seed!
  "One namespace under `src` with declarations telling the two standards apart
  and the test reaching two of them, plus one under `test` that only the
  unscoped run may name. Every entity comes from its writer: namespaces from
  `seon.program/declaration-row`, declarations analyzed from source (the writer
  refuses a hand-built declaration's digest, 79b6d9abd)."
  [connection]
  (let [projection (seon.db/carried-projection (seon.db/db connection))
        namespace-entity #(seon.program/declaration-row projection {:seon.ns/name %1 :seon.ns/source %2} :all :agent)]
    (seon.test-support/transacted!
     connection
     [(namespace-entity source-ns (pr-str (list 'ns source-ns '(:require [clojure.test]))))
      (namespace-entity helper-ns (pr-str (list 'ns helper-ns)))
      {:seon.fn.file/relative-path source-file
       :seon.fn.file/digest (apply str (repeat 64 "a"))
       :seon.fn.file/relative-root "src"}
      {:seon.fn.file/relative-path helper-file
       :seon.fn.file/digest (apply str (repeat 64 "b"))
       :seon.fn.file/relative-root "test"}])
    (let [database (seon.db/db connection)]
      (seon.test-support/transacted!
       connection
       (conj (mapv #(declaration database [:seon.fn/sym %] fixture-source source-file)
                   [complete-fn uncontracted-fn untested-fn constant-var])
             (declaration database [:seon.test/sym (sym-in source-ns "covers")] fixture-source nil)
             (declaration database [:seon.fn/sym helper-fn] "(defn helper \"Doc.\" [x] x)" helper-file))))))

(defn- declare-more!
  "Write the declarations `extra` source adds to the fixture file, analyzed
  after the file's own forms so every form keeps its own span."
  [connection symbols extra]
  (let [database (seon.db/db connection)
        source (str fixture-source "\n" extra)]
    (seon.test-support/transacted!
     connection
     (mapv #(declaration database [:seon.fn/sym %] source source-file) symbols))))

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
        (clojure.test/is (some? subject) (str untested-fn))
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
      (let [shared (sym-in source-ns "->Shared")
            sibling (sym-in source-ns "map->Shared")
            _ (declare-more! connection [shared sibling] "(defrecord Shared [a])")
            database (seon.db/db connection)
            contract (into #{} (map :seon.fn/sym)
                           (seon.issue.detect/public-without-contract
                            database {:seon.fn.file/relative-root "src"}))]
        (clojure.test/is (not (contains? contract shared)) (str shared))
        (clojure.test/is (not (contains? contract sibling)) (str sibling))
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
      (let [constructor (sym-in source-ns "->Bodiless")
            method (sym-in source-ns "bodiless-method")
            defined (sym-in source-ns "defined-by-defn")
            _ (declare-more! connection [constructor method defined]
                             (str "(deftype Bodiless [a])\n(defprotocol Bodiless-protocol (bodiless-method [x]))\n"
                                  "(defn defined-by-defn \"Doc.\" [x] x)"))
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
                         "and so is the seeded uncontracted defn")))))
