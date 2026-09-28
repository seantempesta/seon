(ns seon.sci.kernel
  "The one guarded operation shared by evaluation and renderer calls.

  SCI calls the stable interrupt function at interpreted function-body and
  loop entrances. One process guard keeps per-thread arm state; an invocation
  either owns a new arm or inherits the arm already governing its thread. Values are admitted before disarm. Host calls remain SCI's documented
  interruption ceiling and are never described as hard-stoppable.

  THE ARM IS A VALUE AND TRAVELS WITH THE WORK. The thread-local slot is the
  fast path for finding the arm governing the running thread, never the arm's
  identity: `current-arm` hands the governing arm out as a value that rides a
  submission the way the environment does, and `adopt-arm` installs it on the
  thread that actually runs the work. The deadline is a latch shared by the
  arm's value, so every thread serving one evaluation observes one deadline,
  counts into one entrance total, and is reachable by one `interrupt!`."
  (:require [clojure.main :as main]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [sci.core :as sci]
            [sci.impl.utils :as sci.utils]
            [sci.interrupt :as sci.interrupt]
            [seon.db :as db]
            [seon.error :as error]
            [seon.error.refusal :as error.refusal]
            [seon.fault :as fault]
            [seon.schema :as schema]
            [seon.sci.admit :as admit])
  (:import [java.lang.management ManagementFactory MemoryNotificationInfo MemoryPoolMXBean MemoryType]
           [javax.management Notification NotificationEmitter NotificationListener]
           [java.util.concurrent Future ScheduledThreadPoolExecutor TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean AtomicLong]))

(defn interrupted?
  "True when a throwable or one of its causes is SCI's interrupt."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [throwable]
  (loop [candidate throwable]
    (cond
      (nil? candidate) false
      (sci.utils/interrupt-ex? candidate) true
      :else (recur (ex-cause candidate)))))

(def ^:private thread-mx (ManagementFactory/getThreadMXBean))

(defn- allocated-bytes
  []
  (.getCurrentThreadAllocatedBytes
   ^com.sun.management.ThreadMXBean thread-mx))

(defonce ^:private ^ScheduledThreadPoolExecutor deadline-timer
  (doto (ScheduledThreadPoolExecutor.
         1
         (reify java.util.concurrent.ThreadFactory
           (newThread [_ runnable]
             (doto (Thread. runnable "seon-sci-time-limit")
               (.setDaemon true)))))
    (.setRemoveOnCancelPolicy true)))

(defn- new-guard
  []
  (let [thread-arm (ThreadLocal.)
        interrupt-fn
        (fn []
          (when-let [armed (.get ^ThreadLocal thread-arm)]
            (let [^AtomicLong entries (::entries armed)
                  ^longs sampled (::sampled armed)
                  ^AtomicBoolean reached (::reached armed)
                  outcome (::outcome armed)
                  ^long allocated-at-start (::allocated-at-start armed)
                  entrance-count (.incrementAndGet entries)]
              (when (.get reached)
                (vreset! outcome :time)
                (sci.interrupt/interrupt! "time-limit"))
              (when (and (::measurable armed)
                         (zero? (bit-and entrance-count 1023))
                         ;; Allocation is a per-thread JVM counter, so only
                         ;; the owning thread's sample is comparable with
                         ;; `allocated-at-start`. An adopting thread's
                         ;; allocation is accumulated by `adopt-arm` instead.
                         (= (::owner-thread-id armed)
                            (.threadId (Thread/currentThread))))
                (aset sampled 0
                      (long (- (allocated-bytes)
                               allocated-at-start)))))))
        host-interop-observer
        (fn []
          (when-let [armed (.get ^ThreadLocal thread-arm)]
            (.incrementAndGet ^AtomicLong (::host-interop-observations armed))))
        built-in-call-observer
        (fn [qualified-symbol]
          (when-let [armed (.get ^ThreadLocal thread-arm)]
            (swap! (::built-in-calls armed) conj qualified-symbol)))]
    {::thread-arm thread-arm
     ::interrupt-fn interrupt-fn
     ::host-interop-observer host-interop-observer
     ::built-in-call-observer built-in-call-observer}))

(defonce ^:private process-guard (delay (new-guard)))

;;; ---------------------------------------------------------------------------
;;; Memory pressure: the JVM's own crossing notice closes arming
;;; ---------------------------------------------------------------------------

(def memory-bounds
  "Tenured occupancy after a collection, as fractions of the pool's maximum:
  new arms refuse from `::enter`, admit again below `::resume` (the gap stops
  flapping). The JVM's heap bounds, shared by its clusters, not their config."
  {::enter 0.70 ::resume 0.55})

;;; Beside the guard (a `defonce` a reload never rebuilds). The JVM keeps no
;;; state a volatile read could answer: `isCollectionUsageThresholdExceeded`
;;; calls into the VM and allocates (`sun/management/MemoryPoolImpl.java`).
(defonce ^:private memory-pressure-state (volatile! nil))

(defn- tenured-pool
  "The old generation: the one heap pool supporting a usage threshold, which
  the JDK says eden-like pools do not suit (`MemoryPoolMXBean`)."
  ^MemoryPoolMXBean []
  (some (fn [^MemoryPoolMXBean pool]
          (when (and (= MemoryType/HEAP (.getType pool))
                     (.isUsageThresholdSupported pool)
                     (.isCollectionUsageThresholdSupported pool))
            pool))
        (ManagementFactory/getMemoryPoolMXBeans)))

(defn- collection-usage
  "The tenured pool's occupancy after its most recent collection."
  [^MemoryPoolMXBean pool]
  (let [usage (.getCollectionUsage pool)]
    {::pool (.getName pool) ::used-bytes (.getUsed usage) ::max-bytes (.getMax usage)}))

(defn memory-pressure-error
  "The refusal a new arm answers under `pressure`, naming work then in flight."
  {:malli/schema [:=> [:cat ::memory-pressure] ::memory-pressure-error]}
  [{::keys [pool used-bytes max-bytes enter-bytes resume-bytes in-flight] :as pressure}]
  (error.refusal/diagnostic
   (java.util.Date.) ::arming 'seon.sci.kernel/acquire-arm
   (assoc pressure
          :seon.error/message
          (str "This JVM is under memory pressure: " pool " holds " used-bytes " of " max-bytes
               " bytes after its last collection. New evaluations refuse from " enter-bytes
               " and admit again below " resume-bytes ". In flight when it began: "
               (if (seq in-flight) (pr-str in-flight) "no open run") ".")
          :seon.error/expected ::resume-bytes)))

(defn enter-memory-pressure!
  "Close new arms when the tenured pool's live bytes cross `::enter`.
  Answers the pressure entered, or nil when already under pressure or when
  the crossing was garbage.

  The crossing notice alone is not live occupancy: G1 updates the old pool's
  collection usage after every young pause (`G1 Young Generation` manages
  `G1 Old Gen`), so it counts promoted garbage no marking has reclaimed yet.
  Measured on default 2026-09-27: 8.53 GB read after a round whose forced
  collections held 1.5-2.4 GB live. One full collection per crossing measures
  the live bytes (0.1-0.5 s at 1.5-2.4 GB live), and only they close arms."
  {:malli/schema [:=> [:cat ::in-flight] [:maybe ::memory-pressure]]}
  [in-flight]
  (when-not @memory-pressure-state
    (System/gc)
    (let [usage (collection-usage (tenured-pool))
          bound #(long (* (% memory-bounds) (::max-bytes usage)))]
      (when (>= (::used-bytes usage) (bound ::enter))
        (vreset! memory-pressure-state
                 (assoc usage
                        ::enter-bytes (bound ::enter)
                        ::resume-bytes (bound ::resume)
                        ::entered-at (java.util.Date.)
                        ::in-flight in-flight))))))

(defn memory-pressure
  "The memory pressure `state` holds (this process's by default), or nil while
  arms admit work. Ordinary admission pays one volatile read; a held pressure
  re-reads the JVM and clears once usage is below its `::resume-bytes`."
  {:malli/schema [:function [:=> [:cat] [:maybe ::memory-pressure]]
                  [:=> [:cat [:fn clojure.core/volatile?]] [:maybe ::memory-pressure]]]}
  ([] (memory-pressure memory-pressure-state))
  ([state]
   (when-let [pressure @state]
     (let [used (::used-bytes (collection-usage (tenured-pool)))]
       (if (< used (::resume-bytes pressure))
         (vreset! state nil)
         (assoc pressure ::used-bytes used))))))

(defn watch-memory!
  "Set the tenured pool's collection usage threshold at `::enter` and call
  `on-crossing` for each `MEMORY_COLLECTION_THRESHOLD_EXCEEDED` the
  `MemoryMXBean` emits (after a collection at or above it; again only after
  falling below). Nothing polls. Once per process; answers the threshold."
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?]] [:maybe :int]]}
  [on-crossing]
  (let [pool (tenured-pool)
        maximum (.getMax (.getUsage pool))]
    (when (pos? maximum)
      (let [threshold (long (* (::enter memory-bounds) maximum))]
        (.setCollectionUsageThreshold pool threshold)
        (.addNotificationListener
         ^NotificationEmitter (ManagementFactory/getMemoryMXBean)
         (reify NotificationListener
           (handleNotification [_ notification _]
             (when (= MemoryNotificationInfo/MEMORY_COLLECTION_THRESHOLD_EXCEEDED
                      (.getType ^Notification notification))
               (on-crossing))))
         nil nil)
        threshold))))

(defn context-options
  "SCI initialization options backed by the one process guard."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [guard @process-guard]
    {::guard guard
     :interrupt-fn (::interrupt-fn guard)
     :host-interop-observer (::host-interop-observer guard)
     :built-in-call-observer (::built-in-call-observer guard)}))

(defn cache-program!
  "Publish immutable function and namespace rows used by lazy installation."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :map :map]
                  :nil]}
  [ctx functions namespaces]
  (reset! (::program-snapshot ctx)
          {:functions functions :namespaces namespaces})
  nil)

(defn cache-function!
  "Publish one committed function row into the live program snapshot."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol :map]
                  :qualified-symbol]}
  [ctx function-symbol row]
  (swap! (::program-snapshot ctx) assoc-in
         [:functions function-symbol] row)
  function-symbol)

(defn program-function
  "Return one cached function row from the acquired program snapshot."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol]
                  [:or :nil :map]]}
  [ctx function-symbol]
  (get-in @(::program-snapshot ctx) [:functions function-symbol]))

(defn program-namespace
  "Return one cached namespace row from the acquired program snapshot."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.ns/name]
                  [:or :nil :map]]}
  [ctx namespace-name]
  (get-in @(::program-snapshot ctx) [:namespaces namespace-name]))

(defn public-functions-in
  "Return public contracted functions in `namespace-name` from the snapshot."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :seon.ns/name]
  [:vector :qualified-symbol]]}
  [ctx namespace-name]
  (->> (:functions @(::program-snapshot ctx))
       (keep (fn [[function-symbol row]]
               (when (and (= namespace-name
                             (symbol (namespace function-symbol)))
                          (false? (:seon.sci.eval/function-private? row)))
                 function-symbol)))
       (sort-by str)
       vec))

(defn mark-installed!
  "Record that `function-symbol` has been installed from database source."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx :qualified-symbol]
                  :qualified-symbol]}
  [ctx function-symbol]
  (swap! (::installed-functions ctx) conj function-symbol)
  function-symbol)

(defn ensure-function!
  "Install `function-symbol` from its database row once per live context."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx
                       :seon.db/database-value
                       :qualified-symbol]
                  :qualified-symbol]}
  [ctx database function-symbol]
  (when-not (contains? @(::installed-functions ctx) function-symbol)
    (if-let [install! (::install-function! ctx)]
      (install! ctx database function-symbol)
      (throw
       (ex-info "SCI context has no database-program installer."
                (seon.error.refusal/diagnostic (java.util.Date.) ::installation 'seon.sci.kernel/ensure-function!
                 {::guard-observation
                 {:seon.error.evidence/attribute :seon.fn/sym
                  :seon.error.evidence/value function-symbol}
                 :seon.fn/sym function-symbol})))))
  function-symbol)

(defn context-projection
  "Return the latest immutable schema projection held by `ctx`."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx]
                  [:or :nil :seon.schema/projection]]}
  [ctx]
  (or (some-> (:seon.sci.eval/projection-state ctx)
              deref
              :seon.schema/projection)
      (:seon.schema/projection ctx)))

(defn- record
  [armed final-outcome]
  (let [^AtomicLong entries (::entries armed)
        ^longs sampled (::sampled armed)
        ^AtomicLong host-interop-observations (::host-interop-observations armed)
        ^AtomicLong adopted-allocated (::adopted-allocated-bytes armed)
        started-at (::started-at armed)
        allocated-at-start (::allocated-at-start armed)
        owning-thread? (= (::owner-thread-id armed)
                          (.threadId (Thread/currentThread)))]
    {:seon.eval/fn-entries (.get entries)
     :seon.eval/host-interop-count (.get host-interop-observations)
     :seon.eval/duration-ms
     (quot (- (System/nanoTime) started-at) 1000000)
     :seon.eval/allocated-bytes
     (if (::measurable armed)
       (+ (if owning-thread?
            (max (aget sampled 0) (- (allocated-bytes) allocated-at-start))
            (aget sampled 0))
          (.get adopted-allocated))
       -1)
     :seon.eval/outcome (or @(::outcome armed) final-outcome)}))

(defn- new-armed
  "The arm VALUE: every counter, latch, and identity one evaluation needs,
  shared by whichever threads serve it. Nothing here is thread-local."
  []
  (let [allocated-at-start (allocated-bytes)]
    {::entries (AtomicLong. 0)
     ::sampled (long-array 1)
     ::host-interop-observations (AtomicLong. 0)
     ::adopted-allocated-bytes (AtomicLong. 0)
     ::built-in-calls (atom #{})
     ::reached (AtomicBoolean. false)
     ::travelled (AtomicBoolean. false)
     ::outcome (volatile! nil)
     ::started-at (System/nanoTime)
     ::owner-thread-id (.threadId (Thread/currentThread))
     ::allocated-at-start allocated-at-start
     ::measurable (not (neg? allocated-at-start))}))

(defn- own-arm
  [guard ctx time-limit-ms]
  ;; THE ONE ADMISSION: only an outer execution owns an arm (nested work
  ;; inherits it), so pressure refuses new work and cuts none admitted. The
  ;; unpressured path is one volatile read, before any contracted call.
  (when-let [pressure (and @memory-pressure-state (memory-pressure))]
    (let [refusal (memory-pressure-error pressure)]
      (throw (ex-info (:seon.error/message refusal) refusal))))
  (let [^ThreadLocal thread-arm (::thread-arm guard)
        base (new-armed)
        ;; The deadline as a value ON the arm, so work that BLOCKS can honour
        ;; it. The latch alone is reachable only from an interpreted function
        ;; entrance, and a thread parked in a host call makes none — which is
        ;; how an interrupted `my.shell/run` left its child running for its
        ;; natural lifetime while its receipt stayed open.
        armed (assoc base
                     ::ctx ctx
                     ::deadline-nanos
                     (+ (::started-at base) (* (long time-limit-ms) 1000000)))]
    (.set thread-arm armed)
    (try
      (let [task (.schedule deadline-timer
                            ^Runnable #(.set ^AtomicBoolean (::reached armed)
                                             true)
                            (long time-limit-ms)
                            TimeUnit/MILLISECONDS)]
        {:interrupt-fn (::interrupt-fn guard)
         ::built-in-calls (fn [] @(::built-in-calls armed))
         ::stop!
         (fn []
           ;; The deadline is the arm's latch, not the owning thread's timer.
           ;; Once the arm has TRAVELLED, work elsewhere is still governed by
           ;; it, so the latch must be allowed to close on schedule; the
           ;; owner only stops serving the arm on its own thread. An arm that
           ;; never travelled has no other observer, so its task is cancelled.
           (when-not (.get ^AtomicBoolean (::travelled armed))
             (.cancel ^Future task false))
           (.remove thread-arm)
           nil)
         ::record #(record armed %)})
      (catch Throwable failure
        (.remove thread-arm)
        (throw failure)))))

(defn- current-thread-arm
  "The arm installed on this thread, never an arm from a sibling thread."
  [guard]
  (.get ^ThreadLocal (::thread-arm guard)))

(defn- acquire-arm
  [ctx time-limit-ms]
  (let [guard (::guard ctx)]
    (when-not guard
      (throw
       (ex-info "SCI context has no stable interrupt guard."
                (seon.error.refusal/diagnostic (java.util.Date.) ::arming 'seon.sci.kernel/acquire-arm
                 {::guard-observation
                 {:seon.error.evidence/attribute ::guard
                  :seon.error.evidence/value :seon.error/unknown}}))))
    (if-let [armed (current-thread-arm guard)]
      ;; Nested work shares the governing arm's deadline and counters (the
      ;; same objects), and is the evaluation of ITS context for its extent:
      ;; the arm names the context whose world a call receives (`world-ctx`).
      (let [^ThreadLocal thread-arm (::thread-arm guard)]
        (.set thread-arm (assoc armed ::ctx ctx))
        {:interrupt-fn (::interrupt-fn guard)
         ::built-in-calls (fn [] @(::built-in-calls armed))
         ::stop! #(.set thread-arm armed)
         ::record #(record armed %)})
      (own-arm guard ctx time-limit-ms))))

(defn arm
  "Arm `ctx` on this thread and return its stop, record, and observers.

  ONE re-entrancy rule for every entrance, because a second rule is how the
  two boundaries diverged: work inherits the arm installed in THIS thread's
  ThreadLocal, whichever SCI context it evaluates in: the same interpreter, a
  `sci/fork`, or another agent's context an evaluation deliberately opened. A
  sibling thread sees no current arm and therefore owns an independent one
  even when it evaluates the same context concurrently. The outer deadline
  keeps governing nested work, the returned `::stop!` is inert, and no second
  timer exists: one thread serves one limit, the one that admitted the outer
  evaluation. `::built-in-calls` reports the governing arm's observations.

  THE THREAD IS NOT THE ARM. Work that leaves this thread carries the arm as
  a value (`current-arm`) and the receiving thread installs it for the extent
  of that work (`adopt-arm`), so a crossing neither escapes the deadline nor
  starts a second one: there is exactly one latch, one entrance total, and
  one reachable `interrupt!` per armed evaluation, on however many threads."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx
                       :seon.sci.eval/time-limit-ms]
                  :map]}
  [ctx time-limit-ms]
  (acquire-arm ctx time-limit-ms))

(defn with-arm
  "Run `work` under one arm and release it on every exit path.

  Acquisition happens inside this function's instrumented boundary and the
  arm is released before output validation can run. This closes the gap where
  an instrumented `arm` call could install thread state and then fail while
  validating its returned map, before the caller had received `stop!`.
  `work` receives the acquired arm so it can read the diagnostic record and
  built-in observations. Nested work in any context inherits the governing
  arm and its inert stop remains safe."
  {:malli/schema
   [:=> [:cat :seon.sci.eval/ctx
         :seon.sci.eval/time-limit-ms
         [:fn clojure.core/ifn?]]
    [:any {:seon.schema.admission/exemption
           :seon.schema.admission/polymorphic-boundary
           :seon.schema.admission/reason
           "The caller's body determines its return type; with-arm preserves that exact result."}]]}
  [ctx time-limit-ms work]
  (let [armed (acquire-arm ctx time-limit-ms)]
    (try
      (work armed)
      (finally
        ((::stop! armed))))))

(def ^:dynamic *host-world*
  "The context a host body runs as when no evaluation arms its thread: a JVM
  test body is its member's agent (`seon.test/bounded-result` binds it), so a
  call it makes receives its supplied defaults exactly as that agent's
  evaluation would. An arm always governs over it."
  nil)

(defn world-ctx
  "The context of the evaluation governing this thread, else the host body's
  world (`*host-world*`), or nil when neither runs.

  Every entrance arms (`with-arm`: an evaluation, a named invocation, a test
  run), and the arm names the context it evaluates for its extent, nested work
  included; `current-arm`/`adopt-arm` carry it across threads. The context's
  environment is the world a call receives its supplied defaults from, so a
  closure made in one context and called in another evaluation uses the
  CALLING evaluation's world. Reading does not mark the arm travelled."
  {:malli/schema [:=> [:cat] [:or :nil :seon.sci.eval/ctx]]}
  []
  (or (::ctx (.get ^ThreadLocal (::thread-arm @process-guard)))
      *host-world*))

(defn with-world-ctx
  "Run `work` with `ctx` as the governing arm's context for its extent.

  For work inside an evaluation that acts on another context than the one
  evaluating (an admitted program operation on a fork or base). Unarmed work
  runs unchanged: there is no evaluation whose world it could name."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx [:fn clojure.core/ifn?]]
                  [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The caller's body determines its return type; scoping the arm's context preserves that exact result."}]]}
  [ctx work]
  (let [^ThreadLocal thread-arm (::thread-arm @process-guard)
        armed (.get thread-arm)]
    (if-not armed
      (work)
      (try (.set thread-arm (assoc armed ::ctx ctx))
           (work)
           (finally (.set thread-arm armed))))))

(defn arm?
  "True for a live arm value as handed out by `current-arm`."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (and (map? value)
       (instance? AtomicLong (::entries value))
       (instance? AtomicBoolean (::reached value))
       (instance? AtomicBoolean (::travelled value))))

(schema/register-core-predicate! 'seon.sci.kernel/arm? arm?)

(def arm-generator
  "A real arm value — honest by constructing an instance."
  (gen/fmap (fn [_] (new-armed)) (gen/return nil)))

(defn deadline-remaining-ms
  "Milliseconds left on the arm governing this thread, or nil when unarmed.

  The evaluation deadline is what ADMITTED the work, so blocking work must be
  able to ask how long it may block. Zero or a negative remainder means the
  deadline has already passed; nil means this thread is serving no arm, or is
  serving one that carries no deadline (an inherited nested arm), and the
  caller keeps its own bound.

  This is the interface change the timeout rule asks for: rather than bolting
  a second clock onto a handler, the arm publishes the one deadline it already
  owns, so a child process, a socket read, or any other host wait is cut by
  the limit that admitted it instead of outliving its evaluation."
  {:malli/schema [:=> [:cat] [:or :nil :int]]}
  []
  (when-let [armed (.get ^ThreadLocal (::thread-arm @process-guard))]
    (when-let [deadline (::deadline-nanos armed)]
      (quot (- (long deadline) (System/nanoTime)) 1000000))))

(defn deadline-reached?
  "True when the arm governing this thread has latched its deadline."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (boolean
   (when-let [armed (.get ^ThreadLocal (::thread-arm @process-guard))]
     (.get ^AtomicBoolean (::reached armed)))))

(defn current-arm
  "The arm governing this thread right now, as a value work can carry.

  This is the ONE way an arm leaves the thread that created it. Capture it on
  the submitting thread, put it in what crosses — the submission's
  environment under `:seon.sci.kernel/arm` — and the receiving thread hands
  it to `adopt-arm`. Nil means the caller is not inside an armed evaluation
  (system-side work), which is not an error: unarmed work simply carries no
  arm. Capturing marks the arm as travelled, so its deadline latch is kept
  live for the crossing even after the arming evaluation disarms."
  {:malli/schema [:=> [:cat] [:or :nil :seon.sci.kernel/arm]]}
  []
  (let [guard @process-guard]
    (when-let [armed (.get ^ThreadLocal (::thread-arm guard))]
      (.set ^AtomicBoolean (::travelled armed) true)
      armed)))

(defn detached-arm
  "A FRESH arm bounding work that must outlive the evaluation submitting it.

  `current-arm` is for work the submitter's limit should govern. This is for
  the other case — `my.background`, whose whole reason to exist is work that
  finishes after its turn — where inheriting the submitter's deadline latch
  would cut the work at the turn's end. The arm returned here shares nothing
  with the submitting arm: its own deadline, its own counters, its own latch,
  starting now. Hand it to the crossing under `:seon.sci.kernel/arm` exactly
  as a captured arm rides, and the thread that runs the work adopts it.

  Nothing about the submitter's limit or accounting carries over; an
  evaluation nested inside the detached work inherits this arm through `arm`.

  Because nothing owns the returned arm on a thread, nothing calls `stop!`
  for it: `release-arm!` cancels its deadline task when the work settles
  early. Absence of a release only leaves one scheduled task pending until
  the limit — never an unbounded evaluation."
  {:malli/schema [:=> [:cat :seon.sci.eval/time-limit-ms]
                  :seon.sci.kernel/arm]}
  [time-limit-ms]
  (let [base (new-armed)
        world (world-ctx)
        armed (cond-> (assoc base
                             ::deadline-nanos
                             (+ (::started-at base) (* (long time-limit-ms) 1000000)))
                ;; The detached work serves the submitting evaluation's world.
                world (assoc ::ctx world))]
    ;; It exists only to be carried, so it is travelled from birth.
    (.set ^AtomicBoolean (::travelled armed) true)
    (assoc armed
           ::deadline-task
           (.schedule deadline-timer
                      ^Runnable #(.set ^AtomicBoolean (::reached armed) true)
                      (long time-limit-ms)
                      TimeUnit/MILLISECONDS))))

(defn release-arm!
  "Cancel the deadline of an arm whose work has settled.

  Only a `detached-arm` carries its own task; every other arm is stopped by
  the evaluation that owns it. Releasing an arm without one is a no-op, so a
  caller never has to ask which kind it holds."
  {:malli/schema [:=> [:cat [:or :nil :seon.sci.kernel/arm]] :nil]}
  [armed]
  (when-let [^Future task (::deadline-task armed)]
    (.cancel task false))
  nil)

(defn adopt-arm
  "Run `work` on this thread governed by `carried-arm`, then restore.

  The receiving half of `current-arm`. Inside the extent, this thread's
  interpreted entrances count into `carried-arm`'s total, its deadline is the
  one being observed, and its `interrupt!` reaches this thread — so detached
  work is cut by the limit that admitted it rather than running unbounded.

  RE-ENTRANCY, one rule: adoption is strictly nested. A thread already armed
  saves that arm, serves the carried one for the extent of `work`, and
  restores on the way out. That is why arriving work never has to be refused
  and never merges two limits: at every instant the thread serves exactly one
  arm, and the displaced arm's deadline is a latch on its own value rather
  than a clock on this thread, so nothing about it is lost while it waits.
  A nil `carried-arm` runs `work` unarmed, unchanged."
  {:malli/schema [:=> [:cat [:or :nil :seon.sci.kernel/arm] [:fn clojure.core/ifn?]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "The caller's body determines its return type; adopting the kernel arm preserves that exact result.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [carried-arm work]
  (if-not carried-arm
    (work)
    (let [^ThreadLocal thread-arm (::thread-arm @process-guard)
          displaced (.get thread-arm)
          allocated-at-entry (allocated-bytes)]
      (.set thread-arm carried-arm)
      (try
        (work)
        (finally
          (let [allocated-at-exit (allocated-bytes)]
            (when (and (not (neg? allocated-at-entry))
                       (not (neg? allocated-at-exit)))
              (.addAndGet ^AtomicLong (::adopted-allocated-bytes carried-arm)
                          (- allocated-at-exit allocated-at-entry))))
          (if displaced
            (.set thread-arm displaced)
            (.remove thread-arm)))))))

(defn future-call
  "`clojure.core/future-call` carrying the arm governing this thread
  (`current-arm`, `adopt-arm`): the future's interpreted work counts into the
  evaluation's one entrance total and is cut by its one deadline, instead of
  running unbounded on a pool thread. Unarmed callers get a plain future."
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?]] [:fn clojure.core/future?]]}
  [f]
  (let [carried (current-arm)]
    (clojure.core/future-call #(adopt-arm carried f))))

(defn- without-interrupt-marker
  "One cause-chain link without SCI's private interrupt marker.
  `sci.interrupt/interrupt!` tags its ex-data with an opaque identity object
  (`sci.impl.utils/interrupt-marker`) that `interrupted?` reads; it is never
  evidence."
  {:malli/schema [:=> [:cat :seon.error/link] :seon.error/link]}
  [link]
  (if-let [data (not-empty (dissoc (:seon.error/data link) :sci.impl/interrupt))]
    (assoc link :seon.error/data data)
    (dissoc link :seon.error/data)))

(defn- execution-error-text
  "`clojure.main`'s own text for one exception an agent's code threw:
  `ex-triage` names the root class and message. SCI's location wrapper
  (`:type :sci/error`, `sci/impl/utils.cljc:121-175`) names the form's line,
  column and file; it stands where clojure.main names its compiled frame,
  which here would be the interpreter's own. Only that wrapper is a location:
  an agent's own `ex-data` is never read as one, and without the wrapper
  `ex-triage`'s frame stands whole."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:string {:min 1}]]}
  [throwable]
  (let [data (ex-data throwable)
        triage (main/ex-triage (Throwable->map throwable))]
    (str/trim
     (main/ex-str
      (if (isa? (:type data) :sci/error)
        (-> (dissoc triage :clojure.error/symbol :clojure.error/line :clojure.error/column)
            (assoc :clojure.error/source (or (:file data) "REPL"))
            (cond-> (int? (:line data)) (assoc :clojure.error/line (:line data))
                    (int? (:column data)) (assoc :clojure.error/column (:column data))))
        triage)))))

(defn- sci-location?
  "Whether `link` is SCI's location wrapper (`:type :sci/error` carrying its
  `:sci.impl/callstack`, sci/impl/utils.cljc:143-182): a copy of the author's
  exception rethrown with the form's line and column, never an author link."
  {:malli/schema [:=> [:cat :seon.error/link] :boolean]}
  [link]
  (let [data (:seon.error/data link)]
    (boolean (and (isa? (:type data) :sci/error) (contains? data :sci.impl/callstack)))))

(defn- stacktrace
  "The author's SCI frames for `throwable`, innermost first, as
  `[where line column]` (`sci.core/stacktrace`, sci/core.cljc:419): the
  source location of an interpreted failure. SCI's live callstack volatile is
  read here once and never leaves as a value."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:maybe :seon.sci.eval/stacktrace]]}
  [throwable]
  (not-empty
   (into [] (keep (fn [{frame-ns :ns frame-name :name :keys [line column]}]
                    (when (and frame-ns (int? line) (int? column))
                      [(if frame-name (symbol (str frame-ns) (str frame-name)) (symbol (str frame-ns)))
                       line column])))
         (try (sci/stacktrace throwable) (catch Exception _ nil)))))

(defn- author-throwable
  "The exception the author's code threw: `throwable` past SCI's location
  wrappers (`sci-location?`), which only copy it with the form's position."
  {:malli/schema [:=> [:cat :seon.error/throwable] :seon.error/throwable]}
  [throwable]
  (let [data (ex-data throwable)]
    (if (and (isa? (:type data) :sci/error) (contains? data :sci.impl/callstack) (ex-cause throwable))
      (recur (ex-cause throwable))
      throwable)))

(defn failure-value
  "The minimal complete error for a failure of an agent's evaluation, the
  way `clojure.main` reports it (`ex-triage`/`ex-str`, main.clj:219-300):
  the class and message the author's code threw, never SCI's location
  wrapper; the whole cause chain once, each link's message and `ex-data`
  (worlds referenced) and first-party frames, kept whole; the author's
  source location as `:seon.sci.eval/stacktrace` (SCI's own). What an agent
  is shown is the schema pair's selection (`render-ai`), never a trim here.
  The evaluation's duration, allocation and outcome stay on its record.
  The value is a `:seon.sci.kernel/execution-error`; a fired deadline also
  names its guard observation (`:seon.sci.kernel/error`). An existing
  structural refusal is preserved with the chain around it and the author's
  class, which every alternative carries.
  A core fault already recorded by this propagation (its receipt anywhere in
  the cause chain, `seon.fault/carried-receipt`) is rethrown, never turned
  into an ordinary evaluation value: its panic reaches the caller."
  {:seon/tasks #{"e13e5a9c557f"}
   :malli/schema
   [:=> [:cat :seon.sci.kernel/failure-request [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A kernel failure can carry any thrown or returned value; normalization must preserve evidence of an unrecognized failure.", :gen/elements [nil false 0 "" :k [] {}]}] :seon.sci.admit/record]
    [:or :seon.sci.kernel/execution-error :seon.sci.kernel/error]]}
  [{subject :seon.fn/sym}
   throwable
   diagnostic-record]
  (when (fault/carried-receipt throwable) (throw throwable))
  (let [timed-out? (= :time (:seon.eval/outcome diagnostic-record))
        author (author-throwable throwable)
        ;; The whole cause chain, read once, without SCI's location copies.
        links (error.refusal/without-links
               (mapv without-interrupt-marker
                     (error.refusal/chain author (schema/handed-projection)))
               sci-location?)
        failing-symbol (:sci.impl/symbol (ex-data author))
        location (stacktrace throwable)
        located (fn [error]
                  (cond-> (assoc error :seon.error/exception-class (symbol (.getName (class author))))
                    (seq links) (assoc :seon.error/chain links)
                    location (assoc :seon.sci.eval/stacktrace location)
                    (and (:seon.error/frame error)
                         (not (error.refusal/first-party-frame? (:seon.error/frame error))))
                    (dissoc :seon.error/frame)))
        existing (error.refusal/refusal throwable)]
    (if (and (map? existing) (contains? existing :seon.error/at) (contains? existing :seon.error/layer) (contains? existing :seon.error/operation)) ;; debt: seon.error.refusal/refusal declares the generic :seon.error/base output (:seon.error/value's target) alongside its declared-schemas.
      ;; The refusal is already the boundary value, with the chain around
      ;; it; the link that carried the refusal as its ex-data names it by
      ;; class only (its message is the refusal's own).
      (let [own? #(= (select-keys % [:seon.error/at :seon.error/operation])
                     (select-keys existing [:seon.error/at :seon.error/operation]))
            own-link (fn [link]
                       (cond-> (dissoc link :seon.error/data)
                         (= (:seon.error/message link) (:seon.error/message existing))
                         (dissoc :seon.error/message)))
            uncarried? (not-every? (set links) (:seon.error/chain existing))]
        (cond-> (located existing)
          ;; A refusal whose own chain holds links the throwable does not
          ;; carry (a rejection it records, never a Throwable cause) keeps that
          ;; chain whole after the links wrapping it; the carrier link is the
          ;; refusal itself. Replacing it dropped the rejection's message and
          ;; data (instrument's minimal refusal).
          uncarried?
          (assoc :seon.error/chain
                 (let [outer (error.refusal/without-links links #(own? (:seon.error/data %)))
                       shift (fn [link k] (cond-> link (find link k) (update k + (count outer))))]
                   (into outer (map #(-> % (shift :seon.error/suppressed-by) (shift :seon.error/cause-of)))
                         (:seon.error/chain existing))))
          (and (seq links) (not uncarried?))
          (assoc :seon.error/chain (mapv #(if (own? (:seon.error/data %)) (own-link %) %) links))
          (not (string? (:seon.error/message existing)))
          (assoc :seon.error/message
                 (or (ex-message throwable) "The operation was refused."))))
      (located
       (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.kernel/evaluation 'seon.sci.kernel/failure-value
       (cond->
        {:seon.error/message
         (or (:seon.error/message existing)
         (cond->> (if timed-out?
                    (str "Ran out of time after "
                         (:seon.eval/duration-ms diagnostic-record) "ms.")
                    (execution-error-text throwable))
           subject (str "Invocation of " subject " failed: ")))}
         ;; An agent's own exception is an ordinary value, not a refusal:
         ;; it names no expected requirement it failed. Its class and chain
         ;; are the author's (`located`), so no Throwable is handed over.
         ;; SCI's unresolved symbol: the evidence the correction reads
         ;; (`seon.error`'s unresolved-symbol fix).
         failing-symbol
         (assoc :seon.error/data {:seon.sci.eval/symbol failing-symbol})
         ;; The deadline guard fired: its observation is the evidence.
         timed-out?
         (assoc :seon.sci.kernel/guard-observation
                {:seon.error.evidence/attribute :seon.eval/duration-ms
                 :seon.error.evidence/value (:seon.eval/duration-ms diagnostic-record)})
         ;; Absent, never stored nil: a failure without a failing symbol or
         ;; subject (a reader error) names no offending value.
         (or failing-symbol subject)
         (assoc :seon.error/offending (or failing-symbol subject))))))))

(defn render-ai
  "An agent's own exception as REPL entries, the way `clojure.main` reports
  it: its message (class and location), then the author's SCI stacktrace and
  each cause's `ex-data`, read from the held error. The whole chain with its
  first-party frames is one read away."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [unit]
  (let [value (if (map? (:seon.render/value unit)) (:seon.render/value unit) unit)
        held (error/held-reference unit)
        location (:seon.sci.eval/stacktrace value)]
    (error/source-entries
     (concat
      [(or (:seon.error/message value) (str (:seon.error/exception-class value)))]
      (when location
        [(if held (list :seon.sci.eval/stacktrace held) (str "At " (pr-str location)))])
      (when (and held (some :seon.error/data (:seon.error/chain value)))
        [(list 'keep :seon.error/data (list :seon.error/chain held))])
      (when held [(str "Cause chain: (:seon.error/chain " held ")")])))))

(defn unarmed-record
  "The diagnostic record for a failure that never reached an arm."
  {:malli/schema [:=> [:cat :int] :seon.sci.admit/record]}
  [started-at]
  {:seon.eval/fn-entries 0
   :seon.eval/host-interop-count 0
   :seon.eval/duration-ms
   (quot (- (System/nanoTime) started-at) 1000000)
   :seon.eval/allocated-bytes -1
   :seon.eval/outcome :error})

(defn invoke
  "Resolve and invoke one live SCI Var, admitting its value before disarm."
  {:malli/schema [:=> [:cat :seon.sci.eval/invocation-request]
                  :seon.sci.eval/invocation-result]}
  [{ctx :seon.sci.eval/ctx
    database :seon.db/db
    function-symbol :seon.fn/sym
    function-value :seon.sci.eval/function
    arguments :seon.sci.eval/args
    time-limit-ms :seon.sci.eval/time-limit-ms
    caps :seon.sci.admit/caps
    unbounded? :seon.sci.admit/unbounded?
    read-evidence-sink :seon.db/read-evidence-sink}]
  (let [started-at (System/nanoTime)
        arm-state (volatile! nil)]
    (try
      (with-arm
       ctx time-limit-ms
       (fn [{:keys [interrupt-fn] record-fn ::record :as armed}]
         (vreset! arm-state armed)
         (when-not function-value (ensure-function! ctx database function-symbol))
         (binding [db/*conn*
                  (get-in ctx
                          [:seon.sci.eval/custody
                           :seon.db/connection])
                  db/*read-evidence-sink*
                  (or read-evidence-sink db/*read-evidence-sink*)]
          (let [sci-var (if function-value function-value (sci/resolve ctx function-symbol))]
            (when-not (or function-value (sci.utils/var? sci-var))
              (throw
               (ex-info (str function-symbol " is not an installed SCI Var.")
                        {:seon.error/at (java.util.Date.)
                          :seon.error/layer :seon.sci.kernel/evaluation
                          :seon.error/operation 'seon.sci.kernel/invoke
                          :seon.error/message "Requested function has no installed SCI Var; acquire the function before invocation."
                          :seon.sci.kernel/guard-observation {:seon.error.evidence/attribute :seon.fn/sym
                           :seon.error.evidence/value function-symbol}
                          :seon.fn/sym function-symbol
                          :seon.error/expected :seon.sci.eval/invocation-result})))
            (let [;; The callee's own armed wrapper supplies its declared
                  ;; defaults from this arm's context (`world-ctx`).
                  value (apply sci-var arguments)
                  invocation-record (record-fn :ok)]
              (admit/admit-value
               (cond->
                {:seon.sci.admit/value value
                 :seon.schema/projection (or (:seon.schema/projection (meta database))
                                             (context-projection ctx))
                 :seon.sci.admit/interrupt-fn interrupt-fn
                 :seon.sci.admit/caps caps
                 :seon.sci.admit/record invocation-record}
                 unbounded?
                 (assoc :seon.sci.admit/unbounded? true))))))))
      (catch Throwable throwable
        (let [record-value
              (if-let [record-fn (::record @arm-state)]
                (record-fn (if (interrupted? throwable) :time :error))
                (unarmed-record started-at))
              failure (failure-value
                       (cond-> {::time-limit-kind ::time-limit
                                ::failure-kind ::invocation-failed}
                         function-symbol (assoc :seon.fn/sym function-symbol))
                       throwable record-value)]
          (try
            (admit/admit-value
             {:seon.sci.admit/value failure
              :seon.schema/projection (or (:seon.schema/projection (meta database))
                                             (context-projection ctx))
              :seon.sci.admit/interrupt-fn (constantly nil)
              :seon.sci.admit/caps caps
              :seon.sci.admit/record record-value})
            (catch Throwable admission-failure
              {:seon.sci.admit/value
               (seon.error.refusal/diagnostic (java.util.Date.) :seon.sci.kernel/evaluation 'seon.sci.kernel/invoke
                {:seon.error/message "The invocation failure could not be admitted; inspect its admission evidence."
                 :seon.sci.kernel/guard-observation {:seon.error.evidence/attribute :seon.error/message
                  :seon.error.evidence/value (or (ex-message admission-failure) "Failure admission failed.")}
                 :seon.error/throwable admission-failure
                 ;; the failure could not be admitted, so it is named by its
                 ;; classifying base, never carried whole past admission
                 :seon.error/offending (select-keys failure [:seon.error/at :seon.error/layer
                                                             :seon.error/operation :seon.error/message])
                 :seon.error/expected :seon.sci.eval/invocation-result})
               :seon.sci.admit/record record-value})))))))
