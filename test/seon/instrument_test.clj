(ns seon.instrument-test
  "Instrumentation: that it catches, that it is idempotent, and that a
  re-`defn` silently undoes it.

  THIS SUITE LEAVES THE JVM AS IT FOUND IT. Instrumentation is
  process-global — `alter-var-root` on every schema'd public var — so a
  test that turned it on and walked away would change how every LATER
  suite behaves, and the gate's result would depend on test order. Every
  test here restores the entering callable roots in a `finally`, and that discipline is the reason
  the whole gate is deterministic with this namespace in it.

  THE FIXTURE IS THE ARCHIVED DEFECT. `seon.db/transact!`
  declared `[:vector :any]` while the run loop's `:open` branch passed
  Datahike's argument map, and it worked only because Datahike's own
  spec admits a map by accident — see
  `docs/seon/issues/archive/loop-open-transaction-violates-transact-schema.md`.
  That is precisely the class instrumentation exists to catch, so the
  suite reproduces the shape rather than inventing one."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [malli.instrument :as mi]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.dev.docstring :as docstring]
            [seon.dev.markdown :as markdown]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.env :as env]
            [seon.error :as error]
            [seon.flow :as flow]
            [seon.fs :as fs]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test.arm]
            [seon.test-support :as test-support])
  (:import [java.nio.file Files LinkOption]))

(def ^:private function-schemas-state
  ;; Malli exposes collection as a process-global defonce atom but no exact
  ;; snapshot/restore operation. Tests must restore the entering map itself:
  ;; re-registering compiled schemas changes their identity and is not exact.
  (ns-resolve 'malli.core '-function-schemas*))

(defn- preserving-instrumentation-state
  [body]
  (test-support/with-database
   (fn [_connection]
     (let [instrumented-roots (into {}
                                    (map (juxt identity deref))
                                    (instrument/instrumented))
           function-schemas @@function-schemas-state]
       (try
         (body)
         (finally
           (try
             (doseq [candidate (instrument/instrumented)]
               (alter-var-root candidate mi/-f->original))
             (finally
               (reset! @function-schemas-state function-schemas)))
           (doseq [[instrumented-var root] instrumented-roots]
             (alter-var-root instrumented-var (constantly root)))))))))

(use-fixtures :each preserving-instrumentation-state)

(deftest an-invalid-core-error-mode-is-an-evidence-complete-value
  ;; `apply!` IS THE ARM. Boot calls it on a JVM whose contracts are not
  ;; installed yet, and that is the only state in which its own typed refusal
  ;; is reachable: once the contracts are armed, `:seon.instrument/request`
  ;; refuses a bad dial first, at the same crossing, naming the same
  ;; function. So the subject is reproduced in boot's state rather than in
  ;; one no caller is ever in; `preserving-instrumentation-state` puts this
  ;; worker's wrappers back afterwards.
  (instrument/remove!)
  (let [result ((mi/-f->original instrument/apply!)
                {:seon.config/on-core-error nil})]
    (is (= :seon.instrument/invalid-mode (:seon.error/kind result)))
    (is (= {:seon.error/diagnostic-layer :instrumentation
            :seon.error/diagnostic-operation 'seon.instrument/apply!
            :seon.error/diagnostic-member :seon.config/on-core-error
            :seon.error/diagnostic-expected [:enum :panic :record]
            :seon.error/diagnostic-offending :seon.instrument/nil
            :seon.error/diagnostic-cause :seon.instrument/invalid-mode
            :seon.error/diagnostic-evidence-availability :seon.error/known
            :seon.error/diagnostic-evidence
            {:seon.instrument/accepted-modes [:panic :record]}}
           (select-keys
            (:seon.error/data result)
            [:seon.error/diagnostic-layer
             :seon.error/diagnostic-operation
             :seon.error/diagnostic-member
             :seon.error/diagnostic-expected
             :seon.error/diagnostic-offending
             :seon.error/diagnostic-cause
             :seon.error/diagnostic-evidence-availability
             :seon.error/diagnostic-evidence]))))
  ;; and armed, the contract owns the same refusal at the same crossing
  (instrument/apply! {:seon.config/on-core-error :panic})
  (let [refusal (test-support/refusal-data
                 #(instrument/apply! {:seon.config/on-core-error :degrade}))]
    (is (= :seon.instrument/contract-violated (:seon.error/kind refusal)))
    (is (= 'seon.instrument/apply!
           (:seon.error/diagnostic-operation (:seon.error/data refusal))))))

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
         [:cat [:map [:seon.instrument-test/expected :int]]]
         :int]}
  declared-map-input
  [_]
  1)

(defn ^{:malli/schema
        [:=>
         [:cat [:vector :int]]
         :int]}
  many-problem-input
  [_]
  1)

(defn ^{:malli/schema
        [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The instrumentation regression deliberately returns arbitrary supplied values to exercise an invalid-output explanation.", :gen/elements [nil false 0 "" :k [] {}]}]] [:vector :int]]}
  many-problem-output
  [value]
  value)

(defn ^{:malli/schema [:=> [:cat :int] :int]} integer-inspector
  [value]
  value)

(defn- ^{:malli/schema [:=> [:cat :int] :int]} private-integer-boundary
  [value]
  (if (zero? value) "invalid output" value))

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] [:maybe :string]]}
  optional-positional
  [value]
  value)

(defn ^{:malli/schema [:=> [:cat :int] :int]} prefix-contract
  [value]
  value)

(defn ^{:malli/schema [:=> [:cat :string :string] :string]} prefix-contract-in
  [left right]
  (str left right))

(defn- many-invalid-values
  []
  (vec (repeat 200 "not an integer")))

;;; ---------------------------------------------------------------------------
;;; It catches, and it throws
;;; ---------------------------------------------------------------------------

(deftest optional-positional-nil-is-valid-under-instrumentation
  (instrumented!
   (fn [_]
     (is (nil? (optional-positional nil))
         "a :maybe positional is validated as nil, not as its child schema"))))

(deftest a-public-multi-arity-does-not-reenter-its-instrumented-var
  (let [base (str "tmp/instrumented-fs-test/" (random-uuid))
        root (io/file base "owned")
        outside (io/file base "outside")
        sentinel (io/file outside "nested/must-survive.txt")
        link (io/file root "linked-elsewhere")
        no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])]
    (try
      (.mkdirs root)
      (.mkdirs (.getParentFile sentinel))
      (spit sentinel "do not delete me")
      (Files/createSymbolicLink
       (.toPath link)
       (.toAbsolutePath (.toPath outside))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (instrumented!
       (fn [_]
         (is (nil? (fs/delete-recursively! (.getPath root) (.getPath root))))
         (is (not (Files/exists (.toPath root) no-follow)))
         (is (Files/exists (.toPath sentinel) no-follow)
             "the instrumented two-arity still preserves symlink targets")
         (let [failure
               (try
                 (fs/delete-recursively! base (.getPath outside) nil)
                 (catch Exception thrown thrown))]
           (is (= :seon.instrument/contract-violated
                  (:seon.error/kind (ex-data failure)))
               "the direct three-arity still requires its options map"))))
      (finally
        (fs/delete-recursively! base base)))))

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
       (is (= 'seon.error/value
              (:seon.error/diagnostic-operation (:seon.error/data data)))
           "naming the function whose contract was violated")
       (is (= :arguments
              (:seon.error/diagnostic-member (:seon.error/data data))))
       (is (= :seon.error/fact
              (:seon.error/diagnostic-expected (:seon.error/data data)))
           "a cluster re-arm retains the JVM wrapper's bounded evidence policy")
       (is (str/includes? (ex-message failure) "refused fact at []: expected a map, got a string"))))))

(deftest projection-gates-inspect-the-complete-candidate-population
  (instrumented!
   (fn [_]
     (let [base-key :seon.instrument-test.local/base
           alias-key :seon.instrument-test.local/alias
           forms (assoc (schema/snapshot)
                        base-key :string
                        alias-key base-key)
           projection (schema/build-projection forms)]
       (is (= base-key
              (get (:seon.schema.projection/forms projection) alias-key))
           "a projection gate inspects candidate data against the population
            it was given; instrumentation must not prevalidate that data
            against the process-global registry")))))

(deftest interpreted-contracts-use-the-active-registry-and-core-error-dial
  (let [projection (or (schema/current-projection)
                       (schema/build-projection (schema/snapshot)))
        caps (assoc (config/result-caps (test-support/effective-config))
                    :seon.config.eval.result/max-depth 4
                    :seon.config.eval.result/max-collection 8
                    :seon.config.eval.result/max-string 256
                    :seon.config.eval.result/max-nodes 64)
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
    (is (= 'my.agents.contract/value
           (get-in (ex-data failure)
                   [:seon.error/data :seon.error/diagnostic-operation])))
    (is (= original
           (instrument/wrap-interpreted
            'my.agents.contract/value
            "[:=> [:cat [:fn clojure.core/int?]] :int]"
            projection :record caps wrapped))
        ":record removes an already-installed interpreted wrapper")))

(defn guarded-result
  "A fixture function whose pair of arguments and return must be a map."
  {:malli/schema [:=> [:cat :int] :int
                  [:fn {:error/message "The guard was evaluated."} clojure.core/map?]]}
  [value]
  value)

(deftest jvm-and-interpreted-functions-arm-the-declared-guard
  (let [projection (schema/handed-projection)
        caps (config/result-caps (test-support/effective-config))
        interpreted (instrument/wrap-interpreted
                     'my.agents.guarded/result
                     "[:=> [:cat :int] :int [:fn {:error/message \"The guard was evaluated.\"} clojure.core/map?]]"
                     projection :panic caps identity)]
    (instrument/apply! {:seon.config/on-core-error :panic :seon.schema/projection projection})
    (doseq [call [#(guarded-result 1) #(interpreted 1)]]
      (let [failure (test-support/refusal-data call)]
        (is (= :seon.instrument/contract-violated (:seon.error/kind failure)))
        (is (= :malli.core/invalid-guard
               (get-in failure [:seon.error/data :seon.instrument/malli])))
        (is (str/includes? (:seon.error/message failure) "The guard was evaluated."))))))

(deftest a-sci-only-arity-miss-names-its-program-graph-arglists
  (test-support/with-database
   (fn [connection]
     (let [function-symbol 'my.agents.reporter/largest
           registration (db/transact!
              connection
              [{:seon.fn/sym (str function-symbol)
                :seon.schema.admission/source :agent
                :seon.fn/ns {:seon.ns/name 'my.agents.reporter}
                :seon.fn/arglists "([rows])"}])
           projection (schema/build-projection (schema/snapshot))
           wrapped (instrument/wrap-interpreted
                    function-symbol
                    "[:=> [:cat [:vector :map]] :map]"
                    projection :panic
                    ;; the declared admission caps, like every production
                    ;; caller hands
                    (config/result-caps (test-support/effective-config))
                    identity)
           environment (env/environment
                        {:seon.boot/cluster-name "instrument-test"
                         :seon.db/connection connection})
           failure (binding [effect/*request-context*
                             {:seon.env/environment environment}]
                     (try (wrapped)
                          (catch Exception thrown thrown)))]
       (is (:db-after registration) (pr-str registration))
       (is (env/environment? environment) (pr-str environment))
       (let [diagnostic (ex-data failure)]
         (is (str/includes? (error/render-ai diagnostic)
                            (str function-symbol " refused argument count at []:")))
         (is (str/includes? (error/render-ai diagnostic) "([rows])"))
         (is (str/includes? (error/render-ai diagnostic) "0"))
         (is (= {:seon.error/diagnostic-layer :instrumentation
                 :seon.error/diagnostic-operation function-symbol
                 :seon.error/diagnostic-member :arity
                 :seon.error/diagnostic-expected '([rows])
                 :seon.error/diagnostic-offending 0
                 :seon.error/diagnostic-cause :malli.core/invalid-arity
                 :seon.error/diagnostic-evidence-availability
                 :seon.error/known
                 :seon.error/diagnostic-evidence
                 {:seon.instrument.lookup/status :found
                  :seon.instrument/arglists '([rows])}}
                (select-keys
                 (:seon.error/data diagnostic)
                 [:seon.error/diagnostic-layer
                  :seon.error/diagnostic-operation
                  :seon.error/diagnostic-member
                  :seon.error/diagnostic-expected
                  :seon.error/diagnostic-offending
                  :seon.error/diagnostic-cause
                  :seon.error/diagnostic-evidence-availability
                  :seon.error/diagnostic-evidence]))))))))

(deftest a-violation-carries-bounded-arguments-only-when-it-can
  (let [caps (assoc (config/result-caps (test-support/effective-config))
                    :seon.config.eval.result/max-depth 4
                    :seon.config.eval.result/max-collection 4
                    :seon.config.eval.result/max-string 32
                    :seon.config.eval.result/max-nodes 64)]
    (try
      (instrument/apply! {:seon.config/on-core-error :panic
                          :seon.sci.admit/caps caps})
      (let [data (try (error/value "not a fact")
                      (catch Exception thrown (ex-data thrown)))]
        (is (= ["not a fact"]
               (:seon.error/diagnostic-offending (:seon.error/data data)))
            "arguments remain bounded ordinary data, never a printed value")
        (is (= :seon.error/fact
               (:seon.error/diagnostic-expected (:seon.error/data data))))
        (is (nil? (::instrument/args (:seon.error/data data)))
            "semantic evidence is not duplicated as serialized arguments"))
      (finally (instrument/remove!))))
  (instrumented!
   (fn [_]
     (let [data (try (error/value "not a fact")
                     (catch Exception thrown (ex-data thrown)))]
       (is (= ["not a fact"]
              (:seon.error/diagnostic-offending (:seon.error/data data)))
           "re-arming without caps cannot replace the shared reporter")))))

(deftest a-flat-error-value-at-a-contract-boundary-is-its-own-face
  (let [violation @#'instrument/violation
        inner {:seon.error/kind :seon.db/missing-connection-binding
               :seon.error/message "No connection is bound on this thread."
               :seon.error/data {:seon.db/binding 'seon.db/*conn*}}]
    (testing "the inner error is the answer, never buried in wrapper prose"
      (is (= inner
             (violation nil :malli.core/invalid-input
                        {:fn-name 'seon.config/effective
                         :input [:cat :map]
                         :args [inner]})))
      (is (= inner
             (violation nil :malli.core/invalid-output
                        {:fn-name 'seon.config/effective
                         :output :map
                         :value inner}))))
    (testing "an ordinary contract violation still reports as one"
      (is (= :seon.instrument/contract-violated
             (:seon.error/kind
              (violation nil :malli.core/invalid-input
                         {:fn-name 'seon.config/effective
                          :input [:cat :map]
                          :args [42]})))))))

(deftest contract-problems-are-semantic-once-never-a-serialized-print-tree
  (let [caps (assoc (config/result-caps (test-support/effective-config))
                    :seon.config.eval.result/max-depth 8
                    :seon.config.eval.result/max-collection 32
                    :seon.config.eval.result/max-string 4096
                    :seon.config.eval.result/max-nodes 1024)]
    (try
      (instrument/apply! {:seon.config/on-core-error :panic
                          :seon.sci.admit/caps caps})
      (let [failure
            (try
              (declared-map-input
               {:seon.instrument-test/expected "not an int"})
              (catch Exception thrown thrown))
            data (ex-data failure)
            message (:seon.error/message data)
            problems (get-in data
                             [:seon.error/data :seon.error/problems])]
        (is (str/includes? message "seon.instrument-test"))
        (is (str/includes? message "expected an integer"))
        (is (not (str/includes? message ":seon.print/face"))
            "the print-node tree is rendered rather than serialized inline")
        (is (not (str/includes? message "\n"))
            "contract feedback is one readable line")
        (is (vector? problems))
        (is (= 1 (count problems)))
        (is (every? map? problems))
        (is (not (str/includes? (pr-str data) ":seon.print/face"))
            "error data contains semantic evidence, never a serialized print tree"))
      (finally
        (instrument/remove!)))))

(deftest many-problem-contract-violations-have-bounded-headlines
  (let [caps (assoc (config/result-caps (test-support/effective-config))
                    :seon.config.eval.result/max-depth 4
                    :seon.config.eval.result/max-collection 4
                    :seon.config.eval.result/max-string 64
                    :seon.config.eval.result/max-nodes 64)
        offending (many-invalid-values)]
    (try
      (instrument/apply! {:seon.config/on-core-error :panic
                          :seon.sci.admit/caps caps})
      (doseq [[expected-arm expected-kind function-name invoke]
              [[:input :malli.core/invalid-input
                "seon.instrument-test/many-problem-input"
                #(many-problem-input offending)]
               [:output :malli.core/invalid-output
                "seon.instrument-test/many-problem-output"
                #(many-problem-output offending)]]]
        (let [failure (try (invoke) (catch Exception thrown thrown))
              data (ex-data failure)
              message (:seon.error/message data)
              instrument-data (:seon.error/data data)]
          (testing (name expected-arm)
            (is (= expected-kind (:seon.instrument/malli instrument-data)))
            (is (= expected-arm (:seon.instrument/arm instrument-data)))
            (is (str/includes? message function-name)
                "the bounded headline still names the violated function")
            (is (str/includes? message "refused"))
            (is (< (tokens/estimate message) 64)
                "the headline is a concise diagnosis measured in estimated tokens")
            (is (= 200 (:seon.instrument/problem-count instrument-data))
                "the problem count is the broken-system signal"))))
      (finally
        (instrument/remove!)))))

(deftest refusal-value-projection-obeys-the-profile-and-html-keeps-the-whole-value
  (let [raw (concat (range 8) ["candidate-final"])
        wrapped (instrument/wrap-interpreted
                 'my.agents.audit/vector-input "[:=> [:cat [:vector :int]] :int]"
                 (schema/handed-projection) :panic
                 (config/result-caps (test-support/effective-config)) (constantly 1))
        refusal (try (wrapped raw) (catch Exception failure (ex-data failure)))
        unit {:seon.render/value refusal
              :seon.repl/handle 'result/eaudit
              :seon.render/profile
              {:seon.render.profile/id ::refusal-profile
               :seon.render.profile/token-budget 2048
               :seon.render.profile/max-depth 8
               :seon.render.profile/max-children 3
               :seon.render.profile/max-string-length 256
               :seon.render.profile/composition :multiline}}
        ai (error/render-ai unit)
        html (pr-str (error/render-html unit))]
    (is (identical? raw (get-in refusal [:seon.error/data :seon.error/problems 0 :seon.error/offending])))
    (is (str/includes? ai "expected a vector") ai)
    (is (str/includes? ai ":seon.print/omitted") ai)
    (is (str/includes? ai "(get-in result/eaudit [:seon.error/data :seon.error/problems 0 :seon.error/offending])") ai)
    (is (not (str/includes? ai "candidate-final")) ai)
    (is (str/includes? html "candidate-final") html)))

(deftest registry-sized-contract-evidence-retains-the-offending-object
  (let [caps (config/result-caps (test-support/effective-config))
        inline-ceiling 4096
        allocation-ceiling (* 16 1024 1024)
        registry (schema/snapshot)
        thread-bean
        ^com.sun.management.ThreadMXBean
        (java.lang.management.ManagementFactory/getThreadMXBean)
        thread-id (.getId (Thread/currentThread))]
    (is (> (count (pr-str registry)) inline-ceiling)
        "the reproduction passes a genuinely oversized schema registry")
    (when-not (.isThreadAllocatedMemoryEnabled thread-bean)
      (.setThreadAllocatedMemoryEnabled thread-bean true))
    (try
      (instrument/apply! {:seon.config/on-core-error :panic
                          :seon.sci.admit/caps caps})
      ;; Projection acquisition is deliberately per test. Exercise the
      ;; instrumented boundary once so this measurement isolates violation
      ;; construction rather than first-use registry realization.
      (try (integer-inspector registry) (catch Exception _))
      (let [before (.getThreadAllocatedBytes thread-bean thread-id)
            failure (try (integer-inspector registry)
                         (catch Exception thrown thrown))
            allocated (- (.getThreadAllocatedBytes thread-bean thread-id)
                         before)
            data (ex-data failure)
            instrument-data (:seon.error/data data)
            received (first (:seon.error/diagnostic-offending instrument-data))]
        (is (identical? registry received)
            "the seam retains the actual object; presentation alone elides it")
        (is (< allocated allocation-ceiling)
            (str "construction allocated " allocated
                 " bytes; the issue baseline was 150,063,304"))
        (is (= 1 (:seon.instrument/problem-count instrument-data)))
        (is (= :int (:seon.error/diagnostic-expected instrument-data)))
        (is (identical? registry
                        (get-in instrument-data [:seon.error/problems 0 :seon.error/offending]))))
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

(deftest authored-contract-changes-rearm-without-reloading
  (let [var-name (symbol (str "contract-freshness-" (random-uuid)))
        candidate (intern 'seon.instrument-test var-name identity)
        old-key :seon.instrument-test/old-request
        new-key :seon.instrument-test/new-request
        projection (schema/build-projection
                    (assoc (schema/snapshot)
                           old-key [:map [::value :int]]
                           new-key [:map [::value [:or :int :string]]]))]
    (try
      (schema/call-with-projection
       projection
       (fn []
         (alter-meta! candidate assoc :malli/schema [:=> [:cat old-key] :map])
         (instrument/apply! {:seon.config/on-core-error :panic
                             :seon.schema/projection projection})
         (let [before @candidate]
           (is (thrown? Exception (candidate {::value "new input"})))
           (alter-meta! candidate assoc :malli/schema [:=> [:cat new-key] :map])
           (instrument/apply! {:seon.config/on-core-error :panic
                               :seon.schema/projection projection})
           (is (not (identical? before @candidate)))
           (is (identical? (mi/-f->original before) (mi/-f->original @candidate))
               "Only the contract changed; the loaded function was not replaced.")
           (is (= {::value "new input"} (candidate {::value "new input"})))
           (let [failure (try (candidate {::value false})
                              (catch Exception refusal refusal))]
             (is (= :seon.instrument/contract-violated (:seon.error/kind (ex-data failure))))
             (is (str/includes? (ex-message failure) (str new-key))))
           (let [current @candidate]
             (instrument/apply! {:seon.config/on-core-error :panic
                                 :seon.schema/projection projection})
             (is (identical? current @candidate))))))
      (finally (ns-unmap 'seon.instrument-test var-name)))))

(deftest referenced-contract-changes-rearm-only-dependent-wrappers
  (let [candidate-name (symbol (str "referenced-contract-" (random-uuid)))
        unrelated-name (symbol (str "unrelated-contract-" (random-uuid)))
        candidate (intern 'seon.instrument-test candidate-name identity)
        unrelated (intern 'seon.instrument-test unrelated-name identity)
        entity-key :seon.instrument-test/referenced-entity
        alias-key :seon.instrument-test/referenced-alias
        forms (assoc (schema/snapshot)
                     entity-key [:map [:seon.instrument-test/value :int]]
                     alias-key entity-key)
        before-projection (schema/build-projection forms)
        after-projection (schema/build-projection
                          (assoc forms entity-key
                                 [:map [:seon.instrument-test/value [:or :int :string]]]))
        arm (fn [projection]
              (schema/call-with-projection
               projection
               #(instrument/apply! {:seon.config/on-core-error :panic
                                    :seon.schema/projection projection})))]
    (try
      (alter-meta! candidate assoc :malli/schema [:=> [:cat alias-key] :map])
      (alter-meta! unrelated assoc :malli/schema [:=> [:cat :int] :int])
      (arm before-projection)
      (let [before @candidate
            other-before @unrelated]
        (is (thrown? Exception
                     (schema/call-with-projection
                      before-projection #(candidate {:seon.instrument-test/value "new"}))))
        (arm after-projection)
        (is (not (identical? before @candidate)))
        (is (identical? other-before @unrelated))
        (is (not= (:seon.instrument/contract-digest (meta before))
                  (:seon.instrument/contract-digest (meta @candidate))))
        (is (= {:seon.instrument-test/value "new"}
               (schema/call-with-projection
                after-projection #(candidate {:seon.instrument-test/value "new"}))))
        (is (thrown? Exception
                     (schema/call-with-projection
                      after-projection #(candidate {:seon.instrument-test/value false}))))
        (let [current @candidate]
          (arm after-projection)
          (is (identical? current @candidate))
          (is (identical? other-before @unrelated))))
      (finally
        (ns-unmap 'seon.instrument-test candidate-name)
        (ns-unmap 'seon.instrument-test unrelated-name)))))

(deftest cold-arming-derives-wrapper-identity-without-a-predicate-binding
  (let [projection (#'seon.test.arm/packaged-test-projection "cold-arming-regression")
        candidate-name (symbol (str "cold-contract-" (random-uuid)))
        candidate (intern 'seon.instrument-test candidate-name identity)
        contract [:=> [:cat :seon.agent/id] :seon.agent/id]
        caps (config/result-caps (config/defaults))
        arm (fn []
              (#'instrument/arm-var! candidate contract projection projection caps)
              {:seon.instrument/contract-digest
               (:seon.instrument/contract-digest (meta @candidate))
               :seon.instrument-test/value (candidate "juniper")})]
    (try
      (is (not (contains? projection :seon.schema.projection/predicate-functions)))
      (let [bound (schema/call-with-projection projection arm)]
        (alter-var-root candidate (constantly identity))
        (let [cold (with-bindings {#'schema/*projection* nil
                                  #'schema/*projection-state* nil}
                     (arm))]
          (is (= "juniper" (:seon.instrument-test/value cold)))
          (is (= 64 (count (:seon.instrument/contract-digest cold))))
          (is (= bound cold))
          (is (thrown? Exception (candidate 42)))))
      (finally
        (ns-unmap 'seon.instrument-test candidate-name)))))

(deftest prefix-related-sibling-vars-keep-their-own-contracts
  (instrumented!
   (fn [_]
     (let [database-form
           [:symbol {:seon.db/identity true :seon.search/index :symbol}]]
       (is (= database-form
              (schema.datahike/resolve-datahike-form database-form))
           "the observed one-argument resolver remains callable under the
            operation's handed projection")
       (is (= 7 (prefix-contract 7)))
       (is (= "left-right" (prefix-contract-in "left-" "right")))
       (doseq [[expected-function invoke]
               [['seon.instrument-test/prefix-contract
                 #(apply prefix-contract ["left" "right"])]
                ['seon.instrument-test/prefix-contract-in
                 #(apply prefix-contract-in [7])]]]
         (let [failure (try (invoke) (catch Exception thrown thrown))]
           (is (= :seon.instrument/contract-violated
                  (:seon.error/kind (ex-data failure))))
           (is (= expected-function
                  (get-in (ex-data failure)
                          [:seon.error/data
                           :seon.error/diagnostic-operation]))
               "each exact qualified Var symbol selects its own contract")))))))

(deftest registration-failure-names-the-var-and-authored-contract
  (let [namespace-name 'n5.registration.probe
        namespace-object (create-ns namespace-name)
        function-symbol 'n5.registration.probe/broken
        authored-schema [:=> [:cat [:ref :n5/missing]] :int]]
    (try
      (intern namespace-object
              (with-meta 'broken {:malli/schema authored-schema})
              identity)
      (let [failure
            (try
              ;; ABSENT MEANS NO KEY: the admission caps are optional here
              ;; and a nil in an optional key fails its contract.
              (instrument/apply! {:seon.config/on-core-error :panic})
              (catch clojure.lang.ExceptionInfo thrown thrown))
            diagnostic (ex-data failure)]
        (is (= function-symbol
               (:seon.error/diagnostic-member
                (:seon.error/data diagnostic))))
        (is (= authored-schema
               (:seon.error/diagnostic-expected
                (:seon.error/data diagnostic))))
        (is (= :malli.core/invalid-ref
               (:seon.error/diagnostic-cause
                (:seon.error/data diagnostic))))
        (is (= :n5/missing
               (:seon.error/diagnostic-offending
                (:seon.error/data diagnostic)))))
      (finally
        (instrument/remove!)
        (remove-ns namespace-name)))))

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

(deftest applying-uses-the-acquired-projection-without-publishing-it
  (let [schema-key :seon.instrument-test/late-value
        var-name (symbol (str "late-contract-" (random-uuid)))
        contract-var (intern *ns* var-name identity)
        projection
        (schema/build-projection (assoc (schema/snapshot) schema-key :int))
        projection-state (atom {:seon.schema/projection projection})]
    (try
      (alter-meta! contract-var assoc
                   :malli/schema [:=> [:cat schema-key] schema-key])
      (let [observed
            (schema/call-with-projection-state
             projection-state
             #(do
                (instrument/apply!
                 {:seon.config/on-core-error :panic})
                (schema/snapshot)))]
        (is (contains? observed schema-key)))

      (is (not (contains? (schema/snapshot) schema-key))
          "the acquired projection remains cluster-local")
      (is (thrown? Exception (@contract-var "not-an-int"))
          "Malli collected the contract against the acquired projection")
      (finally
        (instrument/remove!)
        (ns-unmap *ns* var-name)))))

(deftest applying-without-a-handed-projection-refuses-before-collection
  (let [collected? (atom false)
        result
        ;; A PROJECTION STATE IS A REFERENCE HOLDING ONE ENVIRONMENT, and
         ;; the declared contract says so; the missing-projection subject is
         ;; the redefined `handed-projection` below, not a nil in the state.
         (schema/call-with-projection-state
         (atom {})
         (fn []
           (with-redefs-fn
            {#'schema/handed-projection (fn [] nil)
             #'instrument/collect-contracts!
             (fn [_]
               (reset! collected? true)
               [])}
            (fn []
              (instrument/apply!
               {:seon.config/on-core-error :panic})))))]
    (is (= :seon.instrument/missing-projection
           (:seon.error/kind result)))
    (is (false? @collected?)
        "a missing projection must refuse before Malli collects contracts")))

;;; ---------------------------------------------------------------------------
;;; The dial
;;; ---------------------------------------------------------------------------

(deftest cluster-policy-and-removal-preserve-the-jvm-wrappers
  (instrument/apply! {:seon.config/on-core-error :panic})
  (let [before (into {} (map (juxt identity deref)) (instrument/instrumented))
        applied (instrument/apply! {:seon.config/on-core-error :record})]
    (is (pos? (count before)))
    (is (= (count before) (:seon.instrument/instrumented applied)))
    (is (= (count before) (instrument/remove!)))
    (is (= (count before) (instrument/remove!)))
    (is (every? (fn [[candidate root]] (identical? root @candidate)) before))
    (is (thrown? Exception (error/value "not a fact")))))

;;; ---------------------------------------------------------------------------
;;; The selection is computed
;;; ---------------------------------------------------------------------------

(deftest the-selection-is-declared-vars-with-schemas-and-nothing-else
  (instrumented!
   (fn [_]
     (let [wrapped (instrument/instrumented)]
       (is (contains? wrapped #'error/value) "a public var with a schema")
       (is (contains? wrapped #'private-integer-boundary)
           "a private callable with a declared contract is also a boundary")
       (is (every? (fn [candidate]
                     (some? (mi/-schema candidate)))
                   wrapped)
           "and every wrapped var carries a schema: the selection is the
            computation, not a roster")
       (doseq [[kind call]
               [[:input #(apply private-integer-boundary ["not an integer"])]
                [:output #(private-integer-boundary 0)]]]
         (let [failure (try (call) (catch Exception thrown thrown))]
           (is (= :seon.instrument/contract-violated
                  (:seon.error/kind (ex-data failure)))
               (str "private boundary rejects invalid " (name kind)))))))))

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
