(ns seon.instrument-test
  "Instrumentation: that it catches, that it is idempotent, and that a
  re-`defn` silently undoes it.

  THIS SUITE LEAVES THE JVM AS IT FOUND IT. Instrumentation is
  process-global — `alter-var-root` on every schema'd var, private included — so a
  test that turned it on and walked away would change how every LATER
  suite behaves, and the gate's result would depend on test order. Every
  test here runs inside `seon.test-support/preserving-instrumentation-state`,
  whose `seon.instrument/restore!` returns the entering state even after a
  throw and never reinstalls a closure over a reloaded definition.

  THE FIXTURE IS THE ARCHIVED DEFECT. `seon.db/transact!`
  declared `[:vector :any]` while the run loop's `:open` branch passed
  Datahike's argument map, and it worked only because Datahike's own
  spec admits a map by accident — see
  `docs/seon/issues/archive/loop-open-transaction-violates-transact-schema.md`.
  That is precisely the class instrumentation exists to catch, so the
  suite reproduces the shape rather than inventing one."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [malli.instrument :as mi]
            [malli.core :as m]
            [malli.registry :as mr]
            [sci.core :as sci]
            [seon.ai :as ai]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.cluster :as cluster]
            [seon.fault :as fault]
            [seon.dev.docstring :as docstring]
            [seon.dev.markdown :as markdown]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.env :as env]
            [seon.error :as error]
            [seon.flow :as flow]
            [seon.fs :as fs]
            [seon.instrument :as instrument]
            [seon.print :as print]
            [seon.repl :as repl]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.kernel :as kernel]
            [seon.sci.eval :as sci.eval]
            [seon.sci.admit :as admit]
            [seon.test-support :as test-support])
  (:import [java.nio.file Files LinkOption]))

(defn- scoped-instrumentation
  "Each test runs on its own fixture branch, and `seon.instrument/restore!`
  (through the canonical scope) returns every root it armed or unarmed;
  a definition a reload replaced stays the loader's."
  [body]
  (test-support/with-database
   (fn [_connection]
     (test-support/preserving-instrumentation-state body))))

(use-fixtures :each scoped-instrumentation)

(defn- arm-with!
  "Arm `candidate` as adoption does: compile its wrapper once against
  `projection`, then install that wrapper."
  [candidate contract projection caps policy]
  (#'instrument/arm-var! candidate contract projection
                         (#'instrument/compiled-wrapper projection (symbol candidate) contract
                                                        (mi/-f->original @candidate) caps policy)
                         policy))

(deftest an-invalid-apply-request-is-refused-at-its-crossing
  ;; The core-error mode this test once refused is deleted (7c40396b3, M1 0b:
  ;; the :panic/:record dial carriers), so `{}` is a valid request and the
  ;; unarmed typed refusal it reached is gone; the missing-projection
  ;; registration error keeps its own regression below. Armed, the request
  ;; contract refuses an invalid dial at the crossing, naming `apply!`.
  (instrument/apply! {})
  (let [refusal (test-support/refusal-data
                 #(instrument/apply! {:seon.config.error/max-evidence-bytes 0}))]
    (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) refusal))
    (is (= 'seon.instrument/apply!
           (:seon.error/operation refusal)))))

(defn- instrumented!
  "Run `body` with instrumentation on, and always take it back off."
  [body]
  (try
    (body (instrument/apply! {}))
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

(defn- arm-prefix-contract!
  "Arm this suite's `prefix-contract` as adoption does. The suite's namespace
  loads after boot arming, so nothing else arms its Vars; the fixture restores
  the root afterwards."
  []
  (let [result (instrument/apply!
                {:seon.schema/projection (schema/handed-projection)
                 :seon.instrument/changed-identities
                 #{[:seon.fn/sym 'seon.instrument-test/prefix-contract]}})]
    (is (= 1 (:seon.instrument/registered result)) (pr-str result))))

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

(deftest host-boundaries-enforce-per-arity-declared-schemas-in-both-modes
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "host-error-wrapper")
     (let [projection (schema/projection-from-database (db/db connection))
           caps (config/result-caps (test-support/effective-config))
           contract [:function
                     [:=> [:cat :map] [:map [::success :boolean]]]
                     [:=> [:cat :map :int] [:or :int :seon.agent/error]]]
           base {:seon.error/at #inst "2026-09-18T00:00:00Z"
                 :seon.error/layer :seon.instrument-test/body
                 :seon.error/operation 'seon.instrument-test/host-declared-schema}
           domain (assoc base :seon.agent/error-agent-id "observed-agent")
           calls (atom 0)
           candidate-name (symbol (str "host-declared-schema-" (random-uuid)))
           candidate (intern 'seon.instrument-test candidate-name
                             (fn [value & _] (swap! calls inc) value))]
       (try
         ;; D01 (lost-error seams): the wrapper returns only declared values in
         ;; BOTH dials. Every refusal throws; none is returned as the value of
         ;; a function whose output contract does not declare it, and the
         ;; wrapper records nothing: the owning boundary decides that.
         (doseq [mode [:panic :record]]
           (arm-with! candidate contract projection caps
                      {:seon.config.error/max-evidence-bytes
                       (:seon.config.error/max-evidence-bytes
                        (test-support/effective-config))})
           (let [before @calls
                 input (test-support/refusal-data #(candidate 42))
                 arity (test-support/refusal-data #(candidate))]
             (is (= before @calls) "Input and arity checks precede the body.")
             (doseq [[refusal declared-schema] [[input :seon.instrument/contract-error]
                                      [arity :seon.instrument/arity-error]]]
               (is ((schema/projection-validator projection declared-schema) refusal)
                   (pr-str refusal))))
           (let [refusal (test-support/refusal-data #(candidate domain))]
             (is (= :output (:seon.instrument/check refusal)) (pr-str refusal))
             (is (= 1 (:seon.instrument/arity refusal)))
             (is ((schema/projection-validator projection :seon.instrument/contract-error) refusal))
             (is (= domain (candidate domain 1)) "Only the declaring arity permits this declared-schema."))
           (let [refusal (test-support/refusal-data #(candidate base))]
             (is (= :output (:seon.instrument/check refusal)))
             (is ((schema/projection-validator projection :seon.instrument/contract-error) refusal))))
         (is (empty? (db/q '[:find ?e :in $ ?function
                             :where [?e :seon.instrument/fn ?function]]
                           (db/db connection) (symbol "seon.instrument-test" (str candidate-name))))
             "The wrapper stores no fault in either dial.")
         (finally (ns-unmap 'seon.instrument-test candidate-name)))))))

(deftest armed-compositions-return-only-their-declared-alternatives
  ;; D02, D03 (lost-error seams): a unit naming no root identity is valid
  ;; input, and the floor answers it with its declared refusal. The attempt
  ;; renderers now declare that alternative, so their armed output check
  ;; admits it instead of refusing a value the callee declares.
  (let [unit {:seon.render/value {:seon.ai.attempt/id "rootless"}}
        missing-root? (schema/projection-validator (schema/handed-projection)
                                                   :seon.render.value/missing-root-identity-error)]
    (is (contains? (instrument/instrumented) #'ai/attempt-ai))
    (doseq [render [ai/attempt-ai ai/attempt-html]]
      (is (missing-root? (render unit)))))
  ;; E07: a core fault already recorded by this propagation is rethrown by
  ;; the evaluation boundary's one normalizer, directly or wrapped, never
  ;; turned into an ordinary evaluation value.
  (let [receipt {:seon.boot/cluster-name "seon-instrument-test"
                 :seon.fault/committed? true
                 :seon.error/layer :seon.instrument-test/receipt
                 :seon.error/operation 'seon.instrument-test/receipt}
        panic (ex-info "SEON CORE FAULT (panic): recorded" {:seon.fault/recorded receipt}
                       (ex-info "the original failure" {}))
        request {:seon.sci.kernel/time-limit-kind :seon.sci.kernel/time-limit
                 :seon.sci.kernel/failure-kind :seon.sci.kernel/invocation-failed}
        record (kernel/unarmed-record (System/nanoTime))]
    (doseq [thrown [panic (ex-info "an interpreter frame" {} panic)]]
      (is (identical? thrown (try (kernel/failure-value request thrown record)
                                  (catch clojure.lang.ExceptionInfo rethrown rethrown)))))
    (is (:seon.error/at (kernel/failure-value request (ex-info "not recorded" {}) record))
        "an unrecorded failure is still the evaluation's value")))

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
           (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) (ex-data failure))
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
       (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) data)
           "and it arrives as OUR flat error kind, not as malli's — so
            when this throw escapes a proc, the fault path classifies it
            from the cause chain like any other refusal")
       (is (= 'seon.error/value
              (:seon.error/operation data))
           "naming the function whose contract was violated")
       (is (= :seon.fn.arity/input
              (:seon.error/member data)))
       (is (= :seon.error/fact
              (:seon.error/expected data))
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
         projection caps original)
        failure (try (wrapped "wrong")
                     (catch Exception thrown thrown))]
    (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) (ex-data failure)))
    (is (= 'my.agents.contract/value
           (get-in (ex-data failure)
                   [:seon.error/operation])))
    (let [recorded (instrument/wrap-interpreted
                    'my.agents.contract/value
                    "[:=> [:cat [:fn clojure.core/int?]] :int]"
                    projection caps original)]
      (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error)
           (test-support/refusal-data #(recorded "wrong")))
          ":record arms without a recorder, and its refusal throws as :panic's does"))))

(deftest sci-installed-contracts-enforce-declared-schemas-and-refusals-in-both-dials
  (test-support/with-database
   (fn [connection]
     (doseq [mode [:panic :record]]
       (test-support/seed-cluster! connection "sci-wrapper"
                                   {})
       (let [database (db/db connection)
             projection (schema/projection-from-database database)
             ctx (sci.eval/base-ctx database)
             _ (sci.eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db database})
             domain {:seon.error/at #inst "2026-09-18T00:00:00Z"
                     :seon.error/layer ::sci-body
                     :seon.error/operation 'user/sci-declared-schema
                     :seon.agent/error-agent-id "sci-agent"}
             contract [:function [:=> [:cat :map] [:map [::success :boolean]]]
                       [:=> [:cat :map :int] [:or :int :seon.agent/error]]]]
         (sci/eval-string* ctx "(def calls (atom 0))")
         (doseq [[label args declared-schema ran?]
                 [[:input [42] :seon.instrument/contract-error false]
                  [:arity [] :seon.instrument/arity-error false]
                  [:undeclared [domain] :seon.instrument/contract-error true]
                  [:declared [domain 1] nil true]]]
           (let [name (symbol (str "sci-declared-schema-" (clojure.core/name mode) "-" (clojure.core/name label)))
                 qualified (symbol "user" (str name))
                 source (str "(defn ^{:malli/schema " (pr-str contract) "} " name
                             " [value & extras] (swap! calls inc) value)")
                 row (test-support/program-fn-row database qualified source)
                 _ (sci/eval-string* ctx source)
                 _ (#'sci.eval/install-function-contract! ctx row projection database)
                 before (sci/eval-string* ctx "@calls")
                 [result] (kernel/with-arm
                           ctx (* 1000 test-support/event-backstop-seconds)
                           (fn [_]
                             (let [invoke #(sci/eval-form
                                            ctx (cons name (map (fn [value] (list 'quote value)) args)))]
                               [(if declared-schema
                                  (test-support/refusal-data invoke) (invoke))])))]
             (is (= (+ before (if ran? 1 0)) (sci/eval-string* ctx "@calls")))
             (if declared-schema
               (do
                 (is ((schema/projection-validator projection declared-schema) result))
                 (is (= qualified (:seon.instrument/fn result)))
                 (is (= (count args) (:seon.instrument/arity result)))
                 (when (= :undeclared label)
                   (is (= :output (:seon.instrument/check result))))
                 (is (empty? (db/q '[:find ?e :in $ ?function
                                     :where [?e :seon.instrument/fn ?function]]
                                   (db/db connection) qualified))
                     "The wrapper stores no fault in either dial."))
               (is (= domain result))))))))))

(deftest a-sovereign-sci-fork-refuses-by-throwing-under-both-dials
  ;; D01 (lost-error seams): an interpreted wrapper no longer needs a fault
  ;; recorder, because it never records or returns its refusal: a fork under
  ;; :record constructs without one and throws exactly as its :panic source does.
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "sci-fork-recorder")
     (test-support/transacted!
      connection [(test-support/revision (db/db connection) [:seon.fn/sym 'seon.id/valid?]
                                         {:seon.schema.admission/source :agent
                                          :seon.fn/source "(defn valid? [length id] true)"})])
     (let [committed (db/pull (db/db connection) '[*] [:seon.fn/sym 'seon.id/valid?])
           _ (is (= "(defn valid? [length id] true)" (:seon.fn/source committed))
                 (pr-str (select-keys committed [:seon.error/operation :seon.error/message])))
           base (sci.eval/base-ctx (db/db connection))
           _ (is (::instrument/interpreted-original
                  (meta @(sci/resolve base 'seon.id/valid?)))
                 "The source context holds a real interpreted wrapper.")
           _ (test-support/apply-config! connection "sci-fork-recorder"
                                         {})
           database (db/db connection)
           projection (schema/projection-from-database database)
           state (sci.eval/projection-state database projection "sci-fork-recorder")
           fork (sci.eval/fork-cluster-ctx base database connection state)
           invoke (fn [ctx]
                    (first (kernel/with-arm
                            ctx (* 1000 test-support/event-backstop-seconds)
                            (fn [_] [(test-support/refusal-data
                                      #(sci/eval-string* ctx "(seon.id/valid?)"))]))))]
       (doseq [ctx [base fork]]
         (is ((schema/projection-validator projection :seon.instrument/arity-error)
              (invoke ctx))))
       (is (empty? (db/q '[:find ?e :in $ ?function
                           :where [?e :seon.instrument/fn ?function]]
                         (db/db connection) 'seon.id/valid?))
           "Neither context's wrapper stores its refusal.")))))

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
                     projection caps identity)]
    (instrument/apply! {:seon.schema/projection projection})
    (doseq [call [#(guarded-result 1) #(interpreted 1)]]
      (let [failure (test-support/refusal-data call)]
        (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) failure))
        (is (= :malli.core/invalid-guard
               (get-in failure [:seon.instrument/malli])))
        (is (str/includes? (:seon.error/message failure) "The guard was evaluated."))))))

(def ^:private reporter-frames (atom nil))

(defn- inside-the-contract-reporter?
  "True when the contract reporter is composing on this thread, right now."
  []
  (boolean
   (some #(str/starts-with? (.getClassName ^StackTraceElement %)
                            "seon.instrument$violation")
         (.getStackTrace (Thread/currentThread)))))

(defn- deadline-crossing-sequence
  "A value whose FIRST element refuses the contract and whose tail enters the
  armed guard when it is realized.

  This is the real shape, not a stand-in: `m/validate` stops at the first
  invalid element, so the wrapper refuses without touching the tail, and
  `seon.instrument/violation` then explains the same value — walking every
  element, realizing the tail, and entering the interrupt-fn exactly where SCI
  enters it for interpreted work (`reference-code/sci/doc/interrupt.md`). The
  frame check records WHERE the bound fired, so this regression cannot pass by
  interrupting somewhere else."
  [interrupt-fn]
  (lazy-seq
   (cons "not an int"
         (lazy-seq
          (reset! reporter-frames (inside-the-contract-reporter?))
          (interrupt-fn)
          nil))))

(defn ^{:malli/schema [:=> [:cat [:sequential :int]] :int]}
  deadline-probe
  [_values]
  1)

(deftest a-deadline-firing-inside-an-instrumented-call-is-reported-as-the-bound
  ;; A BOUND FIRING IS ITS OWN REPORT (AGENTS.md 2.3). `violation` used to
  ;; convert ANY throwable into `minimal-violation`'s contract sentence, so a
  ;; 2000 ms evaluation deadline closing around a 10 ms refusal was read as
  ;; "Wrong number of args (0) passed to: seon.db/as-of" — the kernel keeps a
  ;; throwable's own interrupt evidence (`src/seon/sci/kernel.clj:486`), and
  ;; the contract reporter had just given the interrupt one. Measured in
  ;; `docs/prds/steward-platform/research/sci-arity-message-parity-2026-09-17.md`.
  (instrumented!
   (fn [_]
     (reset! reporter-frames nil)
     (let [options (kernel/context-options)
           ctx (assoc (sci/init {:interrupt-fn (:interrupt-fn options)})
                      :seon.sci.kernel/guard (:seon.sci.kernel/guard options))
           armed (kernel/arm ctx 1)
           record (:seon.sci.kernel/record armed)]
       (try
         ;; the declared bound's own latch closes; nothing here tunes it
         (Thread/sleep 25)
         (let [thrown (try (deadline-probe
                            (deadline-crossing-sequence (:interrupt-fn options)))
                           (catch Throwable throwable throwable))
               outcome (kernel/failure-value
                        {:seon.sci.kernel/time-limit-kind :seon.sci.eval/time-limit
                         :seon.sci.kernel/failure-kind :seon.sci.eval/evaluation-failed}
                        thrown
                        (record (if (kernel/interrupted? thrown) :time :error)))]
           (is (true? @reporter-frames)
               "the bound fired while the contract reporter was composing")
           (is (kernel/interrupted? thrown)
               "the contract reporter re-raises the bound's own interrupt")
           (is (not ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) (ex-data thrown)))
               "a bound firing is never reported as a contract violation")
           (is ((schema/projection-validator (schema/handed-projection) :seon.sci.kernel/error) outcome))
           (is (some? (:seon.sci.kernel/guard-observation outcome))
               "the evaluation boundary names the fired deadline guard's observation"))
         (finally ((:seon.sci.kernel/stop! armed))))))))

(deftest a-sci-only-arity-miss-names-its-program-graph-arglists
  (test-support/with-database
   (fn [connection]
     (let [function-symbol 'seon.instrument-test/agent-largest
           registration (test-support/transacted!
              connection
              [(test-support/program-fn-row
                (db/db connection) function-symbol
                "(defn agent-largest [rows] rows)")])
           projection (schema/build-projection (schema/snapshot))
           wrapped (instrument/wrap-interpreted
                    function-symbol
                    "[:=> [:cat [:vector :map]] :map]"
                    projection ;; the declared admission caps, like every production
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
         (is (str/includes? (error/render-ai diagnostic) "0")))))))

(deftest a-violation-carries-bounded-arguments-only-when-it-can
  (let [caps (assoc (config/result-caps (test-support/effective-config))
                    :seon.config.eval.result/max-depth 4
                    :seon.config.eval.result/max-collection 4
                    :seon.config.eval.result/max-string 32
                    :seon.config.eval.result/max-nodes 64)]
    (doseq [policy [{:seon.sci.admit/caps caps} {}]]
      (instrument/apply!
       (merge {:seon.schema/projection (schema/handed-projection)
               :seon.instrument/changed-identities #{[:seon.fn/sym 'seon.error/value]}}
              policy))
      (let [data (try (error/value "not a fact")
                      (catch Exception thrown (ex-data thrown)))]
        (is (= ["not a fact"] (:seon.error/offending data)))
        (is (= :seon.error/fact (:seon.error/expected data)))
        (is (nil? (::instrument/args (:seon.error/data data)))
            "semantic evidence is not duplicated as serialized arguments")))))

(deftest arglist-lookup-failures-retain-their-cause
  (let [lookup (#'instrument/diagnostic-arglists 'unqualified)]
    (is (= :failed (:seon.instrument.lookup/status lookup)))
    (is (seq (:seon.instrument.lookup/cause lookup)))))

(deftest an-error-shaped-argument-does-not-bypass-the-declared-input
  (let [observed {:seon.error/at #inst "2026-09-19T00:00:00Z"
                  :seon.error/layer :seon.db/custody
                  :seon.error/operation 'seon.db/db}
        _ (arm-prefix-contract!)
        refusal (test-support/refusal-data #(prefix-contract observed))]
    (is ((schema/projection-validator (schema/handed-projection)
                                     :seon.instrument/contract-error) refusal))
    (is (= :input (:seon.instrument/check refusal)))
    (is (= [observed]
           (get-in refusal [:seon.error/offending])))))

(deftest contract-problems-are-semantic-once-never-a-serialized-print-tree
  (let [caps (assoc (config/result-caps (test-support/effective-config))
                    :seon.config.eval.result/max-depth 8
                    :seon.config.eval.result/max-collection 32
                    :seon.config.eval.result/max-string 4096
                    :seon.config.eval.result/max-nodes 1024)]
    (try
      (instrument/apply! {:seon.sci.admit/caps caps
                          :seon.instrument/changed-identities
                          #{[:seon.fn/sym 'seon.instrument-test/declared-map-input]}
                          :seon.schema/projection
                          (schema/projection-with-function-contract
                           (schema/handed-projection) 'seon.instrument-test/declared-map-input
                           (:malli/schema (meta #'declared-map-input))
                           {:seon.schema.admission/source :core})})
      (let [failure
            (try
              (declared-map-input
               {:seon.instrument-test/expected "not an int"})
              (catch Exception thrown thrown))
            data (ex-data failure)
            message (:seon.error/message data)
            problems (get-in data
                             [:seon.error/problems])]
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
      (instrument/apply! {:seon.sci.admit/caps caps})
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
              instrument-data data]
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

(deftest refusal-value-projection-obeys-the-profile-in-both-faces-and-the-whole-value-stays-retrievable
  ;; THE OFFENDING VALUE IS THE FAILING LEAF, not the container it sat in
  ;; (error-entities PRD 2026-09-17, "Offending value": the fault retains the
  ;; independently bounded projection of its own LEAF; AGENTS.md 2.4, a
  ;; refusal names "the offending value"). `940f4b426` moved the wording and
  ;; the leaf together, so the member schema and the member value are what a
  ;; collection violation now reports. The whole checked value is not lost:
  ;; it stays beside the leaf as the diagnostic's own offending value.
  ;;
  ;; The leaf is therefore what both faces are measured on. AI agents and the
  ;; browser share one selection, node tree and cut retrieval; only the final
  ;; face differs (CLAUDE.md, Presentation; root ruling 2026-09-27). The
  ;; original value stays whole and retrievable from its reference; neither
  ;; face prints it whole unless asked.
  (let [member (vec (range 100 140))
        raw (concat (range 8) [member])
        wrapped (instrument/wrap-interpreted
                 'my.agents.audit/vector-input "[:=> [:cat [:vector :int]] :int]"
                 (schema/handed-projection) (config/result-caps (test-support/effective-config)) (constantly 1))
        refusal (try (wrapped raw) (catch Exception failure (ex-data failure)))
        offending (get-in refusal [:seon.error/problems 0 :seon.error/offending])
        checked (get-in refusal [:seon.error/offending])
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
    (is (identical? member offending)
        "the refusal names the failing member, and retains the actual object")
    (is (identical? raw (first checked))
        "the whole checked argument survives beside the leaf, unprojected")
    (is (str/includes? ai "expected a collection member satisfying an integer") ai)
    (is (str/includes? ai "100") ai)
    (is (not (str/includes? ai "139"))
        "the AI projection stops at the profile's child bound")
    ;; One cut face (one-render-path §3e): the shown members, then `…N`
    ;; counting what was omitted of the 40 that were there.
    ;; The layout may break the line before the cut; the cut and its
    ;; continuation are the same tokens either way.
    (is (str/includes? (str/replace ai #"\s+" " ")
                       "[100 101 102 …37 (drop 3 (get-in result/eaudit [:seon.error/problems 0 :seon.error/offending]))]")
        ai)
    (is (str/includes? ai "(get-in result/eaudit [:seon.error/problems 0 :seon.error/offending])")
        "and it hands back the form that requeries the leaf it elided")
    (testing "the HTML face is the same selection with the same cut"
      (is (every? #(str/includes? html (str %)) [100 101 102]) html)
      (is (not (str/includes? html "139")) "HTML stops at the same child bound")
      (is (str/includes? html "seon-print-elision") "the cut is marked in HTML")
      (is (str/includes? html "…37 (drop 3 (get-in result/eaudit [:seon.error/problems 0 :seon.error/offending]))")
          "and carries the same continuation that retrieves the rest"))
    (testing "the whole value stays retrievable from the stored reference"
      (is (= member (print/value-at refusal [:seon.error/problems 0 :seon.error/offending])))
      (is (= (drop 3 member)
             (drop 3 (get-in refusal [:seon.error/problems 0 :seon.error/offending])))
          "the continuation form, evaluated over the held value, is exactly the omitted rest"))))

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
      (instrument/apply! {:seon.sci.admit/caps caps})
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
            instrument-data data
            received (first (:seon.error/offending instrument-data))]
        (is (identical? registry received)
            "the seam retains the actual object; presentation alone elides it")
        (is (< allocated allocation-ceiling)
            (str "construction allocated " allocated
                 " bytes; the issue baseline was 150,063,304"))
        (is (= 1 (:seon.instrument/problem-count instrument-data)))
        (is (= :int (:seon.error/expected instrument-data)))
        (is (identical? registry
                        (get-in instrument-data [:seon.error/problems 0 :seon.error/offending]))))
      (finally
        (instrument/remove!)))))

;;; ---------------------------------------------------------------------------
;;; Idempotence, and the measured hot-reload strip
;;; ---------------------------------------------------------------------------

(deftest acquisition-carries-one-effective-policy-to-every-wrapper
  (test-support/preserving-instrumentation-state
   (fn []
     (let [names [(gensym "policy-first-") (gensym "policy-second-")]
           candidates (mapv #(intern 'seon.instrument-test % identity) names)
           symbols (mapv #(symbol "seon.instrument-test" (str %)) names)
           identities (set (map #(vector :seon.fn/sym %) symbols))
           projection (reduce #(schema/projection-with-function-contract
                                %1 %2 [:=> [:cat :int] :int]
                                {:seon.schema.admission/source :core})
                              (schema/handed-projection) symbols)
           defaults config/defaults]
       (try
         (doseq [candidate candidates]
           (alter-meta! candidate assoc :malli/schema [:=> [:cat :int] :int]))
           (instrument/apply! {:seon.schema/projection projection
                               :seon.instrument/changed-identities identities})
           (let [roots (mapv deref candidates)
                 policy (:seon.instrument/policy (meta (first roots)))
                 request (assoc policy :seon.schema/projection projection
                                       :seon.instrument/changed-identities identities)]
             (is (= (:seon.config.error/max-evidence-bytes defaults)
                    (:seon.config.error/max-evidence-bytes policy)))
             (is (every? #(= policy (:seon.instrument/policy (meta %))) roots))
             (doseq [candidate candidates]
               (is (= 7 (candidate 7)))
               (is (thrown? Exception (candidate "not an integer"))))
             (instrument/apply! (assoc request :seon.instrument/changed-identities #{}))
             (is (every? true? (map identical? roots (map deref candidates)))
                 "an empty adoption preserves wrappers")
             (doseq [changed [(update request :seon.config.error/max-evidence-bytes inc)
                              (update-in request [:seon.sci.admit/caps
                                                  :seon.config.eval.result/max-string] inc)]]
               (instrument/apply! request)
               (let [before (mapv deref candidates)]
                 (instrument/apply! changed)
                 (is (every? false? (map identical? before (map deref candidates)))
                   "changed captured policy cannot reuse the previous closure")
               (doseq [candidate candidates]
                 (is (= (dissoc changed :seon.schema/projection :seon.instrument/changed-identities)
                        (:seon.instrument/policy (meta @candidate))))))))
           (let [effective defaults
                 caps (config/result-caps effective)
                 arm-request (select-keys effective [:seon.config.error/max-evidence-bytes])]
             (doseq [function-symbol ['my.agents.policy/first 'my.agents.policy/second]]
               (let [wrapped (instrument/wrap-interpreted
                              function-symbol "[:=> [:cat :int] :int]"
                              projection caps identity arm-request)]
                 (is (= 9 (wrapped 9)))
                 (is (thrown? Exception (wrapped "not an integer")))))
             (let [wrapped (instrument/wrap-interpreted
                            'my.agents.policy/standalone "[:=> [:cat :int] :int]"
                            projection caps identity)]
               (is (= 9 (wrapped 9)))
               (is (thrown? Exception (wrapped "not an integer")))))
         (finally
           (doseq [name names] (ns-unmap 'seon.instrument-test name))))))))

(deftest applying-twice-is-applying-once
  (instrumented!
   (fn [first-result]
     (let [second-result (instrument/apply! {})]
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
         (instrument/apply! {:seon.schema/projection projection})
         (let [before @candidate]
           (is (thrown? Exception (candidate {::value "new input"})))
           (alter-meta! candidate assoc :malli/schema [:=> [:cat new-key] :map])
           (instrument/apply! {:seon.schema/projection projection})
           (is (not (identical? before @candidate)))
           (is (identical? (mi/-f->original before) (mi/-f->original @candidate))
               "Only the contract changed; the loaded function was not replaced.")
           (is (= {::value "new input"} (candidate {::value "new input"})))
           (let [failure (try (candidate {::value false})
                              (catch Exception refusal refusal))]
             (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) (ex-data failure)))
             (is (str/includes? (ex-message failure) (str new-key))))
           (let [current @candidate]
             (instrument/apply! {:seon.schema/projection projection})
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
               #(instrument/apply! {:seon.schema/projection projection})))]
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
        (is (not= (:seon.instrument/definitions (meta before))
                  (:seon.instrument/definitions (meta @candidate))))
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

(defn- with-disposable-namespace
  "Run `body` with a fresh namespace of its own, removed afterwards, so a
  test's Vars never enter a namespace another test or reload owns."
  [body]
  (let [namespace-name (symbol (str "seon.instrument-test.scratch-" (random-uuid)))]
    (create-ns namespace-name)
    (try (body namespace-name)
         (finally (remove-ns namespace-name)))))

(deftest one-wrapper-per-adoption-keyed-by-content-never-by-call
  ;; Two equal-content projection objects arm one wrapper; a call carrying
  ;; another projection never re-selects or recompiles it; only adopting
  ;; changed content rearms.
  (with-disposable-namespace
   (fn [namespace-name]
     (let [request-key :seon.instrument-test/content-keyed-request
           forms (assoc (schema/snapshot) request-key [:map [::value :int]])
           first-projection (schema/build-projection forms)
           equal-projection (schema/build-projection forms)
           changed-projection (schema/build-projection
                               (assoc forms request-key [:map [::value :string]]))
           candidate (intern namespace-name 'content-keyed identity)
           arm! #(instrument/apply! {:seon.schema/projection %})]
       (alter-meta! candidate assoc :malli/schema [:=> [:cat request-key] :map])
       ;; equal inputs answer the one built projection (39960bb15, seon.cache
       ;; since 92b22cbf1), so equal content is one wrapper by construction
       (is (identical? first-projection equal-projection))
       (arm! first-projection)
       (let [armed @candidate]
         (arm! equal-projection)
         (is (identical? armed @candidate) "equal content keeps the one wrapper")
         (is (= {::value 1}
                (schema/call-with-projection
                 changed-projection
                 #(candidate (with-meta {::value 1}
                               {:seon.schema/projection changed-projection}))))
             "a call's own projection does not re-select the armed contract")
         (is (thrown? clojure.lang.ExceptionInfo (candidate {::value "one"})))
         (is (identical? armed @candidate))
         (arm! changed-projection)
         (is (not (identical? armed @candidate)) "changed content rearms")
         (is (= {::value "one"} (candidate {::value "one"})))
         (is (thrown? clojure.lang.ExceptionInfo (candidate {::value 1}))))))))

(deftest cold-arming-keeps-acquired-predicates-without-an-ambient-binding
  ;; `apply!` arms from the supplied projection's retained contract, so the
  ;; installed wrapper validates with the predicates that projection acquired
  ;; whether or not a projection is bound around arming or the call.
  (with-disposable-namespace
   (fn [namespace-name]
     (let [contract [:=> [:cat :seon.agent/id] :seon.agent/id]
           candidate (intern namespace-name (with-meta 'cold-contract {:malli/schema contract})
                             identity)
           function-symbol (symbol candidate)
           admitted (schema/handed-projection)
           projection (update admitted :seon.schema.projection/registry
                              #(mr/composite-registry
                                {function-symbol (m/schema contract {:registry %})} %))
           arm (fn []
                 (let [applied (instrument/apply!
                                {:seon.schema/projection projection
                                 :seon.instrument/changed-identities #{[:seon.fn/sym function-symbol]}})]
                   {:seon.instrument/registered (:seon.instrument/registered applied)
                    :seon.instrument/definitions
                    (:seon.instrument/definitions (meta @candidate))
                    :seon.instrument-test/value (candidate "juniper")}))]
       (is (map? (:seon.schema.projection/predicate-functions projection)))
       (let [bound (schema/call-with-projection projection arm)
             cold (with-bindings {#'schema/*projection* nil
                                  #'schema/*projection-state* nil}
                    (arm))]
         (is (= 1 (:seon.instrument/registered cold)))
         (is (= "juniper" (:seon.instrument-test/value cold)))
         (is (seq (:seon.instrument/definitions cold)))
         (is (= bound cold))
         (is (contains? (instrument/instrumented) candidate))
         (is (thrown? Exception
                      (with-bindings {#'schema/*projection* nil
                                      #'schema/*projection-state* nil}
                        (candidate 42)))))))))

(deftest prefix-related-sibling-vars-keep-their-own-contracts
  (instrumented!
   (fn [_]
     (let [database-form
           [:symbol {:seon.db/identity true}]]
       (is (= database-form
              (m/form (schema.datahike/storage-schema (schema/structural-schema database-form))))
           "compiled storage navigation remains callable under instrumentation")
       (is (= 7 (prefix-contract 7)))
       (is (= "left-right" (prefix-contract-in "left-" "right")))
       (doseq [[expected-function invoke]
               [['seon.instrument-test/prefix-contract
                 #(apply prefix-contract ["left" "right"])]
                ['seon.instrument-test/prefix-contract-in
                 #(apply prefix-contract-in [7])]]]
         (let [failure (try (invoke) (catch Exception thrown thrown))]
           (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/arity-error) (ex-data failure)))
           (is (= expected-function
                  (get-in (ex-data failure)
                          [:seon.error/operation]))
               "each exact qualified Var symbol selects its own contract")))))))

(deftest one-uncompilable-contract-refuses-alone-and-the-rest-arm
  ;; A1-1 (G1): each wrapper is compiled once at arming and installed; a
  ;; contract that cannot compile keeps its Var's previous root and comes
  ;; back as one registration error, while every other Var in the batch arms.
  (let [names ['arming-broken 'arming-healthy]
        [broken healthy] (mapv #(intern 'seon.instrument-test % identity) names)
        [broken-sym healthy-sym] (mapv #(symbol "seon.instrument-test" (str %)) names)
        unresolvable [:=> [:cat [:ref :seon.instrument-test/absent]] :int]
        contract [:=> [:cat :int] :int]
        admitted (schema/projection-with-function-contract
                  (schema/handed-projection) healthy-sym contract
                  {:seon.schema.admission/source :core})
        registry (:seon.schema.projection/registry admitted)
        projection (assoc admitted :seon.schema.projection/registry
                          (mr/composite-registry {broken-sym unresolvable} registry))
        broken-root @broken]
    (try
      (alter-meta! broken assoc :malli/schema unresolvable)
      (alter-meta! healthy assoc :malli/schema contract)
      (let [refusal (instrument/apply!
                     {:seon.schema/projection projection
                      :seon.instrument/changed-identities
                      #{[:seon.fn/sym broken-sym] [:seon.fn/sym healthy-sym]}})]
        (is (= broken-sym (:seon.error/member refusal) (:seon.instrument/fn refusal)))
        (is (= unresolvable (:seon.error/expected refusal)))
        (is (= :seon.instrument-test/absent (:seon.error/offending refusal)))
        (is (seq (:seon.error/chain refusal)) "the compile failure's whole chain survives")
        (is (m/validate :seon.instrument/registration-error refusal {:registry registry}))
        (is (identical? broken-root @broken) "the refused Var keeps its previous root")
        (is (contains? (instrument/instrumented) healthy) "the rest of the batch arms")
        (is (thrown? clojure.lang.ExceptionInfo (healthy "not an int"))))
      (finally (doseq [name names] (ns-unmap 'seon.instrument-test name))))))

(defn- masked-registry
  "`projection` whose registry answers `replacement` for `schema-key` (nil:
  absent), without the compiled holder that would answer from the old one."
  [projection schema-key replacement]
  (let [registry (:seon.schema.projection/registry projection)]
    (-> projection
        (dissoc :seon.schema.projection/compiled)
        (assoc :seon.schema.projection/registry
               (reify mr/Registry
                 (-schema [_ candidate]
                   (if (= schema-key candidate) replacement (mr/-schema registry candidate)))
                 (-schemas [_]
                   (let [schemas (mr/-schemas registry)]
                     (if replacement (assoc schemas schema-key replacement)
                         (dissoc schemas schema-key)))))))))

(deftest a-refusal-that-cannot-be-built-is-a-minimal-declared-error
  ;; A1-8b (G2-G4). Each case reaches the wrapper's minimal base: a validator
  ;; that throws at the call (Malli's ::invalid-schema-at-call), an absent
  ;; refusal schema, a refusal its schema rejects, and a construction that
  ;; throws. The base keeps the report's expected schema and offending value;
  ;; the body's own exception passes through untouched. The wrapper is the
  ;; object `apply!` installs, built without arming any Var.
  (let [admitted (schema/handed-projection)
        registry (:seon.schema.projection/registry admitted)
        base? (schema/projection-validator admitted :seon.error/base)
        required (into #{} (keep (fn [[k properties _]] (when-not (:optional properties) k)))
                       (m/children (m/deref-all (m/schema :seon.error/base {:registry registry}))))
        lazy {:registry (assoc (m/default-schemas) :lazy-ref (m/-ref-schema {:lazy true}))}
        absent [:schema [:lazy-ref ::absent]]
        caps (config/result-caps config/defaults)
        wrapper (fn [projection function-symbol form body]
                  (#'instrument/compiled-wrapper
                   (update projection :seon.schema.projection/registry
                           #(mr/composite-registry {function-symbol (m/schema form lazy)} %))
                   function-symbol form body caps {}))
        refusal (fn [call] (try (call) nil (catch clojure.lang.ExceptionInfo thrown (ex-data thrown))))
        unhashable (reify Object
                     (hashCode [_] (throw (ex-info "unhashable" {})))
                     (toString [_] "unhashable"))]
    (doseq [[label projection form argument member expected offending cause]
            [[:input admitted [:=> [:cat absent] :int] 1
              :malli.core/invalid-schema-at-call [:cat [:schema [:ref ::absent]]] [1] ":malli.core/invalid-ref"]
             [:output admitted [:=> [:cat :int] absent] 2
              :malli.core/invalid-schema-at-call [:schema [:ref ::absent]] 2 ":malli.core/invalid-ref"]
             [:guard admitted [:=> [:cat :int] :int absent] 3
              :malli.core/invalid-schema-at-call [:schema [:ref ::absent]] [[3] 3] ":malli.core/invalid-ref"]
             [:missing-refusal-schema (masked-registry admitted :seon.instrument/refusal-result nil)
              [:=> [:cat :int] :int] "x" :malli.core/invalid-input [:cat :int] ["x"]
              "Missing schema declaration :seon.instrument/refusal-result."]
             [:rejected-refusal (masked-registry admitted :seon.instrument/refusal-result (m/schema :nil))
              [:=> [:cat :int] :int] "x" :malli.core/invalid-input [:cat :int] ["x"]
              "Instrumentation constructed an invalid refusal."]
             [:construction-throws admitted [:=> [:cat :int] :int] unhashable
              :malli.core/invalid-input [:cat :int] [unhashable] "unhashable"]]]
      (testing label
        (let [function-symbol (symbol "seon.instrument-test" (str "minimal-" (name label)))
              called (atom 0)
              value (refusal #((wrapper projection function-symbol form
                                        (fn [x] (swap! called inc) x))
                               argument))]
          (is (= member (:seon.error/member value)) "the report key names who is wrong")
          (is (= function-symbol (:seon.error/operation value)) "the error names the call")
          (is (= expected (:seon.error/expected value)) "the report's schema survives")
          (is (= offending (:seon.error/offending value)) "the checked value survives")
          (is (= cause (:seon.error/message (peek (:seon.error/chain value))))
              "the chain carries the whole cause")
          (is (every? #(some? (find value %)) required))
          (is (base? value))
          (is (= (if (#{:output :guard} label) 1 0) @called) "a refused input never runs the body"))))
    (testing "the body's own exception is rethrown unchanged"
      (let [boom (ex-info "boom" {::body true})]
        (is (identical? boom
                        (try ((wrapper admitted 'seon.instrument-test/minimal-body [:=> [:cat :int] :int]
                                       (fn [_] (throw boom))) 4)
                             (catch Throwable thrown thrown))))))
    (is (= ::absent (get-in (peek (:seon.error/chain
                                   (refusal #((wrapper admitted 'seon.instrument-test/minimal-ref
                                                       [:=> [:cat absent] :int] identity) 1))))
                            [:seon.error/data :data :ref]))
        "the chain names the unresolvable schema")))

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
     (instrument/apply! {})
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
                 {})
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
               {})))))]
    (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/registration-error) result))
    (is (false? @collected?)
        "a missing projection must refuse before Malli collects contracts")))

;;; ---------------------------------------------------------------------------
;;; The dial
;;; ---------------------------------------------------------------------------

(deftest cluster-policy-and-removal-preserve-the-jvm-wrappers
  (instrument/apply! {})
  (let [before (into {} (map (juxt identity deref)) (instrument/instrumented))]
    (is (pos? (count before)))
    (is (= (count before) (instrument/remove!)))
    (is (= (count before) (instrument/remove!)))
    (is (every? (fn [[candidate root]] (identical? root @candidate)) before))
    (is (thrown? Exception (error/value "not a fact")))))

;;; ---------------------------------------------------------------------------
;;; The selection is computed
;;; ---------------------------------------------------------------------------

(deftest the-selection-is-declared-vars-with-schemas-and-nothing-else
  ;; Exercise boot's arming owner, `apply!` without changed identities
  ;; (`collect-contracts!` over every loaded namespace), against the executing
  ;; projection. Removing this one wrapper makes selection observable even
  ;; though this namespace was armed before the test ran.
  (let [projection (schema/handed-projection)
        boundary #'private-integer-boundary]
    (alter-var-root boundary mi/-f->original)
    (is (not (contains? (instrument/instrumented) boundary)))
    (let [measurements
          (mapv
           (fn [phase]
             (let [started (System/nanoTime)
                   result (instrument/apply!
                           {:seon.schema/projection projection})]
               {:seon.instrument-test/phase phase
                :seon.instrument-test/elapsed-ms
                (/ (double (- (System/nanoTime) started)) 1000000.0)
                :seon.instrument-test/applied result}))
           [:rearm-private :unchanged])
          wrapped (instrument/instrumented)
          original-error {:seon.error/at (java.util.Date.)
                          :seon.error/layer :seon.instrument-test/reader
                          :seon.error/operation 'seon.instrument-test/read
                          :seon.error/message "The read was refused."}]
      (prn {:seon.instrument-test/arming measurements})
      (is (every? #(pos? (get-in % [:seon.instrument-test/applied
                                    :seon.instrument/instrumented])) measurements))
      (is (= (mapv :seon.instrument-test/applied measurements)
             (vec (repeat 2 (:seon.instrument-test/applied (first measurements))))))
      (is (set/subset? (instrument/armable ['seon.instrument-test]) wrapped)
          "every declared contract of the namespace is armed")
      (is (true? (:private (meta boundary))))
      (is (contains? wrapped #'error/value) "a public var with a schema")
      (is (contains? wrapped boundary)
          "boot's arming includes a private declared contract")
      (is (every? #(some? (mi/-schema %)) wrapped))
      (is (= 7 (private-integer-boundary 7)))
      (doseq [[kind call]
              [[:input #(private-integer-boundary "not an integer")]
               [:output #(private-integer-boundary 0)]]]
        (let [refusal (test-support/refusal-data call)]
          (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) refusal))
          (is (= kind (get-in refusal [:seon.instrument/arm])))
          (is (= 'seon.instrument-test/private-integer-boundary
                 (get-in refusal [:seon.error/operation])))))
      (let [refusal (test-support/refusal-data #(private-integer-boundary original-error))]
        (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) refusal))
        (is (= original-error
               (get-in refusal [:seon.error/problems 0 :seon.error/offending]))
            "The consumer refuses its input and retains the causal value.")))))

(deftest stored-error-pull-remains-data-through-an-armed-function
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "stored-error-data")
     (let [caps (config/result-caps (test-support/effective-config))
           source {:seon.error/at #inst "2026-09-22T00:00:00Z"
                   :seon.error/layer ::stored-error
                   :seon.error/operation 'seon.instrument-test/stored-error
                   :seon.error/message "Stored error data"
                   :seon.agent/error-agent-id "stored-error-agent"}
           [error outcome] (fault/record!
                            connection "stored-error-data" "stored-error-test" caps
                            {:seon.error/source source :seon.error/declared-schema :seon.agent/error})
           database (db/db connection)
           occurrence (db/q '[:find ?occurrence . :in $ ?signature
                              :where [?error :seon.error/signature ?signature]
                                     [?error :seon.error/occurrences ?occurrence]]
                            database (:seon.error/signature error))]
       (is (= :seon.flow/committed outcome) (pr-str outcome))
       (is (pos-int? occurrence))
       (is (contains? (instrument/instrumented) #'db/pull))
       (let [pulled (db/pull database '[*] occurrence)]
         (is (= occurrence (:db/id pulled)))
         (is (= (:seon.error/operation source) (:seon.error/operation pulled)))
         (is ((schema/projection-validator (schema/handed-projection) :seon.error/base)
              pulled)))))))

(deftest parsed-shown-error-entities-remain-data
  (let [value {:seon.error/at #inst "2026-09-22T00:00:00Z"
               :seon.error/layer ::shown
               :seon.error/operation 'seon.instrument-test/shown
               :seon.agent/error-agent-id "shown-agent"}]
    (is (contains? (instrument/instrumented) #'repl/shown-value))
    (is (= value (repl/shown-value (pr-str value))))))

(deftest semantic-admission-explicitly-declares-its-error-contract
  (doseq [[candidate expected]
          [[#'admit/semantic-value :seon.schema/value]
           [#'error/refusal [:or :nil :map :seon.error/base]]
           [#'error/latest-fact [:or :map :seon.error/base]]
           ;; 36a3714b4 split the kernel's error schemas
           [#'kernel/failure-value [:or :seon.sci.kernel/execution-error :seon.sci.kernel/error]]]]
    (is (= expected (last (:malli/schema (meta candidate)))) (str candidate))))

(deftest the-work-launcher-api-is-collected-without-an-allowlist
  (instrumented!
   (fn [_]
     (doseq [boundary [#'flow/start-work-launcher!
                       #'flow/stop-work-launcher!
                       #'flow/submit!]]
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
       (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) (ex-data failure)))))))

(deftest restoring-instrumentation-state-never-reinstalls-a-replaced-definition
  ;; THE CLASS (gate batch 88, worker pool-1, run tmp/test-runs/run.j9rx0e):
  ;; a fixture captured this JVM's armed roots, its body adopted a published
  ;; program — `src/seon/cluster.clj:2247` reloads every namespace with source
  ;; in the HOSTING JVM — and the restore put the pre-reload closures back over
  ;; post-reload protocols and types. `seon.print/text-sink` then returned a
  ;; superseded `seon.print.TextSink` that the reloaded `seon.print/sink?`
  ;; refused, so its own armed output contract refused six later calls across
  ;; three namespaces in that worker. A captured root is restorable only while
  ;; the definition under it is still the one it was compiled against.
  (instrumented!
   (fn [_]
     (let [namespace-name 'r7.reload.probe
           ;; No file on any classpath, so nothing can load this namespace again.
           load! (fn []
                   (binding [*ns* (create-ns namespace-name)]
                     (clojure.core/refer-clojure)
                     (eval '(do (defprotocol ProbeSink (-emit [sink]))
                                (deftype ProbeText [] ProbeSink (-emit [_] :text))
                                (defn make [] (ProbeText.))
                                (defn sink? [value] (satisfies? ProbeSink value))))))
           emits-a-value-its-own-protocol-accepts?
           (fn [] ((ns-resolve namespace-name 'sink?)
                   ((ns-resolve namespace-name 'make))))]
       (try
         (load!)
         (let [probe (ns-resolve namespace-name 'make)]
           ;; Carry the wrapper shape `instrument/instrumented` derives from, so
           ;; the probe enters `state` exactly as an armed Var does.
           (alter-var-root probe
                           (fn [original]
                             (with-meta (fn [& arguments] (apply original arguments))
                               {:seon.instrument/var probe
                                :malli.instrument/original original})))
           (let [state (instrument/state)
                 entering (count (:seon.instrument/roots state))]
             (is (contains? (:seon.instrument/roots state) probe)
                 "an armed Var's root is part of the captured state")
             (load!)
             (is (= #{probe} (instrument/replaced-definitions state))
                 "only the Var whose loaded definition was replaced is named")
             (is (= #{probe} (instrument/restore! state))
                 "the restore reports the Vars it left to the loader")
             (is (emits-a-value-its-own-protocol-accepts?)
                 "the replaced definition is left as the loader left it")
             (is (contains? (instrument/instrumented) #'db/transact!)
                 "every unreplaced root round-trips through the restore")
             (is (= (dec entering) (count (instrument/instrumented)))
                 "and nothing else is armed or unarmed by it")))
         (finally
           (remove-ns namespace-name)))))))

(deftest named-generators-survive-compilation-in-refusal-evidence
  (test-support/with-database
   (fn [_]
     (let [refusal (test-support/refusal-data
                    #(db/database-value-identity :seon.instrument-test/not-a-database))]
       (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) refusal)
           (pr-str refusal))
       (is (= :input (:seon.instrument/check refusal)))
       (is (= 'seon.db/database-value-identity (:seon.instrument/fn refusal)))
       (is (seq (:seon.error/problems refusal)))))))

(deftest a-missing-key-refusal-holds-values-and-names-the-key
  ;; One missing key once came back over MCP as 34,518 bytes: a second
  ;; explanation copied the problems as pre-printed floor-printer node
  ;; strings nested seven levels deep (error-text-opus, 2026-09-26). The
  ;; refusal holds its problems as values and its message names the key.
  (test-support/with-database
   (fn [_]
     (let [projection (schema/handed-projection)
           wrapped (#'instrument/compiled-wrapper
                    projection 'seon.instrument-test/needs-db
                    [:=> [:cat [:map [:seon.db/db :seon.db/database-value]]] :any]
                    (fn [_] :ran) (config/result-caps config/defaults)
                    {:seon.config.error/max-evidence-bytes 8192})
           refusal (test-support/refusal-data #(wrapped {:seon.db/connection :absent}))
           message (:seon.error/message refusal)
           printed (pr-str refusal)]
       (is ((schema/projection-validator projection :seon.instrument/contract-error) refusal)
           printed)
       (is (not (find refusal :seon.instrument/explanations)))
       (is (not (str/includes? printed "#:seon.print")) printed)
       (is (= [[:seon.db/db]] (mapv :seon.error/path (:seon.error/problems refusal))))
       (is (str/includes? message "at [:seon.db/db]") message)
       (is (< (count message) 400) message)
       (is (< (count printed) 2000) printed)))))

(deftest the-caller-frame-is-part-of-the-refusal-sentence
  (arm-prefix-contract!)
  (let [refusal (test-support/refusal-data #(prefix-contract "wrong"))
        caller (:seon.instrument/caller refusal)]
    (is (string? caller))
    (is (str/starts-with? caller "seon.instrument-test "))
    (is (str/includes? (:seon.error/message refusal) caller))
    (is (str/includes? (error/render-ai refusal) caller))))

(deftest host-diagnostics-use-the-loaded-vars-arglists
  (test-support/with-database
   (fn [connection]
     (arm-prefix-contract!)
     ;; The stored declaration is replaced exactly: an upsert would keep the
     ;; owned attributes the new definition drops.
     (test-support/transacted!
      connection
      (test-support/replacement-tx
       (db/db connection) '[:seon.fn/sym seon.instrument-test/prefix-contract]
       "(defn prefix-contract [stale-name] stale-name)" {}))
     (let [environment (env/environment
                        {:seon.boot/cluster-name "instrument-test"
                         :seon.db/connection connection})
           refusal (binding [effect/*request-context*
                             {:seon.env/environment environment}]
                     (test-support/refusal-data #(apply prefix-contract [])))]
       (is (= '([value])
              (:seon.instrument/arglists refusal)))
       (is (not (str/includes? (error/render-ai refusal) "stale-name")))))))

(deftest output-validation-follows-the-declared-union
  (let [projection (schema/handed-projection)
        caps (config/result-caps (test-support/effective-config))
        wrap (fn [output]
               (instrument/wrap-interpreted
                'seon.instrument-test/precise-error-output
                (pr-str [:=> [:cat :map] output])
                projection caps identity))
        data (wrap :map)
        declared (wrap [:or [:map [::success :boolean]] :seon.agent/error])
        base {:seon.error/at #inst "2026-09-19T00:00:00Z"
              :seon.error/layer ::body
              :seon.error/operation 'seon.instrument-test/precise-error-output}
        complete (assoc base :seon.agent/error-agent-id "observed")
        refusal (test-support/refusal-data #(declared base))]
    (is (= base (data base)))
    (is (= complete (data complete)))
    (is (= complete (declared complete)))
    (is (= {::success true} (declared {::success true})))
    (is (= :output (:seon.instrument/check refusal)))
    (is ((schema/projection-validator projection :seon.instrument/contract-error) refusal))))

(deftest every-instrument-function-declares-its-input-and-output
  (doseq [[name candidate] (ns-interns 'seon.instrument)
          :when (and (bound? candidate) (fn? @candidate))]
    (is (some? (mi/-schema candidate)) (str name))))

(deftest an-invalid-refusal-retains-its-evidence-at-the-kernel-boundary
  (let [canonical (schema/handed-projection)
        ;; A complete canonical population with a deliberately incompatible
        ;; refusal declaration probes drift at the actual compiled wrapper.
        projection (schema/build-projection
                    (assoc (:seon.schema.projection/forms canonical)
                           :seon.instrument/refusal-result :int))
        caps (config/result-caps (test-support/effective-config))
        wrapped (instrument/wrap-interpreted
                 'seon.instrument-test/refusal-drift
                 "[:=> [:cat :int] :int]" projection caps identity)
        thrown (try (wrapped "wrong") (catch Throwable failure failure))
        options (kernel/context-options)
        ctx (assoc (sci/init {:interrupt-fn (:interrupt-fn options)})
                   :seon.sci.kernel/guard (:seon.sci.kernel/guard options))]
    ;; A refusal its schema rejects is the minimal base (A1-8b): the rejection
    ;; is the root of its chain, never the thrown message.
    (is (= (str "seon.instrument-test/refusal-drift :malli.core/invalid-input: the refusal could not be built: "
                "Instrumentation constructed an invalid refusal.")
           (ex-message thrown)))
    (is (= "Instrumentation constructed an invalid refusal."
           (:seon.error/message (peek (:seon.error/chain (ex-data thrown))))))
    (kernel/with-arm
     ctx (* 1000 test-support/event-backstop-seconds)
     (fn [armed]
       (let [outcome (kernel/failure-value
                      {:seon.sci.kernel/time-limit-kind :seon.sci.eval/time-limit
                       :seon.sci.kernel/failure-kind :seon.sci.eval/evaluation-failed}
                      thrown ((:seon.sci.kernel/record armed) :error))
             evidence [:seon.error/member :seon.error/expected :seon.error/offending
                       :seon.error/chain :seon.error/message]]
         (is ((schema/projection-validator canonical :seon.error/base) outcome))
         (is (= (select-keys (ex-data thrown) evidence)
                (select-keys outcome evidence))
             "the kernel boundary keeps the minimal base's whole evidence"))))))

(defn reporting-failure
  "A declared humanizer that fails while the real reporter explains a value."
  {:malli/schema [:=> [:cat :map :map] :string]}
  [_problem _options]
  (throw (ex-info "The contract humanizer failed."
                  {:seon.instrument-test/reporting-evidence "original cause"})))

(deftest a-reporter-failure-is-not-relabelled-as-a-contract-violation
  (let [canonical (schema/handed-projection)
        projection (schema/with-predicate-functions
                    canonical
                    (assoc (schema/predicate-functions-in canonical)
                           'seon.instrument-test/reporting-failure #'reporting-failure))
        wrapped (instrument/wrap-interpreted
                 'seon.instrument-test/reporting-boundary
                 "[:=> [:cat [:fn {:error/fn seon.instrument-test/reporting-failure} clojure.core/int?]] :int]"
                 projection (config/result-caps (test-support/effective-config)) identity)
        failure (try (wrapped "wrong") (catch Throwable thrown thrown))]
    ;; The humanizer's failure is the root of the minimal base's chain, with
    ;; its own evidence; the base is a core fault, not a contract violation.
    (let [root (peek (:seon.error/chain (ex-data failure)))]
      (is (= "The contract humanizer failed." (:seon.error/message root)))
      (is (= "original cause"
             (get-in root [:seon.error/data :seon.instrument-test/reporting-evidence]))))
    (is ((schema/projection-validator (schema/handed-projection) :seon.error/base) (ex-data failure)))
    (is (not ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) (ex-data failure))))))

(deftest adoption-arms-only-the-named-identity
  (let [names ['changed-wrapper 'unchanged-wrapper]
        candidates (mapv #(intern 'seon.instrument-test % identity) names)
        symbols (mapv #(symbol "seon.instrument-test" (str %)) names)
        contract [:=> [:cat :int] :int]
        projection (reduce #(schema/projection-with-function-contract
                             %1 %2 contract {:seon.schema.admission/source :core})
                           (schema/handed-projection) symbols)
        request {:seon.schema/projection projection
                 :seon.instrument/changed-identities (set (map #(vector :seon.fn/sym %) symbols))}]
    (try
      (doseq [candidate candidates] (alter-meta! candidate assoc :malli/schema contract))
      (instrument/apply! request)
      (let [before (into {} (map (juxt identity deref)) (instrument/instrumented))
            result (instrument/apply!
                    (assoc request :seon.instrument/changed-identities
                           #{[:seon.fn/sym (first symbols)]}))
            replaced (into #{} (keep (fn [[v root]] (when-not (identical? root @v) v))) before)]
        (is (= #{(first candidates)} replaced))
        (is (= 1 (count replaced)))
        (is (= 1 (:seon.instrument/registered result)))
        (is (identical? (get before (second candidates)) @(second candidates)))
        (is (= 3 ((first candidates) 3)))
        (let [roots (mapv deref candidates)]
          (instrument/apply! (assoc request :seon.instrument/changed-identities #{}))
          (is (every? true? (map identical? roots (map deref candidates))))))
      (finally (doseq [name names] (ns-unmap 'seon.instrument-test name))))))

(deftest adoption-disarms-a-retired-contract
  (let [name 'retired-wrapper
        sym 'seon.instrument-test/retired-wrapper
        candidate (intern 'seon.instrument-test name identity)
        contract [:=> [:cat :int] :int]
        base (schema/handed-projection)
        projection (schema/projection-with-function-contract
                    base sym contract {:seon.schema.admission/source :core})
        request {:seon.schema/projection projection
                 :seon.instrument/changed-identities #{[:seon.fn/sym sym]}}]
    (try
      (alter-meta! candidate assoc :malli/schema contract)
      (instrument/apply! request)
      (is (contains? (instrument/instrumented) candidate))
      (let [original (mi/-f->original @candidate)]
        (instrument/apply! (assoc request :seon.schema/projection base))
        (is (identical? original @candidate))
        (is (not (contains? (instrument/instrumented) candidate))))
      (finally (ns-unmap 'seon.instrument-test name)))))

(deftest cold-arming-covers-the-complete-armable-population
  (doseq [candidate (instrument/instrumented)]
    (alter-var-root candidate mi/-f->original))
  (let [population (instrument/armable (map ns-name (all-ns)))
        applied (instrument/apply! {:seon.schema/projection (schema/handed-projection)})]
    (is (pos? (count population)))
    (is (= population (instrument/instrumented)))
    (is (= (count population) (:seon.instrument/registered applied)
           (:seon.instrument/instrumented applied)))))

(deftest boot-arming-arms-every-loaded-contract-with-its-definition-digest
  ;; Boot calls this owner once (`seon.cluster.boot/stand-cluster-runtime!`).
  ;; e0577a6fb narrowed adoption to changed identities and left boot arming
  ;; nothing, so a booted cluster ran with zero host wrappers; this regression
  ;; asserts the wanted population on the canonical fixture's program.
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           result (cluster/arm-host-program!
                   {:seon.profile/definition-digests (cluster/definition-digests database)
                    :seon.cluster/program-namespaces []
                    ;; The cluster's supplied-default rows the wrappers plan from.
                    :seon.db/db database}
                   (test-support/effective-config)
                   nil)
           armed (instrument/instrumented)
           digests (cluster/definition-digests database ['seon.id/valid?])]
       (is (= (count armed) (:seon.instrument/instrumented result)))
       (is (= (instrument/armable (map ns-name (all-ns))) armed)
           "every loaded contracted Var is armed, and nothing else")
       (is (= (get digests 'seon.id/valid?)
              (:seon.profile/digest (:seon.instrument/cell (meta @(requiring-resolve (quote seon.id/valid?))))))
           "the host cell carries the function row's definition digest")))))

(deftest a-root-replaced-outside-a-reload-is-named
  ;; `doubled` is this file's own defn: its compiled root and recorded file
  ;; match the row, so nothing is reported until a redefinition replaces it.
  (let [rows [['seon.instrument-test/doubled "test/seon/instrument_test.clj"]]]
    (is (= [] (instrument/replaced-roots rows)))
    (with-redefs [doubled (fn [n] n)]
      (is (= [{:seon.instrument/replaced-var 'seon.instrument-test/doubled
               :seon.instrument/expected-class "seon.instrument_test$doubled"
               :seon.instrument/expected-file "test/seon/instrument_test.clj"}]
             (mapv #(select-keys % [:seon.instrument/replaced-var
                                    :seon.instrument/expected-class
                                    :seon.instrument/expected-file])
                   (instrument/replaced-roots rows)))))
    (is (= [] (instrument/replaced-roots rows)) "restoring the root clears the report")))

(deftest a-handed-projection-without-the-contracts-declarations-uses-the-loaded-ones
  ;; A cluster whose stored program predates a loaded schema resource hands a
  ;; projection lacking its keys; the host wrapper then validates against the
  ;; declarations it was armed with (the files'), never refusing with
  ;; :malli.core/invalid-schema (default's first armed boot, 2026-09-23).
  (let [var-name (symbol (str "loaded-declarations-" (random-uuid)))
        candidate (intern 'seon.instrument-test var-name identity)
        request-key :seon.instrument-test/loaded-only-request
        handed (schema/handed-projection)
        loaded (schema/build-projection (assoc (schema/snapshot) request-key [:map [::value :int]]))
        contract [:=> [:cat request-key] :map]
        effective (test-support/effective-config)]
    (try
      (alter-meta! candidate assoc :malli/schema contract)
      (arm-with! candidate contract loaded (config/result-caps effective)
                 {:seon.config.error/max-evidence-bytes
                  (:seon.config.error/max-evidence-bytes effective)})
      (is (nil? (find (:seon.schema.projection/forms handed) request-key)))
      (is (= {::value 1} (schema/call-with-projection handed #(candidate {::value 1}))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (schema/call-with-projection handed #(candidate {::value "one"})))
          "the loaded declarations still refuse an invalid input")
      (finally (ns-unmap 'seon.instrument-test var-name)))))
