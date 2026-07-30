(ns seon.sci.eval
  "The guarded eval: one form, one armed boundary, one admitted value.

  DRAFT FOR ORCHESTRATOR SEAL REVIEW (drafted + implemented
  2026-07-27 — N3's last dependency, C7 from n3-plan §10). Adopted
  FRESH from the quarry's mechanism rather than ported: the old
  namespace's Semaphore, cached compute pool, database-derived binding
  table and lifecycle bindings are all gone, and what survives is the
  part that was right — one shared base ctx, one fork per evaluation,
  and time as the only limit.

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
  timer is cancelled afterwards, in `finally`. That ordering is the
  contract, not an implementation detail — after disarm there is no
  limit left to stop anything.

  NOTHING THROWS. Every failure is a flat `:seon.error` value carried
  inside an ordinary evaluation map: an agent's exception, a refused
  parse, a time limit, or a host failure all come back as data the run
  loop commits as a receipt. The loop has no catch around this call
  because there is nothing to catch.

  ONE OWNER FOR `interrupted?`. This namespace answers \"is this
  throwable sci's uncatchable interrupt?\" for the whole system.
  `seon.sci.admit` currently carries a private copy with a comment
  saying the two must not diverge; re-pointing it here is a one-line
  seal revision, flagged in the report rather than taken, because
  `admit.clj` is sealed.

  AN AGENT EVALUATES IN ITS OWN NAMESPACE, by construction. The eval
  binds sci's own `*ns*` to `my.agents.<id>` for the whole form, so a
  `defn` lands where the prompt says it lands and the model never needs
  to write `(in-ns …)` — which is what the first live drive tried, and
  what failed with `Can't change/establish root binding of
  clojure.core/*ns*`. The namespace name has ONE derivation
  (`agent-namespace`) shared with the prompt, because an agent told one
  name and evaluated in another would reason from a lie.

  OUTPUT IS CAPTURED, NOT DISCARDED. sci's `*out*` and `*err*` are
  unbound by default — `println` fails with `SciUnbound cannot be cast
  to java.io.Writer` — so both are bound to one `StringWriter` per
  evaluation and what the form printed rides back on the evaluation as
  `:seon.cluster.eval/output`, bounded by the same `max-string` cap the
  projection uses. Printed output is evidence an agent produced about
  its own work; a sink would have made it disappear, and the drive
  showed exactly how expensive disappeared evidence is.

  THE CALLER OWNS CTX LIFETIME, and that is not a detail: `sci/fork`
  copies the env into a NEW atom, so vars created in a fork are
  invisible to the original (`reference-code/sci/src/sci/core.cljc:318-323`).
  Forking per FORM would therefore make a `defn` in form 1 invisible to
  form 2 — which is exactly the State A defect the plan records as
  `construct 6 is broken three ways`. So a supplied ctx is used AS
  GIVEN: the run loop forks ONE ctx per run and hands it to every form
  of that plan, and the fold accumulates defs the way a REPL session
  does. When no ctx is supplied a fresh fork of the base is made, which
  is the isolated one-off case.

  THE CTX IS SUPPLIED, NOT BUILT HERE. `base` is the minimum N3 needs —
  `clojure.core` and `clojure.string` in their interrupt-aware form
  plus the two `my.run` dispositions and both `my.message` values — and a
  caller may pass its own.
  The computed binding table (capability functions derived from
  program-graph facts, filtered by a derived namespace policy) is N5's
  and is deliberately absent: a hand-listed callable surface here would
  be the L17 violation the quarry's own comment warned about.

  Crash walk: this namespace owns no durable state. A kill during an
  evaluation leaves the loop's running receipt (one with no terminal
  fact), which N2's `recover-tx` settles by asserting its
  `interrupted-at` — and rows 6 and 7 of the crash walk stay
  indistinguishable, which is honest: the form's effect MAY have
  happened. Nothing re-executes."
  (:require [clojure.edn :as edn]
            [clojure.test]
            [clojure.test.check.generators :as gen]
            [datahike.api :as d]
            [my.message]
            [my.run]
            [sci.core :as sci]
            [sci.interrupt :as sci.interrupt]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]
            [seon.sci.reader :as reader])
  (:import [java.lang.management ManagementFactory]
           [java.util.concurrent ScheduledThreadPoolExecutor TimeUnit]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/eval.edn
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

(defonce ^:private base-ctx
  (delay
    (let [run-ns (sci/create-ns 'my.run)
          message-ns (sci/create-ns 'my.message)
          schema-ns (sci/create-ns 'seon.schema)
          test-ns (sci/create-ns 'clojure.test)]
      (sci/init
       {;; the interrupt-aware core: a lazy sequence built by NATIVE
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
                  'java.lang.Error Error}}))))

(defn agent-namespace
  "The ONE namespace name for an agent: `my.agents.<id>`.
  One derivation, because the prompt tells the agent this name and the
  evaluator evaluates in it — if those two ever disagreed the agent
  would be told a lie it then reasons from."
  {:malli/schema [:=> [:cat :seon.cluster.agent/id] :symbol]}
  [agent-id]
  (symbol (str "my.agents." agent-id)))

(defn base
  "The process-shared base context every evaluation forks.
  One ctx per process, not per evaluation: building it is the expensive
  part and forking is the cheap part, which is the whole reason sci has
  `fork`."
  {:malli/schema [:=> [:cat] :seon.sci.eval/ctx]}
  []
  @base-ctx)

;;; ---------------------------------------------------------------------------
;;; The armed boundary
;;; ---------------------------------------------------------------------------

(defn interrupted?
  "True when `throwable` is sci's uncatchable interrupt.
  THE single owner of this question. Read from sci's own marker key
  (`reference-code/sci/src/sci/interrupt.cljc:41`) rather than from a
  message or a class, because the marker is the only thing sandboxed
  code cannot forge."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [throwable]
  (contains? (ex-data throwable) :sci.impl/interrupt))

(def ^:private thread-mx (ManagementFactory/getThreadMXBean))

(defn- allocated-bytes
  "Allocated bytes for the calling platform thread, or -1."
  []
  (.getCurrentThreadAllocatedBytes
   ^com.sun.management.ThreadMXBean thread-mx))

(defonce ^:private deadline-timer
  (doto (ScheduledThreadPoolExecutor.
         1
         (reify java.util.concurrent.ThreadFactory
           (newThread [_ runnable]
             (doto (Thread. runnable "seon-sci-time-limit")
               (.setDaemon true)))))
    (.setRemoveOnCancelPolicy true)))

;;; The sampling mask: allocation is read every 1024th entrance rather
;;; than every one, because the interrupt-fn runs on EVERY interpreted
;;; body and its cost is the interpreter's cost. The time flag is read
;;; every time — that one is the limit and may not be sampled.
(def ^:private allocation-sample-mask 1023)

(defn- arm
  "Arm one evaluation's interrupt-fn; return it with stop! and record.
  A scheduled task flips a volatile at the deadline — the interrupt-fn
  itself does no clock arithmetic, because it runs on every interpreted
  body entrance and its cost is the interpreter's cost."
  [time-limit-ms]
  (let [entries (long-array 1)
        sampled (long-array 1)
        reached? (volatile! false)
        outcome (volatile! nil)
        started-at (System/nanoTime)
        allocated-at-start (allocated-bytes)
        measurable? (not (neg? allocated-at-start))
        task (.schedule deadline-timer
                        ^Runnable #(vreset! reached? true)
                        (long time-limit-ms)
                        TimeUnit/MILLISECONDS)]
    {:interrupt-fn
     (fn []
       (let [count (unchecked-inc (aget entries 0))]
         (aset entries 0 count)
         (when @reached?
           (vreset! outcome :time)
           (sci.interrupt/interrupt! "time-limit"))
         (when (and measurable? (zero? (bit-and count allocation-sample-mask)))
           (aset sampled 0 (- (allocated-bytes) allocated-at-start)))))
     ::stop! (fn [] (.cancel task false) nil)
     ::record
     (fn [final-outcome]
       {:seon.eval/fn-entries (aget entries 0)
        :seon.eval/duration-ms (quot (- (System/nanoTime) started-at) 1000000)
        :seon.eval/allocated-bytes (if measurable?
                                     (max (aget sampled 0)
                                          (- (allocated-bytes)
                                             allocated-at-start))
                                     -1)
        :seon.eval/outcome (or @outcome final-outcome)})}))

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

(defn- diagnosis
  [throwable record]
  (if (= :time (:seon.eval/outcome record))
    (str "Ran out of time after " (:seon.eval/duration-ms record) "ms.")
    (or (ex-message throwable) (.getName (class throwable)))))

(defn- failure-value
  "The flat error value an agent sees for a failed evaluation."
  [throwable record]
  {:seon.error/kind (if (= :time (:seon.eval/outcome record))
                      ::time-limit
                      ::evaluation-failed)
   :seon.error/message (diagnosis throwable record)
   :seon.error/data (cond-> {:seon.sci.eval/throwable
                             (.getName (class throwable))
                             :seon.sci.admit/record record}
                      (ex-data throwable)
                      (assoc :seon.sci.eval/data (ex-data throwable)))})

(declare deleted-schema-key)

(defn- program-row
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
                        [:seon.test/sym qualified]]))
                   (sort-by str
                            (remove (get after namespace-name #{})
                                    intern-names)))))
        before))

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
  (let [{:keys [aliases refers requires]}
        (sci/namespace-bindings ctx namespace-name)]
    {:seon.sci.reader/ns namespace-name
     :seon.sci.reader/aliases aliases
     :seon.sci.reader/refers refers
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
    refers :seon.sci.reader/refers
    requires ::requires}]
  {:seon.ns/requires
   requires
   :seon.ns/aliases
   (into #{}
         (map (fn [[local target-ns]]
                {:seon.ns.alias/local local
                 :seon.ns.alias/target-ns target-ns}))
         aliases)
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
   :refers
   (into {}
         (map (fn [{:seon.ns.refer/keys [local target-ns target-name]}]
                [local (symbol (str target-ns) (str target-name))]))
         (:seon.ns/refers row))
   :requires (or (:seon.ns/requires row) #{})})

(defn- namespace-context-row
  [namespace-name source before after]
  (when (not= (select-keys before [:seon.sci.reader/aliases
                                   :seon.sci.reader/refers ::requires])
              (select-keys after [:seon.sci.reader/aliases
                                  :seon.sci.reader/refers ::requires]))
    (program/declaration-row
     (merge
      {:seon.ns/name namespace-name
       :seon.ns/source source}
      (binding-rows after))
     :contracted)))

(defn install-program-row!
  "Install one declaration from the terminal transaction's db-after.
  The exact committed row is resolved by identity. Receipts are never
  consulted."
  {:malli/schema [:=> [:cat :seon.sci.eval/install-request] :map]}
  [{ctx :seon.sci.eval/ctx
    db :seon.db/db
    row :seon.sci.eval/program-row}]
  (let [projection (or (:seon.schema/projection ctx)
                       (schema/projection-from-database db))
        [identity value]
        (some (fn [attribute]
                (when-some [value (get row attribute)]
                  [attribute value]))
              (conj program/identity-attributes
                    :seon.program/delete-identities))
        committed (when-not (= identity :seon.program/delete-identities)
                    (d/pull db
                            (if (= identity :seon.ns/name)
                              '[* :seon.ns/requires
                                  {:seon.ns/aliases [*]}
                                  {:seon.ns/refers [*]}]
                              '[*])
                            [identity value]))]
    (when (and (#{:seon.fn/sym :seon.test/sym} identity)
               (let [source-attribute
                     (:seon.program/source-attribute
                      (program/shape identity))]
                 (not= (get row source-attribute)
                       (get committed source-attribute))))
      (throw (ex-info "Committed declaration source does not match install request."
                      {:seon.error/kind ::install-source-mismatch
                       :seon.program/identity [identity value]})))
    (case identity
      :seon.ns/name
      (let [namespace-name (:seon.ns/name committed)]
        (sci/install-namespace-bindings!
         ctx namespace-name (row-bindings committed))
        {:seon.schema/projection projection
         :seon.sci.eval/installed 1})

      :seon.fn/sym
      (let [namespace-name (second (:seon.fn/ns row))
            event (one-event (:seon.fn/source committed) namespace-name ctx)]
        (sci/binding [sci/ns (sci/create-ns namespace-name)]
          (sci/eval-form ctx (:seon.sci.reader/form event)))
        {:seon.schema/projection
         (schema/projection-from-database db projection)
         :seon.sci.eval/installed 1})

      :seon.schema/key
      {:seon.schema/projection
       (schema/projection-from-database db projection)
       :seon.sci.eval/installed 1}

      :seon.test/sym
      (let [namespace-name (second (:seon.test/ns row))
            event (one-event (:seon.test/source committed) namespace-name ctx)]
        (sci/binding [sci/ns (sci/create-ns namespace-name)]
          (sci/eval-form ctx (:seon.sci.reader/form event)))
        {:seon.schema/projection projection
         :seon.sci.eval/installed 1})

      :seon.program/delete-identities
      (let [schema-deletion? (boolean (deleted-schema-key row))
            namespace-name (second (:seon.program/ns row))]
        (when-let [remaining
                   (some (fn [[identity-attribute identity-value]]
                           (when (d/pull db [:db/id]
                                         [identity-attribute identity-value])
                             [identity-attribute identity-value]))
                         value)]
          (throw (ex-info "Deleted declaration is still present after commit."
                          {:seon.error/kind ::install-delete-mismatch
                           :seon.program/identity remaining})))
        (when-not schema-deletion?
          (let [event (one-event (:seon.program/source row)
                                 namespace-name ctx)]
            (sci/binding [sci/ns (sci/create-ns namespace-name)]
              (sci/eval-form ctx (:seon.sci.reader/form event)))))
        {:seon.schema/projection
         (schema/projection-from-database db projection)
         :seon.sci.eval/installed 1})
      {:seon.schema/projection projection
       :seon.sci.eval/installed 0})))

(defn acquire!
  "Install current agent-authored functions into a ctx at one database value.
  Ancestor-authored rows describe the compiled/base program and participate
  in schema projection; they are not replayed as interpreted source. This
  reads program rows only—receipts and eval results are outside the query by
  construction."
  {:malli/schema [:=> [:cat :seon.sci.eval/acquire-request] :map]}
  [{ctx :seon.sci.eval/ctx db :seon.db/db}]
  (let [projection (schema/projection-from-database db)
        ctx (assoc ctx :seon.schema/projection projection)
        agent-authored?
        (fn [source-tx]
          (= :agent
             (:seon.schema.admission/source
              (schema/admission-from-asserting-transaction db source-tx))))
        namespace-rows
        (into
         []
         (comp
          (filter (fn [[_ _ source-tx]] (agent-authored? source-tx)))
          (map (fn [[namespace-name _ _]]
                 (d/pull db
                         '[* :seon.ns/requires
                             {:seon.ns/aliases [*]}
                             {:seon.ns/refers [*]}]
                         [:seon.ns/name namespace-name]))))
         (d/q '[:find ?namespace-name ?source ?source-tx
                :where
                [?namespace :seon.ns/name ?namespace-name]
                [?namespace :seon.ns/source ?source ?source-tx]]
              db))
        function-rows
        (into
         []
         (filter
          (fn [[_ _ _ source-tx]] (agent-authored? source-tx)))
         (d/q '[:find ?sym ?source ?namespace-name ?source-tx
                :where
                [?function :seon.fn/sym ?sym]
                [?function :seon.fn/source ?source ?source-tx]
                [?function :seon.fn/spec _]
                [?function :seon.fn/ns ?namespace]
                [?namespace :seon.ns/name ?namespace-name]]
              db))
        test-rows
        (into
         []
         (filter (fn [[_ _ _ source-tx]] (agent-authored? source-tx)))
         (d/q '[:find ?sym ?source ?namespace-name ?source-tx
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
                (install-program-row!
                 {:seon.sci.eval/ctx
                  (assoc ctx :seon.schema/projection
                         (:seon.schema/projection state))
                  :seon.db/db db
                  :seon.sci.eval/program-row row})]
            {:seon.schema/projection (:seon.schema/projection installed)
             :seon.sci.eval/installed
             (+ (:seon.sci.eval/installed state)
                (:seon.sci.eval/installed installed))}))]
    ;; Create every namespace and publish aliases first. Alias targets need not
    ;; exist: this is the effective behavior of SCI's `:as-alias` too.
    (doseq [[namespace-name row] namespace-row-by-name]
      (sci/install-namespace-bindings!
       ctx namespace-name (assoc (row-bindings row) :refers {})))
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
                (contains? namespace-row-by-name namespace-name)
                (update :seon.sci.eval/installed inc))
              (map (fn [[sym source _ _]]
                     {:seon.fn/sym sym
                      :seon.fn/source source
                      :seon.fn/ns [:seon.ns/name namespace-name]})
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

(defn evaluate
  "Evaluate one form source and return what may leave the boundary.
  Runs on the CALLER's thread, which must be a `:compute` platform
  thread — this never blocks and never submits, because the two jobs
  the quarry's Semaphore conflated (backpressure and parallelism) now
  belong to the caller's work launcher.

  Order is the contract:
  1. arm the interrupt-fn with `::time-limit-ms`, the ONLY limit;
  2. install it on the SUPPLIED ctx (the caller's per-run fork, so the
     fold shares defs) or on a fresh fork of the base when none was
     given;
  3. consume THE ONE reader event; source is never reparsed;
  4. evaluate;
  5. ADMIT the value — realized and bounded — while still armed;
  6. disarm in `finally`.

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
    namespace-ref :seon.cluster.run.form/ns
    time-limit-ms :seon.sci.eval/time-limit-ms
    on-core-error :seon.config/on-core-error}]
  (let [{:keys [interrupt-fn] stop! ::stop! record ::record}
        (arm time-limit-ms)
        ;; a supplied ctx is used AS GIVEN — forking it here would
        ;; discard the caller's accumulated defs, which is the bug this
        ;; contract exists to not repeat
        evaluation-ctx (assoc (or ctx (sci/fork (base)))
                              :interrupt-fn interrupt-fn)
        printed (java.io.StringWriter.)
        namespace-name (or (second namespace-ref)
                           (when agent-id (agent-namespace agent-id))
                           'user)
        namespace-object (sci/create-ns namespace-name)
        ending-namespace (volatile! namespace-name)]
    (try
      (let [before-reader-context
            (reader-context evaluation-ctx namespace-name)
            event (one-event source namespace-name evaluation-ctx)
            form (:seon.sci.reader/form event)
            namespace-unmap? (:seon.sci.reader/ns-unmap? event)
            execution-ctx (if namespace-unmap?
                            (sci/fork evaluation-ctx)
                            evaluation-ctx)
            before-interns (when namespace-unmap?
                             (sci/namespace-interns execution-ctx))
            eval-form!
            (fn []
              (sci/binding [sci/ns namespace-object
                            sci/out printed
                            sci/err printed]
                (let [value (sci/eval-form execution-ctx form)]
                  (vreset! ending-namespace (sci/ns-name @sci/ns))
                  value)))
            projection
            (or (:seon.schema/projection evaluation-ctx)
                (schema/current-projection)
                (schema/build-projection (schema/registered-schemas)))
            raw-row (program-row event projection)
            unregister-key (deleted-schema-key raw-row)
            schema-delta
            (when (or (:seon.schema/key raw-row) unregister-key)
              (schema/begin-registration-delta projection))
            schema-value
            (when schema-delta
              (schema/call-with-registration-delta
               schema-delta
               eval-form!))
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
                     (ex-info "Schema declaration did not register its reader identity."
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
            evaluated-value
            (if base-declared-row
              (do
                (when-let [declared-ns (:seon.ns/name base-declared-row)]
                  (vreset! ending-namespace declared-ns))
                (or (:seon.ns/name base-declared-row)
                    (:seon.fn/sym base-declared-row)
                    (:seon.schema/key base-declared-row)
                    (:seon.test/sym base-declared-row)
                    (when unregister-key schema-value)))
              (eval-form!))
            removed-identities
            (when namespace-unmap?
              (removed-program-identities
               before-interns (sci/namespace-interns execution-ctx)))
            deletion-row
            (when (seq removed-identities)
              (program/deletion-row
               (assoc event :seon.sci.reader/ns-unmap-identities
                      removed-identities)))
            declared-row (or deletion-row base-declared-row)
            ;; Standalone REPL `require` is namespace registration too. Its
            ;; committed row carries the complete dependency set derived from
            ;; SCI's namespace table, so fresh acquisition reconstructs the
            ;; same reader/evaluator context before installing declarations.
            context-row
            (when-not declared-row
              (namespace-context-row
               namespace-name source before-reader-context
               (reader-context execution-ctx namespace-name)))
            row (or declared-row context-row)
            ;; Durable declarations are installed only after the row commits.
            value (if context-row (:seon.ns/name context-row) evaluated-value)
              ;; INSIDE the boundary, BEFORE disarm: an infinite lazy
              ;; sequence dies at the time limit here rather than in the
              ;; receipt writer
              admitted (admit/admit
                        {:seon.sci.admit/value value
                         :seon.sci.admit/interrupt-fn interrupt-fn
                         :seon.sci.admit/caps caps
                         ;; R41 travels WITH the request: admission does
                         ;; not read a dial of its own, and this
                         ;; evaluator does not default one
                         :seon.config/on-core-error on-core-error
                         :seon.sci.admit/record (record :ok)})]
          (cond-> {:seon.sci.admit/value (:seon.sci.admit/value admitted)
                   :seon.cluster.eval/result-edn
                   (:seon.cluster.eval/result-edn admitted)
                   :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                   :seon.sci.eval/ending-ns @ending-namespace
                   :seon.sci.admit/capped? (:seon.sci.admit/capped? admitted)
                   :seon.sci.admit/record (:seon.sci.admit/record admitted)}
            row (assoc :seon.sci.eval/program-row row)
            (seq (str printed))
            (assoc :seon.cluster.eval/output
                   (bounded-output printed caps))))
      (catch Throwable throwable
          (let [record (record (if (interrupted? throwable) :time :error))
                value (failure-value throwable record)
                admitted
                (admit/admit
                 {:seon.sci.admit/value value
                  :seon.sci.admit/interrupt-fn (constantly nil)
                  :seon.sci.admit/caps caps
                  :seon.config/on-core-error :record
                  :seon.sci.admit/record record})]
            (cond-> {:seon.sci.admit/value (:seon.sci.admit/value admitted)
                     :seon.cluster.eval/result-edn
                     (:seon.cluster.eval/result-edn admitted)
                     :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                     :seon.sci.eval/ending-ns namespace-name
                     :seon.cluster.eval/error (:seon.error/message value)
                     :seon.sci.admit/capped?
                     (:seon.sci.admit/capped? admitted)
                     :seon.sci.admit/record record}
              ;; the instant the interrupt was OBSERVED — the one
              ;; genuinely new fact a cut evaluation leaves. Its
              ;; presence IS the interrupted state; there is no label.
              (= :time (:seon.eval/outcome record))
              (assoc :seon.cluster.eval/interrupted-at (java.util.Date.))
              ;; whatever it printed BEFORE it failed is often the whole
              ;; story of why
              (seq (str printed))
              (assoc :seon.cluster.eval/output
                     (bounded-output printed caps)))))
      (finally
        (stop!)))))
