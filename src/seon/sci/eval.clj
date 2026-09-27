(ns seon.sci.eval
  "The guarded eval: one form, one armed boundary, one admitted value.

  DRAFT FOR ORCHESTRATOR SEAL REVIEW (drafted + implemented
  2026-07-27 — N3's last dependency, C7 from n3-plan §10). Adopted
  FRESH from the quarry's mechanism rather than ported: the old
  namespace's Semaphore, cached compute pool, database-derived binding
  table and lifecycle bindings are all gone, and what survives is the
  part that was right — one live ctx per cluster, one process-wide guard
  with thread-scoped arming, and time as the only limit.

  THE ARMED BOUNDARY. `:interrupt-fn` is sci's own hook, called on
  every interpreted fn and `loop/recur` body entrance
  (`reference-code/sci/doc/interrupt.md:6-9`). Ours reads a wall-clock
  flag and calls `sci.interrupt/interrupt!`, which throws something
  evaluated code CANNOT catch — sci's `try` refuses to hand it to a
  user `catch` clause and sandboxed code cannot forge it
  (`reference-code/sci/src/sci/interrupt.cljc:32-41`). That is the
  whole limit. Sci counts nothing: it has no step concept, so there is
  no fuel, no gas, and no interpreter-step budget to configure.

  `:seon.eval/fn-entries` AND `:seon.eval/allocated-bytes` ARE
  DIAGNOSTICS, NEVER LIMITS. Their value is diagnostic precisely
  because they are not enforced: 271M entries in 500 ms reads as a
  spin, 12 entries reads as blocked inside a host call — which is the
  one case the interrupt-fn cannot see, because a thread inside a host
  call never enters an interpreted body
  (`interrupt.md:56`, and its own closing warning that unbounded host
  work stays possible). The honest ceiling is stated rather than
  papered over: for hard guarantees the process boundary is the fence,
  and O4's heap watermark plus the caller's submission backstop cover
  what this cannot.

  ADMISSION HAPPENS INSIDE THE BOUNDARY, BEFORE DISARM. The value is
  realized and bounded by `seon.sci.admit/admit` while the interrupt-fn
  is still armed and still able to stop an infinite realization; the
  timer is cancelled and the current thread's guard state is removed
  afterwards, in `finally`. That ordering is the contract, not an
  implementation detail — after disarm the stable guard is inert until
  that thread's next evaluation arms it.

  NOTHING THROWS. Every failure is a flat `:seon.error` value carried
  inside an ordinary evaluation map: an agent's exception, a refused
  parse, a time limit, or a host failure all come back as data the run
  loop commits as a receipt. The loop has no catch around this call
  because there is nothing to catch.

  ONE GUARDED OWNER, TWO ENTRANCES. `seon.sci.kernel` owns the process
  guard, arming, the deadline, `interrupted?`, and the one failure
  classifier; this namespace is the FORM entrance and `kernel/invoke` is
  the NAMED-FUNCTION entrance a renderer call takes. Neither copies the
  other's guard semantics, so they cannot drift: work reached on a thread
  already armed inherits that arm and its deadline, in whichever context
  it evaluates.

  AN AGENT EVALUATES IN ITS ASSIGNED NAMESPACE, by construction. The eval
  reads `:seon.agent/namespace` from the database and binds sci's
  own `*ns*` there for the whole form, so a `defn` lands where the prompt
  says it lands and the model never needs to write `(in-ns …)` — which is
  what the first live drive tried, and what failed with `Can't
  change/establish root binding of clojure.core/*ns*`. The assignment fact
  is the authority; `my.agents.<id>` is only a creation-time default.

  OUTPUT IS CAPTURED, NOT DISCARDED. sci's `*out*` and `*err*` are
  unbound by default — `println` fails with `SciUnbound cannot be cast
  to java.io.Writer` — so both are bound to one writer per evaluation and
  what the form printed rides back on the evaluation as
  `:seon.cluster.eval/output`, streamed under the caps' `max-bytes`
  storage bound (a crossing print refuses with `:seon.sci.admit/error`).
  Compiled host code printing to `clojure.core/*out*` is not captured. Printed output is evidence an agent produced about
  its own work; a sink would have made it disappear, and the drive
  showed exactly how expensive disappeared evidence is.

  THE CLUSTER OWNS THE LIVE BASE CTX. Each agent retains its own context
  and receives accepted base changes before later turns. Every form, a
  function declaration included, evaluates once in that retained context;
  the install gate proves a declaration in a fork of it and a refusal
  restores the form's change (`refuse-install`). This raw form entrance uses a
  supplied context as given; cluster boot acquires the program-only base.
  When no ctx is supplied a fresh base is made for an isolated one-off.

  THE CTX IS SUPPLIED, NOT BUILT HERE. `build-base-ctx` is the minimum
  N3 needs —
  `clojure.core` and `clojure.string` in their interrupt-aware form
  plus bare `help`, the two `my.turn` dispositions, and both `my.message`
  values — and a caller may pass its own. `acquire!` compares program definition
  digests with the JVM's loaded source commit. Changed declarations and their
  affected callers interpret stored source; all other declarations bind the
  actual compiled Vars. The set is computed, never listed. `cluster-ctx` performs that fact-derived install
  only at cluster boot or recovery; the turn path never reacquires it.

  Crash walk: this namespace owns no durable state. A kill during an
  evaluation leaves the loop's running receipt (one with no terminal
  fact), which N2's `recover-tx` settles by asserting its
  `interrupted-at` — and rows 6 and 7 of the crash walk stay
  indistinguishable, which is honest: the form's effect MAY have
  happened. Nothing re-executes."
  (:require
            [clojure.core.cache.wrapped :as cache]
            [clojure.edn :as edn]
            [datahike.api :as d]
            [edamame.core :as edamame]
            [clojure.java.io :as io]
            [clojure.main :as main]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.test]
            [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [my.background]
            [my.message]
            [my.turn]
            [sci.addons.future :as sci.future]
            [sci.core :as sci]
            [sci.ctx-store]
            [sci.impl.namespaces :as sci.namespaces]
            [sci.impl.vars :as sci.vars]
            [sci.impl.utils :as sci.utils]
            [sci.interrupt :as sci.interrupt]
            [seon.call-preparation :as call-preparation]
            [seon.config :as config]
            [seon.db :as db]
            [seon.id :as id]
            [seon.effect :as effect]
            [seon.fn]
            [seon.env :as env]
            [seon.error :as error]
            [seon.error.refusal :as error.refusal]
            [seon.instrument :as instrument]
            [seon.program :as program]
            [seon.render :as render]
            [seon.ai.tokens :as tokens]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.kernel :as kernel]
            [seon.sci.reader :as reader]
            [seon.test.accretion :as accretion]
            [seon.test.runner :as test.runner]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(defn ctx?
  "True for a sci interpreter context.
  Sci's own vocabulary and its own shape: `sci/init` returns a map
  carrying the interpreter's environment, and `sci/fork` derives one
  from another."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (and (map? value) (contains? value :env)))

(schema/register-core-predicate! 'seon.sci.eval/ctx? ctx?)

(defn sci-var?
  "True for an SCI Var, the interned value of an SCI namespace map."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (sci.utils/var? value))

(schema/register-core-predicate! 'seon.sci.eval/sci-var? sci-var?)

(defn context-state?
  "True for the atom holding one piece of an SCI context's program state."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (instance? clojure.lang.IAtom value))

(schema/register-core-predicate! 'seon.sci.eval/context-state? context-state?)

(def sci-var-generator
  (gen/fmap (fn [_] (sci.utils/new-var 'generated nil)) (gen/return nil)))

(defn projection-state?
  "True for the replacement reference holding one immutable environment."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (env/environment-state? value))

(schema/register-core-predicate! 'seon.sci.eval/projection-state?
                                 projection-state?)

(def projection-state-generator env/environment-state-generator)

(def ctx-generator
  "A real ctx, honest by constructing a fresh one per sample (0.04 ms): no
  Var holds a live context, and no sample's mutation reaches the next."
  (gen/fmap (fn [_] (sci/init {})) (gen/return nil)))

(schema.edn/load! {})

(declare carried-cluster-name)

(defn projection-state
  "Create `cluster-name`'s context environment replacement reference; the
  caller names the cluster whose world `db` is. Without a name, the cluster is
  the one that world itself names (`carried-cluster-name`), never an invented one."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.schema/projection] :seon.sci.eval/projection-state]
    [:=> [:cat :seon.db/database-value :seon.schema/projection :seon.boot/cluster-name]
     :seon.sci.eval/projection-state]]}
  ([db projection] (projection-state db projection (carried-cluster-name nil db)))
  ([db projection cluster-name]
   (env/environment-state
    (env/refuse-incomplete-environment!
     (env/environment {:seon.boot/cluster-name cluster-name
                       :seon.db/basis-t (db/basis-t db)
                       :seon.schema/projection projection})))))

(defn- carried-cluster-name
  "The cluster the caller's world names: the environment its connection or
  database value carries, else the database's one cluster row, else, for the
  published source branch (which holds no cluster), that branch's own name.
  Anything else refuses by name; no cluster is invented."
  {:malli/schema [:=> [:cat [:maybe :seon.db/connection] :seon.db/database-value] :seon.boot/cluster-name]}
  [connection db]
  (or (some-> (or (some-> connection meta ::projection-state) (::projection-state (meta db)))
              deref :seon.boot/cluster-name)
      (let [names (db/q '[:find [?name ...] :where [_ :seon.cluster/name ?name]] db)]
        (cond
          (= 1 (count names)) (first names)
          (and (empty? names) (= :current-src (get-in db [:config :branch]))) (name :current-src)
          :else
          (throw (ex-info "A cluster context needs its caller's cluster name: this world carries no environment and names no single cluster."
                          {:seon.error/expected :seon.boot/cluster-name
                           :seon.error/offending (vec (sort names))}))))))

;;; ---------------------------------------------------------------------------
;;; The base context
;;; ---------------------------------------------------------------------------

(defn- copy-host-var
  "Copy a loaded JVM Var into SCI at its root.

  SCI's `copy-var*` copies `@host-var` (`reference-code/sci/src/sci/core.cljc:137`),
  which for a dynamic Var bound on this thread is the binding: a base context
  derived under an evaluation would keep that evaluation's `seon.db/*read-database*`,
  and the memoized base would pin its branch's node cache."
  {:malli/schema [:=> [:cat [:fn clojure.core/var?] [:fn clojure.core/some?]] [:fn clojure.core/some?]]}
  [^clojure.lang.Var host-var sci-namespace]
  (let [copied (sci/copy-var* host-var sci-namespace)]
    (when (.getThreadBinding host-var)
      (.bindRoot ^sci.lang.Var copied (.getRawRoot host-var)))
    copied))


(defn- load-host-namespace
  "SCI's `:load-fn` (`reference-code/sci/src/sci/impl/load.cljc:204-243`): a
  namespace the JVM already loaded, required and not yet in the context, is
  exposed as itself, each public Var copied at its root, so an agent requires
  `malli.core` or `datahike.api` as a Clojure REPL does. Program namespaces are
  acquired into the context before any form runs, so none reaches here; an
  unloaded namespace answers nil and SCI reports it as not found.

  The copies carry no SCI generation: the agent defined none of them, so the
  definition authority (`turn-interns`) never reads them as its program change.
  `regenerate-agent-context!` carries them because the agent's base never had
  their namespace."
  {:malli/schema [:=> [:cat [:map [:namespace :symbol] [:ctx :map]]] [:maybe :map]]}
  [{namespace-name :namespace ctx :ctx}]
  (when-let [host (find-ns namespace-name)]
    (let [sci-namespace (sci/create-ns namespace-name)]
      (sci/add-namespace!
       ctx namespace-name
       (into {:obj sci-namespace}
             (map (fn [[intern-name host-var]]
                    [intern-name (copy-host-var host-var sci-namespace)]))
             (ns-publics host)))
      {})))

;;; clojure.test over the evaluating context's namespaces. The agent's
;;; clojure.test is the host namespace copied in; its test-var, test-vars and
;;; report run as they are, while these four find SCI namespaces through the
;;; evaluating ctx (`sci.ctx-store`, bound by `sci/eval-form`) as babashka's
;;; SCI-side copy does (reference-code/babashka/src/babashka/impl/clojure/test.clj:750-790).

(defn- agent-test-all-vars
  "Run every test Var interned in one SCI namespace, with its fixtures."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "clojure.test names a namespace by symbol or by namespace object.", :gen/elements [nil]}]] :nil]}
  [namespace-designator]
  (let [ctx (sci.ctx-store/get-ctx)]
    (clojure.test/test-vars
     (vals (sci.namespaces/sci-ns-interns*
            ctx (sci.namespaces/sci-the-ns* ctx namespace-designator))))))

(defn- agent-test-ns
  "Run one SCI namespace's tests; answer its report counters."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "clojure.test names a namespace by symbol or by namespace object.", :gen/elements [nil]}]] [:map-of :keyword :int]]}
  [namespace-designator]
  (binding [clojure.test/*report-counters* (ref clojure.test/*initial-report-counters*)]
    ;; clojure.test's :begin-test-ns report calls the host `ns-name`, which
    ;; cannot name a SCI namespace; print its line here instead.
    (clojure.test/with-test-out
      (println "\nTesting" (str (sci.namespaces/sci-the-ns* (sci.ctx-store/get-ctx) namespace-designator))))
    (agent-test-all-vars namespace-designator)
    @clojure.test/*report-counters*))

(defn- agent-run-tests
  "Run the named SCI namespaces' tests, else the current namespace's; report the summary."
  {:malli/schema [:=> [:cat [:* [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "clojure.test names a namespace by symbol or by namespace object.", :gen/elements [nil]}]]] [:map-of :keyword [:or :int :keyword]]]}
  [& namespace-designators]
  (let [summary (assoc (apply merge-with + (map agent-test-ns (or namespace-designators [@sci/ns])))
                       :type :summary)]
    (clojure.test/do-report summary)
    summary))

(defn- agent-use-fixtures
  "Record fixtures on the current SCI namespace, where clojure.test/test-vars reads them."
  {:malli/schema [:=> [:cat [:enum :once :each] [:* ifn?]] :any]}
  [fixture-type & fixtures]
  (alter-meta! @sci/ns assoc
               (keyword "clojure.test" (str (name fixture-type) "-fixtures")) fixtures))

(defn- agent-test-namespace
  "The clojure.test entries that must see the evaluating context's namespaces."
  [sci-namespace]
  (into {}
        (map (fn [[intern-name function]]
               [intern-name (sci/new-var intern-name function {:ns sci-namespace})]))
        {'test-all-vars agent-test-all-vars
         'test-ns agent-test-ns
         'run-tests agent-run-tests
         'use-fixtures agent-use-fixtures}))

(defn- native-mutation
  "SCI's own `native` operation, refused by name when it would change a
  program row outside `my.program`.

  The decision is `my.program/native-call-refusal`'s, over the world of the
  evaluation calling (`kernel/world-ctx`); an admitted program operation
  (`evaluate-native!`) and unevaluated host work run the native unchanged."
  {:malli/schema [:=> [:cat :seon.schema/value] [:fn clojure.core/ifn?]]}
  [native]
  (let [operation @native]
    (fn [& arguments]
      (let [ctx (kernel/world-ctx)
            refusal (when (and ctx (not (::native-operation? ctx)))
                      ((requiring-resolve 'my.program/native-call-refusal)
                       ctx native (vec arguments)))]
        (if refusal
          (unreduced refusal)
          (apply operation arguments))))))

(defn build-base-ctx
  "Build the minimal interpreter from the projection supplied by its caller."
  {:malli/schema [:=> [:cat :seon.schema/projection] :seon.sci.eval/ctx]}
  [projection]
  (let [{guard ::kernel/guard :as kernel-options} (kernel/context-options)
        injected-namespaces
        (into {}
              (map
               (fn [[namespace-name symbols]]
                 (let [sci-namespace (sci/create-ns namespace-name)]
                   [namespace-name
                    (into {}
                          (map (fn [qualified]
                                 [(symbol (name qualified))
                                  (copy-host-var
                                   (or (when-let [host (find-ns (symbol (namespace qualified)))]
                                         (ns-resolve host (symbol (name qualified))))
                                       (throw (ex-info
                                               (str "The loaded JVM has no interpreter binding " qualified ".")
                                               {:seon.error/at (java.util.Date.)
                                                 :seon.error/layer :seon.sci.eval/program
                                                 :seon.error/operation 'seon.sci.eval/build-base-ctx
                                                 :seon.error/message "Interpreter binding is unavailable; load its declared JVM namespace."
                                                 :seon.error/offending qualified
                                                 ::row-symbol qualified
                                                 ::acquisition-observation {:seon.error.evidence/attribute :seon.fn/sym
                                                                            :seon.error.evidence/value qualified}
                                                 :seon.error/expected :seon.sci.eval/loaded-binding})))
                                   sci-namespace)]))
                          symbols)])))
              (group-by (comp symbol namespace)
                        (program/base-context-injected-symbols projection)))
        injected-namespaces (update injected-namespaces 'clojure.test merge
                                    (agent-test-namespace (sci/create-ns 'clojure.test)))
        ctx
        (sci/init
        (->
        {:interrupt-fn (:interrupt-fn kernel-options)
         :host-interop-observer (:host-interop-observer kernel-options)
         :built-in-call-observer (:built-in-call-observer kernel-options)
         ;; the interrupt-aware core: a lazy sequence built by NATIVE
         ;; clojure.core enters no interpreted body, so `(range)` inside
         ;; `reduce` would never hit the interrupt-fn. Sci ships drop-in
         ;; alternatives for exactly this and they are opt-in
         ;; (interrupt.md:56-60).
         :namespaces
         (merge {'clojure.core sci.interrupt/clojure-core
                 'clojure.string sci.interrupt/clojure-string}
                injected-namespaces)
         ;; every class on the classpath resolves as the compiler resolves it
         ;; (our SCI fork's `interop/host-class-opts`); interruption stays
         ;; SCI's ceiling: a host call is not interrupted (interrupt.md:84-98)
         :classes {:allow :all}
         :load-fn load-host-namespace}
         ;; SCI's own `future` addon; its `future-call` carries the arm
         sci.future/install
         (assoc-in [:namespaces 'clojure.core 'future-call] kernel/future-call)))
        help-var (get-in injected-namespaces ['seon.bootstrap 'help])
        dir-var (get-in injected-namespaces ['seon.bootstrap 'dir])
        doc-var (get-in injected-namespaces ['seon.bootstrap 'doc])]
    ;; REPL documentation and test macros resolve through the core refer
    ;; every namespace receives. Acquisition refreshes the same bindings.
    (sci/add-namespace!
     ctx 'clojure.core
     (into {'dir dir-var
            'doc doc-var
            'help help-var
            'deftest (sci/resolve ctx 'clojure.test/deftest)
            'is (sci/resolve ctx 'clojure.test/is)}
           ;; Native program mutation asks the program's facts first.
           (map (fn [native-name]
                  [native-name (native-mutation
                                (sci/resolve ctx (symbol "clojure.core" (name native-name))))]))
           '[intern alter-var-root remove-ns ns-unalias]))
    (sci/add-namespace!
     ctx 'seon.bootstrap
     {'dir dir-var
      'doc doc-var
      'help help-var})
    (assoc ctx
           ::kernel/guard guard
           ::kernel/installed-functions (atom #{})
           ::kernel/program-snapshot (atom {:functions {} :namespaces {}})
           :seon.schema/projection projection)))

(defn agent-namespace
  "The namespace name assigned to `agent-id`, or nil when it is absent.

  This reads the agent's own assignment fact — the namespace it works in
  by default — and never reconstructs it from an agent id naming
  convention. It is not the inverse of `seon.cluster.agent/steward-of`:
  assignment is not unique, so several agents may answer with the same
  namespace, and the namespace's one steward is a separate fact."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.agent/id]
                  [:maybe :seon.ns/name]]}
  [db agent-id]
  (db/q '[:find ?namespace-name .
          :in $ ?agent-id
          :where
          [?agent :seon.agent/id ?agent-id]
          [?agent :seon.agent/namespace ?namespace]
          [?namespace :seon.ns/name ?namespace-name]]
        db agent-id))

;;; ---------------------------------------------------------------------------
;;; The armed boundary
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; The one operation
;;; ---------------------------------------------------------------------------

(defn- evaluation-output
  "Return the complete printed value for storage and later rendering."
  [prefix printed]
  (let [printed (str printed)]
    (when (or (seq prefix) (seq printed))
      (str prefix
           (when (and (seq prefix) (seq printed)) "\n")
           printed))))

(declare deleted-schema-key)

(defn- row
  "Return reader-owned rows; Var definitions are derived after evaluation."
  [event projection]
  (or
   (let [deletion (program/deletion-row event)]
     (when (deleted-schema-key deletion) deletion))
   ;; A schema's source form is executable SCI code, not yet a Malli value.
   ;; Its canonical row is derived from the value evaluated inside the
   ;; registration delta in `declared-row`; validating this source expression
   ;; here would execute zero times and reject ordinary `(do ... schema)`.
   (when (:seon.schema/key event)
     (assoc (select-keys event [:seon.schema/key :seon.schema/form
                                :seon.schema/ns])
            :seon.schema.admission/source :agent))
   (when (:seon.ns/name event)
     (program/declaration-row projection event :contracted :agent))))

(defn- removed-program-identities
  "Function and test identities removed from SCI's own intern tables."
  [before after]
  (into []
        (mapcat
         (fn [[namespace-name intern-names]]
           (mapcat (fn [intern-name]
                     (let [qualified (symbol (str namespace-name)
                                             (str intern-name))]
                       [[:seon.fn/sym qualified]
                        [:seon.test/sym qualified]]))
                   (sort-by str
                            (remove (get after namespace-name #{})
                                    intern-names)))))
        before))

(def ^:private absent-intern (Object.))

(defn- turn-interns
  "Vars and dereferenced roots owned by this SCI fork's generation.

  `def`, `intern`, and inherited-root writes stamp SCI's current fork
  generation (`reference-code/sci/src/sci/impl/evaluator.cljc:25-49` and
  `sci/impl/utils.cljc:359-379`). Host Vars copied into the context by system
  namespace installation carry no such stamp. This is the definition-event
  provenance for the agent's defs: namespace membership never decides authorship.

  A Var of this generation that did not exist in the acquired program was
  `assoc-in`ed into its namespace map, so only maps not `identical?` to the
  acquired program's (`::program-namespaces`, taken under another generation)
  are walked. Without that reference every namespace is walked.

  Values rather than Var identities are retained because redefinition may
  mutate an already generation-owned Var root. Values are not traversed."
  [ctx]
  (let [turn-generation (:sci/generation @(:env ctx))
        program (::program-namespaces @(::kernel/program-snapshot ctx))
        reference (when (and program
                             (not= (::generation program) turn-generation))
                    (::namespaces program))]
    (if-not turn-generation
      {}
      (into {}
            (for [[namespace-name namespace-map] (sci/namespace-state ctx)
                  :when (not (and reference
                                  (identical? namespace-map
                                              (get reference namespace-name))))
                  [intern-name sci-var] namespace-map
                  :when (and (symbol? intern-name)
                             (sci.utils/var? sci-var)
                             (= turn-generation (:sci/generation (meta sci-var))))]
              [(symbol (str namespace-name) (str intern-name))
               {:seon.sci.eval/var sci-var
                :seon.sci.eval/metadata (meta sci-var)
                :seon.sci.eval/value (if (sci.vars/hasRoot sci-var)
                                       @sci-var
                                       absent-intern)}])))))

(defn- turn-intern-values
  [interns]
  (into {} (map (fn [[qualified entry]]
                  [qualified (:seon.sci.eval/value entry)]))
        interns))

(defn- same-intern-value?
  [left right]
  (or (identical? left right)
      (and (not (identical? absent-intern left))
           (not (identical? absent-intern right))
           (= (class left) (class right))
           (= (meta left) (meta right))
           (= left right))))

(declare missing-contract install-function-contract!)

(defn- program-value?
  "Whether a definition's root makes it a program declaration: a function or
  test. It reads only the root and its metadata, so it takes the binding shape
  that both an intern and a failed form's surviving definition carry."
  {:malli/schema [:=> [:cat :seon.sci.eval/binding] :boolean]}
  [{value :seon.sci.eval/value metadata :seon.sci.eval/metadata}]
  (and (not (identical? absent-intern value))
       (boolean (or (fn? value) (:test metadata)))))

(defn- held-intern
  "The intern `qualified` names in the held namespace maps, as `turn-interns`
  would report it: an inherited Var is copied before any write, so its root and
  metadata here are the prior ones."
  {:malli/schema [:=> [:cat :seon.sci.eval/namespaces :qualified-symbol]
                  [:maybe :seon.sci.eval/intern]]}
  [namespaces qualified]
  (let [sci-var (get-in namespaces [(symbol (namespace qualified)) (symbol (name qualified))])]
    (when (sci.utils/var? sci-var)
      {:seon.sci.eval/var sci-var
       :seon.sci.eval/metadata (meta sci-var)
       :seon.sci.eval/value (if (sci.vars/hasRoot sci-var) @sci-var absent-intern)})))

(defn- program-definitions
  "This form's definitions: the Vars whose root or Var metadata changed
  between the prior intern (`before`, else the held namespace maps) and
  `after`, when either the prior or the final root is a program value.
  Replacing a function with data is therefore still a definition, marked
  `::prior-program?`. Other defs (data, atoms, protocol and multimethod
  objects) are private state. Roots compare by identity first; only function
  values reach `=`."
  {:malli/schema [:=> [:cat :seon.sci.eval/namespaces :seon.sci.eval/interns :seon.sci.eval/interns]
                  [:sequential [:tuple :qualified-symbol :seon.sci.eval/intern]]]}
  [before-namespaces before after]
  (sort-by (comp str first)
           (keep (fn [[qualified {value :seon.sci.eval/value
                                  metadata :seon.sci.eval/metadata :as entry}]]
                   (let [prior (or (get before qualified)
                                   (held-intern before-namespaces qualified))
                         prior-program? (boolean (some-> prior program-value?))]
                     (when (and (or prior-program? (program-value? entry))
                                (or (not (same-intern-value?
                                          (get prior :seon.sci.eval/value absent-intern) value))
                                    (not (identical? (:seon.sci.eval/metadata prior) metadata))))
                       [qualified (assoc entry ::prior-program? prior-program?)])))
                 after)))

(defn- source-offset
  "The UTF-8 byte offset within `source`, read at `start`, of the reader
  position `[line column]`: the unit of an analyzed row's `:seon.fn/form-span`.
  Nil when the position is absent or lies outside `source`."
  {:malli/schema [:=> [:cat :string :seon.sci.eval/position
                       [:tuple [:maybe :int] [:maybe :int]]]
                  [:maybe :int]]}
  [source [start-line start-column] [line column]]
  (when (and (int? line) (int? column) (<= start-line line))
    (let [line-start (reduce (fn [offset _]
                               (if-let [newline (str/index-of source "\n" offset)]
                                 (inc newline)
                                 (reduced nil)))
                             0 (range (- line start-line)))
          offset (when line-start
                   (+ line-start (- column (if (= line start-line) start-column 1))))]
      (when (and offset (<= 0 offset (count source)))
        (alength (.getBytes (subs source 0 offset) java.nio.charset.StandardCharsets/UTF_8))))))

(defn- program-change
  "Classify this form's definitions against its analyzed rows by provenance.
  SCI gives a defined Var the `:line`/`:column` of the declaration that ran
  and marks a root written after it `:sci/rebound`; a definition is matched
  only when exactly one analyzed row names it, its root was not rebound, and
  the analyzer's `:seon.fn/form-span` for that row starts at the Var's
  position. Otherwise it is unindexed: an `eval` or macro expansion, a
  second occurrence or root write overwriting the analyzed one, or a
  function replaced by data. Repeated occurrences of one name are repeated
  declarations. A Var no row names is a helper of a host-bound declaration
  (record or type) only when it was defined at that declaration's position.
  Host-bound rows group by their shared declaration position."
  {:malli/schema [:=> [:cat [:sequential [:tuple :qualified-symbol :seon.sci.eval/intern]]
                       [:vector :map] :string :seon.sci.eval/position]
                  :seon.sci.eval/program-change]}
  [definitions analyzed source start]
  (let [position (comp first :seon.fn/form-span)
        identity-of #(or (:seon.fn/sym %) (:seon.test/sym %))
        by-name (group-by identity-of analyzed)
        host-positions (into #{} (comp (filter :seon.fn/host-bound?) (keep position)) analyzed)
        classify
        (fn [[qualified {sci-var :seon.sci.eval/var :as entry}]]
          (let [occurrences (get by-name qualified)
                defined-at (source-offset source start ((juxt :line :column) (meta sci-var)))]
            (cond
              (and (::prior-program? entry) (not (program-value? entry))) {::unmatched qualified}
              (< 1 (count occurrences)) {::repeated (repeat (count occurrences) qualified)}
              ;; A root written after the definition (alter-var-root, intern,
              ;; with-redefs) no longer comes from it: SCI marks it rebound.
              (:sci/rebound (meta sci-var)) {::unmatched qualified}
              (and (= 1 (count occurrences)) defined-at
                   (= defined-at (position (first occurrences))))
              {::row (first occurrences) ::var sci-var}
              (and (empty? occurrences) defined-at (contains? host-positions defined-at)) {}
              :else {::unmatched qualified})))
        classified (map classify definitions)
        matched (keep #(when (::row %) %) classified)]
    {::matched (vec matched)
     ::unmatched (into [] (keep ::unmatched) classified)
     ::declared (into (vec (mapcat ::repeated classified))
                      (concat (keep #(when-not (:seon.fn/host-bound? (::row %))
                                       (identity-of (::row %)))
                                    matched)
                              (->> (filter #(:seon.fn/host-bound? (::row %)) matched)
                                   (group-by #(position (::row %)))
                                   vals
                                   (map #(identity-of (::row (first %)))))))}))

(defn- row-backed?
  "Whether `qualified` names a stored function or test row in `db`."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-symbol] :boolean]}
  [db qualified]
  (boolean
   (some (fn [attribute]
           (let [found (db/pull db [:db/id] [attribute qualified])]
             (when (:seon.error/at found)
               (throw (ex-info (:seon.error/message found) found)))
             (some? (:db/id found))))
         [:seon.fn/sym :seon.test/sym])))

(defn- removed-interns
  "Vars this form removed: `[namespace-name intern-name prior-var]` for every
  symbol intern of a held namespace map that differs by identity after it,
  so only changed namespace maps are walked."
  {:malli/schema [:=> [:cat :seon.sci.eval/namespaces :seon.sci.eval/namespaces]
                  [:sequential [:tuple :symbol :symbol :seon.sci.eval/sci-var]]]}
  [before-namespaces after-namespaces]
  (for [[namespace-name before-map] before-namespaces
        :let [after-map (get after-namespaces namespace-name)]
        :when (not (identical? before-map after-map))
        [intern-name prior] before-map
        :when (and (symbol? intern-name) (sci.utils/var? prior)
                   (not (find after-map intern-name)))]
    [namespace-name intern-name prior]))

(defn- var-step
  "The step putting `sci-var` back to its captured root, or unbinding it when
  it had none, with its captured metadata."
  {:malli/schema [:=> [:cat :seon.sci.eval/sci-var :seon.sci.eval/held-value :map]
                  :seon.sci.eval/restoration-step]}
  [sci-var value metadata]
  (if (identical? absent-intern value)
    {::var sci-var ::unbound true ::metadata metadata}
    {::var sci-var ::root value ::metadata metadata}))

(defn- program-restoration
  "The steps that put this form's program change back: each changed Var's
  prior root and metadata (Var identity kept, so interpreted callers stay
  linked), the held prior Var of a copied inherited one, no Var the form
  created, and each removed Var or namespace re-interned from the held
  namespace maps. SCI copies an inherited Var before writing it
  (`sci/impl/evaluator.cljc:25-49`), so the held map's prior Var is
  unmodified. `restore?` selects the names. Limits: the form has already
  run; its IO, atom writes and calls stay done."
  {:malli/schema [:=> [:cat :seon.sci.eval/namespaces :seon.sci.eval/namespaces
                       :seon.sci.eval/interns :seon.sci.eval/interns
                       [:=> [:cat :qualified-symbol] :any]]
                  [:vector :seon.sci.eval/restoration-step]]}
  [before-namespaces after-namespaces before after restore?]
  (into
   (vec
    (for [[qualified {sci-var :seon.sci.eval/var
                      after-value :seon.sci.eval/value
                      after-metadata :seon.sci.eval/metadata}] after
          :let [path [(symbol (namespace qualified)) (symbol (name qualified))]
                prior (get-in before-namespaces path)
                {value :seon.sci.eval/value metadata :seon.sci.eval/metadata}
                (get before qualified)]
          :when (and (not (and (identical? value after-value)
                               (identical? metadata after-metadata)))
                     (restore? qualified))]
      (cond
        (and (identical? prior sci-var) (find before qualified))
        (var-step sci-var value metadata)
        (some? prior) {::path path ::prior prior}
        :else {::path path})))
   (mapcat
    (fn [[namespace-name intern-name prior]]
      (let [qualified (symbol (str namespace-name) (str intern-name))
            {owned ::var value ::value metadata ::metadata} (get before qualified)]
        (when (restore? qualified)
          (cond-> [(if (get after-namespaces namespace-name)
                     {::path [namespace-name intern-name] ::prior prior}
                     {::path [namespace-name] ::prior (get before-namespaces namespace-name)})]
            ;; A Var this generation owned was redefined in place before its
            ;; removal: the held Var carries the new root, so put back the
            ;; captured one as well.
            (identical? owned prior)
            (conj (var-step prior value metadata))))))
    (removed-interns before-namespaces after-namespaces))))

(defn- apply-restoration!
  "Apply `program-restoration`'s steps to `ctx`."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:vector :seon.sci.eval/restoration-step]] :nil]}
  [ctx steps]
  (doseq [{sci-var ::var path ::path state ::state :as step} steps]
    (cond
      state (reset! state (::held step))
      sci-var (do (if (::unbound step)
                    (sci.vars/unbind sci-var)
                    (sci.vars/bindRoot sci-var (::root step)))
                  (reset-meta! sci-var (::metadata step)))
      (find step ::prior) (swap! (:env ctx) assoc-in (into [:namespaces] path) (::prior step))
      :else (swap! (:env ctx) update-in [:namespaces (first path)] dissoc (second path)))))

(defn- execution-unavailable
  "The refusal every later form of an execution receives once a refused
  program change could not be restored, with the whole cause: the
  restoration's own failure, if it threw, and the `refused` failure it was
  restoring. When both exist the outer link carries the restoration's chain
  as its data and is caused by the refusal."
  {:malli/schema [:=> [:cat :seon.sci.eval/unavailable [:maybe :seon.error/throwable]
                       [:maybe :seon.error/throwable]]
                  :seon.sci.eval/execution-unavailable-error]}
  [kind restoration refused]
  (seon.error.refusal/diagnostic
   (cond-> {:seon.error/at (java.util.Date.)
            :seon.error/layer :seon.sci.eval/program
            :seon.error/operation 'seon.sci.eval/evaluate
            :seon.error/message
            (str "This execution is unavailable: "
                 (case kind
                   :restore-failed "restoring a refused program change threw"
                   :program-reacquired "its program was reacquired before a refused change could be restored")
                 "; the rest of this run is refused, and your next run starts from the branch head"
                 " without this run's private definitions.")
            ::unavailable kind}
     (or restoration refused)
     (assoc :seon.error/throwable
            (if (and restoration refused)
              (ex-info "Restoring a refused program change threw."
                       {:seon.error/chain (seon.error.refusal/chain restoration)} refused)
              (or restoration refused))))))

(defn- restore-or-unavailable!
  "Plan and apply a restoration through `steps!`, unless the context
  reacquired its program since `loaded` was captured. Planning or applying
  that throws, or a reacquisition, leaves the execution unavailable: the
  refusal is kept on the context (every later form receives it), delivered
  through the context's fault recorder, and answered, caused by `refused`
  when a failure is being restored; nil when restored."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:maybe :seon.db/database-value]
                       [:=> [:cat] [:vector :seon.sci.eval/restoration-step]]
                       [:maybe :seon.error/throwable]]
                  [:maybe :seon.sci.eval/execution-unavailable-error]]}
  [ctx loaded steps! refused]
  (let [unavailable
        (try
          (let [steps (steps!)]
            (cond
              (empty? steps) nil
              (not (identical? loaded (::loaded-database @(::kernel/program-snapshot ctx))))
              (execution-unavailable :program-reacquired nil refused)
              :else (do (apply-restoration! ctx steps) nil)))
          (catch Throwable failure
            (execution-unavailable :restore-failed failure refused)))]
    (when unavailable
      (swap! (:env ctx) assoc ::unavailable-execution unavailable)
      (when-let [commit-fault! (:seon.flow/commit-fault! @(::kernel/program-snapshot ctx))]
        (commit-fault! {:seon.error/source unavailable
                        :seon.error/declared-schema :seon.sci.eval/execution-unavailable-error}))
      unavailable)))

(defn- context-capture
  "Hold `ctx`'s program state now: its namespace maps, generation-owned
  interns, and the program snapshot, installed functions and environment
  state its installs and projection advances replace. One walk of the
  interns serves both admission and restoration."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :seon.sci.eval/capture]}
  [ctx]
  {::namespaces (:namespaces @(:env ctx))
   ::owned (turn-interns ctx)
   ::states (into [] (keep (fn [state] (when state {::state state ::held @state})))
                [(::kernel/program-snapshot ctx) (::kernel/installed-functions ctx)
                 (get ctx env/state-carrier)])})

(defn- context-restoration
  "The one restoration of `ctx` to its `capture`: the namespace maps and
  generation-owned interns (`program-restoration`) and, for a whole
  restoration, the held program state. Called with a selector, only the
  selected Vars are restored; with the failure being restored, an unavailable
  refusal keeps it as its cause."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.sci.eval/capture] :seon.sci.eval/restore]}
  [ctx {namespaces ::namespaces owned ::owned held ::states}]
  (let [loaded (::loaded-database (::held (first held)))]
    (fn restore!
      ([] (restore! nil nil))
      ([restore?] (restore! restore? nil))
      ([restore? refused]
       (restore-or-unavailable!
        ctx loaded
        #(cond-> (program-restoration namespaces (:namespaces @(:env ctx)) owned
                                      (turn-interns ctx) (or restore? (constantly true)))
           (nil? restore?)
           (into (remove (fn [step] (identical? @(::state step) (::held step)))) held))
        refused)))))

(defn- unindexed-change
  "The refusal of a program change the form's analysis does not declare."
  {:malli/schema [:=> [:cat [:sequential {:min 1} :qualified-symbol]] :seon.sci.eval/unindexed-change-error]}
  [members]
  (seon.error.refusal/diagnostic
   (java.util.Date.) :seon.sci.eval/program 'seon.sci.eval/evaluate
   {:seon.error/message
    (str "This form changed " (str/join ", " (sort-by str members))
         ", which its analysis declares no definition for at the place it ran: an eval, a "
         "macro expansion, a second definition of the name, a root written after its "
         "definition (alter-var-root, intern), a nested unmap, a point-free "
         "def such as (def f (comp ...)), or a function replaced by a value. Define it with "
         "its own defn, or remove it first with a top-level ns-unmap.")
    :seon.error/member :seon.program/row
    ::unindexed (set members)}))

(defn- multiple-declarations
  "The refusal of a form making more than one program declaration, or a
  declaration beside a removal."
  {:malli/schema [:=> [:cat [:sequential {:min 1} :seon.sci.eval/program-name] [:sequential :qualified-symbol]]
                  :seon.sci.eval/multiple-declarations-error]}
  [declared removed]
  (seon.error.refusal/diagnostic
   (java.util.Date.) :seon.sci.eval/program 'seon.sci.eval/evaluate
   (cond-> {:seon.error/message
            (str "One form may make one program declaration; this one makes "
                 (str/join ", " (concat (map str (sort-by str declared))
                                        (map #(str "removal of " %) (sort-by str removed))))
                 ". Send each declaration as its own top-level form.")
            :seon.error/member :seon.program/row
            ::declarations (vec declared)}
     (seq removed) (assoc ::removals (set removed)))))

(defn- program-identity-name
  "The name a program row declares."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.sci.eval/program-name]]}
  [row]
  (or (:seon.fn/sym row) (:seon.test/sym row) (:seon.schema/key row)
      (:seon.ns/name row)
      (some-> row :seon.program/delete-identities first second)))

(defn- analyzed-rows
  "Analyze the accepted form source once, answering its declaration rows."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection :string
                       :seon.sci.eval/namespace-row]
                  [:vector :map]]}
  [database projection source namespace-row]
  (vec (seon.fn/source-rows
        database
        (program/shapes-in projection)
        (assoc namespace-row :seon.program/definition-digest
               (program/definition-digest namespace-row)) source
        (set (keys (:seon.schema.projection/forms projection))))))

(defn- definition-row
  "One matched analyzed row made FINAL: runtime contract, workload and test
  markers from the Var, contract facts, and the digest from
  `seon.db/definition-digest-of` with the namespace row its transaction
  leaves stored (the database's, which holds the run's earlier forms, else
  SCI's for a namespace not yet stored), the digest the writer recomputes."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection :map
                       :map :seon.sci.eval/sci-var]
                  :map]}
  [database projection namespace-row analysed-row sci-var]
  (let [namespace-name (:seon.ns/name namespace-row)
        digest-namespace (or (db/pull database '[*] [:seon.ns/name namespace-name])
                             namespace-row)
        metadata (meta sci-var)
        qualified (or (:seon.fn/sym analysed-row) (:seon.test/sym analysed-row))
        ;; The form-span locates the row in this form's source only.
        row (cond-> (assoc (dissoc analysed-row :seon.fn/form-span) ::evaluated? true)
              (:malli/schema metadata)
              (assoc :seon.fn/spec
                     (pr-str (accretion/data-contract! (:malli/schema metadata))))
              (contains? #{:io :compute} (:seon.workload metadata))
              (assoc :seon.fn/workload (:seon.workload metadata))
              (:test metadata)
              (merge (program/test-markers metadata (meta (:ns metadata)))))
        row (if (:seon.fn/spec row)
              (let [definition (edn/read-string (:seon.fn/spec row))]
                (schema/projection-with-function-contract
                 projection qualified definition
                 {:seon.schema.admission/source :agent})
                (program/with-contract-facts
                 {:seon.program/row row
                  :seon.program/compile-options
                  (:seon.schema.projection/compile-options projection)
                  :seon.program/predicate-functions
                  (schema/predicate-functions-in projection)
                  :seon.program/schema-keys
                  (set (keys (:seon.schema.projection/forms projection)))}))
              row)]
    (assoc row :seon.program/definition-digest
           (db/definition-digest-of projection row digest-namespace))))

(defn- admit-program-change!
  "The one definition authority for one evaluated form. Every definition
  (`program-definitions`) matches exactly one analyzed row; a stored row's Var
  is removed only by the form's own top-level `ns-unmap`. A form carries one
  declaration: several, or one beside a removal, refuse as
  `:seon.sci.eval/multiple-declarations-error`; a change its analysis does not
  declare refuses as `:seon.sci.eval/unindexed-change-error`; an uncontracted
  function as `:seon.sci.eval/declaration-absent-error`. The names an earlier
  child of the same top-level `do` declared (`:seon.sci.eval/declared`) count
  toward the one declaration. `program-change`
  decides the match by provenance. Contracts are validated and armed inside
  this boundary, and any refusal or throw restores the whole change before it
  leaves. Answers the admitted rows."
  {:malli/schema [:=> [:cat :seon.sci.eval/admission-request] [:vector :map]]}
  [{ctx :seon.sci.eval/ctx database :seon.db/db current :seon.sci.eval/current-db
    projection :seon.schema/projection source :seon.cluster.eval/source
    start :seon.sci.eval/form-start
    before :seon.sci.eval/before-owned before-namespaces :seon.sci.eval/before-namespaces
    reader-row :seon.sci.eval/reader-row namespace-row :seon.sci.eval/namespace-row
    restore! :seon.sci.eval/restore
    namespace-unmap? :seon.sci.eval/namespace-unmap?
    earlier :seon.sci.eval/declared}]
  (try
    (let [definitions (program-definitions before-namespaces before (turn-interns ctx))
          {matched ::matched unindexed ::unmatched declared ::declared}
          (if (seq definitions)
            (program-change definitions
                            (analyzed-rows database projection source namespace-row)
                            source start)
            {})
          declared (cond-> (into (vec earlier) declared)
                     reader-row (conj (program-identity-name reader-row)))
          removed (when-not namespace-unmap?
                    (into [] (comp (map (fn [[n i _]] (symbol (str n) (str i))))
                                   (filter #(row-backed? current %)))
                          (removed-interns before-namespaces (:namespaces @(:env ctx)))))
          refuse! #(throw (ex-info (:seon.error/message %) %))]
      (cond
        (seq unindexed) (refuse! (unindexed-change unindexed))
        (or (< 1 (count declared)) (and (seq declared) (seq removed)))
        (refuse! (multiple-declarations declared removed))
        (seq removed) (refuse! (unindexed-change removed)))
      (let [rows (mapv #(definition-row database projection namespace-row
                                        (::row %) (::var %))
                       matched)]
      (when-let [missing (first (sort-by str (for [row rows
                                                   :when (and (:seon.fn/sym row)
                                                              (not (:seon.fn/spec row))
                                                              (not (:seon.fn/macro? row))
                                                              (not (:seon.fn/host-bound? row)))]
                                               (:seon.fn/sym row))))]
        (refuse! (missing-contract missing)))
      ;; Arming belongs to the live Var, not to durability: an evaluation that
      ;; never commits must refuse invalid input all the same. The commit's
      ;; later install re-wraps the interpreted original idempotently.
      (doseq [row rows :when (:seon.fn/spec row)]
        (install-function-contract! ctx row projection database))
      rows))
    (catch Throwable failure
      (if-let [unavailable (restore! nil failure)]
        (throw (ex-info (:seon.error/message unavailable) unavailable failure))
        (throw failure)))))

(defn- unadmitted-notice
  "The loud line for the functions and tests a failed form defined: a failed
  evaluation admits nothing, so each stays a private definition of this
  context with no program row. Nil when it defined none."
  {:malli/schema [:=> [:cat [:maybe [:map-of :qualified-symbol :map]]] [:maybe :string]]}
  [definitions]
  (when-let [names (seq (sort-by str (keep (fn [[qualified entry]]
                                             (when (program-value? entry) qualified))
                                           definitions)))]
    (str ";; NOT ADMITTED: this form failed, so " (str/join ", " names)
         " stayed a private definition with no program row (no tests, no merge)."
         " Evaluate the definition again once its form succeeds.")))

(defn- top-level-do?
  "Whether `form` is a `do`, whose children a top-level evaluation treats as
  top-level forms: Clojure's `Compiler/eval` (`reference-code/clojure/src/jvm/
  clojure/lang/Compiler.java:7742-7748`) and SCI's `eval-form*`
  (`reference-code/sci/src/sci/impl/interpreter.cljc:34-43`) both unroll it."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [form]
  (and (seq? form) (= 'do (first form))))

(defn- top-level-evaluation
  "Evaluate `form` as a top-level form, in order: a `do`'s children are each
  top-level; any other form runs through `eval!` and then the one definition
  authority, `admit!`, over what it changed since its own `capture!`, after
  the names earlier children declared. A later child therefore calls an
  earlier child's admitted, armed definition, as two top-level forms would.
  Folds into `folded` (start with no rows and no names) the last value
  (`(do)` is nil), the admitted rows and the names declared."
  {:malli/schema
   [:=> [:cat
         [:map [:seon.sci.eval/eval! [:=> [:cat :any] :any]]
          [:seon.sci.eval/capture! [:=> [:cat] :seon.sci.eval/capture]]
          [:seon.sci.eval/admit! [:=> [:cat :seon.sci.eval/capture [:vector :seon.sci.eval/program-name]]
                                  [:maybe [:vector :map]]]]]
         [:map [:seon.sci.eval/value {:optional true} :any]
          [:seon.sci.eval/rows [:vector :map]]
          [:seon.sci.eval/declared [:vector :seon.sci.eval/program-name]]]
         :any]
    [:map [:seon.sci.eval/value :any]
     [:seon.sci.eval/rows [:vector :map]]
     [:seon.sci.eval/declared [:vector :seon.sci.eval/program-name]]]]}
  [{eval! ::eval! capture! ::capture! admit! ::admit! :as stages} folded form]
  (if (top-level-do? form)
    (reduce #(top-level-evaluation stages %1 %2) (assoc folded ::value nil) (rest form))
    (let [capture (capture!)
          value (eval! form)
          rows (vec (admit! capture (::declared folded)))]
      (cond-> (assoc folded ::value value ::rows (into (::rows folded) rows))
        (seq rows) (update ::declared conj (program-identity-name (first rows)))))))

(defn- bindings
  [ctx _namespace-name before _source _form _observed-built-in-calls]
  (into {}
        (keep (fn [[qualified {sci-var :seon.sci.eval/var
                              value :seon.sci.eval/value}]]
                (when-not (or (identical? absent-intern value)
                              (same-intern-value?
                               (get before qualified absent-intern) value))
                  [qualified {:seon.sci.eval/value value
                              :seon.sci.eval/metadata
                              (dissoc (meta sci-var) :sci/generation)}])))
        (turn-interns ctx)))

(defn- deleted-schema-key
  [row]
  (some (fn [[identity-attribute identity-value]]
          (when (= :seon.schema/key identity-attribute)
            identity-value))
        (:seon.program/delete-identities row)))

(defn- reader-context
  "Project SCI's namespace-in-effect into the one reader's own context.

  A run is a REPL reduce: executing `require` mutates SCI's namespace table,
  and the following form must be read with those exact aliases and refers.
  Re-reading with only the namespace name loses declaration identity before
  admission.  The table is SCI's existing per-ctx state, not a second registry
  (`reference-code/sci/src/sci/impl/namespaces.cljc:488-554`)."
  [ctx namespace-name]
  (let [{:keys [aliases imports refers requires]}
        (sci/namespace-bindings ctx namespace-name)]
    {:seon.sci.reader/ns namespace-name
     :seon.sci.reader/aliases aliases
     :seon.sci.reader/refers refers
     ::imports imports
     ::requires requires}))

(defn- one-event
  [source namespace-name ctx max-source]
  (let [events (sci/binding [sci/ns (sci/create-ns namespace-name)]
                 ;; SCI resolves `::alias/k` and syntax-quote from this
                 ;; ctx's namespace table (sci/impl/parser.cljc:60-167).
                 (reader/read
                  (assoc (reader-context ctx namespace-name)
                         :seon.sci.reader/ctx ctx
                         :seon.sci.reader/text source
                         :seon.config.eval.result/max-source max-source)))]
    (cond
      (map? events)
      (throw (ex-info (:seon.error/message events) events))

      ;; The reader recovers around a malformed span and returns it as an
      ;; event carrying its flat error with a placeholder form. Evaluation
      ;; refuses the whole input on its first such event, even after a
      ;; complete form: the placeholder must never run, and a request with
      ;; an unmatched delimiter is not wholly a form.
      (some :seon.sci.reader/error events)
      (let [failure (some :seon.sci.reader/error events)]
        (throw (ex-info (:seon.error/message failure) failure)))

      (= 1 (count events))
      (first events)

      :else
      ;; The refused member is the source; the offending value is the source
      ;; itself when empty, else the second form's, whose reader position
      ;; the message names.
      (let [{:seon.sci.reader/keys [line column] :as second-event} (second events)
            message (if second-event
                      (str "Evaluation accepts exactly one form; the second form starts at line "
                           line ", column " column ". Wrap sequences in `(do ...)`.")
                      "Your reply had no form; only comments/prose. Send a form.")]
        (throw (ex-info message
                        (seon.error.refusal/diagnostic
                         (java.util.Date.) :seon.sci.eval/reader 'seon.sci.eval/one-event
                         (cond-> {:seon.error/message message
                                  ::reader-event-count (count events)
                                  :seon.error/member :seon.cluster.eval/source
                                  :seon.error/expected ::one-reader-event
                                  :seon.error/offending (get second-event :seon.sci.reader/source source)}
                           second-event
                           (assoc :seon.error/fix "Wrap sequences in `(do ...)`."
                                  :seon.error/data (select-keys second-event [:seon.sci.reader/line
                                                                             :seon.sci.reader/column
                                                                             :seon.sci.reader/source]))))))))))

(defn bind-result!
  "Bind one evaluation's value in the fork under its own handle.

  The handle is the evaluation's identity as a symbol (`admit/result-handle`),
  never its ordinal: ordinals restart at 0 in every run, so an ordinal handle
  named two different values in one agent's context and a later turn could
  read the wrong one. Any valid symbol is admissible; this binds the name in
  the `result` namespace the handle qualifies."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "SCI result bindings retain the actual arbitrary result object, including nil, without serialization.", :gen/elements [nil false 0 "" :k [] {}]}]] :qualified-symbol]}
  [ctx handle value]
  (let [namespace-name 'result
        intern-name (symbol (name handle))]
    (when-let [objects (::result-objects ctx)]
      (swap! objects assoc (subs (name handle) 1) value))
    (when-not (sci/find-ns ctx namespace-name)
      (sci/add-namespace! ctx namespace-name {}))
    (sci/intern ctx namespace-name intern-name value)
    handle))

(defn- binding-rows
  "Project SCI's effective resolver inputs into namespace components."
  [{aliases :seon.sci.reader/aliases
    imports ::imports
    refers :seon.sci.reader/refers
    requires ::requires}]
  {:seon.ns/requires
   (set requires)
   :seon.ns/aliases
   (into #{}
         (map (fn [[local target-ns]]
                {:seon.ns.alias/local local
                 :seon.ns.alias/target-ns target-ns}))
         aliases)
   :seon.ns/imports
   (into #{}
         (map (fn [[local target-class]]
                (cond-> {:seon.ns.import/local local}
                  target-class
                  (assoc :seon.ns.import/target-class target-class))))
         imports)
   :seon.ns/refers
   (into #{}
         (keep (fn [[local target]]
                 (when-let [target-ns (some-> target namespace symbol)]
                   {:seon.ns.refer/local local
                    :seon.ns.refer/target-ns target-ns
                    :seon.ns.refer/target-name (symbol (name target))})))
         refers)})

(defn- row-bindings
  [row]
  {:aliases
   (into {}
         (map (juxt :seon.ns.alias/local :seon.ns.alias/target-ns))
         (:seon.ns/aliases row))
   :imports
   (into {}
         (map (juxt :seon.ns.import/local
                    :seon.ns.import/target-class))
         (:seon.ns/imports row))
   :refers
   (into {}
         (map (fn [{:seon.ns.refer/keys [local target-ns target-name]}]
                [local (symbol (str target-ns) (str target-name))]))
         (:seon.ns/refers row))
   :requires
   (set (:seon.ns/requires row))})

(defn- program-row-identity
  "The declared identity carried by one program row, or nil."
  [row]
  (some (fn [attribute]
          (when-some [value (get row attribute)]
            [attribute value]))
        (conj program/identity-attributes
              :seon.program/delete-identities)))

(defn- namespace-source
  "The `ns` form a namespace declares after the REPL form `form` changed its
  context: a `require`'s new libspecs spliced into the `:require` clause of the
  namespace's stored `ns` form, each on its own line aligned with the clause
  (edamame's locations; the rest of the text stays exact); a new `ns` form when
  none is stored. Any other form keeps the stored `ns` form. Never the REPL
  form itself."
  {:malli/schema [:=> [:cat [:maybe :seon.db/database-value] :seon.ns/name :seon.schema/value] :seon.ns/source]}
  [database namespace-name form]
  (let [text (some-> database (db/pull [:seon.ns/source] [:seon.ns/name namespace-name]) :seon.ns/source)
        declared (when text (edamame/parse-string text {:all true :read-cond :preserve}))
        declared? (and (seq? declared) (= 'ns (first declared)))
        clause (when declared? (some #(when (and (seq? %) (= :require (first %))) %) declared))
        libspecs (for [arg (rest form) :let [spec (if (and (seq? arg) (= 'quote (first arg))) (second arg) arg)]
                       :when (not (keyword? spec))
                       :when (not (some #{spec} (rest clause)))]
                   spec)
        ;; The character offset of edamame's 1-based row and column.
        offset (fn [row col] (+ (dec col) (reduce + (map #(inc (count %)) (take (dec row) (str/split-lines text))))))]
    (cond
      (not (and (seq? form) (#{'require 'clojure.core/require} (first form))))
      (if declared? text (pr-str (list 'ns namespace-name)))
      (not declared?) (str "(ns " namespace-name "\n  (:require " (str/join " " (map pr-str libspecs)) "))")
      :else
      (let [{:keys [end-row end-col col]} (meta (or clause declared))
            at (dec (offset end-row end-col))
            lines (if clause
                    (map #(str "\n" (apply str (repeat (+ col 9) \space)) (pr-str %)) libspecs)
                    [(str "\n  (:require " (str/join " " (map pr-str libspecs)) ")")])]
        (str (subs text 0 at) (apply str lines) (subs text at))))))

(defn- namespace-context-row
  [projection database namespace-name form before after changed?]
  (when (or changed?
            (not= (select-keys before [:seon.sci.reader/aliases
                                      :seon.sci.reader/refers
                                      ::imports ::requires])
                  (select-keys after [:seon.sci.reader/aliases
                                     :seon.sci.reader/refers
                                     ::imports ::requires])))
    (program/declaration-row
     projection
     (merge
      {:seon.ns/name namespace-name
       :seon.ns/source (namespace-source database namespace-name form)}
      (binding-rows after))
     :contracted :agent)))

(defn- context-projection
  "The latest schema projection held by one live cluster context."
  [ctx]
  (or (some-> (env/of ctx) :seon.schema/projection)
      (:seon.schema/projection ctx)))

(defn- evaluation-projection
  [{ctx :seon.sci.eval/ctx database :seon.db/db
    projection :seon.schema/projection}]
  (or (context-projection ctx)
      projection
      (when database (db/carried-projection database))
      (let [failure (db/projection-fallback 'seon.sci.eval/evaluate)]
        (throw (ex-info (:seon.error/message failure) failure)))))

(defn- advance-context-projection!
  "Advance a live context's projection at the database's basis transaction."
  [ctx db projection]
  (when-let [state (get ctx env/state-carrier)]
    (env/advance-projection! state (db/basis-t db) projection))
  projection)

(defn- database-effective-config
  "The selected cluster's effective config, or the refusal naming its absence.

  NEVER NIL, and never an invented cluster name. A database carrying no
  config singleton names no cluster, so there is no cluster whose effective
  config could be read and none to report: the answer is a flat refusal
  naming this function and the `:seon.boot/cluster-name` it wanted.
  `config/result-caps` and `seon.render/agent-render-profile` admit that
  shape alongside `:seon.config/missing-effective-error`; handing them the
  `nil` this used to produce violated their contracts at every SCI contract
  install."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or :seon.config/effective :seon.config/error]]}
  [db]
  (let [cluster-name
        (db/q '[:find ?cluster .
               :where
               [_ :seon.config/cluster ?cluster]]
             db)]
    (if cluster-name
      (config/effective db cluster-name)
      (seon.error.refusal/diagnostic (java.util.Date.) :seon.config/read 'seon.sci.eval/database-effective-config
       {:seon.config/error-key :seon.boot/cluster-name
       :seon.error/expected-key :seon.boot/cluster-name
       :seon.error/message
       "seon.sci.eval/database-effective-config requires configuration naming its cluster; this database has none."}))))

(defn- instrumentation-config
  "Read the evidence bound and admission caps from this database value.

  A refused read is a declared error value carrying its message; it is
  thrown whole rather than defaulted. The caps member is
  whatever `result-caps` returned; `instrument/wrap-interpreted` decides what
  to do with it."
  [db]
  (let [effective (database-effective-config db)]
    (when (:seon.error/message effective)
      (throw (ex-info (:seon.error/message effective) effective)))
    (merge (select-keys effective [:seon.config.error/max-evidence-bytes])
           {:seon.sci.admit/caps (config/result-caps effective)})))

(defn- install-function-contract!
  [ctx committed projection db]
  (when-let [spec-edn (:seon.fn/spec committed)]
    (let [function-symbol (:seon.fn/sym committed)
          sci-var (sci/resolve ctx function-symbol)
          {:keys [:seon.sci.admit/caps
                  :seon.config.error/max-evidence-bytes]}
          (instrumentation-config db)
          ;; The supplied-default rows this arming decides the plan from.
          current (call-preparation/snapshot db (or (db/carried-projection db) projection))
          _ (when (db/read-refusal? current)
              (throw (ex-info (:seon.error/message current) current)))]
      (sci/bind-root!
       ctx sci-var
       (instrument/wrap-interpreted
        function-symbol spec-edn projection caps @sci-var
        (cond-> (assoc (select-keys @(::kernel/program-snapshot ctx)
                                    [:seon.flow/commit-fault!])
                       :seon.call-preparation/snapshot current)
          max-evidence-bytes
          (assoc :seon.config.error/max-evidence-bytes max-evidence-bytes)
          ;; The profiling cell's identity is the row it installs.
          (:seon.program/definition-digest committed)
          (assoc :seon.program/definition-digest
                 (:seon.program/definition-digest committed)))))))
  nil)

(declare install-declared-classes! install-jvm-root! overridden-row?)

(defn- install-function-from-database!
  "Install one selected function from the acquired database snapshot. A definition
  the loaded program holds unchanged binds its compiled Var, exactly as acquisition
  binds a loaded namespace's, unless this context already defined the symbol itself;
  only an overridden or affected row interprets source."
  [ctx db function-symbol]
  (let [{source ::function-source
         namespace-name ::function-namespace}
        (kernel/program-function ctx function-symbol)
        {interpreted ::interpreted loaded ::loaded-database} @(::kernel/program-snapshot ctx)]
    (when-not source
      (throw
       (ex-info "Selected function is missing from the acquired SCI program snapshot."
                (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.eval/program 'seon.sci.eval/install-function-from-database!
                 {:seon.error/message "Selected function has no acquired row; acquire its declaration before installing it."
                  :seon.error/offending function-symbol
                  ::missing-function-row function-symbol
                  :seon.fn/sym function-symbol
                  :seon.error/expected :seon.sci.eval/acquired-function}))))
    ;; An uninstalled program symbol has no SCI entry, so one present is this
    ;; context's own definition, which survives its first install (AGENTS.md).
    (when-not (and (not (contains? interpreted function-symbol))
                   (not (overridden-row? loaded (db/pull db '[*] [:seon.fn/sym function-symbol])))
                   (or (when (contains? (get-in @(:env ctx) [:namespaces namespace-name])
                                        (symbol (name function-symbol)))
                         ;; Recorded so `acquire!` still carries it once installed.
                         (swap! (::kernel/program-snapshot ctx) assoc-in
                                [:functions function-symbol ::context-defined?] true))
                       (install-jvm-root! ctx function-symbol)))
    (let [namespace-row (kernel/program-namespace ctx namespace-name)]
      (install-declared-classes! ctx [namespace-row])
      (sci/install-namespace-bindings!
       ctx namespace-name (assoc (row-bindings namespace-row) :refers {}))
      (sci/install-namespace-bindings! ctx namespace-name
                                       (row-bindings namespace-row))
      (let [event (one-event source namespace-name ctx (count source))]
        (sci/binding [sci/ns (sci/create-ns namespace-name)]
          (sci/eval-form ctx (:seon.sci.reader/form event)))
        (install-function-contract!
         ctx (db/pull db '[*] [:seon.fn/sym function-symbol])
         (context-projection ctx) db))))
    (kernel/mark-installed! ctx function-symbol)
    function-symbol))

(def ^:private namespace-reference-attributes
  {:seon.fn/sym :seon.fn/ns
   :seon.test/sym :seon.test/ns})

(defn- remaining-definition-facts
  [db [identity-attribute identity-value :as program-identity]]
  (when-let [row (db/pull db '[*] program-identity)]
    (let [committed
          (dissoc row :db/id :seon.schema.admission/source)
          namespace-attribute
          (get namespace-reference-attributes identity-attribute)
          identity-row
          (cond-> {identity-attribute identity-value}
            (and namespace-attribute
                 (find committed namespace-attribute))
            (assoc namespace-attribute (get committed namespace-attribute)))
          attributes (program/changed-attributes committed identity-row)]
      (when (seq attributes)
        {:seon.program/identity program-identity
         :seon.program/definition-attributes attributes}))))

(defn- declaration-source-value
  "The VALUE a declaration's source attribute names, or the source itself.

  A source this reader cannot read as data is its own value, so the
  comparison below falls back to exactly the bytes it compared before."
  [source]
  (if (string? source)
    (try (edn/read-string source)
         (catch Exception _ source))
    source))

(defn- same-declaration-source?
  "Whether a request row's source names the declaration the database holds.

  THE WRITER CANONICALIZES BEFORE IT COMMITS. `seon.turn/row-tx` runs every
  reader row through `seon.program/declaration-row`, which rebuilds a schema
  row from `seon.schema/canonical-schema-rows` and RE-PRINTS its
  `:seon.schema/form`. A form carrying a namespaced property map is stored as
  `[:string #:seon.db{:identity true}]` while the request row still holds the
  reader's `[:string {:seon.db/identity true}]` — two printings of ONE value.
  Comparing those BYTES answered `false` for the cluster's own committed
  declaration, and since exactly the STORABLE declarations carry such a map
  (`:seon.db/identity`, `:seon.db/attributes`), every storable declaration was
  skipped by `install-evaluated-rows!` and dropped from the live projection
  while its facts were intact (2026-09-17, measured on `default`). A
  declaration IS a value, so the value decides."
  [requested committed]
  (or (= requested committed)
      (and (some? requested)
           (some? committed)
           (= (declaration-source-value requested)
              (declaration-source-value committed)))))

(defn committed-row?
  "True when `row` is the effective declaration in the terminal database.

  A turn may redefine or delete one identity more than once before its single
  settlement. Only the last effective row may advance the live SCI contexts;
  earlier rows remain truthful eval evidence but are superseded install work."
  {:malli/schema [:=> [:cat :seon.db/database-value :map] :boolean]}
  [db row]
  (let [[identity-attribute value] (program-row-identity row)]
    (if (= identity-attribute :seon.program/delete-identities)
      (not-any? #(remaining-definition-facts db %) value)
      (let [committed (db/pull db '[*] [identity-attribute value])
            source-attribute
            (:seon.program/source-attribute
             (program/shape
              (program/shapes-in
               (or (db/carried-projection db)
                   (schema/projection-from-database db)))
              identity-attribute))]
        (and (some? (:db/id committed))
             (same-declaration-source? (get row source-attribute)
                                       (get committed source-attribute)))))))

(defn- install-jvm-root!
  [ctx function-symbol]
  (when-let [host-namespace (find-ns (symbol (namespace function-symbol)))]
    (when-let [host-var (ns-resolve host-namespace (symbol (name function-symbol)))]
      (let [sci-namespace (sci/create-ns (symbol (namespace function-symbol)))]
        (sci/add-namespace! ctx (symbol (namespace function-symbol))
                            {(symbol (name function-symbol))
                             (copy-host-var host-var sci-namespace)})
        (kernel/mark-installed! ctx function-symbol)
        true))))

(defn- install-refusing-root!
  "Bind `function-symbol` to a function that throws `refusal` when called.

  Callers analyze against the Var; only a call that reaches it refuses, by name."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol :seon.sci.eval/interpretation-error]
                  [:= :unavailable]]}
  [ctx function-symbol refusal]
  (let [namespace-name (symbol (namespace function-symbol))
        var-name (symbol (name function-symbol))
        refused (fn refused [& _]
                  (throw (ex-info (:seon.error/message refusal) refusal)))]
    ;; A Var acquisition declared (or copied into this generation) keeps its
    ;; identity, so callers analyzed against it reach the refusal by name.
    (if-let [declared (get-in @(:env ctx) [:namespaces namespace-name var-name])]
      (sci/bind-root! ctx declared refused)
      (sci/add-namespace! ctx namespace-name
                          {var-name (sci/new-var var-name refused
                                                 {:ns (sci/create-ns namespace-name)})}))
    (kernel/mark-installed! ctx function-symbol)
    :unavailable))

(defn- interpretation-error
  "Refuse a row whose branch definition cannot execute in this context."
  {:malli/schema [:=> [:cat :qualified-symbol :string]
                  :seon.sci.eval/interpretation-error]}
  [sym reason]
  (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.eval/program 'seon.sci.eval/install-row!
   {:seon.error/message (str "Cannot interpret " sym ": " reason)
   :seon.error/expected :seon.sci.eval/interpretable-definition
   :seon.sci.eval/refused-function sym
   :seon.sci.eval/interpretation-reason reason}))

(defn- interpretation-failure
  "Refuse a row whose interpretation threw, carrying the whole cause: class,
  root frame and chain (`seon.error.refusal/chain`), each link's message and ex-data."
  {:malli/schema [:=> [:cat :qualified-symbol :seon.error/throwable]
                  :seon.sci.eval/interpretation-error]}
  [sym ^Throwable failure]
  (let [frame (error.refusal/root-frame failure)]
    (cond-> (assoc (interpretation-error sym (or (ex-message failure) (.getName (class failure))))
                   :seon.error/exception-class (symbol (.getName (class failure)))
                   :seon.error/chain (error.refusal/chain failure))
      frame (assoc :seon.error/frame frame))))

;;; Loaded Var roots have no database owner: this value names the JVM's loaded
;;; program. Development adoption replaces it with its unknown-program
;;; diagnostic before touching a root and with `{:seon.source/commit-id}` once its
;;; record commits; a failed adoption leaves the diagnostic. Nothing waits on it.
(defonce loaded-identity (atom {}))

(def ^:dynamic ^:private *expected-identities*
  "This thread's detecting executions, innermost first, each an identity atom
  with the value it expects at its end: its own adoptions advance them, so only
  another thread's change (another member's included) reads as one."
  ())

(defn advance-loaded-identity!
  "Set `identity-atom` to `value`, carrying this thread's expectations along."
  {:malli/schema [:=> [:cat :seon.sci.eval/loaded-identity :map] :map]}
  [identity-atom value]
  (let [[old] (reset-vals! identity-atom value)]
    (doseq [[held expected] *expected-identities*
            :when (and (identical? held identity-atom) (identical? old @expected))]
      (vreset! expected value))
    value))

(defn- identity-name
  {:malli/schema [:=> [:cat :map] :string]}
  [loaded]
  (if (:seon.error/at loaded)
    (str "unknown (" (:seon.error/message loaded) ")")
    (str (:seon.source/commit-id loaded "the booted program"))))

(defn call-detecting-program-change
  "Call `work`, answering its result only when the loaded program did not change.

  `identity-atom` is read when `work` starts and when it ends; `since` is the
  identity the caller already committed to (an admitted request's), else the
  start read. An unknown program at start or end answers its diagnostic, and
  at start `work` never runs. A change by another thread since `since` or an
  enclosing execution's start answers the refusal naming both identities
  instead of the result. This thread's own adoptions advance its expectations
  (`advance-loaded-identity!`)."
  {:malli/schema [:function
                  [:=> [:cat :seon.sci.eval/loaded-identity :qualified-symbol [:=> [:cat] :any]] :any]
                  [:=> [:cat :seon.sci.eval/loaded-identity :qualified-symbol :map [:=> [:cat] :any]] :any]]}
  ([identity-atom operation work]
   (call-detecting-program-change identity-atom operation @identity-atom work))
  ([identity-atom operation since work]
   (let [before @identity-atom
         enclosing (some (fn [[held expected]] (when (identical? held identity-atom) @expected))
                         *expected-identities*)
         expected (volatile! before)
         changed (fn [from to]
                   (error.refusal/diagnostic
                    (java.util.Date.) :seon.cluster/adoption operation
                    {:seon.error/message (str "The loaded program changed during this execution, from "
                                              (identity-name from) " to " (identity-name to)
                                              "; its result is not trusted. Rerun it.")
                     :seon.test/not-runnable (str (identity-name from) " -> " (identity-name to))}))]
     (cond
       (:seon.error/at before) before
       (not (identical? since before)) (changed since before)
       (and enclosing (not (identical? enclosing before))) (changed enclosing before)
       :else (let [result (binding [*expected-identities* (cons [identity-atom expected] *expected-identities*)]
                            (work))
                   after @identity-atom]
               (cond (:seon.error/at after) after
                     (identical? @expected after) result
                     :else (changed @expected after)))))))

(defn- loaded-source
  "The database whose adoption record names the JVM's loaded source commit.

  The loaded namespaces are one per JVM, so this is the adopting cluster's live
  connection (`::loaded-connection`, which a fork inherits), read now: its
  `:seon.source/commit-id` moves when `seon.cluster/development-source-refresh!`
  has reloaded and armed. A branch's own record is its branch-point value and
  stays behind a later adoption. A context handed no connection reads `database`."
  {:malli/schema [:=> [:cat :map :seon.db/database-value] :seon.db/database-value]}
  [holder database]
  (if-let [connection (::loaded-connection holder)] (db/db connection) database))

(defn- loaded-commit-id
  "The source commit the JVM loaded, as the adoption record in `database` names it.
  A `current-src` value without a record is that commit itself."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.source/commit-id]}
  [database]
  (let [commits (db/q '[:find [?commit ...]
                        :where [_ :seon.source/commit-id ?commit]] database)]
    (cond
      (= 1 (count commits)) (first commits)
      (= :current-src (get-in database [:config :branch])) (db/commit-id database)
      :else (throw (ex-info "The JVM's loaded source commit is unknown."
                            {:seon.error/expected :seon.source/commit-id
                             :seon.error/offending commits})))))

(defn- loaded-program
  "Materialize the source commit the JVM loaded (`loaded-commit-id` of `database`)."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.db/database-value]}
  [database]
  (let [commit (loaded-commit-id database)]
    (if (= commit (db/commit-id database))
      database
      (or (some-> (d/commit-as-db database commit {:secondary-indices? false})
                  (vary-meta assoc :seon.schema/projection (db/carried-projection database)))
          (throw (ex-info "The JVM's recorded source commit is unavailable."
                          {:seon.source/commit-id commit}))))))

(defn- row-definition-digest
  {:malli/schema [:=> [:cat :map] :seon.program/definition-digest]}
  [row]
  (or (:seon.program/definition-digest row) (program/definition-digest row)))

(defn- function-digests
  "Read stored definition identities in one query; only legacy rows need a pull."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:map-of :qualified-symbol :seon.program/definition-digest]]}
  [database]
  (let [rows (db/q '[:find ?sym ?digest
                     :where [?row :seon.fn/sym ?sym]
                            [(get-else $ ?row :seon.program/definition-digest "") ?digest]]
                   database)]
    (when (:seon.db/invalid-read rows)
      (throw (ex-info "Definition identities could not be read." rows)))
    (into {} (map (fn [[sym digest]]
                    [sym (if (seq digest) digest
                           (row-definition-digest (db/pull database '[*] [:seon.fn/sym sym])))]))
          rows)))

(defn- overridden-row?
  "Whether `row` is this program's own change to the loaded program: a row an
  agent authored (`:seon.schema.admission/source :agent`) that the loaded
  program lacks or holds at another digest. A file-derived row that differs
  was published, not authored here: the branch is behind the loaded program,
  whose compiled Var it runs."
  {:malli/schema [:=> [:cat [:maybe :seon.db/database-value] :map] :boolean]}
  [loaded row]
  (let [previous (when loaded (db/pull loaded '[*] [:seon.fn/sym (:seon.fn/sym row)]))]
    (and (= :agent (:seon.schema.admission/source row))
         (or (nil? (:seon.fn/sym previous))
             (not= (row-definition-digest row) (row-definition-digest previous))))))

(defn- host-bound-row?
  "Whether the indexer's per-declaration host-binding fact holds for this row.

  `seon.fn` derives `:seon.fn/host-bound?` from the defining form and the
  declaration's own analyzed class and form usages; callers inherit nothing.
  A function row without the fact was not analyzed by that producer, so its
  binding is unknown and it is treated as host-bound: an interpretation is never
  granted on silence."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [row]
  (not (false? (get row :seon.fn/host-bound?))))

(defn install-row!
  "Install one declaration from the terminal transaction's db-after.
  The exact committed row is resolved by identity; saved evaluation results
  are never consulted."
  {:malli/schema [:=> [:cat :seon.sci.eval/install-request]
                  [:or :seon.sci.eval/install-result :seon.sci.eval/interpretation-error]]}
  [{ctx :seon.sci.eval/ctx
    db :seon.db/db
    row :seon.program/row
    evaluated? ::evaluated?
    prepared-projection ::prepared-projection}]
  (let [projection (or prepared-projection
                       (context-projection ctx)
                       (schema/projection-from-database db))
        [identity-attribute value] (program-row-identity row)
        committed (when-not (= identity-attribute
                               :seon.program/delete-identities)
                    (db/pull db
                            (if (= identity-attribute :seon.ns/name)
                              '[* :seon.ns/requires
                                  {:seon.ns/aliases [*]}
                                  {:seon.ns/imports [*]}
                                  {:seon.ns/refers [*]}]
                              '[*])
                            [identity-attribute value]))]
    (when (and (#{:seon.fn/sym :seon.test/sym} identity-attribute)
               (let [source-attribute
                     (:seon.program/source-attribute
                      (program/shape
                       (program/shapes-in projection)
                       identity-attribute))]
                 (not (same-declaration-source?
                       (get row source-attribute)
                       (get committed source-attribute)))))
      (throw (ex-info "Committed declaration source does not match install request."
                      (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.eval/program 'seon.sci.eval/install-row!
                       {:seon.error/message "Install source differs from the committed declaration; install the committed source."
                        :seon.error/offending row
                        :seon.sci.eval/installation-member value
                        :seon.error/expected :seon.sci.eval/committed-source
                        :seon.error/member identity-attribute}))))
    (let [installed
          (case identity-attribute
      :seon.ns/name
      (let [namespace-name (:seon.ns/name committed)]
        (sci/install-namespace-bindings!
         ctx namespace-name (row-bindings committed))
        {:seon.schema/projection projection
         :seon.sci.eval/installed 1})

      :seon.fn/sym
      (let [function-symbol (:seon.fn/sym committed)
            namespace-name (symbol (namespace function-symbol))
            admission (:seon.schema.admission/source committed)
            next-projection (or prepared-projection
                                (schema/projection-from-database db projection))]
        (kernel/cache-function!
         ctx function-symbol
         {::function-source (:seon.fn/source committed)
          ::function-namespace namespace-name
          ::function-private? (:seon.fn/private? committed)
          ::function-admission admission
          ::agent-authored? (= :agent admission)})
        (let [snapshot @(::kernel/program-snapshot ctx)
              interpreted? (or (get (::interpreted snapshot) function-symbol)
                               (overridden-row? (::loaded-database snapshot) committed))
              host-row (when interpreted?
                         (if (host-bound-row? committed)
                           committed
                           (when-let [loaded (::loaded-database snapshot)]
                             (let [previous (db/pull loaded '[*] [:seon.fn/sym function-symbol])]
                               (when (and previous (host-bound-row? previous)) previous)))))
              ;; A host-bound row overrides a compiled Var only when its
              ;; namespace is loaded and its own definition differs. An
              ;; unchanged caller of a changed definition, or a row whose
              ;; namespace this JVM never loaded, has no Var to override: it
              ;; refuses by name when called instead of refusing the program.
              ;; Agent rows and evaluations keep the returned refusal.
              ;; An unchanged caller in an unloaded namespace has neither a
              ;; compiled Var nor its namespace's other interns to analyze
              ;; against, so it refuses by name when called too.
              unloaded? (nil? (find-ns namespace-name))
              unbound? (and interpreted?
                            (= :core (:seon.schema.admission/source committed))
                            (not (or evaluated? (::evaluated? row)))
                            (if host-row
                              (or unloaded?
                                  (not (overridden-row? (::loaded-database snapshot) committed)))
                              (and unloaded?
                                   (not (overridden-row? (::loaded-database snapshot) committed)))))
              evaluated-row? (boolean (or evaluated? (::evaluated? row)))
              refusal (when (or host-row unbound?)
                        (interpretation-error function-symbol
                          (str (cond (nil? host-row) "Declaration "
                                     (contains? host-row :seon.fn/host-bound?) "Host-bound declaration "
                                     :else "Declaration with no :seon.fn/host-bound? fact ")
                               (:seon.fn/sym (or host-row committed))
                               (cond unloaded? " is in a namespace this JVM has not loaded; load its source files to call it."
                                     unbound? " is unchanged but calls a definition this branch changes; it runs only from the loaded source files."
                                     :else " must change through the loaded source files."))))
              ;; A row acquired from the database refuses by name only when
              ;; called: the rest of the program and every later form keep
              ;; working. An agent's evaluation keeps the returned refusal.
              result
              (cond
                (and refusal (not evaluated-row?)) (install-refusing-root! ctx function-symbol refusal)
                refusal refusal
                :else
                  (if-not interpreted?
                    (if (install-jvm-root! ctx function-symbol) :jvm :unavailable)
                    (try
                      (if (or evaluated? (::evaluated? row))
                        (do
                          (kernel/mark-installed! ctx function-symbol)
                          (when (:seon.fn/spec committed)
                            (install-function-contract! ctx committed next-projection db)))
                        (install-function-from-database! ctx db function-symbol))
                      :interpreted
                      (catch Throwable failure
                        ;; An unarmable contract or an unreadable cluster
                        ;; config is not this row's fault: it refuses the
                        ;; acquisition instead of blaming the row.
                        (when (let [refusal (error/refusal failure)]
                                (or (:seon.instrument/registration-observation refusal)
                                    (:seon.config/error-key refusal)))
                          (throw failure))
                        (interpretation-failure function-symbol failure)))))]
          (if (map? result)
            (do (sci/eval-form ctx (list 'ns-unmap (list 'quote namespace-name)
                                       (list 'quote (symbol (name function-symbol)))))
                result)
            {:seon.schema/projection next-projection
             :seon.sci.eval/installed (if (= :unavailable result) 0 1)
             :seon.sci.eval/load-state result
             :seon.sci.eval/load-result
             (cond-> {:seon.sci.eval/load-state result :seon.fn/sym function-symbol}
               refusal (assoc :seon.error/message (:seon.error/message refusal)))})))

      :seon.schema/key
      {:seon.schema/projection
       (or prepared-projection
           (schema/projection-from-database db projection))
       :seon.sci.eval/installed 1}

      :seon.test/sym
      (let [namespace-name (second (:seon.test/ns row))
            event (when-not (or evaluated? (::evaluated? row))
                    (one-event (:seon.test/source committed)
                               namespace-name ctx
                               (count (:seon.test/source committed))))]
        (when event
          (sci/binding [sci/ns (sci/create-ns namespace-name)]
            (sci/eval-form ctx (:seon.sci.reader/form event))))
        {:seon.schema/projection projection
         :seon.sci.eval/installed 1})

      :seon.program/delete-identities
      (let [schema-deletion? (boolean (deleted-schema-key row))
            namespace-name (second (:seon.program/ns row))]
        (when-let [remaining
                   (some #(remaining-definition-facts db %) value)]
          (throw (ex-info "Deleted declaration definition facts remain after commit."
                          {:seon.error/at (java.util.Date.)
                            :seon.error/layer :seon.sci.eval/program
                            :seon.error/operation 'seon.sci.eval/install-row!
                            :seon.error/message "Deleted declaration still has definition facts; commit its retraction before installation."
                            :seon.error/offending remaining
                            :seon.sci.eval/installation-member (second (:seon.program/identity remaining))
                            :seon.error/member :seon.program/definition-attributes
                            :seon.error/expected :seon.sci.eval/retracted-declaration})))
        (when (and (not schema-deletion?)
                   (nil? (::namespace-state row)))
          (let [event (one-event (:seon.program/source row)
                                 namespace-name ctx
                                 (count (:seon.program/source row)))]
            (sci/binding [sci/ns (sci/create-ns namespace-name)]
              (sci/eval-form ctx (:seon.sci.reader/form event)))))
        {:seon.schema/projection
         (or prepared-projection
             (schema/projection-from-database db projection))
         :seon.sci.eval/installed 1})
      {:seon.schema/projection projection
       :seon.sci.eval/installed 0})]
      ;; Transfer only the namespace operation's changed entries. Copying its
      ;; whole namespace table would also publish unrelated private bindings.
      ;; The operation's resolved targets remain exact without replaying source.
      (when-let [namespace-state (::namespace-state row)]
        (swap! (:env ctx)
               (fn [environment]
                 (reduce (fn [environment [namespace-name binding-name value]]
                           (if (identical? absent-intern value)
                             (update-in environment [:namespaces namespace-name]
                                        dissoc binding-name)
                             (assoc-in environment
                                       [:namespaces namespace-name binding-name]
                                       value)))
                         environment namespace-state))))
      (when-let [installed-projection (:seon.schema/projection installed)]
        (advance-context-projection! ctx db installed-projection))
      installed)))

(defn- evaluate-native!
  "Execute an already-admitted program operation in its supplied SCI context.
   Only this operation's native mutation skips the program-fact refusal
   (`native-mutation`), never an agent evaluation."
  [ctx form]
  (kernel/with-world-ctx (assoc ctx ::native-operation? true)
    #(sci/eval-form ctx form)))

(defn- transfer-evaluated-roots!
  [ctx installations]
  (let [roots (into {} (mapcat (comp :seon.sci.eval/bindings
                                    :seon.sci.eval/evaluation)) installations)]
    (doseq [qualified (into #{} (keep #(some-> % str symbol))
                           (mapcat (comp (juxt :seon.fn/sym :seon.test/sym)
                                         :seon.program/row) installations))
            :let [{value :seon.sci.eval/value metadata :seon.sci.eval/metadata}
                  (get roots qualified)]
            :when (get roots qualified)]
      (let [namespace-name (symbol (namespace qualified))]
        (when-not (sci/find-ns ctx namespace-name)
          (sci/add-namespace! ctx namespace-name {}))
        (let [installed (sci/intern ctx namespace-name
                                    (with-meta (symbol (name qualified)) metadata) value)]
          ;; SCI intern replaces an existing root without adopting symbol
          ;; metadata. The evaluated :test body and contract belong to the
          ;; replacement too; retain only the destination's generation/ns.
          (reset-meta! installed
                       (merge metadata (select-keys (meta installed)
                                                    [:sci/generation :ns]))))))))

(declare base-bindings acquired-database?)

(defn acquired-program
  "Return the database and refusal report actually acquired by this context.

  Evidence, including :seon.sci.eval/interpreted-count, follows the existing
  program snapshot through reacquisition and forks. A resolvable Var alone is not evidence of an acquired program."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :seon.test/acquisition]}
  [ctx]
  (let [snapshot (some-> (::kernel/program-snapshot ctx) deref)]
    (assoc (select-keys snapshot [:seon.db/db :seon.test/class-loader])
           :seon.sci.eval/interpreted-count
           (get-in snapshot [::acquisition :seon.sci.eval/interpreted-count] 0)
           :seon.test/acquisition-refusals
           (vec (get-in snapshot [::acquisition ::acquisition-refusals])))))

(defn- installation-covers-program-change?
  "True when everything the settlement `report` changed since the context's
  acquired program is one of `installations` or leaves its program row
  canonically equal.

  Three facts, each from its owner: `written-on`, the committed value the
  write started from, is the report's `:db-before` (equal basis on one branch:
  no commit between them); the context acquired `written-on`'s program
  (`acquired-database?`, which follows a branch to its parent's recorded
  basis); and the report's `:db-after` ends at its own transaction (Datahike's
  writer hands every report of a batch the batch's final value,
  datahike/writer.cljc:229-264). Then the report's datoms name every change,
  and each changed component's owning parents join through the history index
  from those few children. Anything else is not covered: the next acquisition
  derives from the head, as for any unknown change. Cost: proportional to the
  settlement's datoms, never to the store's history (task 361088de3d56)."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :seon.db/transaction-report
                       [:sequential [:map [:seon.program/row :seon.program/row]]] :seon.schema/projection]
                  [:or :nil :boolean]]}
  [ctx written-on report installations projection]
  (when (and (= (db/basis-t written-on) (db/basis-t (:db-before report)))
             (= (get-in written-on [:config :branch]) (get-in report [:db-before :config :branch]))
             (= (db/basis-t (:db-after report)) (:tx (first (:tx-data report))))
             (acquired-database? ctx written-on))
    (let [before written-on
          after (:db-after report)
          identities (set (map (comp program/row-identity :seon.program/row)
                               installations))
          shapes (program/shapes-in projection)
          history (db/history after)
          components (into []
                           (keep (fn [[attribute properties]]
                                   (when (:db/isComponent properties) attribute)))
                           (:schema (db/schema-database after)))
          touched (into #{} (map :e) (:tx-data report))
          parents (loop [frontier (vec touched) seen #{}]
                    (if (or (empty? frontier) (empty? components))
                      seen
                      (let [incoming (db/q '[:find [?parent ...]
                                             :in $ [?child ...] [?attribute ...]
                                             :where [?parent ?attribute ?child]]
                                           history frontier components)]
                        (when-not (or (:seon.db/invalid-read incoming) (:seon.schema/expected-value incoming))
                          (recur (vec (remove seen incoming)) (into seen incoming))))))]
      (when parents
        (let [eids (vec (into touched parents))
              old (db/pull-many before '[*] eids)
              current (db/pull-many after '[*] eids)]
          (and (not (or (:seon.db/invalid-read old) (:seon.schema/expected-value old)))
               (not (or (:seon.db/invalid-read current) (:seon.schema/expected-value current)))
               (every?
                (fn [[eid old-row current-row]]
                  (let [identity (or (program/row-identity current-row)
                                     (program/row-identity old-row))]
                    (or (nil? identity)
                        (contains? identities identity)
                        (and (not (contains? parents eid))
                             (= (program/canonical-row shapes old-row)
                                (program/canonical-row shapes current-row))))))
                (map vector eids old current))))))))

(defn install-evaluated-rows!
  "Install committed rows from the evaluations that produced them.

  The turn fork already owns the exact evaluated Var roots. Transfer the
  complete batch into the live base before advancing any database-derived
  projection; installing one root at a time cannot resolve a same-turn test's
  reference to a same-turn function, while replaying source would execute the
  definitions twice after settlement."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.sci.eval/ctx :seon.sci.eval/ctx]
      [:seon.db/db :seon.db/database-value]
      [:seon.db/transaction-report {:optional true} :seon.db/transaction-report]
      ;; the committed value the report's write started from, on its connection
      [:seon.sci.eval/written-on {:optional true} :seon.db/database-value]
      [:seon.sci.eval/agent-ctx {:optional true} :seon.sci.eval/ctx]
      [:seon.sci.eval/installations
       [:vector
        [:map
         [:seon.program/row :seon.program/row]
         [:seon.sci.eval/evaluation :seon.sci.eval/evaluation]]]]]]
    [:vector :map]]}
  [{ctx :seon.sci.eval/ctx
    db :seon.db/db
    installations :seon.sci.eval/installations
    report :seon.db/transaction-report
    written-on :seon.sci.eval/written-on
    agent-ctx :seon.sci.eval/agent-ctx}]
  (transfer-evaluated-roots! ctx installations)
    (let [installed (:installed
     (reduce
      (fn [{projection :projection installed :installed}
           {program-row :seon.program/row}]
        (let [next-projection
              (cond
                (:seon.schema/key program-row)
                (schema/projection-with-schema
                 projection
                 (:seon.schema/key program-row)
                 (edn/read-string (:seon.schema/form program-row))
                 {:seon.schema.admission/source :agent})

                (deleted-schema-key program-row)
                (schema/projection-without-schema
                 projection (deleted-schema-key program-row))

                (:seon.fn/spec program-row)
                (schema/projection-with-function-contract
                 projection
                 (:seon.fn/sym program-row)
                 (edn/read-string (:seon.fn/spec program-row))
                 {:seon.schema.admission/source :agent})

                :else projection)
              result
              (install-row!
               {:seon.sci.eval/ctx ctx
                :seon.db/db db
                :seon.program/row program-row
                ::evaluated? true
                ::prepared-projection next-projection})]
          {:projection (or (:seon.schema/projection result) projection)
           :installed (conj installed result)}))
      {:projection (context-projection ctx)
       :installed []}
      installations))]
      (when-let [snapshot (::base-bindings agent-ctx)]
        (reset! snapshot (base-bindings ctx)))
      (when (and report written-on
                 (every? :seon.sci.eval/installed installed)
                 (installation-covers-program-change?
                  ctx written-on report installations (context-projection ctx)))
        (doseq [target (cond-> [ctx] agent-ctx (conj agent-ctx))]
          (swap! (::kernel/program-snapshot target) assoc :seon.db/db db)))
      installed))

(defn- classpath-locatable?
  "Whether THIS PROCESS's own classpath can serve one namespace's source.

  Graph membership and PROCESS membership are two different facts, and
  conflating them refused every cluster boot on 2026-08-08. The program graph
  is indexed from both source roots (`seon.fn/source-roots` — `src` and
  `test`), so `my.background-test` is an ordinary core-provenanced program
  row; cluster launch deliberately omits the resolved test classpath, so that
  row names source this process genuinely cannot load. The in-process test
  runner installs its resolved test loader only after boot. Requiring a test
  row during boot was a correct refusal of a wrong premise.

  The loader is the PROCESS's launch classpath, never the calling thread's.
  `io/resource`'s one-argument arity asks `clojure.lang.RT/baseLoader`, which
  returns the current thread's CONTEXT classloader, so any caller that binds
  one silently redefines what this process can serve.
  `seon.test/with-test-loader` binds exactly such a loader over the `:test`
  alias source paths so an in-process run can resolve a test Var; acquiring an
  evaluation context inside its extent made every `test/` namespace row
  locatable, and the install died requiring the first one whose dependency is
  an alias EXTRA-dep rather than a source path
  (`seon.dev.dependency-cache-test` -> `dev-cache` ->
  `clojure.tools.build.api`, 2026-09-16). A gate worker launched `-M:test`
  carries `test/` and those dependencies on its own `-cp`, so its membership
  is unchanged.

  Asking the process's classpath asks the process itself. It is a computed
  fact, not a path convention and not a maintained list, so it stays true when
  the source roots, the aliases, or the packaging change — and a namespace
  this process CAN serve is still bound whether or not anything happened to
  load it."
  [namespace-name]
  (let [stem (-> (str namespace-name)
                 (str/replace "-" "_")
                 (str/replace "." "/"))
        loader (ClassLoader/getSystemClassLoader)]
    (boolean
     (some (fn [suffix] (io/resource (str stem suffix) loader))
           [".clj" ".cljc" "__init.class"]))))

(defn- host-namespace!
  "The loaded host namespace for one core-provenanced program row, or nil.

  Membership of the ctx must not depend on WHICH namespaces something else
  happened to load first. `my.web` was unreachable from agent code for exactly
  that reason: `my.fs`, `my.shell`, and `my.edit` are loaded as a side effect
  of `requiring-resolve` on the core predicates they register, `my.web`
  registers none, and the install silently skipped every graph namespace it
  did not already find in `all-ns`. A capability namespace that is public,
  contracted, in the program graph, and absent from the ctx is the
  silent-fallback shape the ethos names — right until the second namespace,
  then a lie. A core-provenanced row IS first-party source, so loading it here
  is the ordinary thing to do, and it makes the ctx's membership exactly the
  graph's.

  A namespace this process cannot serve at all is nil — it is not part of
  this process's callable surface. A namespace that IS on the classpath and
  still fails to load is a loud refusal naming the namespace and the cause,
  never a quiet omission."
  [namespace-name]
  (or (find-ns namespace-name)
      (when (classpath-locatable? namespace-name)
        (try
          ;; `:reload` because the namespace is absent: its `ns` form registers
          ;; the lib in `*loaded-libs*` even when the rest of the file then
          ;; fails, and `remove-ns` never clears it, so a plain `require` of a
          ;; namespace that once failed or was removed loads nothing and the
          ;; real cause is lost. Nothing loaded exists here to be replaced.
          (require namespace-name :reload)
          (catch Throwable failure
            (let [causes (take-while some? (iterate ex-cause failure))
                  underlying (last causes)
                  cause-message (or (ex-message underlying)
                                    (ex-message failure)
                                    "The namespace loader reported no message.")
                  location-data
                  (some (fn [cause]
                          (let [data (ex-data cause)]
                            (when (or (:clojure.error/source data)
                                      (:clojure.error/line data)
                                      (:clojure.error/column data))
                              (select-keys data
                                           [:clojure.error/source
                                            :clojure.error/line
                                            :clojure.error/column]))))
                        causes)
                  location
                  (when (seq location-data)
                    (str (:clojure.error/source location-data)
                         (when-let [line (:clojure.error/line location-data)]
                           (str ":" line))
                         (when-let [column (:clojure.error/column location-data)]
                           (str ":" column))))
                  message
                  (str "First-party program namespace " namespace-name
                       " could not be loaded for the evaluation context. Cause: "
                       cause-message
                       (when location (str " at " location)) ".")
                  diagnostic
                  (seon.error.refusal/diagnostic (java.util.Date.) ::acquisition 'seon.sci.eval/host-namespace!
                   {:seon.sci.eval/row-symbol namespace-name
                    :seon.sci.eval/acquisition-observation {:seon.error.evidence/attribute :seon.error/message
                     :seon.error.evidence/value cause-message}
                    :seon.error/message message
                    :seon.ns/name namespace-name
                    :seon.error/expected ::loaded-host-namespace
                    :seon.error/data {:seon.sci.eval/cause-class (symbol (.getName (class underlying)))
                                      :seon.sci.eval/cause-location location-data}})]
              (throw (ex-info message diagnostic failure)))))
        (or (find-ns namespace-name)
            (throw
             (ex-info
              (str "First-party program namespace " namespace-name
                   " loaded without defining a namespace.")
              (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.eval/program 'seon.sci.eval/host-namespace!
               {:seon.error/message "Loaded source defined no namespace; correct its namespace declaration."
                :seon.error/offending namespace-name
                ::row-symbol namespace-name
                ::acquisition-observation {:seon.error.evidence/attribute :seon.error/message
                                           :seon.error.evidence/value "Loaded source defined no namespace."}
                :seon.error/member :seon.ns/name
                :seon.error/expected :seon.sci.eval/loaded-namespace})))))))

(defn- load-core-namespaces!
  "The effectful cluster caller loads the running program's JVM namespaces
  before pure construction. A namespace indexed from the test source root is
  not run by the cluster: `seon.test/resolve-test` requires it on the test
  request that needs it. Measured 2026-09-23 on a 417-namespace program:
  107 armed, 310 deferred, 7,772 ms -> 1,193 ms on a warm resume."
  [database]
  (let [names (db/q '[:find [?name ...]
                      :where [?file :seon.fn.file/relative-root ?root]
                      [(not= ?root "test")]
                      [?namespace :seon.fn/file ?file]
                      [?namespace :seon.ns/name ?name]
                      (or-join [?namespace]
                        (and [?namespace :seon.schema.admission/source :core]
                             [?namespace :seon.ns/source _])
                        (and [?function :seon.fn/ns ?namespace]
                             [?function :seon.fn/source _]
                             [?function :seon.schema.admission/source :core]))]
                    database)]
    (when (:seon.error/at names)
      (throw (ex-info (:seon.error/message names) names)))
    (doseq [namespace-name (sort-by str names)]
      (host-namespace! namespace-name))))

(defn- install-first-party-namespaces!
  "Bind every first-party program namespace as its actual compiled JVM Vars.

  Namespace membership comes from admitted program rows. The cluster
  caller loads compiled namespaces before construction; this pure derivation
  only reads the JVM Vars already present. Every admitted identity is added,
  including native Vars without stored source or a privacy declaration.
  Each binding copies the loaded JVM Var root. Reacquisition takes a
  new copy after JVM reload. Privacy is a rendering and
  curation fact, never an execution boundary. A JVM intern without a current
  program identity does not become callable merely because its namespace loads.

  Safety residual from ruling #20: once execution enters one compiled host
  call, SCI's interrupt hook sees no interpreted function entrance. Runaway
  work inside that call is bounded by the submit-level wedge backstop, not the
  evaluation time-limit."
  [ctx namespace-assertions _namespace-rows
   function-rows]
  (let [first-party-names
        (into (set (map first namespace-assertions)) (map #(nth % 2)) function-rows)
        indexed-function-names
        (reduce (fn [by-namespace [function-symbol _source namespace-name
                                  _admission _private?]]
                  (update by-namespace namespace-name (fnil conj #{})
                          (symbol (name (symbol function-symbol)))))
                {}
                function-rows)]
    (doseq [namespace-name (sort-by str first-party-names)
            :let [host-namespace (find-ns namespace-name)]
            :when host-namespace]
      (let [sci-namespace (sci/create-ns namespace-name)
            indexed-names (get indexed-function-names namespace-name)
            host-bindings
            (select-keys
             (ns-interns host-namespace)
             indexed-names)]
        (sci/add-namespace!
         ctx namespace-name
         (into {}
               (map
                (fn [[local-name host-var]]
                  [local-name
                   (copy-host-var host-var sci-namespace)]))
               host-bindings))
        ;; These are already the current compiled host Vars. Record that fact
        ;; at the same seam that installs them so lazy invocation can never
        ;; mistake a first-party function for an agent-authored function that
        ;; requires a fact-backed SCI root descriptor.
        (doseq [local-name indexed-names
                :when (contains? host-bindings local-name)]
          (kernel/mark-installed!
           ctx (symbol (str namespace-name) (str local-name))))))))

(defn- install-host-namespace!
  [ctx namespace-name intern-map]
  (let [sci-namespace (sci/create-ns namespace-name)]
    (sci/add-namespace!
     ctx namespace-name
     (into {}
           (map (fn [[local-name host-var]]
                  [local-name (copy-host-var host-var sci-namespace)]))
           intern-map))))

(defn- install-caller-host-bindings!
  "Bind the compiled Vars an interpreted caller's source names but no program row installs.

  An interpreted declaration's namespace receives its loaded JVM namespace's
  remaining interns (private helpers, `def`/`defonce` Vars); each alias and
  refer target its namespace row declares receives that loaded namespace's
  public Vars. Existing SCI bindings win: first-party copies, interpreted
  definitions and SCI's own `clojure.*` namespaces are never replaced, and
  `clojure.core` is left to SCI. A target this JVM has not loaded stays
  absent, so its caller refuses by name."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:sequential :map]] :nil]}
  [ctx namespace-rows]
  (let [sci-core (get-in @(:env ctx) [:namespaces 'clojure.core])
        ;; clojure.core names SCI does not carry (spit) bind in the caller's
        ;; own namespace; SCI's interrupt-aware core is never replaced.
        own-bindings
        (fn [host]
          (merge (into {} (filter (fn [[local-name host-var]]
                                    (and (= 'clojure.core (ns-name (.ns ^clojure.lang.Var host-var)))
                                         (not (contains? sci-core local-name)))))
                       (ns-refers host))
                 (ns-interns host)))
        installs
        (for [row namespace-rows
              :let [namespace-name (:seon.ns/name row)
                    {:keys [aliases refers]} (row-bindings row)]
              [target interns]
              (cons [namespace-name (some-> (find-ns namespace-name) own-bindings)]
                    (for [target (distinct (concat (vals aliases)
                                                   (map (comp symbol namespace) (vals refers))))
                          :when (not= 'clojure.core target)]
                      [target (some-> (find-ns target) ns-publics)]))
              :when (seq interns)]
          [target interns])]
    (doseq [[target interns] installs
            :let [present (get-in @(:env ctx) [:namespaces target])]]
      (install-host-namespace! ctx target (remove (fn [[local-name]] (contains? present local-name))
                                                  interns)))))

(def ^:private program-documentation-selector
  [:seon.fn/sym :seon.fn/doc :seon.fn/doc-order :seon.fn/arglists :seon.fn/spec
   :seon.schema.admission/source
   {:seon.fn/arities
    [:seon.fn.arity/order :seon.fn.arity/arity
     '(limit :seon.fn.arity/input-refs nil)
     '(limit :seon.fn.arity/output-refs nil)]}])

(defn- program-documentation
  "Read one namespace's public functions at the evaluation's database basis."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol]
                  [:or [:vector :map] :seon.db/error-result]]}
  [database namespace-name]
  (db/q '[:find [(pull ?function selector) ...]
          :in $ selector ?name
          :where [?namespace :seon.ns/name ?name]
                 [?function :seon.fn/ns ?namespace]
                 [?function :seon.fn/sym _]
                 [?function :seon.fn/private? false]]
        database program-documentation-selector namespace-name))

(defn- documentation-unavailable
  {:malli/schema [:=> [:cat :symbol] :seon.sci.eval/documentation-unavailable-error]}
  [requested]
  (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.eval/program 'seon.sci.eval/documentation-unavailable
   {:seon.error/message "No public documentation is available; request an installed public declaration."
    :seon.error/offending requested
    ::documentation-unavailable requested
    :seon.error/member :seon.fn/sym
    :seon.error/expected :seon.sci.eval/public-documentation}))

(defn- declaration-statement
  "The sentence `doc` and `dir` show in place of a declaration this row lacks."
  [attribute]
  (str "This program row carries no " attribute "."))

(defn- declaration-absent
  "The typed unknown `doc` and `dir` show for an absent declaration.

   An absent contract and a declared empty one are different facts, and this
   is the surface an agent reads before deciding how to call a function: a
   missing `:seon.fn/spec` rendered as `[]` says the function declares
   nothing, which is a claim the row does not support (AGENTS.md section 2.4)."
  {:malli/schema [:=> [:cat :qualified-keyword] :seon.sci.eval/declaration-absent-error]}
  [attribute]
  (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.eval/program 'seon.sci.eval/declaration-absent
   {:seon.error/message "The program row lacks a declaration; supply the declared member before relying on it."
    :seon.error/offending attribute
    ::missing-declaration attribute
    :seon.error/expected :seon.sci.eval/present-declaration}))

(defn- missing-contract
  "The refusal of a function definition that declares no `:malli/schema`.

  Entry defaults to the gate: `admit-program-change!` refuses the evaluation
  and restores every definition it made, so the function neither stays in the
  context nor replaces an already armed root. No entry dial exists yet; a
  `:warn` admission needs its owner's ruling first."
  {:malli/schema [:=> [:cat :qualified-symbol] :seon.sci.eval/declaration-absent-error]}
  [function-symbol]
  (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.eval/program 'seon.sci.eval/evaluate
   {:seon.error/message
    (str function-symbol " declares no :malli/schema; add a complete contract to its "
         "attribute map, e.g. (defn f \"Doc.\" {:malli/schema [:=> [:cat :int] :int]} [x] x).")
    :seon.error/member :malli/schema
    :seon.error/offending function-symbol
    ::missing-declaration :seon.fn/spec
    :seon.error/expected :malli/schema}))

(defn docstring-parts
  "Split a declared docstring into its summary, body, and final Example section."
  {:malli/schema [:=> [:cat :string] :map]}
  [docstring]
  (let [[summary & lines] (str/split-lines (or docstring ""))
        lines (mapv str/trim lines)
        example-index (last (keep-indexed #(when (= "Example:" %2) %1) lines))]
    {:summary (or summary "")
     :body (str/trim (str/join "\n" (if example-index (subvec lines 0 example-index) lines)))
     :example (if example-index
                (str/trim (str/join "\n" (subvec lines (inc example-index)))) "")}))

(defn- documentation-schemas
  {:malli/schema [:=> [:cat :seon.db/database-value :map] [:map-of :qualified-keyword :seon.schema/definition]]}
  [database row]
  (let [keys (into #{} (mapcat #(concat (:seon.fn.arity/input-refs %)
                                       (:seon.fn.arity/output-refs %)))
                   (:seon.fn/arities row))
        definitions (db/q '[:find ?key ?form :in $ [?key ...]
                            :where [?schema :seon.schema/key ?key]
                                   [?schema :seon.schema/form ?form]] database keys)]
    (when (or (:seon.db/invalid-read definitions) (:seon.schema/expected-value definitions))
      (throw (ex-info "Documentation schema references unavailable." definitions)))
    (into (sorted-map) (map (fn [[key form]] [key (edn/read-string form)])) definitions)))

(defn- documentation-contract
  [database row]
  (if-let [spec (:seon.fn/spec row)]
    (let [projection (or (db/carried-projection database)
                         (let [failure (db/projection-fallback
                                        'seon.sci.eval/documentation-contract)]
                           (throw (ex-info (:seon.error/message failure) failure))))]
      (schema/projection-cache-value
       projection [::documentation-contract spec]
       (fn []
         (let [compiled (m/function-schema (edn/read-string spec)
                          {:registry (:seon.schema.projection/registry projection)})
               arities (mapv m/-function-info (m/-function-schema-arities compiled))
               inputs (mapv #(m/form (:input %)) arities)
               outputs (mapv #(m/form (:output %)) arities)]
           {:in (if (= 1 (count inputs)) (first inputs) inputs)
            :out (if (= 1 (count outputs)) (first outputs) outputs)}))))
    {:in (declaration-absent :seon.fn/spec)
     :out (declaration-absent :seon.fn/spec)}))

(defn- agent-documentation-contract
  {:malli/schema [:=> [:cat :seon.db/database-value :map]
                  [:or :map :seon.db/error-result]]}
  [database row]
  (let [entries (call-preparation/supplied-map-entries database (:seon.fn/sym row))
        contract (documentation-contract database row)
        expanded (walk/postwalk-replace (into {} (documentation-schemas database row)) contract)
        spec (:seon.fn/spec row)
        arities (if (and spec (= :function (first (edn/read-string spec))))
                  (:in expanded) [(:in expanded)])]
    (if (or (:seon.db/invalid-read entries) (:seon.schema/expected-value entries))
      entries
      (let [inputs
            (mapv (fn [order input]
                    (reduce (fn [form [position supplied]]
                              (let [offset (if (map? (second form)) 2 1)
                                    index (+ offset position)
                                    argument (get form index)
                                    keys (set (map #(nth % 2) supplied))]
                                (if (and (vector? argument) (= :map (first argument)))
                                  (assoc form index
                                         (into [] (remove #(and (vector? %) (keys (first %)))) argument))
                                  form)))
                            input
                            (group-by second (filter #(= order (first %)) entries))))
                  (range) arities)]
        (cond-> contract
          (seq entries)
          (assoc :in (if (= 1 (count inputs)) (first inputs) inputs)
                 :supplied (vec (distinct (map #(nth % 2) entries)))))))))

(defn- function-doc-map
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :map] [:or :map :seon.db/error-result]]
                  [:=> [:cat :seon.db/database-value :map :boolean] [:or :map :seon.db/error-result]]]}
  ([database row]
   (let [overrides (when (= :agent (:seon.schema.admission/source row))
                     (program/overrides database))]
     (if (or (:seon.db/invalid-read overrides) (:seon.schema/expected-value overrides))
       overrides
       (function-doc-map database row
                         (boolean (some #{(:seon.fn/sym row)} overrides))))))
  ([database row overridden?]
   (merge (if-let [doc (:seon.fn/doc row)]
               (docstring-parts doc)
               {:summary (declaration-statement :seon.fn/doc) :body "" :example ""})
             {:arglists (if-let [arglists (:seon.fn/arglists row)]
                          (edn/read-string arglists)
                          (declaration-absent :seon.fn/arglists))}
             (agent-documentation-contract database row)
             (when overridden?
               {:seon.schema.admission/note
                "Accepted database override for SCI; JVM callers retain the compiled definition until write-back and reload."}))))

(defn directory-value
  "Return current public function summaries and declared schemas for a namespace."
  {:malli/schema [:=> [:cat :seon.db/db :symbol :boolean] [:or :seon.repl/directory :seon.db/error-result :seon.sci.eval/documentation-unavailable-error]]}
  [database namespace-name present?]
  (let [namespace-row (db/pull database
                             '[:seon.ns/name
                               {:seon.ns/aliases [:seon.ns.alias/local :seon.ns.alias/target-ns]}
                               {:seon.schema/_ns [:seon.schema/key :seon.schema/form]}]
                             [:seon.ns/name namespace-name])
        functions (program-documentation database namespace-name)
        ;; Arglists are source text: the one reader resolves their
        ;; auto-resolved keywords (`{id ::id}`) in the namespace's own aliases.
        aliases (into {} (map (juxt :seon.ns.alias/local :seon.ns.alias/target-ns))
                      (:seon.ns/aliases namespace-row))
        read-arglists (fn [text]
                        (let [read (reader/read {::reader/text text ::reader/ns namespace-name ::reader/aliases aliases
                                                 :seon.config.eval.result/max-source (count text)})]
                          (if (vector? read) (::reader/form (first read)) read)))]
    (cond
      (or (:seon.db/invalid-read namespace-row) (:seon.schema/expected-value namespace-row)) namespace-row
      (or (:seon.db/invalid-read functions) (:seon.schema/expected-value functions)) functions
      (or (:seon.ns/name namespace-row) present? (seq functions))
      (let [functions (sort-by (juxt #(get % :seon.fn/doc-order Long/MAX_VALUE)
                                    :seon.fn/sym) functions)]
        ;; `dir` is a macro, so its call names no producer; the directory
        ;; carries its own pair (the render law's master override).
        {:seon.render/ai 'seon.repl/render-directory-ai
         :seon.render/html 'seon.repl/render-directory-html
         :schemas (into (into (sorted-map) (mapcat #(documentation-schemas database %)) functions)
                        (map (fn [row]
                               [(:seon.schema/key row)
                                (edn/read-string (:seon.schema/form row))]))
                        (:seon.schema/_ns namespace-row))
         :functions (mapv (fn [row]
                            (merge {:sym (:seon.fn/sym row)
                                    :arglists (if-let [arglists (:seon.fn/arglists row)]
                                                (read-arglists arglists)
                                                (declaration-absent :seon.fn/arglists))
                                    :doc (if-let [doc (:seon.fn/doc row)]
                                           (:summary (docstring-parts doc))
                                           (declaration-statement :seon.fn/doc))}
                                   (agent-documentation-contract database row)))
                          functions)})
      :else (documentation-unavailable namespace-name))))

(defn documentation-value
  "Read documentation for a public function, named override, or namespace."
  {:malli/schema [:=> [:cat :seon.db/db :symbol :symbol] [:or :map :seon.db/error-result :seon.sci.eval/documentation-unavailable-error]]}
  [database requested qualified]
  (if (namespace qualified)
    (let [row (db/pull database
                       (conj program-documentation-selector :seon.fn/private?)
                       [:seon.fn/sym qualified])
          overrides (when (= :agent (:seon.schema.admission/source row))
                      (program/overrides database))
          overridden? (boolean (some #{qualified} overrides))]
      (cond
        (or (:seon.db/invalid-read row) (:seon.schema/expected-value row)) row
        (or (:seon.db/invalid-read overrides) (:seon.schema/expected-value overrides)) overrides
        (and (:seon.fn/sym row)
             (or (false? (:seon.fn/private? row)) overridden?))
        (function-doc-map database row overridden?)
        :else (documentation-unavailable requested)))
    (let [row (db/pull database [:seon.ns/doc] [:seon.ns/name requested])]
      (cond
        (or (:seon.db/invalid-read row) (:seon.schema/expected-value row)) row
        (:seon.ns/doc row) (merge (docstring-parts (:seon.ns/doc row)) {:in [] :out []})
        :else (documentation-unavailable requested)))))

(defn- program-doc-var
  "Resolve in the calling SCI namespace; read facts when the form executes."
  []
  (sci/new-macro-var
   'doc
   (fn [_form _env function-symbol]
     `(let [resolved# (resolve '~function-symbol)
            metadata# (meta resolved#)]
        (seon.sci.eval/documentation-value
         (seon.db/db) '~function-symbol
         (if resolved#
           (symbol (str (:ns metadata#)) (str (:name metadata#)))
           '~function-symbol))))
   {:ns (sci/create-ns 'clojure.repl)}))

(defn- program-dir-var
  "Read current facts through the calling evaluation's database custody."
  []
  (sci/new-macro-var
   'dir
   (fn [_form _env namespace-name]
     `(seon.sci.eval/directory-value
       (seon.db/db) '~namespace-name
       (boolean (clojure.core/find-ns '~namespace-name))))
   {:ns (sci/create-ns 'clojure.repl)}))

(defn stored-source
  "The stored source of the program declaration named `qualified`, or nil.

  Each program family's shape names its identity and source attributes
  (`seon.program/shapes-in`); the families whose identity schema admits
  `qualified` are asked, and the first holding it answers."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol] [:maybe :string]]}
  [database qualified]
  (let [projection (or (db/carried-projection database)
                       (schema/projection-from-database database))
        registry (:seon.schema.projection/registry projection)]
    (some (fn [[identity-attribute {source-attribute :seon.program/source-attribute}]]
            (when (m/validate (m/schema identity-attribute {:registry registry}) qualified)
              (let [source (db/q '[:find ?source . :in $ ?identity ?value ?attribute
                                   :where [?entity ?identity ?value] [?entity ?attribute ?source]]
                                 database identity-attribute qualified source-attribute)]
                (if (:seon.db/invalid-read source)
                  (throw (ex-info (:seon.error/message source) source))
                  source))))
          (program/shapes-in projection))))

(defn- stored-source-fn
  "`clojure.repl/source-fn` for the evaluating context: SCI's `source` macro
  calls it (`reference-code/sci/src/sci/impl/namespaces.cljc:2436`); the
  symbol resolves in the calling SCI namespace and the stored row answers."
  {:malli/schema [:=> [:cat :symbol] [:maybe :string]]}
  [requested]
  (let [resolved (sci/resolve (sci.ctx-store/get-ctx) requested)
        {var-ns :ns var-name :name} (meta resolved)]
    (stored-source (db/db) (if resolved (symbol (str var-ns) (str var-name)) requested))))

(defn- install-program-doc!
  "Install REPL macros without capturing program facts or a database value."
  [ctx _db _projection]
  (let [doc-var (program-doc-var)
        dir-var (program-dir-var)]
    (sci/add-namespace! ctx 'clojure.repl
                        {'doc doc-var 'dir dir-var
                         'source-fn (sci/new-var 'source-fn stored-source-fn
                                                 {:ns (sci/create-ns 'clojure.repl)})})
    (sci/add-namespace! ctx 'clojure.core
                        {'doc doc-var 'dir dir-var
                         'deftest (sci/resolve ctx 'clojure.test/deftest)
                         'is (sci/resolve ctx 'clojure.test/is)})
    ctx))

(defn- install-declared-classes!
  "Install every non-masked class named by acquired namespace facts."
  [ctx namespace-rows]
  (doseq [class-name
          (->> namespace-rows
               (mapcat (comp vals :imports row-bindings))
               (remove nil?)
               set
               (sort-by str))]
    (sci/add-class! ctx class-name (Class/forName (str class-name))))
  ctx)

(def ^:private acquisition-process "seon.sci.eval/acquire")

(defn- acquisition-refusal
  "A flat agent-mistake value naming one row that could not be installed."
  {:malli/schema [:=> [:cat :map [:or :map :seon.error/throwable]]
                  [:or :seon.sci.eval/row-acquisition-error
                   :seon.sci.eval/interpretation-error]]}
  [row failure]
  (let [identity (program-row-identity row)
        failure-data (if (map? failure) failure (error/refusal failure))
        cause-message (or (:seon.error/message failure-data)
                          (when (instance? Throwable failure) (ex-message failure))
                          (.getName (class failure)))]
    (if (:seon.sci.eval/refused-function failure-data)
      failure-data
      (cond->
       (seon.error.refusal/diagnostic (java.util.Date.) ::acquisition 'seon.sci.eval/acquisition-refusal
        {(if (= :seon.schema/key (first identity)) :seon.sci.eval/row-schema-key :seon.sci.eval/row-symbol)
         (second identity)
      :seon.sci.eval/acquisition-observation {:seon.error.evidence/attribute :seon.error/message
       :seon.error.evidence/value cause-message}
      :seon.error/message "Program row could not be installed; correct the reported acquisition evidence."
      :seon.error/data (cond-> {::acquisition-row identity
               ::acquisition-cause-message cause-message}
        failure-data (assoc ::acquisition-failure failure-data)
        (instance? Throwable failure)
        (assoc ::acquisition-throwable-class (.getName (class failure))))
      :seon.error/expected ::installed})
       (instance? Throwable failure)
       (assoc :seon.error/chain (error.refusal/chain failure))))))

(defn- acquisition-refusal-id
  [refusal]
  (id/digest 64
             [::acquisition-refused
              (get-in refusal [:seon.error/data ::acquisition-row])
              (or (:seon.sci.eval/refused-function refusal)
                  (:seon.sci.eval/row-symbol refusal)
                  (:seon.sci.eval/row-schema-key refusal))
              (:seon.error/operation refusal)
              (get-in refusal [:seon.error/data ::acquisition-cause-message])]))

(defn- refusal-member
  "Where one row's acquisition refusal belongs in the acquisition state.

  An agent-authored row (`:seon.schema.admission/source :agent`) that cannot be
  installed is that agent's mistake (AGENTS.md error policy): it is recorded
  through the one error owner, which routes it to the namespace's agent, and
  the rest of the program acquires without it (`::agent-mistakes`). Any other
  row's refusal refuses the acquisition (`::acquisition-refusals`)."
  {:malli/schema [:=> [:cat :map] [:enum ::agent-mistakes ::acquisition-refusals]]}
  [declaration]
  (if (= :agent (:seon.schema.admission/source declaration))
    ::agent-mistakes
    ::acquisition-refusals))

(defn- record-acquisition-refusals!
  "Record contained row refusals through the one durable error owner, once.

  All of one acquisition's refusals commit as one transaction on the context's
  custody connection: acquisition runs under the arming monitor, and one fault
  delivery per refusal paid one writer projection derivation each (measured 164 ms
  per call; warm-restart hang, schedule #17a). A context with no connection
  delivers through the supplied fault recorder."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :map [:or :nil :seon.flow/commit-fault!]] :map]}
  [ctx db state commit-fault!]
  (let [refusals (into (vec (::acquisition-refusals state)) (::agent-mistakes state))]
    (if-not (seq refusals)
      state
      (if-let [connection (:seon.db/connection (::custody ctx))]
        (let [read-effective (database-effective-config db)
              ;; THE FALLBACK KEYS ON THE REFUSAL, not on nil: this read
              ;; answers the typed unknown rather than an absence, so an
              ;; `or` here would carry the refusal into `result-caps` and
              ;; `commit-tx` instead of the shipped decisions it means.
              effective (if (contains? read-effective :seon.config/error-key)
                          config/defaults
                          read-effective)
              caps (config/result-caps effective)
              recurrence-limit
              (:seon.config.error/recurrence-limit effective)
              escalate-to (:seon.config.error/escalate-to effective)
              tx-data
              (into
               []
               (mapcat
                (fn [refusal]
                  (error/commit-tx
                   db
                   (cond->
                    {:seon.error/source refusal
                     :seon.error/declared-schema
                     (if (:seon.sci.eval/refused-function refusal)
                       :seon.sci.eval/interpretation-error :seon.sci.eval/row-acquisition-error)
                     :seon.error/id (acquisition-refusal-id refusal)
                     :seon.error/at (java.util.Date.)
                     :seon.error/process acquisition-process
                     :seon.error/basis-t (db/basis-t db)
                     :seon.sci.admit/caps caps
                     :seon.config.error/recurrence-limit recurrence-limit
                     :seon.config.error/max-evidence-bytes
                     (:seon.config.error/max-evidence-bytes effective)}
                     escalate-to
                     (assoc :seon.config.error/escalate-to escalate-to))))
               refusals))
              ;; The refusals are this branch's own work, whoever acquires
              ;; it: agent acquisition or a lazy invocation under another
              ;; branch's custody (`kernel/ensure-function!`).
              outcome (db/call-with-custody {:seon.db/connection connection}
                                            #(db/transact! connection tx-data))]
          (cond-> (assoc state ::acquisition-refusals-recorded? true)
            (or (:seon.db.write.attempt/request-id outcome) (:seon.db/invalid-read outcome) (:seon.schema/expected-value outcome))
            (assoc ::acquisition-refusals-recorded? false
                   ::acquisition-recording-error outcome)))
      (if commit-fault!
        (let [outcomes (mapv #(commit-fault! {:seon.error/source %
                                             :seon.error/declared-schema
                                             (if (:seon.sci.eval/refused-function %)
                                               :seon.sci.eval/interpretation-error
                                               :seon.sci.eval/row-acquisition-error)}) refusals)
              failed (first (remove #(= :seon.flow/committed (second %)) outcomes))]
          (cond-> (assoc state ::acquisition-refusals-recorded? (nil? failed))
            failed (assoc ::acquisition-recording-error (second failed))))
        (assoc state ::acquisition-refusals-recorded? false))))))

(defn- acquire-program!
  "Acquire definitions against the JVM's loaded program value.

  Overridden rows and their affected callers interpret stored source; matching
  definitions bind compiled Vars regardless of authorship. Private evaluation objects are never
  read from the database. Failed source loads remain typed acquisition results."
  {:malli/schema [:=> [:cat :seon.sci.eval/acquire-request] :map]}
  [{ctx :seon.sci.eval/ctx
    db :seon.db/db
    supplied-projection :seon.schema/projection
    commit-fault! :seon.flow/commit-fault!}]
  (let [recording-operation (or commit-fault!
                                (:seon.flow/commit-fault! @(::kernel/program-snapshot ctx)))
        projection (or supplied-projection
                       (db/carried-projection db)
                       (:seon.schema/projection (env/of ctx)))
        db (if projection
             (vary-meta db assoc :seon.schema/projection projection)
             db)]
    (when-not projection
      (let [failure (db/projection-fallback 'seon.sci.eval/acquire!)]
        (throw (ex-info (:seon.error/message failure) failure))))
    (schema/call-with-projection
     projection
     (fn []
      (let [ctx (assoc ctx :seon.schema/projection projection)
        namespace-assertions
        (db/q '[:find ?namespace-name ?source ?admission
               :where
               [?namespace :seon.ns/name ?namespace-name]
               [?namespace :seon.ns/source ?source]
                [?namespace :seon.schema.admission/source ?admission]]
             db)
        namespace-source-by-name
        (into {}
              (map (fn [[namespace-name source admission]]
                     [namespace-name [source admission]]))
              namespace-assertions)
        all-namespace-names
        (db/q '[:find [?namespace-name ...]
                :where
                [_ :seon.ns/name ?namespace-name]]
              db)
        agent-authored?
        (fn [admission]
          (= :agent admission))
        all-function-rows
        (mapv (fn [[sym source admission private?]]
                [sym source (symbol (namespace (symbol sym))) admission private?])
         (db/q '[:find ?sym ?source ?admission ?private
                :where
                [?function :seon.fn/sym ?sym]
                [(get-else $ ?function :seon.fn/source "") ?source]
                [?function :seon.schema.admission/source ?admission]
                [(get-else $ ?function :seon.fn/private? false) ?private]]
              db))
        loaded (or (::loaded-database @(::kernel/program-snapshot ctx))
                   (loaded-program db))
        loaded-digests (function-digests loaded)
        branch-digests (function-digests db)
        ;; Only what an agent authored overrides (`overridden-row?`): a
        ;; file-derived row the loaded program moved past is not the branch's.
        authored (into #{} (keep (fn [[sym _ _ admission]] (when (agent-authored? admission) (symbol sym))))
                       all-function-rows)
        overridden
        (into #{} (keep (fn [[sym digest]]
                          (when (and (authored sym) (not= digest (get loaded-digests sym))) sym)))
              branch-digests)
        closure (if (seq overridden)
                  (seon.fn/reverse-closure {:seon.db/db db :seon.fn/seeds overridden})
                  {:seon.fn/affected #{} :seon.fn/tests []})
        _ (when (:seon.db/invalid-read closure)
            (throw (ex-info "Cannot acquire the affected function closure." closure)))
        interpreted (into overridden (:seon.fn/affected closure))
        function-rows (filterv (fn [[sym]] (interpreted sym)) all-function-rows)
        _ (install-program-doc! ctx db projection)
        selected-namespace-names
        (into (into #{} (map #(nth % 2)) function-rows)
              (comp
               (filter (fn [[_ _ admission]] (agent-authored? admission)))
               (map first))
              namespace-assertions)
        all-namespace-rows
        (into
         []
         (map (fn [namespace-name]
                (let [[source admission]
                      (get namespace-source-by-name namespace-name)]
                  (assoc
                   (db/pull db
                            '[* :seon.ns/requires
                                {:seon.ns/aliases [*]}
                                {:seon.ns/imports [*]}
                                {:seon.ns/refers [*]}]
                            [:seon.ns/name namespace-name])
                   ::namespace-source source
                   ::namespace-admission admission
                   ::agent-authored?
                   (boolean (and admission
                                 (agent-authored? admission)))))))
         all-namespace-names)
        all-namespace-row-by-name
        (into {} (map (juxt :seon.ns/name identity)) all-namespace-rows)
        namespace-rows
        (into []
              (keep (fn [namespace-name]
                      (get all-namespace-row-by-name namespace-name)))
              selected-namespace-names)
        test-rows
        (into
         []
         (filter (fn [[_ _ _ admission]] (agent-authored? admission)))
         (db/q '[:find ?sym ?source ?namespace-name ?admission
                :where
                [?test :seon.test/sym ?sym]
                [?test :seon.test/source ?source]
                [?test :seon.schema.admission/source ?admission]
                [?test :seon.test/ns ?namespace]
                [?namespace :seon.ns/name ?namespace-name]]
              db))
        function-rows-by-ns
        (group-by #(nth % 2) function-rows)
        test-rows-by-ns
        (group-by #(nth % 2) test-rows)
        namespace-row-by-name
        (into {} (map (juxt :seon.ns/name identity)) namespace-rows)
        _ (kernel/cache-program!
           ctx
           (into {}
                 (map (fn [[sym source namespace-name admission private?]]
                        [(symbol sym)
                         {::function-source source
                          ::function-admission admission
                          ::function-namespace namespace-name
                          ::function-private? private?
                          ::agent-authored? (agent-authored? admission)}]))
                 all-function-rows)
           all-namespace-row-by-name)
        _ (swap! (::kernel/program-snapshot ctx) assoc
                 ::loaded-database loaded ::interpreted interpreted)
        _ (when-let [recorder recording-operation]
            (swap! (::kernel/program-snapshot ctx) assoc
                   :seon.flow/commit-fault! recorder))
        namespace-names
        (into (set (keys namespace-row-by-name))
              (concat (keys function-rows-by-ns)
                      (keys test-rows-by-ns)))
        dependencies
        (into {}
              (map
               (fn [namespace-name]
                 (let [bindings
                       (row-bindings
                        (get namespace-row-by-name namespace-name))]
                   [namespace-name
                    (into #{}
                          (comp
                           (filter namespace-names)
                           (remove #{namespace-name}))
                          (concat
                           (:requires bindings)
                           (keep (comp symbol namespace)
                                 (vals (:refers bindings)))))])))
              namespace-names)
        namespace-order
        (loop [remaining dependencies
               ordered []]
          (if (empty? remaining)
            ordered
            (let [ready (->> remaining
                             (keep (fn [[namespace-name required]]
                                     (when (empty? required) namespace-name)))
                             (sort-by str)
                             vec)]
              (when (empty? ready)
                (throw
                 (ex-info
                  "Program acquisition found a namespace binding cycle."
                  {:seon.error/at (java.util.Date.)
                    :seon.error/layer :seon.sci.eval/program
                    :seon.error/operation 'seon.sci.eval/acquire-program!
                    :seon.error/message "Namespace bindings form a cycle; remove the cyclic dependency before acquisition."
                    :seon.error/offending remaining
                    ::pending-namespaces (set (keys remaining))
                    :seon.error/member :seon.ns/requires
                    :seon.error/expected :seon.sci.eval/acyclic-bindings})))
              (let [released (set ready)]
                (recur
                 (into {}
                       (map (fn [[namespace-name required]]
                              [namespace-name
                               (apply disj required released)]))
                       (apply dissoc remaining ready))
                 (into ordered ready))))))
        install-row
        (fn [state row]
          (try
            (let [installed
                  (install-row!
                   {:seon.sci.eval/ctx
                    (assoc ctx :seon.schema/projection
                           (:seon.schema/projection state))
                    :seon.db/db db
                    ::prepared-projection (:seon.schema/projection state)
                    :seon.program/row row})]
              (if (or (contains? installed :seon.instrument/check)
                      (:seon.sci.eval/refused-function installed))
                (update state (refusal-member row) (fnil conj [])
                        (acquisition-refusal row installed))
                (cond-> (assoc state
                       :seon.schema/projection
                       (:seon.schema/projection installed)
                       :seon.sci.eval/installed
                       (+ (:seon.sci.eval/installed state)
                          (:seon.sci.eval/installed installed)))
                  (:seon.sci.eval/load-result installed)
                  (update :seon.sci.eval/load-results (fnil conj [])
                          (:seon.sci.eval/load-result installed)))))
            (catch Throwable failure
              (when (:seon.instrument/registration-observation (error/refusal failure))
                (throw failure))
              (update state (refusal-member row) (fnil conj [])
                      (acquisition-refusal row failure)))))]
    ;; Imports are explicit namespace facts. Install their named classes before
    ;; the namespace bindings resolve them; SCI is containment, not a security
    ;; boundary, and the program graph—not a hand list—declares the set.
    (install-declared-classes! ctx all-namespace-rows)
    ;; Create every namespace and publish aliases first. Alias targets need not
    ;; exist: this is the effective behavior of SCI's `:as-alias` too. Every
    ;; program namespace needs its reader context, interpreted or not: an
    ;; agent's first definition in a clean namespace is analysed against it.
    (doseq [[namespace-name row] all-namespace-row-by-name]
      (sci/install-namespace-bindings!
       ctx namespace-name (assoc (row-bindings row) :refers {})))
    ;; Namespace declarations establish aliases/imports first; compiled host
    ;; bindings then populate the same SCI namespaces without being erased by
    ;; that declaration install. Selected definitions overwrite only their Vars.
    (install-first-party-namespaces!
     ctx namespace-assertions all-namespace-rows
     all-function-rows)
    ;; Interpreted callers analyze against every other name their compiled
    ;; namespace reaches; only this branch's rows become SCI definitions.
    (install-caller-host-bindings!
     ctx (keep all-namespace-row-by-name (keys function-rows-by-ns)))
    ;; Copy inherited Vars into this generation before analyzing any caller.
    ;; SCI definitions then replace roots in those same Vars; a caller analyzed
    ;; before its callee must not retain the old generation's copied root.
    (doseq [sym interpreted
            :let [candidate (sci/resolve ctx sym)]
            :when candidate]
      (sci/bind-root! ctx candidate @candidate))
    ;; The bare REPL name refers to the acquired macro itself. Keeping the
    ;; boot-time copy here would preserve its old expansion after adoption.
    (sci/add-namespace! ctx 'clojure.core
                        {'help (sci/resolve ctx 'seon.bootstrap/help)})
    (let [functions-installed
          (reduce
           (fn [state namespace-name]
             ;; Each function installs its exact bindings inside the named
             ;; refusal boundary. A refused dependency must not escape here
             ;; as an unclassified namespace-binding exception.
             (when (empty? (get function-rows-by-ns namespace-name))
               (when-let [row (get namespace-row-by-name namespace-name)]
                 (sci/install-namespace-bindings!
                  ctx namespace-name (row-bindings row))))
             ;; Rows carry no definition order, and SCI resolves a body's
             ;; free symbols when it analyzes it. Declare every new Var of
             ;; the namespace first, as Clojure's `declare` does, so a caller
             ;; installed before its callee resolves the Var the callee binds.
             (when-let [undeclared
                        (seq (keep (fn [[sym]]
                                     (when-not (sci/resolve ctx (symbol sym))
                                       (symbol (name sym))))
                                   (get function-rows-by-ns namespace-name)))]
               (sci/binding [sci/ns (sci/create-ns namespace-name)]
                 (sci/eval-form ctx (list* 'declare undeclared))))
             (reduce
              install-row
              (cond-> state
                (::agent-authored?
                 (get namespace-row-by-name namespace-name))
                (update :seon.sci.eval/installed inc))
              (map (fn [[sym source _ admission private?]]
                     {:seon.fn/sym sym
                      :seon.schema.admission/source admission
                      :seon.fn/source source
                      :seon.fn/ns [:seon.ns/name namespace-name]
                      :seon.fn/private? private?
                      :seon.fn/arglists
                      (:seon.fn/arglists (db/pull db [:seon.fn/arglists]
                                                [:seon.fn/sym sym]))
                      ::skip-contract-install?
                      (not (agent-authored? admission))})
                   (sort-by first
                            (get function-rows-by-ns namespace-name)))))
           {:seon.schema/projection projection
            :seon.sci.eval/interpreted-count (count interpreted)
            :seon.sci.eval/installed 0
            :seon.sci.eval/load-results
            (into []
                  (keep (fn [[sym _source _namespace admission _private]]
                          (when (and (= :core admission)
                                     (nil? (sci/resolve ctx (symbol sym))))
                            {:seon.fn/sym sym
                             :seon.sci.eval/load-state :unavailable
                             :seon.error/message
                             (str "The loaded JVM has no core definition " sym ".")})))
                  all-function-rows)}
           namespace-order)]
      ;; Tests resolve only after every namespace's functions and exact
      ;; bindings are present. This makes renamed `deftest` deterministic.
      (record-acquisition-refusals!
       ctx db
       (reduce
        (fn [state namespace-name]
          (reduce
           install-row
           state
           (map (fn [[sym source _ admission]]
                  {:seon.test/sym sym
                   :seon.schema.admission/source admission
                   :seon.test/source source
                   :seon.test/ns [:seon.ns/name namespace-name]})
                (sort-by first (get test-rows-by-ns namespace-name)))))
        functions-installed
        namespace-order)
       commit-fault!)))))))

(defn- latest-print-fact
  "The value of one print attribute on this agent's latest evaluation to set it."
  [db agent-id attribute]
  (when-let [rows (seq (db/q '[:find ?evaluation ?value
                               :in $ ?agent-id ?attribute
                               :where
                               [?agent :seon.agent/id ?agent-id]
                               [?run :seon.turn/agent ?agent]
                               [?evaluation :seon.cluster.eval/run ?run]
                               [?evaluation ?attribute ?value]]
                             db agent-id attribute))]
    (second (last (sort-by first rows)))))

(defn- session-print-options
  "The print options this agent's session is already in effect with.

  A REPL session keeps a form's `set!` of `*print-length*` until another form
  changes it, and an agent's session spans turns. So the turn's opening value
  is DERIVED from the agent's own latest evaluation that recorded one — the
  stored fact settlement already writes — rather than remembered in the fork
  that happened to run it. An agent that never set one gets no key, and the
  shipped default stands."
  [db agent-id]
  (when agent-id
    (let [length (latest-print-fact db agent-id :seon.print/length)
          level (latest-print-fact db agent-id :seon.print/level)]
      (cond-> {}
        (int? length) (assoc :seon.print/length length)
        (int? level) (assoc :seon.print/level level)))))

(defn- base-bindings
  "Snapshot program roots to distinguish the entering private layer."
  [ctx]
  (into {}
        (mapcat
         (fn [[namespace-name bindings]]
           (map (fn [[binding-name binding]]
                  [[namespace-name binding-name]
                   (if (sci.utils/var? binding)
                     [(if (sci.vars/hasRoot binding) @binding absent-intern)
                      (dissoc (meta binding) :sci/generation)]
                     [binding])])
                bindings)))
        (sci/namespace-state ctx)))

(defn- same-program-root?
  "Contract wrappers retain their original interpreted callable as metadata."
  [left right]
  (identical? (get (meta left) ::instrument/interpreted-original left)
              (get (meta right) ::instrument/interpreted-original right)))

(defn- replaced-definitions
  "The `symbols` whose program row changed between two acquired program
  snapshots. A context's own definition survives its symbol's first lazy
  install, but at an idle boundary the program advances in place over it."
  [before after symbols]
  (into (sorted-set)
        (filter #(not= (get-in before [:functions % ::function-source])
                       (get-in after [:functions % ::function-source])))
        symbols))

(defn- regenerate-agent-context!
  "Fork the replacement base and carry the private layer as actual objects.

  The private layer is what the agent's own evaluation added: Vars its SCI
  generation defined, and Vars at a path the base it forked from never had (a
  namespace it required from the JVM, `load-host-namespace`). Owned private
  Vars keep their roots, including captured values. Inherited program bindings
  are replaced. A JVM restart has no entering context and
  consequently preserves no private objects. No database read restores them.
  An unavailable execution's private layer is the unrestored one, so it
  carries nothing: the context is the base acquired at the branch head."
  [ctx base]
  (let [generation (:sci/generation @(:env ctx))
        layer (if (::unavailable-execution @(:env ctx)) {} (sci/namespace-state ctx))
        previous @(::base-bindings ctx)
        current (base-bindings base)
        changed? #(seq (replaced-definitions @(::kernel/program-snapshot ctx)
                                             @(::kernel/program-snapshot base)
                                             [(symbol (str %1) (str %2))]))
        private-bindings
        (for [[namespace-name entries] layer
              [local-name entry] entries
              :when (sci.utils/var? entry)
              :let [path [namespace-name local-name]
                    root (if (sci.vars/hasRoot entry) @entry absent-intern)]
              :when (and (or (= generation (:sci/generation (meta entry)))
                             (not (contains? previous path)))
                         (not (changed? namespace-name local-name))
                         (not (or (when-let [prior (get previous path)]
                                    (same-program-root? root (first prior)))
                                  (when-let [next (get current path)]
                                    (same-program-root? root (first next))))))]
          [namespace-name local-name entry])
        private-names (into #{} (map (fn [[namespace-name local-name _]]
                                      (symbol (str namespace-name) (str local-name))))
                            private-bindings)
        regenerated
        (assoc (merge ctx (sci/fork base))
               ::base-bindings (atom current)
               ::kernel/installed-functions
               (atom (into @(::kernel/installed-functions base)
                           (filter private-names)
                           @(::kernel/installed-functions ctx)))
               ::kernel/program-snapshot
               (atom (update @(::kernel/program-snapshot base) :functions merge
                             (select-keys (:functions @(::kernel/program-snapshot ctx))
                                          private-names)))
               :seon.sci.eval/private-state :preserved-in-memory)]
    ;; Keep the continuing agent's generation and owned Vars. Re-interning
    ;; only their roots would detach private closures from their private Vars.
    ;; Every inherited base Var still belongs to a different generation.
    (swap! (:env regenerated) assoc :sci/generation generation)
    (doseq [[namespace-name local-name entry] private-bindings]
      (sci/add-namespace! regenerated namespace-name {local-name entry}))
    (doseq [[namespace-name entries] layer
            [local-name entry] entries
            :let [prior (first (get previous [namespace-name local-name]))]
            :when (and (not (sci.utils/var? entry)) (not= entry prior))]
      (swap! (:env regenerated) update-in [:namespaces namespace-name local-name]
             (fn [current]
               (if (and (map? entry) (map? prior) (map? current))
                 (merge (apply dissoc current (remove (set (keys entry)) (keys prior)))
                        (into {} (remove (fn [[key value]] (= value (get prior key)))) entry))
                 entry))))
    ;; Agent graphs retain the entering handle. Preserve its owned atoms while
    ;; replacing the environment with the regenerated fork's environment.
    (reset! (:env ctx) @(:env regenerated))
    (reset! (::base-bindings ctx) current)
    (reset! (::kernel/installed-functions ctx)
            @(::kernel/installed-functions regenerated))
    (reset! (::kernel/program-snapshot ctx)
            @(::kernel/program-snapshot regenerated))
    ctx))

(defn fork-for-turn
  "Fork the current base and reapply the agent's in-memory private layer."
  {:malli/schema [:=> [:cat :seon.sci.eval/defs-fork-request]
                  :seon.sci.eval/defs-fork-result]}
  [{base-ctx :seon.sci.eval/ctx
    agent-ctx :seon.sci.eval/agent-ctx
    db :seon.db/db
    agent-id :seon.agent/id}]
  (let [carried? (and agent-ctx (not (::unavailable-execution @(:env agent-ctx))))
        ctx (if agent-ctx
              (regenerate-agent-context! agent-ctx base-ctx)
              (env/carry-state
               (assoc (sci/fork base-ctx)
                             ::turn-fork? true
                             ::base-bindings (atom (base-bindings base-ctx))
                             ::result-objects (atom {})
                             ::kernel/installed-functions
                             (atom @(::kernel/installed-functions base-ctx))
                             ::kernel/program-snapshot
                             (atom @(::kernel/program-snapshot base-ctx))
                             ::print-session
                             (atom (or (session-print-options db agent-id) {})))
               (if (env/environment? (env/of base-ctx))
                 (env/environment-state (env/of base-ctx))
                 (projection-state db (context-projection base-ctx)))))
        ctx (cond-> (assoc ctx ::bind-result! #'bind-result!)
              (contains? base-ctx ::custody) (assoc ::base-ctx base-ctx))
        assigned-namespace (agent-namespace db agent-id)]
    (advance-context-projection! ctx db (context-projection base-ctx))
    (when (and assigned-namespace (not (sci/find-ns ctx assigned-namespace)))
      (sci/add-namespace! ctx assigned-namespace {}))
    {:seon.sci.eval/ctx ctx
     :seon.sci.eval/private-state
     (if carried? :preserved-in-memory :absent)}))

(def program-cache-policy
  "Bound the memoized program identities and base contexts independently of commits."
  {::program-identity-cache-size 256
   ::program-identity-cache-reason
   "One entry per recently acquired commit: agent turns, MCP evaluations and test requests on a few live branches; an evicted commit derives its identity again from its own cache-context."
   ::base-context-cache-size 4
   ::base-context-cache-reason
   "A base context holds a whole interpreted program; four recent program identities cover the live branch and concurrent test requests without retaining an unbounded population."})

(defonce ^:private program-identity-cache
  (cache/lru-cache-factory
   {} :threshold (::program-identity-cache-size program-cache-policy)))

(defonce ^:private base-context-cache
  (cache/lru-cache-factory
   {} :threshold (::base-context-cache-size program-cache-policy)))

(defn- acquisition-attributes
  "Every attribute an acquisition reads, derived from the projection's declarations.

  The program partition (`seon.program/program-attributes`), the projection's own
  read set (`seon.schema/projection-attributes`), the contract dials an interpreted
  wrapper arms with (`seon.config/dial-attributes`) and the loaded source commit.
  Retained on the projection's own holder (`schema/projection-cache-value`), so
  two populations whose fingerprints collide never share a derivation."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:set :qualified-keyword]]}
  [projection]
  (schema/projection-cache-value
   projection ::acquisition-attributes
   (fn []
     (-> (program/program-attributes projection)
         (into schema/projection-attributes)
         (into (config/dial-attributes projection))
         (conj :seon.config/cluster :seon.source/commit-id)))))

(defn- revision-basis
  "The part of a value's Datahike cache-context that names its attribute revisions.

  Datahike advances one attribute revision per attribute a commit touched and the
  conservative revision on a schema or unknown change
  (`reference-code/datahike/src/datahike/query.cljc:2568-2589`); its query cache
  carries results across commits by comparing exactly these members
  (`source-context-unchanged?`, `:2963-2975`). Revisions are comparable within
  one connection generation, which the basis names."
  {:malli/schema [:=> [:cat :seon.db/database-value] :map]}
  [database]
  (select-keys (:cache-context database)
               [:datahike.cache/connection-id
                :datahike.cache/generation
                :datahike.cache/conservative-revision
                :datahike.cache/attribute-revisions]))

(defn- program-basis
  "A revision basis restricted to the attributes an acquisition reads."
  {:malli/schema [:=> [:cat :map [:set :qualified-keyword]] :map]}
  [basis attributes]
  (update basis :datahike.cache/attribute-revisions select-keys attributes))

(defn- commit-id-of
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :nil :uuid]]}
  [database]
  (let [value-id (db/committed-value-identity database)]
    (when (map? value-id) (:datahike.value/commit-id value-id))))

(defn- remember-program-identity!
  "Record a commit's revision basis unless its commit already has one."
  {:malli/schema [:=> [:cat :uuid :map] :map]}
  [commit-id basis]
  @(cache/lookup-or-miss program-identity-cache commit-id (fn [_] (delay basis))))

(defn program-identity
  "The revision basis naming the program a committed value holds, or nil.

  Memoized by commit id, which names one immutable root
  (`reference-code/datahike/src/datahike/db.cljc:385`): a branch opened at a commit
  reads the basis its parent recorded when the commit landed
  (`watch-program-identity!`), so its program compares with the parent's. A commit
  proven program-equal to an acquired value takes that value's basis
  (`acquired-database?`). Compare two identities through `program-basis` over the
  acquisition attributes. A value without committed identity has none."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :nil :map]]}
  [database]
  (when-let [commit-id (commit-id-of database)]
    (remember-program-identity! commit-id (revision-basis database))))

(defn- watch-program-identity!
  "Record each committed value's revision basis as its commit lands.

  A system-side `d/listen` observer (the seam AGENTS names for notification): the
  callback is one select over the committed value's cache-context, no read."
  {:malli/schema [:=> [:cat :seon.db/connection] :keyword]}
  [connection]
  (d/listen connection ::program-identity
            (fn [report] (program-identity (:db-after report))))
  ::program-identity)

(defn- derive-base-ctx
  "Derive one base context: build the interpreter and acquire the program."
  {:malli/schema [:=> [:cat :seon.db/database-value :map] :seon.sci.eval/ctx]}
  [database arm-request]
  (let [projection (if-let [carried (db/carried-projection database)]
                     (schema/projection-from-database database carried)
                     (schema/projection-from-database database))]
    (schema/call-with-projection
     projection
     (fn []
       (let [ctx (assoc (build-base-ctx projection)
                        ::bind-result! #'bind-result!
                        :seon.schema/projection projection
                        ::kernel/install-function! install-function-from-database!)
             _ (swap! (::kernel/program-snapshot ctx) merge
                      (select-keys arm-request [:seon.flow/commit-fault! ::loaded-database]))
             acquired (acquire-program! {:seon.sci.eval/ctx ctx
                                 :seon.db/db database
                                 :seon.schema/projection projection})]
         ;; The base is memoized across branches and holds no database value:
         ;; one would pin its connection's node cache. `copy-base-ctx` supplies
         ;; each caller's database and loaded program.
         (swap! (::kernel/program-snapshot ctx)
                #(-> (dissoc % ::loaded-database)
                     (assoc ::acquisition acquired
                            ::program-namespaces
                            {::generation (:sci/generation @(:env ctx))
                             ::namespaces (sci/namespace-state ctx)}
                            :seon.test/class-loader (clojure.lang.RT/baseLoader))))
         (assoc ctx ::acquisition acquired))))))

(defn- base-ctx-key
  "The inputs a base context is a function of, or nil when one has no identity.

  The program identity of the database, the loaded program it compares against
  and the fault recorder its contract wrappers close over."
  {:malli/schema [:=> [:cat :seon.db/database-value :map]
                  [:or :nil [:tuple :map [:or :nil :uuid] [:or :nil :seon.flow/commit-fault!]]]]}
  [database arm-request]
  (let [program (some-> (program-identity database)
                        (program-basis (acquisition-attributes
                                        (db/carried-projection database))))
        loaded (::loaded-database arm-request)
        loaded-id (when loaded (db/committed-value-identity loaded))]
    (when (and program (or (nil? loaded) (map? loaded-id)))
      [program
       (when loaded (:datahike.value/commit-id loaded-id))
       (:seon.flow/commit-fault! arm-request)])))

(defn- copy-base-ctx
  "A fresh context over a memoized base: SCI's generation-aware fork plus owned
  copies of the kernel atoms, acquired at the supplied value against the
  caller's loaded program (the base's key names its commit)."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :map] :seon.sci.eval/ctx]}
  [cached database arm-request]
  (assoc (sci/fork cached)
         ::kernel/installed-functions (atom @(::kernel/installed-functions cached))
         ::kernel/program-snapshot
         (atom (assoc @(::kernel/program-snapshot cached)
                      :seon.db/db database
                      ::loaded-database (or (::loaded-database arm-request)
                                            (loaded-program database))))))

(defn base-ctx
  "Derive the program-only SCI context from one database value.

  Matching definitions copy the loaded JVM Var root. Overrides and affected
  callers interpret their admitted source. Acquisition refusals remain values on the context;
  construction has no connection, writes no facts, and restores no private state.
  A base context is a function of its program identity, loaded program and fault
  recorder, memoized by that key; each call returns its own fork of the value."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value] :seon.sci.eval/ctx]
    [:=> [:cat :seon.db/database-value
          [:map [:seon.flow/commit-fault! {:optional true} :seon.flow/commit-fault!]]]
     :seon.sci.eval/ctx]]}
  ([database] (base-ctx database {}))
  ([database arm-request]
   (if-let [cache-key (base-ctx-key database arm-request)]
     (let [cell (cache/lookup-or-miss
                 base-context-cache cache-key
                 (fn [_] (delay (derive-base-ctx database arm-request))))]
       (copy-base-ctx
        (try @cell
             (catch Throwable failure
               ;; A failed derivation is not a value of the key: drop the
               ;; cell so the next call derives again, and surface the cause.
               (cache/evict base-context-cache cache-key)
               (throw failure)))
        database arm-request))
     (copy-base-ctx (derive-base-ctx database arm-request) database arm-request))))

(defn- loaded-basis
  "The revision of the adoption record's attribute in the loaded source.

  Datahike advances an attribute's revision exactly when a commit touches it
  (`revision-basis`), so an equal basis proves the record did not move."
  {:malli/schema [:=> [:cat :seon.db/database-value] :map]}
  [source]
  (program-basis (revision-basis source) #{:seon.source/commit-id}))

(defn- loaded-program-current?
  "Whether the program this acquisition compared against is still the JVM's loaded one.

  An unmoved record answers from its attribute revision; a moved one compares
  the commit it names with the loaded commit the acquisition used, and an equal
  answer records the new revision so the next call reads it."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value] :boolean]}
  [ctx database]
  (let [snapshot @(::kernel/program-snapshot ctx)
        source (loaded-source snapshot database)
        basis (loaded-basis source)]
    (or (= (::loaded-basis snapshot) basis)
        (when (= (some-> (::loaded-database snapshot) db/commit-id)
                 (loaded-commit-id source))
          (swap! (::kernel/program-snapshot ctx) assoc ::loaded-basis basis)
          true)
        false)))

(defn acquired-database?
  "Whether this context already acquired the program the supplied value holds.

  True for the acquired value itself, and for any committed value whose program
  identity (restricted to the acquisition attributes) equals the acquired one: a
  commit that wrote only receipts, turns, messages, test results or artifacts
  leaves those revisions alone, and a branch opened at such a commit reads its
  parent's recorded basis. A later value of the acquired value's own lineage with
  equal revisions also matches and records its commit under the acquired identity.
  A value without committed identity never matches."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value] :boolean]}
  [ctx database]
  (let [snapshot @(::kernel/program-snapshot ctx)
        previous (:seon.db/db snapshot)
        commit-id (commit-id-of database)]
    (boolean
     (and (::acquisition snapshot)
          previous
          ;; An adoption moves the JVM's loaded program without touching this
          ;; branch's rows; an acquisition compared against the older one is stale.
          (loaded-program-current? ctx database)
          (or (identical? previous database)
              (when (and commit-id (commit-id-of previous))
                (let [attributes (acquisition-attributes (db/carried-projection previous))
                      acquired (program-identity previous)]
                  (or (= (program-basis acquired attributes)
                         (program-basis (program-identity database) attributes))
                      ;; The basis carries connection and generation, so this
                      ;; equality also proves one lineage.
                      (when (= (program-basis (revision-basis database) attributes)
                               (program-basis (revision-basis previous) attributes))
                        ;; A branch opened at this commit reads the acquired
                        ;; program by commit id from here on.
                        (cache/evict program-identity-cache commit-id)
                        (remember-program-identity! commit-id acquired)
                        true)))))))))

(defonce ^:private refusal-recording-cache
  (cache/lru-cache-factory
   {} :threshold (::program-identity-cache-size program-cache-policy)))

(defn- record-refusals-once!
  "Record one program's acquisition refusals once per recording target.

  A refusal is a fact about the program rows, so re-acquiring the same program
  identity (another context, another commit of the same program) records nothing
  again. Concurrent acquirers share the one delay; no monitor is held."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :map
                       [:or :nil :seon.flow/commit-fault!]] :map]}
  [ctx database acquired commit-fault!]
  (let [program (some-> (program-identity database)
                        (program-basis (acquisition-attributes
                                        (db/carried-projection database))))
        ;; The target's identity, never the Connection object: a released
        ;; Connection keeps its metadata and so its whole world reachable.
        ;; `db/connection-identity` is the dependency's connection key, or the
        ;; typed unavailable result for released custody.
        connection (:seon.db/connection (::custody ctx))
        target (if connection (db/connection-identity connection) commit-fault!)
        refusals? (or (seq (::acquisition-refusals acquired)) (seq (::agent-mistakes acquired)))]
    (cond
      (and refusals? connection (not (:datahike/connection-id target)))
      (assoc acquired ::acquisition-refusals-recorded? false
             ::acquisition-recording-error target)

      (and refusals? program target)
      (let [cache-key [program target]
            cell (cache/lookup-or-miss
                  refusal-recording-cache cache-key
                  (fn [_] (delay (select-keys
                                  (record-acquisition-refusals!
                                   ctx database acquired commit-fault!)
                                  [::acquisition-refusals-recorded?
                                   ::acquisition-recording-error]))))]
        (merge acquired
               (try @cell
                    (catch Throwable failure
                      (cache/evict refusal-recording-cache cache-key)
                      (throw failure)))))

      :else (record-acquisition-refusals! ctx database acquired commit-fault!))))

(defn acquire!
  "Acquire the program the supplied database holds in this cluster context.

  The existing context identity belongs to cluster handles; replacing its
  program environment lets their retained forks regenerate from that base.
  A value holding the acquired program returns at once. Otherwise the program's
  base context is a memoized function of its identity (`base-ctx`), so
  concurrent acquirers of one program share one derivation and hold no monitor;
  each installs an equal fork of it."
  {:malli/schema [:=> [:cat :seon.sci.eval/acquire-request] :map]}
  [{ctx :seon.sci.eval/ctx database :seon.db/db
    commit-fault! :seon.flow/commit-fault!}]
  (let [snapshot @(::kernel/program-snapshot ctx)]
    (if (acquired-database? ctx database)
      (do
        ;; Same program: the acquired value advances to the supplied one, so
        ;; a branch opened at it holds the acquired program by commit id.
        (when-not (identical? database (:seon.db/db snapshot))
          (swap! (::kernel/program-snapshot ctx) assoc :seon.db/db database)
          (advance-context-projection! ctx database (context-projection ctx)))
        (::acquisition snapshot))
      (let [commit-fault! (or commit-fault! (:seon.flow/commit-fault! snapshot))
            _ (load-core-namespaces! database)
            source (loaded-source snapshot database)
            generated (base-ctx database
                                (cond-> {::loaded-database (loaded-program source)}
                                  commit-fault!
                                  (assoc :seon.flow/commit-fault! commit-fault!)))
            acquired (::acquisition generated)
            ;; This context's own definitions (its generation's Vars that no
            ;; program install bound) survive as objects, as
            ;; `regenerate-agent-context!` carries them for a turn fork,
            ;; unless the program changed their symbol's row.
            generation (:sci/generation @(:env ctx))
            installed @(::kernel/installed-functions ctx)
            interns (turn-interns ctx)
            replaced (replaced-definitions snapshot @(::kernel/program-snapshot generated)
                                           (keys interns))
            own (into [] (keep (fn [[qualified {sci-var :seon.sci.eval/var}]]
                                 (when-not (or (and (contains? installed qualified)
                                                    (not (get-in snapshot [:functions qualified ::context-defined?])))
                                               (contains? replaced qualified))
                                   [(symbol (namespace qualified)) (symbol (name qualified)) sci-var])))
                      interns)]
        ;; SCI stamps later definitions with this generation. Installing the
        ;; unforked base loses the provenance that definition-row observes.
        (reset! (:env ctx) @(:env (sci/fork generated)))
        (when generation (swap! (:env ctx) assoc :sci/generation generation))
        (doseq [[namespace-name local-name sci-var] own]
          (sci/add-namespace! ctx namespace-name {local-name sci-var}))
        (reset! (::kernel/installed-functions ctx) @(::kernel/installed-functions generated))
        (advance-context-projection! ctx database (:seon.schema/projection generated))
        (let [recorded (record-refusals-once! ctx database acquired commit-fault!)]
          ;; The memoized base is shared by key; the loaded authority belongs
          ;; to this context, not to the base.
          (reset! (::kernel/program-snapshot ctx)
                  (cond-> (assoc @(::kernel/program-snapshot generated)
                                 ::acquisition recorded
                                 ::loaded-basis (loaded-basis source))
                    (::loaded-connection snapshot)
                    (assoc ::loaded-connection (::loaded-connection snapshot))))
          recorded)))))

(defn- acquire-function-from-database!
  "Acquire the lazy program before its first named invocation."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value
                      :qualified-symbol]
                  :qualified-symbol]}
  [ctx database function-symbol]
  (acquire! {:seon.sci.eval/ctx ctx :seon.db/db database})
  (kernel/ensure-function!
   (assoc ctx ::kernel/install-function! install-function-from-database!)
   database function-symbol))

(declare cluster-ctx*)

(defn cluster-ctx
  "Build one cluster's interpreter; acquire its program on first use."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value] :seon.sci.eval/ctx]
    [:=> [:cat :seon.db/database-value :seon.db/connection]
     :seon.sci.eval/ctx]
    [:=> [:cat :seon.db/database-value :seon.db/connection
          :seon.sci.eval/projection-state]
     :seon.sci.eval/ctx]
    [:=> [:cat :seon.db/database-value :seon.db/connection
          :seon.sci.eval/projection-state
          [:map [:seon.flow/commit-fault! {:optional true} :seon.flow/commit-fault!]
           [:seon.sci.eval/loaded-connection {:optional true} :seon.db/connection]]]
     :seon.sci.eval/ctx]]}
  ;; EACH ARITY HANDS ON ONLY WHAT IT HAS. Delegating through the widest
  ;; arity with `nil` made the function violate its own declared contract the
  ;; moment instrumentation was armed — which is every live cluster, and now
  ;; the gate too — and it wrote a stored nil into the ctx's custody besides.
  ([db]
   (cluster-ctx* db nil nil {}))
  ([db connection]
   (cluster-ctx* db connection nil {}))
  ([db connection supplied-projection-state]
   (cluster-ctx* db connection supplied-projection-state {}))
  ([db connection supplied-projection-state arm-request]
   (cluster-ctx* db connection supplied-projection-state arm-request)))

(defn- cluster-ctx*
  [db connection supplied-projection-state arm-request]
   (let [projection (or (some-> supplied-projection-state deref :seon.schema/projection)
                        (db/carried-projection db)
                        (schema/projection-from-database db))
         ctx (assoc (build-base-ctx projection)
                    ::bind-result! #'bind-result!
                    ::kernel/install-function! acquire-function-from-database!
                    ::custody (cond-> {}
                                connection (assoc :seon.db/connection connection)
                                (nil? connection) (assoc :seon.db/db db)))
         _ (swap! (::kernel/program-snapshot ctx) merge
                  {::loaded-database (loaded-program (loaded-source arm-request db))}
                  (select-keys arm-request
                               [:seon.flow/commit-fault! ::loaded-database ::loaded-connection]))
         projection-state (or supplied-projection-state
                              (projection-state db projection (carried-cluster-name connection db)))
         ctx (env/carry-state
              (assoc ctx :seon.schema/projection projection)
              projection-state)
         ;; `my.program/supplied-context` names the cluster base it mutates.
         ctx (assoc ctx :my.program/base-ctx ctx)]
     ;; Only the program identity observer needs the live connection.
     (when connection
       (watch-program-identity! connection))
     ctx))

(defn fork-cluster-ctx
  "Fork an acquired program ctx for one sovereign database connection.

  Program Vars remain copy-on-write through SCI's generation-aware `fork`;
  connection custody, schema projection, and supplied-default state are
  reconstructed from the receiving branch and therefore cannot leak between
  sibling clusters."
  {:malli/schema
   [:function
    [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value
          :seon.db/connection]
     :seon.sci.eval/ctx]
    [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value
          :seon.db/connection :seon.sci.eval/projection-state]
     :seon.sci.eval/ctx]
    [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value
          :seon.db/connection :seon.sci.eval/projection-state
          [:map [:seon.flow/commit-fault! {:optional true} :seon.flow/commit-fault!]
           [:seon.env/environment {:optional true} :seon.env/environment]
           [:seon.sci.eval/loaded-connection {:optional true} :seon.db/connection]]]
     :seon.sci.eval/ctx]]}
  ;; The fork keeps the base context's cluster, else the one its world names.
  ([base-ctx db connection]
   (fork-cluster-ctx base-ctx db connection
                     (projection-state db (schema/projection-from-database db)
                                       (or (:seon.boot/cluster-name (env/of base-ctx))
                                           (carried-cluster-name connection db)))
                     (if-let [environment (env/of base-ctx)]
                       {:seon.env/environment environment}
                       {})))
  ([base-ctx db connection supplied-projection-state]
   (fork-cluster-ctx base-ctx db connection supplied-projection-state {}))
  ([base-ctx db connection supplied-projection-state arm-request]
   (let [projection (or (:seon.schema/projection
                         (some-> supplied-projection-state deref))
                        (schema/projection-from-database db))
         receiving-environment
         (env/refuse-incomplete-environment!
          (env/environment
           (assoc (dissoc (or (:seon.env/environment arm-request)
                             (some-> supplied-projection-state deref)
                             (env/of base-ctx))
                          :seon.db/db :seon.agent/id :seon.turn/id
                          :my.program/executing-ctx :seon.sci.kernel/arm)
                  :seon.db/connection connection
                  :seon.db/basis-t (db/basis-t db)
                  :seon.schema/projection projection)))
         projection-state (env/environment-state receiving-environment)
         _ (db/carry-connection-projection-state! connection projection-state)
         ctx (env/carry-state
               (assoc (sci/fork base-ctx)
                      ::kernel/installed-functions
                      (atom @(::kernel/installed-functions base-ctx))
                      ::kernel/program-snapshot
                      (atom (merge (dissoc @(::kernel/program-snapshot base-ctx)
                                           :seon.flow/commit-fault!)
                                   (select-keys arm-request
                                                [:seon.flow/commit-fault! ::loaded-database
                                                 ::loaded-connection])))
                      ::custody {:seon.db/connection connection}
                      :seon.schema/projection projection)
               projection-state)
         ctx (assoc ctx :my.program/base-ctx ctx)]
     ;; A sovereign branch must not inherit the source branch's recorder.
     ;; SCI's generation-aware bind-root! changes only this fork's Vars.
     (doseq [function-symbol @(::kernel/installed-functions ctx)
             :let [candidate (sci/resolve ctx function-symbol)]
             :when (and candidate
                        (::instrument/interpreted-original (meta @candidate)))]
       (install-function-contract!
        ctx (db/pull db '[*] [:seon.fn/sym function-symbol]) projection db))
     (when connection
       (watch-program-identity! connection))
     ctx)))

(defn- declared-row
  [{event :seon.sci.eval/event
    eval-form! :seon.sci.eval/eval-form!
    projection :seon.schema/projection}]
  (let [raw-row (row event projection)
        unregister-key (deleted-schema-key raw-row)
        schema-delta
        (when (or (:seon.schema/key raw-row) unregister-key)
          (schema/begin-registration-delta projection))
        schema-value
        (when schema-delta
          (schema/call-with-registration-delta schema-delta eval-form!))
        live-declaration? (and raw-row (nil? schema-delta))
        base-declared-row
        (if schema-delta
          (if unregister-key
            (do
              (when-not (and (= unregister-key schema-value)
                             (nil? (schema/registration-delta-form
                                    schema-delta unregister-key)))
                (throw
                 (ex-info
                  "Schema deletion did not unregister its reader identity."
                  (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.eval/program 'seon.sci.eval/declared-row
                   {:seon.error/message "Evaluated schema identity differs from its declaration; return the declared identity."
                    :seon.error/offending schema-value
                    ::schema-refused unregister-key
                    :seon.error/expected :seon.sci.eval/registered-reader-identity}))))
              ;; Dependency validation is pure here. Current database data
              ;; is fenced by the terminal transaction against db-before.
              (schema/projection-without-schema projection unregister-key)
              raw-row)
            (let [schema-key (:seon.schema/key raw-row)
                  definition
                  (schema/registration-delta-form schema-delta schema-key)]
              (when-not (and (= schema-key schema-value) definition)
                (throw
                 (ex-info
                  "Schema declaration did not register its reader identity."
                  (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.eval/program 'seon.sci.eval/declared-row
                   {:seon.error/message "Evaluated schema identity differs from its declaration; return the declared identity."
                    :seon.error/offending schema-value
                    ::schema-refused schema-key
                    :seon.error/expected :seon.sci.eval/registered-reader-identity}))))
              ;; Validate the actual evaluated value while the overlay is
              ;; isolated. The terminal transaction repeats this pure
              ;; candidate validation against its mid-transaction db value.
              (let [candidate-projection
                    (schema/projection-with-schema
                     projection schema-key definition
                     {:seon.schema.admission/source :agent})
                    forms (:seon.schema.projection/forms candidate-projection)]
                (program/with-contract-facts
                 {:seon.program/row
                  (accretion/schema-row
                   forms (assoc raw-row :seon.schema/form (pr-str definition)))
                  :seon.program/compile-options
                  (:seon.schema.projection/compile-options candidate-projection)
                  :seon.program/predicate-functions
                  (schema/predicate-functions-in candidate-projection)
                  :seon.program/schema-keys (set (keys forms))
                  :seon.program/schema-forms forms}))))
          raw-row)
        base-declared-row
        (if (:seon.fn/spec base-declared-row)
          (program/with-contract-facts
           {:seon.program/row base-declared-row
            :seon.program/compile-options
            (:seon.schema.projection/compile-options projection)
            :seon.program/predicate-functions
            (schema/predicate-functions-in projection)
            :seon.program/schema-keys
            (set (keys (:seon.schema.projection/forms projection)))})
          base-declared-row)]
    {:seon.sci.eval/base-declared-row base-declared-row
     :seon.sci.eval/live-declaration? live-declaration?
     :seon.sci.eval/schema-value schema-value
     :seon.sci.eval/unregister-key unregister-key}))

(defn- unmap-row
  [{execution-ctx :seon.sci.eval/execution-ctx
    before-interns :seon.sci.eval/before-interns
    before-namespace-state :seon.sci.eval/before-namespace-state
    before-reader-context :seon.sci.eval/before-reader-context
    event :seon.sci.eval/event
    base-declared-row :seon.sci.eval/base-declared-row
    live-declaration? :seon.sci.eval/live-declaration?
    namespace-name :seon.sci.eval/namespace-name
    namespace-unmap? :seon.sci.eval/namespace-unmap?
    database :seon.db/db}]
  (let [removed-identities
        (when namespace-unmap?
          (removed-program-identities
           before-interns (sci/namespace-interns execution-ctx)))
        after-namespace-state
        (when namespace-unmap?
          (sci/namespace-state execution-ctx))
        namespace-changed?
        (and namespace-unmap?
             (not= before-namespace-state after-namespace-state))
        deletion-row
        (when (seq removed-identities)
          (program/deletion-row
           (assoc event :seon.sci.reader/ns-unmap-identities
                  removed-identities)))
        selected-row (or deletion-row base-declared-row)
        ;; Standalone REPL `require` is namespace registration too. Its
        ;; committed row carries the complete dependency set derived from
        ;; SCI's namespace table, so fresh acquisition reconstructs the
        ;; same reader/evaluator context before installing declarations.
        context-row
        (when-not selected-row
          (namespace-context-row
           (context-projection execution-ctx)
           database namespace-name (:seon.sci.reader/form event) before-reader-context
           (reader-context execution-ctx namespace-name)
           namespace-changed?))
        row (cond-> (or selected-row context-row)
              live-declaration? (assoc ::evaluated? true)
              (and namespace-changed? (or selected-row context-row))
              (assoc ::namespace-state
                     (into []
                           (mapcat
                            (fn [namespace-name]
                              (let [before (get before-namespace-state namespace-name)
                                    after (get after-namespace-state namespace-name)]
                                (keep (fn [binding-name]
                                        (let [old (get before binding-name absent-intern)
                                              new (get after binding-name absent-intern)]
                                          (when-not (identical? old new)
                                            [namespace-name binding-name new])))
                                      (into (set (keys before)) (keys after))))))
                           (into (set (keys before-namespace-state))
                                 (keys after-namespace-state)))))]
    {:seon.program/row row
     :seon.sci.eval/namespace-changed? namespace-changed?}))

(defn- failure-text
  "The declared string an evaluation names its failure with.

  `:seon.cluster.eval/error` is declared `:string`
  (`resources/seon/schemas/seon.cluster.eval.edn:3`), and the value it
  projects is ARBITRARY: any form an agent evaluates may RETURN a map
  whose `:seon.error/message` is not a string on a failed execution path.
  Reading that key verbatim handed `evaluate`'s own output contract a
  lookup-ref vector, so the evaluation stopped naming its own failure and
  the diagnostic became a contract violation against `seon.sci.eval/evaluate`
  (fault `7710efbc…`, default pid 66052, 2026-09-17 04:22:54Z). This text is
  DERIVED from the value at the one seam that declares it; the value's own
  shape is never the evaluation's declared text.

  A non-string message is still evidence, so it is printed rather than
  dropped: `seon.sci.admit/value` retains the value itself."
  [value]
  (let [message (:seon.error/message value)]
    (cond
      (string? message) message
      (some? message) (pr-str message)
      ;; A value that names no message still reached a
      ;; failing arm. The declared string says exactly that rather than
      ;; printing `nil` as though it were the failure.
      :else "The evaluation failed and named no failure.")))

(defn- floor-text
  "`value` printed by the floor as the data it is: no declared renderer is
  selected (`:seon.render.value/structural?`), so an error value prints its
  whole flat shape, and the saved text reads back as that data."
  {:malli/schema [:=> [:cat :map [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                                        :seon.schema.admission/reason "Any error or diagnostic value is printed here."
                                        :gen/elements [nil 0 "" :k [] {}]}]]
                  :string]}
  [request value]
  (render.value/render-ai-data
   (render.value/prepare
    (assoc (dissoc request :seon.render.call/function)
           :seon.render/value value
           :seon.render.value/options {:seon.render.value/structural? true}
           :seon.render.call/id [:seon.cluster.eval/source (:seon.cluster.eval/source request)]))))

(defn- flat-diagnostic
  "One presentation failure as a flat error: a Throwable normalized once by
  `seon.error.refusal/diagnostic` (class, message, ex-data per link); a
  declared failure value as it is."
  {:malli/schema [:=> [:cat [:or :seon.error/throwable :map]] :map]}
  [failure]
  (if (instance? Throwable failure)
    (seon.error.refusal/diagnostic (java.util.Date.) :seon.render/invocation 'seon.sci.eval/shown-result
                                   {:seon.error/throwable failure})
    failure))

(declare evaluate)

(defn- evaluated-source
  "A render's REPL entries as the viewing agent's REPL shows them: each form
  read once and evaluated through `evaluate` in `request`'s SCI world, then
  `;=> ` and its shown result; comments keep their place. Answers
  `{:seon.render/source text}`, or `{:seon.error/value refusal}` naming the
  first form that could not be read or evaluated, so the floor prints the
  value beside that one diagnostic: a form is never shown without its result."
  {:malli/schema [:=> [:cat :seon.sci.eval/shown-request :seon.render/unit :string]
                  [:or [:map [:seon.render/source :seon.render/source]]
                   [:map [:seon.error/value :seon.error/base]]]]}
  [request _unit source]
  (let [ctx (:seon.sci.eval/ctx request)
        namespace-name (or (second (:seon.cluster.eval/ns request)) 'user)
        refused (fn [offending message]
                  {:seon.error/value
                   (seon.error.refusal/diagnostic
                    (java.util.Date.) :seon.render/invocation 'seon.sci.eval/evaluated-source
                    {:seon.error/message message
                     :seon.error/member :seon.render/source
                     :seon.error/expected :seon.sci.eval/evaluation
                     :seon.error/offending offending})})
        events (sci/binding [sci/ns (sci/create-ns namespace-name)]
                 (reader/read (assoc (reader-context ctx namespace-name)
                                     :seon.sci.reader/ctx ctx
                                     :seon.sci.reader/text source
                                     :seon.config.eval.result/max-source (count source))))]
    (if-not (vector? events)
      (refused source (str "The render's entries could not be read: " (:seon.error/message events)))
      (loop [[event & more] events offset 0 shown []]
        (if-not event
          {:seon.render/source (apply str (conj shown (subs source offset)))}
          (let [end (:seon.sci.reader/source-end event)
                evaluation (when-not (:seon.sci.reader/error event)
                             (evaluate
                              (-> request
                                  (dissoc :seon.turn/id :seon.cluster.eval/ordinal :seon.repl/handle
                                          :seon.render.call/function :seon.sci.eval/output-prefix
                                          :seon.render.value/options)
                                  (assoc :seon.cluster.eval/source (:seon.sci.reader/source event)
                                         :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                                         :seon.sci.eval/event event
                                         ::rendering? true))))]
            (if (or (nil? evaluation) (:seon.cluster.eval/error evaluation))
              (refused (:seon.sci.reader/source event)
                       (str "A render form failed: "
                            (or (:seon.cluster.eval/error evaluation)
                                (get-in event [:seon.sci.reader/error :seon.error/message]))))
              (recur more end (conj shown (subs source offset end) "\n;=> "
                                    (:seon.eval/shown evaluation))))))))))

(defn shown-result
  "The one print of a final value: its admitted value and exact shown text.

  Every evaluation's shown text comes from here, and so does the text of a
  value that replaces a result after it was printed (a disposition refusal, a
  completed disposition's delivery, a settlement refusal): the earlier bytes
  are discarded, never stored, and the final value is rendered by this owner.
  The value is printed as it is: a declared pair selects what shows.

  A pair that returns REPL entries is evaluated here, once, as the saved
  observation the MCP reply and the agent's next context both read: the
  value is bound under its handle, each form is evaluated through `evaluate`
  (`evaluated-source`) and shown with its result. A form evaluated for a
  render renders its own result without evaluating further entries.

  TOTAL: printing never replaces or fails the value. A failure preparing the
  print (profile) or a renderer failure the floor survived is a flat
  diagnostic beside the value, `:seon.cluster.eval/diagnostics`, printed by
  the floor."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                                  :seon.schema.admission/reason "Any evaluated or replacing value is printed here."
                                  :gen/elements [nil false 0 "" :k [] {}]}]
                       :seon.sci.eval/shown-request [:maybe :seon.sci.admit/record]]
                  :seon.sci.eval/shown]}
  [value request record]
  (let [ctx (:seon.sci.eval/ctx request)
        handle (:seon.repl/handle request)
        profile (render/request-profile request)
        ;; A refused profile is a diagnostic; the value prints under the
        ;; floor's defaults, never under the refusal read as a profile.
        failures (cond-> [] (:seon.render/refused-member profile) (conj profile))
        request (if (:seon.render/refused-member profile)
                  (dissoc request :seon.render/profile)
                  (assoc request :seon.render/profile profile))
        ;; The render's forms read the value by its handle.
        _ (when (and ctx handle) (bind-result! ctx handle value))
        projection (render.value/prepare
                    (cond-> (assoc request
                                   :seon.render/value value
                                   :seon.render.call/id
                                   [:seon.cluster.eval/source
                                    (:seon.cluster.eval/source request)])
                      (and ctx (:seon.sci.eval/time-limit-ms request) (:seon.sci.admit/caps request)
                           (not (::rendering? request)))
                      (assoc :seon.sci.eval/evaluate-source (partial evaluated-source request))))
        shown (if (contains? projection :seon.render.value/root-description) projection
                  (or (:seon.render.value/value-text projection)
                      (render.value/render-ai-data projection)))
        failures (into failures (:seon.render.value/diagnostics projection))
        shown (if (string? shown) shown (pr-str shown))]
    ;; THE VALUE'S SIZE IS RECORDED BESIDE ITS SHOWN TEXT: the whole value's
    ;; estimated tokens from the floor's own cut counts (never a second print),
    ;; the shown tokens, how many cuts it made, and an explicit
    ;; `seon.print/full` opt-in. Queryable; shown only past a declared bound.
    (cond-> {:seon.sci.admit/value value
             :seon.eval/shown shown
             :seon.eval/shown-tokens (tokens/estimate shown)}
      (int? (:seon.render.value/whole-characters projection))
      (assoc :seon.eval/value-tokens
             (tokens/estimate-of-characters (:seon.render.value/whole-characters projection)))
      (int? (:seon.render.value/elisions projection))
      (assoc :seon.eval/elisions (:seon.render.value/elisions projection))
      (:seon.render.value/full? projection) (assoc :seon.eval/full true)
      (:seon.render.call/selected-producer projection)
      (assoc :seon.eval/renderer (:seon.render.call/selected-producer projection))
      (seq failures)
      (assoc :seon.cluster.eval/diagnostics (floor-text request (mapv flat-diagnostic failures)))
      record (assoc :seon.sci.admit/record record))))

(def ^:private value-size-keys
  "The shown value's recorded size, carried from `shown-result` to the
  evaluation and settled onto its receipt."
  [:seon.eval/value-tokens :seon.eval/shown-tokens :seon.eval/elisions :seon.eval/full])

(defn- success-evaluation
  [{admitted :seon.sci.eval/admitted
    caps :seon.sci.admit/caps
    output-prefix :seon.sci.eval/output-prefix
    printed :seon.sci.eval/printed
    namespace-name :seon.sci.eval/namespace-name
    ending-namespace :seon.sci.eval/ending-namespace
    print-options :seon.print/options
    definitions :seon.sci.eval/bindings
    restore! :seon.sci.eval/restore
    row :seon.program/row
    duration-ms :seon.eval/duration-ms
    acquisition-ms :seon.eval/acquisition-ms}]
  (cond-> (cond-> {:seon.print/options print-options
                   :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                   :seon.sci.eval/ending-ns ending-namespace
                   :seon.sci.admit/record (:seon.sci.admit/record admitted)}
            ;; ONE OF TWO TERMINAL SHAPES, never both and never neither: the
            ;; stored value, or the reason it is missing. `absent = no key`
            ;; is what lets a reader ask `contains?` instead of guessing.
            (contains? admitted :seon.sci.admit/value)
            (assoc :seon.sci.admit/value (:seon.sci.admit/value admitted))
            (:seon.eval/renderer admitted)
            (assoc :seon.eval/renderer (:seon.eval/renderer admitted))
            (:seon.eval/shown admitted)
            (assoc :seon.eval/shown
                   (:seon.eval/shown admitted))
            (:seon.cluster.eval/diagnostics admitted)
            (assoc :seon.cluster.eval/diagnostics (:seon.cluster.eval/diagnostics admitted)))
    ;; HOW LONG THE EVALUATION TOOK IS A KEY OF THE EVALUATION: entrance to
    ;; shown answer, less Seon's program acquisition, which is its own key.
    ;; Allocation is the kernel's measurement, kept instead of dropped.
    :always (assoc :seon.eval/duration-ms duration-ms)
    (pos? acquisition-ms) (assoc :seon.eval/acquisition-ms acquisition-ms)
    :always (merge (select-keys admitted value-size-keys))
    (pos? (get-in admitted [:seon.sci.admit/record :seon.eval/allocated-bytes] -1))
    (assoc :seon.eval/allocated-bytes
           (get-in admitted [:seon.sci.admit/record :seon.eval/allocated-bytes]))
    row (assoc :seon.program/row row)
    (seq definitions) (assoc :seon.sci.eval/bindings definitions)
    restore! (assoc :seon.sci.eval/restore restore!)
    (or (seq output-prefix) (seq (str printed)))
    (assoc :seon.cluster.eval/output
           (evaluation-output output-prefix printed))))

(defn- failed-evaluation
  [{admitted :seon.sci.eval/admitted
    caps :seon.sci.admit/caps
    output-prefix :seon.sci.eval/output-prefix
    printed :seon.sci.eval/printed
    namespace-name :seon.sci.eval/namespace-name
    print-options :seon.print/options
    definitions :seon.sci.eval/bindings
    record :seon.sci.admit/record
    value :seon.sci.admit/value
    interrupted-at :seon.cluster.eval/interrupted-at
    :as request}]
  (cond-> (cond-> {:seon.print/options print-options
                   :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                   :seon.sci.eval/ending-ns namespace-name
                   :seon.cluster.eval/error (failure-text value)
                   :seon.sci.admit/record record}
            (contains? admitted :seon.sci.admit/value)
            (assoc :seon.sci.admit/value (:seon.sci.admit/value admitted))
            (:seon.eval/renderer admitted)
            (assoc :seon.eval/renderer (:seon.eval/renderer admitted))
            (:seon.eval/shown admitted)
            (assoc :seon.eval/shown
                   (:seon.eval/shown admitted))
            (:seon.cluster.eval/diagnostics admitted)
            (assoc :seon.cluster.eval/diagnostics (:seon.cluster.eval/diagnostics admitted)))
    :always (merge (select-keys admitted value-size-keys))
    ;; The same one spelling on the failing path.
    (int? (:seon.eval/duration-ms record))
    (assoc :seon.eval/duration-ms (:seon.eval/duration-ms record))
    (seq definitions) (assoc :seon.sci.eval/bindings definitions)
    (contains? request :seon.cluster.eval/interrupted-at)
    (assoc :seon.cluster.eval/interrupted-at interrupted-at)
    (or (seq output-prefix) (seq (str printed)))
    (assoc :seon.cluster.eval/output
           (evaluation-output output-prefix printed))))

(defn- arity-exception
  [throwable]
  (loop [candidate throwable]
    (when candidate
      (if (instance? clojure.lang.ArityException candidate)
        candidate
        (recur (ex-cause candidate))))))

(defn- interpreted-arity-message
  "The named SCI function identity for one arity failure, when present."
  [throwable]
  (let [arity-failure (arity-exception throwable)
        callstack (some-> throwable ex-data :sci.impl/callstack deref)
        function-meta (:f-meta (last callstack))]
    (when (and arity-failure
               (contains? function-meta :sci/generation)
               (:ns function-meta)
               (:name function-meta))
      (let [actual (.actual ^clojure.lang.ArityException arity-failure)
            function-symbol
            (symbol (str (sci/ns-name (:ns function-meta)))
                    (str (:name function-meta)))]
        (str "Wrong number of args ("
             (if (<= actual 20) actual "> 20")
             ") passed to: " function-symbol)))))

(defn unrun-evaluation
  "The evaluation value for a form the runtime settled WITHOUT running it.

  There is exactly ONE shape of `:seon.sci.eval/evaluation`, so every arm that
  produces one comes through a constructor that fills its required keys. The
  arm this replaced hand-built four keys of that value inside the loop, so the
  submission backstop's own report — a run cut when its evaluation reached its
  time limit — arrived at `seon.problems/form-problem` missing
  `:seon.cluster.eval/ns`, `:seon.sci.eval/ending-ns` and
  `:seon.print/options`. The durable evidence of the interruption
  became a contract violation from the recorder: the diagnostic lied about
  what happened, which is the one thing a diagnostic may never do.

  An arm that does not name the value's keys cannot omit them. The namespace
  is the form's own `:seon.cluster.eval/ns` reference, so nothing here has
  to guess where the form was going to run. It prints nothing: its caller
  renders the final value through `shown-result`."
  {:malli/schema [:=> [:cat :seon.sci.eval/unrun-request]
                  :seon.sci.eval/evaluation]}
  [{value :seon.sci.admit/value
    namespace-ref :seon.cluster.eval/ns
    duration-ms :seon.eval/duration-ms
    interrupted-at :seon.cluster.eval/interrupted-at
    :as request}]
  (let [interrupted? (contains? request :seon.cluster.eval/interrupted-at)
        record (cond-> (kernel/unarmed-record (System/nanoTime))
                 (int? duration-ms)
                 (assoc :seon.eval/duration-ms duration-ms)
                 interrupted? (assoc :seon.eval/outcome :time))]
    (cond-> {:seon.sci.admit/value value
             :seon.cluster.eval/error (failure-text value)
             :seon.cluster.eval/ns namespace-ref
             :seon.sci.eval/ending-ns (second namespace-ref)
             :seon.print/options {}
             :seon.sci.admit/record record}
      interrupted? (assoc :seon.cluster.eval/interrupted-at interrupted-at))))

(defn- trusted-evaluation
  "Answer `evaluate!`'s evaluation, or throw the declared refusal naming both
  loaded identities when the program `identity-atom` names changed while it ran."
  {:malli/schema [:=> [:cat :seon.sci.eval/loaded-identity [:=> [:cat] :seon.sci.eval/evaluation]]
                  :seon.sci.eval/evaluation]}
  [identity-atom evaluate!]
  (let [answer (call-detecting-program-change identity-atom `evaluate evaluate!)]
    (if (:seon.error/at answer)
      (throw (ex-info (:seon.error/message answer) answer))
      answer)))

(defn- form-read-sink
  "The sink collecting one form's own reads: the request's, else the ambient one.
  `evaluate` takes it once and unbinds the ambient sink for everything else, so
  acquisition, program install, identity and gate reads never enter it."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.db/read-evidence-sink]]}
  [request]
  (or (:seon.db/read-evidence-sink request) db/*read-evidence-sink*))

(defn evaluate
  "Evaluate one form source and return what may leave the boundary.
  Runs synchronously on the caller's `:compute` workload task — this
  never blocks and never submits, because the two jobs the quarry's
  Semaphore conflated (backpressure and parallelism) now belong to the
  caller's work launcher.

  Order is the contract:
  1. bind the compiled `seon.db/*conn*` from the supplied cluster ctx,
     or nil for an isolated base ctx, and carry the current receipt through
     that same custody when run and form identities are present;
  2. use the SUPPLIED live cluster ctx, or make a fresh guarded base for
     an isolated one-off when none was given;
  3. arm through `kernel/with-arm` with `::time-limit-ms`, the ONLY limit —
     or INHERIT this context's active arm when one already governs this
     thread, so nested work never restarts the clock;
  4. consume THE ONE reader event; source is never reparsed;
  5. evaluate;
  6. ADMIT the value — realized and bounded — while still armed;
  7. disarm in `finally`.
  Steps 4-6 answer the declared refusal instead of their value when the
  loaded program (`:seon.sci.eval/loaded-identity`, else the JVM's
  `loaded-identity`) changed while they ran: nothing waits for adoption.

  Never throws. A failure of any kind returns an ordinary map whose
  `:seon.sci.admit/value` is a flat `:seon.error` value. PRESENCE IS
  THE STATE (owner ruling 2026-07-28): `:seon.cluster.eval/error` is
  present exactly when the form failed, and
  `:seon.cluster.eval/interrupted-at` — the instant the interrupt was
  observed — is present exactly when the time limit fired. The record
  rides through with `fn-entries` and `allocated-bytes` intact."
  {:malli/schema [:=> [:cat :seon.sci.eval/request]
                  :seon.sci.eval/evaluation]
   :seon/tasks #{"132fee0467c8"}}
  [{:keys [:seon.cluster.eval/source :seon.sci.admit/caps]
    ctx :seon.sci.eval/ctx
    agent-id :seon.agent/id
    run-id :seon.turn/id
    form-ordinal :seon.cluster.eval/ordinal
    cluster-name :seon.boot/cluster-name
    work-launcher :seon.flow/work-launcher
    namespace-ref :seon.cluster.eval/ns
    output-prefix :seon.sci.eval/output-prefix
    time-limit-ms :seon.sci.eval/time-limit-ms
    :as request}]
  (let [form-sink (form-read-sink request)]
  (binding [db/*read-evidence-sink* nil]
  (let [;; A supplied ctx keeps its accumulated defs. The only replacement is
        ;; the environment state: each form receives a turn-scoped immutable
        ;; value, so call preparation cannot read the long-lived cluster value
        ;; and silently omit the agent/run/form members.
        base-evaluation-ctx (or ctx (build-base-ctx (evaluation-projection request)))
        request
        (if-let [database (or (:seon.db/db request)
                              (some-> (get-in base-evaluation-ctx
                                               [::custody :seon.db/connection])
                                      db/db)
                              (get-in base-evaluation-ctx [::custody :seon.db/db]))]
          (if-let [projection (or (db/carried-projection database)
                                  (context-projection base-evaluation-ctx))]
            (assoc request :seon.db/db
                   (vary-meta database assoc :seon.schema/projection projection))
            request)
          request)
        turn-members (cond-> {}
                       (:seon.db/db request)
                       (assoc :seon.db/db (:seon.db/db request))
                       agent-id
                       (assoc :seon.agent/id agent-id)
                       run-id
                       (assoc :seon.turn/id run-id)
                       (some? form-ordinal)
                       (assoc :seon.cluster.eval/ordinal form-ordinal))
        turn-environment
        (some-> (env/of base-evaluation-ctx)
                (env/scope turn-members))
        evaluation-ctx
        (if (env/environment? turn-environment)
          (if (::turn-fork? base-evaluation-ctx)
            (do
              (env/replace-environment!
               (get base-evaluation-ctx env/state-carrier)
               turn-environment)
              base-evaluation-ctx)
            (env/carry-state base-evaluation-ctx
                             (env/environment-state turn-environment)))
          base-evaluation-ctx)
        ;; ARMING HAPPENS INSIDE THE BOUNDARY, and these reach it through
        ;; one volatile. `kernel/with-arm` refuses a DIFFERENT context already
        ;; armed on this thread, and a refusal at an agent-facing operation
        ;; is a VALUE like every other failure here — binding the arm before
        ;; the try would let that refusal escape as a throw and contradict
        ;; this namespace's own contract. The interrupt-fn needs no arm: it
        ;; is the ctx's stable process guard, inert until armed.
        started-at (System/nanoTime)
        ;; Seon's own program acquisition inside the arm, subtracted from the
        ;; evaluation's reported time and recorded beside it.
        acquisition-nanos (volatile! 0)
        ;; The reply's `:ms`: entrance to answer (shown text), without
        ;; acquisition. One clock difference, approximate by design.
        elapsed-ms (fn [] (quot (- (System/nanoTime) started-at @acquisition-nanos) 1000000))
        arm-state (volatile! nil)
        interrupt-fn (get-in evaluation-ctx
                             [::kernel/guard ::kernel/interrupt-fn])
        record (fn [outcome]
                 (if-let [armed-record (::kernel/record @arm-state)]
                   (armed-record outcome)
                   (kernel/unarmed-record started-at)))
        built-in-calls (fn []
                         (if-let [observed (::kernel/built-in-calls @arm-state)]
                           (observed)
                           #{}))
        ;; Printed output is stored with the evaluation, so it streams under
        ;; the same UTF-8 storage bound as external evidence: the fragment that
        ;; would cross `max-bytes` refuses (admission's declared
        ;; `:seon.sci.admit/error`) and stops a runaway print; the text kept is
        ;; exact. Presentation clipping stays with the value renderer.
        printed-text (StringBuilder.)
        printed-bytes (volatile! 0)
        put-printed!
        (fn [^String text]
          (locking printed-text
            (let [bound (long (:seon.config.eval.result/max-bytes caps))
                  total (+ (long @printed-bytes)
                           (alength (.getBytes text java.nio.charset.StandardCharsets/UTF_8)))]
              (when (> total bound)
                (throw (ex-info "printed output reached its storage bound"
                                (seon.error.refusal/diagnostic
                                 (java.util.Date.) :seon.sci.eval/output 'seon.sci.eval/evaluate
                                 {:seon.error/message "Printed output reached its byte bound; print less or return a value."
                                  :seon.error/offending total
                                  :seon.error/member :seon.config.eval.result/max-bytes
                                  :seon.sci.admit/bound-bytes bound
                                  :seon.sci.admit/bytes total
                                  :seon.sci.admit/projection-observation
                                  {:seon.error.evidence/attribute :seon.sci.admit/bytes
                                   :seon.error.evidence/value total}}))))
              (vreset! printed-bytes total)
              (.append printed-text text))))
        printed (proxy [java.io.Writer] []
                  (write
                    ([x] (put-printed! (cond (string? x) x
                                             (int? x) (str (char x))
                                             :else (String. ^chars x))))
                    ([x off len]
                     (put-printed! (if (string? x)
                                     (subs x off (+ off len))
                                     (String. ^chars x (int off) (int len))))))
                  (flush [])
                  (close [])
                  (toString [] (locking printed-text (str printed-text))))
        ;; A turn's fork carries the session's print options; a host caller
        ;; that forked nothing gets a carrier of its own for its own forms.
        print-session (or (::print-session evaluation-ctx) (atom {}))
        connection (get-in evaluation-ctx
                           [::custody :seon.db/connection])
        receipt (when (and run-id (some? form-ordinal))
                  [:seon.cluster.eval/id
                   (id/evaluation run-id form-ordinal)])
        namespace-name (or (second namespace-ref)
                           (when (and connection agent-id)
                             (agent-namespace (db/db connection) agent-id))
                           'user)
        namespace-object (sci/create-ns namespace-name)
        ending-namespace (volatile! namespace-name)
        print-options (volatile! {})
        session-observation (volatile! nil)
        replaced-notice (volatile! nil)
        projection-state
        (or (::projection-state evaluation-ctx)
            (atom {:seon.schema/projection
                   (evaluation-projection
                    {:seon.sci.eval/ctx evaluation-ctx})}))
        ;; The render of this result asks the projection this evaluation
        ;; holds and the profile derived once here, never a second choice.
        request (let [request (assoc request :seon.schema/projection
                                     (:seon.schema/projection @projection-state))
                      profile (render/request-profile request)]
                  (cond-> request
                    (not (:seon.render/refused-member profile))
                    (assoc :seon.render/profile profile)))]
    (schema/call-with-projection-state
     projection-state
     (fn []
       (db/call-with-custody
        (cond-> {} connection (assoc :seon.db/connection connection))
        (fn []
         (with-bindings {;; The bound read value carries this evaluation's
                       ;; projection state: interpreted reads run past the
                       ;; dynamic binding above, and a bare value rebuilds
                       ;; the projection per read (measured 2026-09-15).
                       #'db/*read-database*
                       (some-> (:seon.db/db turn-environment)
                               (db/carry-projection-state projection-state))
                       #'db/*receipt* receipt
                       #'db/*open* (when receipt (:seon.db/pending-open request))
                       #'effect/*request-context*
                       (when (and run-id (some? form-ordinal) cluster-name)
                         {;; The environment the ctx carries, scoped to this turn.
                          ;; Every crossing this request makes — a background io
                          ;; submission above all — carries it as DATA rather than
                          ;; hoping the executor inherited a binding frame.
                          :seon.env/environment
                          (some-> (env/of evaluation-ctx)
                                  (env/scope
                                   {:seon.agent/id agent-id
                                    :seon.turn/id run-id
                                    :seon.cluster.eval/ordinal form-ordinal}))
                          :seon.db/connection connection
                          :seon.turn/id run-id
                          :seon.cluster.eval/ordinal form-ordinal
                          :seon.agent/id agent-id
                          :seon.flow/work-launcher work-launcher
                          :seon.boot/cluster-name cluster-name
                          :seon.sci.admit/caps caps
                          ;; Named so a DETACHED capability handler can rebuild
                          ;; this evaluation's schema frame from data. Without
                          ;; it a background handler resolving any declaration
                          ;; refuses with :seon.schema/missing-projection, and a
                          ;; cached delay turns that one refusal into every
                          ;; later failure in the JVM.
                          :seon.sci.eval/projection-state projection-state
                          ;; The capability handler resolves in this ctx.
                          :seon.sci.eval/ctx evaluation-ctx
                          :seon.effect/counter (atom -1)})}
      (let [failure-result
             (fn [throwable]
               (let [record (record (if (kernel/interrupted? throwable)
                                 :time :error))
                observation @session-observation
                ;; A refused replacement or removal of a stored program row
                ;; never survives as a private binding. A restoration that
                ;; could not run is the evaluation's value, caused by the
                ;; original failure.
                unavailable
                (when-let [current (and observation
                                        (or (some-> connection db/db) (:seon.db/db request)))]
                  ((:seon.sci.eval/restore observation) #(row-backed? current %) throwable))
                throwable (if unavailable
                            (ex-info (:seon.error/message unavailable) unavailable throwable)
                            throwable)
                definitions
                (when-let [{failed-ctx :seon.sci.eval/ctx
                            before :seon.sci.eval/before-intern-values
                            failed-form :seon.sci.eval/form}
                           observation]
                  ;; A failed evaluation has no durable program row. Any def
                  ;; it installed before the later throw/cut therefore belongs
                  ;; to the agent's defs, including a contracted def whose declaration
                  ;; never reached the terminal transaction.
                  (bindings
                   failed-ctx namespace-name before source failed-form
                   (built-in-calls)))
                arity-message (interpreted-arity-message throwable)
                value (cond->
                          (kernel/failure-value
                           {::kernel/time-limit-kind ::time-limit
                            ::kernel/failure-kind ::evaluation-failed}
                           throwable record)
                        arity-message
                        (assoc :seon.error/message arity-message))
                ;; The agent's failure is its whole flat error; its declared
                ;; pair selects what shows, like any value's: a refusal's own
                ;; declaration, else its producer's return.
                admitted (shown-result value (assoc request :seon.render.call/function
                                                    'seon.sci.kernel/failure-value)
                                       record)]
          (failed-evaluation
           (cond-> {:seon.sci.eval/output-prefix (unadmitted-notice definitions)
                    :seon.sci.eval/admitted admitted
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/printed printed
                    :seon.sci.eval/namespace-name namespace-name
                    :seon.print/options @print-options
                    :seon.sci.eval/bindings definitions
                    :seon.sci.admit/record record
                    :seon.sci.admit/value value}
             ;; the instant the interrupt was OBSERVED — the one
             ;; genuinely new fact a cut evaluation leaves. Its
             ;; presence IS the interrupted state; there is no label.
             (= :time (:seon.eval/outcome record))
             (assoc :seon.cluster.eval/interrupted-at
                    (java.util.Date.))))))]
       (try
        (kernel/with-arm
       evaluation-ctx time-limit-ms
       (fn [armed]
        (vreset! arm-state armed)
        (try
         (trusted-evaluation
          (:seon.sci.eval/loaded-identity request loaded-identity)
          (fn []
          ;; An unavailable execution refuses before acquisition could replace
          ;; the environment that names it.
          (when-let [unavailable (::unavailable-execution @(:env evaluation-ctx))]
            (throw (ex-info (:seon.error/message unavailable) unavailable)))
          (when (contains? evaluation-ctx ::custody)
            (when-let [database (:seon.db/db request)]
              (when-not (acquired-database? evaluation-ctx database)
                (let [before-snapshot @(::kernel/program-snapshot evaluation-ctx)
                      own (keys (turn-interns evaluation-ctx))
                      base (or (::base-ctx evaluation-ctx) evaluation-ctx)
                      acquired-at (System/nanoTime)
                      result (acquire! {:seon.sci.eval/ctx base :seon.db/db database})
                      _ (vswap! acquisition-nanos + (- (System/nanoTime) acquired-at))]
                  (when-let [failure (first (::acquisition-refusals result))]
                    (throw (ex-info "SCI program acquisition refused a declaration." failure)))
                  (when-let [failure (::acquisition-recording-error result)]
                    (throw (ex-info "SCI acquisition could not record a row fault." failure)))
                  (when (::base-ctx evaluation-ctx)
                    (regenerate-agent-context! evaluation-ctx base))
                  ;; Say so loudly: the program advanced over these.
                  (when-let [replaced (seq (replaced-definitions
                                            before-snapshot
                                            @(::kernel/program-snapshot evaluation-ctx) own))]
                    (vreset! replaced-notice
                             (str ";; PROGRAM CHANGE replaced your definition of "
                                  (str/join ", " replaced)
                                  " with its changed program row; re-evaluate yours to redefine it.")))))))
          (when-let [failure (first (:seon.test/acquisition-refusals
                                    (acquired-program evaluation-ctx)))]
            (throw (ex-info "SCI program acquisition refused a declaration." failure)))
          (let [before-reader-context
            (reader-context evaluation-ctx namespace-name)
            event (or (:seon.sci.eval/event request)
                      (one-event
                       source namespace-name evaluation-ctx
                       (:seon.config.eval.result/max-source caps)))
            _ (when-let [reader-error (:seon.sci.reader/error event)]
                (throw (ex-info (:seon.error/message reader-error)
                                reader-error)))
            form (:seon.sci.reader/form event)
            namespace-unmap? (:seon.sci.reader/ns-unmap? event)
            execution-ctx evaluation-ctx
            capture (context-capture execution-ctx)
            before-namespaces (::namespaces capture)
            before-owned (::owned capture)
            before-intern-values (turn-intern-values before-owned)
            ;; The one restoration of this form's program change, reused at
            ;; every failure point: admission, arming and settlement refusal
            ;; restore all of it; a throw inside the form only what replaced
            ;; or removed a stored row (`failure-result`).
            restore! (context-restoration execution-ctx capture)
            _ (vreset! session-observation
                         {:seon.sci.eval/ctx execution-ctx
                          :seon.sci.eval/before-intern-values
                          before-intern-values
                          :seon.sci.eval/restore restore!
                          :seon.sci.eval/form form})
            before-namespace-state (when namespace-unmap? before-namespaces)
            before-interns (when namespace-unmap?
                             (sci/namespace-interns execution-ctx))
            ;; Runs `evaluate!` on this form's ctx inside its REPL frame.
            eval-in-form!
            (fn [evaluate!]
              (sci/binding [sci/ns namespace-object
                            sci/out printed
                            sci/err printed
                            sci/print-length (get @print-session
                                                  :seon.print/length
                                                  @sci/print-length)
                            sci/print-level (get @print-session
                                                 :seon.print/level
                                                 @sci/print-level)
                            sci/print-namespace-maps true
                            sci/print-readably true]
                (try
                  ;; Read evidence is what the form read: the request's sink
                  ;; is bound here and around its shown result, never around
                  ;; acquisition, identity checks or install.
                  ;; clojure.test reports through the host `*test-out*`
                  ;; (its `with-test-out`); this form's report is its output.
                  (let [value (binding [db/*read-evidence-sink* form-sink
                                        clojure.test/*test-out* printed]
                                ;; This thread is the REPL thread for this
                                ;; form. SCI attaches the form's location to a
                                ;; throw only on the ctx's :main-thread-id,
                                ;; set once at `sci/init` on whichever thread
                                ;; built the context (sci/impl/opts.cljc:332,
                                ;; utils.cljc:125-135); elsewhere it assumes an
                                ;; enclosing try and drops the location.
                                (evaluate!
                                 (assoc execution-ctx :main-thread-id
                                        (.getId (Thread/currentThread)))))]
                    (vreset! ending-namespace (sci/ns-name @sci/ns))
                    value)
                  (finally
                    ;; `set!` mutates SCI's current dynamic print binding.
                    ;; Capture it while that binding is still installed;
                    ;; after `sci/binding` unwinds only the host/default face
                    ;; remains and the agent's choice is unrecoverable. The
                    ;; session carrier takes the same ending value, which is
                    ;; what the NEXT form opens with.
                    (let [ending {:seon.print/length @sci/print-length
                                  :seon.print/level @sci/print-level}]
                      (vreset! print-options ending)
                      (reset! print-session ending))))))
            eval-form! #(eval-in-form! (fn [ctx] (sci/eval-form ctx form)))
            projection
            (evaluation-projection {:seon.sci.eval/ctx evaluation-ctx})
            ;; The one definition authority, over the program change made
            ;; since `capture`, after the names `declared` earlier in this form.
            admit!
            (fn [{owned ::owned namespaces ::namespaces} reader-row declared]
              (when-let [database (:seon.db/db request)]
                (admit-program-change!
                 (cond->
                  {:seon.sci.eval/ctx execution-ctx
                   :seon.db/db database
                   :seon.sci.eval/current-db (if connection (db/db connection) database)
                   :seon.schema/projection projection
                   ;; Analyze exactly the bytes the reader accepted as this form.
                   :seon.cluster.eval/source (or (:seon.sci.reader/source event) source)
                   :seon.sci.eval/form-start [(:seon.sci.reader/line event 1)
                                              (:seon.sci.reader/column event 1)]
                   :seon.sci.eval/before-owned owned
                   :seon.sci.eval/before-namespaces namespaces
                   :seon.sci.eval/namespace-row
                   (assoc (binding-rows before-reader-context) :seon.ns/name namespace-name)
                   :seon.sci.eval/restore restore!
                   :seon.sci.eval/namespace-unmap? (boolean namespace-unmap?)
                   :seon.sci.eval/declared declared}
                   reader-row (assoc :seon.sci.eval/reader-row reader-row)))))
            {base-declared-row :seon.sci.eval/base-declared-row
             live-declaration? :seon.sci.eval/live-declaration?
             schema-value :seon.sci.eval/schema-value
             unregister-key :seon.sci.eval/unregister-key}
            ;; Keep this call inside the total evaluation try: declared-row
            ;; deliberately throws ::schema-refused for an agent mistake.
            (declared-row
             {:seon.sci.eval/event event
              :seon.sci.eval/eval-form! eval-form!
              :seon.schema/projection projection})
            evaluated-value
            (cond
              ;; A faithful REPL returns the value SCI produced. This is the
              ;; same path a plain `def` takes, and keeps `defn`/`deftest`
              ;; declarations as Vars instead of replacing them with identity
              ;; strings after evaluation. Persistence is still decided later
              ;; by the terminal transaction; a refusal there restores the
              ;; change through the evaluation's `:seon.sci.eval/restore`.
              live-declaration? (eval-form!)

              ;; Schema declarations run once inside their isolated
              ;; registration delta above. Preserve that evaluated identity
              ;; without executing the form a second time.
              base-declared-row
              (or (:seon.schema/key base-declared-row)
                  (when unregister-key schema-value))

              ;; Each child of a top-level `do` is a top-level form, admitted
              ;; before the next runs (`top-level-evaluation`).
              (top-level-do? form)
              (eval-in-form! #(top-level-evaluation
                               {::eval! (partial sci/eval-form %)
                                ::capture! (fn [] (context-capture execution-ctx))
                                ;; Admission's reads are not the form's reads.
                                ::admit! (fn [capture declared]
                                           (binding [db/*read-evidence-sink* nil]
                                             (admit! capture nil declared)))}
                               {::rows [] ::declared []}
                               form))

              :else (eval-form!))
            top-level (when (top-level-do? form) evaluated-value)
            evaluated-value (if top-level (::value top-level) evaluated-value)
            _ (when-let [declared-ns (:seon.ns/name base-declared-row)]
                (vreset! ending-namespace declared-ns))
            {reader-row :seon.program/row}
            (unmap-row
             {:seon.sci.eval/execution-ctx execution-ctx
              :seon.sci.eval/before-interns before-interns
              :seon.sci.eval/before-namespace-state before-namespace-state
              :seon.sci.eval/before-reader-context before-reader-context
              :seon.sci.eval/event event
              :seon.sci.eval/base-declared-row base-declared-row
              :seon.sci.eval/live-declaration? live-declaration?
              :seon.sci.eval/namespace-name namespace-name
              :seon.sci.eval/namespace-unmap? namespace-unmap?
              :seon.db/db (if connection (db/db connection) (:seon.db/db request))})
            ;; A top-level `do` admitted its children's definitions as they
            ;; ran; what remains is the form's own reader row, counted with them.
            var-rows
            (if top-level
              (into (::rows top-level)
                    (admit! (context-capture execution-ctx) reader-row (::declared top-level)))
              (admit! capture reader-row []))
            var-row (first var-rows)
            row (or var-row reader-row)
            next-projection
            (cond
              (:seon.schema/key reader-row)
              (schema/projection-with-schema
               projection
               (:seon.schema/key reader-row)
               (edn/read-string (:seon.schema/form reader-row))
               {:seon.schema.admission/source :agent})

              (deleted-schema-key reader-row)
              (schema/projection-without-schema
               projection (deleted-schema-key reader-row))

              :else nil)
            _ (when next-projection
                ;; Registration deltas are deliberately isolated per eval.
                ;; The turn fork, however, is a REPL: later forms must see a
                ;; schema admitted by an earlier form before the batch commits.
                (advance-context-projection!
                 evaluation-ctx (db/db connection) next-projection))
            ;; Durable declarations are installed only after the row commits.
            ;; The form's own value, as SCI returned it: `require` answers
            ;; nil, and `(do (require ...) x)` answers x. A namespace-context
            ;; row is persistence, never the shown value.
            value evaluated-value
              ;; INSIDE the boundary, BEFORE disarm: an infinite lazy
              ;; sequence dies at the time limit here rather than in the
              ;; receipt writer
              evaluation-record (record :ok)
              ;; Capture exact evaluated roots; settlement installs rows from them.
              definitions
              (bindings execution-ctx namespace-name before-intern-values
                         source form (built-in-calls))
              ;; A top-level call's contracted head names the root value's
              ;; declared returns; any other form supplies no producer.
              head (some-> (render/request-projection request)
                           (render/head-function execution-ctx namespace-name form))
              admitted (binding [db/*read-evidence-sink* form-sink]
                         (shown-result value (cond-> request
                                               head (assoc :seon.render.call/function head))
                                       evaluation-record))]
          (success-evaluation
           {:seon.sci.eval/admitted admitted
            :seon.sci.admit/caps caps
            :seon.sci.eval/printed printed
            :seon.sci.eval/output-prefix
            (str/join "\n"
                      (remove str/blank?
                               [output-prefix
                                @replaced-notice
                                (when row
                                  (accretion/non-generatable-advisory row))]))
            :seon.sci.eval/namespace-name namespace-name
            :seon.sci.eval/ending-namespace @ending-namespace
            :seon.print/options @print-options
            :seon.sci.eval/bindings definitions
            :seon.sci.eval/restore restore!
            :seon.program/row row
            :seon.eval/duration-ms (elapsed-ms)
            :seon.eval/acquisition-ms (quot @acquisition-nanos 1000000)}))))
        (catch Throwable throwable
          (failure-result throwable))
          )))
        (catch Throwable throwable
          (if @arm-state
            (throw throwable)
            (failure-result throwable))))))))))))))

(defn- install-candidate-function!
  [ctx database row]
  (let [function-symbol (:seon.fn/sym row)
        definition (edn/read-string (:seon.fn/spec row))
        projection (context-projection ctx)
        next-projection
        (schema/projection-with-function-contract
         projection function-symbol definition
         {:seon.schema.admission/source :agent})]
    (kernel/cache-function!
     ctx function-symbol
     {::function-source (:seon.fn/source row)
      ::function-namespace (second (:seon.fn/ns row))
      ::function-private? (:seon.fn/private? row)
      ::agent-authored? true})
    (kernel/mark-installed! ctx function-symbol)
    (install-function-contract! ctx row next-projection database)
    (advance-context-projection! ctx database next-projection)
    ctx))

(defn accept-candidate!
  "Arm an accepted function in the retained context that evaluated it.

  The form ran once, in this context (`evaluate`), so its root is already
  here; acceptance installs the contract. A failed install restores the
  form's change through the evaluation's own `:seon.sci.eval/restore`."
  {:malli/schema
   [:=> [:cat [:map [:seon.sci.eval/ctx :seon.sci.eval/ctx]
               [:seon.db/db :seon.db/database-value]
               [:seon.sci.eval/evaluation :seon.sci.eval/evaluation]]]
    :seon.sci.eval/evaluation]}
  [{ctx :seon.sci.eval/ctx database :seon.db/db
    evaluation :seon.sci.eval/evaluation}]
  (try
    (install-candidate-function! ctx database (:seon.program/row evaluation))
    (catch Throwable failure
      (if-let [unavailable ((:seon.sci.eval/restore evaluation) nil failure)]
        (throw (ex-info (:seon.error/message unavailable) unavailable failure))
        (throw failure))))
  evaluation)

(defn refuse-install
  "Project the flat refusal as the evaluation result. The refused form's
  change is undone by its evaluation's own `:seon.sci.eval/restore`, the one
  restoration admission, arming and settlement refusals use. The refusal is
  the install gate's, or the writer's refusal of this form's row at
  settlement (`seon.turn/settled-value`)."
  {:malli/schema
   [:=> [:cat :seon.sci.eval/request :seon.sci.eval/evaluation
         [:or :seon.test.accretion/install-refused-error :seon.db/error-result :seon.turn/refused-error]]
    :seon.sci.eval/evaluation]}
  [request evaluation refusal]
  (when-let [unavailable ((:seon.sci.eval/restore evaluation) nil (ex-info (:seon.error/message refusal) refusal))]
    (throw (ex-info (:seon.error/message unavailable) unavailable)))
  (merge (dissoc evaluation :seon.program/row :seon.sci.eval/bindings :seon.eval/renderer)
         (shown-result refusal request
                       (assoc (:seon.sci.admit/record evaluation)
                              :seon.eval/outcome :error))))

(defn- candidate-test-result
  [test-symbol evaluation]
  {:seon.test/sym test-symbol
   :seon.test/pass-count 0
   :seon.test/fail-count 0
   :seon.test/error-count 1
   :seon.test/failure-message
   (or (:seon.cluster.eval/error evaluation)
       (str "Candidate test " test-symbol " could not be evaluated."))})

(defn run-tests
  "Invoke the selected-Var capture owner under this SCI context's bound."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.sci.eval/ctx :seon.sci.eval/ctx]
               [:seon.test/vars [:vector :seon.test/var]]
               [:seon.sci.eval/time-limit-ms :seon.sci.eval/time-limit-ms]
               [:seon.db/connection {:optional true} :seon.db/connection]]]
    [:or :seon.test.runner/captured-results :seon.test/not-runnable-error]]}
  [{ctx :seon.sci.eval/ctx test-vars :seon.test/vars
    limit :seon.sci.eval/time-limit-ms :as request}]
  (kernel/with-arm
   ctx limit
   (fn [_]
     (test.runner/run-vars! test-vars request))))

(defn run-test
  "Invoke one Var through the selected-Var capture owner under the SCI bound."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.sci.eval/ctx :seon.sci.eval/ctx]
               [:seon.test/var :seon.test/var]
               [:seon.sci.eval/time-limit-ms :seon.sci.eval/time-limit-ms]
               [:seon.db/connection {:optional true} :seon.db/connection]]]
    [:or :seon.test.runner/captured-result :seon.test/not-runnable-error]]}
  [request]
  (let [results (run-tests (assoc request :seon.test/vars [(:seon.test/var request)]))]
    (if (contains? results :seon.test/not-runnable) results (first results))))

(defn- run-candidate-test!
  [ctx database request test-symbol]
  (let [row (db/pull database
                     '[:seon.test/source {:seon.test/ns [:seon.ns/name]}]
                     [:seon.test/sym test-symbol])
        evaluation
        (evaluate
         (assoc request
                :seon.sci.eval/ctx ctx
                :seon.cluster.eval/source (:seon.test/source row)
                :seon.cluster.eval/ns
                [:seon.ns/name (get-in row [:seon.test/ns :seon.ns/name])]))]
    (if (:seon.cluster.eval/error evaluation)
      (candidate-test-result test-symbol evaluation)
      (let [test-var (sci/resolve ctx (symbol test-symbol))]
          (binding [clojure.test/report (constantly nil)]
            ;; A gate test runs in the disposable copy: the candidate request
            ;; carries the copy's connection, so the test's elided `seon.db`
            ;; arities reach the copy's branch, never the agent's. Same seam,
            ;; same value, as a `seon.test/run` member.
            (run-test
             (assoc (select-keys request [:seon.db/connection :seon.sci.eval/time-limit-ms])
                    :seon.sci.eval/ctx ctx :seon.test/var test-var)))))))

(def ^:private agent-entrance
  ;; The agent entrance sits above this namespace; realized at first use.
  (delay {:acquire (requiring-resolve 'seon.cluster.agent/acquire-context!)
          :release (requiring-resolve 'seon.cluster.agent/release-context!)}))

(defn- disposable-copy
  "The evaluating context copied onto a fresh branch of the agent's commit.

  An isolated execution off `database`'s commit (the agent entrance a test
  member uses), and the evaluating context forked onto its connection, so the
  candidate root and the agent's private definitions are present while every
  write, generated or tested, lands on the copy. The world keeps the agent's
  id. The caller releases `:seon.sci.eval/execution`."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :seon.db/connection
                       [:maybe :seon.agent/id]]
                  [:map [:seon.sci.eval/ctx :seon.sci.eval/ctx]
                   [:seon.db/db :seon.db/database-value]
                   [:seon.db/connection :seon.db/connection]
                   [:seon.sci.eval/execution :seon.agent/execution-handle]]]}
  [ctx database connection agent-id]
  (let [environment (env/of ctx)
        execution ((:acquire @agent-entrance)
                   (cond-> {:seon.db/connection connection
                            :seon.sci.eval/ctx ctx
                            :seon.cluster/name (:seon.boot/cluster-name environment)
                            :seon.agent/routing (:seon.agent/routing environment)}
                     (:seon.store/store environment)
                     (assoc :seon.store/store (:seon.store/store environment))
                     (::base-ctx ctx) (assoc :seon.sci.eval/base-ctx (::base-ctx ctx)))
                   nil
                   {:seon.agent/isolate? true
                    :seon.cluster.registry/from (db/commit-id database)})]
    (try
      (let [copy-connection (:seon.db/connection execution)
            copy-database (db/db copy-connection)
            ;; The copy is its own base: it never names the agent's.
            copy (dissoc (fork-cluster-ctx
                          ctx copy-database copy-connection
                          (projection-state copy-database (context-projection ctx)
                                            (:seon.boot/cluster-name environment))
                          {:seon.env/environment environment})
                         ::base-ctx)]
        (when agent-id
          (env/replace-environment! (get copy env/state-carrier)
                                    (env/scope (env/of copy) {:seon.agent/id agent-id})))
        {:seon.sci.eval/ctx copy
         :seon.db/db copy-database
         :seon.db/connection copy-connection
         :seon.sci.eval/execution execution})
      (catch Throwable failure
        ((:release @agent-entrance) execution)
        (throw failure)))))

(defn evaluate-candidate
  "Prove one evaluated durable function: its seeded contract check, then every
  gate test, in a disposable copy of the retained context that evaluated it.

  The form ran once (`evaluate`); the copy holds that evaluated root, so the
  proof runs the value acceptance keeps. The copy is its own branch of the
  agent's commit (`disposable-copy`): generated calls and gate test bodies
  write there, never on the agent's branch, and it is released afterwards.
  The returned test values are exactly `seon.test.runner/run-var!` reports.
  All tests run even after a red result. Nothing in the copy is transferred."
  {:malli/schema [:=> [:cat :seon.test.accretion/candidate-request]
                  :seon.test.accretion/candidate-result]}
  [{base-ctx :seon.sci.eval/ctx
    database :seon.db/db
    connection :seon.db/connection
    agent-id :seon.agent/id
    test-symbols :seon.test.accretion/gate-set
    evaluation :seon.test.accretion/evaluation
    :as request}]
  (let [{ctx :seon.sci.eval/ctx copy-database :seon.db/db copy-connection :seon.db/connection
         execution :seon.sci.eval/execution}
        (disposable-copy base-ctx database connection agent-id)
        request (assoc request :seon.db/db copy-database :seon.db/connection copy-connection)
        row (or (:seon.program/row request) (:seon.program/row evaluation))]
    (try
      (install-candidate-function! ctx copy-database row)
      (let [check
            (when (and (:seon.config.test/auto-check-cases request)
                       (:seon.test.accretion/seed request))
              (accretion/auto-check
               (assoc request
                      :seon.sci.eval/ctx ctx
                      :seon.program/row row
                      :seon.schema/projection (context-projection ctx))))]
        (cond->
         {:seon.test.accretion/candidate-ctx ctx
          :seon.test.accretion/evaluation (assoc evaluation :seon.program/row row)
          ;; Auto-check is deliberately realized above this expression:
          ;; the seeded contract probe precedes every example test.
          :seon.test.accretion/results
          ;; A gate test's declaration is read from the run's working value
          ;; (`database`), where a test an earlier form of this run defined
          ;; exists before its settlement; it runs in the copy.
          (mapv (partial run-candidate-test! ctx database request)
                test-symbols)}
          check (assoc :seon.test.accretion/auto-check check)))
      (finally
        ((:release @agent-entrance) execution)))))

(defn auto-check-candidate
  "Run the seeded contract check against an already evaluated candidate."
  {:malli/schema
   [:=> [:cat :seon.test.accretion/candidate-auto-check-request]
    :seon.test.accretion/auto-check-result]}
  [{ctx :seon.sci.eval/ctx :as request}]
  (accretion/auto-check
   (assoc request :seon.schema/projection (context-projection ctx))))
