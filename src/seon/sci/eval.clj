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
  papered over: only the process boundary stops such work. The heap watch
  (`seon.heap`) names it while it runs and, at its critical bound, refuses
  new arms; it neither stops nor cuts work already running.

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
  plus bare `help`, the `my.turn` disposition, and both `my.message`
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
            [clojure.edn :as edn]
            [datahike.api :as d]
            [edamame.core :as edamame]
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
            [seon.schema.internal :as schema.internal]
            [seon.schema.edn :as schema.edn]
            [seon.sci.kernel :as kernel]
            [seon.sci.runtime :as runtime]
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
  (and (map? value) (instance? clojure.lang.IDeref (:env value))))

(defn program-context?
  "True for a program context: a frozen SCI context (`sci.core/freeze`), whose
  environment and kernel state are values. Only its forks evaluate."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (and (map? value) (map? (:env value))))

(defn sci-var?
  "True for an SCI Var, the interned value of an SCI namespace map."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (sci.utils/var? value))

(defn context-state?
  "True for the atom holding one piece of an SCI context's program state."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (instance? clojure.lang.IAtom value))

(def sci-var-generator
  (gen/fmap (fn [_] (sci.utils/new-var 'generated nil)) (gen/return nil)))

(defn projection-state?
  "True for the replacement reference holding one immutable environment."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (env/environment-state? value))

(def projection-state-generator env/environment-state-generator)

(def ctx-generator
  "A real ctx, honest by constructing a fresh one per sample (0.04 ms): no
  Var holds a live context, and no sample's mutation reaches the next."
  (gen/fmap (fn [_] (sci/init {})) (gen/return nil)))

(def program-context-generator
  "A real program context: a frozen fresh ctx."
  (gen/fmap (fn [_] (sci/freeze (sci/init {}))) (gen/return nil)))

(schema.edn/load! {})

(declare loaded-identity carried-cluster-name agent-require acquired-basis context-database loaded-database
         program-revisions acquisition-attributes)

(defn projection-state
  "Create `cluster-name`'s context environment replacement reference; the
  caller names the cluster whose world `db` is. Without a name, the cluster is
  the one that world itself names (`carried-cluster-name`), never an invented one.
  It holds the JVM's loaded-program place (`loaded-identity`) by reference and,
  when the caller names it, the program `commit-id` the cluster booted: the
  projection builder reads both from here (`seon.db/loaded-builder`)."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.schema/projection] :seon.sci.eval/projection-state]
    [:=> [:cat :seon.db/database-value :seon.schema/projection :seon.boot/cluster-name]
     :seon.sci.eval/projection-state]
    [:=> [:cat :seon.db/database-value :seon.schema/projection :seon.boot/cluster-name
          [:maybe :seon.source/commit-id]]
     :seon.sci.eval/projection-state]]}
  ([db projection] (projection-state db projection (carried-cluster-name nil db)))
  ([db projection cluster-name] (projection-state db projection cluster-name nil))
  ([db projection cluster-name commit-id]
   (env/environment-state
    (env/refuse-incomplete-environment!
     (env/environment (cond-> {:seon.boot/cluster-name cluster-name
                               :seon.db/basis-t (db/basis-t db)
                               :seon.schema/projection projection
                               :seon.sci.eval/loaded-identity loaded-identity}
                        commit-id (assoc :seon.source/commit-id commit-id)))))))

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
  "The process Var space's SCI Var for a loaded JVM Var (`seon.sci.runtime/space-var`),
  its root and metadata the host's.

  One SCI Var per name for the whole process, so an analysis holding it serves
  every context. Its own root and metadata follow the host Var: a reload's new
  root or a new host Var at the name is written into the shared Var, which
  every context without its own binding of it reads. SCI's `copy-var*` copies
  `@host-var` (`reference-code/sci/src/sci/core.cljc:137`), which for a dynamic
  Var bound on this thread is the binding, so the root is the raw root: a base
  derived under an evaluation keeps no evaluation's `seon.db/*read-database*`.
  `copy-var*` copies only a few metadata keys; the rest (its contract,
  `:malli/schema`) is the Var's own data a reader expects, as the compiled Var
  answers. `:test` stays behind: a copied test Var would run its compiled body
  in this context."
  {:malli/schema [:=> [:cat [:fn clojure.core/var?] [:fn clojure.core/some?]] [:fn clojure.core/some?]]}
  [^clojure.lang.Var host-var sci-namespace]
  (let [sym (symbol (str (ns-name (.ns host-var))) (str (.sym host-var)))
        root (.getRawRoot host-var)
        ;; The shared Var's own root and metadata, which every context's binding
        ;; of it starts from; under an active context `bindRoot` and `alter-meta!`
        ;; write only that context's binding, and the root write would reach the
        ;; evaluation's write observer as a definition. A new root is a new
        ;; definition (a reload), so its metadata is taken again with it.
        follow! (fn [^sci.lang.Var copied]
                  (sci.ctx-store/with-ctx nil
                    (.bindRoot copied root)
                    (alter-meta! copied #(cond-> (merge % (dissoc (meta host-var) :ns :name :test))
                                           (:dynamic (meta host-var)) (assoc :sci.impl/host-var host-var))))
                  copied)
        copied (runtime/space-var sym #(follow! (sci/copy-var* host-var sci-namespace)))]
    (when-not (identical? root (sci.ctx-store/with-ctx nil (.getRawRoot ^sci.lang.Var copied)))
      (follow! copied))
    copied))


(defn- context-namespace
  "The namespace object `ctx` holds for `namespace-name` (SCI keeps one under
  `[:namespaces name :obj]`, which `the-ns`, `in-ns` and a namespace's metadata
  are), else a fresh one for a namespace `ctx` does not hold yet. A fresh object
  per binding would answer `*ns*` apart from `the-ns`, so `use-fixtures` would
  alter metadata no Var's `:ns` carries."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :symbol] [:fn sci.utils/namespace?]]}
  [ctx namespace-name]
  (or (sci/find-ns ctx namespace-name) (sci/create-ns namespace-name)))

(defn- load-host-namespace
  "SCI's `:load-fn` (`reference-code/sci/src/sci/impl/load.cljc:204-243`): a
  namespace the JVM already loaded, required and not yet in the context, is
  exposed as itself, each Var it interns copied at its root, so an agent requires
  `malli.core` or `datahike.api` as a Clojure REPL does, and `var` reaches a
  private one as Clojure's does. Program namespaces are
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
    (let [sci-namespace (context-namespace ctx namespace-name)]
      (sci/add-namespace!
       ctx namespace-name
       (into {:obj sci-namespace}
             (map (fn [[intern-name host-var]]
                    [intern-name (copy-host-var host-var sci-namespace)]))
             (ns-interns host)))
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
        ;; Forked at birth, so the context has its own generation: the
        ;; process Var space's Vars (`copy-host-var`) are born in none, and an
        ;; unforked context would own them and write their shared roots.
        ctx
        (sci/fork
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
         (assoc-in [:namespaces 'clojure.core 'require] agent-require))))
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

(declare arm-contract! install-function-contract! interpretation-error)

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
     ::requires requires
     ;; the named fixtures `use-fixtures` keeps in the namespace table
     ;; (`seon.sci.lookup/agent-record-fixtures`)
     ::fixtures (get-in @(:env ctx) [:namespaces namespace-name :seon.ns/fixtures] {})}))

(defn- one-event
  [source namespace-name ctx max-source]
  (let [events (sci/binding [sci/ns (context-namespace ctx namespace-name)]
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
    requires ::requires
    fixtures ::fixtures}]
  {:seon.ns/fixtures (seon.fn/fixture-components fixtures)
   :seon.ns/requires
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
                                      ::imports ::requires ::fixtures])
                  (select-keys after [:seon.sci.reader/aliases
                                     :seon.sci.reader/refers
                                     ::imports ::requires ::fixtures])))
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

(defn- arm-contract!
  "Arm `contract`, a function contract form, on `function-symbol`'s Var in
  `ctx`, its named predicates resolved as `ctx` resolves them."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol :seon.schema/value
                       [:or :nil :seon.program/definition-digest] :map
                       :seon.db/database-value]
                  :nil]}
  [ctx function-symbol contract definition-digest projection db]
  (let [sci-var (sci/resolve ctx function-symbol)
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
            (throw (ex-info (:seon.error/message current) current)))
        projection (kernel/context-bound-projection ctx projection contract)]
    (sci/bind-root!
     ctx sci-var
     (instrument/wrap-interpreted
      function-symbol contract projection caps (sci.ctx-store/with-ctx ctx @sci-var)
      (cond-> (assoc (select-keys @(::kernel/program-snapshot ctx)
                                  [:seon.flow/commit-fault!])
                     :seon.call-preparation/snapshot current)
        max-evidence-bytes
        (assoc :seon.config.error/max-evidence-bytes max-evidence-bytes)
        ;; The profiling cell's identity is the row it installs.
        definition-digest
        (assoc :seon.program/definition-digest definition-digest)))))
  nil)

(defn- install-function-contract!
  "Arm the stored contract of `committed`, a program row, on its Var in `ctx`."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :map :map :seon.db/database-value] :nil]}
  [ctx committed projection db]
  (when-let [spec-edn (:seon.fn/spec committed)]
    (arm-contract! ctx (:seon.fn/sym committed) (edn/read-string spec-edn)
                   (:seon.program/definition-digest committed) projection db))
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
                          {:ns (context-namespace ctx (symbol (namespace target)))})))
    ctx))

(declare install-declared-classes! install-jvm-root! overridden-row? source-namespace? enter-namespace!)

(defn- install-function-from-database!
  "Install one selected function from the acquired database snapshot. A definition
  the loaded program holds unchanged binds its compiled Var, exactly as acquisition
  binds a loaded namespace's, unless this context already defined the symbol itself;
  only an overridden or affected row interprets source."
  [ctx db function-symbol]
  (let [{source ::function-source
         namespace-name ::function-namespace}
        (kernel/program-function ctx function-symbol)
        {interpreted ::interpreted} @(::kernel/program-snapshot ctx)
        loaded (loaded-database ctx db)]
    ;; A source namespace's definition is read in its namespace as loading
    ;; it reads it: entered first, its sibling definitions are bound.
    (when (and source (source-namespace? ctx namespace-name))
      (enter-namespace! ctx namespace-name db))
    (when-not source
      (throw
       (ex-info "Selected function is missing from the acquired SCI program snapshot."
                (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.eval/program 'seon.sci.eval/install-function-from-database!
                 {:seon.error/message "Selected function has no acquired row; acquire its declaration before installing it."
                  :seon.error/offending function-symbol
                  ::missing-function-row function-symbol
                  :seon.fn/sym function-symbol
                  :seon.error/expected :seon.sci.eval/acquired-function}))))
    ;; The evaluating context's own definition of an uninstalled program
    ;; symbol is its value and survives the symbol's first install
    ;; (AGENTS.md); recorded so `acquire!` still carries it. An interpreted
    ;; symbol's Var holds the overlay's own root until it installs.
    (when-not (and (not (contains? interpreted function-symbol))
                   (or (when (some->> (sci/resolve ctx function-symbol) (kernel/own-definition? ctx))
                         (swap! (::kernel/program-snapshot ctx) update ::context-defined (fnil conj #{}) function-symbol))
                       (and (not (source-namespace? ctx namespace-name))
                            (not (overridden-row? loaded db (db/pull db '[*] [:seon.fn/sym function-symbol])))
                            (install-jvm-root! ctx function-symbol))))
    (let [namespace-row (kernel/program-namespace ctx namespace-name)]
      (install-declared-classes! ctx [namespace-row])
      ;; Only a namespace the context does not hold yet takes its bindings from
      ;; the snapshot: loading a definition never touches the aliases of a
      ;; namespace already there, as in Clojure (a REPL require stays).
      (when-not (contains? (:namespaces @(:env ctx)) namespace-name)
        (sci/install-namespace-bindings!
         ctx namespace-name (assoc (row-bindings namespace-row) :refers {}))
        (install-namespace-bindings! ctx namespace-name (row-bindings namespace-row)))
      (let [row (db/pull db '[*] [:seon.fn/sym function-symbol])
            form (:seon.sci.reader/form (one-event source namespace-name ctx (count source)))]
        (sci/binding [sci/ns (context-namespace ctx namespace-name)]
          (sci/eval-form ctx form))
        (install-function-contract! ctx row (context-projection ctx) db))))
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
  "Bind `function-symbol` in `ctx` to the loaded program's definition: its
  analysed one (`::analysed-roots`) where the acquisition analysed it, else
  the compiled root. The Var is the process space's, so a definition `ctx`
  bound before is replaced, never kept."
  [ctx function-symbol]
  (when-let [host-namespace (find-ns (symbol (namespace function-symbol)))]
    (when-let [host-var (ns-resolve host-namespace (symbol (name function-symbol)))]
      (let [sci-namespace (context-namespace ctx (symbol (namespace function-symbol)))
            sci-var (copy-host-var host-var sci-namespace)
            root (get (::analysed-roots @(::kernel/program-snapshot ctx)) function-symbol (.getRawRoot ^clojure.lang.Var host-var))]
        (sci/add-namespace! ctx (symbol (namespace function-symbol)) {(symbol (name function-symbol)) sci-var})
        (sci.ctx-store/with-ctx ctx
          (when-not (identical? root @sci-var)
            (sci/bind-root! ctx sci-var root)))
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
                                                 {:ns (context-namespace ctx namespace-name)})}))
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

(defn- context-database
  "Read the context's connection at use, including an explicitly bound evaluation."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] [:maybe :seon.db/database-value]]}
  [ctx]
  (when-let [connection (or (:seon.db/connection (::custody ctx))
                            (:seon.db/connection (env/of ctx))
                            db/*conn*)]
    (db/db connection)))

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

(defn loaded-database
  "Open the acquired loaded commit from the context's current connection."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value] :seon.db/database-value]}
  [ctx database]
  (let [snapshot @(::kernel/program-snapshot ctx)
        source (loaded-source snapshot database)
        commit (::loaded-commit-id snapshot)]
    (if commit
      (if (= commit (db/commit-id source))
        source
        (d/commit-as-db source commit {:secondary-indices? false}))
      (loaded-program source))))

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

(defn- function-symbols
  "The symbols of the functions `database` holds, from one attribute range."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:set :qualified-symbol]]}
  [database]
  (let [symbols (db/datoms database :aevt :seon.fn/sym)]
    (when (db/read-refusal? symbols)
      (throw (ex-info "Function symbols could not be read." symbols)))
    (into #{} (map :v) symbols)))

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

(defn- behavior-changed?
  "Whether function `sym` of `database` behaves otherwise than `loaded`'s: the
  loaded program lacks it or holds it at another behavior digest
  (`seon.db/behavior-digest`, the digest `seon.test/changes` selects by). A
  docstring-only change is none: it keeps the compiled Var."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :qualified-symbol] :boolean]}
  [database loaded sym]
  (let [behavior (fn [value]
                   (let [datoms (db/datoms value :avet :seon.fn/sym sym)]
                     (when (db/read-refusal? datoms)
                       (throw (ex-info (:seon.error/message datoms) datoms)))
                     (some->> datoms first :e (db/behavior-digest value))))
        held (behavior loaded)]
    (or (nil? held) (not= held (behavior database)))))

(defn- overridden-row?
  "Whether `row`, as `database` holds it, is this program's own change to the
  loaded program: a row an agent authored (`:seon.schema.admission/source
  :agent`) that the loaded program lacks or holds at another digest and
  behavior (`behavior-changed?`), before any plan names it. A file-derived
  row that differs is in the plan's interpret set (`derive-overlay-plan`)
  when SCI can interpret it; a host-bound one runs as loaded."
  {:malli/schema [:=> [:cat [:maybe :seon.db/database-value] :seon.db/database-value :map] :boolean]}
  [loaded database row]
  (and (= :agent (:seon.schema.admission/source row))
       (let [sym (:seon.fn/sym row)
             previous (when loaded (db/pull loaded '[*] [:seon.fn/sym sym]))]
         (or (nil? (:seon.fn/sym previous))
             (and (not= (row-definition-digest row) (row-definition-digest previous))
                  (behavior-changed? database loaded sym))))))

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

(defn- committed-function
  "The program snapshot's entry of one committed function row."
  {:malli/schema [:=> [:cat [:map [:seon.fn/sym :qualified-symbol]]] :map]}
  [committed]
  (let [admission (:seon.schema.admission/source committed)]
    {::function-source (:seon.fn/source committed)
     ::function-namespace (symbol (namespace (:seon.fn/sym committed)))
     ::function-private? (:seon.fn/private? committed)
     ::function-admission admission
     ::agent-authored? (= :agent admission)}))

(defn install-row!
  "Install one declaration from the terminal transaction's db-after.
  The exact committed row is resolved by identity; saved evaluation results
  are never consulted."
  {:malli/schema [:=> [:cat :seon.sci.eval/install-request]
                  [:or :seon.sci.eval/install-result :seon.sci.eval/interpretation-error]]}
  [{ctx :seon.sci.eval/ctx
    db :seon.db/db
    row :seon.program/row
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
        (kernel/cache-function! ctx function-symbol (committed-function committed))
        (let [snapshot @(::kernel/program-snapshot ctx)
              loaded (delay (loaded-database ctx db))
              changed? (and (= :agent admission) (overridden-row? @loaded db committed))
              interpreted? (or (get (::interpreted snapshot) function-symbol) changed?)
              host-row (when interpreted?
                         (if (host-bound-row? committed)
                           committed
                           (let [previous (db/pull @loaded '[*] [:seon.fn/sym function-symbol])]
                             (when (and previous (host-bound-row? previous)) previous))))
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
                refusal (install-refusing-root! ctx function-symbol refusal)
                :else
                  (if (or (not interpreted?) compiled?)
                    (if (install-jvm-root! ctx function-symbol) :jvm :unavailable)
                    (try
                      (install-function-from-database! ctx db function-symbol)
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
              failed (when (and (map? result) (not refusal) (= :core admission))
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
            event (one-event (:seon.test/source committed)
                             namespace-name ctx
                             (count (:seon.test/source committed)))]
        (sci/binding [sci/ns (context-namespace ctx namespace-name)]
          (sci/eval-form ctx (:seon.sci.reader/form event)))
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
            (sci/binding [sci/ns (context-namespace ctx namespace-name)]
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

(defn evaluate-native!
  "Execute an already-admitted `form` in its supplied SCI context `ctx`; answers its value."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.schema/value] :seon.schema/value]}
  [ctx form]
  (kernel/with-world-ctx ctx
    #(sci/eval-form ctx form)))

(declare program-key-input acquired-database? stale-reach reinterpret-reach! interpret-affected!
         namespace-row install-caller-host-bindings! install-required-libraries! acquisition-refusal)

(defn acquired-program
  "Return the basis and refusal report actually acquired by this context.

  Evidence, including :seon.sci.eval/interpreted-count, follows the existing
  program snapshot through reacquisition and forks. A resolvable Var alone is not evidence of an acquired program."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :seon.test/acquisition]}
  [ctx]
  (let [snapshot (some-> (::kernel/program-snapshot ctx) deref)]
    (assoc (merge (::acquired-basis snapshot)
                  (select-keys snapshot [:seon.test/class-loader]))
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

(defn- settled-on-acquired-basis?
  "Whether the settlement `report` wrote on the very value `ctx` acquired:
  its `:db-before` is `written-on` (one branch, one basis), `ctx` acquired
  `written-on`'s program (`acquired-database?`) and its `:db-after` ends at its
  own transaction (Datahike's writer hands every report of a batch the batch's
  final value). Then the settled program is the one `ctx` evaluated."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :seon.db/transaction-report] :boolean]}
  [ctx written-on report]
  (boolean
   (and (= (db/basis-t written-on) (db/basis-t (:db-before report)))
        (= (get-in written-on [:config :branch]) (get-in report [:db-before :config :branch]))
        (= (db/basis-t (:db-after report)) (:tx (first (:tx-data report))))
        (acquired-database? ctx written-on))))

(defn- record-settled-rows!
  "Record in the evaluating context `ctx` the rows its own evaluations
  committed: their entries in its program snapshot, so its next regeneration
  reads them unchanged rather than another writer's, and, when the settlement
  wrote on the value it acquired (`settled-on-acquired-basis?`), the settled
  commit's basis and program key, so its next request forks nothing. Its
  roots are already the ones it evaluated."
  [ctx db installations report written-on]
  (let [committed (into [] (keep #(some->> (:seon.fn/sym (:seon.program/row %))
                                           (vector :seon.fn/sym)
                                           (db/pull db '[*])))
                        installations)]
    (swap! (::kernel/program-snapshot ctx) update :functions
           #(into (if (seq %) % (sorted-map)) (map (juxt :seon.fn/sym committed-function)) committed))
    (when (and report written-on (settled-on-acquired-basis? ctx written-on report))
      (let [input (program-key-input db ctx)]
        (swap! (::kernel/program-snapshot ctx)
               #(cond-> (assoc % ::acquired-basis (acquired-basis db)) input (assoc ::program-key input)))))
    []))

(defn install-evaluated-rows!
  "Record committed rows in the context that evaluated them.

  The evaluating context (`agent-ctx`, else `ctx` itself) holds every root its
  forms bound, so settlement only records the rows in it
  (`record-settled-rows!`): nothing is evaluated or copied again. `ctx`, an
  execution's base, is never written when an `agent-ctx` evaluated: it stays
  the program context of the content it acquired and takes the settled
  program by content at its next `acquire!`."
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
  (record-settled-rows! (or agent-ctx ctx) db installations report written-on))

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
      (let [sci-namespace (context-namespace ctx namespace-name)
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
  (let [sci-namespace (context-namespace ctx namespace-name)]
    (sci/add-namespace!
     ctx namespace-name
     (into {}
           (map (fn [[local-name host-var]]
                  [local-name (copy-host-var host-var sci-namespace)]))
           intern-map))))

(defn- install-host-core!
  "Join the clojure.core names SCI does not carry (`spit`, `ref`) to `ctx`'s
  core, `sci-core`, once; SCI's interrupt-aware core and its analyzer's own
  names are never replaced."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :map] :nil]}
  [ctx sci-core]
  (install-host-namespace! ctx 'clojure.core
                           (remove (fn [[local-name]] (or (contains? sci-core local-name)
                                                          (contains? sci.utils/ana-macros local-name)))
                                   (ns-publics 'clojure.core)))
  nil)

(defn- install-caller-host-bindings!
  "Bind the compiled Vars an interpreted caller's source names but no program row installs.

  An interpreted declaration's namespace receives its loaded JVM namespace's
  remaining interns (private helpers, `def`/`defonce` Vars); each alias and
  refer target its namespace row declares receives that loaded namespace's
  interns, so `var` reaches a private one as Clojure's does. Existing SCI bindings win: first-party copies, interpreted
  definitions and SCI's own `clojure.*` namespaces are never replaced. The
  core names SCI lacks (`ref`, `agent`) join the context's `clojure.core`,
  which every namespace refers as Clojure's does, so `ns-interns` answers
  only a namespace's own Vars and `:refer-clojure :exclude` holds; the names
  SCI's analyzer owns (`sci.impl.utils/ana-macros`) stay SCI's. A target this
  JVM has not loaded stays absent, so its caller refuses by name."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:sequential :map]] :nil]}
  [ctx namespace-rows]
  (let [sci-core (get-in @(:env ctx) [:namespaces 'clojure.core])
        ;; A source namespace interprets its interns from its rows.
        own-bindings
        (fn [namespace-name]
          (when-not (source-namespace? ctx namespace-name)
            (some-> (find-ns namespace-name) ns-interns)))
        own (set (map :seon.ns/name namespace-rows))
        test-namespaces (::test-namespaces @(::kernel/program-snapshot ctx))
        targets (distinct
                 (mapcat (fn [row]
                           (let [{:keys [aliases refers]} (row-bindings row)]
                             (cons (:seon.ns/name row)
                                   (remove #(or (= 'clojure.core %) (contains? test-namespaces %))
                                           (concat (vals aliases)
                                                   (map (comp symbol namespace) (vals refers)))))))
                         namespace-rows))]
    (install-host-core! ctx sci-core)
    (doseq [target targets
            :let [interns (if (own target) (own-bindings target) (some-> (find-ns target) ns-interns))
                  present (get-in @(:env ctx) [:namespaces target])]
            :when (seq interns)]
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

(declare install-selected! record-acquisition-refusals! bind-lazy-members! cluster-base-key record-refusals-once!)

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
  "Acquire source namespaces with lazy function roots and eager macros, values
  and tests in binding order; return the acquisition with its refusals."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value [:set :symbol]] :map]}
  [ctx database namespace-names]
  (let [snapshot @(::kernel/program-snapshot ctx)
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
        eager (set (bind-lazy-members! ctx database (map first function-rows)))
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
                   (filterv (comp eager first) function-rows) (vec test-rows) eager
                   ;; entered while its base acquires, a context holds no acquisition yet: none installed
                   (-> (::acquisition snapshot {:seon.sci.eval/installed 0})
                       (dissoc ::acquisition-refusals-recorded? ::acquisition-recording-error)
                       (assoc :seon.schema/projection (context-projection ctx))))
                  (:seon.flow/commit-fault! snapshot))]
    (swap! (::kernel/program-snapshot ctx) assoc ::acquisition acquired)
    acquired))

(defn- own-classes
  "The classes the loaded JVM namespace `namespace-name` defines itself
  (`deftype`, `defrecord`), by the names it imports them under: a `deftype`
  imports its class into its namespace as it loads, with no `:import` in the
  `ns` form, so the namespace's row never declares them."
  {:malli/schema [:=> [:cat :symbol] [:map-of :symbol [:fn class?]]]}
  [namespace-name]
  (let [package (namespace-munge namespace-name)]
    (into {} (filter (fn [[_ ^Class class]] (= package (.getPackageName class))))
          (some-> (find-ns namespace-name) ns-imports))))

(defn own-class-imports
  "The imports the loaded JVM namespace `namespace-name` gains from the
  classes it defines itself (`own-classes`), short name to class name: never
  a declaration of its row, which only its `ns` form declares."
  {:malli/schema [:=> [:cat :symbol] [:map-of :symbol :symbol]]}
  [namespace-name]
  (update-vals (own-classes namespace-name) #(symbol (.getName ^Class %))))

(defn- loaded-bindings
  "`row`'s bindings (`row-bindings`) as its loaded JVM namespace resolves
  them: with the classes the namespace defines itself imported
  (`own-class-imports`), so entering it again leaves its resolver state as it
  was."
  {:malli/schema [:=> [:cat :map] :map]}
  [row]
  (update (row-bindings row) :imports merge (own-class-imports (:seon.ns/name row))))

(defn- enter-namespace*
  "The namespace entry of `enter-namespace!`, with `read-database` called once only when a source namespace interprets."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :symbol [:=> [:cat] :seon.db/database-value]] :seon.sci.eval/ctx]}
  [ctx namespace-name read-database]
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
            (install-namespace-bindings! ctx (:seon.ns/name row) (loaded-bindings row)))
          (when (seq sources)
            (interpret-source-namespaces! ctx (read-database) sources)))))
    ctx))

(defn enter-namespace!
  "`ctx` with the program namespace a form runs in, and each it aliases, bound as
  its file declares it, once per context: its loaded JVM namespace's interns,
  private ones included, its refers and the public Vars of what it aliases
  (`install-caller-host-bindings!`). A source namespace (`source-namespace?`:
  a test namespace, or one this JVM has not loaded) and the source namespaces
  it binds are interpreted from their rows instead
  (`interpret-source-namespaces!`); nothing is loaded into the shared JVM. A
  namespace without a program row is left as SCI holds it. The sources read
  `database` when given: an acquisition interprets from the value it acquires,
  else the context's connection at use (`context-database`)."
  {:malli/schema [:function
                  [:=> [:cat :seon.sci.eval/ctx :symbol] :seon.sci.eval/ctx]
                  [:=> [:cat :seon.sci.eval/ctx :symbol :seon.db/database-value] :seon.sci.eval/ctx]]}
  ([ctx namespace-name] (enter-namespace* ctx namespace-name #(context-database ctx)))
  ([ctx namespace-name database] (enter-namespace* ctx namespace-name (constantly database))))

(defn auto-require!
  "Require in `namespace-name` of `ctx` each namespace a definition names fully
  qualified that the namespace neither is, aliases nor requires, and that
  resolves (`ctx` holds it or the platform loaded it): SCI resolves any such
  namespace, the file's `ns` form only what it requires, so the namespace's
  resolver state, and with it the merged `ns` form, holds what its definitions
  use. A quoted form is data, never a use: `'my.program/source` requires nothing.
  Answers the namespaces required."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :symbol :seon.batch/form] [:set :symbol]]}
  [ctx namespace-name form]
  (let [{:keys [aliases requires]} (sci/namespace-bindings ctx namespace-name)
        named (into (sorted-set)
                    (comp (filter qualified-symbol?)
                          (map (comp symbol namespace))
                          (remove (into #{namespace-name 'clojure.core} requires))
                          (remove #(contains? aliases %))
                          (filter #(or (sci/find-ns ctx %) (find-ns %))))
                    (tree-seq #(and (coll? %) (not (and (seq? %) (= 'quote (first %))))) seq form))]
    (when (seq named)
      (sci/binding [sci/ns (sci/find-ns ctx namespace-name)]
        (sci/eval-form ctx (apply list 'clojure.core/require (map #(list 'quote %) named)))))
    named))

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
  "A flat agent-mistake value naming one row that could not be installed: the
  row, the cause's message, its location in the row's source and its flags, by
  reference; the cause's stack and data are never copied into it."
  {:malli/schema [:=> [:cat :map [:or :map :seon.error/throwable]]
                  [:or :seon.sci.eval/row-acquisition-error
                   :seon.sci.eval/interpretation-error]]}
  [row failure]
  (let [identity (program-row-identity row)
        failure-data (if (map? failure) failure (error/refusal failure))
        cause-message (or (:seon.error/message failure-data)
                          (when (instance? Throwable failure) (ex-message failure))
                          (.getName (class failure)))
        location (cond-> {}
                   (:line failure-data) (assoc :clojure.error/line (:line failure-data))
                   (:column failure-data) (assoc :clojure.error/column (:column failure-data))
                   (:file failure-data) (assoc :clojure.error/source (:file failure-data)))
        ;; the cause's flags (`:type`, `:seon.fn/namespace-unresolvable`):
        ;; its strings and collections are the message, the location and
        ;; copies of what the row and the program already name
        classifying (into {} (filter (fn [[_ v]] (or (keyword? v) (symbol? v) (number? v) (boolean? v))))
                          (dissoc failure-data :line :column))]
    (if (:seon.sci.eval/refused-function failure-data)
      failure-data
      (seon.error.refusal/diagnostic (java.util.Date.) ::acquisition 'seon.sci.eval/acquisition-refusal
        {(if (= :seon.schema/key (first identity)) :seon.sci.eval/row-schema-key :seon.sci.eval/row-symbol)
         (second identity)
      :seon.sci.eval/acquisition-observation {:seon.error.evidence/attribute :seon.error/message
       :seon.error.evidence/value cause-message}
      :seon.error/message "Program row could not be installed; correct the reported acquisition evidence."
      :seon.error/data (cond-> {::acquisition-row identity
               ::acquisition-cause-message cause-message}
        (seq classifying) (assoc ::acquisition-failure classifying)
        (seq location) (assoc :seon.sci.eval/cause-location location)
        (instance? Throwable failure)
        (assoc ::acquisition-throwable-class (.getName (class failure))))
      :seon.error/expected ::installed}))))

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
                    {:seon.error/source (dissoc refusal ::produced-in)
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
              outcome (db/with-branch {:seon.db/connection connection}
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
  "Function declarations as `[sym source namespace admission private?]`."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:vector [:tuple :qualified-symbol :string :symbol :keyword :boolean]]]}
  [db]
  (let [[symbols & ranges :as reads]
        (mapv #(db/datoms db :aevt %)
              [:seon.fn/sym :seon.fn/source :seon.schema.admission/source :seon.fn/private?])]
    (doseq [result reads]
      (when (db/read-refusal? result)
        (throw (ex-info (:seon.error/message result) result))))
    (let [[sources admissions privates] (mapv #(into {} (map (juxt :e :v)) %) ranges)]
      (into []
            (keep (fn [{entity :e sym :v}]
                    (when-let [admission (get admissions entity)]
                      [sym (get sources entity "") (symbol (namespace sym))
                       admission (get privates entity false)])))
            symbols))))

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

(defn- install-fixtures!
  "Restore on `ctx` the fixtures `namespace-name`'s entity in `db` names
  (`:seon.ns/fixtures`), through the context's own `use-fixtures`, as loading
  its file would; nil."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :symbol] :nil]}
  [ctx db namespace-name]
  (let [{fixtures :seon.ns/fixtures} (db/pull db [{:seon.ns/fixtures [:seon.ns.fixture/runs :seon.ns.fixture/position
                                                                      :seon.ns.fixture/function]}]
                                              [:seon.ns/name namespace-name])]
    (doseq [[runs held] (group-by :seon.ns.fixture/runs fixtures)]
      (sci/binding [sci/ns (context-namespace ctx namespace-name)]
        (sci/eval-form ctx (list* 'clojure.test/use-fixtures runs
                                  (map :seon.ns.fixture/function (sort-by :seon.ns.fixture/position held)))))))
  nil)

(defn- definition-order
  "The rows of one namespace's `entries` in the order loading it evaluates them,
  so a def reading another def finds it bound: those with a file position in
  file order, then those defined at a REPL, which carry none, each after the
  REPL rows its `:seon.fn/calls` name, ties by name."
  {:malli/schema [:=> [:cat [:sequential [:map [:seon.program/row :map]]]] [:vector :map]]}
  [entries]
  (let [{placed true repl false} (group-by (comp some? ::position) entries)
        name-of (comp :seon.fn/sym :seon.program/row)
        by-sym (into {} (map (juxt name-of identity)) repl)
        visit (fn visit [[out seen] sym]
                (if (seen sym)
                  [out seen]
                  (let [[out seen] (reduce visit [out (conj seen sym)]
                                           (sort-by str (filter by-sym (::calls (by-sym sym)))))]
                    [(conj out (by-sym sym)) seen])))]
    (mapv :seon.program/row
          (concat (sort-by (juxt ::position (comp str name-of)) placed)
                  (first (reduce visit [[] #{}] (sort-by str (keys by-sym))))))))

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
          produced (::produced-in state)
          stamp #(cond-> % produced (assoc ::produced-in produced))
          state (cond-> state cycle (update ::agent-mistakes (fnil into []) (map stamp) (cycle-refusals cycle)))
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
                          (stamp (acquisition-refusal row installed)))
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
                        (stamp (acquisition-refusal row failure))))))]
      ;; Interpreted callers and tests analyze against every other name their
      ;; file namespace reaches; only this branch's rows become SCI definitions.
      (doseq [namespace-name (sort-by str (distinct (concat (keys function-rows-by-ns) (keys test-rows-by-ns))))]
        (enter-namespace! ctx namespace-name db))
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
                 (sci/binding [sci/ns (context-namespace ctx namespace-name)]
                   (sci/eval-form ctx (list* 'declare undeclared))))
               (reduce
                install-row
                (cond-> state
                  (::agent-authored?
                   (get namespace-row-by-name namespace-name))
                  (update :seon.sci.eval/installed inc))
                (definition-order
                 (map (fn [[sym source _ admission private?]]
                        (let [{:seon.fn/keys [arglists form-span calls]}
                              (db/pull db [:seon.fn/arglists :seon.fn/form-span :seon.fn/calls] [:seon.fn/sym sym])]
                          {::position (first form-span)
                           ::calls calls
                           :seon.program/row {:seon.fn/sym sym
                                              :seon.schema.admission/source admission
                                              :seon.fn/source source
                                              :seon.fn/ns [:seon.ns/name namespace-name]
                                              :seon.fn/private? private?
                                              :seon.fn/arglists arglists
                                              ::skip-contract-install? (not= :agent admission)}}))
                      (get function-rows-by-ns namespace-name)))))
             state
             order)]
        ;; Tests resolve only after every namespace's functions and exact
        ;; bindings are present. This makes renamed `deftest` deterministic.
        (let [installed
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
         order)]
        ;; Fixtures name functions and run around tests: they install last.
        (doseq [namespace-name order]
          (install-fixtures! ctx db namespace-name))
        (dissoc installed ::produced-in))))))

(defn- namespace-row
  "A pulled namespace row carrying its source, admission and authorship."
  {:malli/schema [:=> [:cat :map] :map]}
  [{source :seon.ns/source admission :seon.schema.admission/source :as row}]
  (assoc row
         ::namespace-source source
         ::namespace-admission (when source admission)
         ::agent-authored? (boolean (and source (= :agent admission)))))

(defn- base-namespace-rows
  "Namespace declarations and bindings read as attribute joins."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :symbol]
                       [:set [:tuple :symbol :string :keyword]]]
                  [:map-of :symbol :map]]}
  [database names assertions]
  (let [rows (into {} (map (fn [name] [name (namespace-row {:seon.ns/name name})])) names)
        rows (reduce (fn [rows [name source admission]]
                       (assoc rows name (namespace-row {:seon.ns/name name :seon.ns/source source
                                                        :seon.schema.admission/source admission})))
                     rows assertions)]
    (reduce
     (fn [rows [attribute fields query]]
       (let [bindings (db/q query database)]
         (when (db/read-refusal? bindings)
           (throw (ex-info (:seon.error/message bindings) bindings)))
         (reduce (fn [rows [name & values]]
                   (update-in rows [name attribute] (fnil conj #{})
                              (if fields (zipmap fields values) (first values))))
                 rows bindings)))
     rows
     [[:seon.ns/requires nil
       '[:find ?name ?required :where [?ns :seon.ns/requires ?required] [?ns :seon.ns/name ?name]]]
      [:seon.ns/aliases [:seon.ns.alias/local :seon.ns.alias/target-ns]
       '[:find ?name ?local ?target :where [?binding :seon.ns.alias/local ?local]
         [?binding :seon.ns.alias/target-ns ?target] [?ns :seon.ns/aliases ?binding]
         [?ns :seon.ns/name ?name]]]
      [:seon.ns/imports [:seon.ns.import/local :seon.ns.import/target-class]
       '[:find ?name ?local ?target :where [?binding :seon.ns.import/local ?local]
         [?binding :seon.ns.import/target-class ?target] [?ns :seon.ns/imports ?binding]
         [?ns :seon.ns/name ?name]]]
      [:seon.ns/refers [:seon.ns.refer/local :seon.ns.refer/target-ns :seon.ns.refer/target-name]
       '[:find ?name ?local ?target-ns ?target-name :where [?binding :seon.ns.refer/local ?local]
         [?binding :seon.ns.refer/target-ns ?target-ns] [?binding :seon.ns.refer/target-name ?target-name]
         [?ns :seon.ns/refers ?binding] [?ns :seon.ns/name ?name]]]])))

(defn- armed-as-compiled
  "`analysed`, the analysed definition of `sym`, armed with the contract and
  policy its loaded JVM Var's root `compiled` was armed with (`seon.instrument`
  keeps them on the wrapper), against the projection that policy's snapshot
  holds, else `projection`; `analysed` itself when the compiled root is unarmed."
  {:malli/schema [:=> [:cat :qualified-symbol [:fn clojure.core/ifn?] [:or :nil :seon.program/definition-digest]
                       :seon.schema/projection :seon.instrument/callable]
                  :seon.instrument/callable]}
  [sym compiled digest projection analysed]
  (let [{policy :seon.instrument/policy contract :seon.instrument/contract} (meta compiled)
        snapshot (:seon.call-preparation/snapshot policy)]
    (if (and policy contract)
      (instrument/wrap-interpreted
       sym contract (or (:seon.schema/projection snapshot) projection) (:seon.sci.admit/caps policy) analysed
       (cond-> (select-keys policy [:seon.config.error/max-evidence-bytes :seon.call-preparation/snapshot])
         digest (assoc :seon.program/definition-digest digest)))
      analysed)))

(defn- defonce-source?
  "Whether `source`, the stored definition of `sym`, is a `defonce`, read as
  `ctx` reads its namespace."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol :string] :boolean]}
  [ctx sym source]
  (sci/binding [sci/ns (sci/find-ns ctx (symbol (namespace sym)))]
    (= 'defonce (first (sci/parse-string (assoc ctx :features #{:clj}) source)))))

(defn- runtime-rows!
  "The loaded program's `src` definitions SCI can run, as `seon.sci.runtime`
  rows, with `ctx` made to resolve them as their files do: each namespace's
  refers, the classes its own `deftype`/`defrecord` define, and its other
  compiled interns (private state, `def`/`defonce` values) bound as the
  host's, so interpreting adds no second copy of process state. A function
  row (`::runtime/kind` `:function`) is armed as its compiled Var is; a value
  definition's row (`:value`) carries the program names it calls or holds
  (`::runtime/calls`), which order its evaluation, and so does a macro's
  (`:macro`): its expansion reads them. A host-bound declaration
  (`:seon.fn/host-bound?`), a `defonce` (an observation the process made once,
  which every runtime keeps) and a test-root or unloaded namespace have no
  row."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :seon.schema/projection
                       [:map-of :symbol :map] [:set :symbol]]
                  [:vector :map]]}
  [ctx database projection namespace-row-by-name test-names]
  (let [read! (fn [result] (when (db/read-refusal? result) (throw (ex-info (:seon.error/message result) result))) result)
        rows (read! (db/q '[:find ?sym ?source ?digest ?arglists ?macro
                            :where [?f :seon.fn/host-bound? false]
                            [?f :seon.schema.admission/source :core]
                            [?f :seon.fn/arglists ?arglists]
                            [(get-else $ ?f :seon.fn/macro? false) ?macro]
                            [?f :seon.fn/ns ?namespace] [?namespace :seon.fn/file ?file]
                            [?file :seon.fn.file/relative-root "src"]
                            [?f :seon.fn/sym ?sym] [?f :seon.fn/source ?source]
                            [?f :seon.program/definition-digest ?digest]]
                          database))
        ;; the program macros each definition expands: a changed macro
        ;; changes its callers' analyses
        expanded (reduce (fn [held [sym macro digest]] (update held sym (fnil conj (sorted-map)) [macro digest]))
                         {}
                         (read! (db/q '[:find ?sym ?macro ?digest
                                        :where [?m :seon.fn/macro? true] [?m :seon.fn/sym ?macro]
                                        [?m :seon.program/definition-digest ?digest]
                                        [?f :seon.fn/calls ?macro] [?f :seon.fn/sym ?sym]]
                                      database)))
        macros (into #{} (keep (fn [[sym _ _ _ macro?]] (when macro? sym))) rows)
        values (into #{} (keep (fn [[sym _ _ arglists macro?]] (when (and (not macro?) (= "()" arglists)) sym))) rows)
        value-calls (reduce (fn [held [sym callee]] (update held sym (fnil conj #{}) callee))
                            {}
                            (read! (db/q '[:find ?sym ?callee :in $ [?sym ...]
                                           :where [?f :seon.fn/sym ?sym] [?f :seon.fn/calls ?callee]]
                                         database (vec (into values macros)))))
        rows (sort-by (comp str first)
                      (filter (fn [[sym]] (let [namespace-name (symbol (namespace sym))]
                                            (and (find-ns namespace-name) (not (contains? test-names namespace-name)))))
                              rows))]
    (install-host-core! ctx (get-in @(:env ctx) [:namespaces 'clojure.core]))
    (doseq [namespace-name (distinct (map (comp symbol namespace first) rows))
            :let [host (find-ns namespace-name)
                  present (get-in @(:env ctx) [:namespaces namespace-name])]]
      (doseq [[_ ^Class class] (own-classes namespace-name)]
        (sci/add-class! ctx (symbol (.getName class)) class))
      (install-namespace-bindings! ctx namespace-name (loaded-bindings (get namespace-row-by-name namespace-name)))
      (install-host-namespace! ctx namespace-name
                               (remove (fn [[local-name]] (contains? present local-name))
                                       (ns-interns host))))
    (into []
          (keep (fn [[sym source digest]]
                  (when-let [host-var (and (not (and (contains? values sym) (defonce-source? ctx sym source)))
                                           (find-var sym))]
                    (let [row {:seon.fn/sym sym :seon.fn/source source :seon.program/definition-digest digest
                               ::runtime/macro-digests (vec (get expanded sym))}]
                      (cond
                        (contains? values sym)
                        (assoc row ::runtime/kind :value ::runtime/calls (get value-calls sym #{}))
                        (contains? macros sym)
                        (assoc row ::runtime/kind :macro ::runtime/calls (get value-calls sym #{}))
                        :else
                        (let [compiled @host-var]
                          (assoc row ::runtime/kind :function
                                 ::runtime/armed-with compiled
                                 ::runtime/arm #(armed-as-compiled sym compiled digest projection %))))))))
          rows)))

(def ^:private run-time-resolution
  "The calls that read the runtime's namespaces and Vars themselves: they name
  a definition at run time, which `:seon.fn/calls` cannot follow, and the
  namespaces and Vars an analysed definition reads are the runtime's SCI ones,
  which a compiled function reads as the JVM's. A function reaching one runs
  its analysed definition on every runtime whose program differs
  (`seon.sci.runtime/reach-index`)."
  '#{clojure.core/resolve clojure.core/ns-resolve clojure.core/requiring-resolve clojure.core/find-var
     clojure.core/eval clojure.core/load-string clojure.core/load-file clojure.core/load clojure.core/require
     clojure.core/intern clojure.core/var-get clojure.core/the-ns clojure.core/ns-publics clojure.core/ns-interns
     clojure.core/all-ns clojure.core/find-ns clojure.core/ns-map clojure.core/ns-refers clojure.core/ns-aliases
     clojure.core/ns-imports clojure.core/bound? clojure.core/var? clojure.core/create-ns clojure.core/remove-ns
     clojure.core/ns-unmap clojure.core/ns-unalias clojure.core/alter-var-root})

(def ^:private reach-index-entry-bytes
  "Retained bytes of one reach in a reach index: a set entry and its share of
  the inverted map, measured over the loaded program (15,170 reaches)."
  96)

(defn- reach-index
  "The program's reach index (`seon.sci.runtime/reach-index`) over
  `database`'s `:seon.fn/calls` facts, once per content of those facts."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:map [::runtime/closed [:set :qualified-symbol]]
                   [::runtime/reached-by [:map-of :qualified-symbol [:set :qualified-symbol]]]]]}
  [database]
  (let [build #(let [names (db/q '[:find [?sym ...] :where [?f :seon.fn/sym ?sym]] database)
                     called (db/q '[:find ?sym (distinct ?callee) :where [?f :seon.fn/sym ?sym] [?f :seon.fn/calls ?callee]]
                                  database)]
                 (runtime/reach-index (into (zipmap names (repeat #{})) (map (fn [[sym callees]] [sym (set callees)])) called)
                                      run-time-resolution))]
    (if-let [revisions (db/content-revisions database [:seon.fn/sym :seon.fn/calls])]
      (seon.cache/lookup ::reach-index revisions
                         #(* reach-index-entry-bytes (reduce + (map count (vals (::runtime/reached-by %)))))
                         build)
      (build))))

(def ^:private cache-entry-points
  "The calls that derive, read or evict a cached value: the entry points a
  changed runtime keys by its code (`seon.sci.runtime/keyed-entries`)."
  '#{seon.cache/lookup seon.cache/lookup-within seon.cache/present seon.cache/evict!
     seon.schema.internal/projection-cache-value})

(defn- callable-schema?
  "Whether `form`, a schema form over `forms`' named schemas, admits only a
  function: a function schema, a callable predicate or a map of them."
  {:malli/schema [:=> [:cat [:map-of :keyword :seon.schema/value] :seon.schema/value] :boolean]}
  [forms form]
  (let [callable-predicates '#{clojure.core/ifn? clojure.core/fn? ifn? fn?}]
    (cond (keyword? form) (and (contains? forms form) (callable-schema? forms (get forms form)))
          (symbol? form) (contains? callable-predicates form)
          (vector? form) (let [[t & more] form
                               more (if (map? (first more)) (rest more) more)]
                           (case t
                             (:=> :-> :function) true
                             :fn (contains? callable-predicates (first more))
                             :map-of (callable-schema? forms (second more))
                             (:maybe :and) (callable-schema? forms (first more))
                             :or (boolean (some #(callable-schema? forms %) more))
                             false))
          :else false)))

(defn- cache-derivers
  "The definitions of `database` calling a cache entry point, each as
  `[sym]`."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector [:tuple :qualified-symbol]]]}
  [database]
  (into [] (comp (remove #(contains? cache-entry-points %)) (map vector))
        (db/q '[:find [?sym ...] :in $ [?entry ...] :where [?f :seon.fn/calls ?entry] [?f :seon.fn/sym ?sym]]
              database cache-entry-points)))

(defn- handed-derivers
  "The cache derivers among `syms` whose contract in `projection` takes a
  function: code their callers hand them, which their closed reach cannot see."
  {:malli/schema [:=> [:cat :seon.schema/projection [:sequential :qualified-symbol]] [:set :qualified-symbol]]}
  [{forms :seon.schema.projection/forms contracts :seon.schema.projection/function-contracts} syms]
  (let [inputs (fn [contract] (mapcat (fn [[_ input]] (let [[t & xs] input] (if (= :catn t) (map last xs) xs)))
                                      (if (= :function (first contract)) (rest contract) [contract])))]
    (into #{} (filter (fn [sym] (some #(callable-schema? forms %) (inputs (get contracts sym))))) syms)))

(defn- handed-code
  "The program names whose code `caller` (its `source` and `calls`) hands
  `deriver`: the caller itself and the names its calls of `deriver` mention."
  {:malli/schema [:=> [:cat :qualified-symbol :qualified-symbol :string [:set :qualified-symbol]] [:set :qualified-symbol]]}
  [deriver caller source calls]
  (let [forms (edamame/parse-string-all source {:all true :read-cond :allow :features #{:clj}
                                                :auto-resolve (fn [alias] (if (= :current alias) (symbol (namespace caller)) alias))})
        by-name (group-by name calls)
        sites (filter (fn [form] (and (seq? form) (symbol? (first form)) (= (name (first form)) (name deriver))))
                      (tree-seq coll? seq forms))]
    (into #{caller} (comp (mapcat rest) (mapcat #(tree-seq coll? seq %)) (filter symbol?) (mapcat #(get by-name (name %))))
          sites)))

(defn- derive-coupling
  "`coupling`'s derivation: each handed deriver's leaders, the names whose
  change may change the code it is handed (`handed-code`), a handed caller's
  own leaders included."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection]
                  [:map [::runtime/leaders [:map-of :qualified-symbol [:set :qualified-symbol]]]]]}
  [database projection]
  (let [handed (handed-derivers projection (map first (cache-derivers database)))
        own (reduce (fn [held [deriver caller source callees]]
                      (update held deriver (fnil into #{}) (handed-code deriver caller source (set callees))))
                    {}
                    (db/q '[:find ?handed ?caller ?source (distinct ?callee) :in $ [?handed ...]
                            :where [?f :seon.fn/calls ?handed] [?f :seon.fn/sym ?caller] [?f :seon.fn/source ?source]
                            [?f :seon.fn/calls ?callee]]
                          database (vec handed)))]
    ;; a handed caller passes on what its own callers hand it
    {::runtime/leaders (loop [held own]
                         (let [grown (reduce-kv (fn [grown deriver names]
                                                  (assoc grown deriver (into names (mapcat #(get held %)) (filter handed names))))
                                                held held)]
                           (if (= grown held) held (recur grown))))}))

(defn- coupling
  "What else decides which program names run compiled
  (`seon.sci.runtime/compiled-names`): a cache deriver whose contract takes a
  function runs analysed whenever a change reaches the code its callers hand
  it (`::runtime/leaders`), so a derivation it is handed is keyed by the
  runtime that wrote it. Once per content of `database`'s definitions and
  `projection`'s contracts."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection]
                  [:map [::runtime/leaders [:map-of :qualified-symbol [:set :qualified-symbol]]]]]}
  [database projection]
  (if-let [revisions (db/content-revisions database [:seon.fn/sym :seon.fn/calls :seon.fn/source])]
    (seon.cache/lookup ::coupling [revisions (:seon.schema.projection/fingerprint projection)]
                       (fn [held] (* reach-index-entry-bytes (reduce + (map count (vals (::runtime/leaders held))))))
                       (fn [] (derive-coupling database projection)))
    (derive-coupling database projection)))

(defn- plan-changes
  "What `plan` changes of the program `ctx` runs, as `bind-analysed!` takes
  it: the names it defines otherwise, to the digest it holds (none for a
  retraction), and the loaded `src` namespaces whose resolution (aliases,
  refers, imports) its rows change."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :map] [:map [::own [:map-of :symbol [:maybe :string]]] [::namespaces [:set :symbol]]]]}
  [ctx plan]
  (let [{rows ::runtime-rows held :namespaces} @(::kernel/program-snapshot ctx)
        runnable (into #{} (map (comp symbol namespace :seon.fn/sym)) rows)]
    {::own (into {} (map (fn [sym] [sym (get (::held plan) sym)]))
                 (concat (::overridden plan) (::added plan) (::retracted plan)))
     ::namespaces (into #{} (comp (filter #(contains? runnable (:seon.ns/name %)))
                                  (remove #(= (row-bindings %) (some-> (get held (:seon.ns/name %)) row-bindings)))
                                  (map :seon.ns/name))
                        (::namespaces plan))}))

(defn- resolver-digest
  "The digest of how `ctx` resolves names in `namespace-name`: its aliases,
  imports and refers (by the Vars' names). An analysis against `ctx` is a
  function of it, so it keys the analysis (`seon.sci.runtime/analysed`)."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :symbol] :string]}
  [ctx namespace-name]
  (let [{:keys [aliases imports refers]} (get-in @(:env ctx) [:namespaces namespace-name])]
    (id/digest 32 [(into (sorted-map) aliases) (into (sorted-map) imports)
                   (into (sorted-map) (update-vals refers str))])))

(defn- dependency-order
  "The due rows `rows`, each after the rows it reads when it is produced: a
  function after the macros it expands, a value or a macro after the due rows
  it calls and those `reached` names for it (its reach among `rows`), which
  its evaluation or expansion may run."
  {:malli/schema [:=> [:cat [:vector :map] [:map-of :qualified-symbol [:set :qualified-symbol]]] [:vector :map]]}
  [rows reached]
  (let [by-sym (into {} (map (juxt :seon.fn/sym identity)) rows)
        reads (fn [{sym :seon.fn/sym kind ::runtime/kind :as row}]
                (if (= :function kind)
                  (map first (::runtime/macro-digests row))
                  (concat (::runtime/calls row) (get reached sym))))
        visit (fn visit [[ordered seen :as held] sym]
                (if (or (contains? seen sym) (not (contains? by-sym sym)))
                  held
                  (let [[ordered seen] (reduce visit [ordered (conj seen sym)] (sort (reads (by-sym sym))))]
                    [(conj ordered (by-sym sym)) seen])))]
    (first (reduce visit [[] #{}] (sort (keys by-sym))))))

(defn- produced-roots
  "The roots of the due `rows` (in dependency order), each produced on a fork
  of `ctx` that binds `entries` and every root produced before it: a value
  evaluated from its source, what a fresh interpretation of the definition
  answers on `ctx`'s runtime, and a function or macro analysed (`analyse`, of
  the fork and the row), so a caller expands the macros and the values they
  read as this runtime holds them. The fork is dropped; nothing is written to
  `ctx`."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :map [:vector :map]
                       [:=> [:cat :seon.sci.eval/ctx :map] :seon.instrument/callable]]
                  :map]}
  [ctx entries rows analyse]
  (let [fork (assoc (sci/fork ctx) :features #{:clj})]
    (binding [sci.lang/*write-observer* nil sci.lang/*expansion-observer* nil]
      (sci.ctx-store/with-ctx fork
        (doseq [[sym root] entries]
          (sci/bind-root! fork (sci/resolve fork sym) root))
        (reduce (fn [held {sym :seon.fn/sym source :seon.fn/source kind ::runtime/kind :as row}]
                  (let [root (if (= :value kind)
                               (sci/binding [sci/ns (sci/find-ns fork (symbol (namespace sym)))]
                                 (sci/eval-string* fork source)
                                 @(sci/resolve fork sym))
                               (analyse fork row))]
                    (sci/bind-root! fork (sci/resolve fork sym) root)
                    (assoc held sym root)))
                {} rows)))))

(defn- bind-analysed!
  "Bind in `ctx` each loaded `src` definition SCI can run (`::runtime-rows`)
  that may answer otherwise than its compiled Var once `ctx` holds `own`
  (name -> digest of the definitions it holds itself) and the declarations of
  `namespaces` differ from the loaded program; the analysis summary. Main
  shares the open functions' analyses with its forks. A runtime keeps a compiled function
  only where its closed reach holds no changed name
  (`seon.sci.runtime/compiled-names`), which answers what its analysed
  definition would; every other function is bound to its analysed definition
  (`seon.sci.runtime/analysed`), which resolves its callees late, through the
  Var each analysed body holds, in the executing context's own binding. A
  value definition holds what it computed: one whose reach holds a name
  changed now, or an open one whenever the runtime's changes differ from
  those it was last evaluated under, is evaluated again from its source on
  `ctx`'s runtime. A macro whose closed reach holds a change, or an open one
  (analysed once in the shared program context), runs analysed, so its callers expand it with the
  values and functions it reads as `ctx` holds them; each root is produced
  after what it reads, its whole reach (`dependency-order`, `produced-roots`). An
  analysis is keyed by its definition, by how `ctx` resolves its namespace
  (`resolver-digest`) and by each macro it expands: its digest as `ctx` holds
  it and the changes its closed reach holds (every change, for an open
  macro), so a changed macro, or a change
  a macro reads, re-analyses its callers here only and an analysis is shared
  by every context holding the same. Everything is analysed and evaluated
  before anything is bound: a failure binds nothing."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:map-of :symbol [:maybe :string]] [:set :symbol]] :map]}
  [ctx own namespaces]
  (let [snapshot (::kernel/program-snapshot ctx)
        {rows ::runtime-rows index ::reach-index roots ::analysed-roots prior ::analysis held ::held-digests
         prior-changes ::changes} @snapshot
        held (merge held own)
        changed (into (set (keys own))
                      (when (seq namespaces)
                        (filter #(contains? namespaces (symbol (namespace %))) (::runtime/closed index))))
        ;; every change the runtime holds, to what it is: a definition's
        ;; digest, else (a namespace whose resolution changed) the digest of
        ;; how this runtime resolves it
        changes (into (or prior-changes {})
                      (map (fn [sym] [sym (if (contains? held sym) (get held sym) (resolver-digest ctx (symbol (namespace sym))))]))
                      changed)
        kept (runtime/compiled-names index (::compiled-kept @snapshot (::runtime/closed index)) changed)
        reached (into #{} (mapcat #(get (::runtime/reached-by index) %)) changed)
        ;; an open name's reach is unknown: any change may be what it reads
        open? (fn [sym] (not (contains? (::runtime/closed index) sym)))
        changed-now? (not= changes prior-changes)
        rekeyed? (fn [row] (or (contains? namespaces (symbol (namespace (:seon.fn/sym row))))
                               (some (fn [[macro]] (or (contains? own macro) (contains? reached macro)
                                                       (and (open? macro) changed-now?)))
                                     (::runtime/macro-digests row))))
        due (into [] (filter (fn [{sym :seon.fn/sym kind ::runtime/kind :as row}]
                               (and (not (contains? held sym))
                                    (not (contains? kept sym))
                                    (case kind
                                      :value (or (contains? reached sym)
                                                 (and (not (contains? (::runtime/closed index) sym))
                                                      (not= changes prior-changes)))
                                      :macro (and (or (contains? reached sym) (open? sym))
                                                  (or (not (contains? roots sym)) (rekeyed? row)))
                                      :function (or (not (contains? roots sym)) (rekeyed? row))))))
              rows)
        resolution (into {} (map (fn [namespace-name] [namespace-name (resolver-digest ctx namespace-name)]))
                         (distinct (map (comp symbol namespace :seon.fn/sym) due)))
        ;; the changes a macro's closed reach holds: what its expansion may read
        reaching (fn [macro] (into (sorted-map) (filter (fn [[sym]] (or (open? macro)
                                                                        (contains? (get (::runtime/reached-by index) sym) macro))))
                                   changes))
        ;; what a due value or macro may run while it is produced: its reach, or
        ;; when that is unknown every due function that does not expand it
        due-functions (into {} (comp (filter #(= :function (::runtime/kind %))) (map (juxt :seon.fn/sym identity))) due)
        reaches? (fn [reader sym]
                   (if (open? reader)
                     (boolean (some->> (get due-functions sym) ::runtime/macro-digests (not-any? #(= reader (first %)))))
                     (contains? (get (::runtime/reached-by index) sym) reader)))
        reached-due (into {} (comp (remove #(= :function (::runtime/kind %)))
                                   (map (fn [{reader :seon.fn/sym}]
                                          [reader (into #{} (comp (map :seon.fn/sym) (filter #(and (not= reader %) (reaches? reader %))))
                                                        due)])))
                          due)
        keyed (fn [row] (assoc row
                               ::runtime/namespace-digest (resolution (symbol (namespace (:seon.fn/sym row))))
                               ::runtime/macro-digests (mapv (fn [[macro digest]] [macro (get held macro digest) (reaching macro)])
                                                             (::runtime/macro-digests row))))
        ;; every derivation this runtime's code makes is keyed by the code it
        ;; runs, its delta (`seon.sci.runtime/keyed-entries`); main's is the bare key
        entries (if (empty? changes)
                  {}
                  (sci.ctx-store/with-ctx ctx
                    (into {} (filter (fn [[sym]] (sci/resolve ctx sym)))
                          (runtime/keyed-entries (id/digest 32 [(into (sorted-map) changes)])))))
        produced (produced-roots ctx entries (dependency-order due reached-due)
                                 (fn [fork row] (runtime/analysed (runtime/program-space fork) (keyed row))))
        values (into #{} (comp (filter #(= :value (::runtime/kind %))) (map :seon.fn/sym)) due)
        bound (merge entries produced)
        analysis {::analysed-count (+ (::analysed-count prior 0) (- (count produced) (count values)))
                  ::evaluated-count (+ (::evaluated-count prior 0) (count values))
                  ::compiled-count (count kept)}]
    (sci.ctx-store/with-ctx ctx
      (doseq [[sym root] bound]
        (sci/bind-root! ctx (sci/resolve ctx sym) root)))
    ;; the program's own definitions, never this context's: its own
    ;; definitions differ from these (`own-bindings`)
    (let [vars (sci.ctx-store/with-ctx ctx (mapv #(sci/resolve ctx %) (keys bound)))]
      (swap! (:env ctx) (fn [env] (update env ::inherited-var-bindings merge
                                          (select-keys (:sci/var-bindings env) vars)))))
    (swap! snapshot (fn [held-snapshot] (-> held-snapshot
                                            (update ::analysed-roots merge bound)
                                            (assoc ::compiled-kept kept ::analysis analysis ::held-digests held
                                                   ::changes changes))))
    analysis))

(defn- acquire-program!
  "Acquire the loaded program into its shared base context. Closed definitions
  bind their JVM roots; open functions bind their shared analyses. Namespaces
  the JVM has not loaded interpret their source. Branch differences belong
  to `derive-overlay-plan`."
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
            test-namespaces (test-namespaces db)
            ;; a program namespace this JVM never loaded runs from its rows
            ;; (an agent never loads into the shared JVM); a test namespace
            ;; is interpreted when it is entered (`enter-namespace!`)
            unloaded (into #{} (keep (fn [[sym _ namespace-name]]
                                       (when (and (not (test-namespaces namespace-name))
                                                  (nil? (find-ns namespace-name)))
                                         (symbol sym))))
                           all-function-rows)
            interpreted #{}
            function-rows (filterv (comp unloaded first) all-function-rows)
            _ (lookup/install-program-doc! ctx db projection)
            selected-namespace-names
            (into (into #{} (map #(nth % 2)) function-rows)
                  (comp (filter (fn [[_ _ admission]] (= :agent admission))) (map first))
                  namespace-assertions)
            all-namespace-row-by-name (base-namespace-rows db all-namespace-names namespace-assertions)
            all-namespace-rows (vec (vals all-namespace-row-by-name))
            test-rows
            (vec
             (db/q '[:find ?sym ?source ?namespace-name ?admission
                     :in $ ?admission
                     :where
                     [?test :seon.schema.admission/source ?admission]
                     [?test :seon.test/sym ?sym]
                     [?test :seon.test/source ?source]
                     [?test :seon.test/ns ?namespace]
                     [?namespace :seon.ns/name ?namespace-name]]
                   db :agent))]
        (kernel/cache-program!
         ctx
         (into {} (map (fn [row] [(symbol (first row)) (function-entry row)])) all-function-rows)
         all-namespace-row-by-name)
        (swap! (::kernel/program-snapshot ctx) assoc
               ::loaded-commit-id (db/commit-id db) ::interpreted interpreted
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
         all-function-rows)
        ;; Libraries the namespaces require resolve through their aliases and
        ;; full names, as the compiled namespaces that require them do.
        (install-required-libraries! ctx all-namespace-rows)
        ;; Open functions resolve runtime names, so every changed fork needs
        ;; their analyses. Bind them once in the shared program context.
        (swap! (::kernel/program-snapshot ctx) assoc
               ::runtime-rows (runtime-rows! ctx db projection all-namespace-row-by-name test-namespaces)
               ::reach-index (merge (reach-index db) (coupling db projection)) ::changes {})
        (bind-analysed! ctx {} #{})
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
          ::produced-in (cluster-base-key db)
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

(declare same-program-root? program-context program-revisions loaded-basis interpretable-functions)

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

(defn- plan-namespace-key
  "What a namespace row of a plan is interpreted from: its source and bindings."
  {:malli/schema [:=> [:cat :map] [:tuple [:maybe :string] :map]]}
  [row]
  [(:seon.ns/source row) (row-bindings row)])

(defn- plan-test-row
  "A test row of a plan, `[sym source namespace-name admission]`."
  {:malli/schema [:=> [:cat :map] [:tuple :qualified-symbol [:maybe :string] [:maybe :symbol] [:maybe :keyword]]]}
  [row]
  [(:seon.test/sym row) (:seon.test/source row) (get-in row [:seon.test/ns :seon.ns/name])
   (:seon.schema.admission/source row)])

(defn- loaded-authored
  "The namespace keys and test rows the loaded commit's agent-authored rows hold, once per store and commit:
  a plan leaves out what the loaded commit already holds as it is."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:map [::held-namespaces [:set :seon.schema/value]] [::held-tests [:set :seon.schema/value]]]]}
  [loaded]
  (seon.cache/lookup
   ::loaded-authored [(store-identity loaded) (db/commit-id loaded)]
   #(* 512 (+ (count (::held-namespaces %)) (count (::held-tests %))))
   #(let [previous (authored-rows loaded)]
      {::held-namespaces (into #{} (comp (filter :seon.ns/name) (map plan-namespace-key)) previous)
       ::held-tests (into #{} (comp (filter :seon.test/sym) (map plan-test-row)) previous)})))

(def ^:private plan-attributes
  "What a row's place in an overlay plan reads beside the loaded commit: its identity, its definition digest (a
  function of its content) and the two row facts outside that digest, its admission and host binding."
  [:seon.fn/sym :seon.test/sym :seon.ns/name :seon.program/definition-digest :seon.schema.admission/source
   :seon.fn/host-bound?])

(defn- entity-value
  "The value of `attribute` on entity `e` in `database`, else nil."
  {:malli/schema [:=> [:cat :seon.db/database-value :int :qualified-keyword] [:maybe :seon.schema/value]]}
  [database e attribute]
  (let [datoms (db/datoms database :eavt e attribute)]
    (when (db/read-refusal? datoms)
      (throw (ex-info (:seon.error/message datoms) datoms)))
    (:v (first datoms))))

(defn- identity-entity
  "The entity `database` holds at the identity `[attribute value]`, else nil."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword :seon.schema/value] [:maybe :int]]}
  [database attribute value]
  (let [datoms (db/datoms database :avet attribute value)]
    (when (db/read-refusal? datoms)
      (throw (ex-info (:seon.error/message datoms) datoms)))
    (:e (first datoms))))

(defn- plan-identities
  "The function, namespace and test names whose rows differ between `before` and `database`, two values of one
  store (`seon.db/datoms-diff`): work proportional to what differs, never to the program."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value]
                  [:map [:seon.fn/sym [:set :qualified-symbol]] [:seon.ns/name [:set :symbol]]
                   [:seon.test/sym [:set :qualified-symbol]]]]}
  [before database]
  (let [entities (into #{} (comp (mapcat #(apply concat (db/datoms-diff before database :aevt [%]))) (map first))
                       plan-attributes)
        identities [:seon.fn/sym :seon.ns/name :seon.test/sym]
        rows (mapcat (fn [basis]
                       (let [rows (db/pull-many basis identities (vec entities))]
                         (when (db/read-refusal? rows)
                           (throw (ex-info (:seon.error/message rows) rows)))
                         rows))
                     [before database])
        names (fn [attribute] (into #{} (keep attribute) rows))]
    {:seon.fn/sym (names :seon.fn/sym) :seon.ns/name (names :seon.ns/name) :seon.test/sym (names :seon.test/sym)}))

(defn- function-places
  "Each of `symbols` to its place in the plan of `database` against `loaded`, whose definition digests are
  `digests`: `[::overridden digest]` where its digest differs and so does its behavior (`behavior-changed?`), an
  interpretable or agent-authored one, or the agent's own the loaded commit lacks; `[::added digest]` for another
  the loaded commit lacks; `[::retracted]` for an interpretable one the loaded commit holds and `database` lacks;
  absent for every other."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value
                       [:map-of :qualified-symbol :seon.program/definition-digest] [:set :qualified-symbol]]
                  [:map-of :qualified-symbol [:or [:tuple [:enum ::overridden ::added] :seon.program/definition-digest]
                                              [:tuple [:= ::retracted]]]]]}
  [database loaded digests symbols]
  (let [own (into {} (keep (fn [sym]
                             (when-let [e (identity-entity database :seon.fn/sym sym)]
                               [sym {:digest (or (not-empty (entity-value database e :seon.program/definition-digest))
                                                  (row-definition-digest (db/pull database '[*] [:seon.fn/sym sym])))
                                     :authored? (= :agent (entity-value database e :seon.schema.admission/source))}])))
                  symbols)
        differing (into [] (filter #(and (contains? digests %) (not= (:digest (own %)) (get digests %)))) (keys own))
        interpretable (interpretable-functions database (vec (remove (comp :authored? own) differing)))
        retracted (interpretable-functions loaded (into [] (remove #(contains? own %)) (filter #(contains? digests %) symbols)))]
    (into (into {} (map (fn [sym] [sym [::retracted]])) retracted)
          (keep (fn [[sym {:keys [digest authored?]}]]
                  (cond (not (contains? digests sym)) [sym [(if authored? ::overridden ::added) digest]]
                        (and (not= digest (get digests sym)) (or authored? (contains? interpretable sym))
                             (behavior-changed? database loaded sym))
                        [sym [::overridden digest]])))
          own)))

(defn- namespace-place
  "The row of namespace `namespace-name` the plan of `database` against `loaded` interprets: one with source the
  agent authored or whose digest the loaded commit does not hold, unless the loaded commit's authored rows hold it
  as it is (`held-namespaces`); else nil."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value [:set :seon.schema/value] :symbol]
                  [:maybe :map]]}
  [database loaded held-namespaces namespace-name]
  (when-let [e (identity-entity database :seon.ns/name namespace-name)]
    (let [authored? (= :agent (entity-value database e :seon.schema.admission/source))
          digest (entity-value database e :seon.program/definition-digest)
          moved? (and digest (not= digest (some->> (identity-entity loaded :seon.ns/name namespace-name)
                                                   (#(entity-value loaded % :seon.program/definition-digest)))))]
      (when (or authored? moved?)
        (let [row (db/pull database (cond-> namespace-pattern authored? (conj {:seon.test/ns [:seon.ns/name]}))
                           [:seon.ns/name namespace-name])]
          (when (and (:seon.ns/source row) (not (held-namespaces (plan-namespace-key row))))
            row))))))

(defn- test-place
  "The test row of `sym` the plan of `database` interprets: an agent-authored test the loaded commit's authored
  rows do not hold as it is (`held-tests`); else nil."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :seon.schema/value] :qualified-symbol]
                  [:maybe :seon.schema/value]]}
  [database held-tests sym]
  (when-let [e (identity-entity database :seon.test/sym sym)]
    (when (= :agent (entity-value database e :seon.schema.admission/source))
      (let [row (plan-test-row (db/pull database (conj namespace-pattern {:seon.test/ns [:seon.ns/name]})
                                        [:seon.test/sym sym]))]
        (when-not (held-tests row) row)))))

(def ^:private empty-overlay-plan
  "The plan of the loaded commit against itself: it changes nothing."
  {::overridden #{} ::added #{} ::retracted #{} ::namespaces [] ::tests [] ::held {}})

(defn- fold-overlay-plan
  "What `database` changes of the `loaded` commit, the interpret set I(B, L), folded from `prior`, the plan of
  `before`, by the rows that differ between `before` and `database` (`plan-identities`): the functions whose digest
  differs (`::overridden`, where the behavior differs too, and the agent's own the loaded commit lacks) or the
  other functions the loaded commit lacks (`::added`), the functions it holds that `database` lacks
  (`::retracted`), the authored test rows it does not hold as they are, and every namespace row it does not hold
  as it is, authored or file-derived. A work branch runs its own HEAD: what main changed since the fork is a
  difference like any other until it is merged."
  {:malli/schema [:=> [:cat :map :seon.db/database-value :seon.db/database-value :seon.db/database-value] :map]}
  [prior before database loaded]
  (let [{functions :seon.fn/sym namespace-names :seon.ns/name test-symbols :seon.test/sym} (plan-identities before database)
        {held-namespaces ::held-namespaces held-tests ::held-tests} (loaded-authored loaded)
        places (function-places database loaded (loaded-digests loaded) functions)
        placed (fn [place] (into (reduce disj (place prior) functions)
                                 (keep (fn [[sym [at]]] (when (= place at) sym))) places))
        namespaces (into [] (concat (remove #(contains? namespace-names (:seon.ns/name %)) (::namespaces prior))
                                    (keep #(namespace-place database loaded held-namespaces %) namespace-names)))
        tests (into [] (concat (remove #(contains? test-symbols (first %)) (::tests prior))
                               (keep #(test-place database held-tests %) test-symbols)))
        held (into (apply dissoc (::held prior) functions)
                   (keep (fn [[sym [at digest]]] (when (#{::overridden ::added} at) [sym digest]))) places)]
    {::overridden (placed ::overridden)
     ::added (placed ::added)
     ::retracted (placed ::retracted)
     ::namespaces (vec (sort-by :seon.ns/name namespaces))
     ::tests (vec (sort-by first tests))
     ::held held
     ;; What the plan's interpretation is a function of, by content only: its
     ;; definitions by digest, its retractions, its namespaces by name, source
     ;; and bindings, its tests by row content. Entity ids differ per branch
     ;; for equal programs.
     ::digest (id/digest 32 [held
                             (vec (sort (placed ::retracted)))
                             (mapv (juxt :seon.ns/name plan-namespace-key) (sort-by :seon.ns/name namespaces))
                             (vec (sort-by first tests))])}))

(defn- derive-overlay-plan
  "The overlay plan of `database` against the `loaded` commit (`fold-overlay-plan`), folded from the loaded commit,
  whose own plan changes nothing."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value] :map]}
  [database loaded]
  (fold-overlay-plan empty-overlay-plan loaded database loaded))

(defn- overlay-plan
  "The overlay of one program against the loaded commit, once per pair: keyed
  by the store, `database`'s program revisions (the commit whose program
  datoms equal its own, so a result-only commit answers its parent's entry)
  and the loaded commit. A miss folds the plan its branch last held forward
  by what its commits changed since (`fold-overlay-plan`), else derives it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value] :map]}
  [database loaded]
  (if-let [basis (when (commit-id-of loaded) (program-revisions database (db/carried-projection database)))]
    (let [head [(store-identity database) (get-in database [:config :branch]) (db/commit-id loaded)]
          plan-key [(store-identity database) basis (db/commit-id loaded)]
          commit (commit-id-of database)
          plan (seon.cache/lookup
                ::overlay-plan plan-key
                (fn [{::keys [overridden added retracted namespaces tests]}]
                  (+ (* 128 (count basis))
                     (* 256 (+ (count overridden) (count added) (count retracted) (count namespaces) (count tests)))
                     (* 2 (+ (reduce + 0 (map (comp count :seon.ns/source) namespaces))
                             (reduce + 0 (map (comp count second) tests))))))
                #(let [{prior-key ::plan-key prior-commit ::commit} (seon.cache/present ::overlay-head head)
                       prior (when prior-key (seon.cache/present ::overlay-plan prior-key))]
                   (or (when prior
                         (db/read-at database prior-commit
                                     (fn [before] (when before (fold-overlay-plan prior before database loaded)))))
                       (derive-overlay-plan database loaded))))]
      (when commit
        (seon.cache/evict! ::overlay-head head)
        (seon.cache/lookup ::overlay-head head (constantly 256) (constantly {::plan-key plan-key ::commit commit})))
      plan)
    (derive-overlay-plan database loaded)))

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
  (let [rows (db/q '[:find [?sym ...] :in $ [?sym ...]
                     :where [?row :seon.fn/sym ?sym] [?row :seon.fn/host-bound? false]]
                   database (vec symbols))]
    (when (db/read-refusal? rows)
      (throw (ex-info (:seon.error/message rows) rows)))
    (set rows)))

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
             ;; A namespace the context holds keeps its bindings: promoting a member never touches its aliases.
             (when-not (contains? (:namespaces @(:env ctx)) namespace-name)
               (sci/install-namespace-bindings! ctx namespace-name (assoc (row-bindings ns-row) :refers {})))
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

(def ^:dynamic ^:private *deriving-database*
  "The database a program context derivation interprets (`derive-base-ctx`, `derive-overlay-ctx`): a derivation
  holds no custody, so a lazy root its acquisition calls (a value definition calling a closure member) reads its
  stored source here."
  nil)

(defn- source-database
  "The database `ctx` interprets its program's stored source from: its custody's while it evaluates, the one
  being derived while its program context is built."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] [:maybe :seon.db/database-value]]}
  [ctx]
  (or (context-database ctx) *deriving-database*))

(defn- lazy-root
  "The root of closure member `sym` at `sci-var` until its first call: that
  call interprets the member, in the context executing it, from the stored
  source its executing context reads (`source-database`, `promote-member!`), so cost follows the members called; then
  it and every later call run what that context binds. The closure retains no database value and no
  commit: the program context holding this root is shared by content (`program-context`), so the
  database that built it may live in a store the executing context never holds (a released test
  world's overlay), while the executing context's database holds the same program."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :qualified-symbol :seon.sci.eval/sci-var]
                  [:fn clojure.core/ifn?]]}
  [ctx database sym sci-var]
  (let [projection (db/carried-projection database)]
    ;; marked as the placeholder it is: no context's own definition
    ;; (`seon.sci.kernel/own-definition?`)
    (with-meta
      (fn lazy-member [& args]
        (let [executing (executing-ctx ctx)
              held #(sci.ctx-store/with-ctx executing @sci-var)
              pending (held)]
          (when (::lazy-member (meta pending))
            (promote-member! executing
                             (cond-> (source-database executing)
                               projection (vary-meta assoc :seon.schema/projection projection))
                             sym sci-var pending))
          (apply (held) args)))
      {::lazy-member sym})))

(defn- bind-lazy-members!
  "Bind each function of `members` in `ctx` to its `lazy-root`, creating the
  Var of one `ctx` does not hold; answer the rest, left unbound for eager
  interpretation: a macro runs when its callers are analyzed, not called, and
  a value definition (its `:seon.fn/arglists` the empty list) is read, never called."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value [:seqable :qualified-symbol]]
                  [:vector :qualified-symbol]]}
  [ctx database members]
  (let [functions (set (db/q '[:find [?sym ...] :in $ [?sym ...]
                               :where [?f :seon.fn/sym ?sym] [?f :seon.fn/arglists ?arglists]
                                      [(not= "()" ?arglists)]
                                      (not [?f :seon.fn/macro? true])]
                             database (vec members)))]
    (sci.ctx-store/with-ctx ctx
      (reduce
       (fn [eager sym]
         (let [held (sci/resolve ctx sym)]
           (if (or (not (functions sym)) (and held (:macro (meta held))))
             (conj eager sym)
             (let [namespace-name (symbol (namespace sym))
                   sci-var (or held
                               (let [created (sci/new-var (symbol (name sym)) nil
                                                          {:ns (context-namespace ctx namespace-name)})]
                                 (sci/add-namespace! ctx namespace-name {(symbol (name sym)) created})
                                 created))]
               (sci/bind-root! ctx sci-var (lazy-root ctx database sym sci-var))
               eager))))
       []
       members))))

(defn- without-reinstalled
  "The acquisition `state` a derivation carries, without the outcomes of the
  rows it installs again: each row's refusal and load result is its latest
  installation's."
  {:malli/schema [:=> [:cat :map [:set :qualified-symbol]] :map]}
  [state reinstalled]
  (let [row-of #(or (:seon.sci.eval/refused-function %) (:seon.sci.eval/row-symbol %) (:seon.fn/sym %))
        keep-row (fn [entries] (into [] (remove #(contains? reinstalled (row-of %))) entries))]
    (cond-> state
      (::acquisition-refusals state) (update ::acquisition-refusals keep-row)
      (::agent-mistakes state) (update ::agent-mistakes keep-row)
      (:seon.sci.eval/load-results state) (update :seon.sci.eval/load-results keep-row))))

(defn- unbind-reinstalled!
  "Unbind in `ctx` each Var of `symbols` it holds, keeping the Var: a row
  installed again takes only its new definition's root, so a refused one
  answers no predecessor's root and a `defonce` runs its init again."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:sequential :qualified-symbol]] :nil]}
  [ctx symbols]
  (sci.ctx-store/with-ctx ctx
    (doseq [sym symbols
            :let [held (sci/resolve ctx sym)]
            :when (sci.utils/var? held)]
      (sci.vars/unbind held))))

(defn- interpret-overlay!
  "Interpret the overlay `plan` on `ctx`: its rows whose definition digest
  (`::held`) the plan `ctx` was derived with did not hold (Δ; on the loaded
  base, every row), their namespaces and tests now, and bind the compiled Vars
  of the functions it adds; the reverse closure of Δ, its callers the branch
  interprets from source (`seon.fn/reverse-closure`: never loaded platform code,
  which keeps the compiled Vars `ctx` binds and sees Δ only after merge and
  reload) and the tests reaching them as interpreted tests, is bound again lazily
  (`bind-lazy-members!`), its value definitions and macros interpreted now,
  but for the members of a test namespace `ctx` has not entered, which its entry
  interprets; from `::closure` and `::closure-tests` when the plan carries them, else
  walked from Δ. Each Var installed again is unbound first. The acquisition
  advanced from the predecessor's, carrying this plan. A plan without
  `::held` (an evaluation's `::evaluated` definitions, which `ctx` holds as
  evaluated) changes its `::overridden` and keeps what `ctx` interprets."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :map] :map]}
  [ctx database {::keys [overridden added retracted namespaces tests evaluated held] :as plan}]
  (let [snapshot @(::kernel/program-snapshot ctx)
        ;; the plan this context's program was derived with: none on the loaded base
        previous (::plan (::acquisition snapshot))
        prior-held (::held previous {})
        delta (if held
                (into #{} (keep (fn [[sym digest]] (when-not (= digest (get prior-held sym)) sym))) held)
                overridden)
        projection (or (db/carried-projection database) (:seon.schema/projection ctx))
        database (vary-meta database assoc :seon.schema/projection projection)
        closure (cond (contains? plan ::closure) {:seon.fn/affected (::closure plan)
                                                  :seon.fn/tests (::closure-tests plan)}
                      (seq delta) (seon.fn/reverse-closure {:seon.db/db database :seon.fn/seeds delta})
                      :else {:seon.fn/affected #{}})
        _ (when (:seon.db/invalid-read closure)
            (throw (ex-info "Cannot acquire the affected function closure." closure)))
        affected (set (:seon.fn/affected closure))
        reached (set/difference affected delta)
        ;; what the predecessor interpreted stays, but for Δ and what reaches it
        already (cond-> (into (set (::interpreted snapshot)) evaluated)
                  held (set/difference delta reached))
        prior-tests (set (::tests previous))
        already-tests (cond-> (set (::interpreted-tests snapshot))
                        held (set/difference (into (set (:seon.fn/tests closure))
                                                   (comp (remove prior-tests) (map first)) tests)))
        interpreted (into overridden affected)
        tests (into [] (remove (comp already-tests first)) tests)
        entered (::entered-namespaces snapshot #{})
        ;; a test namespace not yet entered interprets all its rows from the
        ;; branch when it is entered (`enter-namespace!`), against the Vars
        ;; `ctx` holds then: none of its members is bound or run before
        unentered-test? (fn [namespace-name]
                          (and (contains? (::test-namespaces snapshot) namespace-name)
                               (not (entered namespace-name))))
        callers (into [] (remove #(or (already %) (contains? overridden %)
                                      (unentered-test? (symbol (namespace %)))))
                      (sort reached))
        ;; the closure holds only source callers (`seon.fn/reverse-closure`): a
        ;; loaded platform or host-bound caller keeps the compiled Var the
        ;; context binds; the macros and value definitions among them interpret now
        macros (bind-lazy-members! ctx database callers)
        added-rows (function-rows-in database (remove #(or (contains? reached %) (contains? overridden %)
                                                           (and held (not (contains? delta %))))
                                                      added))
        ;; an added function with no compiled Var (of a source namespace, or
        ;; one the loaded JVM lacks, as a callee main deleted) interprets now,
        ;; a test namespace's once that namespace is entered
        sourced (into [] (keep (fn [[sym _ namespace-name]]
                                 (when (if (source-namespace? ctx namespace-name)
                                         (not (unentered-test? namespace-name))
                                         (nil? (get (ns-interns namespace-name) (symbol (name sym)))))
                                   sym)))
                      added-rows)
        function-rows (function-rows-in database (into (vec (remove already overridden))
                                                       (concat macros sourced)))
        namespace-key (juxt :seon.ns/name :seon.ns/source row-bindings)
        prior-namespaces (into #{} (map namespace-key) (::namespaces previous))
        namespaces (into [] (remove (comp prior-namespaces namespace-key)) namespaces)
        selected (into (into #{} (map :seon.ns/name) namespaces) (map #(nth % 2)) function-rows)
        new-namespaces (into #{} (comp (map #(nth % 2)) (remove (:namespaces snapshot)) (remove selected))
                             added-rows)
        pulled (into {} (map (juxt :seon.ns/name namespace-row))
                     (db/pull-many database namespace-pattern
                                   (mapv #(vector :seon.ns/name %) (sort-by str (into selected new-namespaces)))))
        namespace-rows (vec (vals pulled))
        namespace-row-by-name (select-keys pulled selected)
        reinstalled (into (into #{} (map first) function-rows) (map first) tests)]
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
       (unbind-reinstalled! ctx (map first function-rows))
       ;; a declaration the branch does not hold is not in its program
       (doseq [sym retracted]
         (sci/eval-form ctx (list 'ns-unmap (list 'quote (symbol (namespace sym))) (list 'quote (symbol (name sym))))))
       (let [acquired (install-selected!
                       ctx database namespace-row-by-name
                       function-rows tests (into #{} (map first) function-rows)
                       (-> (::acquisition snapshot)
                           (dissoc ::acquisition-refusals-recorded? ::acquisition-recording-error)
                           (without-reinstalled reinstalled)
                           (assoc :seon.schema/projection projection
                                  ::produced-in (::produced-in plan)
                                  :seon.sci.eval/interpreted-count
                                  (count (::interpreted @(::kernel/program-snapshot ctx))))))]
         (cond-> acquired
           held (assoc ::plan (select-keys plan [::held ::tests ::namespaces]))))))))

(defn program-value?
  "Whether `sym` names a loaded `src` value definition `ctx` runs
  (`::runtime-rows`): a write of it changes what loaded definitions read."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol] :boolean]}
  [ctx sym]
  (boolean (some #(and (= sym (:seon.fn/sym %)) (= :value (::runtime/kind %)))
                 (::runtime-rows @(::kernel/program-snapshot ctx)))))

(defn interpret-affected!
  "Interpret in `ctx` the source callers and tests reaching `changed`, the
  definitions `ctx` holds as evaluated, each to the digest of the source it
  was evaluated from. Every loaded `src` definition whose reach holds one is
  bound analysed, or evaluated again for a value (`bind-analysed!`), so it
  calls `ctx`'s definitions; each caller of their reverse closure the branch
  interprets from source (`seon.fn/reverse-closure`) is bound to interpret
  itself from its stored source in `database` on its first call
  (`bind-lazy-members!`), and each test reaching them runs interpreted. The
  acquisition after it, with the analysis summary as `::analysis`."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value [:map-of :qualified-symbol [:maybe :string]]] :map]}
  [ctx database changed]
  ;; Interpreting a stored row is not the form's own definition: no write
  ;; or expansion of it is observed as the program change.
  (binding [sci.lang/*write-observer* nil
            sci.lang/*expansion-observer* nil]
    (sci.ctx-store/with-ctx ctx
      (let [analysis (bind-analysed! ctx changed #{})
            names (set (keys changed))
            interpreted (interpret-overlay! ctx database
                                            {::overridden names ::evaluated names
                                             ::added #{} ::namespaces [] ::tests []
                                             ::produced-in [::interpret-affected (db/basis-t database) (vec (sort names))]})
            acquired (record-refusals-once! ctx database interpreted
                                            (:seon.flow/commit-fault! @(::kernel/program-snapshot ctx)))]
        (swap! (::kernel/program-snapshot ctx) assoc ::acquisition acquired)
        (assoc acquired ::analysis analysis)))))

(defn- program-changes
  "What `database` changes of the `loaded` program (`overlay-plan`), or nil when
  it changes nothing: its program revisions are the loaded commit's, or its
  plan is empty."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.schema/projection]
                  [:or :nil :map]]}
  [database loaded projection]
  (let [revisions (program-revisions database projection)]
    (when-not (or (identical? loaded database)
                  (and revisions (= revisions (program-revisions loaded projection))))
      (let [plan (overlay-plan database loaded)]
        (when (some seq (vals (dissoc plan ::digest ::held)))
          plan)))))

(defn- overlay!
  "Interpret on `ctx`, a fork of the cluster base, the `plan` of what `database`
  changes of the loaded program; the acquisition."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :map] :map]}
  [ctx database plan]
  (let [acquired (interpret-overlay! ctx database plan)]
    (swap! (::kernel/program-snapshot ctx) assoc ::acquisition acquired)
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

(def ^:private carried-snapshot-keys
  "The program snapshot entries a context's custody owns, never its program:
  what a fork of a program context takes from the context it serves as."
  [::acquired-basis ::loaded-commit-id ::loaded-basis ::loaded-connection ::armed-dials :seon.flow/commit-fault!])

(defn- forked-program
  "One fork of the program context `value`, serving as `base` serves: `base`'s
  custody, world and custody-owned snapshot entries (`carried-snapshot-keys`),
  fresh kernel atoms of the value's program, and the value it forked from."
  {:malli/schema [:=> [:cat :seon.sci.eval/program-context :seon.sci.eval/ctx] :seon.sci.eval/ctx]}
  [value base]
  (assoc (merge base (inherit-var-bindings! (sci/fork value)))
         ::kernel/installed-functions (atom (::kernel/installed-functions value))
         ::kernel/program-snapshot
         (atom (merge (::kernel/program-snapshot value)
                      (select-keys @(::kernel/program-snapshot base) carried-snapshot-keys)
                      {::program-context value}))))

(defn- program-key-input
  "What `database`'s program context is a function of, as `base` serves it:
  the store, the program and dial revisions, and the revision of the adoption
  record naming the loaded commit (`loaded-basis`). Nil for a value with no
  committed revisions."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.sci.eval/ctx] [:or :nil :seon.schema/value]]}
  [database base]
  (let [projection (or (db/carried-projection database) (schema/projection-from-database database))]
    (when-let [revisions (program-revisions database projection)]
      [(store-identity database) revisions
       (db/content-revisions database (vec (sort (config/dial-attributes projection))))
       (loaded-basis (loaded-source @(::kernel/program-snapshot base) database))])))

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

(defn- own-namespaces
  "The namespaces of `ctx`'s `layer` that hold its own work: those whose map is
  not the one `previous` (the program context it forked from) holds, and those
  of a Var it rebound. Proportional to the namespaces, never their Vars."
  [layer previous own-binding]
  (into (into #{} (keep (fn [[namespace-name entries]]
                          (when-not (identical? entries (get previous namespace-name)) namespace-name)))
              layer)
        (keep #(some-> (:ns (meta %)) sci/ns-name))
        (keys own-binding)))

(defn- regenerate-agent-context!
  "Fork the program context `value`, serving as `base` serves, and carry
  `ctx`'s private layer into it as actual objects.

  The private layer is what the agent's own evaluation added: Vars its SCI
  generations defined, its own bindings of inherited Vars, and Vars at a path
  the program context it forked from never had (a namespace it required from
  the JVM, `load-host-namespace`). Only the namespaces holding them are read
  (`own-namespaces`): a namespace map the context shares with that value holds
  none. Owned private Vars keep their roots, including captured values; an own
  definition keeps its Var, which takes another writer's definition of its
  row. Inherited program bindings are replaced. A JVM restart has no entering
  context and consequently preserves no private objects. No database read
  restores them."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.sci.eval/program-context :seon.sci.eval/ctx]
                  :seon.sci.eval/ctx]}
  [ctx value base]
  (let [env @(:env ctx)
        ;; Every generation this context held: SCI stamps a Var born in one,
        ;; and each regeneration gives the context a new one.
        generations (conj (get env ::generations #{}) (:sci/generation env))
        layer (:namespaces env)
        snapshot @(::kernel/program-snapshot ctx)
        previous (:namespaces (:env (or (::program-context snapshot) value)))
        base-layer (:namespaces (:env value))
        root-of #(if (sci.vars/hasRoot %) (sci.vars/getRawRoot %) absent-intern)
        own-binding (own-bindings ctx)
        visited (own-namespaces layer previous own-binding)
        changed? #(seq (replaced-definitions snapshot (::kernel/program-snapshot value)
                                             [(symbol (str %1) (str %2))]))
        private-bindings
        (sci.ctx-store/with-ctx ctx
          (doall
           (for [namespace-name visited
                 [local-name entry] (get layer namespace-name)
                 :when (sci.utils/var? entry)
                 :let [path [namespace-name local-name]
                       held (get-in base-layer path)
                       transferred? (boolean (and held (same-program-root? (root-of entry)
                                                                           (if (sci.utils/var? held) (root-of held) held))))
                       binding (get own-binding entry)
                       rebound? (some? binding)
                       own? (or rebound? (contains? generations (:sci/generation (meta entry))))
                       changed (when-not transferred? (changed? namespace-name local-name))
                       adopted (when (and own? changed (sci.utils/var? held)) held)]
                 ;; An own Var stays the Var its callers' closures hold, as one
                 ;; Clojure Var does, with this context's binding of it, and
                 ;; another writer's definition of its row is adopted into it.
                 ;; Only a deleted row drops it.
                 :when (if own?
                         (or transferred? (not changed) adopted)
                         (and (not (contains? (get previous namespace-name) local-name))
                              (not changed) (not transferred?)))]
             [namespace-name local-name entry (or transferred? (some? adopted)) adopted
              binding])))
        private-names (into #{} (map (fn [[namespace-name local-name _]]
                                      (symbol (str namespace-name) (str local-name))))
                            private-bindings)
        ;; the program context's row of a transferred or adopted definition is the current one
        snapshot-names (into #{} (keep (fn [[namespace-name local-name _ base-row?]]
                                         (when-not base-row?
                                           (symbol (str namespace-name) (str local-name)))))
                             private-bindings)
        forked (forked-program value base)
        regenerated
        (assoc (merge ctx forked)
               ::kernel/installed-functions
               (atom (into @(::kernel/installed-functions forked)
                           (filter private-names)
                           @(::kernel/installed-functions ctx)))
               ::kernel/program-snapshot
               (atom (cond-> (update @(::kernel/program-snapshot forked) :functions merge
                                     (select-keys (:functions snapshot) snapshot-names))
                       (seq (::context-defined snapshot))
                       (assoc ::context-defined (into #{} (filter snapshot-names) (::context-defined snapshot)))))
               :seon.sci.eval/private-state :preserved-in-memory)]
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
    (doseq [namespace-name visited
            [local-name entry] (get layer namespace-name)
            :let [prior (get-in previous [namespace-name local-name])]
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

(defn- agent-ran?
  "Whether `agent-id` has any run in `db`: one lookup, so a fresh agent's
  context reads none of its history."
  {:malli/schema [:=> [:cat :seon.db/database-value [:maybe :seon.agent/id]] :boolean]}
  [db agent-id]
  (boolean (and agent-id
                (db/q '[:find ?run .
                        :in $ ?agent-id
                        :where [?agent :seon.agent/id ?agent-id]
                               [?run :seon.turn/agent ?agent]]
                      db agent-id))))

(defn fork-for-turn
  "The agent's context for this turn, serving as the base `ctx` serves.

  An agent context whose program key (`program-key-input`) is the branch
  head's is returned as it is: no fork, no walk. A moved key looks up the
  head's program context (`program-context`); the identical value keeps the
  context too, and another value is forked once with the agent's private layer
  carried across (`regenerate-agent-context!`). A fresh fork (no carried agent
  context: a restart) binds each of the agent's settled handles to a marker
  naming its value's class; values are never stored."
  {:malli/schema [:=> [:cat :seon.sci.eval/defs-fork-request]
                  :seon.sci.eval/defs-fork-result]}
  [{base-ctx :seon.sci.eval/ctx
    agent-ctx :seon.sci.eval/agent-ctx
    db :seon.db/db
    agent-id :seon.agent/id}]
  (let [carried? (some? agent-ctx)
        ;; A fresh context reads the agent's session and handles from its
        ;; evaluations; an agent that never ran has none to read.
        history? (and (not carried?) (agent-ran? db agent-id))
        base-snapshot @(::kernel/program-snapshot base-ctx)
        ;; the key the base's acquisition of this very commit computed, else derived
        input (if (and (::program-key base-snapshot)
                       (= (db/commit-id db) (get-in base-snapshot [::acquired-basis :seon.source/commit-id])))
                (::program-key base-snapshot)
                (program-key-input db base-ctx))
        held (some-> agent-ctx ::kernel/program-snapshot deref)
        value (when-not (and carried? input (= input (::program-key held)))
                (program-context db (loaded-database base-ctx db)))
        ctx (cond
              (not carried?)
              (env/carry-state
               (assoc (forked-program value base-ctx)
                      ::turn-fork? true
                      ::result-objects (atom {})
                      ::print-session
                      (atom (or (when history? (session-print-options db agent-id)) {})))
               (if (env/environment? (env/of base-ctx))
                 (env/environment-state (env/of base-ctx))
                 (projection-state db (context-projection base-ctx))))

              (or (nil? value) (identical? value (::program-context held)))
              agent-ctx

              :else
              (regenerate-agent-context! agent-ctx value base-ctx))
        ;; the custody's per-commit entries and the key the context now serves
        _ (swap! (::kernel/program-snapshot ctx)
                 #(cond-> (merge % (select-keys @(::kernel/program-snapshot base-ctx) carried-snapshot-keys))
                    input (assoc ::program-key input)))
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
    (let [unbound (when history?
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

(defn- acquisition-attributes
  "Every attribute an acquisition reads, derived from the projection's declarations.

  The program partition (`seon.program/program-attributes`), the projection's own
  read set (`seon.schema/projection-attributes`) and the loaded source commit.
  Dials are not among them: a context compares the dial values its wrappers
  armed with (`armed-dials`). Retained on the projection's own holder
  (`schema.internal/projection-cache-value`)."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:vector :qualified-keyword]]}
  [projection]
  (schema.internal/projection-cache-value
   projection ::acquisition-attributes
   (fn []
     (-> (program/program-attributes projection)
         (into schema/projection-attributes)
         (conj :seon.config/cluster :seon.source/commit-id)
         sort
         vec))))

(defn- program-revisions
  "The program `database` holds: each acquisition attribute to the commit whose
  datoms of it equal `database`'s (`seon.db/content-revisions`). Each commit
  carries its revisions, so equal maps name equal programs on every branch,
  connection and restart. Nil for a value with no committed revisions."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection]
                  [:or :nil [:map-of :qualified-keyword :uuid]]]}
  [database projection]
  (db/content-revisions database (acquisition-attributes projection)))

(defn- acquired-basis
  "The commit and program revisions acquired from `database`."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:map [:seon.db/basis-t :seon.db/basis-t]
                   [::program-revisions {:optional true} [:map-of :qualified-keyword :uuid]]
                   [:seon.source/commit-id {:optional true} :seon.source/commit-id]]]}
  [database]
  (let [revisions (program-revisions database (db/carried-projection database))
        commit (db/commit-id database)]
    (cond-> {:seon.db/basis-t (db/basis-t database)}
      revisions (assoc ::program-revisions revisions)
      (uuid? commit) (assoc :seon.source/commit-id commit))))

(defn- freeze-program
  "The builder `ctx` as a program context: SCI's environment, the kernel's
  program snapshot and installed functions as their values, and no custody.
  The builder's later writes never reach it (`sci.core/freeze`)."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :seon.sci.eval/program-context]}
  [ctx]
  (-> (sci/freeze ctx)
      (dissoc ::custody)
      (update ::kernel/installed-functions deref)
      (update ::kernel/program-snapshot deref)))

(defn- derive-base-ctx
  "Derive the loaded program's context: load its JVM namespaces, build the
  interpreter under its projection on a builder, acquire the `loaded` program
  and freeze it."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.sci.eval/program-context]}
  [loaded]
  ;; A program context is a function of its content: no init reads the
  ;; deriving thread's connection or read basis.
  (binding [db/*conn* nil db/*read-database* nil *deriving-database* loaded]
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
               _ (swap! (::kernel/program-snapshot ctx) assoc ::loaded-commit-id (db/commit-id loaded))
               acquired (acquire-program! {:seon.sci.eval/ctx ctx
                                           :seon.db/db loaded
                                           :seon.schema/projection projection})
               ;; read before the freeze renews the builder's generation
               program-namespaces {::generation (:sci/generation @(:env ctx))
                                   ::namespaces (sci/namespace-state ctx)}]
           ;; The program context holds program data only. Each fork carries its
           ;; own acquired basis and loaded commit.
           (-> (freeze-program ctx)
               (update ::kernel/program-snapshot
                       #(-> (dissoc % ::loaded-commit-id)
                            (assoc ::acquisition acquired
                                   ::program-namespaces program-namespaces
                                   :seon.test/class-loader (clojure.lang.RT/baseLoader))))
               (assoc ::acquisition acquired))))))))

(defn- cluster-base-key
  "The inputs the cluster base is a function of: the loaded program's store and
  commit, which also name its projection. The base interprets nothing, so it
  arms no wrapper over a fault recorder. Nil for an uncommitted loaded value."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :nil [:tuple :map :uuid]]]}
  [loaded]
  (when-let [commit (commit-id-of loaded)]
    [(store-identity loaded) commit]))

(defn- copy-base-ctx
  "Fork the program context `value` with the caller's basis, connection and fault recorder."
  {:malli/schema [:=> [:cat :seon.sci.eval/program-context :seon.db/database-value :seon.db/database-value
                       [:or :nil :seon.flow/commit-fault!]]
                  :seon.sci.eval/ctx]}
  [value database loaded recorder]
  (assoc (inherit-var-bindings! (sci/fork value))
         ::custody (if-let [connection (db/branch-connection database (get-in database [:config :branch]))]
                     {:seon.db/connection connection}
                     {})
         ::kernel/installed-functions (atom (::kernel/installed-functions value))
         ::kernel/program-snapshot
         (atom (cond-> (assoc (::kernel/program-snapshot value)
                              ::acquired-basis (acquired-basis database)
                              ::loaded-commit-id (db/commit-id loaded))
                 recorder (assoc :seon.flow/commit-fault! recorder)))))

(def ^:private function-bytes
  "Retained bytes per function a loaded program context binds from its
  loaded JVM Var, beyond its source text: the SCI Var, its namespace map entry,
  metadata and program row. The compiled Var and its root are retained by the
  JVM anyway. Calibrated by `seon.program-context-weight/report`."
  1280)

(def ^:private interpreted-function-bytes
  "Retained bytes per function a program context interprets from source: its
  analyzed body, SCI Var and row. Calibrated by
  `seon.program-context-weight/report`."
  3712)

(def ^:private namespace-entry-bytes
  "Retained bytes per entry of a namespace map an overlay binds anew. Calibrated
  by `seon.program-context-weight/report`."
  112)

(def ^:private overlay-bytes
  "Retained bytes of an overlay beyond its functions and namespace entries:
  its kernel state and acquisition. Calibrated by
  `seon.program-context-weight/report`."
  51200)

(defn- context-weight
  "Retained bytes of a loaded program context, its functions' source text included: what its eviction frees."
  {:malli/schema [:=> [:cat :seon.sci.eval/program-context] nat-int?]}
  [value]
  (let [functions (:functions (::kernel/program-snapshot value))]
    (+ (* function-bytes (count functions))
       (reduce-kv (fn [total _ entry] (+ total (count (::function-source entry)))) 0 functions)
       (* interpreted-function-bytes (or (::interpreted-count (::acquisition value)) 0)))))

(defn- overlay-weight
  "Retained bytes an overlay adds to the loaded context it forks: its
  interpreted functions, the namespace map entries it binds anew and its own
  state. The fork shares the rest of the loaded context, so the loaded program
  is weighed once, under its own key."
  {:malli/schema [:=> [:cat :seon.sci.eval/program-context] nat-int?]}
  [value]
  (let [{::keys [interpreted-count own-namespace-entries]} (::acquisition value)]
    (+ overlay-bytes
       (* interpreted-function-bytes (or interpreted-count 0))
       (* namespace-entry-bytes (or own-namespace-entries 0)))))

(defn- own-namespace-entries
  "The entries of `value`'s namespace maps that are not `parent`'s own maps."
  {:malli/schema [:=> [:cat :seon.sci.eval/program-context :seon.sci.eval/program-context] nat-int?]}
  [value parent]
  (let [held (:namespaces (:env parent))]
    (reduce-kv (fn [total name namespace] (if (identical? namespace (get held name)) total (+ total (count namespace))))
               0 (:namespaces (:env value)))))

(defn- derive-overlay-ctx
  "Interpret one program's `plan` on a builder forked from `parent`, the loaded
  program context `cluster` or a program context derived from it, with no
  custody or recorder, and freeze it. Its namespace entries are weighed against
  `cluster`'s."
  {:malli/schema [:function
                  [:=> [:cat :seon.sci.eval/program-context :seon.db/database-value :seon.db/database-value
                        :seon.schema/projection :map] :seon.sci.eval/program-context]
                  [:=> [:cat :seon.sci.eval/program-context :seon.sci.eval/program-context :seon.db/database-value
                        :seon.db/database-value :seon.schema/projection :map] :seon.sci.eval/program-context]]}
  ([cluster database loaded projection plan]
   (derive-overlay-ctx cluster cluster database loaded projection plan))
  ([parent cluster database loaded projection plan]
   ;; A program context is a function of its content: no init reads the
   ;; deriving thread's connection or read basis.
   (binding [db/*conn* nil db/*read-database* nil *deriving-database* database]
     (let [ctx (assoc (copy-base-ctx parent database loaded nil)
                      ::custody {} :seon.schema/projection projection)
           ;; the branch's own definitions (macros among them) first, then
           ;; main's src definitions its changes reach, analysed against them
           {own ::own namespaces ::namespaces} (plan-changes ctx plan)
           overlaid (overlay! ctx database plan)
           analysis (when (or (seq own) (seq namespaces))
                      (bind-analysed! ctx own namespaces))
           acquired (cond-> overlaid analysis (assoc ::analysis analysis))
           value (freeze-program ctx)]
       (assoc value ::acquisition (assoc acquired ::own-namespace-entries (own-namespace-entries value cluster)))))))

(defn- projection-delta
  "The digest of the schema forms and function contracts `database`'s
  projection holds otherwise than `loaded`'s, and of the keys it lacks: what an
  overlay's wrappers arm under beyond its plan, by content."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value] [:string {:min 32 :max 32}]]}
  [database loaded]
  (let [own (or (db/carried-projection database) (schema/projection-from-database database))
        held (or (db/carried-projection loaded) (schema/projection-from-database loaded))
        section (fn [k]
                  (let [own (get own k) held (get held k)]
                    [(reduce-kv (fn [delta key value]
                                  (let [prior (get held key ::absent)]
                                    (if (or (identical? value prior) (= value prior)) delta (assoc delta key value))))
                                {} own)
                     (into (sorted-set) (remove #(contains? own %)) (keys held))]))]
    (id/digest 32 [(section :seon.schema.projection/forms) (section :seon.schema.projection/function-contracts)])))

(defn- program-content-key
  "The key a program context is kept under: the loaded program's [store
  loaded-commit], a changed program's with its plan's digest, its projection's
  difference from the loaded one (`projection-delta`) and armed dials, all
  content. Nil when `loaded` is uncommitted."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value [:or :nil :map]]
                  [:or :nil [:vector :seon.schema/value]]]}
  [database loaded plan]
  (when-let [base-key (cluster-base-key loaded)]
    (if plan
      (conj base-key (::digest plan) (projection-delta database loaded) (armed-dials database))
      base-key)))

(defn- with-content-key
  "`value` naming the key it is kept under, so a context forked from it names its predecessor."
  {:malli/schema [:=> [:cat :seon.sci.eval/program-context [:or :nil [:vector :seon.schema/value]]]
                  :seon.sci.eval/program-context]}
  [value content-key]
  (cond-> value content-key (assoc-in [::kernel/program-snapshot ::program-content-key] content-key)))

(defn- predecessor-plan
  "The overlay `plan` derived from the held program context `parent` instead of
  the loaded base: `plan` with Δ's closure, when `parent` holds a derived
  program of the same loaded commit, projection and dials whose rows,
  namespaces and tests `plan` still holds and no macro is among Δ and what
  reaches it. Nil otherwise: the loaded base derives it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.sci.eval/program-context
                       [:vector :seon.schema/value] [:vector :seon.schema/value] :map]
                  [:or :nil :map]]}
  [database parent parent-key content-key plan]
  (let [previous (::plan (::acquisition parent))
        names (fn [rows k] (into #{} (map k) rows))]
    (when (and previous
               (= (count parent-key) (count content-key))
               (= (subvec parent-key 0 2) (subvec content-key 0 2))
               (= (subvec parent-key 3) (subvec content-key 3))
               (every? #(contains? (::held plan) %) (keys (::held previous)))
               (set/subset? (names (::namespaces previous) :seon.ns/name) (names (::namespaces plan) :seon.ns/name))
               (set/subset? (names (::tests previous) first) (names (::tests plan) first)))
      (let [delta (into #{} (keep (fn [[sym digest]] (when-not (= digest (get (::held previous) sym)) sym)))
                        (::held plan))
            closure (if (seq delta)
                      (seon.fn/reverse-closure {:seon.db/db database :seon.fn/seeds delta})
                      {:seon.fn/affected #{} :seon.fn/tests #{}})
            _ (when (:seon.db/invalid-read closure)
                (throw (ex-info "Cannot acquire the affected function closure." closure)))
            reach (vec (into delta (:seon.fn/affected closure)))
            macros (db/q '[:find [?sym ...] :in $ [?sym ...]
                           :where [?f :seon.fn/sym ?sym] [?f :seon.fn/macro? true]]
                         database reach)]
        (when (db/read-refusal? macros)
          (throw (ex-info (:seon.error/message macros) macros)))
        (when (empty? macros)
          (assoc plan ::closure (set (:seon.fn/affected closure)) ::closure-tests (set (:seon.fn/tests closure))))))))

(defn- derive-program-context
  "Derive `database`'s program context: the loaded program's for no `plan`,
  else the `plan` interpreted on a builder forked from the program context
  kept under `predecessor` when it holds this program but for Δ
  (`predecessor-plan`), else from the loaded one, which is kept under its own
  key. The loaded program's derivation never looks up its own key: a cache
  derivation that joins itself never completes."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.schema/projection
                        [:or :nil :map]]
                   :seon.sci.eval/program-context]
                  [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.schema/projection
                        [:or :nil :map] [:or :nil [:vector :seon.schema/value]] [:or :nil :seon.schema/value]]
                   :seon.sci.eval/program-context]]}
  ([database loaded projection plan] (derive-program-context database loaded projection plan nil nil))
  ([database loaded projection plan content-key predecessor]
   (if plan
     (let [plan (cond-> plan content-key (assoc ::produced-in content-key))
           base-key (cluster-base-key loaded)
           derive-base #(with-content-key (derive-base-ctx loaded) base-key)
           cluster (if base-key
                     (seon.cache/lookup ::program-context base-key context-weight derive-base)
                     (derive-base))
           parent (when (and content-key (vector? predecessor) (not= predecessor content-key))
                    (seon.cache/present ::program-context predecessor))
           from-parent (when parent (predecessor-plan database parent predecessor content-key plan))]
       (if from-parent
         (derive-overlay-ctx parent cluster database loaded projection from-parent)
         (derive-overlay-ctx cluster database loaded projection plan)))
     (derive-base-ctx loaded))))

(defn program-context
  "The program context of `database`'s program: a frozen SCI context holding
  exactly the program it holds, never written; only its forks evaluate.

  Matching definitions copy the loaded JVM Var root; overrides and affected
  callers interpret their admitted source. One `seon.cache` entry per program
  content (`program-content-key`), so equal programs on many branches and
  commits answer the identical value; a commit's key is read once per program
  and dial revisions. Acquisition refusals are data on it (`::acquisition`).
  `loaded` defaults to the loaded program `database`'s adoption record names.
  A miss derives from the program context kept under `predecessor`, the
  content key the acquiring context's program was kept under, when it holds
  this program but for its changed rows (`derive-program-context`); the key
  names content only, so the value is the same either way."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value] :seon.sci.eval/program-context]
    [:=> [:cat :seon.db/database-value [:maybe :seon.db/database-value]] :seon.sci.eval/program-context]
    [:=> [:cat :seon.db/database-value [:maybe :seon.db/database-value] [:maybe :seon.schema/value]]
     :seon.sci.eval/program-context]]}
  ([database] (program-context database nil nil))
  ([database supplied-loaded] (program-context database supplied-loaded nil))
  ([database supplied-loaded predecessor]
   (let [projection (or (db/carried-projection database) (schema/projection-from-database database))
         loaded #(or supplied-loaded (loaded-program database))
         revisions (program-revisions database projection)
         plan (delay (program-changes database (loaded) projection))
         content-key #(program-content-key database (loaded) @plan)
         ;; program revisions cover the adoption record and the projection;
         ;; dial revisions cover what the wrappers arm with
         program-key (if revisions
               (seon.cache/lookup ::program-key
                                  [(store-identity database) revisions
                                   (db/content-revisions database (vec (sort (config/dial-attributes projection))))
                                   (some-> supplied-loaded db/commit-id)]
                                  (constantly 1024) content-key)
               (content-key))
         derive-value #(with-content-key
                         (derive-program-context database (loaded) projection @plan program-key predecessor)
                         program-key)
         weigh #(if @plan (overlay-weight %) (context-weight %))
         value (if program-key
                 (seon.cache/lookup ::program-context program-key weigh derive-value)
                 (derive-value))]
     (if (identical? projection (:seon.schema/projection value))
       value
       (assoc value :seon.schema/projection projection)))))

(defn base-ctx
  "A fork of `database`'s program context (`program-context`) with the
  caller's custody, basis and fault recorder.

  Acquisition refusals remain values on the context; the caller holding custody
  records them (`record-refusals-once!`) and restores no private state. Every caller receives its own
  SCI and kernel atoms."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value] :seon.sci.eval/ctx]
    [:=> [:cat :seon.db/database-value
          [:map [:seon.flow/commit-fault! {:optional true} :seon.flow/commit-fault!]
           [::predecessor {:optional true} :seon.schema/value]]]
     :seon.sci.eval/ctx]]}
  ([database] (base-ctx database {}))
  ([database arm-request]
   (let [supplied (::loaded-database arm-request)
         source (program-context database supplied (::predecessor arm-request))
         loaded (or supplied (loaded-program database))
         dials (armed-dials database)
         recorder (:seon.flow/commit-fault! arm-request)
         ctx (copy-base-ctx source database loaded recorder)
         acquired (::acquisition source)]
     (let [input (program-key-input database ctx)]
       (swap! (::kernel/program-snapshot ctx)
              #(cond-> (assoc % ::acquisition acquired ::armed-dials dials) input (assoc ::program-key input))))
     (assoc ctx ::acquisition acquired))))

(defn- loaded-basis
  "The revision of the adoption record's attribute in the loaded source.

  Datahike advances an attribute's revision exactly when a commit touches it
  (`seon.db/content-revisions`), so an equal basis proves the record did not move."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:or :nil [:map-of :qualified-keyword :uuid]]]}
  [source]
  (db/content-revisions source [:seon.source/commit-id]))

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
    (or (and basis (= (::loaded-basis snapshot) basis))
        (when (= (::loaded-commit-id snapshot)
                 (loaded-commit-id source))
          (swap! (::kernel/program-snapshot ctx) assoc ::loaded-basis basis)
          true)
        false)))

(defn acquired-database?
  "Whether this context already acquired the program the supplied value holds.

  True for every committed value whose program revisions (`program-revisions`)
  equal the acquired value's: a commit that wrote only receipts, turns,
  messages, test results or artifacts leaves them alone, and a branch opened at
  a commit carries that commit's revisions. Dial values the wrappers armed with
  must also be unchanged. A value without committed revisions never matches."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value] :boolean]}
  [ctx database]
  (let [snapshot @(::kernel/program-snapshot ctx)
        acquired (get-in snapshot [::acquired-basis ::program-revisions])]
    (boolean
     (and (::acquisition snapshot)
          acquired
          ;; An adoption moves the JVM's loaded program without touching this
          ;; branch's rows; an acquisition compared against the older one is stale.
          (loaded-program-current? ctx database)
          ;; A settings write re-arms only when it moved a value the
          ;; context's interpreted wrappers armed with.
          (= (::armed-dials snapshot) (armed-dials database))
          ;; Only revisions are compared, never the acquired value's index: a
          ;; collection reclaims the nodes of a value no branch head reaches.
          (= acquired (program-revisions database (db/carried-projection database)))))))

(defn program-equal
  "The fork request member stating that the fork source holds `database`'s
  program: a caller that proved it (a test world built as its candidate's
  program) forks without acquiring `database` again (`fork-cluster-ctx`)."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:map [::acquired-basis :map]]]}
  [database]
  {::acquired-basis (acquired-basis database)})

(defn record-refusals-once!
  "Record the acquisition refusals of `acquired` not yet recorded to this target.

  A refusal is a fact about the production that made it (`::produced-in`, the
  content key of the derivation that installed its row), so another context,
  another commit or a derivation carrying it records nothing again. Each is
  keyed `[produced-in row target]`; concurrent acquirers share the one entry and
  no monitor is held. The fresh refusals record as one transaction. A refusal
  with no production key records every time."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :map
                       [:or :nil :seon.flow/commit-fault!]] :map]}
  [ctx database acquired commit-fault!]
  (let [;; The target's identity, never the Connection object: a released
        ;; Connection keeps its metadata and so its whole world reachable.
        ;; `db/connection-identity` is the dependency's connection key, or the
        ;; typed unavailable result for released custody.
        connection (:seon.db/connection (::custody ctx))
        target (if connection (db/connection-identity connection) commit-fault!)
        refusals? (or (seq (::acquisition-refusals acquired)) (seq (::agent-mistakes acquired)))]
    (cond
      (not refusals?) (record-acquisition-refusals! ctx database acquired commit-fault!)

      (and connection (not (:datahike/connection-id target)))
      (assoc acquired ::acquisition-refusals-recorded? false
             ::acquisition-recording-error target)

      target
      (let [fresh? (fn [refusal]
                     (let [claimed (volatile! false)]
                       (if-let [produced (::produced-in refusal)]
                         (do (seon.cache/lookup ::refusal-recording [produced (acquisition-refusal-id refusal) target]
                                                (constantly 512) #(vreset! claimed true))
                             @claimed)
                         true)))
            fresh (-> acquired
                      (update ::acquisition-refusals #(filterv fresh? %))
                      (update ::agent-mistakes #(filterv fresh? %)))]
        (merge (assoc acquired ::acquisition-refusals-recorded? true)
               (select-keys (record-acquisition-refusals! ctx database fresh commit-fault!)
                            [::acquisition-refusals-recorded? ::acquisition-recording-error])))

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
    (let [snapshot @(::kernel/program-snapshot ctx)
          input (program-key-input database ctx)]
      (if (or (and input (= input (::program-key snapshot))) (acquired-database? ctx database))
        (do
          (when (and input (not= input (::program-key snapshot)))
            (swap! (::kernel/program-snapshot ctx) assoc ::program-key input))
          ;; Same program: the acquired value advances to the supplied one, so
          ;; a branch opened at it holds the acquired program by commit id.
          (when-not (= (acquired-basis database) (::acquired-basis snapshot))
            (swap! (::kernel/program-snapshot ctx) assoc ::acquired-basis (acquired-basis database))
            (advance-context-projection! ctx database (context-projection ctx)))
          (::acquisition snapshot))
        (let [commit-fault! (or commit-fault! (:seon.flow/commit-fault! snapshot))
              source (loaded-source snapshot database)
              generated (base-ctx database
                                  (cond-> {::loaded-database (loaded-program source)}
                                    (::program-content-key snapshot)
                                    (assoc ::predecessor (::program-content-key snapshot))
                                    commit-fault!
                                    (assoc :seon.flow/commit-fault! commit-fault!)))
              acquired (::acquisition generated)
              ;; This context's own definitions (the Vars it wrote that no
              ;; program install bound) survive as objects with its bindings of
              ;; them, as `regenerate-agent-context!` carries them for a turn
              ;; fork, unless the program changed their symbol's row. A test the
              ;; program interprets from its row is the program's: this
              ;; context's Var of it was analyzed against Vars the program
              ;; replaced, so carrying it would call their stale roots.
              installed @(::kernel/installed-functions ctx)
              interns (turn-interns ctx)
              generated-snapshot @(::kernel/program-snapshot generated)
              replaced (replaced-definitions snapshot generated-snapshot (keys interns))
              program-tests (set (::interpreted-tests generated-snapshot))
              own (into [] (keep (fn [[qualified {sci-var :seon.sci.eval/var}]]
                                   (when-not (or (and (contains? installed qualified)
                                                      (not (contains? (::context-defined snapshot) qualified)))
                                                 (contains? replaced qualified)
                                                 (contains? program-tests qualified))
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
            (when-let [input (program-key-input database ctx)]
              (swap! (::kernel/program-snapshot ctx) assoc ::program-key input))
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
                    ::custody (if-let [connection (or connection (db/branch-connection db (get-in db [:config :branch])))]
                                {:seon.db/connection connection}
                                {}))
         _ (swap! (::kernel/program-snapshot ctx) merge
                  {::loaded-commit-id (db/commit-id (or (::loaded-database arm-request)
                                                   (loaded-program (loaded-source arm-request db))))}
                  (select-keys arm-request
                               [:seon.flow/commit-fault! ::loaded-connection]))
         projection-state (or supplied-projection-state
                              (projection-state db projection (carried-cluster-name connection db)))
         ctx (env/carry-state
              (assoc ctx :seon.schema/projection projection)
              projection-state)
         ;; `my.program/supplied-context` names the cluster base it mutates.
         ctx (assoc ctx :my.program/base-ctx ctx)]
     ctx))

(defn- receiving-state
  "The environment state of a context receiving `connection`'s branch: the
  supplied or `source`'s environment with that connection, basis and
  `projection`, carried by the connection too."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :seon.db/connection
                       [:maybe :seon.sci.eval/projection-state] :map :seon.schema/projection]
                  :seon.sci.eval/projection-state]}
  [source db connection supplied-projection-state arm-request projection]
  (let [state (env/environment-state
               (env/refuse-incomplete-environment!
                (env/environment
                 (assoc (dissoc (or (:seon.env/environment arm-request)
                                    (some-> supplied-projection-state deref)
                                    (env/of source))
                                :seon.db/db :seon.agent/id :seon.turn/id
                                :my.program/executing-ctx :seon.sci.kernel/arm)
                        :seon.db/connection connection
                        :seon.db/basis-t (db/basis-t db)
                        :seon.schema/projection projection
                        :seon.sci.eval/loaded-identity loaded-identity))))]
    (db/carry-connection-projection-state! connection state)
    state))

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
           [:seon.sci.eval/loaded-connection {:optional true} :seon.db/connection]
           [:seon.sci.eval/acquired-basis {:optional true} :map]]]
     :seon.sci.eval/ctx]]}
  ;; The receiving branch's environment is the one its connection carries, its
  ;; holder's registry included; a connection no execution holds yet receives
  ;; the base context's environment.
  ([base-ctx db connection]
   (let [environment (or (some-> (:seon.sci.eval/projection-state (meta connection)) deref)
                         (env/of base-ctx))]
     (fork-cluster-ctx base-ctx db connection
                       (projection-state db (schema/projection-from-database db)
                                         (or (:seon.boot/cluster-name environment)
                                             (carried-cluster-name connection db)))
                       (if environment
                         {:seon.env/environment environment}
                         {}))))
  ([base-ctx db connection supplied-projection-state]
   (fork-cluster-ctx base-ctx db connection supplied-projection-state {}))
  ([base-ctx db connection supplied-projection-state arm-request]
   (let [projection (or (:seon.schema/projection
                         (some-> supplied-projection-state deref))
                        (schema/projection-from-database db))
         projection-state (receiving-state base-ctx db connection supplied-projection-state
                                           arm-request projection)
         ctx (env/carry-state
               (assoc (inherit-var-bindings! (sci/fork base-ctx))
                      ::kernel/installed-functions
                      (atom @(::kernel/installed-functions base-ctx))
                      ::kernel/program-snapshot
                      (atom (merge (dissoc @(::kernel/program-snapshot base-ctx)
                                           :seon.flow/commit-fault!)
                                   (select-keys arm-request
                                                [:seon.flow/commit-fault! ::loaded-connection
                                                 ::acquired-basis])))
                      ::custody {:seon.db/connection connection}
                      :seon.schema/projection projection)
               projection-state)
         ctx (assoc ctx :my.program/base-ctx ctx)]
     ;; A sovereign branch must not inherit the source branch's recorder.
     ;; bind-root! in the fork writes the fork's own binding of each Var. The
     ;; loaded program's analysed definitions are armed as compiled, with no
     ;; recorder (`armed-as-compiled`).
     (sci.ctx-store/with-ctx ctx
       (doseq [function-symbol @(::kernel/installed-functions ctx)
               :let [candidate (sci/resolve ctx function-symbol)]
               :when (and candidate
                          (::instrument/interpreted-original (meta @candidate))
                          (not (kernel/program-definition? ctx function-symbol @candidate)))]
         (install-function-contract!
          ctx (db/pull db '[*] [:seon.fn/sym function-symbol]) projection db)))
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
                  (not= (db/commit-id db) (get-in snapshot [::acquired-basis :seon.source/commit-id]))))
       (when-let [failure (::acquisition-recording-error
                           (acquire! {:seon.sci.eval/ctx ctx :seon.db/db db}))]
         (throw (ex-info "Program acquisition could not record its refusals."
                         {::acquisition-recording-error failure}))))
     ctx)))

(defn branch-ctx
  "The program context of a fresh branch, from one fork: `source` itself when
  it already acquired the program `db` holds (`fork-cluster-ctx`), else
  `base-ctx`'s own copy of that program. Either has `connection`'s custody and
  the receiving environment, follows the loaded program `source` follows, and
  carries its acquisition."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :seon.db/connection
                       :seon.sci.eval/projection-state
                       [:map [:seon.env/environment {:optional true} :seon.env/environment]]]
                  :seon.sci.eval/ctx]}
  [source db connection supplied-projection-state arm-request]
  (if (acquired-database? source db)
    (let [ctx (fork-cluster-ctx source db connection supplied-projection-state arm-request)]
      (assoc ctx ::acquisition (::acquisition @(::kernel/program-snapshot ctx))))
    (let [projection (or (:seon.schema/projection (some-> supplied-projection-state deref))
                         (schema/projection-from-database db))
          projection-state (receiving-state source db connection supplied-projection-state
                                            arm-request projection)
          held @(::kernel/program-snapshot source)
          ctx (env/carry-state
               (assoc (base-ctx db (cond-> {::loaded-database (loaded-program (loaded-source held db))}
                                     (::program-content-key held)
                                     (assoc ::predecessor (::program-content-key held))))
                      ::custody {:seon.db/connection connection}
                      :seon.schema/projection projection)
               projection-state)]
      ;; The JVM's loaded program is followed through the source's connection.
      (when-let [loaded-connection (::loaded-connection held)]
        (swap! (::kernel/program-snapshot ctx) assoc ::loaded-connection loaded-connection))
      (let [recorded (record-refusals-once! ctx db (::acquisition ctx)
                                            (:seon.flow/commit-fault! @(::kernel/program-snapshot ctx)))
            ctx (assoc ctx ::acquisition recorded)]
        (swap! (::kernel/program-snapshot ctx) assoc ::acquisition recorded)
        (assoc ctx :my.program/base-ctx ctx)))))

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
        events (sci/binding [sci/ns (context-namespace ctx namespace-name)]
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
  {:seon/entrant true
   :malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
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
          (let [projection (render.value/show
                            (cond-> (assoc (dissoc request :seon.cluster.eval/output)
                                           :seon.render/value value
                                           :seon.render/output :seon.render/ai
                                           :seon.render/outputs #{:text}
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
                     (:seon.render.value/text projection)))])
          (catch Throwable failure
            [{:seon.render.value/diagnostics [{:seon.render.data/path [] :seon.render.value/failure failure}]}
             (render.value/core-text value)]))
        failures (into failures (map :seon.render.value/failure) (:seon.render.value/diagnostics projection))
        ;; a critical heap crossing seen while the evaluation ran rides its reply
        failures (cond-> failures (:seon.heap/crossing record) (conj (:seon.heap/crossing record)))
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
             ;; the one entry's token size, when the shown text is its text
             :seon.eval/shown-tokens (or (when-not (:seon.render.value/value-text projection)
                                           (:seon.render.value/tokens projection))
                                         (tokens/estimate shown))}
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
  3. arm through `kernel/with-arm` with `::time-limit-ms` and the caps'
     `:seon.config.eval/alloc-limit-bytes` —
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
  observed — is present exactly when a bound cut it: the time limit, or the
  allocation bound `:seon.config.eval/alloc-limit-bytes` its caps carry. The record
  rides through with `fn-entries` and `allocated-bytes` intact."
  {:seon/entrant true
   :malli/schema [:=> [:cat :seon.sci.eval/request]
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
                              (context-database base-evaluation-ctx))]
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
        namespace-object (context-namespace evaluation-ctx namespace-name)
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
       (db/with-branch
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
                failure (kernel/failure-value
                         (cond-> {::kernel/time-limit-kind ::time-limit
                                  ::kernel/failure-kind ::evaluation-failed
                                  ::form (::form request)}
                           (:seon.config.eval/alloc-limit-bytes caps)
                           (assoc :seon.config.eval/alloc-limit-bytes (:seon.config.eval/alloc-limit-bytes caps)))
                         throwable record)
                {:keys [line column]} (ex-data throwable)
                ;; a guessed name: the locals in scope where SCI failed to
                ;; resolve it, for the reply's nearest names (absent when none)
                locals (when (and (get-in failure [:seon.error/data :seon.sci.eval/symbol])
                                  (pos-int? line) (pos-int? column))
                         (not-empty (error/locals-at (::form request) line column)))
                value (cond-> failure
                        arity-message (assoc :seon.error/message arity-message)
                        locals (assoc-in [:seon.error/data :seon.sci.eval/locals] locals))
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
             ;; presence IS the interrupted state; the record's outcome
             ;; names the bound that cut it (:time or :alloc).
             (#{:time :alloc} (:seon.eval/outcome record))
             (assoc :seon.cluster.eval/interrupted-at
                    (java.util.Date.)))))))]
       (try
        (kernel/with-arm
       evaluation-ctx time-limit-ms (:seon.config.eval/alloc-limit-bytes caps)
       (fn [armed]
        (vreset! arm-state armed)
        ;; the running-evaluation registry names the form in flight
        (kernel/evaluating! (::form request))
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
