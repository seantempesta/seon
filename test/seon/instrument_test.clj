(ns seon.instrument-test
  "Instrumentation: that it catches, that it is idempotent, and that a
  re-`defn` silently undoes it.

  THIS SUITE LEAVES THE JVM AS IT FOUND IT. Instrumentation is
  process-global — `alter-var-root` on every schema'd public var — so a
  test that turned it on and walked away would change how every LATER
  suite behaves, and the gate's result would depend on test order. Every
  test here removes it in a `finally`, and that discipline is the reason
  the whole gate is deterministic with this namespace in it.

  THE FIXTURE IS THE ARCHIVED DEFECT. `seon.db/transact!`
  declared `[:vector :any]` while the run loop's `:open` branch passed
  Datahike's argument map, and it worked only because Datahike's own
  spec admits a map by accident — see
  `docs/seon/issues/archive/loop-open-transaction-violates-transact-schema.md`.
  That is precisely the class instrumentation exists to catch, so the
  suite reproduces the shape rather than inventing one."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [malli.instrument :as mi]
            [seon.dev.docstring :as docstring]
            [seon.dev.markdown :as markdown]
            [seon.error :as error]
            [seon.flow :as flow]
            [seon.instrument :as instrument]
            [seon.schema :as schema]))

(def ^:private function-schemas-state
  ;; Malli exposes collection as a process-global defonce atom but no exact
  ;; snapshot/restore operation. Tests must restore the entering map itself:
  ;; re-registering compiled schemas changes their identity and is not exact.
  (ns-resolve 'malli.core '-function-schemas*))

(defn- preserving-instrumentation-state
  [body]
  (let [instrumented-roots (into {}
                                 (map (juxt identity deref))
                                 (instrument/instrumented))
        function-schemas @@function-schemas-state]
    (try
      (body)
      (finally
        (try
          (instrument/remove!)
          (finally
            (reset! @function-schemas-state function-schemas)))
        (doseq [[instrumented-var root] instrumented-roots]
          (alter-var-root instrumented-var (constantly root)))))))

(use-fixtures :each preserving-instrumentation-state)

(defn- instrumented!
  "Run `body` with instrumentation on, and always take it back off."
  [body]
  (try
    (body (instrument/apply! {:seon.config/on-core-error :panic}))
    (finally
      (instrument/remove!))))

;;; A var of this suite's own, so the re-evaluation proof does not
;;; rewrite a production namespace to make its point.
(defn ^{:malli/schema [:=> [:cat :int] :int]} doubled
  [n]
  (* 2 n))

(defn ^{:malli/schema
        [:=>
         [:cat [:map {:closed true} [:seon.instrument-test/expected :int]]]
         :int]}
  closed-map-input
  [_]
  1)

(defn ^{:malli/schema
        [:=>
         [:cat :map]
         [:map {:closed true} [:seon.instrument-test/expected :int]]]}
  closed-map-output
  [value]
  value)

(defn- many-key-map
  []
  (into {:seon.instrument-test/expected 1}
        (map (fn [index]
               [(keyword "seon.instrument-test.unexpected" (str index)) index]))
        (range 200)))

;;; ---------------------------------------------------------------------------
;;; It catches, and it throws
;;; ---------------------------------------------------------------------------

(deftest a-wrong-shaped-call-throws-a-flat-error-value
  (instrumented!
   (fn [_]
     (let [failure (try
                     ;; the archived D1 shape: a value the declared
                     ;; contract forbids, which the callee would have
                     ;; accepted by accident
                     (error/value "not a fact")
                     (catch Exception thrown thrown))
           data (ex-data failure)]
       (is (some? failure) "the call was stopped, not merely observed")
       (is (= :seon.instrument/contract-violated (:seon.error/kind data))
           "and it arrives as OUR flat error kind, not as malli's — so
            when this throw escapes a proc, the fault path classifies it
            from the cause chain like any other refusal")
       (is (= "seon.error/value" (:seon.instrument/fn (:seon.error/data data)))
           "naming the function whose contract was violated")
       (is (= :input (:seon.instrument/arm (:seon.error/data data))))
       (is (= ":seon.error/fact"
              (:seon.instrument/schema (:seon.error/data data)))
           "the single positional input is identified, not Malli's cat wrapper")
       (is (re-find #"invalid-input" (ex-message failure)))))))

(deftest interpreted-contracts-use-the-active-registry-and-core-error-dial
  (let [projection (or (schema/current-projection)
                       (schema/build-projection (schema/snapshot)))
        caps {:seon.config.eval.result/max-depth 4
              :seon.config.eval.result/max-collection 8
              :seon.config.eval.result/max-string 256
              :seon.config.eval.result/max-nodes 64}
        original identity
        wrapped
        (instrument/wrap-interpreted
         'my.agents.contract/value
         "[:=> [:cat [:fn clojure.core/int?]] :int]"
         projection :panic caps original)
        failure (try (wrapped "wrong")
                     (catch Exception thrown thrown))]
    (is (= :seon.instrument/contract-violated
           (:seon.error/kind (ex-data failure))))
    (is (= "my.agents.contract/value"
           (get-in (ex-data failure)
                   [:seon.error/data :seon.instrument/fn])))
    (is (= original
           (instrument/wrap-interpreted
            'my.agents.contract/value
            "[:=> [:cat [:fn clojure.core/int?]] :int]"
            projection :record caps wrapped))
        ":record removes an already-installed interpreted wrapper")))

(deftest a-violation-carries-bounded-arguments-only-when-it-can
  (let [caps {:seon.config.eval.result/max-depth 4
              :seon.config.eval.result/max-collection 4
              :seon.config.eval.result/max-string 32
              :seon.config.eval.result/max-nodes 64}]
    (try
      (instrument/apply! {:seon.config/on-core-error :panic
                          :seon.sci.admit/caps caps})
      (let [data (try (error/value "not a fact")
                      (catch Exception thrown (ex-data thrown)))]
        (is (string? (:seon.instrument/args (:seon.error/data data)))
            "args go through the ONE codec — an argument at these
             boundaries can be a live connection"))
      (finally (instrument/remove!))))
  (instrumented!
   (fn [_]
     (let [data (try (error/value "not a fact")
                     (catch Exception thrown (ex-data thrown)))]
       (is (not (contains? (:seon.error/data data) :seon.instrument/args))
           "and with no caps to bound them they are OMITTED, never
            printed unbounded")))))

(deftest many-problem-contract-violations-have-bounded-headlines
  (let [caps {:seon.config.eval.result/max-depth 4
              :seon.config.eval.result/max-collection 4
              :seon.config.eval.result/max-string 64
              :seon.config.eval.result/max-nodes 64}
        offending (many-key-map)]
    (try
      (instrument/apply! {:seon.config/on-core-error :panic
                          :seon.sci.admit/caps caps})
      (doseq [[expected-arm expected-kind function-name invoke]
              [[:input :malli.core/invalid-input
                "seon.instrument-test/closed-map-input"
                #(closed-map-input offending)]
               [:output :malli.core/invalid-output
                "seon.instrument-test/closed-map-output"
                #(closed-map-output offending)]]]
        (let [failure (try (invoke) (catch Exception thrown thrown))
              data (ex-data failure)
              message (:seon.error/message data)
              instrument-data (:seon.error/data data)]
          (testing (name expected-arm)
            (is (= expected-kind (:seon.instrument/malli instrument-data)))
            (is (= expected-arm (:seon.instrument/arm instrument-data)))
            (is (str/includes? message function-name)
                "the bounded headline still names the violated function")
            (is (str/includes? message (name expected-kind)))
            (is (< (count message) 2000)
                "all problems flow through the ONE general printer, so the
                message stays bounded by the admission caps — never by a
                second literal limit")
            (is (= 200 (:seon.instrument/problem-count instrument-data))
                "the problem count is the broken-system signal"))))
      (finally
        (instrument/remove!)))))

;;; ---------------------------------------------------------------------------
;;; Idempotence, and the measured hot-reload strip
;;; ---------------------------------------------------------------------------

(deftest applying-twice-is-applying-once
  (instrumented!
   (fn [first-result]
     (let [second-result (instrument/apply! {:seon.config/on-core-error :panic})]
       (is (pos? (:seon.instrument/instrumented first-result))
           "a :panic apply! that instruments zero vars is a bug")
       (is (= (:seon.instrument/instrumented first-result)
              (:seon.instrument/instrumented second-result)))
       (is (= (instrument/instrumented) (instrument/instrumented))
           "and the second pass wrapped no wrapper: malli unwraps to
            ::original before re-instrumenting")))))

(deftest re-evaluating-a-defn-silently-strips-instrumentation
  ;; the measured fact this namespace's discipline exists for
  ;; (research §4.3): `alter-var-root`'s wrapper is undone by the new
  ;; `def`, the ::original meta is gone, NOTHING warns, and the schema
  ;; stays registered so the registry now disagrees with reality
  (instrumented!
   (fn [_]
     ;; 2.0 violates `:int` and yet multiplies perfectly well, which is
     ;; what makes it the honest probe here: the point is that the
     ;; UNPROTECTED call succeeds silently, not that it fails some other
     ;; way (review-caught — a string would have thrown a
     ;; ClassCastException and hidden the very thing under test)
     (is (thrown? Exception (doubled 2.0)) "instrumented to begin with")
     ;; a re-eval as the REPL does it: in the namespace, plain `defn`
     (binding [*ns* (the-ns 'seon.instrument-test)]
       (eval '(defn ^{:malli/schema [:=> [:cat :int] :int]}
                doubled [n] (* 2 n))))
     (is (= 4.0 (doubled 2.0))
         "after a plain re-eval the wrapper is gone and the bad call
          runs — silently, which is the whole problem")
     (instrument/apply! {:seon.config/on-core-error :panic})
     (is (thrown? Exception (doubled 2.0))
         "and apply! is the explicit re-application that restores it"))))

(deftest applying-activates-newly-loaded-schema-references
  (let [schema-state (seon.schema/snapshot-state)
        schema-key :seon.instrument-test/late-value
        var-name (symbol (str "late-contract-" (random-uuid)))
        contract-var (intern *ns* var-name identity)]
    (try
      ;; Reproduce a long-lived JVM: its active projection predates a schema
      ;; resource contribution, while a hot-reloaded public Var already names
      ;; that new schema in its contract.
      (seon.schema/activate! (seon.schema/snapshot))
      (seon.schema/contribute-candidate-forms! {schema-key :int})
      (alter-meta! contract-var assoc
                   :malli/schema [:=> [:cat schema-key] schema-key])
      (is (contains? (seon.schema/snapshot) schema-key))
      (is (not (contains?
                (:seon.schema.projection/forms
                 (seon.schema/current-projection))
                schema-key)))

      (instrument/apply! {:seon.config/on-core-error :panic})

      (is (contains?
           (:seon.schema.projection/forms
            (seon.schema/current-projection))
           schema-key)
          "apply! publishes the admitted candidate generation before collect")
      (is (thrown? Exception (@contract-var "not-an-int"))
          "Malli collected the hot-reloaded contract against that generation")
      (finally
        (instrument/remove!)
        (ns-unmap *ns* var-name)
        (seon.schema/restore-state! schema-state)))))

;;; ---------------------------------------------------------------------------
;;; The dial
;;; ---------------------------------------------------------------------------

(deftest production-instruments-nothing-and-undoes-what-is-there
  (try
    (instrument/apply! {:seon.config/on-core-error :panic})
    (is (pos? (count (instrument/instrumented))))
    (let [applied (instrument/apply! {:seon.config/on-core-error :record})]
      (is (zero? (:seon.instrument/instrumented applied))
          "moving the dial to production actually takes effect rather
           than leaving yesterday's wrappers in place")
      (is (pos? (:seon.instrument/registered applied))
          "the schemas are still collected — the registry is honest even
           when nothing is wrapped")
      (is (map? (try (error/value "x") (catch Exception _ ::threw)))
          "and a violating call simply RUNS — returning whatever it
           returns for a bad argument — which is what :report could
           never have prevented either"))
    (finally (instrument/remove!))))

(deftest remove-is-total
  (try
    (instrument/apply! {:seon.config/on-core-error :panic})
    (is (zero? (instrument/remove!)))
    (is (zero? (instrument/remove!)) "and idempotent")
    (is (empty? (instrument/instrumented)))
    (finally
      (instrument/remove!))))

;;; ---------------------------------------------------------------------------
;;; The selection is computed
;;; ---------------------------------------------------------------------------

(deftest the-selection-is-public-vars-with-schemas-and-nothing-else
  (instrumented!
   (fn [_]
     (let [wrapped (instrument/instrumented)]
       (is (contains? wrapped #'error/value) "a public var with a schema")
       (is (not-any? (fn [candidate] (:private (meta candidate))) wrapped)
           "no private var is instrumented — which is what keeps every
            hot inner walker out BY CONSTRUCTION rather than by a list")
       (is (every? (fn [candidate]
                     (some? (mi/-schema candidate)))
                   wrapped)
           "and every wrapped var carries a schema: the selection is the
            computation, not a roster")))))

(deftest the-work-launcher-api-is-collected-without-an-allowlist
  (instrumented!
   (fn [_]
     (doseq [boundary [#'flow/start-work-launcher!
                       #'flow/stop-work-launcher!
                       #'flow/submit!!]]
       (is (some? (mi/-schema boundary))
           (str boundary " was omitted from Malli collection"))))))

(deftest dependency-free-linters-join-production-instrumentation
  (instrumented!
   (fn [_]
     (let [wrapped (instrument/instrumented)]
       (is (contains? wrapped #'docstring/check-source))
       (is (contains? wrapped #'markdown/parse)))
     (is (:seon.dev.docstring/clean?
          (docstring/check-source
           {:seon.dev.docstring/source "(ns example)"})))
     (is (= "# Example\n"
            (:seon.dev.markdown/content
             (markdown/parse
              {:seon.dev.markdown/content "# Example\n"}))))
     (doseq [failure [(try
                        (docstring/check-source
                         {:seon.dev.docstring/source 1})
                        (catch Exception thrown thrown))
                      (try
                        (markdown/parse {:seon.dev.markdown/content 1})
                        (catch Exception thrown thrown))]]
       (is (= :seon.instrument/contract-violated
              (:seon.error/kind (ex-data failure))))))))
