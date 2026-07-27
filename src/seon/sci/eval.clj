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
  plus the two `my.run` dispositions — and a caller may pass its own.
  The computed binding table (capability functions derived from
  program-graph facts, filtered by a derived namespace policy) is N5's
  and is deliberately absent: a hand-listed callable surface here would
  be the L17 violation the quarry's own comment warned about.

  Crash walk: this namespace owns no durable state. A kill during an
  evaluation leaves the loop's `:running` receipt, which N2's
  `recover-tx` settles as `:interrupted` — and rows 6 and 7 of the
  crash walk stay indistinguishable, which is honest: the form's effect
  MAY have happened. Nothing re-executes."
  (:require [clojure.test.check.generators :as gen]
            [my.run]
            [sci.core :as sci]
            [sci.interrupt :as sci.interrupt]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit])
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
    (let [run-ns (sci/create-ns 'my.run)]
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
                  'complete (sci/copy-var my.run/complete run-ns)}}
        ;; two broad roots rather than an enumeration of exception
        ;; subclasses — an agent needs to catch things, not to be given
        ;; a curated taxonomy
        :classes {'Throwable Throwable
                  'java.lang.Throwable Throwable
                  'Error Error
                  'java.lang.Error Error}}))))

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
                      (assoc :seon.sci.eval/data
                             (pr-str (ex-data throwable))))})

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
  3. PARSE INSIDE the armed ctx, so `#=` and unknown tags are refused
     by sci's own reader and never reach host evaluation;
  4. evaluate;
  5. ADMIT the value — realized and bounded — while still armed;
  6. disarm in `finally`.

  Never throws. A failure of any kind returns an ordinary map whose
  `:seon.sci.admit/value` is a flat `:seon.error` value:
  `:seon.cluster.eval/status` is `:done` when the form produced a
  value, `:interrupted` when the time limit fired, and `:error`
  otherwise. The record rides through with `fn-entries` and
  `allocated-bytes` intact."
  {:malli/schema [:=> [:cat :seon.sci.eval/request]
                  :seon.sci.eval/evaluation]}
  [{:keys [:seon.cluster.run.form/source :seon.sci.admit/caps]
    ctx :seon.sci.eval/ctx
    time-limit-ms :seon.sci.eval/time-limit-ms}]
  (let [{:keys [interrupt-fn] stop! ::stop! record ::record}
        (arm time-limit-ms)
        ;; a supplied ctx is used AS GIVEN — forking it here would
        ;; discard the caller's accumulated defs, which is the bug this
        ;; contract exists to not repeat
        evaluation-ctx (assoc (or ctx (sci/fork (base)))
                              :interrupt-fn interrupt-fn)]
    (try
      (let [form (sci/parse-string evaluation-ctx source)
              value (sci/eval-form evaluation-ctx form)
              ;; INSIDE the boundary, BEFORE disarm: an infinite lazy
              ;; sequence dies at the time limit here rather than in the
              ;; receipt writer
              admitted (admit/admit
                        {:seon.sci.admit/value value
                         :seon.sci.admit/interrupt-fn interrupt-fn
                         :seon.sci.admit/caps caps
                         :seon.sci.admit/record (record :ok)})]
          {:seon.cluster.eval/status :done
           :seon.sci.admit/value (:seon.sci.admit/value admitted)
           :seon.cluster.eval/result-edn
           (:seon.cluster.eval/result-edn admitted)
           :seon.sci.admit/capped? (:seon.sci.admit/capped? admitted)
           :seon.sci.admit/record (:seon.sci.admit/record admitted)})
      (catch Throwable throwable
          (let [record (record (if (interrupted? throwable) :time :error))
                value (failure-value throwable record)]
            {:seon.cluster.eval/status
             (if (= :time (:seon.eval/outcome record))
               :interrupted
               :error)
             :seon.sci.admit/value value
             :seon.cluster.eval/result-edn (pr-str value)
             :seon.cluster.eval/error (:seon.error/message value)
             :seon.sci.admit/capped? false
             :seon.sci.admit/record record}))
      (finally
        (stop!)))))
