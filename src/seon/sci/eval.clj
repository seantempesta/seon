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
  reads `:seon.cluster.agent/namespace` from the database and binds sci's
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

  THE CLUSTER OWNS CTX LIFETIME, and that is not a detail: `sci/fork`
  copies the env into a NEW atom, so vars created in a fork are
  invisible to the original (`reference-code/sci/src/sci/core.cljc:318-323`).
  Forking per FORM would therefore make a `defn` in form 1 invisible to
  form 2 — which is exactly the State A defect the plan records as
  `construct 6 is broken three ways`. So a supplied ctx is used AS
  GIVEN: cluster boot builds and acquires it once, and every agent in
  that cluster evaluates against the same live program graph. A def is
  visible immediately, including when its terminal transaction is
  refused; the refusal is durable session state, not a REPL rollback.
  When no ctx is supplied a fresh base is made for an isolated one-off.

  THE CTX IS SUPPLIED, NOT BUILT HERE. `build-base-ctx` is the minimum
  N3 needs —
  `clojure.core` and `clojure.string` in their interrupt-aware form
  plus bare `help`, the two `my.run` dispositions, and both `my.message`
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
            [clojure.main :as main]
            [clojure.test]
            [clojure.test.check.generators :as gen]
            [my.background]
            [my.message]
            [my.run]
            [sci.core :as sci]
            [sci.impl.vars :as sci.vars]
            [sci.impl.utils :as sci.utils]
            [sci.interrupt :as sci.interrupt]
            [seon.blob :as blob]
            [seon.bootstrap :as bootstrap]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.instrument :as instrument]
            [seon.print :as print]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]
            [seon.sci.kernel :as kernel]
            [seon.sci.reader :as reader]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(defn ctx?
  "True for a sci interpreter context.
  Sci's own vocabulary and its own shape: `sci/init` returns a map
  carrying the interpreter's environment, and `sci/fork` derives one
  from another."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value) (contains? value :env)))

(schema/register-core-predicate! 'seon.sci.eval/ctx? ctx?)

(defonce ^:private generator-ctx
  (delay (sci/init {})))

(def ctx-generator
  "A real ctx — honest by constructing an instance."
  (gen/fmap (fn [_] @generator-ctx) (gen/return nil)))

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The base context
;;; ---------------------------------------------------------------------------

(defn build-base-ctx
  "Build one independent SCI program context with the process guard."
  {:malli/schema [:=> [:cat] :seon.sci.eval/ctx]}
  []
  (let [{guard ::kernel/guard :as kernel-options} (kernel/context-options)
        run-ns (sci/create-ns 'my.run)
        background-ns (sci/create-ns 'my.background)
        message-ns (sci/create-ns 'my.message)
        bootstrap-ns (sci/create-ns 'seon.bootstrap)
        schema-ns (sci/create-ns 'seon.schema)
        test-ns (sci/create-ns 'clojure.test)
        ctx
        (sci/init
        {:interrupt-fn (:interrupt-fn kernel-options)
         :host-interop-observer (:host-interop-observer kernel-options)
         :built-in-call-observer (:built-in-call-observer kernel-options)
         ;; the interrupt-aware core: a lazy sequence built by NATIVE
         ;; clojure.core enters no interpreted body, so `(range)` inside
         ;; `reduce` would never hit the interrupt-fn. Sci ships drop-in
         ;; alternatives for exactly this and they are opt-in
         ;; (interrupt.md:56-60).
         :namespaces
         {'clojure.core sci.interrupt/clojure-core
          'clojure.string sci.interrupt/clojure-string
          ;; The schema surface is exactly its two lifecycle functions. Their
          ;; dynamic registration overlay is bound per evaluation below.
          'seon.schema
          {'register! (sci/copy-var schema/register! schema-ns)
           'unregister! (sci/copy-var schema/unregister! schema-ns)}
          ;; A deftest must have clojure.test's actual macro and Var semantics.
          ;; Derive the complete public namespace instead of maintaining a
          ;; second hand list that silently omits a runnable test operation.
          'clojure.test
          (into {}
                (map (fn [[sym v]] [sym (sci/copy-var* v test-ns)]))
                (ns-publics 'clojure.test))
          'my.run {'wait (sci/copy-var my.run/wait run-ns)
                   'complete (sci/copy-var my.run/complete run-ns)}
          'my.background
          {'background (sci/copy-var my.background/background background-ns)
           'poll (sci/copy-var my.background/poll background-ns)
           'await (sci/copy-var my.background/await background-ns)}
          ;; the second agent-facing value family, bound the same way and
          ;; for the same reason: it is a PURE function returning a map,
          ;; so copying the var into the base ctx is the whole binding —
          ;; there is no capability to thread, no connection to close
          ;; over, and nothing an agent could hold onto after the eval
          'my.message {'send (sci/copy-var my.message/send message-ns)
                       ;; the can't-fix answer is the same kind of value,
                       ;; so it is the same kind of binding. Without it a
                       ;; routed problem has no third arm at all: the
                       ;; owner's only reachable answers are "fixed" —
                       ;; which no fact can confirm — and silence.
                   'decline (sci/copy-var my.message/decline message-ns)}}
         ;; two broad roots rather than an enumeration of exception
         ;; subclasses — an agent needs to catch things, not to be given
         ;; a curated taxonomy
         :classes {'Throwable Throwable
                   'java.lang.Throwable Throwable
                   'Error Error
                   'java.lang.Error Error}})
        help-var (sci/copy-var bootstrap/help bootstrap-ns)
        dir-var (sci/copy-var bootstrap/dir bootstrap-ns)
        doc-var (sci/copy-var bootstrap/doc bootstrap-ns)]
    ;; `dir` and `doc` are REPL operations, so every namespace resolves
    ;; them bare through the same clojure.core refer it already receives.
    ;; `acquire!` replaces only `doc` with its row-derived macro.
    (sci/add-namespace!
     ctx 'clojure.core
     {'dir dir-var
      'doc doc-var
      'help help-var})
    (sci/add-namespace!
     ctx 'seon.bootstrap
     {'dir dir-var
      'doc doc-var
      'help help-var})
    (assoc ctx
           ::kernel/guard guard
           ::kernel/installed-functions (atom #{})
           ::kernel/program-snapshot (atom {:functions {} :namespaces {}}))))

(defn agent-namespace
  "The namespace name assigned to `agent-id`, or nil when it is absent.

  This is the forward read of `seon.cluster.agent/owner-of`: evaluation
  reads the committed assignment fact and never reconstructs it from an
  agent id naming convention."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.agent/id]
                  [:maybe :seon.ns/name]]}
  [db agent-id]
  (db/q '[:find ?namespace-name .
          :in $ ?agent-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          [?agent :seon.cluster.agent/namespace ?namespace]
          [?namespace :seon.ns/name ?namespace-name]]
        db agent-id))

;;; ---------------------------------------------------------------------------
;;; The armed boundary
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; The one operation
;;; ---------------------------------------------------------------------------

(defn- bounded-output
  "What the form printed, capped by the projection's own string cap.
  One cap, not a second dial: output and projected strings are the same
  kind of agent-visible text and are bounded the same way."
  [writer caps]
  (let [text (str writer)
        limit (:seon.config.eval.result/max-string caps)]
    (if (<= (count text) limit)
      text
      (subs text 0 limit))))

(declare deleted-schema-key)

(defn- row
  "Return the one reader declaration eligible for durable publication.
  A function without its complete contract is deliberately absent."
  [event projection]
  (or
   (let [deletion (program/deletion-row event)]
     (when (deleted-schema-key deletion) deletion))
   (let [row (program/declaration-row event :contracted)]
     (cond
       (:seon.fn/sym row)
       (let [function-symbol (symbol (:seon.fn/sym row))
             definition (edn/read-string (:seon.fn/spec row))]
         (schema/projection-with-function-contract
          projection function-symbol definition
          {:seon.schema.admission/source :agent})
         row)

       ;; The reader owns identity and exact source; the evaluated declaration
       ;; supplies the canonical value below. Raw syntax is not schema data.
       (:seon.schema/key row) row

       (:seon.test/sym row) row

       :else nil))))

(defn- removed-program-identities
  "Function and test identities removed from SCI's own intern tables."
  [before after]
  (into []
        (mapcat
         (fn [[namespace-name intern-names]]
           (mapcat (fn [intern-name]
                     (let [qualified (str (symbol (str namespace-name)
                                                  (str intern-name)))]
                       [[:seon.fn/sym qualified]
                        [:seon.test/sym qualified]
                        [:seon.def/id qualified]]))
                   (sort-by str
                            (remove (get after namespace-name #{})
                                    intern-names)))))
        before))

(def ^:private absent-intern (Object.))

(defn store-faithful-edn
  "Serialized value exactly when EDN preserves value, class, and metadata."
  {:malli/schema [:=> [:cat :any] [:maybe :string]]}
  [value]
  (try
    (let [serialized (binding [*print-meta* true] (pr-str value))
          restored (edn/read-string serialized)]
      (when (and (= value restored)
                 (= (class value) (class restored))
                 (= (meta value) (meta restored)))
        serialized))
    (catch Throwable _ nil)))

(defn store-faithful?
  "True exactly when the real EDN round trip preserves all fidelity axes."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (boolean (store-faithful-edn value)))

(defn- intern-values
  "Dereferenced SCI intern roots keyed by qualified name.

  This intentionally snapshots values, not env maps or Var identities: SCI
  redefinition mutates the existing Var root and assocs the identical Var back
  into the env. Values are not traversed here."
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

(defn- same-intern-value?
  [left right]
  (or (identical? left right)
      (and (not (identical? absent-intern left))
           (not (identical? absent-intern right))
           (= (class left) (class right))
           (= (meta left) (meta right))
           (= left right))))

(defn- resolved-var?
  [value]
  (or (sci.utils/var? value)
      (var? value)))

(defn- resolved-var-symbol
  [candidate resolved]
  (let [{var-namespace :ns var-name :name} (meta resolved)]
    (if (and var-namespace var-name)
      ;; SCI's implementation uses only these two metadata fields, so the
      ;; same projection is valid for both sci.lang.Var and clojure.lang.Var.
      (sci/var->symbol resolved)
      candidate)))

(defn- resolved-form-vars
  "Every Var a form mentions, resolved after the form changed its ctx.
  Over-approximation is deliberate: a shadowed local can only make purity
  fail closed; a qualified or macro-expanded host touch is independently
  observed by SCI's analyzer."
  [ctx namespace-name form]
  (sci/binding [sci/ns (sci/create-ns namespace-name)]
    (into #{}
          (comp
           (filter symbol?)
           (keep (fn [candidate]
                   (try
                     (let [resolved (sci/resolve ctx candidate)]
                       (when (resolved-var? resolved)
                         (resolved-var-symbol candidate resolved)))
                     (catch Throwable _ nil)))))
          (tree-seq coll? seq form))))

(defn- unproven-called-vars
  "Non-builtin Vars occurring in call position. A missing program row for one
  is not silently pure: this is the fail-closed edge for session macros and
  other process-local callables outside :seon.fn."
  [ctx namespace-name form]
  (sci/binding [sci/ns (sci/create-ns namespace-name)]
    (into #{}
          (keep
           (fn [expression]
             (when (seq? expression)
               (let [candidate (first expression)]
                 (when (symbol? candidate)
                   (try
                     (let [resolved (sci/resolve ctx candidate)]
                       (when (and (resolved-var? resolved)
                                  (not (:sci/built-in (meta resolved))))
                         (resolved-var-symbol candidate resolved)))
                     (catch Throwable _ nil)))))))
          (tree-seq coll? seq form))))

;; SCI marks built-ins but carries no purity or determinism provenance. These
;; are therefore the small closed exception sets permitted by ruling #32,
;; applied to calls SCI observed actually executing (not symbols merely present
;; in a delayed function body). Clojure's sources establish the random/time/
;; identity-hash reads: core.clj:606-613, 5056-5068, 5301-5337, 7008-7013,
;; 7461-7468, 7548-7555, 7947-7954; SCI's system clock and `time` expansion are
;; namespaces.cljc:1377-1417. SCI's io bindings at namespaces.cljc:1489-1525
;; establish the mutable input/output calls.
(def ^:private nondeterministic-built-in-calls
  '#{clojure.core/gensym
     clojure.core/hash
     clojure.core/hash-ordered-coll
     clojure.core/hash-unordered-coll
     clojure.core/rand
     clojure.core/rand-int
     clojure.core/rand-nth
     clojure.core/random-sample
     clojure.core/random-uuid
     clojure.core/shuffle
     clojure.core/system-time})

(def ^:private impure-built-in-calls
  '#{clojure.core/flush
     clojure.core/newline
     clojure.core/pr
     clojure.core/print
     clojure.core/printf
     clojure.core/prn
     clojure.core/println
     clojure.core/read
     clojure.core/read-line})

(defn- built-in-replay-risks
  [observed-built-in-calls]
  {:seon.sci.eval/nondeterministic-calls
   (into #{} (filter nondeterministic-built-in-calls)
         observed-built-in-calls)
   :seon.sci.eval/impure-calls
   (into #{} (filter impure-built-in-calls)
         observed-built-in-calls)})

(defn- changed-session-defs
  [ctx namespace-name before source form contracted-function
   observed-built-in-calls]
  (let [after (intern-values ctx)
        replay-risks (built-in-replay-risks observed-built-in-calls)]
    (into []
          (comp
           (remove (fn [[qualified value]]
                     (or (= (str qualified) contracted-function)
                         (identical? absent-intern value)
                         (same-intern-value?
                          (get before qualified absent-intern) value))))
           (map (fn [[qualified value]]
                  (merge
                   {:seon.def/id (str qualified)
                    :seon.def/ns
                    [:seon.ns/name (symbol (namespace qualified))]
                    :seon.def/name (symbol (name qualified))
                    :seon.def/source source
                    :seon.sci.eval/value value
                    :seon.sci.eval/referenced-vars
                    (resolved-form-vars ctx namespace-name form)
                    :seon.sci.eval/unproven-called-vars
                    (unproven-called-vars ctx namespace-name form)}
                   replay-risks))))
          after)))

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
  [source namespace-name ctx]
  (let [events (reader/read
                (assoc (reader-context ctx namespace-name)
                       :seon.sci.reader/text source))]
    (cond
      (map? events)
      (throw (ex-info (:seon.error/message events) events))

      (= 1 (count events))
      (first events)

      :else
      (throw (ex-info "Evaluation requires exactly one reader event."
                      {:seon.error/kind ::reader-event-count
                       :seon.sci.reader/event-count (count events)})))))

(defn- binding-rows
  "Project SCI's effective resolver inputs into namespace components."
  [{aliases :seon.sci.reader/aliases
    imports ::imports
    refers :seon.sci.reader/refers
    requires ::requires}]
  {:seon.ns/requires
   (into #{}
         (map (fn [namespace-name]
                [:seon.ns/name namespace-name]))
         requires)
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
   (into #{}
         (map :seon.ns/name)
         (:seon.ns/requires row))})

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
     :contracted)))

(defn- context-projection
  "The latest schema projection held by one live cluster context."
  [ctx]
  (or (some-> (::projection-state ctx)
              deref
              :seon.schema/projection)
      (:seon.schema/projection ctx)))

(defn- evaluation-projection
  [{ctx :seon.sci.eval/ctx}]
  (or (context-projection ctx)
      (schema/current-projection)
      (schema/build-projection (schema/registered-schemas))))

(defn- advance-context-projection!
  "Advance a live context's projection at the database's basis transaction."
  [ctx db projection]
  (when-let [state (::projection-state ctx)]
    (let [basis-transaction (long (:max-tx db))]
      (swap! state
             (fn [current]
               (if (<= (long (or (::basis-transaction current)
                                  Long/MIN_VALUE))
                       basis-transaction)
                 {::basis-transaction basis-transaction
                  :seon.schema/projection projection}
                 current)))))
  projection)

(defn- instrumentation-config
  "Read the contract dial and admission caps from this database value."
  [db]
  (let [cluster-name
        (db/q '[:find ?cluster .
               :where
               [?config :seon.config/cluster ?cluster]
               [?config :seon.config/on-core-error _]]
             db)
        effective (when cluster-name (config/effective db cluster-name))]
    {:seon.config/on-core-error
     (or (:seon.config/on-core-error effective) :record)
     :seon.sci.admit/caps (config/result-caps effective)}))

(defn- install-function-contract!
  [ctx committed projection db]
  (when-let [spec-edn (:seon.fn/spec committed)]
    (let [function-symbol (symbol (:seon.fn/sym committed))
          sci-var (sci/resolve ctx function-symbol)
          {:keys [:seon.config/on-core-error :seon.sci.admit/caps]}
          (instrumentation-config db)]
      (sci/bind-root!
       ctx sci-var
       (instrument/wrap-interpreted
        function-symbol spec-edn projection on-core-error caps @sci-var))))
  nil)

(defn install-row!
  "Install one declaration from the terminal transaction's db-after.
  The exact committed row is resolved by identity. Receipts are never
  consulted."
  {:malli/schema [:=> [:cat :seon.sci.eval/install-request] :map]}
  [{ctx :seon.sci.eval/ctx
    db :seon.db/db
    row :seon.program/row}]
  (let [projection (or (context-projection ctx)
                       (schema/projection-from-database db))
        [identity-attribute value]
        (some (fn [attribute]
                (when-some [value (get row attribute)]
                  [attribute value]))
              (conj program/identity-attributes
                    :seon.program/delete-identities))
        committed (when-not (= identity-attribute
                               :seon.program/delete-identities)
                    (db/pull db
                            (if (= identity-attribute :seon.ns/name)
                              '[* {:seon.ns/requires [:seon.ns/name]}
                                  {:seon.ns/aliases [*]}
                                  {:seon.ns/imports [*]}
                                  {:seon.ns/refers [*]}]
                              '[*])
                            [identity-attribute value]))]
    (when (and (#{:seon.fn/sym :seon.test/sym} identity-attribute)
               (let [source-attribute
                     (:seon.program/source-attribute
                      (program/shape identity-attribute))]
                 (not= (get row source-attribute)
                       (get committed source-attribute))))
      (throw (ex-info "Committed declaration source does not match install request."
                      {:seon.error/kind ::install-source-mismatch
                       :seon.program/identity [identity-attribute value]})))
    (let [installed
          (case identity-attribute
      :seon.ns/name
      (let [namespace-name (:seon.ns/name committed)]
        (sci/install-namespace-bindings!
         ctx namespace-name (row-bindings committed))
        {:seon.schema/projection projection
         :seon.sci.eval/installed 1})

      :seon.fn/sym
      (let [namespace-name (second (:seon.fn/ns row))
            function-symbol (symbol (:seon.fn/sym committed))
            event (when-not (::evaluated? row)
                    (one-event (:seon.fn/source committed)
                               namespace-name ctx))
            next-projection
            (schema/projection-from-database db projection)]
        (when event
          (sci/binding [sci/ns (sci/create-ns namespace-name)]
            (sci/eval-form ctx (:seon.sci.reader/form event))))
        (kernel/cache-function!
         ctx function-symbol
         {::function-source (:seon.fn/source committed)
          ::function-namespace namespace-name
          ::function-private? (:seon.fn/private? committed)
          ::agent-authored? true})
        (kernel/mark-installed! ctx function-symbol)
        (when-not (::skip-contract-install? row)
          (install-function-contract! ctx committed next-projection db))
        {:seon.schema/projection next-projection
         :seon.sci.eval/installed
         (if (::skip-contract-install? row) 0 1)})

      :seon.schema/key
      {:seon.schema/projection
       (schema/projection-from-database db projection)
       :seon.sci.eval/installed 1}

      :seon.test/sym
      (let [namespace-name (second (:seon.test/ns row))
            event (when-not (::evaluated? row)
                    (one-event (:seon.test/source committed)
                               namespace-name ctx))]
        (when event
          (sci/binding [sci/ns (sci/create-ns namespace-name)]
            (sci/eval-form ctx (:seon.sci.reader/form event))))
        {:seon.schema/projection projection
         :seon.sci.eval/installed 1})

      :seon.program/delete-identities
      (let [schema-deletion? (boolean (deleted-schema-key row))
            namespace-name (second (:seon.program/ns row))]
        (when-let [remaining
                   (some (fn [[identity-attribute identity-value]]
                           (when (db/pull db [:db/id]
                                         [identity-attribute identity-value])
                             [identity-attribute identity-value]))
                         value)]
          (throw (ex-info "Deleted declaration is still present after commit."
                          {:seon.error/kind ::install-delete-mismatch
                           :seon.program/identity remaining})))
        (when (and (not schema-deletion?)
                   (nil? (::namespace-state row)))
          (let [event (one-event (:seon.program/source row)
                                 namespace-name ctx)]
            (sci/binding [sci/ns (sci/create-ns namespace-name)]
              (sci/eval-form ctx (:seon.sci.reader/form event)))))
        {:seon.schema/projection
         (schema/projection-from-database db projection)
         :seon.sci.eval/installed 1})
      {:seon.schema/projection projection
       :seon.sci.eval/installed 0})]
      ;; Namespace mutations execute in an isolated fork. The exact SCI state
      ;; becomes visible only here, after the terminal transaction has proved
      ;; that every durable declaration/context change committed. Replaying
      ;; source would re-run dynamic target expressions against later state
      ;; and cannot preserve import masks, which are resolver state rather
      ;; than program identities.
      (when-let [namespace-state (::namespace-state row)]
        (sci/install-namespace-state! ctx namespace-state))
      (advance-context-projection!
       ctx db (:seon.schema/projection installed))
      installed)))

(defn- admission-source
  [db source-tx]
  (:seon.schema.admission/source
   (schema/admission-from-asserting-transaction db source-tx)))

(defn- forwarding-host-var
  "An SCI Var that calls the current root of one compiled host Var."
  [host-var sci-namespace]
  (let [host-meta (meta host-var)
        local-name (:name host-meta)]
    (sci/new-var local-name host-var
                 (assoc host-meta :name local-name :ns sci-namespace))))

(defn- install-loaded-first-party-namespaces!
  "Bind loaded first-party namespaces as their actual compiled JVM Vars.

  Namespace membership is the intersection of core-provenanced program rows
  and Clojure's loaded namespace set. Existing public bindings remain
  available, and every indexed function Var is added regardless of its
  `:seon.fn/private?` attribute. Direct bindings retain those Vars; a target
  named by a declared refer becomes an SCI Var whose root is the real Var,
  because SCI's resolver requires that shape. Both paths therefore observe a
  re-evaluated `defn` without reacquisition. Privacy is a rendering and
  curation fact, never an execution boundary; non-function private Vars remain
  outside publication.

  Safety residual from ruling #20: once execution enters one compiled host
  call, SCI's interrupt hook sees no interpreted function entrance. Runaway
  work inside that call is bounded by the submit-level wedge backstop, not the
  evaluation time-limit."
  [ctx namespace-assertions source-for-transaction namespace-rows
   function-rows]
  (let [first-party-names
        (into #{}
              (comp
               (filter (fn [[_ _ source-tx]]
                         (= :core (source-for-transaction source-tx))))
               (map first))
              namespace-assertions)
        loaded-by-name (into {} (map (juxt ns-name identity)) (all-ns))
        indexed-function-names
        (reduce (fn [by-namespace [function-symbol _source namespace-name
                                  _source-tx _private?]]
                  (update by-namespace namespace-name (fnil conj #{})
                          (symbol (name (symbol function-symbol)))))
                {}
                function-rows)
        referred-symbols
        (into #{}
              (comp (map row-bindings) (mapcat (comp vals :refers)))
              namespace-rows)]
    (doseq [namespace-name (sort-by str first-party-names)
            :let [host-namespace (get loaded-by-name namespace-name)]
            :when host-namespace]
      (let [sci-namespace (sci/create-ns namespace-name)]
        (sci/add-namespace!
         ctx namespace-name
         (into {}
               (map
                (fn [[local-name host-var]]
                  [local-name
                   (if (contains?
                        referred-symbols
                        (symbol (str namespace-name) (str local-name)))
                     (forwarding-host-var host-var sci-namespace)
                     host-var)]))
               (select-keys
                (ns-interns host-namespace)
                (into (set (keys (ns-publics host-namespace)))
                      (get indexed-function-names namespace-name)))))))))

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
  [{:seon.fn/arities
    [:seon.fn.arity/order
     :seon.fn.arity/arity
     {:seon.fn.arity/input-refs
      [:seon.schema/key :seon.schema/form]}
     {:seon.fn.arity/output-refs
      [:seon.schema/key :seon.schema/form]}]}])

(defn- program-documentation-config
  [db]
  (let [cluster-name
        (db/q '[:find ?cluster .
                :where [?config :seon.config/cluster ?cluster]]
              db)
        effective
        (merge
         (config/defaults)
         (when cluster-name
           (db/pull db
                    [:seon.config/on-core-error
                     :seon.config.eval.result/max-depth
                     :seon.config.eval.result/max-collection
                     :seon.config.eval.result/max-string
                     :seon.config.eval.result/max-nodes
                     :seon.print/length
                     :seon.print/level]
                    [:seon.config/cluster cluster-name])))]
    {:seon.sci.admit/caps (config/result-caps effective)
     :seon.config/on-core-error (:seon.config/on-core-error effective)
     :seon.print/options
     (select-keys effective [:seon.print/length :seon.print/level])}))

(defn- schema-form-text
  [configuration standard-error? schema-ref]
  (let [form (edn/read-string (:seon.schema/form schema-ref))]
    (when-not (and standard-error?
                   (= :seon.error/value (:seon.schema/key schema-ref)))
      (let [admitted
            (admit/admit-value
             {:seon.sci.admit/value form
              :seon.sci.admit/interrupt-fn (constantly nil)
              :seon.sci.admit/caps (:seon.sci.admit/caps configuration)
              :seon.config/on-core-error
              (:seon.config/on-core-error configuration)})]
        (print/emit-text (:seon.sci.admit/print-node admitted)
                         (:seon.print/options configuration))))))

(defn- role-contract-lines
  [configuration standard-error? label schema-refs]
  (let [first-prefix (case label
                       :input "  in:  "
                       :output "  out: ")]
    (mapv
     (fn [index schema-ref]
       (str (if (zero? index) first-prefix "       ")
            (:seon.schema/key schema-ref)
            (when-let [form-text
                       (schema-form-text
                        configuration standard-error? schema-ref)]
              (str "  " form-text))))
     (range)
     (sort-by (comp str :seon.schema/key) schema-refs))))

(defn- arity-contract-lines
  [configuration standard-error? arities]
  (let [ordered-arities (sort-by :seon.fn.arity/order arities)
        multiple? (< 1 (count ordered-arities))
        blocks
        (keep
         (fn [arity]
           (let [lines
                 (into
                  (role-contract-lines
                   configuration standard-error? :input
                   (:seon.fn.arity/input-refs arity))
                  (role-contract-lines
                   configuration standard-error? :output
                   (:seon.fn.arity/output-refs arity)))]
             (when (seq lines)
               (if multiple?
                 (into [(str "  arity "
                             (:seon.fn.arity/arity arity)
                             ":")]
                       lines)
                 lines))))
         ordered-arities)]
    (vec (mapcat identity (interpose [""] blocks)))))

(defn- program-documentation
  "Public function documentation derived from one database value."
  [db projection]
  (let [configuration (program-documentation-config db)
        standard-error?
        (= :core
           (get-in
            projection
            [:seon.schema.projection/schema-admissions
             :seon.error/value
             :seon.schema.admission/source]))]
    (into {}
          (map
           (fn [[function-symbol doc arglists function]]
             [function-symbol
              {:seon.fn/doc doc
               :seon.fn/arglists arglists
               ::contract-lines
               (arity-contract-lines
                configuration standard-error?
                (:seon.fn/arities function))}]))
          (db/q '[:find ?function-symbol ?doc ?arglists
                         (pull ?function selector)
                  :in $ selector
                  :where
                  [?function :seon.fn/sym ?function-symbol]
                  [?function :seon.fn/doc ?doc]
                  [?function :seon.fn/arglists ?arglists]
                  [?function :seon.fn/private? false]]
                db program-documentation-selector))))

(defn- program-doc-var
  "An SCI `doc` macro whose printed function facts came from acquisition."
  [ctx documentation]
  (let [repl-ns (sci/create-ns 'clojure.repl)
        fallback @(sci/resolve ctx 'clojure.repl/doc)]
    (sci/new-macro-var
     'doc
     (fn [form env function-symbol]
       (if-let [{:seon.fn/keys [doc arglists]
                 contract-lines ::contract-lines}
                (get documentation (str function-symbol))]
         (let [ordinary-lines ["-------------------------"
                               (str function-symbol)
                               arglists]
               print-doc (list 'clojure.core/println " " doc)]
           (cons 'do
                 (concat (map #(list 'clojure.core/println %) ordinary-lines)
                         [print-doc]
                         (map #(list 'clojure.core/println %) contract-lines)
                         [nil])))
         (fallback form env function-symbol)))
     {:ns repl-ns})))

(defn- install-program-doc!
  "Install one acquired program-doc projection without retaining the db."
  [ctx db projection]
  (let [doc-var (program-doc-var ctx (program-documentation db projection))]
    ;; Preserve qualified `clojure.repl/doc` and expose the same macro bare
    ;; through every namespace's ordinary clojure.core refer.
    (sci/add-namespace! ctx 'clojure.repl {'doc doc-var})
    (sci/add-namespace! ctx 'clojure.core {'doc doc-var})
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

(defn acquire!
  "Install declared renderer roots and agent code plus remaining compiled core.

  Renderer identities come only from the schema projection's explicit
  `:seon.render/ai` and `:seon.render/html` properties. Their durable definitions
  install from database source and call remaining first-party helpers through
  the documented host-call interruption ceiling. Current agent-authored
  namespaces, contracted functions, and tests use the same interpreted path.
  Receipts and eval results are outside all derivations by construction."
  {:malli/schema [:=> [:cat :seon.sci.eval/acquire-request] :map]}
  [{ctx :seon.sci.eval/ctx db :seon.db/db}]
  (let [projection (schema/projection-from-database db)
        ctx (assoc ctx :seon.schema/projection projection)
        source-for-transaction
        (memoize (fn [source-tx]
                   (admission-source db source-tx)))
        namespace-assertions
        (db/q '[:find ?namespace-name ?source ?source-tx
               :where
               [?namespace :seon.ns/name ?namespace-name]
               [?namespace :seon.ns/source ?source ?source-tx]]
             db)
        namespace-source-by-name
        (into {}
              (map (fn [[namespace-name source source-tx]]
                     [namespace-name [source source-tx]]))
              namespace-assertions)
        all-namespace-names
        (db/q '[:find [?namespace-name ...]
                :where
                [_ :seon.ns/name ?namespace-name]]
              db)
        agent-authored?
        (fn [source-tx]
          (= :agent (source-for-transaction source-tx)))
        all-function-rows
        (db/q '[:find ?sym ?source ?namespace-name ?source-tx ?private
                :where
                [?function :seon.fn/sym ?sym]
                [?function :seon.fn/source ?source ?source-tx]
                [?function :seon.fn/private? ?private]
                [?function :seon.fn/ns ?namespace]
                [?namespace :seon.ns/name ?namespace-name]]
              db)
        function-rows
        (into []
              (filter
               (fn [[_sym _ _ source-tx _]]
                 (agent-authored? source-tx)))
              all-function-rows)
        _ (install-program-doc! ctx db projection)
        selected-namespace-names
        (into (into #{} (map #(nth % 2)) function-rows)
              (comp
               (filter (fn [[_ _ source-tx]] (agent-authored? source-tx)))
               (map first))
              namespace-assertions)
        all-namespace-rows
        (into
         []
         (map (fn [namespace-name]
                (let [[source source-tx]
                      (get namespace-source-by-name namespace-name)]
                  (assoc
                   (db/pull db
                            '[* {:seon.ns/requires [:seon.ns/name]}
                                {:seon.ns/aliases [*]}
                                {:seon.ns/imports [*]}
                                {:seon.ns/refers [*]}]
                            [:seon.ns/name namespace-name])
                   ::namespace-source source
                   ::namespace-source-tx source-tx
                   ::agent-authored?
                   (boolean (and source-tx
                                 (agent-authored? source-tx)))))))
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
         (filter (fn [[_ _ _ source-tx]] (agent-authored? source-tx)))
         (db/q '[:find ?sym ?source ?namespace-name ?source-tx
                :where
                [?test :seon.test/sym ?sym]
                [?test :seon.test/source ?source ?source-tx]
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
                 (map (fn [[sym source namespace-name source-tx private?]]
                        [(symbol sym)
                         {::function-source source
                          ::function-source-tx source-tx
                          ::function-namespace namespace-name
                          ::function-private? private?
                          ::agent-authored? (agent-authored? source-tx)}]))
                 all-function-rows)
           all-namespace-row-by-name)
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
                  {:seon.error/kind ::namespace-binding-cycle
                   :seon.sci.eval/dependencies remaining})))
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
          (let [installed
                (install-row!
                 {:seon.sci.eval/ctx
                  (assoc ctx :seon.schema/projection
                         (:seon.schema/projection state))
                  :seon.db/db db
                  :seon.program/row row})]
            {:seon.schema/projection (:seon.schema/projection installed)
             :seon.sci.eval/installed
             (+ (:seon.sci.eval/installed state)
                (:seon.sci.eval/installed installed))}))]
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
    (install-loaded-first-party-namespaces!
     ctx namespace-assertions source-for-transaction all-namespace-rows
     all-function-rows)
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
              (map (fn [[sym source _ source-tx _]]
                     {:seon.fn/sym sym
                      :seon.fn/source source
                      :seon.fn/ns [:seon.ns/name namespace-name]
                      ::skip-contract-install?
                      (not (agent-authored? source-tx))})
                   (sort-by first
                            (get function-rows-by-ns namespace-name)))))
           {:seon.schema/projection projection
            :seon.sci.eval/installed 0}
           namespace-order)]
      ;; Tests resolve only after every namespace's functions and exact
      ;; bindings are present. This makes renamed `deftest` deterministic.
      (reduce
       (fn [state namespace-name]
         (reduce
          install-row
          state
          (map (fn [[sym source _ _]]
                 {:seon.test/sym sym
                  :seon.test/source source
                  :seon.test/ns [:seon.ns/name namespace-name]})
               (sort-by first (get test-rows-by-ns namespace-name)))))
       functions-installed
       namespace-order))))

(defn- install-function-from-database!
  "Install one selected function from the acquired database snapshot."
  [ctx _db function-symbol]
  (let [{source ::function-source
         namespace-name ::function-namespace
         agent-authored? ::agent-authored?}
        (kernel/program-function ctx function-symbol)
        namespace-row
        (kernel/program-namespace ctx namespace-name)]
    (when-not source
      (throw
       (ex-info "Selected function has no durable program row."
                {:seon.error/kind ::missing-function-row
                 :seon.fn/sym (str function-symbol)})))
    (let [required-names
          (into #{} (map :seon.ns/name) (:seon.ns/requires namespace-row))]
      (when-not agent-authored?
        (require namespace-name)
        (doseq [required-name required-names
                :let [host-namespace (find-ns required-name)]
                :when host-namespace]
          (install-host-namespace! ctx required-name
                                   (ns-publics host-namespace)))
        (install-host-namespace! ctx namespace-name
                                 (ns-interns (find-ns namespace-name))))
      (install-declared-classes! ctx [namespace-row])
      (sci/install-namespace-bindings!
       ctx namespace-name (assoc (row-bindings namespace-row) :refers {}))
      (sci/install-namespace-bindings! ctx namespace-name
                                       (row-bindings namespace-row))
      (let [event (one-event source namespace-name ctx)]
        (sci/binding [sci/ns (sci/create-ns namespace-name)]
          (sci/eval-form ctx (:seon.sci.reader/form event))))
      (kernel/mark-installed! ctx function-symbol)))
  function-symbol)

(defn install-session-image!
  "Restore namespace session definitions into one cold cluster ctx.

  Pass 1 creates every namespace and interns every name unbound. Pass 2
  evaluates only rows whose source presence records the terminal transaction's
  purity proof, in deterministic ordinal/id order. Unrestorable rows remain
  unbound; their durable fact is the statement of what is absent."
  {:malli/schema [:=> [:cat :seon.sci.eval/session-install-request] :map]}
  [{ctx :seon.sci.eval/ctx
    db :seon.db/db
    connection :seon.db/connection}]
  (let [entry-ids (db/q '[:find [?entry ...]
                         :where [?entry :seon.def/id _]]
                       db)
        rows
        (->> (db/pull-many db
                          '[* {:seon.def/ns [:seon.ns/name]}]
                          entry-ids)
             (remove
              (fn [row]
                (some? (db/pull db [:db/id]
                               [:seon.fn/sym (:seon.def/id row)]))))
             (sort-by (juxt :seon.def/ordinal :seon.def/id))
             vec)]
    (doseq [{namespace-ref :seon.def/ns
             intern-name :seon.def/name} rows]
      (let [namespace-name (:seon.ns/name namespace-ref)]
        (when-not (sci/find-ns ctx namespace-name)
          (sci/add-namespace! ctx namespace-name {}))
        (sci/intern ctx namespace-name intern-name)))
    (doseq [{namespace-ref :seon.def/ns
             intern-name :seon.def/name
             value-edn :seon.def/value-edn
             digest :seon.def/blob}
            (filter #(or (:seon.def/value-edn %)
                         (:seon.def/blob %))
                    rows)]
      (let [serialized
            (or value-edn
                (when connection (blob/get connection digest))
                (throw
                 (ex-info "Session value blob is unavailable during restore."
                          {:seon.error/kind ::session-blob-unavailable
                           :seon.blob/digest digest})))
            value (edn/read-string serialized)]
        (sci/intern ctx (:seon.ns/name namespace-ref) intern-name value)))
    (doseq [{namespace-ref :seon.def/ns
             source :seon.def/source}
            (filter :seon.def/source rows)]
      (let [namespace-name (:seon.ns/name namespace-ref)
            namespace-object (sci/create-ns namespace-name)
            event (one-event source namespace-name ctx)]
        (sci/binding [sci/ns namespace-object]
          (sci/eval-form ctx (:seon.sci.reader/form event)))))
    {:seon.sci.eval/ctx ctx
     :seon.sci.eval/unrestorable
     (into []
           (keep (fn [row]
                   (when-let [reason (:seon.def/unrestorable-reason row)]
                     {:seon.def/id (:seon.def/id row)
                      :seon.def/unrestorable-reason reason})))
           rows)}))

(defn cluster-ctx
  "Build and cold-acquire one cluster's live SCI program context."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value] :seon.sci.eval/ctx]
    [:=> [:cat :seon.db/database-value :seon.db/connection]
     :seon.sci.eval/ctx]]}
  ([db]
   (cluster-ctx db nil))
  ([db connection]
   (let [ctx (assoc (build-base-ctx)
                    ::custody
                    {:seon.db/connection connection}
                    ::kernel/install-function!
                    install-function-from-database!)
         acquired (acquire! {:seon.sci.eval/ctx ctx
                             :seon.db/db db})
         projection (:seon.schema/projection acquired)
         ctx (assoc ctx
                    :seon.schema/projection projection
                    ::projection-state
                    (atom {::basis-transaction (long (:max-tx db))
                           :seon.schema/projection projection}))]
     (install-session-image!
      (cond-> {:seon.sci.eval/ctx ctx
               :seon.db/db db}
        connection (assoc :seon.db/connection connection)))
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
                  {:seon.error/kind ::schema-refused
                   :seon.schema/key unregister-key
                   :seon.sci.eval/value schema-value})))
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
                  {:seon.error/kind ::schema-refused
                   :seon.schema/key schema-key
                   :seon.sci.eval/value schema-value})))
              ;; Validate the actual evaluated value while the overlay is
              ;; isolated. The terminal transaction repeats this pure
              ;; candidate validation against its mid-transaction db value.
              (schema/projection-with-schema
               projection schema-key definition
               {:seon.schema.admission/source :agent})
              (assoc raw-row :seon.schema/form (pr-str definition))))
          raw-row)
        base-declared-row
        (if (:seon.fn/spec base-declared-row)
          (program/with-contract-facts
           {:seon.program/row base-declared-row
            :seon.program/compile-options
            (:seon.schema.projection/compile-options projection)
            :seon.program/predicate-functions
            (:seon.schema.projection/predicate-functions projection)
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
    source :seon.cluster.run.form/source}]
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
              (assoc ::namespace-state after-namespace-state))]
    {:seon.program/row row
     :seon.sci.eval/context-row context-row
     :seon.sci.eval/namespace-changed? namespace-changed?}))

(defn- success-evaluation
  [{admitted :seon.sci.eval/admitted
    caps :seon.sci.admit/caps
    printed :seon.sci.eval/printed
    namespace-name :seon.sci.eval/namespace-name
    ending-namespace :seon.sci.eval/ending-namespace
    print-options :seon.print/options
    session-defs :seon.sci.eval/session-defs
    row :seon.program/row}]
  (cond-> {:seon.sci.admit/value (:seon.sci.admit/value admitted)
           :seon.cluster.eval/result-edn
           (:seon.cluster.eval/result-edn admitted)
           :seon.print/options print-options
           :seon.cluster.eval/ns [:seon.ns/name namespace-name]
           :seon.sci.eval/ending-ns ending-namespace
           :seon.sci.admit/capped? (:seon.sci.admit/capped? admitted)
           :seon.sci.admit/record (:seon.sci.admit/record admitted)}
    row (assoc :seon.program/row row)
    (seq session-defs) (assoc :seon.sci.eval/session-defs session-defs)
    (seq (str printed))
    (assoc :seon.cluster.eval/output (bounded-output printed caps))))

(defn- failed-evaluation
  [{admitted :seon.sci.eval/admitted
    caps :seon.sci.admit/caps
    printed :seon.sci.eval/printed
    namespace-name :seon.sci.eval/namespace-name
    print-options :seon.print/options
    session-defs :seon.sci.eval/session-defs
    record :seon.sci.admit/record
    value :seon.sci.admit/value
    triage-edn :seon.cluster.eval/triage-edn
    interrupted-at :seon.cluster.eval/interrupted-at
    :as request}]
  (cond-> {:seon.sci.admit/value (:seon.sci.admit/value admitted)
           :seon.cluster.eval/result-edn
           (:seon.cluster.eval/result-edn admitted)
           :seon.print/options print-options
           :seon.cluster.eval/ns [:seon.ns/name namespace-name]
           :seon.sci.eval/ending-ns namespace-name
           :seon.cluster.eval/error (:seon.error/message value)
           :seon.sci.admit/capped? (:seon.sci.admit/capped? admitted)
           :seon.sci.admit/record record}
    (string? triage-edn)
    (assoc :seon.cluster.eval/triage-edn triage-edn)
    (seq session-defs) (assoc :seon.sci.eval/session-defs session-defs)
    (contains? request :seon.cluster.eval/interrupted-at)
    (assoc :seon.cluster.eval/interrupted-at interrupted-at)
    (seq (str printed))
    (assoc :seon.cluster.eval/output (bounded-output printed caps))))

(defn evaluate
  "Evaluate one form source and return what may leave the boundary.
  Runs synchronously on the caller's `:compute` workload task — this
  never blocks and never submits, because the two jobs the quarry's
  Semaphore conflated (backpressure and parallelism) now belong to the
  caller's work launcher.

  Order is the contract:
  1. bind the compiled `seon.db/*conn*` from the supplied cluster ctx,
     or nil for an isolated base ctx;
  2. use the SUPPLIED live cluster ctx, or make a fresh guarded base for
     an isolated one-off when none was given;
  3. arm through `kernel/arm` with `::time-limit-ms`, the ONLY limit —
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
  [{:keys [:seon.cluster.run.form/source :seon.sci.admit/caps]
    ctx :seon.sci.eval/ctx
    agent-id :seon.cluster.agent/id
    run-id :seon.cluster.run/id
    form-ordinal :seon.cluster.run.form/ordinal
    cluster-name :seon.boot/cluster-name
    work-launcher :seon.flow/work-launcher
    namespace-ref :seon.cluster.run.form/ns
    time-limit-ms :seon.sci.eval/time-limit-ms
    on-core-error :seon.config/on-core-error}]
  (let [;; a supplied ctx is used AS GIVEN — forking it here would
        ;; discard the caller's accumulated defs, which is the bug this
        ;; contract exists to not repeat
        evaluation-ctx (or ctx (build-base-ctx))
        ;; ARMING HAPPENS INSIDE THE BOUNDARY, and these reach it through
        ;; one volatile. `kernel/arm` refuses a DIFFERENT context already
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
        stop! (fn []
                (when-let [disarm (::kernel/stop! @arm-state)]
                  (disarm)))
        printed (java.io.StringWriter.)
        connection (get-in evaluation-ctx
                           [::custody :seon.db/connection])
        namespace-name (or (second namespace-ref)
                           (when (and connection agent-id)
                             (agent-namespace @connection agent-id))
                           'user)
        namespace-object (sci/create-ns namespace-name)
        ending-namespace (volatile! namespace-name)
        print-options (volatile! {})
        session-observation (volatile! nil)]
    (binding [db/*conn* connection
              effect/*request-context*
              (when (and run-id (some? form-ordinal) cluster-name)
                {:seon.db/connection connection
                 :seon.cluster.run/id run-id
                 :seon.cluster.run.form/ordinal form-ordinal
                 :seon.cluster.agent/id agent-id
                 :seon.flow/work-launcher work-launcher
                 :seon.boot/cluster-name cluster-name
                 :seon.sci.admit/caps caps
                 :seon.config/on-core-error on-core-error
                 :seon.effect/counter (atom -1)})]
      (try
        (let [_ (vreset! arm-state (kernel/arm evaluation-ctx time-limit-ms))
            before-reader-context
            (reader-context evaluation-ctx namespace-name)
            event (one-event source namespace-name evaluation-ctx)
            form (:seon.sci.reader/form event)
            namespace-unmap? (:seon.sci.reader/ns-unmap? event)
            execution-ctx (if namespace-unmap?
                            (sci/fork evaluation-ctx)
                            evaluation-ctx)
            before-intern-values (intern-values execution-ctx)
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
                            sci/print-length @sci/print-length
                            sci/print-level @sci/print-level
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
                    ;; remains and the agent's choice is unrecoverable.
                    (vreset! print-options
                             {:seon.print/length @sci/print-length
                              :seon.print/level @sci/print-level})))))
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
            {row :seon.program/row
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
              :seon.cluster.run.form/source source})
            ;; Durable declarations are installed only after the row commits.
            value (if context-row (:seon.ns/name context-row) evaluated-value)
              ;; INSIDE the boundary, BEFORE disarm: an infinite lazy
              ;; sequence dies at the time limit here rather than in the
              ;; receipt writer
              evaluation-record (record :ok)
              session-defs
              (changed-session-defs
               execution-ctx namespace-name before-intern-values source form
               (:seon.fn/sym row) (built-in-calls))
              admitted (admit/admit
                        {:seon.sci.admit/value value
                         :seon.sci.admit/interrupt-fn interrupt-fn
                         :seon.sci.admit/caps caps
                         ;; R41 travels WITH the request: admission does
                         ;; not read a dial of its own, and this
                         ;; evaluator does not default one
                         :seon.config/on-core-error on-core-error
                         :seon.sci.admit/record evaluation-record})]
          (success-evaluation
           {:seon.sci.eval/admitted admitted
            :seon.sci.admit/caps caps
            :seon.sci.eval/printed printed
            :seon.sci.eval/namespace-name namespace-name
            :seon.sci.eval/ending-namespace @ending-namespace
            :seon.print/options @print-options
            :seon.sci.eval/session-defs session-defs
            :seon.program/row row}))
        (catch Throwable throwable
          (let [record (record (if (kernel/interrupted? throwable)
                                 :time :error))
                session-defs
                (when-let [{failed-ctx :seon.sci.eval/ctx
                            before :seon.sci.eval/before-intern-values
                            failed-form :seon.sci.eval/form}
                           @session-observation]
                  ;; A failed evaluation has no durable program row. Any def
                  ;; it installed before the later throw/cut therefore belongs
                  ;; to the session image, including a contracted def whose
                  ;; declaration never reached the terminal transaction.
                  (changed-session-defs
                   failed-ctx namespace-name before source failed-form nil
                   (built-in-calls)))
                value (kernel/failure-value
                       {::kernel/time-limit-kind ::time-limit
                        ::kernel/failure-kind ::evaluation-failed}
                       throwable record)
                admitted
                (admit/admit
                 {:seon.sci.admit/value value
                  :seon.sci.admit/interrupt-fn (constantly nil)
                  :seon.sci.admit/caps caps
                  :seon.config/on-core-error :record
                  :seon.sci.admit/record record})]
          (failed-evaluation
           (cond-> {:seon.sci.eval/admitted admitted
                    :seon.sci.admit/caps caps
                    :seon.sci.eval/printed printed
                    :seon.sci.eval/namespace-name namespace-name
                    :seon.print/options @print-options
                    :seon.sci.eval/session-defs session-defs
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
                    (java.util.Date.))))))
        (finally
          (stop!))))))
