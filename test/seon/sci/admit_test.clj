(ns seon.sci.admit-test
  "Sealed acceptance draft for value admission (N3's one new mechanism).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). The implementation
  lane makes these green by implementing `seon.sci.admit` ONLY —
  schemas and tests are byte-sealed; friction is reported, never
  resolved by weakening.

  The suite builds REAL sci values (vars, fns, records, deftypes) and a
  REAL armed boundary: an `:interrupt-fn` closing over a volatile that
  the test trips, exactly the shape `seon.sci.interrupt` supplies in
  production. Admission is handed that fn, so nothing here depends on
  the timer, on `seon.sci.eval`, or on any namespace N3 has not adopted
  yet.

  Isolation is per trial by construction rather than by fixture:
  admission is pure, opens nothing and writes nothing, so a trial's
  only state is the value it hands in and the interrupt-fn counter it
  reads back. The sci sample values are built once and never mutated —
  including the cyclic ones, whose whole point is that admission must
  not enter them.

  PRECONDITION for this suite to run at all: `org.babashka/sci` must be
  on the default classpath (n3-plan §6.2 moves it out of the `:host`
  alias). Named in the draft report; not edited here."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.print :as print]
            [seon.sci.admit :as admit]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

;;; ---------------------------------------------------------------------------
;;; The armed boundary, as the suite supplies it
;;; ---------------------------------------------------------------------------

(defn- armed
  "One armed boundary: `{:interrupt-fn f :trip! g :calls h}`.
  `interrupt-fn` counts every call and, once tripped, raises sci's own
  uncatchable interrupt — the production shape
  (`src-old/seon/sci/interrupt.clj:74-79`) with the clock replaced by an
  explicit trip so a test never races a timer."
  []
  (let [calls (atom 0)
        tripped (volatile! false)]
    {:interrupt-fn (fn []
                     (swap! calls inc)
                     (when @tripped
                       ((requiring-resolve 'sci.interrupt/interrupt!)
                        "time-limit")))
     :trip! (fn [] (vreset! tripped true))
     :calls (fn [] @calls)}))

(def ^:private caps
  (config/result-caps (config/defaults)))

(def ^:private packaged-projection
  (delay (schema/declaration-projection
          ((requiring-resolve 'seon.schema.edn/packaged-forms)))))

(defn- request
  ([value] (request value (:interrupt-fn (armed))))
  ([value interrupt-fn] (request value interrupt-fn caps))
  ([value interrupt-fn caps]
   {:seon.sci.admit/value value
    :seon.sci.admit/interrupt-fn interrupt-fn
    :seon.sci.admit/caps caps
    :seon.schema/projection @packaged-projection
    ;; production disposition by default: these trials are about the
    ;; codec, and the panic case has its own test
    :seon.config/on-core-error :record
    ;; EVERY DECLARED MEMBER, exactly like production: the record schema
    ;; requires `:seon.eval/host-interop-count`, and a fixture that omits a
    ;; declared input is the defect (§5.1) — invisible for as long as the
    ;; gate armed no contracts.
    :seon.sci.admit/record {:seon.eval/fn-entries 4242
                            :seon.eval/host-interop-count 0
                            :seon.eval/duration-ms 7
                            :seon.eval/allocated-bytes 918273
                            :seon.eval/outcome :ok}}))

(defrecord IdentityOnlyRecord [id payload])

(def identity-only-record-generator
  (gen/fmap (fn [id] (->IdentityOnlyRecord id "generated")) gen/small-integer))

(defn identity-only-record?
  [value]
  (instance? IdentityOnlyRecord value))

(defn identity-only-record-identity
  [value]
  {::identity (:id value)})

(schema/register-core-predicate!
 'seon.sci.admit-test/identity-only-record?
 identity-only-record?)

(defn- projection-with-identity-only-record
  []
  (schema/build-projection
   (assoc
    (schema/snapshot)
    ::identity-only-record
    [:fn
     {:seon.schema/identity-only true
      :seon.schema/identity-projection
      'seon.sci.admit-test/identity-only-record-identity
      :gen/gen 'seon.sci.admit-test/identity-only-record-generator}
     'seon.sci.admit-test/identity-only-record?])))

;; ONE compiled node schema for the whole namespace. It was compiled per
;; generated case, and `schema/current-projection` is nil outside a delta, so
;; every case fell through Malli's default registry to a complete classpath
;; declaration population — 152 resource reads, ~14 ms, thousands of times
;; per run. Resolving the projection once here is the same repair the
;; admission seam itself received
;; (`docs/seon/issues/value-admission-resolves-the-declaration-population-per-node.md`);
;; the schema does not vary by case, so compiling it per case measured
;; nothing but the resource merge.
(def ^:private compiled-node-schema*
  (delay
    (let [registry (:seon.schema.projection/registry
                    (or (schema/current-projection)
                        (schema/declaration-projection)))]
      (m/schema :seon.print/node {:registry registry}))))

(defn- compiled-node-schema
  []
  @compiled-node-schema*)

(deftest one-print-node-owns-semantic-value-and-result-edn
  (binding [*print-length* 0
            *print-level* 0
            *print-meta* true
            *print-readably* false
            *print-dup* true
            *print-namespace-maps* false]
    (let [admitted (admit/admit (request {:alpha [1 2 3]}))
          print-node (:seon.sci.admit/print-node admitted)]
      (is (= print-node
             (edn/read-string (:seon.sci.admit/edn admitted))))
      (is (= (:seon.sci.admit/value admitted)
             (admit/semantic-value print-node)))
      (is (= print-node
             (:seon.sci.admit/print-node
              (admit/admit-value (request {:alpha [1 2 3]}))))))))

(deftest every-identity-only-record-admits-as-identity-at-depth
  (let [payload (apply str (repeat 100000 \x))
        reference (->IdentityOnlyRecord 41 payload)
        projection (projection-with-identity-only-record)
        admitted
        (admit/admit (assoc (request {:outer [{:reference reference}]})
                            :seon.schema/projection projection))
        semantic (:seon.sci.admit/value admitted)
        print-node (:seon.sci.admit/print-node admitted)
        object-nodes
        (filter #(and (map? %)
                      (= ::print/object (::print/face %)))
                (tree-seq coll? seq print-node))]
    (is (= {:outer [{:reference {::identity 41}}]} semantic))
    (is (= 1 (count object-nodes)))
    (is (= {::identity 41}
           (admit/semantic-value (::print/value (first object-nodes)))))
    (is (< (* 10 (count (:seon.sci.admit/edn admitted)))
           (count payload))
        "a satisfying defrecord can never contribute its structural bulk")
    (is (not (str/includes? (:seon.sci.admit/edn admitted)
                            payload)))))

;;; ---------------------------------------------------------------------------
;;; Real sci values — one context, inert values, never mutated
;;; ---------------------------------------------------------------------------

(def ^:private sci-context
  (delay (sci/init {:namespaces {}
                    :classes {:allow :all
                              'java.util.Date java.util.Date}})))

(defn- evaluated
  [source]
  (sci/eval-string* @sci-context source))

(def ^:private escape-kinds
  "One live instance of every kind that can leave a sci evaluation."
  (delay
    {:sci-var (evaluated "(defn f [] 1) #'f")
     :sci-fn (evaluated "(defn f [] 1) f")
     :sci-record (evaluated "(defrecord Foo [a b]) (->Foo 1 {:x [1 2]})")
     :sci-type (evaluated "(defrecord Foo [a b]) Foo")
     :sci-deftype (evaluated "(deftype Bar [a]) (Bar. 1)")
     :namespace (evaluated "(create-ns 'probe.ns)")
     :atom (evaluated "(atom {:a 1})")
     :cyclic-atom (evaluated "(let [a (atom nil)] (reset! a a) a)")
     :cyclic-through-map (evaluated "(let [a (atom nil) m {:self a}] (reset! a m) m)")
     :cyclic-array (evaluated "(let [a (object-array 1)] (aset a 0 a) a)")
     :promise (evaluated "(promise)")
     :delay (evaluated "(delay 1)")
     :finite-lazy-seq (evaluated "(map inc (range 5))")
     :host-object (evaluated "(java.util.Date. 0)")
     :regex (evaluated "#\"ab+\"")
     :array (evaluated "(to-array [1 2 3])")
     :exception (evaluated "(ex-info \"boom\" {:a 1})")
     :sorted-map (evaluated "(sorted-map :b 1 :a 2)")
     :deep-nest (evaluated "(reduce (fn [v _] [v]) :leaf (range 400))")
     :wide (evaluated "(vec (range 500))")
     :long-string (evaluated "(apply str (repeat 4000 \\x))")}))

(defn- generated-value
  "Ordinary data with hostile leaves woven in, to any shape."
  []
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 4)
                  (gen/set inner {:max-elements 4})
                  (gen/map (gen/one-of [gen/keyword gen/string-alphanumeric])
                           inner
                           {:max-elements 4})
                  (gen/fmap seq (gen/vector inner 0 4))]))
   (gen/one-of [gen/small-integer
                gen/boolean
                gen/string-alphanumeric
                gen/keyword
                gen/symbol
                (gen/return nil)
                (gen/elements (vals @escape-kinds))])))

(def ^:private guaranteed-partitions
  [:sci-var :sci-fn :sci-record :sci-type :sci-deftype :namespace
   :atom :promise :delay :finite-lazy-seq :host-object :regex :array
   :exception :sorted-map :deep-nest :wide :long-string])

(declare admitted-node-valid?)

(defn- admitted-value-valid?
  [value]
  (let [{:keys [interrupt-fn]} (armed)
        input (request value interrupt-fn)
        admitted (admit/admit input)
        printed (:seon.sci.admit/edn admitted)]
    (if (:seon.sci.admit/reason admitted)
      ;; A MISSING ADMISSION IS A COMPLETE ANSWER, and its whole contract is
      ;; that it stored nothing: no node, no value, no bytes to read back.
      (and (nil? printed)
           (not (contains? admitted :seon.sci.admit/print-node))
           (not (contains? admitted :seon.sci.admit/value))
           (= (:seon.sci.admit/record input)
              (:seon.sci.admit/record admitted)))
      (admitted-node-valid? admitted input printed))))

(defn- admitted-node-valid?
  [admitted input printed]
  (let [projection (edn/read-string printed)]
    (and
     (m/validate (compiled-node-schema) projection)
     (string? printed)
     (do (edn/read-string printed) true)
     ;; THE ONE BOUND, and the shape is not part of it: a value is stored
     ;; whole. What must hold of every admission is that its emitted bytes
     ;; are within the storage bound and read back as the node it returned.
     (<= (count (.getBytes ^String printed "UTF-8"))
         (long (:seon.config.eval.result/max-bytes caps)))
     (= projection (:seon.sci.admit/print-node admitted))
     (= (:seon.sci.admit/record input)
        (:seon.sci.admit/record admitted))
     (not (contains? admitted :seon.sci.admit/capped?)))))

;;; ---------------------------------------------------------------------------
;;; Totality — the property that makes the codec a codec
;;; ---------------------------------------------------------------------------

(deftest every-value-projects-bounded-and-reads-back
  (let [check
        (tc/quick-check
         50
         (prop/for-all [value (generated-value)]
           ;; Every trial includes every named hostile partition. The
           ;; generated value varies recursively; coverage of pending
           ;; refs, lazy values, SCI objects, and every cap boundary is
           ;; construction, never probability.
           (every? admitted-value-valid?
                   (cons value
                         (map #(get @escape-kinds %)
                              guaranteed-partitions))))
         :seed 202607280801)]
    (test-support/assert-check! check "Admission totality failed.")))

(deftest a-cyclic-value-projects-where-pr-str-dies
  ;; the class, stated once: pr-str of a self-referential structure raises
  ;; StackOverflowError — an ERROR, which `catch Exception` never sees.
  ;; Admission must not detect cycles; it must make them unreachable.
  (doseq [kind [:cyclic-atom :cyclic-through-map :cyclic-array]]
    (let [value (get @escape-kinds kind)]
      (testing (str kind " kills the raw print")
        (when (not= :cyclic-array kind)
          (is (thrown? StackOverflowError (pr-str value)))))
      (testing (str kind " admits cleanly")
        ;; admitted as a MEMBER: a bare host reference at the root stores
        ;; nothing at all, and the question here is whether the CYCLE is
        ;; unreachable — which it is, because the walk never enters a
        ;; reference type wherever it sits.
        (let [admitted (admit/admit (request [value]))]
          (is (string? (:seon.sci.admit/edn admitted)))
          (is (some? (edn/read-string
                      (:seon.sci.admit/edn admitted))))
          (is (m/validate
               (compiled-node-schema)
               (edn/read-string (:seon.sci.admit/edn admitted)))))))))

(deftest inst-projection-keeps-common-and-exotic-inst-values-readable
  (let [date (java.util.Date. 41)
        instant (java.time.Instant/ofEpochMilli 42)
        exotic (reify clojure.core/Inst
                 (inst-ms* [_] 43))]
    (is (= date
           (:seon.sci.admit/value (admit/admit (request date)))))
    (is (= (java.util.Date. 42)
           (:seon.sci.admit/value (admit/admit (request instant)))))
    (is (= (java.util.Date. 43)
           (:seon.sci.admit/value (admit/admit (request exotic)))))))

;;; ---------------------------------------------------------------------------
;;; The armed boundary — realization inside it, nothing after it
;;; ---------------------------------------------------------------------------

(defn- admit-with-deadline
  "Run admission on another thread so a hung realization FAILS the test
  instead of hanging the suite."
  [request]
  (test-support/await-event!
   (future (try (admit/admit request)
                (catch Throwable failure failure)))
   "admission completion"))

(deftest an-infinite-sequence-dies-inside-the-armed-boundary
  ;; The hard case: a NATIVE producer enters no interpreted fn body, so
  ;; only the admission walk's interrupt-fn calls can stop it.
  (let [{:keys [interrupt-fn trip!]} (armed)
        value (evaluated "(iterate inc 0)")
        _ (trip!)
        outcome (admit-with-deadline (request value interrupt-fn))]
    (is (instance? Throwable outcome)
        "the interrupt reaches the caller as a throwable")
    (is (contains? (ex-data outcome) :sci.impl/interrupt)
        "and it is sci's own uncatchable interrupt, not a forgery")))

(deftest a-projection-failure-obeys-the-one-dial
  ;; owner ruling (2026-07-27): a value the total codec cannot project
  ;; is a core degradation, so R41 decides — dev panics, prod degrades
  ;; genuinely hostile to the WALK: a collection whose seq throws, so
  ;; the codec reaches for children and is refused. (A bare Seqable is
  ;; not hostile — the codec never enters it, which is the totality
  ;; working rather than failing.)
  (let [hostile (reify clojure.lang.IPersistentCollection
                  (seq [_] (throw (ex-info "hostile seq" {})))
                  (count [_] 1)
                  (cons [_ _] nil)
                  (empty [_] nil)
                  (equiv [_ _] false))]
    (testing ":record degrades — the marker, and the run continues"
      (let [admitted (admit/admit (request {:hostile hostile}))]
        (is (string? (:seon.sci.admit/edn admitted)))
        (is (str/includes? (:seon.sci.admit/edn admitted)
                           ":seon.print/failed")
            "the member that could not be projected says so where it stood")))
    (testing ":panic throws hard and loud — a hole in OUR codec"
      (let [data (try
                   (admit/admit
                    (assoc (request {:hostile hostile})
                           :seon.config/on-core-error :panic))
                   ::committed
                   (catch Exception failure (ex-data failure)))]
        (is (= (symbol (.getName (class hostile))) (:seon.sci.admit/failed-class data)))
        (is (= 'seon.sci.admit/rethrow-or-degrade! (:seon.error/operation data)))
        (is (identical? hostile (:seon.error/offending data)))))
    (testing "an ordinary opaque value is NOT a failure in either mode"
      (doseq [mode [:record :panic]]
        (let [admitted (admit/admit
                        (assoc (request {:fine (get @escape-kinds :sci-fn)})
                               :seon.config/on-core-error mode))]
          (is (string? (:seon.sci.admit/edn admitted))
              (str mode ": a marker is the codec working, not failing")))))))

(deftest render-admission-can-preserve-a-complete-value-under-the-same-guard
  (let [{:keys [interrupt-fn calls]} (armed)
        value ["complete" [1 2 3]]
        admitted (admit/admit-value
                  (assoc (request value interrupt-fn
                                  (assoc caps :seon.config.eval.result/max-bytes 1))
                         :seon.sci.admit/unbounded? true))]
    (is (= value (:seon.sci.admit/value admitted))
        "an unbounded caller is bounding its own source and keeps the value")
    (is (pos? (calls))
        "complete render admission still consults the armed interrupt")))

(deftest the-storage-bound-stops-an-unbounded-source-while-it-emits
  ;; ASTRA B8: a byte count on a finished string is not an execution bound —
  ;; a lazy sequence can block before its first byte. The count is taken as
  ;; the bytes are emitted, and emission stops at the bound.
  (let [{:keys [interrupt-fn calls]} (armed)
        admitted (admit-with-deadline
                  (request (range) interrupt-fn
                           (assoc caps
                                  :seon.config.eval.result/max-bytes 4096)))]
    (is (= :over-bound (:seon.sci.admit/reason admitted))
        "an infinite source is missing, never a page of itself")
    (is (>= (long (:seon.sci.admit/bytes admitted)) 4096)
        "and the size it reports is the BYTES REACHED, as the schema declares")
    (is (< (long (:seon.sci.admit/bytes admitted)) (* 2 4096))
        "measured at the fragment that crossed the bound, not a running total")
    (is (pos? (calls))
        "the evaluation's own SCI interrupt was consulted at every node")
    (is (not (contains? admitted :seon.sci.admit/edn))
        "NOTHING is stored for a missing value")
    (is (not (contains? admitted :seon.sci.admit/print-node)))
    (is (= (:seon.sci.admit/record (request nil))
           (:seon.sci.admit/record admitted))
        "the diagnostics the caller measured travel through untouched")))

(deftest a-value-under-the-bound-is-stored-whole-however-wide-or-deep
  ;; The display caps are GONE. Width, depth and string length are
  ;; presentation decisions made where AI context is generated; storage
  ;; keeps the value or says it did not.
  (let [wide (vec (range 5000))
        deep (reduce (fn [inner _] {:in inner}) :leaf (range 200))
        text (apply str (repeat 100000 \x))]
    (doseq [[label value] [["wide" wide] ["deep" deep] ["long string" text]]]
      (testing label
        (let [admitted (admit/admit (request value))]
          (is (= value (:seon.sci.admit/value admitted))
              "the admitted value IS the value, with nothing elided")
          (is (= (:seon.sci.admit/print-node admitted)
                 (edn/read-string
                  (:seon.sci.admit/edn admitted)))
              "and the bytes the walk emitted read back as that node"))))))

(defn- node-depth
  "How many nested map nodes one print node carries, counted iteratively.

  The assertion cannot use `=` or the EDN reader on a value this deep: both
  recurse, and a test that stack-overflows while checking would report the
  fix as the defect."
  [node]
  (loop [node node
         depth 0]
    (if-let [entries (:seon.print/entries node)]
      (recur (second (first entries)) (inc depth))
      depth)))

(deftest admission-is-total-in-depth-and-never-throws-a-stack-overflow
  ;; THE CLASS: the recursive walk made the JVM CALL STACK the real depth
  ;; bound, so a value nested ~2000 deep threw `StackOverflowError` out of
  ;; `admit` under the production dial — an Error escaping the one boundary
  ;; law 2.4 requires to answer with a value. The walk is iterative now, so
  ;; depth is bounded by the storage bound and by nothing else.
  (letfn [(nest [n] (reduce (fn [inner _] {:in inner}) :leaf (range n)))]
    (testing "the depth that used to overflow is admitted WHOLE"
      ;; 20,000 is here because the walk being iterative was not enough: the
      ;; `:seon.print/node` CONTRACT was a recursive Malli ref, so on any
      ;; instrumented JVM — every live cluster — validating a node deeper
      ;; than 3,509 threw `StackOverflowError` out of Malli while the walk
      ;; that built it never came close. The gate arms the same contracts a
      ;; cluster arms, so this case now asks the question it claimed to.
      (doseq [depth [2000 5000 20000]]
        (let [admitted (admit/admit (request (nest depth)))]
          (is (contains? admitted :seon.sci.admit/print-node)
              (str "depth " depth " stored nothing"))
          (is (= depth (node-depth (:seon.sci.admit/print-node admitted)))
              "the stored node keeps every level the value had")
          (is (= depth
                 (loop [value (:seon.sci.admit/value admitted)
                        seen 0]
                   (if (map? value) (recur (:in value) (inc seen)) seen)))
              "and the derived semantic value keeps them too"))))
    (testing "a depth past the storage bound is MARKED, never thrown"
      (let [admitted (admit/admit (request (nest 100000)))]
        (is (= :over-bound (:seon.sci.admit/reason admitted)))
        (is (int? (:seon.sci.admit/bytes admitted))
            "an over-bound depth still reports the bytes it reached")))))

(deftest a-host-reference-the-walk-cannot-enter-is-missing-not-described
  ;; A description of a value is not the value. Storing `#object[...]` as
  ;; though it were a result is the project's recurring class: absence
  ;; reading as content.
  (doseq [[label value] [["a channel" (async/chan)]
                         ["an atom" (atom 1)]]]
    (testing label
      (let [admitted (admit/admit (request value))]
        (is (= :unserializable (:seon.sci.admit/reason admitted)))
        (is (not (contains? admitted :seon.sci.admit/bytes))
            "an unserializable value has no measured size to report")
        (is (not (contains? admitted :seon.sci.admit/edn)))))))

(deftest an-absent-storage-bound-refuses-and-names-the-key-it-wanted
  ;; docs/seon/issues/absent-admission-cap-crashes-the-print-walk.md: the cap
  ;; read straight into a `long` gave RT.longCast on nil, a Throwable
  ;; escaping an evaluation boundary where law 2.4 requires a flat value.
  (test-support/with-database
   (fn [connection]
     (let [refusal
           (test-support/agent-value
            (test-support/fork-cluster-ctx connection)
            "(seon.sci.admit/admit {:seon.sci.admit/value [1 2 3], :seon.sci.admit/caps {}, :seon.sci.admit/interrupt-fn (fn []), :seon.config/on-core-error :record})")]
       (is (= :input (:seon.instrument/check refusal)))
       (is (= 'seon.sci.admit/admit (:seon.instrument/fn refusal)))
       (is (str/includes? (pr-str refusal)
                          ":seon.config.eval.result/max-bytes"))
       (is (not (str/includes? (pr-str refusal) "RT.longCast")))))))
