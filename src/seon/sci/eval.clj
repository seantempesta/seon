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
  other's guard semantics, so they cannot drift: work reached while this
  context is already armed on this thread inherits that arm and its
  deadline, and a different context on an armed thread is refused as an
  ordinary flat error value.

  AN AGENT EVALUATES IN ITS ASSIGNED NAMESPACE, by construction. The eval
  reads `:seon.agent/namespace` from the database and binds sci's
  own `*ns*` there for the whole form, so a `defn` lands where the prompt
  says it lands and the model never needs to write `(in-ns …)` — which is
  what the first live drive tried, and what failed with `Can't
  change/establish root binding of clojure.core/*ns*`. The assignment fact
  is the authority; `my.agents.<id>` is only a creation-time default.

  OUTPUT IS CAPTURED, NOT DISCARDED. sci's `*out*` and `*err*` are
  unbound by default — `println` fails with `SciUnbound cannot be cast
  to java.io.Writer` — so both are bound to one `StringWriter` per
  evaluation and what the form printed rides back on the evaluation as
  `:seon.cluster.eval/output`, bounded by the same `max-string` cap the
  projection uses. Printed output is evidence an agent produced about
  its own work; a sink would have made it disappear, and the drive
  showed exactly how expensive disappeared evidence is.

  THE CLUSTER OWNS THE LIVE BASE CTX. Each agent retains its own context
  and receives accepted base changes before later turns. The turn's install
  entrance evaluates function declarations in a generation-aware candidate
  fork; only accepted function roots enter the retained context. Ordinary
  forms use that retained context directly. This raw form entrance uses a
  supplied context as given; cluster boot acquires the program-only base.
  When no ctx is supplied a fresh base is made for an isolated one-off.

  THE CTX IS SUPPLIED, NOT BUILT HERE. `build-base-ctx` is the minimum
  N3 needs —
  `clojure.core` and `clojure.string` in their interrupt-aware form
  plus bare `help`, the two `my.turn` dispositions, and both `my.message`
  values — and a caller may pass its own. `acquire!` then intersects core-provenanced
  program namespaces with the JVM's loaded namespace set and binds their
  actual compiled Vars. The set is computed, never listed. Agent-authored
  program rows retain the interpreted installation path after those host
  bindings are present. `cluster-ctx` performs that fact-derived install
  only at cluster boot or recovery; the turn path never reacquires it.

  Crash walk: this namespace owns no durable state. A kill during an
  evaluation leaves the loop's running receipt (one with no terminal
  fact), which N2's `recover-tx` settles by asserting its
  `interrupted-at` — and rows 6 and 7 of the crash walk stay
  indistinguishable, which is honest: the form's effect MAY have
  happened. Nothing re-executes."
  (:require [clojure.edn :as edn]
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
            [sci.core :as sci]
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
            [seon.instrument :as instrument]
            [seon.program :as program]
            [seon.render :as render]
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

(defn projection-state?
  "True for the replacement reference holding one immutable environment."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (env/environment-state? value))

(schema/register-core-predicate! 'seon.sci.eval/projection-state?
                                 projection-state?)

(def projection-state-generator env/environment-state-generator)

(defonce ^:private generator-ctx
  (delay (sci/init {})))

(def ctx-generator
  "A real ctx — honest by constructing an instance."
  (gen/fmap (fn [_] @generator-ctx) (gen/return nil)))

(schema.edn/load! {})

(defn projection-state
  "Create a cluster context's immutable environment replacement reference."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.schema/projection]
    :seon.sci.eval/projection-state]}
  [db projection]
  (env/environment-state
   (env/refuse-incomplete-environment!
    (env/environment {:seon.boot/cluster-name "current-src"
                      :seon.db/basis-t (db/basis-t db)
                      :seon.schema/projection projection}))))

;;; ---------------------------------------------------------------------------
;;; The base context
;;; ---------------------------------------------------------------------------

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
                                  (sci/copy-var*
                                   (or (when-let [host (find-ns (symbol (namespace qualified)))]
                                         (ns-resolve host (symbol (name qualified))))
                                       (throw (ex-info
                                               (str "The loaded JVM has no interpreter binding " qualified ".")
                                               (error/diagnostic
                                                {:seon.error/at (java.util.Date.)
                                                 :seon.error/layer :seon.sci.eval/program
                                                 :seon.error/operation 'seon.sci.eval/build-base-ctx
                                                 :seon.error/message "Interpreter binding is unavailable; load its declared JVM namespace."
                                                 :seon.error/offending qualified
                                                 :seon.error/diagnostic-layer :seon.sci.eval/program
                                                 :seon.error/diagnostic-operation 'seon.sci.eval/build-base-ctx
                                                 :seon.error/diagnostic-member qualified
                                                 :seon.error/diagnostic-expected :seon.sci.eval/loaded-binding
                                                 :seon.error/diagnostic-offending qualified
                                                 :seon.error/diagnostic-cause :seon.error/unknown
                                                 :seon.error/diagnostic-evidence qualified
                                                 ::row-member qualified
                                                 ::acquisition-observation {:seon.error.evidence/attribute :seon.fn/sym
                                                                            :seon.error.evidence/value qualified}}))))
                                   sci-namespace)]))
                          symbols)])))
              (group-by (comp symbol namespace)
                        (program/base-context-injected-symbols (:seon.schema.projection/forms projection))))
        ctx
        (sci/init
        {:interrupt-fn (:interrupt-fn kernel-options)
         :host-interop-observer (:host-interop-observer kernel-options)
         :built-in-call-observer (:built-in-call-observer kernel-options)
         ;; The one call-preparation seam. It is installed on EVERY
         ;; context, base and cluster alike, and stays inert until a ctx
         ;; also carries `seon.call-preparation/install`'s state plus an
         ;; environment — so a scratch base ctx behaves exactly as it did
         ;; before, and a cluster ctx prepares every direct Var call.
         :call-preparation-hook
         (fn [ctx callee arguments]
           (or ((requiring-resolve 'my.program/native-call-refusal)
                ctx callee arguments)
               (call-preparation/hook ctx callee arguments)))
         ;; the interrupt-aware core: a lazy sequence built by NATIVE
         ;; clojure.core enters no interpreted body, so `(range)` inside
         ;; `reduce` would never hit the interrupt-fn. Sci ships drop-in
         ;; alternatives for exactly this and they are opt-in
         ;; (interrupt.md:56-60).
         :namespaces
         (merge {'clojure.core sci.interrupt/clojure-core
                 'clojure.string sci.interrupt/clojure-string}
                injected-namespaces)
         ;; two broad roots rather than an enumeration of exception
         ;; subclasses — an agent needs to catch things, not to be given
         ;; a curated taxonomy
         :classes {'Throwable Throwable
                   'java.lang.Throwable Throwable
                   'Error Error
                   'java.lang.Error Error}})
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

(defn- row
  "Return reader-owned rows; Var definitions are derived after evaluation."
  [event _projection]
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
     (program/declaration-row event :contracted :agent))))

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

(defn- intern-values
  "Dereferenced SCI intern roots keyed by qualified name."
  [ctx]
  (let [namespace-state (sci/namespace-state ctx)]
    (into {}
          (mapcat
           (fn [[namespace-name intern-names]]
             (keep
              (fn [intern-name]
                (let [sci-var (get-in namespace-state
                                      [namespace-name intern-name])]
                  (when (sci.utils/var? sci-var)
                    [(symbol (str namespace-name) (str intern-name))
                     (if (sci.vars/hasRoot sci-var)
                       @sci-var
                       absent-intern)])))
              intern-names))
           (sci/namespace-interns ctx)))))

(defn- turn-interns
  "Vars and dereferenced roots owned by this SCI fork's generation.

  `def`, `intern`, and inherited-root writes stamp SCI's current fork
  generation (`reference-code/sci/src/sci/impl/evaluator.cljc:25-49` and
  `sci/impl/utils.cljc:359-379`). Host Vars copied into the context by system
  namespace installation carry no such stamp. This is the definition-event
  provenance for the agent's defs: namespace membership never decides authorship.

  Values rather than Var identities are retained because redefinition may
  mutate an already generation-owned Var root. Values are not traversed."
  [ctx]
  (let [namespace-state (sci/namespace-state ctx)
        values (intern-values ctx)
        turn-generation (:sci/generation @(:env ctx))]
    (if-not turn-generation
      {}
      (into {}
            (keep
             (fn [[qualified value]]
               (let [sci-var
                     (get-in namespace-state
                             [(symbol (namespace qualified))
                              (symbol (name qualified))])]
                 (when (= turn-generation (:sci/generation (meta sci-var)))
                   [qualified {:seon.sci.eval/var sci-var
                               :seon.sci.eval/value value}]))))
            values))))

(defn- turn-intern-values
  [ctx]
  (into {} (map (fn [[qualified entry]]
                  [qualified (:seon.sci.eval/value entry)]))
        (turn-interns ctx)))

(defn- same-intern-value?
  [left right]
  (or (identical? left right)
      (and (not (identical? absent-intern left))
           (not (identical? absent-intern right))
           (= (class left) (class right))
           (= (meta left) (meta right))
           (= left right))))

(defn- definition-row
  "Analyze the accepted source; SCI contributes only runtime contract facts."
  [ctx database projection before source namespace-row]
  (some
   (fn [[qualified {sci-var :seon.sci.eval/var
                    value :seon.sci.eval/value}]]
     (when (and (not (identical? absent-intern value))
                (not (same-intern-value?
                      (get before qualified absent-intern) value)))
       (let [metadata (meta sci-var)
             analysed-row
             (when (or (:test metadata) (fn? value))
               (some #(when (= qualified
                               (or (:seon.fn/sym %) (:seon.test/sym %))) %)
                     (seon.fn/source-rows
                      database
                      (program/shapes-in (:seon.schema.projection/forms projection))
                      namespace-row source
                      (set (keys (:seon.schema.projection/forms projection))))))
             event
             (when analysed-row
               (cond-> analysed-row
                 (:malli/schema metadata)
                 (assoc :seon.fn/spec
                        (pr-str (accretion/data-contract! (:malli/schema metadata))))
                 (contains? #{:io :compute} (:seon.workload metadata))
                 (assoc :seon.fn/workload (:seon.workload metadata))
                 (:test metadata)
                 (merge (program/test-markers metadata (meta (:ns metadata))))))
             row (when event
                   (assoc event ::evaluated? true))]
         (if (:seon.fn/spec row)
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
           row))))
   (sort-by (comp str key) (turn-interns ctx))))

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
  (let [events (reader/read
                (assoc (reader-context ctx namespace-name)
                       :seon.sci.reader/text source
                       :seon.config.eval.result/max-source max-source))]
    (cond
      (map? events)
      (throw (ex-info (:seon.error/message events) events))

      ;; The reader recovers around a malformed form and returns it as an
      ;; event carrying its flat error with a placeholder form. Evaluation
      ;; refuses that event exactly as it refuses a whole-input failure —
      ;; the placeholder must never run as if it were the agent's form.
      (and (= 1 (count events))
           (:seon.sci.reader/error (first events)))
      (let [failure (:seon.sci.reader/error (first events))]
        (throw (ex-info (:seon.error/message failure) failure)))

      (= 1 (count events))
      (first events)

      (empty? events)
      (throw (ex-info "Your reply had no form; only comments/prose. Send a form."
                      {:seon.error/at (java.util.Date.)
                       :seon.error/layer :seon.sci.eval/reader
                       :seon.error/operation 'seon.sci.eval/one-event
                       ::reader-event-count 0}))

      :else
      (throw (ex-info "Evaluation requires exactly one reader event."
                      {:seon.error/at (java.util.Date.)
                       :seon.error/layer :seon.sci.eval/reader
                       :seon.error/operation 'seon.sci.eval/one-event
                       ::reader-event-count (count events)})))))

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

(defn- namespace-context-row
  [namespace-name source before after changed?]
  (when (or changed?
            (not= (select-keys before [:seon.sci.reader/aliases
                                      :seon.sci.reader/refers
                                      ::imports ::requires])
                  (select-keys after [:seon.sci.reader/aliases
                                     :seon.sci.reader/refers
                                     ::imports ::requires])))
    (program/declaration-row
     (merge
      {:seon.ns/name namespace-name
       :seon.ns/source source}
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
               [?config :seon.config/cluster ?cluster]
               [?config :seon.config/on-core-error _]]
             db)]
    (if cluster-name
      (config/effective db cluster-name)
      {:seon.error/at (java.util.Date.)
       :seon.error/layer :seon.config/read
       :seon.error/operation 'seon.sci.eval/database-effective-config
       :seon.config/error-key :seon.boot/cluster-name
       :seon.error/expected-key :seon.boot/cluster-name
       :seon.error/message
       "seon.sci.eval/database-effective-config requires configuration naming its cluster; this database has none."})))

(defn- instrumentation-config
  "Read the contract dial and admission caps from this database value.

  A database with no admissible caps cannot arm a contract in either dial.
  The caps member is whatever
  `result-caps` returned: the caps map, or the refusal naming the first
  config key it wanted. `instrument/wrap-interpreted` is the one seam that
  decides what to do with each."
  [db]
  (let [effective (database-effective-config db)]
    (merge {:seon.config/on-core-error
            (or (:seon.config/on-core-error effective) :record)
            :seon.sci.admit/caps (config/result-caps effective)}
           (select-keys effective [:seon.config.error/max-evidence-bytes]))))

(defn- install-function-contract!
  [ctx committed projection db]
  (when-let [spec-edn (:seon.fn/spec committed)]
    (let [function-symbol (:seon.fn/sym committed)
          sci-var (sci/resolve ctx function-symbol)
          {:keys [:seon.config/on-core-error :seon.sci.admit/caps
                  :seon.config.error/max-evidence-bytes]}
          (instrumentation-config db)]
      (sci/bind-root!
       ctx sci-var
       (instrument/wrap-interpreted
        function-symbol spec-edn projection on-core-error caps @sci-var
        (cond-> (select-keys @(::kernel/program-snapshot ctx)
                             [:seon.flow/commit-fault!])
          max-evidence-bytes
          (assoc :seon.config.error/max-evidence-bytes max-evidence-bytes))))))
  nil)

(declare install-declared-classes!)

(defn- install-function-from-database!
  "Install one selected function from the acquired database snapshot."
  [ctx db function-symbol]
  (let [{source ::function-source
         namespace-name ::function-namespace}
        (kernel/program-function ctx function-symbol)]
    (when-not source
      (throw
       (ex-info "Selected function is missing from the acquired SCI program snapshot."
                (error/diagnostic
                 {:seon.error/at (java.util.Date.)
                  :seon.error/layer :seon.sci.eval/program
                  :seon.error/operation 'seon.sci.eval/install-function-from-database!
                  :seon.error/message "Selected function has no acquired row; acquire its declaration before installing it."
                  :seon.error/offending function-symbol
                  :seon.error/diagnostic-layer :seon.sci.eval/program
                  :seon.error/diagnostic-operation 'seon.sci.eval/install-function-from-database!
                  :seon.error/diagnostic-member :seon.fn/sym
                  :seon.error/diagnostic-expected :seon.sci.eval/acquired-function
                  :seon.error/diagnostic-offending function-symbol
                  :seon.error/diagnostic-cause :seon.error/unknown
                  :seon.error/diagnostic-evidence function-symbol
                  ::missing-function-row function-symbol :seon.fn/sym function-symbol}))))
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
         (context-projection ctx) db)))
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
               (:seon.schema.projection/forms
                (or (db/carried-projection db)
                    (schema/projection-from-database db))))
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
                             (sci/copy-var* host-var sci-namespace)})
        (kernel/mark-installed! ctx function-symbol)
        true))))

(defn install-row!
  "Install one declaration from the terminal transaction's db-after.
  The exact committed row is resolved by identity; saved evaluation results
  are never consulted."
  {:malli/schema [:=> [:cat :seon.sci.eval/install-request] :map]}
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
                       (program/shapes-in (:seon.schema.projection/forms projection))
                       identity-attribute))]
                 (not (same-declaration-source?
                       (get row source-attribute)
                       (get committed source-attribute)))))
      (throw (ex-info "Committed declaration source does not match install request."
                      (error/diagnostic
                       {:seon.error/at (java.util.Date.)
                        :seon.error/layer :seon.sci.eval/program
                        :seon.error/operation 'seon.sci.eval/install-row!
                        :seon.error/message "Install source differs from the committed declaration; install the committed source."
                        :seon.error/offending row
                        :seon.error/diagnostic-layer :seon.sci.eval/program
                        :seon.error/diagnostic-operation 'seon.sci.eval/install-row!
                        :seon.error/diagnostic-member identity-attribute
                        :seon.error/diagnostic-expected :seon.sci.eval/committed-source
                        :seon.error/diagnostic-offending row
                        :seon.error/diagnostic-cause :seon.error/unknown
                        :seon.error/diagnostic-evidence row
                        :seon.sci.eval/installation-member value}))))
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
        (let [state
              (if (= :core admission)
                (if (install-jvm-root! ctx function-symbol)
                  :jvm
                  :unavailable)
                (try
                  (if (or evaluated? (::evaluated? row))
                    (do
                      (kernel/mark-installed! ctx function-symbol)
                      (when (:seon.fn/spec committed)
                        (install-function-contract! ctx committed next-projection db)))
                    (install-function-from-database! ctx db function-symbol))
                  :interpreted
                  (catch Throwable failure
                    (when (:seon.instrument/registration-observation (error/refusal failure))
                      (throw failure))
                    (if (install-jvm-root! ctx function-symbol)
                      {:seon.sci.eval/load-state :jvm-fallback
                       :seon.fn/sym (:seon.fn/sym committed)
                       :seon.error/message (or (.getMessage failure) (str (class failure)))}
                      (throw failure)))))]
          {:seon.schema/projection next-projection
           :seon.sci.eval/installed (if (= :unavailable state) 0 1)
           :seon.sci.eval/load-state
           (if (map? state) (:seon.sci.eval/load-state state) state)
           :seon.sci.eval/load-result
           (if (map? state) state
               {:seon.sci.eval/load-state state
                :seon.fn/sym (:seon.fn/sym committed)})}))

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
                          (error/diagnostic
                           {:seon.error/at (java.util.Date.)
                            :seon.error/layer :seon.sci.eval/program
                            :seon.error/operation 'seon.sci.eval/install-row!
                            :seon.error/message "Deleted declaration still has definition facts; commit its retraction before installation."
                            :seon.error/offending remaining
                            :seon.error/diagnostic-layer :seon.sci.eval/program
                            :seon.error/diagnostic-operation 'seon.sci.eval/install-row!
                            :seon.error/diagnostic-member :seon.program/definition-attributes
                            :seon.error/diagnostic-expected :seon.sci.eval/retracted-declaration
                            :seon.error/diagnostic-offending remaining
                            :seon.error/diagnostic-cause :seon.error/unknown
                            :seon.error/diagnostic-evidence remaining
                            :seon.sci.eval/installation-member (second (:seon.program/identity remaining))}))))
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
      (advance-context-projection!
       ctx db (:seon.schema/projection installed))
      installed)))

(defn- evaluate-native!
  "Execute an already-admitted program operation in its supplied SCI context.
   Only this native operation skips preparation, never an agent evaluation."
  [ctx form]
  (sci/eval-form (assoc ctx :call-preparation-hook nil) form))

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

(declare base-bindings)

(defn acquired-program
  "Return the database and refusal report actually acquired by this context.

  Evidence follows the existing program snapshot through reacquisition and
  forks. A resolvable Var alone is not evidence of an acquired program."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx] :seon.test/acquisition]}
  [ctx]
  (let [snapshot (some-> (::kernel/program-snapshot ctx) deref)]
    (assoc (select-keys snapshot [:seon.db/db :seon.test/class-loader])
           :seon.test/acquisition-refusals
           (vec (get-in snapshot [::acquisition ::acquisition-refusals])))))

(defn- installation-covers-program-change?
  [before after installations projection]
  (let [identities (set (map (comp program/row-identity :seon.program/row)
                             installations))
        shapes (program/shapes-in (:seon.schema.projection/forms projection))
        history (db/history after)
        changed-database (db/since history (db/basis-t before))
        components (into []
                         (keep (fn [[attribute properties]]
                                 (when (:db/isComponent properties) attribute)))
                         (:schema (db/schema-database after)))
        touched (db/q '[:find [?entity ...] :where [?entity]] changed-database)
        parents (when-not (and (map? touched) (contains? touched :seon.error/at) (contains? touched :seon.error/layer) (contains? touched :seon.error/operation)) ;; debt: seon.db/q declares :seon.error/value through its output union.
                  (loop [frontier touched seen #{}]
                    (if (empty? frontier)
                      seen
                      (let [incoming (db/q '[:find [?parent ...]
                                             :in $ [?child ...] [?attribute ...]
                                             :where [?parent ?attribute ?child]]
                                           history frontier components)]
                        (when-not (and (map? incoming) (contains? incoming :seon.error/at) (contains? incoming :seon.error/layer) (contains? incoming :seon.error/operation)) ;; debt: seon.db/q declares :seon.error/value through its output union.
                          (recur (vec (remove seen incoming)) (into seen incoming)))))))
        entities (db/q '[:find [?entity ...]
                         :in $ [?attribute ...]
                         :where [?entity ?attribute]]
                       (db/since (db/history after) (db/basis-t before))
                       program/identity-attributes)
        changed (db/q '[:find [?entity ...]
                        :in $ $changed [?attribute ...]
                        :where [$changed ?entity] [?entity ?attribute]]
                      after (db/since (db/history after) (db/basis-t before))
                      program/identity-attributes)]
    (when (and parents (not (and (map? entities) (contains? entities :seon.error/at) (contains? entities :seon.error/layer) (contains? entities :seon.error/operation))) (not (and (map? changed) (contains? changed :seon.error/at) (contains? changed :seon.error/layer) (contains? changed :seon.error/operation)))) ;; debt: seon.db/q declares :seon.error/value through its output union.
      (let [eids (vec (set (concat entities changed parents)))
            old (db/pull-many before '[*] eids)
            current (db/pull-many after '[*] eids)]
        (and (not (and (map? old) (contains? old :seon.error/at) (contains? old :seon.error/layer) (contains? old :seon.error/operation))) (not (and (map? current) (contains? current :seon.error/at) (contains? current :seon.error/layer) (contains? current :seon.error/operation))) ;; debt: seon.db/pull-many declares :seon.error/value through its output union.
             (every?
              (fn [[eid old-row current-row]]
                (let [identity (or (program/row-identity current-row)
                                   (program/row-identity old-row))]
                  (or (nil? identity)
                      (contains? identities identity)
                      (and (not (contains? parents eid))
                           (= (program/canonical-row shapes old-row)
                              (program/canonical-row shapes current-row))))))
              (map vector eids old current)))))))

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
          {:projection (:seon.schema/projection result)
           :installed (conj installed result)}))
      {:projection (context-projection ctx)
       :installed []}
      installations))]
      (when-let [snapshot (::base-bindings agent-ctx)]
        (reset! snapshot (base-bindings ctx)))
      (when-let [before (:seon.db/db (acquired-program ctx))]
        (when (and (every? :seon.sci.eval/installed installed)
                   (installation-covers-program-change?
                    before db installations (context-projection ctx)))
          (doseq [target (cond-> [ctx] agent-ctx (conj agent-ctx))]
            (swap! (::kernel/program-snapshot target) assoc :seon.db/db db))))
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
          (require namespace-name)
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
                  (error/diagnostic
                   {:seon.error/at (java.util.Date.)
                    :seon.error/layer ::acquisition
                    :seon.error/operation 'seon.sci.eval/host-namespace!
                    :seon.sci.eval/row-member namespace-name
                    :seon.sci.eval/acquisition-observation
                    {:seon.error.evidence/attribute :seon.error/message
                     :seon.error.evidence/value cause-message}
                    :seon.error/message message
                    :seon.error/diagnostic-layer ::acquisition
                    :seon.error/diagnostic-operation
                    'seon.sci.eval/host-namespace!
                    :seon.error/diagnostic-member namespace-name
                    :seon.error/diagnostic-expected ::loaded-host-namespace
                    :seon.error/diagnostic-offending namespace-name
                    :seon.error/diagnostic-cause cause-message
                    :seon.error/diagnostic-evidence
                    {:seon.sci.eval/cause-class
                     (symbol (.getName (class underlying)))
                     :seon.sci.eval/cause-location location-data}
                    :seon.ns/name namespace-name})]
              (throw (ex-info message diagnostic failure)))))
        (or (find-ns namespace-name)
            (throw
             (ex-info
              (str "First-party program namespace " namespace-name
                   " loaded without defining a namespace.")
              (error/diagnostic
               {:seon.error/at (java.util.Date.)
                :seon.error/layer :seon.sci.eval/program
                :seon.error/operation 'seon.sci.eval/host-namespace!
                :seon.error/message "Loaded source defined no namespace; correct its namespace declaration."
                :seon.error/offending namespace-name
                :seon.error/diagnostic-layer :seon.sci.eval/program
                :seon.error/diagnostic-operation 'seon.sci.eval/host-namespace!
                :seon.error/diagnostic-member :seon.ns/name
                :seon.error/diagnostic-expected :seon.sci.eval/loaded-namespace
                :seon.error/diagnostic-offending namespace-name
                :seon.error/diagnostic-cause :seon.error/unknown
                :seon.error/diagnostic-evidence namespace-name
                ::row-member namespace-name
                ::acquisition-observation {:seon.error.evidence/attribute :seon.error/message
                                           :seon.error.evidence/value "Loaded source defined no namespace."}})))))))

(defn- load-core-namespaces!
  "The effectful cluster caller loads JVM namespaces before pure construction."
  [database]
  (doseq [namespace-name
          (sort-by str
                   (db/q '[:find [?name ...]
                           :where [?namespace :seon.ns/name ?name]
                           (or-join [?namespace]
                             (and [?namespace :seon.schema.admission/source :core]
                                  [?namespace :seon.ns/source _])
                             (and [?function :seon.fn/ns ?namespace]
                                  [?function :seon.fn/source _]
                                  [?function :seon.schema.admission/source :core]))]
                         database))]
    (host-namespace! namespace-name)))

(defn- install-first-party-namespaces!
  "Bind every first-party program namespace as its actual compiled JVM Vars.

  Namespace membership comes from core-provenanced program rows. The cluster
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
        (into #{}
              (comp
               (filter (fn [[_ _ admission]]
                         (= :core admission)))
               (map first))
              (concat namespace-assertions
                      (keep (fn [[_ source namespace-name admission _]]
                              (when (seq source)
                                [namespace-name source admission]))
                            function-rows)))
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
                   (sci/copy-var* host-var sci-namespace)]))
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
                  [local-name (sci/copy-var* host-var sci-namespace)]))
           intern-map))))

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
  (error/diagnostic
   {:seon.error/at (java.util.Date.)
    :seon.error/layer :seon.sci.eval/program
    :seon.error/operation 'seon.sci.eval/documentation-unavailable
    :seon.error/message "No public documentation is available; request an installed public declaration."
    :seon.error/offending requested
    :seon.error/diagnostic-layer :seon.sci.eval/program
    :seon.error/diagnostic-operation 'seon.sci.eval/documentation-unavailable
    :seon.error/diagnostic-member :seon.fn/sym
    :seon.error/diagnostic-expected :seon.sci.eval/public-documentation
    :seon.error/diagnostic-offending requested
    :seon.error/diagnostic-cause :seon.error/unknown
    :seon.error/diagnostic-evidence requested
    ::documentation-unavailable requested}))

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
  (error/diagnostic
   {:seon.error/at (java.util.Date.)
    :seon.error/layer :seon.sci.eval/program
    :seon.error/operation 'seon.sci.eval/declaration-absent
    :seon.error/message "The program row lacks a declaration; supply the declared member before relying on it."
    :seon.error/offending attribute
    :seon.error/diagnostic-layer :seon.sci.eval/program
    :seon.error/diagnostic-operation 'seon.sci.eval/declaration-absent
    :seon.error/diagnostic-member attribute
    :seon.error/diagnostic-expected :seon.sci.eval/present-declaration
    :seon.error/diagnostic-offending attribute
    :seon.error/diagnostic-cause :seon.error/unknown
    :seon.error/diagnostic-evidence attribute
    ::missing-declaration attribute}))

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
  [database row]
  (let [keys (into #{} (mapcat #(concat (:seon.fn.arity/input-refs %)
                                       (:seon.fn.arity/output-refs %)))
                   (:seon.fn/arities row))
        definitions (db/q '[:find ?key ?form :in $ [?key ...]
                            :where [?schema :seon.schema/key ?key]
                                   [?schema :seon.schema/form ?form]] database keys)]
    (when (and (map? definitions) (contains? definitions :seon.error/at) (contains? definitions :seon.error/layer) (contains? definitions :seon.error/operation)) ;; debt: seon.db/q declares :seon.error/value through its output union.
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
    (if (and (map? entries) (contains? entries :seon.error/at) (contains? entries :seon.error/layer) (contains? entries :seon.error/operation)) ;; debt: seon.call-preparation/supplied-map-entries declares :seon.error/value through its output union.
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
     (if (and (map? overrides) (contains? overrides :seon.error/at) (contains? overrides :seon.error/layer) (contains? overrides :seon.error/operation)) ;; debt: seon.program/overrides declares :seon.error/value through its output union.
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
  {:malli/schema [:=> [:cat :seon.db/db :symbol :boolean] [:or :map :seon.db/error-result :seon.sci.eval/documentation-unavailable-error]]}
  [database namespace-name present?]
  (let [namespace-row (db/pull database
                             '[:seon.ns/name
                               {:seon.schema/_ns [:seon.schema/key :seon.schema/form]}]
                             [:seon.ns/name namespace-name])
        functions (program-documentation database namespace-name)]
    (cond
      (and (map? namespace-row) (contains? namespace-row :seon.error/at) (contains? namespace-row :seon.error/layer) (contains? namespace-row :seon.error/operation)) namespace-row ;; debt: seon.db/pull declares :seon.error/value through its output union.
      (and (map? functions) (contains? functions :seon.error/at) (contains? functions :seon.error/layer) (contains? functions :seon.error/operation)) functions ;; debt: seon.db/q declares :seon.error/value through its output union.
      (or (:seon.ns/name namespace-row) present? (seq functions))
      (let [functions (sort-by (juxt #(get % :seon.fn/doc-order Long/MAX_VALUE)
                                    :seon.fn/sym) functions)]
        {:schemas (into (into (sorted-map) (mapcat #(documentation-schemas database %)) functions)
                        (map (fn [row]
                               [(:seon.schema/key row)
                                (edn/read-string (:seon.schema/form row))]))
                        (:seon.schema/_ns namespace-row))
         :functions (mapv (fn [row]
                            (merge {:sym (:seon.fn/sym row)
                                    :arglists (if-let [arglists (:seon.fn/arglists row)]
                                                (edn/read-string arglists)
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
        (and (map? row) (contains? row :seon.error/at) (contains? row :seon.error/layer) (contains? row :seon.error/operation)) row ;; debt: seon.db/pull declares :seon.error/value through its output union.
        (and (map? overrides) (contains? overrides :seon.error/at) (contains? overrides :seon.error/layer) (contains? overrides :seon.error/operation)) overrides ;; debt: seon.program/overrides declares :seon.error/value through its output union.
        (and (:seon.fn/sym row)
             (or (false? (:seon.fn/private? row)) overridden?))
        (function-doc-map database row overridden?)
        :else (documentation-unavailable requested)))
    (let [row (db/pull database [:seon.ns/doc] [:seon.ns/name requested])]
      (cond
        (and (map? row) (contains? row :seon.error/at) (contains? row :seon.error/layer) (contains? row :seon.error/operation)) row ;; debt: seon.db/pull declares :seon.error/value through its output union.
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

(defn- install-program-doc!
  "Install REPL macros without capturing program facts or a database value."
  [ctx _db _projection]
  (let [doc-var (program-doc-var)
        dir-var (program-dir-var)]
    (sci/add-namespace! ctx 'clojure.repl {'doc doc-var 'dir dir-var})
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
                  :seon.sci.eval/row-acquisition-error]}
  [row failure]
  (let [identity (program-row-identity row)
        failure-data (if (map? failure) failure (error/refusal failure))
        cause-message (or (:seon.error/message failure-data)
                          (when (instance? Throwable failure) (ex-message failure))
                          (.getName (class failure)))]
    (error/diagnostic
     {:seon.error/at (java.util.Date.)
      :seon.error/layer ::acquisition
      :seon.error/operation 'seon.sci.eval/acquisition-refusal
      :seon.sci.eval/row-member (second identity)
      :seon.sci.eval/acquisition-observation
      {:seon.error.evidence/attribute :seon.error/message
       :seon.error.evidence/value cause-message}
      :seon.error/message
      "Program row could not be installed; correct the reported acquisition evidence."
      :seon.error/diagnostic-layer ::acquisition
      :seon.error/diagnostic-operation 'seon.sci.eval/acquisition-refusal
      :seon.error/diagnostic-member identity
      :seon.error/diagnostic-expected ::installed
      :seon.error/diagnostic-offending identity
      :seon.error/diagnostic-cause
      cause-message
      :seon.error/diagnostic-evidence
      {:seon.error/message cause-message
       :seon.error/diagnostic-cause
       cause-message}
      :seon.error/data
      (cond-> {::acquisition-row identity
               ::acquisition-cause-message cause-message}
        failure-data (assoc ::acquisition-failure failure-data)
        (instance? Throwable failure)
        (assoc ::acquisition-throwable-class (.getName (class failure))))})))

(defn- acquisition-refusal-id
  [refusal]
  (id/digest 64
             [::acquisition-refused
              (get-in refusal [:seon.error/data ::acquisition-row])
              (:seon.sci.eval/row-member refusal)
              (:seon.error/operation refusal)
              (get-in refusal [:seon.error/data ::acquisition-cause-message])]))

(defn- record-acquisition-refusals!
  "Record contained row refusals through the one durable error owner."
  [ctx db state commit-fault!]
  (let [refusals (::acquisition-refusals state)]
    (if-not (seq refusals)
      state
      (if commit-fault!
        (let [outcomes (mapv commit-fault! refusals)
              failed (first (remove #(= :seon.flow/committed (second %)) outcomes))]
          (cond-> (assoc state ::acquisition-refusals-recorded? (nil? failed))
            failed (assoc ::acquisition-recording-error (second failed))))
      (if-let [connection (:seon.db/connection (::custody ctx))]
        (let [read-effective (database-effective-config db)
              ;; THE FALLBACK KEYS ON THE REFUSAL, not on nil: this read
              ;; answers the typed unknown rather than an absence, so an
              ;; `or` here would carry the refusal into `result-caps` and
              ;; `commit-tx` instead of the shipped decisions it means.
              effective (if (contains? read-effective :seon.config/error-key)
                          (config/defaults)
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
              outcome (db/transact! connection tx-data)]
          (cond-> (assoc state ::acquisition-refusals-recorded? true)
            (and (map? outcome) (contains? outcome :seon.error/at) (contains? outcome :seon.error/layer) (contains? outcome :seon.error/operation)) ;; debt: seon.db/transact! declares :seon.error/value through its output union.
            (assoc ::acquisition-refusals-recorded? false
                   ::acquisition-recording-error outcome)))
        (assoc state ::acquisition-refusals-recorded? false))))))

(defn- acquire-program!
  "Acquire program declarations by their current identity's admission.

  Core function roots come from loaded JVM Vars; agent function source is
  interpreted regardless of namespace. Private evaluation objects are never
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
        function-rows
        (into []
              (filter
               (fn [[_sym _ _ admission _]]
                 (agent-authored? admission)))
              all-function-rows)
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
                  (error/diagnostic
                   {:seon.error/at (java.util.Date.)
                    :seon.error/layer :seon.sci.eval/program
                    :seon.error/operation 'seon.sci.eval/acquire-program!
                    :seon.error/message "Namespace bindings form a cycle; remove the cyclic dependency before acquisition."
                    :seon.error/offending remaining
                    :seon.error/diagnostic-layer :seon.sci.eval/program
                    :seon.error/diagnostic-operation 'seon.sci.eval/acquire-program!
                    :seon.error/diagnostic-member :seon.ns/requires
                    :seon.error/diagnostic-expected :seon.sci.eval/acyclic-bindings
                    :seon.error/diagnostic-offending remaining
                    :seon.error/diagnostic-cause :seon.error/unknown
                    :seon.error/diagnostic-evidence remaining
                    ::pending-namespaces (set (keys remaining))}))))
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
              (if (contains? installed :seon.instrument/check)
                (update state ::acquisition-refusals (fnil conj [])
                        (acquisition-refusal row installed))
                (cond-> (assoc state
                       :seon.schema/projection
                       (:seon.schema/projection installed)
                       :seon.sci.eval/installed
                       (+ (:seon.sci.eval/installed state)
                          (:seon.sci.eval/installed installed)))
                  (:seon.sci.eval/load-result installed)
                  (update :seon.sci.eval/load-results (fnil conj [])
                          (:seon.sci.eval/load-result installed))
                  (= :jvm-fallback (:seon.sci.eval/load-state installed))
                  (update ::acquisition-refusals (fnil conj [])
                          (acquisition-refusal
                           row
                           (ex-info
                            (str "SCI source could not load; using the loaded JVM definition: "
                                 (get-in installed [:seon.sci.eval/load-result :seon.error/message]))
                            {:seon.sci.eval/load-state :jvm-fallback}))))))
            (catch Throwable failure
              (when (:seon.instrument/registration-observation (error/refusal failure))
                (throw failure))
              (update state ::acquisition-refusals (fnil conj [])
                      (acquisition-refusal row failure)))))]
    ;; Imports are explicit namespace facts. Install their named classes before
    ;; the namespace bindings resolve them; SCI is containment, not a security
    ;; boundary, and the program graph—not a hand list—declares the set.
    (install-declared-classes! ctx namespace-rows)
    ;; Create every namespace and publish aliases first. Alias targets need not
    ;; exist: this is the effective behavior of SCI's `:as-alias` too.
    (doseq [[namespace-name row] namespace-row-by-name]
      (sci/install-namespace-bindings!
       ctx namespace-name (assoc (row-bindings row) :refers {})))
    ;; Namespace declarations establish aliases/imports first; compiled host
    ;; bindings then populate the same SCI namespaces without being erased by
    ;; that declaration install. Selected definitions overwrite only their Vars.
    (install-first-party-namespaces!
     ctx namespace-assertions all-namespace-rows
     all-function-rows)
    ;; The bare REPL name refers to the acquired macro itself. Keeping the
    ;; boot-time copy here would preserve its old expansion after adoption.
    (sci/add-namespace! ctx 'clojure.core
                        {'help (sci/resolve ctx 'seon.bootstrap/help)})
    (let [functions-installed
          (reduce
           (fn [state namespace-name]
             ;; Refer Vars are installed only after all target namespaces on
             ;; this dependency edge have published their functions.
             (when-let [row (get namespace-row-by-name namespace-name)]
               (sci/install-namespace-bindings!
                ctx namespace-name (row-bindings row)))
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

(defn- regenerate-agent-context!
  "Fork the replacement base and carry the private layer as actual objects.

  Owned private Vars keep their roots, including captured values. Inherited
  program bindings are replaced. A JVM restart has no entering context and
  consequently preserves no private objects. No database read restores them."
  [ctx base]
  (let [generation (:sci/generation @(:env ctx))
        previous @(::base-bindings ctx)
        current (base-bindings base)
        private-bindings
        (for [[namespace-name entries] (sci/namespace-state ctx)
              [local-name entry] entries
              :when (sci.utils/var? entry)
              :let [path [namespace-name local-name]
                    root (if (sci.vars/hasRoot entry) @entry absent-intern)]
              :when (and (= generation (:sci/generation (meta entry)))
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
    (doseq [[namespace-name entries] (sci/namespace-state ctx)
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
  (let [ctx (if agent-ctx
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
        assigned-namespace (agent-namespace db agent-id)]
    (advance-context-projection! ctx db (context-projection base-ctx))
    (when (and assigned-namespace (not (sci/find-ns ctx assigned-namespace)))
      (sci/add-namespace! ctx assigned-namespace {}))
    {:seon.sci.eval/ctx ctx
     :seon.sci.eval/private-state
     (if agent-ctx :preserved-in-memory :absent)}))

(defn base-ctx
  "Derive the program-only SCI context from one database value.

  Core definitions copy the loaded JVM Var root. Agent definitions interpret
  their admitted source. Acquisition refusals remain values on the context;
  construction has no connection, writes no facts, and restores no private state."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value] :seon.sci.eval/ctx]
    [:=> [:cat :seon.db/database-value
          [:map [:seon.flow/commit-fault! {:optional true} :seon.flow/commit-fault!]]]
     :seon.sci.eval/ctx]]}
  ([database] (base-ctx database {}))
  ([database arm-request]
  (let [projection (if-let [carried (db/carried-projection database)]
                     (schema/projection-from-database database carried)
                     (schema/projection-from-database database))]
    (schema/call-with-projection
     projection
     (fn []
       (let [ctx (assoc (build-base-ctx projection)
                        :seon.schema/projection projection
                        ::kernel/install-function! install-function-from-database!)
             _ (swap! (::kernel/program-snapshot ctx) merge
                      (select-keys arm-request [:seon.flow/commit-fault!]))
             acquired (acquire-program! {:seon.sci.eval/ctx ctx
                                 :seon.db/db database
                                 :seon.schema/projection projection})]
         (swap! (::kernel/program-snapshot ctx) assoc
                :seon.db/db database ::acquisition acquired
                :seon.test/class-loader (clojure.lang.RT/baseLoader))
         (assoc ctx ::acquisition acquired)))))))

(defn acquire!
  "Regenerate the cluster base from the supplied database value.

  The existing context identity belongs to cluster handles; replacing its
  program environment lets their retained forks regenerate from that base."
  {:malli/schema [:=> [:cat :seon.sci.eval/acquire-request] :map]}
  [{ctx :seon.sci.eval/ctx database :seon.db/db
    commit-fault! :seon.flow/commit-fault!}]
  (load-core-namespaces! database)
  (let [generated (base-ctx database
                            (cond-> {} commit-fault!
                              (assoc :seon.flow/commit-fault! commit-fault!)))
        acquired (::acquisition generated)]
    (reset! (:env ctx) @(:env generated))
    (reset! (::kernel/program-snapshot ctx) @(::kernel/program-snapshot generated))
    (reset! (::kernel/installed-functions ctx) @(::kernel/installed-functions generated))
    (advance-context-projection! ctx database (:seon.schema/projection generated))
    (record-acquisition-refusals! ctx database acquired commit-fault!)))

(declare cluster-ctx*)

(defn cluster-ctx
  "Build and cold-acquire one cluster's live SCI program context."
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
          [:map [:seon.flow/commit-fault! {:optional true} :seon.flow/commit-fault!]]]
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
   (load-core-namespaces! db)
   (let [ctx (assoc (base-ctx db arm-request)
                    ::custody (cond-> {}
                                connection (assoc :seon.db/connection connection)))
         projection (:seon.schema/projection ctx)
         projection-state (or supplied-projection-state
                              (projection-state db projection))
         ctx (call-preparation/install
              (env/carry-state
               (assoc ctx :seon.schema/projection projection)
               projection-state))]
     (record-acquisition-refusals! ctx db (::acquisition ctx) nil)
     ;; The listener is the optimizer, never the correctness boundary —
     ;; an idle cluster notices a new supplied-default row without
     ;; waiting for the next call's basis comparison. It needs the live
     ;; connection, so a connectionless context simply has none.
     (when connection
       (call-preparation/watch!
        (get ctx call-preparation/carrier) connection projection))
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
          [:map [:seon.flow/commit-fault! {:optional true} :seon.flow/commit-fault!]]]
     :seon.sci.eval/ctx]]}
  ([base-ctx db connection]
   (fork-cluster-ctx base-ctx db connection
                     (projection-state db (schema/projection-from-database db))))
  ([base-ctx db connection supplied-projection-state]
   (fork-cluster-ctx base-ctx db connection supplied-projection-state {}))
  ([base-ctx db connection supplied-projection-state arm-request]
   (let [projection (or (:seon.schema/projection
                         (some-> supplied-projection-state deref))
                        (schema/projection-from-database db))
         projection-state (or supplied-projection-state
                              (projection-state db projection))
         ctx (call-preparation/install
              (env/carry-state
               (assoc (sci/fork base-ctx)
                      ::kernel/installed-functions
                      (atom @(::kernel/installed-functions base-ctx))
                      ::kernel/program-snapshot
                      (atom (merge (dissoc @(::kernel/program-snapshot base-ctx)
                                           :seon.flow/commit-fault!)
                                   (select-keys arm-request [:seon.flow/commit-fault!])))
                      ::custody {:seon.db/connection connection}
                      :seon.schema/projection projection)
               projection-state))]
     ;; A sovereign branch must not inherit the source branch's recorder.
     ;; SCI's generation-aware bind-root! changes only this fork's Vars.
     (doseq [function-symbol @(::kernel/installed-functions ctx)
             :let [candidate (sci/resolve ctx function-symbol)]
             :when (and candidate
                        (::instrument/interpreted-original (meta @candidate)))]
       (install-function-contract!
        ctx (db/pull db '[*] [:seon.fn/sym function-symbol]) projection db))
     (when connection
       (call-preparation/watch!
        (get ctx call-preparation/carrier) connection projection))
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
                  (error/diagnostic
                   {:seon.error/at (java.util.Date.)
                    :seon.error/layer :seon.sci.eval/program
                    :seon.error/operation 'seon.sci.eval/declared-row
                    :seon.error/message "Evaluated schema identity differs from its declaration; return the declared identity."
                    :seon.error/offending schema-value
                    :seon.error/diagnostic-layer :seon.sci.eval/program
                    :seon.error/diagnostic-operation 'seon.sci.eval/declared-row
                    :seon.error/diagnostic-member unregister-key
                    :seon.error/diagnostic-expected :seon.sci.eval/registered-reader-identity
                    :seon.error/diagnostic-offending schema-value
                    :seon.error/diagnostic-cause :seon.error/unknown
                    :seon.error/diagnostic-evidence schema-value
                    ::schema-refused unregister-key}))))
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
                  (error/diagnostic
                   {:seon.error/at (java.util.Date.)
                    :seon.error/layer :seon.sci.eval/program
                    :seon.error/operation 'seon.sci.eval/declared-row
                    :seon.error/message "Evaluated schema identity differs from its declaration; return the declared identity."
                    :seon.error/offending schema-value
                    :seon.error/diagnostic-layer :seon.sci.eval/program
                    :seon.error/diagnostic-operation 'seon.sci.eval/declared-row
                    :seon.error/diagnostic-member schema-key
                    :seon.error/diagnostic-expected :seon.sci.eval/registered-reader-identity
                    :seon.error/diagnostic-offending schema-value
                    :seon.error/diagnostic-cause :seon.error/unknown
                    :seon.error/diagnostic-evidence schema-value
                    ::schema-refused schema-key}))))
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
    source :seon.cluster.eval/source}]
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
           namespace-name source before-reader-context
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
     :seon.sci.eval/context-row context-row
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

(defn- shown-result
  [value request record]
  (let [function-name (when (and (map? value) (contains? value :seon.instrument/check))
                        (:seon.instrument/fn value))
        database (when function-name
                   (or (:seon.db/db request)
                       (some-> (get-in request [:seon.sci.eval/ctx ::custody :seon.db/connection]) db/db)))
        function-row (when (and function-name database)
                       (db/pull database program-documentation-selector
                                [:seon.fn/sym function-name]))
        value (if (:seon.fn/sym function-row)
                (assoc value :seon.error/doc (function-doc-map database function-row)) value)
        profile (render/request-profile request)
        projection (render.value/prepare
               (assoc request
                      :seon.render/value value
                      :seon.render/profile profile
                      :seon.render.call/id
                      [:seon.cluster.eval/source
                       (:seon.cluster.eval/source request)]))
        shown (if (contains? projection :seon.render.value/root-description) projection
                  (render.value/render-ai-data projection))]
    (cond-> {:seon.sci.admit/value value
             :seon.eval/shown (if (string? shown) shown (pr-str shown))}
      (:seon.render.call/selected-producer projection)
      (assoc :seon.eval/renderer (:seon.render.call/selected-producer projection))
      (when-let [declared (render/request-projection request)]
        (seq (error/facets declared value)))
      (assoc :seon.cluster.eval/error (failure-text value))
      record (assoc :seon.sci.admit/record record))))

(defn- success-evaluation
  [{admitted :seon.sci.eval/admitted
    caps :seon.sci.admit/caps
    output-prefix :seon.sci.eval/output-prefix
    printed :seon.sci.eval/printed
    namespace-name :seon.sci.eval/namespace-name
    ending-namespace :seon.sci.eval/ending-namespace
    print-options :seon.print/options
    definitions :seon.sci.eval/bindings
    row :seon.program/row}]
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
            (:seon.cluster.eval/error admitted)
            (assoc :seon.cluster.eval/error (:seon.cluster.eval/error admitted))
)
    ;; HOW LONG THE FORM TOOK IS A KEY OF THE EVALUATION, not something two
    ;; readers dig out of the diagnostic record by different routes. The
    ;; storage projection and the page's in-memory render both read this one
    ;; spelling, so an unstored record and its stored evaluation cannot
    ;; disagree about `:seon.repl/ms`.
    (int? (get-in admitted [:seon.sci.admit/record :seon.eval/duration-ms]))
    (assoc :seon.eval/duration-ms
           (get-in admitted [:seon.sci.admit/record :seon.eval/duration-ms]))
    row (assoc :seon.program/row row)
    (seq definitions) (assoc :seon.sci.eval/bindings definitions)
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
    triage-edn :seon.cluster.eval/triage-edn
    interrupted-at :seon.cluster.eval/interrupted-at
    :as request}]
  (cond-> (cond-> {:seon.print/options print-options
                   :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                   :seon.sci.eval/ending-ns namespace-name
                   :seon.cluster.eval/error (failure-text value)
                   :seon.sci.admit/record record}
            (contains? admitted :seon.sci.admit/value)
            (assoc :seon.sci.admit/value (:seon.sci.admit/value admitted))
            (:seon.eval/shown admitted)
            (assoc :seon.eval/shown
                   (:seon.eval/shown admitted))
)
    ;; The same one spelling on the failing path.
    (int? (:seon.eval/duration-ms record))
    (assoc :seon.eval/duration-ms (:seon.eval/duration-ms record))
    (string? triage-edn)
    (assoc :seon.cluster.eval/triage-edn triage-edn)
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
  to guess where the form was going to run."
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
             :seon.eval/shown (pr-str value)
             :seon.cluster.eval/error (failure-text value)
             :seon.cluster.eval/ns namespace-ref
             :seon.sci.eval/ending-ns (second namespace-ref)
             :seon.print/options {}
             :seon.sci.admit/record record}
      interrupted? (assoc :seon.cluster.eval/interrupted-at interrupted-at))))

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

  Never throws. A failure of any kind returns an ordinary map whose
  `:seon.sci.admit/value` is a flat `:seon.error` value. PRESENCE IS
  THE STATE (owner ruling 2026-07-28): `:seon.cluster.eval/error` is
  present exactly when the form failed, and
  `:seon.cluster.eval/interrupted-at` — the instant the interrupt was
  observed — is present exactly when the time limit fired. The record
  rides through with `fn-entries` and `allocated-bytes` intact."
  {:malli/schema [:=> [:cat :seon.sci.eval/request]
                  :seon.sci.eval/evaluation]}
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
    on-core-error :seon.config/on-core-error
    :as request}]
  (let [;; A supplied ctx keeps its accumulated defs. The only replacement is
        ;; the environment state: each form receives a turn-scoped immutable
        ;; value, so call preparation cannot read the long-lived cluster value
        ;; and silently omit the agent/run/form members.
        base-evaluation-ctx (or ctx (build-base-ctx (evaluation-projection request)))
        request
        (if-let [database (or (:seon.db/db request)
                              (some-> (get-in base-evaluation-ctx
                                               [::custody :seon.db/connection])
                                      db/db))]
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
        printed (java.io.StringWriter.)
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
        projection-state
        (or (::projection-state evaluation-ctx)
            (atom {:seon.schema/projection
                   (evaluation-projection
                    {:seon.sci.eval/ctx evaluation-ctx})}))]
    (schema/call-with-projection-state
     projection-state
     (fn []
       (with-bindings {#'db/*conn* connection
                       ;; The bound read value carries this evaluation's
                       ;; projection state: interpreted reads run past the
                       ;; dynamic binding above, and a bare value rebuilds
                       ;; the projection per read (measured 2026-09-15).
                       #'db/*read-database*
                       (some-> (:seon.db/db turn-environment)
                               (db/carry-projection-state projection-state))
                       #'db/*receipt* receipt
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
                          :seon.config/on-core-error on-core-error
                          ;; Named so a DETACHED capability handler can rebuild
                          ;; this evaluation's schema frame from data. Without
                          ;; it a background handler resolving any declaration
                          ;; refuses with :seon.schema/missing-projection, and a
                          ;; cached delay turns that one refusal into every
                          ;; later failure in the JVM.
                          :seon.sci.eval/projection-state projection-state
                          :seon.effect/counter (atom -1)})}
      (let [failure-result
             (fn [throwable]
               (let [record (record (if (kernel/interrupted? throwable)
                                 :time :error))
                definitions
                (when-let [{failed-ctx :seon.sci.eval/ctx
                            before :seon.sci.eval/before-intern-values
                            failed-form :seon.sci.eval/form}
                           @session-observation]
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
                admitted (shown-result value request record)]
          (failed-evaluation
           (cond-> {:seon.sci.eval/admitted admitted
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/printed printed
                    :seon.sci.eval/namespace-name namespace-name
                    :seon.print/options @print-options
                    :seon.sci.eval/bindings definitions
                    :seon.sci.admit/record record
                    :seon.sci.admit/value value
                    :seon.cluster.eval/triage-edn
                    (pr-str
                     (main/ex-triage (Throwable->map throwable)))}
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
            ;; The install entrance supplies a candidate for a function;
            ;; ordinary forms receive the retained agent context directly.
            execution-ctx evaluation-ctx
            before-intern-values (turn-intern-values execution-ctx)
            _ (when-not namespace-unmap?
                (vreset! session-observation
                         {:seon.sci.eval/ctx execution-ctx
                          :seon.sci.eval/before-intern-values
                          before-intern-values
                          :seon.sci.eval/form form}))
            before-namespace-state
            (when namespace-unmap?
              (sci/namespace-state execution-ctx))
            before-interns (when namespace-unmap?
                             (sci/namespace-interns execution-ctx))
            eval-form!
            (fn []
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
                  (let [value (sci/eval-form execution-ctx form)]
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
            projection
            (evaluation-projection {:seon.sci.eval/ctx evaluation-ctx})
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
              ;; by the terminal transaction; refusal never rolls a live
              ;; definition back.
              live-declaration? (eval-form!)

              ;; Schema declarations run once inside their isolated
              ;; registration delta above. Preserve that evaluated identity
              ;; without executing the form a second time.
              base-declared-row
              (or (:seon.schema/key base-declared-row)
                  (when unregister-key schema-value))

              :else (eval-form!))
            _ (when-let [declared-ns (:seon.ns/name base-declared-row)]
                (vreset! ending-namespace declared-ns))
            {reader-row :seon.program/row
             context-row :seon.sci.eval/context-row}
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
              :seon.cluster.eval/source source})
            var-row
            (when-let [database (:seon.db/db request)]
              (definition-row execution-ctx database projection
                              before-intern-values source
                              (assoc (binding-rows before-reader-context)
                                     :seon.ns/name namespace-name)))
            ending-namespace-row
            (when (and (nil? var-row)
                       (nil? reader-row)
                       (not= namespace-name @ending-namespace))
              ;; `in-ns` creates the namespace in SCI. Persist that living
              ;; declaration so the next form's entity ref resolves; this is
              ;; not a stub for an observed unresolved name.
              (program/declaration-row
               (merge {:seon.ns/name @ending-namespace
                       :seon.ns/source source}
                      (binding-rows
                       (reader-context execution-ctx @ending-namespace)))
               :contracted :agent))
            row (or var-row reader-row ending-namespace-row)
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
            value (if context-row (:seon.ns/name context-row) evaluated-value)
              ;; INSIDE the boundary, BEFORE disarm: an infinite lazy
              ;; sequence dies at the time limit here rather than in the
              ;; receipt writer
              evaluation-record (record :ok)
              ;; Capture exact evaluated roots. The install decision transfers
              ;; accepted function roots and discards refused candidate roots.
              definitions
              (bindings execution-ctx namespace-name before-intern-values
                         source form (built-in-calls))
              admitted (shown-result value request evaluation-record)]
          (success-evaluation
           {:seon.sci.eval/admitted admitted
            :seon.sci.admit/caps caps
            :seon.sci.eval/printed printed
            :seon.sci.eval/output-prefix
            (str/join "\n"
                      (remove str/blank?
                               [output-prefix
                                (when row
                                  (accretion/non-generatable-advisory row))]))
            :seon.sci.eval/namespace-name namespace-name
            :seon.sci.eval/ending-namespace @ending-namespace
            :seon.print/options @print-options
            :seon.sci.eval/bindings definitions
            :seon.program/row row}))
        (catch Throwable throwable
          (failure-result throwable))
          )))
        (catch Throwable throwable
          (if @arm-state
            (throw throwable)
            (failure-result throwable))))))))))

(defn fork-candidate-ctx
  "Fork one candidate through the generation-aware turn path.

  This deliberately delegates to `fork-for-turn`; candidate code never calls
  plain `sci/fork`; the candidate receives a separate context and cannot
  modify the live agent's private bindings."
  {:malli/schema [:=> [:cat :seon.test.accretion/candidate-context-request]
                  :seon.sci.eval/ctx]}
  [request]
  (:seon.sci.eval/ctx (fork-for-turn request)))

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

(defn evaluate-for-install
  "Read once and evaluate function declarations in the existing candidate fork.
  Other forms keep the retained context. The caller owns the install decision."
  {:malli/schema
   [:=> [:cat [:and :seon.sci.eval/request
               :seon.test.accretion/candidate-context-request]]
    :seon.sci.eval/evaluation]}
  [{ctx :seon.sci.eval/ctx source :seon.cluster.eval/source
    namespace-ref :seon.cluster.eval/ns :as request}]
  (let [event (or (:seon.sci.eval/event request)
                  (try
                    (one-event source (second namespace-ref) ctx
                               (get-in request [:seon.sci.admit/caps
                                                :seon.config.eval.result/max-source]))
                    (catch Throwable failure
                      {:seon.sci.reader/error
                       (kernel/failure-value
                        {::kernel/time-limit-kind ::time-limit
                         ::kernel/failure-kind ::evaluation-failed}
                        failure (kernel/unarmed-record (System/nanoTime)))})))
        candidate (when (:seon.fn/sym event) (fork-candidate-ctx request))
        evaluation (evaluate (assoc request :seon.sci.eval/event event
                                    :seon.sci.eval/ctx (or candidate ctx)))]
    (cond-> evaluation
      candidate (assoc :seon.test.accretion/candidate-ctx candidate))))

(defn accept-candidate!
  "Transfer an accepted function's evaluated root into the retained context."
  {:malli/schema
   [:=> [:cat [:map [:seon.sci.eval/ctx :seon.sci.eval/ctx]
               [:seon.db/db :seon.db/database-value]
               [:seon.sci.eval/evaluation :seon.sci.eval/evaluation]]]
    :seon.sci.eval/evaluation]}
  [{ctx :seon.sci.eval/ctx database :seon.db/db
    evaluation :seon.sci.eval/evaluation}]
  (let [row (:seon.program/row evaluation)]
    (transfer-evaluated-roots!
     ctx [{:seon.program/row row :seon.sci.eval/evaluation evaluation}])
    (install-candidate-function! ctx database row)
    (assoc evaluation :seon.sci.admit/value
           (sci/resolve ctx (:seon.fn/sym row)))))

(defn refuse-install
  "Project the flat refusal as the evaluation result, discarding candidate roots."
  {:malli/schema
   [:=> [:cat :seon.sci.eval/request :seon.sci.eval/evaluation
         :seon.test.accretion/install-refused-error]
    :seon.sci.eval/evaluation]}
  [request evaluation refusal]
  (merge (dissoc evaluation :seon.program/row :seon.sci.eval/bindings
                 :seon.test.accretion/candidate-ctx :seon.eval/renderer)
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
            ;; A gate test is THIS AGENT'S work, exactly like the definition
            ;; it gates: the candidate request already carries the connection
            ;; its evaluation runs on, so hand that value down and the test's
            ;; elided `seon.db` arities reach the agent's own cluster rather
            ;; than refusing. Same seam, same value, as `seon.test/run-owned`.
            (run-test
             (assoc (select-keys request [:seon.db/connection :seon.sci.eval/time-limit-ms])
                    :seon.sci.eval/ctx ctx :seon.test/var test-var)))))))

(defn evaluate-candidate
  "Evaluate one durable function and every gate test in an isolated candidate.

  The returned test values are exactly `seon.test.runner/run-var!` reports.
  All tests run even after a red result. A supplied candidate and evaluation
  reuse the already evaluated definition; acceptance transfers only its root,
  never the candidate context or the gate tests' private definitions."
  {:malli/schema [:=> [:cat :seon.test.accretion/candidate-request]
                  :seon.test.accretion/candidate-result]}
  [{base-ctx :seon.sci.eval/ctx
    database :seon.db/db
    connection :seon.db/connection
    agent-id :seon.agent/id
    source :seon.cluster.eval/source
    test-symbols :seon.test.accretion/gate-set
    analyzed-row :seon.program/row
    :as request}]
  (let [ctx (or (:seon.test.accretion/candidate-ctx request)
                (fork-candidate-ctx
             {:seon.sci.eval/ctx base-ctx
              :seon.db/db database
              :seon.db/connection connection
              :seon.agent/id agent-id}))
        evaluation
        (or (:seon.test.accretion/evaluation request)
            (evaluate (assoc request
                         :seon.sci.eval/ctx ctx
                         :seon.cluster.eval/source source)))
        row (or analyzed-row (:seon.program/row evaluation))
        evaluation (cond-> evaluation row (assoc :seon.program/row row))]
    (if (or (:seon.cluster.eval/error evaluation)
            (nil? (:seon.fn/sym row)))
      {:seon.test.accretion/candidate-ctx ctx
       :seon.test.accretion/evaluation evaluation
       :seon.test.accretion/results []}
      (do
        (install-candidate-function! ctx database row)
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
            :seon.test.accretion/evaluation evaluation
            ;; Auto-check is deliberately realized above this expression:
            ;; the seeded contract probe precedes every example test.
            :seon.test.accretion/results
            (mapv (partial run-candidate-test! ctx database request)
                  test-symbols)}
            check (assoc :seon.test.accretion/auto-check check)))))))

(defn auto-check-candidate
  "Run the seeded contract check against an already evaluated candidate."
  {:malli/schema
   [:=> [:cat :seon.test.accretion/candidate-auto-check-request]
    :seon.test.accretion/auto-check-result]}
  [{ctx :seon.sci.eval/ctx :as request}]
  (accretion/auto-check
   (assoc request :seon.schema/projection (context-projection ctx))))
