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
            [clojure.test.check.generators :as gen]
            [datahike.api :as d]
            [my.message]
            [my.run]
            [sci.core :as sci]
            [sci.interrupt :as sci.interrupt]
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
          message-ns (sci/create-ns 'my.message)]
      (sci/init
       {;; the interrupt-aware core: a lazy sequence built by NATIVE
        ;; clojure.core enters no interpreted body, so `(range)` inside
        ;; `reduce` would never hit the interrupt-fn. Sci ships drop-in
        ;; alternatives for exactly this and they are opt-in
        ;; (interrupt.md:56-60).
        :namespaces
        {'clojure.core sci.interrupt/clojure-core
         'clojure.string sci.interrupt/clojure-string
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

(defn- quoted-symbol
  [value]
  (when (and (seq? value) (= 'quote (first value)) (= 2 (count value))
             (symbol? (second value)))
    (second value)))

(defn- deletion-row
  [event]
  (let [form (:seon.sci.reader/form event)]
    (when (and (seq? form)
               (= 'ns-unmap (first form))
               (= 3 (count form)))
      (when-let [namespace-name (quoted-symbol (second form))]
        (when-let [function-name (quoted-symbol (nth form 2))]
          {:seon.fn/delete (str (symbol (str namespace-name)
                                             (str function-name)))
           :seon.fn/source (:seon.sci.reader/source event)
           :seon.fn/ns [:seon.ns/name namespace-name]})))))

(defn- program-row
  "Return the one reader declaration eligible for durable publication.
  A function without its complete contract is deliberately absent."
  [event]
  (or
   (deletion-row event)
   (cond
    (and (:seon.fn/sym event) (:seon.fn/spec event))
    (if (schema/malli-form? (edn/read-string (:seon.fn/spec event)))
      (select-keys event [:seon.fn/sym :seon.fn/ns :seon.fn/source
                          :seon.fn/arglists :seon.fn/doc :seon.fn/private?
                          :seon.fn/spec :seon.fn/workload])
      (throw (ex-info "Function contract is not a registered Malli form."
                      {:seon.error/kind ::contract-refused
                       :seon.fn/sym (:seon.fn/sym event)})))

    (:seon.schema/key event)
    (if (schema/malli-form? (edn/read-string (:seon.schema/form event)))
      (select-keys event [:seon.schema/key :seon.schema/ns :seon.schema/form])
      (throw (ex-info "Schema registration is not a registered Malli form."
                      {:seon.error/kind ::schema-refused
                       :seon.schema/key (:seon.schema/key event)})))

    (:seon.test/sym event)
    (select-keys event [:seon.test/sym :seon.test/ns :seon.test/source])

     :else nil)))

(defn- one-event
  [source namespace-name]
  (let [events (reader/read {:seon.sci.reader/text source
                             :seon.sci.reader/ns namespace-name})]
    (cond
      (map? events)
      (throw (ex-info (:seon.error/message events) events))

      (= 1 (count events))
      (first events)

      :else
      (throw (ex-info "Evaluation requires exactly one reader event."
                      {:seon.error/kind ::reader-event-count
                       :seon.sci.reader/event-count (count events)})))))

(declare activate-program-schemas!)

(defn install-program-row!
  "Install one declaration only after resolving its exact committed row from
  the terminal transaction's db-after. Receipts are never consulted."
  {:malli/schema [:=> [:cat :seon.sci.eval/install-request] :boolean]}
  [{ctx :seon.sci.eval/ctx
    db :seon.db/db
    row :seon.sci.eval/program-row}]
  (let [[identity value]
        (some (fn [attribute]
                (when-some [value (get row attribute)]
                  [attribute value]))
              [:seon.fn/sym :seon.schema/key :seon.test/sym
               :seon.fn/delete])
        committed (when-not (= identity :seon.fn/delete)
                    (d/pull db '[*] [identity value]))]
    (when-not (= (:seon.fn/source row) (:seon.fn/source committed))
      (when (= identity :seon.fn/sym)
        (throw (ex-info "Committed function source does not match install request."
                        {:seon.error/kind ::install-source-mismatch
                         :seon.fn/sym value}))))
    (case identity
      :seon.fn/sym
      (let [namespace-name (second (:seon.fn/ns row))
            event (one-event (:seon.fn/source committed) namespace-name)]
        (sci/binding [sci/ns (sci/create-ns namespace-name)]
          (sci/eval-form ctx (:seon.sci.reader/form event)))
        true)

      ;; Schema activation and test discovery derive from their committed
      ;; rows. They are deliberately not executed as arbitrary eval effects.
      :seon.schema/key (activate-program-schemas! db)
      :seon.test/sym true
      :seon.fn/delete
      (let [namespace-name (second (:seon.fn/ns row))
            event (one-event (:seon.fn/source row) namespace-name)]
        (when (d/pull db [:db/id] [:seon.fn/sym value])
          (throw (ex-info "Deleted function is still present after commit."
                          {:seon.error/kind ::install-delete-mismatch
                           :seon.fn/sym value})))
        (sci/binding [sci/ns (sci/create-ns namespace-name)]
          (sci/eval-form ctx (:seon.sci.reader/form event)))
        true)
      false)))

(defn- activate-program-schemas!
  [db]
  (let [schema-rows
        (d/q '[:find ?key ?form ?tx
               :where
               [?schema :seon.schema/key ?key ?tx]
               [?schema :seon.schema/form ?form]]
             db)]
    (when (seq schema-rows)
      (schema/activate-projection!
       (schema/projection-from-rows
        {:seon.schema/database-value db
         :seon.schema/schema-rows schema-rows
         :seon.schema/function-contract-rows
         (d/q '[:find ?sym ?spec ?tx
                :where
                [?function :seon.fn/sym ?sym]
                [?function :seon.fn/spec ?spec ?tx]]
              db)
         :seon.schema/function-source-rows
         (d/q '[:find ?sym ?source ?tx
                :where
                [?function :seon.fn/sym ?sym]
                [?function :seon.fn/source ?source ?tx]]
              db)
         :seon.schema/artifact-exports #{}
         :seon.schema/pure-predicate-symbols #{}}
        (or (schema/current-projection) {}))))
    (boolean (seq schema-rows))))

(defn acquire!
  "Install current agent-authored functions into a ctx at one database value.
  Ancestor-authored rows describe the compiled/base program and participate
  in schema projection; they are not replayed as interpreted source. This
  reads program rows only—receipts and eval results are outside the query by
  construction."
  {:malli/schema [:=> [:cat :seon.sci.eval/acquire-request] :int]}
  [{ctx :seon.sci.eval/ctx db :seon.db/db}]
  (activate-program-schemas! db)
  (let [rows
        (into
         []
         (filter
          (fn [[_ _ _ source-tx]]
            (= :agent
               (:seon.schema.admission/source
                (schema/admission-from-asserting-transaction
                 db source-tx)))))
         (d/q '[:find ?sym ?source ?namespace-name ?source-tx
                :where
                [?function :seon.fn/sym ?sym]
                [?function :seon.fn/source ?source ?source-tx]
                [?function :seon.fn/spec _]
                [?function :seon.fn/ns ?namespace]
                [?namespace :seon.ns/name ?namespace-name]]
              db))]
    (doseq [[sym source namespace-name _] (sort-by first rows)]
      (install-program-row!
       {:seon.sci.eval/ctx ctx
        :seon.db/db db
        :seon.sci.eval/program-row
        {:seon.fn/sym sym
         :seon.fn/source source
         :seon.fn/ns [:seon.ns/name namespace-name]}}))
    (count rows)))

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
        namespace-object (sci/create-ns namespace-name)]
    (try
      (let [event (one-event source namespace-name)
            row (program-row event)
            form (:seon.sci.reader/form event)
            ;; Durable declarations are installed only after the row commits.
            value (if row
                    (or (:seon.fn/sym row)
                        (:seon.schema/key row)
                        (:seon.test/sym row))
                    (sci/binding [sci/ns namespace-object
                                  sci/out printed
                                  sci/err printed]
                      (sci/eval-form evaluation-ctx form)))
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
