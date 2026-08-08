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
  (:require [clojure.edn :as edn]
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

(defn- request
  ([value] (request value (:interrupt-fn (armed))))
  ([value interrupt-fn] (request value interrupt-fn caps))
  ([value interrupt-fn caps]
   {:seon.sci.admit/value value
    :seon.sci.admit/interrupt-fn interrupt-fn
    :seon.sci.admit/caps caps
    ;; production disposition by default: these trials are about the
    ;; codec, and the panic case has its own test
    :seon.config/on-core-error :record
    :seon.sci.admit/record {:seon.eval/fn-entries 4242
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

;;; ---------------------------------------------------------------------------
;;; Independent measurement of a projection — never the production walker
;;; ---------------------------------------------------------------------------

(defn- measure
  "Semantic depth, node count, width, and longest admitted string."
  [root]
  (let [nodes (atom 0)
        widest (atom 0)
        longest (atom 0)
        deepest (atom 0)]
    (letfn [(walk [node depth]
              (swap! nodes inc)
              (swap! deepest max depth)
              (case (::print/face node)
                (::print/vector ::print/list ::print/set)
                (let [items (::print/items node)]
                  (swap! widest max (count items))
                  (doseq [child items] (walk child (inc depth))))

                (::print/map ::print/record)
                (let [entries (::print/entries node)]
                  (swap! widest max (count entries))
                  (doseq [entry entries
                          :when (vector? entry)
                          child entry]
                    (walk child (inc depth))))

                ::print/throwable
                (walk (::print/value node) (inc depth))

                (::print/string ::print/truncated-string)
                (swap! longest max (count (::print/value node)))

                nil))]
      (walk root 0))
    {:nodes @nodes :depth @deepest :widest @widest
     :longest @longest}))

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
             (edn/read-string (:seon.cluster.eval/result-edn admitted))))
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
        (with-redefs [schema/current-projection (constantly projection)]
          (admit/admit (request {:outer [{:reference reference}]})))
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
    (is (< (* 10 (count (:seon.cluster.eval/result-edn admitted)))
           (count payload))
        "a satisfying defrecord can never contribute its structural bulk")
    (is (not (str/includes? (:seon.cluster.eval/result-edn admitted)
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

(defn- admitted-value-valid?
  [value]
  (let [{:keys [interrupt-fn]} (armed)
        input (request value interrupt-fn)
        admitted (admit/admit input)
        printed (:seon.cluster.eval/result-edn admitted)
        projection (edn/read-string printed)
        shape (measure projection)]
    (and
     (m/validate (compiled-node-schema) projection)
     (string? printed)
     (do (edn/read-string printed) true)
     (<= (:depth shape)
         (:seon.config.eval.result/max-depth caps))
     (<= (:nodes shape)
         (:seon.config.eval.result/max-nodes caps))
     (<= (:widest shape)
         (:seon.config.eval.result/max-collection caps))
     (<= (:longest shape)
         (:seon.config.eval.result/max-string caps))
     (= (:seon.sci.admit/record input)
        (:seon.sci.admit/record admitted))
     (boolean? (:seon.sci.admit/capped? admitted)))))

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
        (let [admitted (admit/admit (request value))]
          (is (string? (:seon.cluster.eval/result-edn admitted)))
          (is (some? (edn/read-string
                      (:seon.cluster.eval/result-edn admitted))))
          (is (m/validate
               (compiled-node-schema)
               (edn/read-string (:seon.cluster.eval/result-edn admitted)))))))))

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
        (is (string? (:seon.cluster.eval/result-edn admitted)))
        (is (true? (:seon.sci.admit/capped? admitted)))))
    (testing ":panic throws hard and loud — a hole in OUR codec"
      (let [data (try
                   (admit/admit
                    (assoc (request {:hostile hostile})
                           :seon.config/on-core-error :panic))
                   ::committed
                   (catch Exception failure (ex-data failure)))]
        (is (= :seon.sci.admit/projection-failed (:seon.error/kind data))
            "and it names itself as a projection failure, not as an
             agent mistake")))
    (testing "an ordinary opaque value is NOT a failure in either mode"
      (doseq [mode [:record :panic]]
        (let [admitted (admit/admit
                        (assoc (request {:fine (get @escape-kinds :sci-fn)})
                               :seon.config/on-core-error mode))]
          (is (string? (:seon.cluster.eval/result-edn admitted))
              (str mode ": a marker is the codec working, not failing")))))))
