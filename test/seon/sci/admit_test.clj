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
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [sci.core :as sci]
            [seon.sci.admit :as admit]
            [seon.schema])
  (:import [java.util.concurrent TimeUnit]))

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
  {:seon.config.eval.result/max-depth 6
   :seon.config.eval.result/max-collection 8
   :seon.config.eval.result/max-string 32
   :seon.config.eval.result/max-nodes 256})

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

;;; ---------------------------------------------------------------------------
;;; Independent measurement of a projection — never the production walker
;;; ---------------------------------------------------------------------------

(defn- measure
  "Depth, node count, widest collection, longest string, and whether the
  projection still holds anything that must never survive admission."
  [value]
  (let [nodes (atom 0)
        widest (atom 0)
        longest (atom 0)
        forbidden (atom #{})
        deepest (atom 0)]
    (letfn [(walk [value depth]
              (swap! nodes inc)
              (swap! deepest max depth)
              (cond
                (instance? clojure.lang.IDeref value)
                (swap! forbidden conj :reference)

                (some-> value class .isArray)
                (swap! forbidden conj :array)

                (instance? clojure.lang.LazySeq value)
                (swap! forbidden conj :lazy)

                (map? value)
                (do (swap! widest max (count value))
                    (doseq [[k v] value] (walk k (inc depth)) (walk v (inc depth))))

                (coll? value)
                (do (swap! widest max (count value))
                    (doseq [child value] (walk child (inc depth))))

                (string? value)
                (swap! longest max (count value))

                (or (nil? value) (boolean? value) (number? value)
                    (keyword? value) (symbol? value) (char? value)
                    (inst? value) (uuid? value))
                nil

                :else
                (swap! forbidden conj (class value))))]
      (walk value 0))
    {:nodes @nodes :depth @deepest :widest @widest
     :longest @longest :forbidden @forbidden}))

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

;;; ---------------------------------------------------------------------------
;;; Totality — the property that makes the codec a codec
;;; ---------------------------------------------------------------------------

(deftest every-value-projects-bounded-and-reads-back
  (let [check
        (tc/quick-check
         50
         (prop/for-all [value (generated-value)]
           (let [{:keys [interrupt-fn]} (armed)
                 admitted (admit/admit (request value interrupt-fn))
                 projection (:seon.sci.admit/value admitted)
                 printed (:seon.cluster.eval/result-edn admitted)
                 shape (measure projection)]
             (and
              ;; it read back as EDN — the whole point of result-edn
              (string? printed)
              (do (edn/read-string printed) true)
              ;; nothing that could hang, cycle, or die later survived
              (empty? (:forbidden shape))
              ;; every cap is a boundary
              (<= (:depth shape)
                  (:seon.config.eval.result/max-depth caps))
              (<= (:nodes shape)
                  (:seon.config.eval.result/max-nodes caps))
              (<= (:widest shape)
                  (:seon.config.eval.result/max-collection caps))
              (<= (:longest shape)
                  (:seon.config.eval.result/max-string caps))
              ;; the diagnostics rode through untouched
              (= (:seon.sci.admit/record (request value interrupt-fn))
                 (:seon.sci.admit/record admitted))
              (boolean? (:seon.sci.admit/capped? admitted)))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "admission totality failed: " (pr-str check)))))

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
          (is (empty? (:forbidden
                       (measure (:seon.sci.admit/value admitted))))))))))

(deftest a-pending-reference-is-never-forced
  ;; forcing a pending promise parks the compute thread past the time
  ;; limit, and no interrupt can take that back
  (let [admitted (admit/admit (request (get @escape-kinds :promise)))]
    (is (some? (edn/read-string (:seon.cluster.eval/result-edn admitted))))
    (is (empty? (:forbidden (measure (:seon.sci.admit/value admitted)))))))

;;; ---------------------------------------------------------------------------
;;; The armed boundary — realization inside it, nothing after it
;;; ---------------------------------------------------------------------------

(defn- admit-with-deadline
  "Run admission on another thread so a hung realization FAILS the test
  instead of hanging the suite."
  [request]
  (let [task (future (try (admit/admit request)
                          (catch Throwable failure failure)))
        outcome (deref task 5000 ::hung)]
    (when (= ::hung outcome)
      (future-cancel task))
    outcome))

(deftest an-infinite-sequence-dies-inside-the-armed-boundary
  (doseq [[label source]
          [;; the hard case: a NATIVE producer, entering no interpreted
           ;; fn body, so only the realizer's own interrupt-fn calls can
           ;; stop it (probe: 200k elements, zero interrupt-fn calls)
           [:native-producer "(iterate inc 0)"]
           [:interpreted-producer "(map (fn [x] (inc x)) (range))"]
           [:nested-in-data "{:k (iterate inc 0)}"]]]
    (testing label
      (let [{:keys [interrupt-fn trip!]} (armed)
            value (evaluated source)
            _ (trip!)
            outcome (admit-with-deadline (request value interrupt-fn))]
        (is (not= ::hung outcome)
            "an infinite realization must die at the limit, not hang")
        (is (instance? Throwable outcome)
            "the interrupt reaches the caller as a throwable")
        (is (contains? (ex-data outcome) :sci.impl/interrupt)
            "and it is sci's own uncatchable interrupt, not a forgery")))))

(deftest nothing-lazy-survives-so-the-interrupt-fn-cannot-fire-later
  ;; "the interrupt-fn is never called after disarm" has exactly one
  ;; honest meaning: no unrealized tail left the boundary. Disarm is a
  ;; timer cancellation the test cannot observe — a surviving lazy seq
  ;; IS observable, so that is what is asserted.
  (let [{:keys [interrupt-fn calls]} (armed)
        value (evaluated "{:a (map inc (range 20)) :b [(map dec (range 5))]}")
        admitted (admit/admit (request value interrupt-fn))
        during (calls)
        projection (:seon.sci.admit/value admitted)]
    (is (pos? during)
        "the walk participates in the armed boundary at every node")
    (measure projection)
    (pr-str projection)
    (is (= during (calls))
        "walking and printing the projection afterwards fires nothing —
         the value is fully realized and holds no lazy tail")))

;;; ---------------------------------------------------------------------------
;;; Caps are boundaries, and the caller supplies them
;;; ---------------------------------------------------------------------------

(deftest caps-are-boundaries-not-suggestions
  (let [tight {:seon.config.eval.result/max-depth 3
               :seon.config.eval.result/max-collection 4
               :seon.config.eval.result/max-string 8
               :seon.config.eval.result/max-nodes 32}
        admit-tight (fn [value]
                      (admit/admit
                       (request value (:interrupt-fn (armed)) tight)))]
    (testing "at the cap, nothing is elided"
      (let [admitted (admit-tight {:a [1 2 3 4]})]
        (is (false? (:seon.sci.admit/capped? admitted)))
        (is (= {:a [1 2 3 4]} (:seon.sci.admit/value admitted)))))
    (testing "one over each cap elides, and says so"
      (doseq [[label value]
              [[:collection {:a [1 2 3 4 5]}]
               [:depth {:a {:b {:c {:d :too-deep}}}}]
               [:string {:a "123456789"}]
               [:nodes (vec (repeat 40 :x))]]]
        (let [admitted (admit-tight value)
              shape (measure (:seon.sci.admit/value admitted))]
          (is (true? (:seon.sci.admit/capped? admitted))
              (str label " must report itself capped"))
          (is (<= (:depth shape) 3) (str label " depth"))
          (is (<= (:widest shape) 4) (str label " width"))
          (is (<= (:longest shape) 8) (str label " string"))
          (is (<= (:nodes shape) 32) (str label " nodes")))))
    (testing "an uncapped value is returned exactly, not merely equivalently"
      (let [admitted (admit-tight {:kept [:a "bc" 1]})]
        (is (= {:kept [:a "bc" 1]} (:seon.sci.admit/value admitted)))
        (is (= {:kept [:a "bc" 1]}
               (edn/read-string
                (:seon.cluster.eval/result-edn admitted))))))))

;;; ---------------------------------------------------------------------------
;;; The quarry defect, as a standing regression
;;; ---------------------------------------------------------------------------

(deftest the-diagnostics-ride-through-untouched
  ;; driver.clj:160-173 dropped fn-entries and allocated-bytes on the
  ;; floor; they are diagnostics, never limits, and admission carries them
  (let [record {:seon.eval/fn-entries 271000000
                :seon.eval/duration-ms 500
                :seon.eval/allocated-bytes -1
                :seon.eval/outcome :time}
        admitted (admit/admit
                  (assoc (request {:any :value}) :seon.sci.admit/record record))]
    (is (= record (:seon.sci.admit/record admitted)))))

(deftest a-value-that-cannot-be-projected-becomes-a-marker
  ;; totality is not "every value we thought of": a hostile object that
  ;; throws when touched must still leave a printable receipt behind
  (let [hostile (reify clojure.lang.Seqable
                  (seq [_] (throw (ex-info "hostile seq" {}))))
        admitted (admit/admit (request {:hostile hostile}))]
    (is (string? (:seon.cluster.eval/result-edn admitted)))
    (is (some? (edn/read-string (:seon.cluster.eval/result-edn admitted))))
    (is (empty? (:forbidden (measure (:seon.sci.admit/value admitted)))))))

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

(deftest sci-types-keep-the-names-sci-gives-them
  ;; grounded in sci's own vocabulary: -get-type reports user.Foo /
  ;; user.Bar, so a receipt says what the agent defined, not
  ;; sci.impl.records.SciRecord
  (let [record-admitted (admit/admit (request (get @escape-kinds :sci-record)))
        deftype-admitted (admit/admit (request (get @escape-kinds :sci-deftype)))
        var-admitted (admit/admit (request (get @escape-kinds :sci-var)))]
    (testing "a record keeps its fields AND its name"
      (let [printed (:seon.cluster.eval/result-edn record-admitted)]
        (is (re-find #"user\.Foo" printed))
        (is (re-find #":a" printed))))
    (testing "an opaque sci type is named, not merely classed"
      (is (re-find #"user\.Bar"
                   (:seon.cluster.eval/result-edn deftype-admitted))))
    (testing "a var prints as the var it is"
      (is (re-find #"f" (:seon.cluster.eval/result-edn var-admitted))))))
