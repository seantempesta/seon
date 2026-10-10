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
            [clojure.set :as set]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test]
            [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [my.background]
            [my.message]
            [my.turn]
            [sci.addons.future :as sci.future]
            [sci.core :as sci]
            [sci.ctx-store]
            [sci.lang]
            [sci.impl.namespaces :as sci.namespaces]
            [sci.impl.opts :as sci.opts]
            [sci.impl.vars :as sci.vars]
            [sci.impl.utils :as sci.utils]
            [sci.interrupt :as sci.interrupt]
            [seon.call-preparation :as call-preparation]
            [seon.config :as config]
            [seon.cache]
            [seon.batch]
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
            [seon.sci.admit :as admit]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.kernel :as kernel]
            [seon.sci.lookup :as lookup]
            [seon.sci.reader :as reader]
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

(declare carried-cluster-name agent-require)

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
  acquired into the context before any form runs, so none reaches here, and
  acquisition exposes the libraries they require the same way
  (`install-required-libraries!`); an unloaded namespace answers nil and SCI
  reports it as not found.

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


(defn- reified-return
  "`value` as `method` returns it: a primitive return coerced, void as nil."
  {:malli/schema [:=> [:cat [:fn clojure.core/some?] [:maybe [:fn clojure.core/some?]]] [:maybe [:fn clojure.core/some?]]]}
  [^java.lang.reflect.Method method value]
  (condp = (.getReturnType method)
    Integer/TYPE (int value) Long/TYPE (long value) Boolean/TYPE (boolean value)
    Double/TYPE (double value) Float/TYPE (float value) Short/TYPE (short value)
    Byte/TYPE (byte value) Character/TYPE (char value) Void/TYPE nil
    value))

(defn- interface-proxy
  "An object implementing `interfaces` (a `java.lang.reflect.Proxy`) whose
  methods are the reified `methods` by name, carrying SCI's custom-type view
  (`sci.impl.types/ICustomType`) for its protocols, and `meta`."
  {:malli/schema [:=> [:cat [:vector [:fn clojure.core/class?]] [:map-of :symbol [:fn clojure.core/ifn?]] [:set [:fn clojure.core/some?]] [:maybe :map]]
                  [:fn clojure.core/some?]]}
  [interfaces methods protocols meta]
  (java.lang.reflect.Proxy/newProxyInstance
   (clojure.lang.RT/baseLoader)
   (into-array Class (conj interfaces sci.impl.types.ICustomType clojure.lang.IObj))
   (reify java.lang.reflect.InvocationHandler
     (invoke [_ proxy method args]
       (let [method ^java.lang.reflect.Method method
             method-name (.getName method)
             declaring (.getDeclaringClass method)
             f (get methods (symbol method-name))]
         (cond
           (= sci.impl.types.ICustomType declaring)
           (case method-name "getInterfaces" (set interfaces) "getMethods" methods
                 "getProtocols" protocols "getFields" nil)
           (and (= clojure.lang.IObj declaring) (= "withMeta" method-name))
           (interface-proxy interfaces methods protocols (first args))
           (and (= "meta" method-name) (empty? args)) meta
           f (reified-return method (apply f proxy args))
           (.isDefault method) (java.lang.reflect.InvocationHandler/invokeDefault proxy method args)
           (= "toString" method-name) (str (.getName ^Class (first interfaces)) "@"
                                           (Integer/toHexString (System/identityHashCode proxy)))
           (= "hashCode" method-name) (System/identityHashCode proxy)
           (= "equals" method-name) (identical? proxy (first args))
           :else (throw (UnsupportedOperationException.
                         (str method-name " is not implemented by this reify")))))))))

(defn- interface-reify
  "SCI's `:reify-fn`: a reify of Java interfaces implements them, as Clojure's
  does (SCI's default implements only its own custom type, so `(.close x)` on a
  reified `java.io.Closeable` answered NoSuchFieldException); a reify of
  protocols and Object alone is SCI's default."
  {:malli/schema [:=> [:cat [:map [:interfaces [:set [:fn clojure.core/class?]]] [:methods :map] [:protocols [:set [:fn clojure.core/some?]]]]] [:fn clojure.core/some?]]}
  [{:keys [interfaces methods protocols] :as reified}]
  (let [interfaces (vec (sort-by #(.getName ^Class %) (filter #(.isInterface ^Class %) interfaces)))]
    (if (empty? interfaces)
      (sci.opts/default-reify-fn reified)
      (interface-proxy interfaces methods protocols nil))))

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
                                    (lookup/agent-test-namespace (sci/create-ns 'clojure.test)))
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
         ;; (interrupt.md:56-60). Declared bindings into clojure.core (the
         ;; host IO functions `slurp`, `spit`, `file-seq`) join those
         ;; alternatives, member by member.
         :namespaces
         (merge-with merge
                     {'clojure.core sci.interrupt/clojure-core
                      'clojure.string sci.interrupt/clojure-string}
                     injected-namespaces)
         ;; every class on the classpath resolves as the compiler resolves it
         ;; (our SCI fork's `interop/host-class-opts`); interruption stays
         ;; SCI's ceiling: a host call is not interrupted (interrupt.md:84-98)
         :classes {:allow :all}
         :reify-fn interface-reify
         :load-fn load-host-namespace}
         ;; SCI's own `future` addon, given our arm-carrying `future-call`
         ;; (sci fork's 2-arity install): `future` follows it
         (sci.future/install {:future-call kernel/future-call})
         ;; the addon's pmap calls the host future-call: its threads escaped
         ;; the arm and the form's owned work (task sci-pmap-escapes-the-arm)
         (assoc-in [:namespaces 'clojure.core 'pmap] kernel/pmap)
         (assoc-in [:namespaces 'clojure.core 'require] agent-require)))
        help-var (get-in injected-namespaces ['seon.bootstrap 'help])
        dir-var (get-in injected-namespaces ['seon.bootstrap 'dir])
        doc-var (get-in injected-namespaces ['seon.bootstrap 'doc])]
    ;; REPL documentation and test macros resolve through the core refer
    ;; every namespace receives. Acquisition refreshes the same bindings.
    (sci/add-namespace!
     ctx 'clojure.core
     {'dir dir-var
      'doc doc-var
      'help help-var
      'deftest (sci/resolve ctx 'clojure.test/deftest)
      'is (sci/resolve ctx 'clojure.test/is)})
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

(def ^:private absent-intern (Object.))

(defn- inherit-var-bindings!
  "Record the Var bindings `ctx` holds now, at its fork, as the ones it
  inherited: its own definitions of inherited Vars are the bindings that
  differ from them (`own-bindings`)."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :seon.sci.eval/ctx]}
  [ctx]
  (swap! (:env ctx) #(assoc % ::inherited-var-bindings (get % :sci/var-bindings {})))
  ctx)

(defn- own-bindings
  "The bindings `ctx` wrote since its fork, by SCI Var: one pass over its own
  bindings, never over its namespaces' Vars."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :map]}
  [ctx]
  (let [env @(:env ctx)
        inherited (get env ::inherited-var-bindings {})]
    (into {}
          (remove (fn [[sci-var binding]] (identical? binding (get inherited sci-var))))
          (get env :sci/var-bindings {}))))

(defn- turn-interns
  "This context's own definitions: the Vars it wrote since its fork.

  A redefinition of an inherited Var keeps the Var and writes this context's
  own binding of it (`own-bindings`). A new name is a Var with an SCI
  generation (an SCI `def` or `intern`; host Vars copied in by namespace
  installation carry none) at a path where the acquired program
  (`::program-namespaces`) has no Var or another one; only namespace maps not
  `identical?` to the acquired program's can hold one. Without that reference
  every generation-stamped Var is new.

  Values rather than Var identities are retained because a later write may
  replace the root. Values are not traversed."
  [ctx]
  (let [program (::program-namespaces @(::kernel/program-snapshot ctx))
        reference (when (and program
                             (not= (::generation program) (:sci/generation @(:env ctx))))
                    (::namespaces program))
        rebound (own-bindings ctx)
        rebound? (boolean (seq rebound))]
    (sci.ctx-store/with-ctx ctx
      (into {}
            (for [[namespace-name namespace-map] (sci/namespace-state ctx)
                  :let [program-map (get reference namespace-name)
                        new-names? (not (and reference (identical? namespace-map program-map)))]
                  :when (or rebound? new-names?)
                  [intern-name sci-var] namespace-map
                  :when (and (symbol? intern-name)
                             (sci.utils/var? sci-var)
                             (or (contains? rebound sci-var)
                                 (and new-names?
                                      (contains? (meta sci-var) :sci/generation)
                                      (not (identical? sci-var (get program-map intern-name))))))]
              [(symbol (str namespace-name) (str intern-name))
               {:seon.sci.eval/var sci-var
                :seon.sci.eval/metadata (meta sci-var)
                :seon.sci.eval/value (if (sci.vars/hasRoot sci-var)
                                       @sci-var
                                       absent-intern)}])))))

(declare install-function-contract! interpretation-error)

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
  the `result` namespace the handle qualifies. The binding is publication's,
  not a write of the evaluated form, so the write observer never sees it."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "SCI result bindings retain the actual arbitrary result object, including nil, without serialization.", :gen/elements [nil false 0 "" :k [] {}]}]] :qualified-symbol]}
  [ctx handle value]
  (let [namespace-name 'result
        intern-name (symbol (name handle))]
    (when-let [objects (::result-objects ctx)]
      (swap! objects assoc (subs (name handle) 1) value))
    (binding [sci.lang/*write-observer* nil]
      (when-not (sci/find-ns ctx namespace-name)
        (sci/add-namespace! ctx namespace-name {}))
      (sci/intern ctx namespace-name intern-name value))
    handle))

(defn- bind-output!
  "Bind one evaluation's printed output in the fork under its output handle
  (`admit/output-handle`), beside its value's `handle`, so the reply's cut of
  `:out` reads the live text."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol :string] :qualified-symbol]}
  [ctx handle output]
  (let [output-handle (admit/output-handle (subs (name handle) 1))]
    (binding [sci.lang/*write-observer* nil]
      (when-not (sci/find-ns ctx 'result)
        (sci/add-namespace! ctx 'result {}))
      (sci/intern ctx 'result (symbol (name output-handle)) output))
    output-handle))

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

(def ^:private namespace-pattern
  '[* :seon.ns/requires {:seon.ns/aliases [*]} {:seon.ns/imports [*]} {:seon.ns/refers [*]}])

;; `binding-rows` and `row-bindings` round-trip through the stored components,
;; so a stored row and SCI's live state compare as one shape.
(defn- binding-delta
  "What `after` declares that `before` does not (`:added`) and what `before`
  declares that `after` no longer does (`:removed`): aliases and refers as
  `local -> target`, requires as a set. Both are `row-bindings` maps."
  {:malli/schema [:=> [:cat :map :map] [:map [:added :map] [:removed :map]]]}
  [before after]
  (let [missing (fn [from in] (into {} (remove (fn [[k v]] (= v (get in k)))) from))]
    {:added {:aliases (missing (:aliases after) (:aliases before))
             :refers (missing (:refers after) (:refers before))
             :requires (set/difference (:requires after) (:requires before))}
     :removed {:aliases (missing (:aliases before) (:aliases after))
               :refers (missing (:refers before) (:refers after))}}))

(defn- added-libspecs
  "One libspec per added alias, refer group and bare require, by target
  namespace: `[t :as a :refer [x]]`, `:as-alias` when `t` is not required."
  {:malli/schema [:=> [:cat :map [:set :symbol]] [:vector :seon.schema/value]]}
  [{:keys [aliases refers requires]} required]
  (let [aliases-by (update-vals (group-by val aliases) #(sort (map key %)))
        refers-by (update-vals (group-by (comp symbol namespace val) refers) #(vec (sort (map key %))))
        targets (sort (into (set requires) (concat (keys aliases-by) (keys refers-by))))]
    (into []
          (mapcat (fn [t]
                    (let [[a & more] (get aliases-by t)
                          as (if (contains? required t) :as :as-alias)
                          referred (get refers-by t)]
                      (cons (cond-> [t] a (conj as a) referred (conj :refer referred))
                            (map #(vector t as %) more)))))
          targets)))

(defn- without-removed
  "`libspec` without the `:as`/`:as-alias` pairs naming a removed alias of its
  library and without its removed refers; `libspec` itself when it is not a
  `[lib & options]` vector."
  {:malli/schema [:=> [:cat :map :seon.schema/value] :seon.schema/value]}
  [{:keys [aliases refers]} libspec]
  (let [[lib & options] libspec]
    (if (and (vector? libspec) (symbol? lib) (even? (count options)))
      (into [lib] (mapcat (fn [[k v]]
                            (cond (and (#{:as :as-alias} k) (= lib (get aliases v))) []
                                  (and (= :refer k) (vector? v))
                                  [k (into [] (remove #(= (symbol (str lib) (str %)) (get refers %))) v)]
                                  :else [k v])))
            (partition 2 options))
      libspec)))

(defn- declared-source
  "`text`, an `ns` form declaring the context `before`, edited to declare
  `after` (both `row-bindings` maps): the libspecs of what `after` adds
  appended to its `:require` clause, each on its own line aligned with the
  clause's first libspec, and the aliases and refers it no longer holds
  removed from their libspecs. rewrite-clj edits only those nodes; the rest
  of the text stays exact."
  {:malli/schema [:=> [:cat :seon.ns/source :map :map] :seon.ns/source]}
  [text before after]
  (let [{:keys [added removed]} (binding-delta before after)
        specs (mapv n/coerce (added-libspecs added (:requires after)))
        form (z/of-string text {:track-position? true})
        form (if (or (seq (:aliases removed)) (seq (:refers removed)))
               (z/prewalk form #(and (= :vector (z/tag %))
                                     (every? (fn [c] (or (n/whitespace-or-comment? c) (n/sexpr-able? c)))
                                             (n/children (z/node %))))
                          #(let [libspec (z/sexpr %) edited (without-removed removed libspec)]
                             (if (= libspec edited) % (z/replace % edited))))
               form)
        clause (some-> (z/down form)
                       (z/find z/right #(and (= :list (z/tag %)) (= :require (some-> % z/down z/sexpr)))))]
    (z/root-string
     (cond
       (empty? specs) form
       clause (let [first-spec (z/right (z/down clause))
                    col (if first-spec (second (z/position first-spec)) (+ 10 (second (z/position clause))))]
                (reduce #(-> %1 (z/append-child* (n/newlines 1)) (z/append-child* (n/spaces (dec col)))
                             (z/append-child* %2))
                        clause specs))
       :else (-> form (z/append-child* (n/newlines 1)) (z/append-child* (n/spaces 2))
                 (z/append-child* (n/list-node (into [(n/keyword-node :require) (n/spaces 1) (first specs)]
                                                     (mapcat #(vector (n/newlines 1) (n/spaces 12) %))
                                                     (rest specs)))))))))

(defn- namespace-source
  "The `ns` form text declaring `namespace-name`'s context `after`
  (`row-bindings` shape): its stored `ns` form, or a bare one when none is
  stored, edited by `declared-source` from the context its stored row declares."
  {:malli/schema [:=> [:cat [:maybe :seon.db/database-value] :seon.ns/name :map] :seon.ns/source]}
  [database namespace-name after]
  (let [stored (some-> database (db/pull namespace-pattern [:seon.ns/name namespace-name]))]
    (if-let [text (:seon.ns/source stored)]
      (declared-source text (row-bindings stored) after)
      (declared-source (str "(ns " namespace-name ")") {} after))))

;; Called only through its Var by the fold's namespace facts (`seon.turn.batch`).
#_{:clj-kondo/ignore [:unused-private-var]}
(defn- namespace-context-row
  [projection database namespace-name before after changed?]
  (when (or changed?
            (not= (select-keys before [:seon.sci.reader/aliases
                                      :seon.sci.reader/refers
                                      ::imports ::requires])
                  (select-keys after [:seon.sci.reader/aliases
                                     :seon.sci.reader/refers
                                     ::imports ::requires])))
    (let [rows (binding-rows after)]
      (program/declaration-row
       projection
       (merge {:seon.ns/name namespace-name
               :seon.ns/source (namespace-source database namespace-name (row-bindings rows))}
              rows)
       :contracted :agent))))

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
          ;; Arming wraps the Var's root: a context without the Var refuses
          ;; by name, never dereferences nil.
          _ (when-not sci-var
              (let [refusal (interpretation-error
                             function-symbol "this context holds no Var to arm its contract on.")]
                (throw (ex-info (:seon.error/message refusal) refusal))))
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
        function-symbol spec-edn projection caps (sci.ctx-store/with-ctx ctx @sci-var)
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

(defn- refusing
  "A function that throws `refusal` whenever it is called."
  {:malli/schema [:=> [:cat :seon.sci.eval/interpretation-error] ifn?]}
  [refusal]
  (fn refused [& _]
    (throw (ex-info (:seon.error/message refusal) refusal))))

(defn- install-namespace-bindings!
  "Install `namespace-bindings` (`row-bindings`) for `namespace-name` from program rows.

  A refer whose target names no installed Var (its row was unmapped or never
  admitted) resolves to a Var that refuses by name when called, as Clojure's
  refer keeps an unmapped Var: the rest of the namespace and the acquisition
  stay available (`sci.core/install-namespace-bindings!` refuses the whole
  namespace instead)."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :symbol :map] :seon.sci.eval/ctx]}
  [ctx namespace-name namespace-bindings]
  (let [missing (into {}
                      (remove (fn [[_ target]]
                                (sci.utils/var? (get-in @(:env ctx) [:namespaces (symbol (namespace target))
                                                                     (symbol (name target))]))))
                      (:refers namespace-bindings))]
    (sci/install-namespace-bindings!
     ctx namespace-name (update namespace-bindings :refers #(apply dissoc % (keys missing))))
    (doseq [[local target] missing
            :let [refusal (interpretation-error
                           target (str namespace-name " refers to it as " local
                                       ", but no installed definition has that name; define it or remove the refer."))]]
      (swap! (:env ctx) assoc-in [:namespaces namespace-name :refers local]
             (sci/new-var (symbol (name target)) (refusing refusal)
                          {:ns (sci/create-ns (symbol (namespace target)))})))
    ctx))

(declare install-declared-classes! install-jvm-root! overridden-row? source-namespace?)

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
                   (not (source-namespace? ctx namespace-name))
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
      (install-namespace-bindings! ctx namespace-name (row-bindings namespace-row))
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
        refused (refusing refusal)]
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
  (`advance-loaded-identity!`).

  ## Costs

  Armed, measured 2026-09-27 on default: 0.8 us per call around an empty
  `work`. Its profile cell is inclusive of `work` (a test member's body, an
  evaluation), so its totals are its callers' costs, not its own."
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
  "The source commit the JVM loaded, as the cluster's adoption record in
  `database` names it: the commit on the entity carrying `:seon.cluster/name`.
  Other entities may carry `:seon.source/commit-id` with another meaning (a
  collection receipt's branch heads), so the attribute alone never decides.
  A `current-src` value without a record is that commit itself."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.source/commit-id]}
  [database]
  (let [commits (db/q '[:find [?commit ...]
                        :where [?cluster :seon.cluster/name]
                               [?cluster :seon.source/commit-id ?commit]] database)]
    (cond
      (= 1 (count commits)) (first commits)
      (= :current-src (get-in database [:config :branch])) (db/commit-id database)
      :else (throw (ex-info "The JVM's loaded source commit is unknown."
                            {:seon.error/expected :seon.source/commit-id
                             :seon.error/offending commits})))))

(defn- store-identity
  "The store a database value reads, as its declared config names it: a commit
  id names one immutable value only within its store."
  {:malli/schema [:=> [:cat :seon.db/database-value] :map]}
  [database]
  (select-keys (get-in database [:config :store]) [:backend :path :id]))

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
  "Stored definition identities from two attribute ranges; legacy rows alone need a pull."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:map-of :qualified-symbol :seon.program/definition-digest]]}
  [database]
  (let [symbols (db/datoms database :aevt :seon.fn/sym)
        digests (db/datoms database :aevt :seon.program/definition-digest)]
    (doseq [result [symbols digests]]
      (when (db/read-refusal? result)
        (throw (ex-info "Definition identities could not be read." result))))
    (let [by-entity (into {} (map (juxt :e :v)) digests)]
      (into {} (map (fn [{entity :e sym :v}]
                      [sym (or (not-empty (get by-entity entity))
                               (row-definition-digest (db/pull database '[*] [:seon.fn/sym sym])))]))
            symbols))))

(defn- loaded-digests
  "`function-digests` of a loaded program, once per store and commit: every
  acquisition compares against the same few loaded commits. `loaded` is a
  committed value (a `loaded-program` materialization or a connection's value)."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:map-of :qualified-symbol :seon.program/definition-digest]]}
  [loaded]
  (seon.cache/lookup ::loaded-digests [(store-identity loaded) (db/commit-id loaded)]
                     #(* 256 (count %))
                     #(function-digests loaded)))

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

;;; Source namespaces: what a context interprets from its program rows.
;;; An agent never loads, reloads or interns anything in the shared JVM (owner,
;;; 2026-10-10): a test namespace, or one this JVM has not loaded, runs from its
;;; rows in the agent's SCI world. Only root's adoption changes the host.

(defn- test-namespaces
  "The program namespaces `database` indexes from the test source root."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:set :symbol]]}
  [database]
  (let [names (db/q '[:find [?name ...]
                      :where [?file :seon.fn.file/relative-root "test"]
                      [?namespace :seon.fn/file ?file]
                      [?namespace :seon.ns/name ?name]]
                    database)]
    (when (:seon.db/invalid-read names)
      (throw (ex-info (:seon.error/message names) names)))
    (set names)))

(defn source-namespace?
  "Whether `ctx` interprets `namespace-name` from its program rows rather than
  binding compiled Vars: a test namespace, or one this JVM has not loaded."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :symbol] :boolean]}
  [ctx namespace-name]
  (boolean (or (contains? (::test-namespaces @(::kernel/program-snapshot ctx)) namespace-name)
               (nil? (find-ns namespace-name)))))

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
              ;; A BRANCH NEVER CHANGES THE PLATFORM (owner, AGENTS ddd3cf9a2):
              ;; compiled code keeps running as compiled on every branch and
              ;; sees a change only after merge and reload. An unchanged
              ;; host-bound caller of a branch-changed definition binds its
              ;; compiled Var; what the branch evaluates from source sees the
              ;; branch's definitions. Refused by name when called: a branch's
              ;; own change to a host-bound definition (SCI cannot interpret
              ;; it), and an unchanged core row in a namespace this JVM never
              ;; loaded (no compiled Var, no interns to analyze against).
              ;; A source namespace has no compiled Var to keep: its rows
              ;; interpret, and one SCI cannot interpret refuses by name when
              ;; called.
              unloaded? (source-namespace? ctx namespace-name)
              interpreted? (or interpreted? unloaded?)
              evaluated-row? (boolean (or evaluated? (::evaluated? row)))
              changed? (overridden-row? (::loaded-database snapshot) committed)
              compiled? (and host-row (not changed?) (not unloaded?))
              refusal (when (and host-row (not compiled?) (not unloaded?))
                        (interpretation-error function-symbol
                          (str (cond (nil? host-row) "Declaration "
                                     (contains? host-row :seon.fn/host-bound?) "Host-bound declaration "
                                     :else "Declaration with no :seon.fn/host-bound? fact ")
                               (:seon.fn/sym (or host-row committed))
                               " must change through the loaded source files.")))
              ;; A row acquired from the database refuses by name only when
              ;; called: the rest of the program and every later form keep
              ;; working. An agent's evaluation keeps the returned refusal.
              result
              (cond
                (and refusal (not evaluated-row?)) (install-refusing-root! ctx function-symbol refusal)
                refusal refusal
                :else
                  (if (or (not interpreted?) compiled?)
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
                        (interpretation-failure function-symbol failure)))))
              ;; An unchanged core row SCI cannot interpret (a same-namespace
              ;; deftype constructor named by its short class name) is not
              ;; the branch's change: it keeps its compiled Var, as a
              ;; host-bound caller does; with no compiled Var it refuses by
              ;; name when called, carrying its whole cause, never refusing
              ;; the acquisition and so every later form.
              failed (when (and (map? result) (not refusal) (not evaluated-row?)
                                (= :core admission))
                       result)
              result (cond (not failed) result
                           (install-jvm-root! ctx function-symbol) :jvm
                           :else (install-refusing-root! ctx function-symbol failed))
              refusal (or refusal (when (= :unavailable result) failed))]
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
  "Execute an already-admitted program operation in its supplied SCI context."
  [ctx form]
  (kernel/with-world-ctx ctx
    #(sci/eval-form ctx form)))

(defn- transfer-evaluated-roots!
  "Install each evaluated definition of `installations` into `ctx`.

  The evaluated Var and its root and metadata are read in the context that
  evaluated it (`source`), whose own binding they are; without one, the
  evaluation's recorded root. A name `ctx` already holds takes them as its
  binding of its Var (`sci.core/intern` of an existing name); a new name
  installs the evaluated Var itself, so closures that captured it and `ctx`
  call one Var."
  [ctx source installations]
  (let [roots (into {} (mapcat (comp :seon.sci.eval/bindings
                                    :seon.sci.eval/evaluation)) installations)]
    (doseq [qualified (into #{} (keep #(some-> % str symbol))
                           (mapcat (comp (juxt :seon.fn/sym :seon.test/sym)
                                         :seon.program/row) installations))
            :let [recorded (get roots qualified)]
            :when recorded]
      (let [namespace-name (symbol (namespace qualified))
            local-name (symbol (name qualified))
            evaluated (when source
                        (let [v (get-in (sci/namespace-state source) [namespace-name local-name])]
                          (when (sci.utils/var? v) v)))
            [value metadata] (if evaluated
                               (sci.ctx-store/with-ctx source [@evaluated (meta evaluated)])
                               [(:seon.sci.eval/value recorded) (:seon.sci.eval/metadata recorded)])
            existing (get-in (sci/namespace-state ctx) [namespace-name local-name])]
        (when-not (sci/find-ns ctx namespace-name)
          (sci/add-namespace! ctx namespace-name {}))
        (when (and evaluated (not (sci.utils/var? existing)))
          (sci/add-namespace! ctx namespace-name {local-name evaluated}))
        (sci.ctx-store/with-ctx ctx
          (let [target (if (sci.utils/var? existing) existing evaluated)]
            ;; Already `ctx`'s root and metadata (a new Var `source` owned):
            ;; nothing to write, so `ctx` holds no binding of its own.
            (when-not (and target (identical? value @target) (identical? metadata (meta target)))
              (let [installed (sci/intern ctx namespace-name (with-meta local-name metadata) value)]
                ;; SCI intern replaces an existing root without adopting symbol
                ;; metadata. The evaluated :test body and contract belong to
                ;; the replacement too; retain only the destination's ns.
                (reset-meta! installed (merge metadata (select-keys (meta installed) [:ns])))))))))))

(declare base-bindings acquired-database? stale-reach reinterpret-reach! interpret-affected! carry-promotions!
         namespace-row install-caller-host-bindings! install-required-libraries! acquisition-refusal)

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

(defn interpreted-test?
  "Whether `ctx` interprets the test `test-symbol` from its stored source.
  It does when the test reaches a definition the context's program changed, so
  its body calls that program's definitions, not the compiled ones."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol] :boolean]}
  [ctx test-symbol]
  (contains? (some-> (::kernel/program-snapshot ctx) deref ::interpreted-tests) test-symbol))

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
  and each changed component's owning parents join from those few children
  in the final value (a parent that lost a child is itself in the datoms).
  Anything else is not covered: the next acquisition derives from the head, as
  for any unknown change. Cost: proportional to the
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
                                           after frontier components)]
                        (when-not (or (:seon.db/invalid-read incoming) (:seon.schema/expected-value incoming))
                          (recur (vec (remove seen incoming)) (into seen incoming))))))]
      (when parents
        (let [candidates (vec (into touched parents))
              ;; Only program rows decide coverage: pull the identity
              ;; attributes of every touched entity (turns and evaluations
              ;; among them), then every attribute of the program rows alone.
              marks (fn [database] (db/pull-many database (vec program/identity-attributes) candidates))
              marked-old (marks before)
              marked-current (marks after)
              eids (if (some #(or (:seon.db/invalid-read %) (:seon.schema/expected-value %))
                             [marked-old marked-current])
                     candidates
                     (into [] (keep (fn [[eid old-mark current-mark]]
                                      (when (or (program/row-identity old-mark)
                                                (program/row-identity current-mark))
                                        eid)))
                           (map vector candidates marked-old marked-current)))
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
  (sci.ctx-store/with-ctx ctx
    (let [batch (into #{} (keep (comp #(some-> % str symbol) (some-fn :seon.fn/sym :seon.test/sym) :seon.program/row))
                      installations)
          functions (into [] (keep (comp #(some-> % str symbol) :seon.fn/sym :seon.program/row)) installations)
          previous (into {} (map (fn [sym] [sym (sci/resolve ctx sym)])) functions)]
      (transfer-evaluated-roots! ctx agent-ctx installations)
      ;; THE COMMITTED VALUE'S PROJECTION: every schema and contract the
      ;; settlement wrote, and every registration an earlier run committed. The
      ;; context's own projection is stale for the latter, so a defn naming a
      ;; schema registered in an earlier run refused out of the turn (reds
      ;; group I); folding each row into it again also rebuilt the projection
      ;; once per row. Derived once per committed value (memoized).
      (let [projection (schema/projection-from-database db)
            installed (mapv (fn [{program-row :seon.program/row}]
                              (install-row!
                               {:seon.sci.eval/ctx ctx
                                :seon.db/db db
                                :seon.program/row program-row
                                ::evaluated? true
                                ::prepared-projection projection}))
                            installations)]
        (when-let [snapshot (::base-bindings agent-ctx)]
          (reset! snapshot (base-bindings ctx)))
        ;; The agent's snapshot holds the rows its own definitions committed, so
        ;; its next regeneration reads them unchanged rather than another writer's.
        (when-let [agent-snapshot (::kernel/program-snapshot agent-ctx)]
          (swap! agent-snapshot update :functions merge
                 (select-keys (:functions @(::kernel/program-snapshot ctx))
                              (keep (comp :seon.fn/sym :seon.program/row) installations))))
        (when (and report written-on
                   (every? :seon.sci.eval/installed installed)
                   (installation-covers-program-change?
                    ctx written-on report installations (context-projection ctx)))
          (doseq [target (cond-> [ctx] agent-ctx (conj agent-ctx))]
            (swap! (::kernel/program-snapshot target) assoc :seon.db/db db)))
        ;; A definition that landed in a new Var of `ctx` leaves the callers and
        ;; tests analyzed against its previous Var calling that one: they are
        ;; analyzed again here, as one Clojure Var would be seen.
        (doseq [sym functions
                :let [before (get previous sym)]
                :when (and before (not (identical? before (sci/resolve ctx sym))))]
          (reinterpret-reach! ctx db (stale-reach ctx db sym (complement batch))))
        ;; A caller `ctx` still runs compiled never sees a redefinition: the
        ;; reverse closure of these definitions is bound lazily once, so the
        ;; next eval request calls the branch's program, as one Clojure Var
        ;; would. The turn fork walked it at each write (`seon.sci.arm/arm!`):
        ;; its closure carries over, walked again only for a definition the
        ;; fork never promoted.
        (when (seq functions)
          (if-let [promoted (some-> agent-ctx ::kernel/program-snapshot deref)]
            (carry-promotions! ctx db promoted functions)
            (interpret-affected! ctx db functions)))
        installed))))

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
  "Load the JVM namespaces `database`'s core program names; nil. A namespace
  indexed from the test source root is not run by the cluster:
  `seon.test/resolve-test` requires it on the test request that needs it."
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
            :let [host-namespace (when-not (source-namespace? ctx namespace-name)
                                   (find-ns namespace-name))]
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
              (cons [namespace-name (when-not (source-namespace? ctx namespace-name)
                                      (some-> (find-ns namespace-name) own-bindings))]
                    (for [target (distinct (concat (vals aliases)
                                                   (map (comp symbol namespace) (vals refers))))
                          :when (and (not= 'clojure.core target)
                                     (not (contains? (::test-namespaces @(::kernel/program-snapshot ctx)) target)))]
                      [target (some-> (find-ns target) ns-publics)]))
              :when (seq interns)]
          [target interns])]
    (doseq [[target interns] installs
            :let [present (get-in @(:env ctx) [:namespaces target])]]
      (install-host-namespace! ctx target (remove (fn [[local-name]] (contains? present local-name))
                                                  interns)))))

(defn- install-required-libraries!
  "Expose each loaded library namespace that `namespace-rows` require, alias or
  refer and `ctx` does not yet hold, as `require` would (`load-host-namespace`):
  a file namespace's aliases and full names resolve to the host's loaded Vars,
  as they do for its compiled callers. Namespaces already in `ctx` (program
  namespaces, SCI's own `clojure.*`) are left as they are; a target this JVM
  has not loaded stays absent."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:sequential :map]] :nil]}
  [ctx namespace-rows]
  (doseq [target (into (sorted-set)
                       (mapcat (fn [row]
                                 (let [{:keys [aliases refers requires]} (row-bindings row)]
                                   (concat requires (vals aliases) (map (comp symbol namespace) (vals refers))))))
                       namespace-rows)
          :when (not (contains? (:namespaces @(:env ctx)) target))]
    (load-host-namespace {:namespace target :ctx ctx})))

(declare install-selected! record-acquisition-refusals!)

(defn- source-closure
  "The source namespaces among `namespace-names` (`source-namespace?`) and the
  program source namespaces they require, alias or refer, transitively, less
  `entered`, given the program's namespace `rows` by name."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:map-of :symbol :map] [:sequential :symbol] [:set :symbol]]
                  [:set :symbol]]}
  [ctx rows namespace-names entered]
  (loop [pending (vec namespace-names) found #{}]
    (if-let [[namespace-name & more] (seq pending)]
      (if (or (found namespace-name) (entered namespace-name) (not (rows namespace-name))
              (not (source-namespace? ctx namespace-name)))
        (recur (vec more) found)
        (let [{:keys [aliases refers requires]} (row-bindings (rows namespace-name))]
          (recur (into (vec more) (concat requires (vals aliases) (map (comp symbol namespace) (vals refers))))
                 (conj found namespace-name))))
      found)))

(defn- interpret-source-namespaces!
  "Interpret every function and test row of the source namespaces
  `namespace-names` in `ctx` from its acquired database (`install-selected!`),
  each after those it binds, recording the refusals; the acquisition."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:set :symbol]] :map]}
  [ctx namespace-names]
  (let [snapshot @(::kernel/program-snapshot ctx)
        database (:seon.db/db snapshot)
        names (vec (sort-by str namespace-names))
        function-rows (into []
                            (map (fn [[sym source namespace-name admission private?]]
                                   [(symbol sym) source namespace-name admission private?]))
                            (db/q '[:find ?sym ?source ?namespace-name ?admission ?private
                                    :in $ [?namespace-name ...]
                                    :where [?namespace :seon.ns/name ?namespace-name]
                                    [?function :seon.fn/ns ?namespace]
                                    [?function :seon.fn/sym ?sym]
                                    [?function :seon.fn/source ?source]
                                    [?function :seon.schema.admission/source ?admission]
                                    [(get-else $ ?function :seon.fn/private? false) ?private]]
                                  database names))
        test-rows (db/q '[:find ?sym ?source ?namespace-name ?admission
                          :in $ [?namespace-name ...]
                          :where [?namespace :seon.ns/name ?namespace-name]
                          [?test :seon.test/ns ?namespace]
                          [?test :seon.test/sym ?sym]
                          [?test :seon.test/source ?source]
                          [?test :seon.schema.admission/source ?admission]]
                        database names)
        acquired (record-acquisition-refusals!
                  ctx database
                  (install-selected!
                   ctx database (select-keys (:namespaces snapshot) names)
                   function-rows (vec test-rows) (into #{} (map first) function-rows)
                   (-> (::acquisition snapshot)
                       (dissoc ::acquisition-refusals-recorded? ::acquisition-recording-error)
                       (assoc :seon.schema/projection (context-projection ctx))))
                  (:seon.flow/commit-fault! snapshot))]
    (swap! (::kernel/program-snapshot ctx) assoc ::acquisition acquired)
    acquired))

(defn enter-namespace!
  "`ctx` with the program namespace a form runs in, and each it aliases, bound as
  its file declares it, once per context: its loaded JVM namespace's interns,
  private ones included, its refers and the public Vars of what it aliases
  (`install-caller-host-bindings!`). A source namespace (`source-namespace?`:
  a test namespace, or one this JVM has not loaded) and the source namespaces
  it binds are interpreted from their rows instead
  (`interpret-source-namespaces!`); nothing is loaded into the shared JVM. A
  namespace without a program row is left as SCI holds it."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :symbol] :seon.sci.eval/ctx]}
  [ctx namespace-name]
  (let [snapshot (::kernel/program-snapshot ctx)
        rows (:namespaces @snapshot)
        entered (::entered-namespaces @snapshot #{})
        aliased (vals (get-in @(:env ctx) [:namespaces namespace-name :aliases]))
        requested (into [] (comp (distinct) (remove entered) (filter rows)) (cons namespace-name aliased))
        sources (source-closure ctx rows requested entered)
        entering (into [] (keep rows) (distinct (concat requested (sort-by str sources))))]
    (when (seq entering)
      ;; entered first: interpreting the sources enters each of them again
      (swap! snapshot update ::entered-namespaces (fnil into #{}) (map :seon.ns/name) entering)
      (binding [sci.lang/*write-observer* nil
                sci.lang/*expansion-observer* nil]
        (sci.ctx-store/with-ctx ctx
          (install-declared-classes! ctx entering)
          (install-caller-host-bindings! ctx entering)
          (install-required-libraries! ctx entering)
          (doseq [row entering]
            (install-namespace-bindings! ctx (:seon.ns/name row) (row-bindings row)))
          (when (seq sources)
            (interpret-source-namespaces! ctx sources)))))
    ctx))

(defn- required-libs
  "The namespace names `require`'s arguments name: a lib symbol, a libspec
  vector's first element, each member of a prefix list; flags name none."
  {:malli/schema [:=> [:cat [:sequential :seon.schema/value]] [:sequential :symbol]]}
  [args]
  (let [lib (fn [spec] (if (vector? spec) (first spec) spec))]
    (into []
          (comp (mapcat (fn [arg]
                          (cond (seq? arg) (map #(symbol (str (first arg) "." (lib %))) (rest arg))
                                :else [(lib arg)])))
                (filter symbol?))
          args)))

(defn- agent-require
  "`clojure.core/require` in an agent's context: each program namespace it
  names is entered first (`enter-namespace!`), then SCI's own require aliases
  and refers it. The base holds every program namespace, test namespaces
  empty, so SCI's require would find it present and load nothing."
  {:malli/schema [:=> [:cat [:* :seon.schema/value]] :nil]}
  [& args]
  (let [ctx (sci.ctx-store/get-ctx)]
    (doseq [lib (required-libs args)]
      (enter-namespace! ctx lib))
    (apply sci.namespaces/require args)))

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

(defn- function-rows
  "Every program function of `db` as `[sym source namespace admission private?]`."
  [db]
  (mapv (fn [[sym source admission private?]]
          [sym source (symbol (namespace (symbol sym))) admission private?])
        (db/q '[:find ?sym ?source ?admission ?private
                :where
                [?function :seon.fn/sym ?sym]
                [(get-else $ ?function :seon.fn/source "") ?source]
                [?function :seon.schema.admission/source ?admission]
                [(get-else $ ?function :seon.fn/private? false) ?private]]
              db)))

(defn- loaded-function-rows
  "`function-rows` of a loaded program, once per store and commit, as
  `loaded-digests` reads its digests: a fresh materialization of the commit
  answers the whole-program join cold."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector [:tuple :qualified-symbol :string :symbol :keyword :boolean]]]}
  [loaded]
  (seon.cache/lookup ::loaded-function-rows [(store-identity loaded) (db/commit-id loaded)]
                     #(reduce (fn [bytes [_ source]] (+ bytes 128 (* 2 (count source)))) 0 %)
                     #(function-rows loaded)))

(defn- host-function-rows
  "The rows whose compiled Vars bind: the branch's own, plus the loaded program's
  rows the branch lacks. A branch forked before a reload holds no row for a
  function the reload added, yet the compiled Var is running on every branch
  (AGENTS: a branch never changes the platform)."
  [branch-rows loaded db]
  (if (or (nil? loaded) (identical? loaded db))
    branch-rows
    (let [known (into #{} (map first) branch-rows)]
      (into branch-rows (remove (comp known first)) (loaded-function-rows loaded)))))

(defn- commit-id-of
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :nil :uuid]]}
  [database]
  (let [value-id (db/committed-value-identity database)]
    (when (map? value-id) (:datahike.value/commit-id value-id))))

(defn- armed-dials
  "The dial values an interpreted contract wrapper arms with from `database`,
  or the key naming the configuration it lacks."
  {:malli/schema [:=> [:cat :seon.db/database-value] :map]}
  [database]
  (let [effective (database-effective-config database)]
    (if (:seon.error/message effective)
      (select-keys effective [:seon.config/error-key])
      (merge (select-keys effective [:seon.config.error/max-evidence-bytes])
             {:seon.sci.admit/caps (config/result-caps effective)}))))

(defn- function-entry
  "The acquired program snapshot's entry for one function row."
  {:malli/schema [:=> [:cat [:tuple :qualified-symbol :string :symbol :keyword :boolean]] :map]}
  [[_ source namespace-name admission private?]]
  {::function-source source
   ::function-admission admission
   ::function-namespace namespace-name
   ::function-private? private?
   ::agent-authored? (= :agent admission)})

(defn- namespace-order
  "`namespace-names` ordered so each follows the program namespaces its row binds,
  as `::order`. Namespaces whose bindings form a cycle follow the rest in name
  order, and the bindings still pending are `::cycle`: Clojure loads a namespace
  once, so a stored cycle never stops the rest of the program."
  {:malli/schema [:=> [:cat [:map-of :symbol :map] [:set :symbol]]
                  [:map [::order [:vector :symbol]] [::cycle {:optional true} [:map-of :symbol [:set :symbol]]]]]}
  [namespace-row-by-name namespace-names]
  (loop [remaining (into {}
                         (map (fn [namespace-name]
                                (let [bindings (row-bindings (get namespace-row-by-name namespace-name))]
                                  [namespace-name
                                   (into #{}
                                         (comp (filter namespace-names) (remove #{namespace-name}))
                                         (concat (:requires bindings)
                                                 (keep (comp symbol namespace) (vals (:refers bindings)))))])))
                         namespace-names)
         ordered []]
    (let [ready (->> remaining
                     (keep (fn [[namespace-name required]] (when (empty? required) namespace-name)))
                     (sort-by str)
                     vec)]
      (cond
        (empty? remaining) {::order ordered}
        (empty? ready) {::order (into ordered (sort-by str (keys remaining))) ::cycle remaining}
        :else (let [released (set ready)]
                (recur (into {}
                             (map (fn [[namespace-name required]] [namespace-name (apply disj required released)]))
                             (apply dissoc remaining ready))
                       (into ordered ready)))))))

(defn- cycle-refusals
  "One acquisition refusal per namespace of `cycle`, the bindings `namespace-order` found cyclic."
  {:malli/schema [:=> [:cat [:map-of :symbol [:set :symbol]]] [:vector :map]]}
  [cycle]
  (into []
        (map (fn [[namespace-name required]]
               (acquisition-refusal
                {:seon.ns/name namespace-name}
                {:seon.error/message (str namespace-name " and " (str/join ", " (sort required))
                                          " require each other; remove the require that closes the cycle.")
                 :seon.error/offending cycle
                 :seon.error/member :seon.ns/requires
                 :seon.error/expected :seon.sci.eval/acyclic-bindings})))
        (sort-by (comp str key) cycle)))

(defn- install-selected!
  "Interpret `function-rows` and `test-rows` on `ctx`, each namespace after the
  ones it binds, advancing the acquisition `state`; the acquisition, its
  refusals not yet recorded."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value
                       [:map-of :symbol :map]
                       [:sequential [:tuple :qualified-symbol :string :symbol :keyword :boolean]]
                       [:sequential [:tuple :qualified-symbol :string :symbol :keyword]]
                       [:set :qualified-symbol] :map]
                  :map]}
  [ctx db namespace-row-by-name function-rows test-rows interpreted state]
  (sci.ctx-store/with-ctx ctx
    (let [function-rows-by-ns (group-by #(nth % 2) function-rows)
          test-rows-by-ns (group-by #(nth % 2) test-rows)
          {order ::order cycle ::cycle}
          (namespace-order namespace-row-by-name
                           (into (set (keys namespace-row-by-name))
                                 (concat (keys function-rows-by-ns) (keys test-rows-by-ns))))
          ;; a stored cycle is the agent's mistake, delivered as its refusal
          state (cond-> state cycle (update ::agent-mistakes (fnil into []) (cycle-refusals cycle)))
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
      ;; Interpreted callers and tests analyze against every other name their
      ;; file namespace reaches; only this branch's rows become SCI definitions.
      (doseq [namespace-name (sort-by str (distinct (concat (keys function-rows-by-ns) (keys test-rows-by-ns))))]
        (enter-namespace! ctx namespace-name))
      ;; Copy inherited Vars into this generation before analyzing any caller.
      ;; SCI definitions then replace roots in those same Vars; a caller analyzed
      ;; before its callee must not retain the old generation's copied root.
      (doseq [sym interpreted
              :let [candidate (sci/resolve ctx sym)]
              :when candidate]
        (sci/bind-root! ctx candidate @candidate))
      (let [functions-installed
            (reduce
             (fn [state namespace-name]
               ;; Each function installs its exact bindings inside the named
               ;; refusal boundary. A refused dependency must not escape here
               ;; as an unclassified namespace-binding exception.
               (when (empty? (get function-rows-by-ns namespace-name))
                 (when-let [row (get namespace-row-by-name namespace-name)]
                   (install-namespace-bindings!
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
                ;; In file order, as loading the file evaluates them: a def
                ;; reading another def finds it bound. Rows with no span
                ;; (defined at a REPL) follow, by name.
                (->> (get function-rows-by-ns namespace-name)
                     (map (fn [[sym source _ admission private?]]
                            (let [{:seon.fn/keys [arglists form-span]}
                                  (db/pull db [:seon.fn/arglists :seon.fn/form-span] [:seon.fn/sym sym])]
                              [[(or (first form-span) Long/MAX_VALUE) (str sym)]
                               {:seon.fn/sym sym
                                :seon.schema.admission/source admission
                                :seon.fn/source source
                                :seon.fn/ns [:seon.ns/name namespace-name]
                                :seon.fn/private? private?
                                :seon.fn/arglists arglists
                                ::skip-contract-install? (not= :agent admission)}])))
                     (sort-by first)
                     (map second))))
             state
             order)]
        ;; Tests resolve only after every namespace's functions and exact
        ;; bindings are present. This makes renamed `deftest` deterministic.
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
         order)))))

(defn- namespace-row
  "A pulled namespace row carrying its source, admission and authorship."
  {:malli/schema [:=> [:cat :map] :map]}
  [{source :seon.ns/source admission :seon.schema.admission/source :as row}]
  (assoc row
         ::namespace-source source
         ::namespace-admission (when source admission)
         ::agent-authored? (boolean (and source (= :agent admission)))))

(defn- acquire-program!
  "Acquire the whole program `db` holds, against the JVM's loaded program value.

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
            all-namespace-names
            (db/q '[:find [?namespace-name ...]
                    :where
                    [_ :seon.ns/name ?namespace-name]]
                  db)
            all-function-rows (function-rows db)
            loaded (or (::loaded-database @(::kernel/program-snapshot ctx))
                       (loaded-program db))
            branch-digests (function-digests db)
            ;; A loaded value identical to the acquired one may be uncommitted
            ;; (`d/with`); only a distinct, committed loaded value is retained.
            loaded-digests (if (identical? loaded db) branch-digests (loaded-digests loaded))
            ;; Only what an agent authored overrides (`overridden-row?`): a
            ;; file-derived row the loaded program moved past is not the branch's.
            authored (into #{} (keep (fn [[sym _ _ admission]] (when (= :agent admission) (symbol sym))))
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
            test-namespaces (test-namespaces db)
            ;; a program namespace this JVM never loaded runs from its rows
            ;; (an agent never loads into the shared JVM); a test namespace
            ;; is interpreted when it is entered (`enter-namespace!`)
            unloaded (into #{} (keep (fn [[sym _ namespace-name]]
                                       (when (and (not (test-namespaces namespace-name))
                                                  (nil? (find-ns namespace-name)))
                                         (symbol sym))))
                           all-function-rows)
            interpreted (into overridden (:seon.fn/affected closure))
            function-rows (filterv (fn [[sym]] (or (interpreted sym) (unloaded sym))) all-function-rows)
            _ (lookup/install-program-doc! ctx db projection)
            selected-namespace-names
            (into (into #{} (map #(nth % 2)) function-rows)
                  (comp (filter (fn [[_ _ admission]] (= :agent admission))) (map first))
                  namespace-assertions)
            all-namespace-rows
            (mapv namespace-row
                  (db/pull-many db namespace-pattern
                                (mapv #(vector :seon.ns/name %) all-namespace-names)))
            all-namespace-row-by-name
            (into {} (map (juxt :seon.ns/name identity)) all-namespace-rows)
            test-rows
            (into
             []
             (filter (fn [[_ _ _ admission]] (= :agent admission)))
             (db/q '[:find ?sym ?source ?namespace-name ?admission
                     :where
                     [?test :seon.test/sym ?sym]
                     [?test :seon.test/source ?source]
                     [?test :seon.schema.admission/source ?admission]
                     [?test :seon.test/ns ?namespace]
                     [?namespace :seon.ns/name ?namespace-name]]
                   db))]
        (kernel/cache-program!
         ctx
         (into {} (map (fn [row] [(symbol (first row)) (function-entry row)])) all-function-rows)
         all-namespace-row-by-name)
        (swap! (::kernel/program-snapshot ctx) assoc
               ::loaded-database loaded ::interpreted interpreted
               ::test-namespaces test-namespaces)
        (when-let [recorder recording-operation]
          (swap! (::kernel/program-snapshot ctx) assoc
                 :seon.flow/commit-fault! recorder))
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
         (host-function-rows all-function-rows loaded db))
        ;; Libraries the namespaces require resolve through their aliases and
        ;; full names, as the compiled namespaces that require them do.
        (install-required-libraries! ctx all-namespace-rows)
        ;; The bare REPL name refers to the acquired macro itself. Keeping the
        ;; boot-time copy here would preserve its old expansion after adoption.
        (sci/add-namespace! ctx 'clojure.core
                            {'help (sci/resolve ctx 'seon.bootstrap/help)})
        (record-acquisition-refusals!
         ctx db
         (install-selected!
         ctx db
         (select-keys all-namespace-row-by-name selected-namespace-names)
         function-rows test-rows interpreted
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
                all-function-rows)})
         commit-fault!))))))

(declare same-program-root?)

(defn- inherited?
  "Whether `ctx` holds the Var at `qualified` as `base`'s Var, or as its own Var holding the base's root."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.sci.eval/ctx :qualified-symbol] :boolean]}
  [ctx base qualified]
  (let [path [:namespaces (symbol (namespace qualified)) (symbol (name qualified))]
        base-var (get-in @(:env base) path)
        fork-var (get-in @(:env ctx) path)]
    (boolean (and (sci.utils/var? base-var) (sci.utils/var? fork-var)
                  (or (identical? base-var fork-var)
                      (same-program-root? @fork-var @base-var))))))

(defn- stale-reach
  "The agent-authored transitive callers of `function-symbol` in `database` and
  the agent-authored test rows reaching it, those `stale?` holds as analyzed
  against another Var of it."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :qualified-symbol
                       [:=> [:cat :qualified-symbol] :boolean]]
                  [:map [::callers [:vector :qualified-symbol]] [::tests [:vector :map]]]]}
  [ctx database function-symbol stale?]
  (let [closure (seon.fn/reverse-closure {:seon.db/db database :seon.fn/seeds #{function-symbol}})
        _ (when (:seon.db/invalid-read closure)
            (throw (ex-info (:seon.error/message closure) closure)))
        tests (filter stale? (sort (:seon.fn/tests closure)))]
    {::callers (into [] (filter #(and (stale? %) (::agent-authored? (kernel/program-function ctx %))))
                     (sort (disj (:seon.fn/affected closure) function-symbol)))
     ::tests (if (empty? tests)
               []
               (into [] (filter #(= :agent (:seon.schema.admission/source %)))
                     (db/pull-many database [:seon.test/sym :seon.test/source :seon.schema.admission/source
                                             {:seon.test/ns [:seon.ns/name]}]
                                   (mapv #(vector :seon.test/sym %) tests))))}))

(defn- reinterpret-reach!
  "Re-interpret `callers` then `tests` from `database` in `ctx`, so each resolves
  the Vars `ctx` holds now; their symbols."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value
                       [:map [::callers [:vector :qualified-symbol]] [::tests [:vector :map]]]]
                  [:vector :qualified-symbol]]}
  [ctx database {callers ::callers tests ::tests}]
  ;; Re-interpretation is not the form's own definition: no write or
  ;; expansion of it is observed as the program change.
  (binding [sci.lang/*write-observer* nil
            sci.lang/*expansion-observer* nil]
    ;; Copy every caller and test into this generation before analyzing any:
    ;; SCI analyzes a body against the Var each name holds then, and `deftest`
    ;; names its own Var in its body, as `install-selected!` copies first.
    (doseq [qualified (into callers (map :seon.test/sym) tests)
            :let [held (sci/resolve ctx qualified)]
            :when held]
      (sci/bind-root! ctx held @held))
    (doseq [caller callers]
      (install-function-from-database! ctx database caller))
    (doseq [{{namespace-name :seon.ns/name} :seon.test/ns :as row} tests]
      (install-row! {:seon.sci.eval/ctx ctx :seon.db/db database
                     :seon.program/row (assoc row :seon.test/ns [:seon.ns/name namespace-name])})))
  (into callers (map :seon.test/sym) tests))

(defn reinterpret-inherited-callers!
  "The base's callers of `function-symbol`, then the base's tests reaching it,
  re-interpreted in the turn fork `ctx`. When the fork defines it as `sci-var`
  apart from the base's Var, its callers and tests call that definition in the
  same pass and every later one, as one Clojure Var."
  {:seon/tasks #{'seon.sci.eval/a-redefinition-walks-the-reverse-closure-with-its-test-reach}
   :malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :qualified-symbol :seon.sci.eval/sci-var]
                  [:vector :qualified-symbol]]}
  [ctx database function-symbol sci-var]
  (let [base (::base-ctx ctx)
        base-var (when base
                   (get-in @(:env base) [:namespaces (symbol (namespace function-symbol))
                                         (symbol (name function-symbol))]))]
    (if-not (and (sci.utils/var? base-var) (not (identical? base-var sci-var)))
      []
      (reinterpret-reach! ctx database (stale-reach ctx database function-symbol #(inherited? ctx base %))))))

(defn- authored-rows
  "The agent-authored program rows of `database`, found by their admission datoms."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector :map]]}
  [database]
  (let [datoms (db/datoms database :aevt :seon.schema.admission/source)]
    (when (db/read-refusal? datoms)
      (throw (ex-info (:seon.error/message datoms) datoms)))
    (db/pull-many database (conj namespace-pattern {:seon.test/ns [:seon.ns/name]})
                  (into [] (keep (fn [datom] (when (= :agent (:v datom)) (:e datom)))) datoms))))

(defn- overlay-plan
  "What `database` changes of the `loaded` program: the authored functions whose
  digest the loaded program lacks or holds otherwise, the other functions it
  lacks, and the authored namespace and test rows it does not hold as they are."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value] :map]}
  [database loaded]
  (let [rows (authored-rows database)
        previous (authored-rows loaded)
        digests (loaded-digests loaded)
        ;; `loaded-digests` memoizes any committed value's digests by commit
        held (if (commit-id-of database) (loaded-digests database) (function-digests database))
        overridden (into #{} (keep (fn [{sym :seon.fn/sym :as row}]
                                     (when (and sym (not= (row-definition-digest row) (get digests sym))) sym)))
                         rows)
        namespace-key (fn [row] [(:seon.ns/source row) (row-bindings row)])
        test-row (fn [row] [(:seon.test/sym row) (:seon.test/source row)
                            (get-in row [:seon.test/ns :seon.ns/name]) (:seon.schema.admission/source row)])
        held-namespaces (into #{} (comp (filter :seon.ns/name) (map namespace-key)) previous)
        held-tests (into #{} (comp (filter :seon.test/sym) (map test-row)) previous)]
    {::overridden overridden
     ::added (into #{} (remove #(or (contains? digests %) (overridden %))) (keys held))
     ::namespaces (into [] (filter #(and (:seon.ns/name %) (:seon.ns/source %)
                                         (not (held-namespaces (namespace-key %)))))
                        rows)
     ::tests (into [] (comp (filter :seon.test/sym) (map test-row) (remove held-tests)) rows)}))

(defn- function-rows-in
  "`[sym source namespace admission private?]` of each admitted function among
  `symbols` stored in `database`, sorted by symbol."
  {:malli/schema [:=> [:cat :seon.db/database-value [:seqable :qualified-symbol]]
                  [:vector [:tuple :qualified-symbol :string :symbol :keyword :boolean]]]}
  [database symbols]
  (into []
        (keep (fn [{sym :seon.fn/sym source :seon.fn/source
                    admission :seon.schema.admission/source private? :seon.fn/private?}]
                (when admission
                  [sym (or source "") (symbol (namespace sym)) admission (boolean private?)])))
        (db/pull-many database [:seon.fn/sym :seon.fn/source :seon.schema.admission/source :seon.fn/private?]
                      (mapv #(vector :seon.fn/sym %) (sort symbols)))))

(defn- interpretable-functions
  "The functions among `symbols` whose row in `database` states they are not
  host-bound (`host-bound-row?`): SCI may interpret them."
  {:malli/schema [:=> [:cat :seon.db/database-value [:sequential :qualified-symbol]] [:set :qualified-symbol]]}
  [database symbols]
  (if (empty? symbols)
    #{}
    (let [rows (db/q '[:find [?sym ...] :in $ [?sym ...]
                       :where [?row :seon.fn/sym ?sym] [?row :seon.fn/host-bound? false]]
                     database (vec symbols))]
      (when (db/read-refusal? rows)
        (throw (ex-info (:seon.error/message rows) rows)))
      (set rows))))

(defn- executing-ctx
  "The context the calling thread runs SCI under, else `ctx`. A context a
  library stored as the root of `sci.ctx-store/*ctx*` is no thread's own, as
  SCI's own fn entry reads it."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :seon.sci.eval/ctx]}
  [ctx]
  (let [active sci.ctx-store/*ctx*]
    (if (or (nil? active) (identical? active (.getRawRoot #'sci.ctx-store/*ctx*))) ctx active)))

(defn- promote-member!
  "Interpret the closure member `sym` in `ctx` from `database`'s stored source.
  `sci-var`, the Var `ctx`'s analyzed callers hold, ends bound to that
  definition, to the compiled root a host-bound member keeps, or to a root
  refusing by name; never still to `pending`, the root that called this."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :qualified-symbol
                       :seon.sci.eval/sci-var [:fn clojure.core/ifn?]]
                  :nil]}
  [ctx database sym sci-var pending]
  ;; Interpreting a stored row is not an evaluated form's own definition.
  (binding [sci.lang/*write-observer* nil
            sci.lang/*expansion-observer* nil]
    (sci.ctx-store/with-ctx ctx
      (let [snapshot (::kernel/program-snapshot ctx)
            projection (or (db/carried-projection database) (context-projection ctx))
            database (vary-meta database assoc :seon.schema/projection projection)
            namespace-name (symbol (namespace sym))
            rows (function-rows-in database [sym])
            ns-row (or (get-in @snapshot [:namespaces namespace-name])
                       (namespace-row (db/pull database namespace-pattern [:seon.ns/name namespace-name])))]
        (when (seq rows)
          (schema/call-with-projection
           projection
           (fn []
             (swap! snapshot #(-> %
                                  (update :functions into (map (fn [row] [(first row) (function-entry row)])) rows)
                                  (update :namespaces assoc namespace-name ns-row)))
             (install-declared-classes! ctx [ns-row])
             (sci/install-namespace-bindings! ctx namespace-name (assoc (row-bindings ns-row) :refers {}))
             (install-required-libraries! ctx [ns-row])
             ;; A refused row binds a root refusing by name, delivered when called.
             (install-selected! ctx database {namespace-name ns-row} rows [] #{sym}
                                {:seon.schema/projection projection :seon.sci.eval/installed 0}))))
        ;; A kept compiled member binds a fresh Var at its name; the Var the
        ;; callers hold follows it.
        (let [current (sci/resolve ctx sym)]
          (when (and current (not (identical? current sci-var)))
            (sci/bind-root! ctx sci-var @current)))
        (when (identical? pending @sci-var)
          (sci/bind-root! ctx sci-var
                          (refusing (interpretation-error
                                     sym (str sym " has no stored definition this context can interpret.")))))
        nil))))

(defn- lazy-root
  "The root of closure member `sym` at `sci-var` until its first call: that
  call interprets the member, in the context executing it, from `database`'s
  stored source (`promote-member!`), so cost follows the members called; then
  it and every later call run what that context binds."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :qualified-symbol :seon.sci.eval/sci-var]
                  [:fn clojure.core/ifn?]]}
  [ctx database sym sci-var]
  (fn lazy-member [& args]
    (let [executing (executing-ctx ctx)
          held #(sci.ctx-store/with-ctx executing @sci-var)]
      (when (identical? lazy-member (held))
        (promote-member! executing database sym sci-var lazy-member))
      (apply (held) args))))

(defn- bind-lazy-members!
  "Bind each of `members` in `ctx` to its `lazy-root`, creating the Var of one
  `ctx` does not hold; the macros among them, left unbound for eager
  interpretation: a macro runs when its callers are analyzed, not called."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value [:seqable :qualified-symbol]]
                  [:vector :qualified-symbol]]}
  [ctx database members]
  (sci.ctx-store/with-ctx ctx
    (reduce
     (fn [macros sym]
       (let [held (sci/resolve ctx sym)]
         (if (and held (:macro (meta held)))
           (conj macros sym)
           (let [namespace-name (symbol (namespace sym))
                 sci-var (or held
                             (let [created (sci/new-var (symbol (name sym)) nil
                                                        {:ns (sci/create-ns namespace-name)})]
                               (sci/add-namespace! ctx namespace-name {(symbol (name sym)) created})
                               created))]
             (sci/bind-root! ctx sci-var (lazy-root ctx database sym sci-var))
             macros))))
     []
     members)))

(defn- interpret-overlay!
  "Interpret the overlay `plan` on `ctx`: its overrides, its namespaces and its
  tests now, and bind the compiled Vars of the functions it adds; its reverse
  closure (functions, and the tests reaching them as interpreted tests) is
  bound lazily (`bind-lazy-members!`) but for its host-bound members, which
  keep the compiled Vars `ctx` binds, from `::closure` and `::closure-tests`
  when the plan carries them, else walked from the overrides. The acquisition
  advanced from the base's. What `ctx` already interprets, and the plan's
  `::evaluated` definitions it holds as evaluated, are not installed again."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :map] :map]}
  [ctx database {::keys [overridden added namespaces tests evaluated] :as plan}]
  (let [snapshot @(::kernel/program-snapshot ctx)
        already (into (set (::interpreted snapshot)) evaluated)
        already-tests (set (::interpreted-tests snapshot))
        projection (or (db/carried-projection database) (:seon.schema/projection ctx))
        database (vary-meta database assoc :seon.schema/projection projection)
        closure (cond (contains? plan ::closure) {:seon.fn/affected (::closure plan)
                                                  :seon.fn/tests (::closure-tests plan)}
                      (seq overridden) (seon.fn/reverse-closure {:seon.db/db database :seon.fn/seeds overridden})
                      :else {:seon.fn/affected #{}})
        _ (when (:seon.db/invalid-read closure)
            (throw (ex-info "Cannot acquire the affected function closure." closure)))
        interpreted (into overridden (:seon.fn/affected closure))
        tests (into [] (remove (comp already-tests first)) tests)
        callers (into [] (remove #(or (already %) (contains? overridden %))) (sort (:seon.fn/affected closure)))
        ;; A host-bound caller keeps the compiled Var the context binds; only
        ;; the macros among the rest are interpreted now.
        macros (bind-lazy-members! ctx database
                                   (filter (interpretable-functions database callers) callers))
        added-rows (function-rows-in database (remove interpreted added))
        entered (::entered-namespaces snapshot #{})
        ;; an added function of a source namespace has no compiled Var: it
        ;; interprets now, a test namespace's once that namespace is entered
        sourced (into [] (keep (fn [[sym _ namespace-name]]
                                 (when (and (source-namespace? ctx namespace-name)
                                            (or (entered namespace-name)
                                                (not (contains? (::test-namespaces snapshot) namespace-name))))
                                   sym)))
                      added-rows)
        function-rows (function-rows-in database (into (vec (remove already overridden))
                                                       (concat macros sourced)))
        selected (into (into #{} (map :seon.ns/name) namespaces) (map #(nth % 2)) function-rows)
        new-namespaces (into #{} (comp (map #(nth % 2)) (remove (:namespaces snapshot)) (remove selected))
                             added-rows)
        pulled (into {} (map (juxt :seon.ns/name namespace-row))
                     (db/pull-many database namespace-pattern
                                   (mapv #(vector :seon.ns/name %) (sort-by str (into selected new-namespaces)))))
        namespace-rows (vec (vals pulled))
        namespace-row-by-name (select-keys pulled selected)]
    (schema/call-with-projection
     projection
     (fn []
       (swap! (::kernel/program-snapshot ctx)
              #(-> %
                   (update :functions into (map (fn [row] [(first row) (function-entry row)]))
                           (concat added-rows function-rows))
                   (update :namespaces merge pulled)
                   (update ::interpreted (fnil into #{}) interpreted)
                   ;; The tests reaching a change run its interpreted program
                   ;; too, a file-derived test as the agent's own: compiled,
                   ;; its body would call the compiled callees. A file test is
                   ;; interpreted when it runs (`enter-namespace!`).
                   (update ::interpreted-tests (fnil into #{})
                           (concat (map first tests) (:seon.fn/tests closure)))))
       (install-declared-classes! ctx namespace-rows)
       (doseq [row namespace-rows]
         (sci/install-namespace-bindings!
          ctx (:seon.ns/name row) (assoc (row-bindings row) :refers {})))
       ;; A function the loaded program lacks runs as its compiled Var where
       ;; this JVM has one, exactly as the base binds the loaded program's.
       (install-first-party-namespaces! ctx [] namespace-rows added-rows)
       (install-required-libraries! ctx namespace-rows)
       (record-acquisition-refusals!
        ctx database
        (install-selected!
         ctx database namespace-row-by-name
         function-rows tests (into #{} (map first) function-rows)
         (-> (::acquisition snapshot)
             (dissoc ::acquisition-refusals-recorded? ::acquisition-recording-error)
             (assoc :seon.schema/projection projection
                    :seon.sci.eval/interpreted-count
                    (count (::interpreted @(::kernel/program-snapshot ctx))))))
        (:seon.flow/commit-fault! snapshot))))))

(defn interpret-affected!
  "Interpret in `ctx` the callers and tests reaching `changed` it runs compiled.
  `changed` are functions `ctx` holds as evaluated; each caller of their
  reverse closure that `ctx` still runs compiled is bound to interpret itself
  from its stored source in `database` on its first call (`bind-lazy-members!`),
  so it calls `ctx`'s definitions, and each test reaching them runs
  interpreted. A host-bound caller keeps its compiled Var. Once interpreted, a
  caller sees every later redefinition through its context's binding, so a
  repeated change pays only the closure read. The acquisition after it."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value [:sequential :qualified-symbol]] :map]}
  [ctx database changed]
  ;; Interpreting a stored row is not the form's own definition: no write
  ;; or expansion of it is observed as the program change.
  (binding [sci.lang/*write-observer* nil
            sci.lang/*expansion-observer* nil]
    (sci.ctx-store/with-ctx ctx
      (let [acquired (interpret-overlay! ctx database {::overridden (set changed) ::evaluated (set changed)
                                                       ::added #{} ::namespaces [] ::tests []})]
        (swap! (::kernel/program-snapshot ctx) assoc ::acquisition acquired)
        acquired))))

(defn- carry-promotions!
  "Bind lazily in `ctx` the closure the turn fork's program snapshot
  `promoted` interprets beyond `ctx`'s, as `interpret-affected!` would for
  `changed`; a changed definition the fork does not interpret has its closure
  walked here. The acquisition after it."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :map [:sequential :qualified-symbol]] :map]}
  [ctx database promoted changed]
  (let [held @(::kernel/program-snapshot ctx)
        carried (set (::interpreted promoted))
        unwalked (remove carried changed)]
    (when (seq unwalked)
      (interpret-affected! ctx database unwalked))
    (binding [sci.lang/*write-observer* nil
              sci.lang/*expansion-observer* nil]
      (sci.ctx-store/with-ctx ctx
        (let [acquired (interpret-overlay!
                        ctx database
                        {::overridden (set changed) ::evaluated (set changed) ::added #{} ::namespaces [] ::tests []
                         ::closure (into #{} (remove (set (::interpreted held))) carried)
                         ::closure-tests (into #{} (remove (set (::interpreted-tests held)))
                                               (::interpreted-tests promoted))})]
          (swap! (::kernel/program-snapshot ctx) assoc ::acquisition acquired)
          acquired)))))

(defn- overlay!
  "Interpret on `ctx`, a fork of the cluster base, what `database` changes of
  the loaded program; the acquisition, the base's own when nothing changed."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value] :map]}
  [ctx database]
  (let [snapshot @(::kernel/program-snapshot ctx)
        loaded (::loaded-database snapshot)
        commit (commit-id-of database)
        plan (when-not (or (identical? loaded database)
                           (and commit (= commit (commit-id-of loaded))))
               (overlay-plan database loaded))
        acquired (if (some seq (vals plan))
                   (interpret-overlay! ctx database plan)
                   (::acquisition snapshot))]
    (swap! (::kernel/program-snapshot ctx) assoc
           ::acquisition acquired ::armed-dials (armed-dials database))
    acquired))

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

(defn- latest-print-budget
  "The `seon.print/*budget*` the agent's latest evaluation ended with: a
  session `set!` back to nil records none, so absence there means none."
  [db agent-id]
  (when-let [latest (ffirst (db/q '[:find (max ?evaluation)
                                     :in $ ?agent-id
                                     :where
                                     [?agent :seon.agent/id ?agent-id]
                                     [?run :seon.turn/agent ?agent]
                                     [?evaluation :seon.cluster.eval/run ?run]]
                                   db agent-id))]
    (:seon.eval/print-budget (db/pull db [:seon.eval/print-budget] latest))))

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
    (let [length (latest-print-fact db agent-id :seon.eval/print-length)
          level (latest-print-fact db agent-id :seon.eval/print-level)
          budget (latest-print-budget db agent-id)]
      (cond-> {}
        (int? length) (assoc :seon.print/length length)
        (int? level) (assoc :seon.print/level level)
        (pos-int? budget) (assoc :seon.print/budget budget)))))

(defn- base-bindings
  "Snapshot program roots, as `ctx` binds them, to distinguish the entering
  private layer."
  [ctx]
  (sci.ctx-store/with-ctx ctx
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
          (sci/namespace-state ctx))))

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
  generations defined, its own bindings of inherited Vars, and Vars at a path the base it forked from never had (a
  namespace it required from the JVM, `load-host-namespace`). Owned private
  Vars keep their roots, including captured values; an own definition keeps its
  Var, which takes another writer's definition of its row in place. Inherited
  program bindings are replaced. A JVM restart has no entering context and
  consequently preserves no private objects. No database read restores them.
  An unavailable execution's private layer is the unrestored one, so it
  carries nothing: the context is the base acquired at the branch head."
  [ctx base]
  (let [;; Every generation this context held: SCI stamps a Var born in one,
        ;; and each regeneration gives the context a new one.
        generations (conj (get @(:env ctx) ::generations #{}) (:sci/generation @(:env ctx)))
        layer (sci/namespace-state ctx)
        previous @(::base-bindings ctx)
        current (base-bindings base)
        base-layer (sci/namespace-state base)
        own-binding (own-bindings ctx)
        changed? #(seq (replaced-definitions @(::kernel/program-snapshot ctx)
                                             @(::kernel/program-snapshot base)
                                             [(symbol (str %1) (str %2))]))
        private-bindings
        (sci.ctx-store/with-ctx ctx
          (doall
           (for [[namespace-name entries] layer
                 [local-name entry] entries
                 :when (sci.utils/var? entry)
                 :let [path [namespace-name local-name]
                       root (if (sci.vars/hasRoot entry) @entry absent-intern)
                       held (get current path)
                       transferred? (boolean (and held (same-program-root? root (first held))))
                       binding (get own-binding entry)
                       rebound? (some? binding)
                       own? (or rebound? (contains? generations (:sci/generation (meta entry))))
                       changed (when-not transferred? (changed? namespace-name local-name))
                       adopted (when (and own? changed (sci.utils/var? (get-in base-layer path)))
                                 (get-in base-layer path))]
                 ;; An own Var stays the Var its callers' closures hold, as one
                 ;; Clojure Var does, with this context's binding of it:
                 ;; settlement transfers its root into the base, and another
                 ;; writer's definition of its row is adopted into it. Only a
                 ;; deleted row drops it.
                 :when (if own?
                         (or transferred? (not changed) adopted)
                         (and (not (contains? previous path)) (not changed) (not transferred?)))]
             [namespace-name local-name entry (or transferred? (some? adopted)) adopted
              binding])))
        private-names (into #{} (map (fn [[namespace-name local-name _]]
                                      (symbol (str namespace-name) (str local-name))))
                            private-bindings)
        ;; the base's row of a transferred or adopted definition is the current one
        snapshot-names (into #{} (keep (fn [[namespace-name local-name _ base-row?]]
                                         (when-not base-row?
                                           (symbol (str namespace-name) (str local-name)))))
                             private-bindings)
        regenerated
        (inherit-var-bindings!
         (assoc (merge ctx (sci/fork base))
                ::base-bindings (atom current)
                ::kernel/installed-functions
                (atom (into @(::kernel/installed-functions base)
                            (filter private-names)
                            @(::kernel/installed-functions ctx)))
                ::kernel/program-snapshot
                (atom (update @(::kernel/program-snapshot base) :functions merge
                              (select-keys (:functions @(::kernel/program-snapshot ctx))
                                           snapshot-names)))
                :seon.sci.eval/private-state :preserved-in-memory))]
    ;; The regenerated context takes a new generation, so it writes no Var
    ;; its earlier forks share in place; the Vars born in the ones it held
    ;; stay its own.
    (swap! (:env regenerated) assoc ::generations generations)
    ;; Owned private Vars keep their objects and this context's bindings of
    ;; them. Re-interning only their roots would detach private closures from
    ;; their private Vars.
    (sci.ctx-store/with-ctx regenerated
      (doseq [[namespace-name local-name entry _ adopted binding] private-bindings]
        (when binding
          (sci.vars/update-binding! regenerated entry (constantly binding)))
        (when (and adopted (sci.vars/hasRoot adopted))
          (sci/bind-root! regenerated entry @adopted)
          (reset-meta! entry (merge (meta adopted) (select-keys (meta entry) [:ns]))))
        (sci/add-namespace! regenerated namespace-name {local-name entry})))
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

(defn- settled-results
  "Every evaluation of the agent that has a handle, `{evaluation-id {class}}`:
  the handles a fresh context binds again under `result/eN`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  ;; pull answers nil for an evaluation that recorded no class
                  [:or [:map-of :string [:maybe :map]] :seon.db/invalid-read-error]]}
  [db agent-id]
  (let [rows (db/q '[:find ?id (pull ?evaluation [:seon.eval/value-class])
                     :in $ ?agent-id
                     :where [?agent :seon.agent/id ?agent-id]
                            [?turn :seon.turn/agent ?agent]
                            [?evaluation :seon.cluster.eval/run ?turn]
                            [?evaluation :seon.cluster.eval/id ?id]]
                   db agent-id)]
    ;; a refused read is answered as itself, never as no handles
    (if (db/read-refusal? rows) rows (into {} rows))))

(defn- bind-settled-results!
  "Bind every handle of the agent in a context that holds none (a fresh fork:
  after a restart, the handles its earlier replies printed) to a marker naming
  the value's class: values live only in the context that evaluated them and
  are never stored, so no handle dangles silently. A refused read of the
  handles is answered as the refusal."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value [:maybe :seon.agent/id]]
                  [:or :nil :seon.error/value]]}
  [ctx db agent-id]
  (when agent-id
    (let [handles (settled-results db agent-id)]
      (if (db/read-refusal? handles)
        handles
        (doseq [[evaluation-id {class-name :seon.eval/value-class}] handles]
          (bind-result! ctx (admit/result-handle evaluation-id)
                        (cond-> {:seon.eval/unstored :live-only
                                 :seon.eval/unstored-note "lived only in the context that evaluated it; values are not stored"}
                          class-name (assoc :seon.eval/value-class class-name))))))))

(defn fork-for-turn
  "Fork the current base and reapply the agent's in-memory private layer.
  A fresh fork (no carried agent context: a restart) binds each of the agent's
  settled handles to a marker naming its value's class; values are never stored."
  {:malli/schema [:=> [:cat :seon.sci.eval/defs-fork-request]
                  :seon.sci.eval/defs-fork-result]}
  [{base-ctx :seon.sci.eval/ctx
    agent-ctx :seon.sci.eval/agent-ctx
    db :seon.db/db
    agent-id :seon.agent/id}]
  (let [carried? (some? agent-ctx)
        ctx (if agent-ctx
              (regenerate-agent-context! agent-ctx base-ctx)
              (env/carry-state
               (assoc (inherit-var-bindings! (sci/fork base-ctx))
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
    ;; Binding is a later stage than the fork: its failure (a refused read,
    ;; or a throw such as an armed contract's refusal) never fails the fork.
    ;; It rides the result as data; the entrant records it as a fault
    ;; delivered to the agent (seon.turn/resume-turn,
    ;; seon.cluster.agent/acquire-context!).
    (let [unbound (when-not agent-ctx
                    (try (bind-settled-results! ctx db agent-id)
                         (catch Throwable failure
                           (seon.error.refusal/diagnostic
                            (java.util.Date.) :seon.sci.eval/fork 'seon.sci.eval/bind-settled-results!
                            {:seon.error/message (str "The agent's result handles could not be bound: "
                                                      (ex-message failure))
                             :seon.error/throwable failure}))))]
      (cond-> {:seon.sci.eval/ctx ctx
               :seon.sci.eval/private-state
               (if carried? :preserved-in-memory :absent)}
        ;; the handles a refused read left unbound: the refusal, for the
        ;; entrant to deliver
        unbound (assoc :seon.sci.eval/unbound-results unbound)))))

(def ^:private program-cache-policy
  "Bound the memoized program identities independently of commits."
  {::program-identity-cache-size 256
   ::program-identity-cache-reason
   "One entry per recently acquired commit: agent turns, MCP evaluations and test requests on a few live branches; an evicted commit derives its identity again from its own cache-context."})

(defonce ^:private program-identity-cache
  (cache/lru-cache-factory
   {} :threshold (::program-identity-cache-size program-cache-policy)))

(defn- acquisition-attributes
  "Every attribute an acquisition reads, derived from the projection's declarations.

  The program partition (`seon.program/program-attributes`), the projection's own
  read set (`seon.schema/projection-attributes`) and the loaded source commit.
  Dials are not among them: a context compares the dial values its wrappers
  armed with (`armed-dials`). Retained on the projection's own holder
  (`schema/projection-cache-value`)."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:set :qualified-keyword]]}
  [projection]
  (schema/projection-cache-value
   projection ::acquisition-attributes
   (fn []
     (-> (program/program-attributes projection)
         (into schema/projection-attributes)
         (conj :seon.config/cluster :seon.source/commit-id)))))

(defn- revision-basis
  "The part of a value's Datahike cache-context that names its attribute revisions.

  Datahike advances one attribute revision per attribute a commit touched and the
  conservative revision on a schema or unknown change
  (`reference-code/datahike/src/datahike/query.cljc:2568-2589`); its query cache
  carries results across commits by comparing exactly these members
  (`datahike.query/source-context-unchanged?`). Each revision is a commit id:
  a connection opens with that commit as its conservative revision
  (`datahike.connector/connect`), so an attribute's revision, else the
  conservative one, names a commit whose datoms of that attribute equal this
  value's, on any connection (`datahike.db/advance-cache-context`)."
  {:malli/schema [:=> [:cat :seon.db/database-value] :map]}
  [database]
  (select-keys (:cache-context database)
               [:datahike.cache/connection-id
                :datahike.cache/generation
                :datahike.cache/conservative-revision
                :datahike.cache/attribute-revisions]))

(defn- program-basis
  "Each attribute an acquisition reads with the commit its datoms equal: its own
  revision, else the conservative one. Equal bases hold equal datoms of those
  attributes, whichever connection produced them."
  {:malli/schema [:=> [:cat :map [:set :qualified-keyword]] [:map-of :qualified-keyword [:maybe :uuid]]]}
  [{revisions :datahike.cache/attribute-revisions conservative :datahike.cache/conservative-revision} attributes]
  (into {} (map (fn [attribute] [attribute (get revisions attribute conservative)])) attributes))

(defn- inherited-basis
  "`basis` over the identity remembered for the commit its conservative revision
  names: a branch opened at a known commit keeps that commit's program, plus the
  attributes it wrote since."
  {:malli/schema [:=> [:cat :map] :map]}
  [{conservative :datahike.cache/conservative-revision :as basis}]
  (if-let [opened (some-> conservative (->> (cache/lookup program-identity-cache)) deref)]
    (update opened :datahike.cache/attribute-revisions merge (:datahike.cache/attribute-revisions basis))
    basis))

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
  (`watch-program-identity!`), so its program compares with the parent's, and a
  commit written there keeps that program for every attribute it did not write
  (`inherited-basis`). A commit
  proven program-equal to an acquired value takes that value's basis
  (`acquired-database?`). Compare two identities through `program-basis` over the
  acquisition attributes. A value without committed identity has none."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :nil :map]]}
  [database]
  (when-let [commit-id (commit-id-of database)]
    (remember-program-identity! commit-id (inherited-basis (revision-basis database)))))

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
  "Derive the cluster base: load the loaded program's JVM namespaces, build the
  interpreter under its projection and acquire the `loaded` program."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.sci.eval/ctx]}
  [loaded]
  ;; Once per derivation: a JVM keeps what it loaded, so a cached base proves
  ;; its loaded program's namespaces loaded.
  (load-core-namespaces! loaded)
  (let [projection (if-let [carried (db/carried-projection loaded)]
                     (schema/projection-from-database loaded carried)
                     (schema/projection-from-database loaded))]
    (schema/call-with-projection
     projection
     (fn []
       (let [ctx (assoc (build-base-ctx projection)
                        ::bind-result! #'bind-result!
                        :seon.schema/projection projection
                        ::kernel/install-function! install-function-from-database!)
             _ (swap! (::kernel/program-snapshot ctx) assoc ::loaded-database loaded)
             acquired (acquire-program! {:seon.sci.eval/ctx ctx
                                         :seon.db/db loaded
                                         :seon.schema/projection projection})]
         ;; The base is shared by every branch and holds no database value: one
         ;; would pin its connection's node cache. `copy-base-ctx` supplies each
         ;; caller's database and loaded program.
         (swap! (::kernel/program-snapshot ctx)
                #(-> (dissoc % ::loaded-database)
                     (assoc ::acquisition acquired
                            ::program-namespaces
                            {::generation (:sci/generation @(:env ctx))
                             ::namespaces (sci/namespace-state ctx)}
                            :seon.test/class-loader (clojure.lang.RT/baseLoader))))
         (assoc ctx ::acquisition acquired))))))

(defn- cluster-base-key
  "The inputs the cluster base is a function of: the loaded program's store and
  commit, which also name its projection. The base interprets nothing, so it
  arms no wrapper over a fault recorder. Nil for an uncommitted loaded value."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :nil [:tuple :map :uuid]]]}
  [loaded]
  (when-let [commit (commit-id-of loaded)]
    [(store-identity loaded) commit]))

(defn- copy-base-ctx
  "A fresh context over the cluster base: an SCI fork (its own Var bindings) plus owned
  copies of the kernel atoms, holding the caller's database, loaded program and
  fault recorder."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :seon.db/database-value
                       [:or :nil :seon.flow/commit-fault!]]
                  :seon.sci.eval/ctx]}
  [cached database loaded recorder]
  (assoc (inherit-var-bindings! (sci/fork cached))
         ::kernel/installed-functions (atom @(::kernel/installed-functions cached))
         ::kernel/program-snapshot
         (atom (cond-> (assoc @(::kernel/program-snapshot cached)
                              :seon.db/db database
                              ::loaded-database loaded)
                 recorder (assoc :seon.flow/commit-fault! recorder)))))

(defn base-ctx
  "Derive the program-only SCI context from one database value.

  Matching definitions copy the loaded JVM Var root. Overrides and affected
  callers interpret their admitted source. Acquisition refusals remain values on the context;
  construction has no connection, writes no facts, and restores no private state.
  Each call forks the one cluster base of the JVM's loaded program (a `seon.cache`
  kind) and interprets the value's own changes on that fork (`overlay!`)."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value] :seon.sci.eval/ctx]
    [:=> [:cat :seon.db/database-value
          [:map [:seon.flow/commit-fault! {:optional true} :seon.flow/commit-fault!]]]
     :seon.sci.eval/ctx]]}
  ([database] (base-ctx database {}))
  ([database arm-request]
   (let [loaded (or (::loaded-database arm-request) (loaded-program database))
         derive-base #(derive-base-ctx loaded)
         cluster (if-let [cache-key (cluster-base-key loaded)]
                   (seon.cache/lookup ::base-context cache-key
                                      #(* 2048 (count (:functions @(::kernel/program-snapshot %))))
                                      derive-base)
                   (derive-base))
         projection (or (db/carried-projection database) (schema/projection-from-database database))
         ctx (assoc (copy-base-ctx cluster database loaded (:seon.flow/commit-fault! arm-request))
                    :seon.schema/projection projection)]
     (assoc ctx ::acquisition (overlay! ctx database)))))

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
  Dial values the wrappers armed with must also be unchanged. A value without
  committed identity never matches."
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
          ;; A settings write re-arms only when it moved a value the
          ;; context's interpreted wrappers armed with.
          (= (::armed-dials snapshot) (armed-dials database))
          (or (identical? previous database)
              (when (and commit-id (commit-id-of previous))
                (let [attributes (acquisition-attributes (db/carried-projection previous))
                      acquired (program-identity previous)]
                  (or (= (program-basis acquired attributes)
                         (program-basis (program-identity database) attributes))
                      ;; Equal effective revisions name commits with equal
                      ;; datoms of every acquisition attribute.
                      (when (= (program-basis (revision-basis database) attributes)
                               (program-basis (revision-basis previous) attributes))
                        ;; A branch opened at this commit reads the acquired
                        ;; program by commit id from here on.
                        (cache/evict program-identity-cache commit-id)
                        (remember-program-identity! commit-id acquired)
                        true)))))))))

(defn program-equal!
  "Returns the program identity `database`'s commit now holds: `source`'s.
  The caller proved `database` holds `source`'s program, so a context that
  acquired it acquires `database` at once (`acquired-database?`). Nil when
  either value has no committed identity."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value] [:or :nil :map]]}
  [database source]
  (when-let [commit-id (commit-id-of database)]
    (when-let [identity (program-identity source)]
      (cache/evict program-identity-cache commit-id)
      (remember-program-identity! commit-id identity))))

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
  (sci.ctx-store/with-ctx ctx
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
              source (loaded-source snapshot database)
              generated (base-ctx database
                                  (cond-> {::loaded-database (loaded-program source)}
                                    commit-fault!
                                    (assoc :seon.flow/commit-fault! commit-fault!)))
              acquired (::acquisition generated)
              ;; This context's own definitions (the Vars it wrote that no
              ;; program install bound) survive as objects with its bindings of
              ;; them, as `regenerate-agent-context!` carries them for a turn
              ;; fork, unless the program changed their symbol's row.
              installed @(::kernel/installed-functions ctx)
              interns (turn-interns ctx)
              replaced (replaced-definitions snapshot @(::kernel/program-snapshot generated)
                                             (keys interns))
              own (into [] (keep (fn [[qualified {sci-var :seon.sci.eval/var}]]
                                   (when-not (or (and (contains? installed qualified)
                                                      (not (get-in snapshot [:functions qualified ::context-defined?])))
                                                 (contains? replaced qualified))
                                     [(symbol (namespace qualified)) (symbol (name qualified)) sci-var
                                      (sci.vars/context-binding ctx sci-var)])))
                        interns)]
          ;; A fork of the base: the context takes a new generation, so it
          ;; writes no Var the base or its other forks share in place.
          (reset! (:env ctx) @(:env (sci/fork generated)))
          (inherit-var-bindings! ctx)
          (doseq [[namespace-name local-name sci-var binding] own]
            (when binding
              (sci.vars/update-binding! ctx sci-var (constantly binding)))
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
            recorded))))))

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

  Program Vars are shared through SCI's `fork`, each context writing its own bindings;
  connection custody, schema projection, and supplied-default state are
  reconstructed from the receiving branch and therefore cannot leak between
  sibling clusters. A fork of an acquired base acquires the supplied value's
  program, so rows committed after the base was acquired are present; an
  unacquired base stays lazy."
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
               (assoc (inherit-var-bindings! (sci/fork base-ctx))
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
     ;; bind-root! in the fork writes the fork's own binding of each Var.
     (sci.ctx-store/with-ctx ctx
       (doseq [function-symbol @(::kernel/installed-functions ctx)
               :let [candidate (sci/resolve ctx function-symbol)]
               :when (and candidate
                          (::instrument/interpreted-original (meta @candidate)))]
         (install-function-contract!
          ctx (db/pull db '[*] [:seon.fn/sym function-symbol]) projection db)))
     (when connection
       (watch-program-identity! connection))
     ;; A fork of an acquired program holds the program of the value it is
     ;; forked onto: rows committed after the base was acquired are acquired
     ;; here (`acquire!` answers at once when the program is equal). An
     ;; unacquired base stays lazy. A fork onto the commit the base's value
     ;; is based on already holds that program: a run's working value is
     ;; uncommitted over it (`seon.turn/with-settled`), and re-acquiring the
     ;; commit would drop the run's earlier declarations from a gate's copy.
     ;; Only a database that cannot store the acquisition's refusal records
     ;; refuses the fork; the refusals themselves are recorded values.
     (when (let [snapshot @(::kernel/program-snapshot ctx)]
             (and (::acquisition snapshot)
                  (not= (db/commit-id db) (db/commit-id (:seon.db/db snapshot)))))
       (when-let [failure (::acquisition-recording-error
                           (acquire! {:seon.sci.eval/ctx ctx :seon.db/db db}))]
         (throw (ex-info "Program acquisition could not record its refusals."
                         {::acquisition-recording-error failure}))))
     ctx)))

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

(declare evaluate shown-result)

(defn- evaluated-source
  "A render's REPL entries as the viewing agent's REPL shows them: each form
  read once and evaluated by the frame (`evaluate` given the read form) in
  `request`'s SCI world, then `;=> ` and its shown result (`shown-result`); comments keep their place. Answers
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
                form-request (-> request
                                 (dissoc :seon.turn/id :seon.cluster.eval/ordinal :seon.repl/handle
                                         :seon.render.call/function :seon.sci.eval/output-prefix
                                         :seon.render.value/options)
                                 (assoc :seon.cluster.eval/source (:seon.sci.reader/source event)
                                        :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                                        ::rendering? true))
                ;; the frame evaluates the form read here; its value prints
                ;; through the one printer, as the fold's show stage does
                evaluation (when-not (:seon.sci.reader/error event)
                             (evaluate (assoc form-request ::form (:seon.sci.reader/form event))))]
            (if (or (nil? evaluation) (:seon.cluster.eval/error evaluation))
              (refused (:seon.sci.reader/source event)
                       (str "A render form failed: "
                            (or (:seon.cluster.eval/error evaluation)
                                (get-in event [:seon.sci.reader/error :seon.error/message]))))
              (recur more end (conj shown (subs source offset end) "\n;=> "
                                    (:seon.eval/shown (shown-result (:seon.sci.admit/value evaluation) form-request
                                                                    (:seon.sci.admit/record evaluation))))))))))))

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
        request (cond-> (if (:seon.render/refused-member profile)
                          (dissoc request :seon.render/profile)
                          (assoc request :seon.render/profile profile))
                  ;; A REPL prints under the session's `*print-length*` and
                  ;; `*print-level*` (its `set!`s), held by the agent's fork.
                  (and (not (contains? request :seon.print/options)) (some-> ctx ::print-session deref seq))
                  (assoc :seon.print/options @(::print-session ctx)))
        ;; The render's forms read the value by its handle; the form's
        ;; printed output is live beside it, so a cut of `:out` reads it.
        output (:seon.cluster.eval/output request)
        _ (when (and ctx handle) (bind-result! ctx handle value))
        _ (when (and ctx handle (seq output)) (bind-output! ctx handle output))
        ;; A preparation or print the render stage refuses (a unit it cannot
        ;; build, a refusal its own contract rejects) is one diagnostic; the
        ;; value then shows as bounded plain Clojure printing.
        [projection shown]
        (try
          (let [projection (render.value/prepare
                            (cond-> (assoc (dissoc request :seon.cluster.eval/output)
                                           :seon.render/value value
                                           :seon.render.call/id
                                           [:seon.cluster.eval/source
                                            (:seon.cluster.eval/source request)])
                              (and ctx (:seon.sci.eval/time-limit-ms request) (:seon.sci.admit/caps request)
                                   (not (::rendering? request)))
                              (assoc :seon.sci.eval/evaluate-source (partial evaluated-source request))
                              ;; ONE BUDGET PER REPLY: the output printed beside
                              ;; the value holds its share of the value's budget.
                              (seq output) (assoc :seon.render.value/beside (+ 2 (count output)))))]
            [projection
             (if (contains? projection :seon.render.value/root-description) projection
                 (or (:seon.render.value/value-text projection)
                     (render.value/render-ai-data projection)))])
          (catch Throwable failure
            [{:seon.render.value/diagnostics [{:seon.render.data/path [] :seon.render.value/failure failure}]}
             (render.value/core-text value)]))
        failures (into failures (map :seon.render.value/failure) (:seon.render.value/diagnostics projection))
        ;; a refusal of the root's description is data: the value printer bounds it
        shown (if (string? shown) shown (render.value/core-text shown))]
    ;; THE VALUE'S SIZE IS RECORDED BESIDE ITS SHOWN TEXT: the whole value's
    ;; estimated tokens from the floor's own cut counts (never a second print),
    ;; the shown tokens, how many cuts it made, and an explicit
    ;; `seon.print/full` opt-in. Queryable; shown only past a declared bound.
    ;; A VALUE THAT DECLARED ITS OWN BUDGET AND PRINTED WHOLE UNDER IT WAS ASKED
    ;; FOR (a definition's source): its size is not a misuse measure, so neither
    ;; the whole-value nor the override size is recorded for the bounds to judge.
    (cond-> {:seon.sci.admit/value value
             :seon.eval/shown shown
             :seon.eval/shown-tokens (tokens/estimate shown)}
      (int? (:seon.render.value/whole-characters projection))
      (assoc :seon.eval/value-tokens
             (tokens/estimate-of-characters (:seon.render.value/whole-characters projection)))
      (int? (:seon.render.value/elisions projection))
      (assoc :seon.eval/elisions (:seon.render.value/elisions projection))
      ;; THE VALUE STAYS LIVE IN ITS WORLD, NEVER COPIED: what outlives the
      ;; process is the shown text, the handle and the value's class (CLAUDE.md:
      ;; never serialize live results). A fresh context binds the handle to a
      ;; marker naming that class (`bind-settled-results!`).
      (and (:seon.sci.admit/caps request) (not (::rendering? request)))
      (assoc :seon.eval/value-class (if (nil? value) "nil" (.getName (class value))))
      (:seon.render.value/full? projection) (assoc :seon.eval/full true)
      (int? (:seon.render.value/budget-override projection))
      (assoc :seon.eval/budget-override (:seon.render.value/budget-override projection))
      (:seon.render.call/selected-producer projection)
      (assoc :seon.eval/renderer (:seon.render.call/selected-producer projection))
      (seq failures)
      (assoc :seon.cluster.eval/diagnostics
             (let [diagnostics (mapv flat-diagnostic failures)]
               (try (floor-text request diagnostics)
                    (catch Throwable _ (render.value/core-text diagnostics)))))
      record (assoc :seon.sci.admit/record record)
      (and (instance? clojure.lang.IMeta value) (pos-int? (:seon.print/budget (meta value)))
           (zero? (:seon.render.value/elisions projection 0)))
      (dissoc :seon.eval/value-tokens :seon.eval/budget-override))))

(defn shown-failure
  "A failure's shown result: its small error map (`seon.error/reply-value`)
  printed as data by the floor, while its handle and value stay the whole
  error. A failure has no value size, so no value-size finding fires on it."
  {:malli/schema [:=> [:cat :map :seon.sci.eval/shown-request [:maybe :seon.sci.admit/record]]
                  :seon.sci.eval/shown]}
  [error request record]
  (let [ctx (:seon.sci.eval/ctx request)
        handle (:seon.repl/handle request)
        shown (shown-result (error/reply-value error)
                            (assoc request :seon.render.value/options
                                   {:seon.render.value/structural? true})
                            record)]
    (when (and ctx handle) (bind-result! ctx handle error))
    (-> (apply dissoc shown [:seon.eval/value-tokens :seon.eval/shown-tokens
                             :seon.eval/elisions :seon.eval/full :seon.eval/value-class
                             :seon.eval/budget-override])
        (assoc :seon.sci.admit/value error))))

(def ^:private value-size-keys
  "The shown value's recorded size, carried from `shown-result` to the
  evaluation and settled onto its receipt."
  [:seon.eval/value-tokens :seon.eval/shown-tokens :seon.eval/elisions :seon.eval/full :seon.eval/value-class
   :seon.eval/budget-override])

(defn- success-evaluation
  [{admitted :seon.sci.eval/admitted
    output-prefix :seon.sci.eval/output-prefix
    printed :seon.sci.eval/printed
    namespace-name :seon.sci.eval/namespace-name
    ending-namespace :seon.sci.eval/ending-namespace
    print-options :seon.print/options
    duration-ms :seon.eval/duration-ms}]
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
    ;; HOW LONG THE EVALUATION TOOK IS A KEY OF THE EVALUATION.
    ;; Allocation is the kernel's measurement, kept instead of dropped.
    :always (assoc :seon.eval/duration-ms duration-ms)
    :always (merge (select-keys admitted value-size-keys))
    (pos? (get-in admitted [:seon.sci.admit/record :seon.eval/allocated-bytes] -1))
    (assoc :seon.eval/allocated-bytes
           (get-in admitted [:seon.sci.admit/record :seon.eval/allocated-bytes]))
    ;; the same threads' CPU time, for the request's cost (not stored)
    (nat-int? (get-in admitted [:seon.sci.admit/record :seon.cost/cpu-ns]))
    (assoc :seon.cost/cpu-ns (get-in admitted [:seon.sci.admit/record :seon.cost/cpu-ns]))
    (or (seq output-prefix) (seq (str printed)))
    (assoc :seon.cluster.eval/output
           (evaluation-output output-prefix printed))))

(defn- failed-evaluation
  [{admitted :seon.sci.eval/admitted
    output-prefix :seon.sci.eval/output-prefix
    printed :seon.sci.eval/printed
    namespace-name :seon.sci.eval/namespace-name
    ending-namespace :seon.sci.eval/ending-namespace
    print-options :seon.print/options
    record :seon.sci.admit/record
    value :seon.sci.admit/value
    interrupted-at :seon.cluster.eval/interrupted-at
    :as request}]
  (cond-> (cond-> {:seon.print/options print-options
                   :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                   :seon.sci.eval/ending-ns (or ending-namespace namespace-name)
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
  time limit — arrived at its settlement missing `:seon.cluster.eval/ns`,
  `:seon.sci.eval/ending-ns` and `:seon.print/options`. The durable evidence of the interruption
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

(def ^:private raw-none
  "The frame's marker for a form SCI has not returned from."
  (Object.))

(defn- failure-refusal
  "A failure after the frame's form returned, as the error value beside the
  kept value: the adoption check's own refusal (its ex-data), else the
  failure's message and class."
  {:malli/schema [:=> [:cat :seon.error/throwable] :seon.sci.eval/untrusted]}
  [^Throwable throwable]
  (let [data (ex-data throwable)]
    (if (and (map? data) (string? (:seon.error/message data)) (:seon.error/at data))
      data
      (seon.error.refusal/diagnostic
       (java.util.Date.) :seon.sci.eval/program 'seon.sci.eval/failure-refusal
       {:seon.error/message (or (ex-message throwable) (.getName (class throwable)))
        :seon.error/throwable throwable}))))

(defn- kept-evaluation
  "The frame's evaluation of a form whose value SCI returned before a later
  step failed (the loaded program changed while it ran; its evaluation could
  not be classified): the value, its output and cost, and the failure as
  `::untrusted`. The value is never lost; its program facts are not proposed."
  {:malli/schema [:=> [:cat :map] :seon.sci.eval/evaluation]}
  [{value ::value record :seon.sci.admit/record untrusted ::untrusted :as request}]
  (assoc (success-evaluation (assoc request ::admitted {:seon.sci.admit/value value
                                                        :seon.sci.admit/record record}))
         ::untrusted untrusted))

(defn evaluate
  "The evaluation frame of the one REPL path: evaluate the already read
  `::form` once and answer its evaluation. The fold's evaluate stage calls it
  (`seon.turn/evaluate-row`); reading, program acquisition and printing are
  the fold's other stages, never this frame's.
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
  4. evaluate the held form, keeping its value the moment SCI returns;
  5. disarm in `finally`.
  Step 4 answers the declared refusal instead of their value when the
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
                  :seon.sci.eval/evaluation]}
  [{:keys [:seon.sci.admit/caps]
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
                              (get-in base-evaluation-ctx [::custody :seon.db/db])
                              ;; A context derived from a database (`base-ctx`)
                              ;; holds it as its acquired value.
                              (some-> (::kernel/program-snapshot base-evaluation-ctx) deref :seon.db/db))]
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
        ;; The reply's `:ms`: entrance to answer. One clock difference,
        ;; approximate by design.
        elapsed-ms (fn [] (quot (- (System/nanoTime) started-at) 1000000))
        arm-state (volatile! nil)
        record (fn [outcome]
                 (if-let [armed-record (::kernel/record @arm-state)]
                   (armed-record outcome)
                   (kernel/unarmed-record started-at)))
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
        ;; THE EVALUATION FRAME: the already read form (`::form`, the one
        ;; path's live read) is evaluated once: no read, no acquisition (the
        ;; turn's fork holds its program; the batch observes its own writes)
        ;; and no print (the fold's show stage prints). Its value is kept the
        ;; moment SCI returns (`raw`): a later check or construction failure
        ;; is a diagnostic beside it, never its loss.
        raw (volatile! raw-none)
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
                       ;; With a connection (custody, or the world's) an
                       ;; elided read is that connection's CURRENT value, so a
                       ;; form sees its own writes (owner ruling 2026-09-28);
                       ;; only a connectionless evaluation reads its value.
                       #'db/*read-database*
                       (when-not (or connection db/*conn*)
                         (some-> (:seon.db/db turn-environment)
                                 (db/carry-projection-state projection-state)))
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
               (if (not (identical? raw-none @raw))
                 (kept-evaluation {:seon.sci.eval/value @raw
                                   :seon.sci.admit/record (record :ok)
                                   :seon.sci.eval/untrusted (failure-refusal throwable)
                                   :seon.sci.eval/printed printed
                                   :seon.sci.eval/output-prefix output-prefix
                                   :seon.sci.eval/namespace-name namespace-name
                                   :seon.sci.eval/ending-namespace @ending-namespace
                                   :seon.print/options @print-options
                                   :seon.eval/duration-ms (elapsed-ms)})
               (let [record (record (if (kernel/interrupted? throwable)
                                 :time :error))
                arity-message (interpreted-arity-message throwable)
                value (cond->
                          (kernel/failure-value
                           {::kernel/time-limit-kind ::time-limit
                            ::kernel/failure-kind ::evaluation-failed}
                           throwable record)
                        arity-message
                        (assoc :seon.error/message arity-message))
                ;; The agent's failure is its whole flat error, AS DATA (owner
                ;; 2026-09-27: "return data, not comments"); the fold's show
                ;; stage prints it.
                admitted {:seon.sci.admit/value value :seon.sci.admit/record record}]
          (failed-evaluation
           (cond-> {:seon.sci.eval/admitted admitted
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/printed printed
                    :seon.sci.eval/namespace-name namespace-name
                    :seon.sci.eval/ending-namespace @ending-namespace
                    :seon.print/options @print-options
                    :seon.sci.admit/record record
                    :seon.sci.admit/value value}
             ;; the instant the interrupt was OBSERVED — the one
             ;; genuinely new fact a cut evaluation leaves. Its
             ;; presence IS the interrupted state; there is no label.
             (= :time (:seon.eval/outcome record))
             (assoc :seon.cluster.eval/interrupted-at
                    (java.util.Date.)))))))]
       (try
        (kernel/with-arm
       evaluation-ctx time-limit-ms
       (fn [armed]
        (vreset! arm-state armed)
        (try
         (trusted-evaluation
          (:seon.sci.eval/loaded-identity request loaded-identity)
          (fn []
          (when-let [failure (first (:seon.test/acquisition-refusals
                                    (acquired-program evaluation-ctx)))]
            (throw (ex-info "SCI program acquisition refused a declaration." failure)))
          (enter-namespace! evaluation-ctx namespace-name)
          (let [form (::form request)
            execution-ctx evaluation-ctx
            ;; Runs `evaluate!` on this form's ctx inside its REPL frame.
            ;; the session's display budget: the agent's `set!` of
            ;; seon.print/*budget* lasts like its `*print-length*`
            budget-var (let [v (sci/resolve execution-ctx 'seon.print/*budget*)]
                         (when (sci.utils/var? v) v))
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
               (sci/with-bindings (if budget-var {budget-var (get @print-session :seon.print/budget)} {})
                (try
                  ;; Read evidence is what the form read: the request's sink
                  ;; is bound here and around its shown result, never around
                  ;; acquisition, identity checks or install.
                  ;; clojure.test reports through the host `*test-out*`
                  ;; (its `with-test-out`); this form's report is its output.
                  (let [value (binding [db/*read-evidence-sink* form-sink
                                        clojure.test/*test-out* printed
                                        ;; the form's own code counts into its own
                                        ;; sink: what it executed is its code evidence
                                        seon.batch/*world* (if-let [s (:seon.batch/form-sink seon.batch/*world*)]
                                                             (assoc seon.batch/*world* :seon.batch/sink s)
                                                             seon.batch/*world*)]
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
                    value)
                  (finally
                    ;; kept after a throw too: `in-ns` before it stands, as in SCI
                    (vreset! ending-namespace (sci/ns-name @sci/ns))
                    ;; `set!` mutates SCI's current dynamic print binding.
                    ;; Capture it while that binding is still installed;
                    ;; after `sci/binding` unwinds only the host/default face
                    ;; remains and the agent's choice is unrecoverable. The
                    ;; session carrier takes the same ending value, which is
                    ;; what the NEXT form opens with.
                    (let [ending (cond-> {:seon.print/length @sci/print-length
                                          :seon.print/level @sci/print-level}
                                   (and budget-var (pos-int? @budget-var)) (assoc :seon.print/budget @budget-var))]
                      (vreset! print-options ending)
                      (reset! print-session ending)))))))
            eval-form! #(eval-in-form! (fn [ctx] (sci/eval-form ctx form)))
            ;; evaluated once, the value kept the moment SCI returns
            evaluated-value (let [v (eval-form!)] (vreset! raw v) v)
            value evaluated-value
              ;; INSIDE the boundary, BEFORE disarm: an infinite lazy
              ;; sequence dies at the time limit here rather than in the
              ;; receipt writer
              evaluation-record (record :ok)
              ;; the frame does not print: the fold's show stage does
              admitted {:seon.sci.admit/value value :seon.sci.admit/record evaluation-record}]
          (success-evaluation
           {:seon.sci.eval/admitted admitted
            :seon.sci.admit/caps caps
            :seon.sci.eval/printed printed
            :seon.sci.eval/output-prefix output-prefix
            :seon.sci.eval/namespace-name namespace-name
            :seon.sci.eval/ending-namespace @ending-namespace
            :seon.print/options @print-options
            :seon.eval/duration-ms (elapsed-ms)}))))
        (catch Throwable throwable
          (failure-result throwable))
          )))
        (catch Throwable throwable
          (if @arm-state
            (throw throwable)
            (failure-result throwable))))))))))))))

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
  ;; A test body prints to the streams of the thread running it, as a
  ;; compiled test does: SCI's own *out*, *err* and *in* are unbound there.
  (let [out *out* err *err* in *in*]
    (kernel/with-arm
     ctx limit
     (fn [_]
       (sci/binding [sci/out out sci/err err sci/in in]
         (test.runner/run-vars! test-vars request))))))

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

