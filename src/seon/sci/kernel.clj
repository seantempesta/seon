(ns seon.sci.kernel
  "The one guarded operation shared by evaluation and renderer calls.

  SCI calls the stable interrupt function at interpreted function-body and
  loop entrances. One process guard keeps per-thread arm state; an invocation
  either owns a new arm or inherits the same SCI interpreter context's active
  arm. Values are admitted before disarm. Host calls remain SCI's documented
  interruption ceiling and are never described as hard-stoppable.

  THE ARM IS A VALUE AND TRAVELS WITH THE WORK. The thread-local slot is the
  fast path for finding the arm governing the running thread, never the arm's
  identity: `current-arm` hands the governing arm out as a value that rides a
  submission the way the environment does, and `adopt-arm` installs it on the
  thread that actually runs the work. The deadline is a latch shared by the
  arm's value, so every thread serving one evaluation observes one deadline,
  counts into one entrance total, and is reachable by one `interrupt!`."
  (:require [clojure.test.check.generators :as gen]
            [sci.core :as sci]
            [sci.impl.utils :as sci.utils]
            [sci.interrupt :as sci.interrupt]
            [seon.call-preparation :as call-preparation]
            [seon.db :as db]
            [seon.error :as error]
            [seon.error.refusal :as error.refusal]
            [seon.schema :as schema]
            [seon.sci.admit :as admit])
  (:import [java.lang.management ManagementFactory]
           [java.util.concurrent Future ScheduledThreadPoolExecutor TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean AtomicLong]))

(defn interrupted?
  "True when a throwable or one of its causes is SCI's interrupt."
  {:malli/schema [:=> [:cat :any] :boolean]}
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
                {:seon.error/kind ::missing-function-installer
                 :seon.fn/sym (str function-symbol) :seon.sci.kernel/missing-function-installer (str function-symbol)}))))
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
  [ctx]
  (let [allocated-at-start (allocated-bytes)]
    {::ctx ctx
     ::entries (AtomicLong. 0)
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
  [ctx guard time-limit-ms]
  (let [^ThreadLocal thread-arm (::thread-arm guard)
        base (new-armed ctx)
        ;; The deadline as a value ON the arm, so work that BLOCKS can honour
        ;; it. The latch alone is reachable only from an interpreted function
        ;; entrance, and a thread parked in a host call makes none — which is
        ;; how an interrupted `my.shell/run` left its child running for its
        ;; natural lifetime while its receipt stayed open.
        armed (assoc base
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

(defn- same-interpreter?
  [armed ctx]
  (identical? (:env (::ctx armed)) (:env ctx)))

(defn arm
  "Arm `ctx` on this thread and return its stop, record, and observers.

  ONE re-entrancy rule for every entrance, because a second rule is how the
  two boundaries diverged: work inherits only the arm installed in THIS
  thread's ThreadLocal and only when that arm names the same SCI interpreter
  through its `:env` reference. Context maps carrying a scoped environment may
  differ while still naming that same interpreter. A sibling thread sees no
  current arm and therefore owns an independent one even when it evaluates the
  same context concurrently. The outer deadline keeps governing nested work,
  the returned `::stop!` is inert, and no second timer exists. A DIFFERENT SCI
  interpreter context on an armed thread is refused, because one thread cannot
  honestly serve two limits. A true `sci/fork` has a distinct `:env` reference
  and remains different. `::built-in-calls` reports the governing arm's
  observations either way.

  THE THREAD IS NOT THE ARM. Work that leaves this thread carries the arm as
  a value (`current-arm`) and the receiving thread installs it for the extent
  of that work (`adopt-arm`), so a crossing neither escapes the deadline nor
  starts a second one: there is exactly one latch, one entrance total, and
  one reachable `interrupt!` per armed evaluation, on however many threads."
  {:malli/schema [:=> [:cat :seon.sci.eval/ctx
                       :seon.sci.eval/time-limit-ms]
                  :map]}
  [ctx time-limit-ms]
  (let [guard (::guard ctx)]
    (when-not guard
      (throw
       (ex-info "SCI context has no stable interrupt guard."
                {:seon.error/kind ::missing-interrupt-guard :seon.sci.kernel/missing-interrupt-guard true})))
    (if-let [armed (current-thread-arm guard)]
      (do
        (when-not (same-interpreter? armed ctx)
          (throw
           (ex-info "A different SCI context is already armed on this thread."
                    {:seon.error/kind ::already-armed :seon.sci.kernel/already-armed true})))
        {:interrupt-fn (::interrupt-fn guard)
         ::built-in-calls (fn [] @(::built-in-calls armed))
         ::stop! (constantly nil)
         ::record #(record armed %)})
      (own-arm ctx guard time-limit-ms))))

(defn arm?
  "True for a live arm value as handed out by `current-arm`."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (and (map? value)
       (instance? AtomicLong (::entries value))
       (instance? AtomicBoolean (::reached value))
       (instance? AtomicBoolean (::travelled value))))

(schema/register-core-predicate! 'seon.sci.kernel/arm? arm?)

(def arm-generator
  "A real arm value — honest by constructing an instance."
  (gen/fmap (fn [_] (new-armed nil)) (gen/return nil)))

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

  It keeps the submitting thread's SCI CONTEXT when there is one, so an
  evaluation nested inside the detached work INHERITS this arm through `arm`
  rather than being refused as a second context on an armed thread. The
  context is the only thing carried over; nothing about the submitter's
  limit or accounting is.

  Because nothing owns the returned arm on a thread, nothing calls `stop!`
  for it: `release-arm!` cancels its deadline task when the work settles
  early. Absence of a release only leaves one scheduled task pending until
  the limit — never an unbounded evaluation."
  {:malli/schema [:=> [:cat :seon.sci.eval/time-limit-ms]
                  :seon.sci.kernel/arm]}
  [time-limit-ms]
  (let [^ThreadLocal thread-arm (::thread-arm @process-guard)
        base (new-armed (some-> (.get thread-arm) ::ctx))
        armed (assoc base
                     ::deadline-nanos
                     (+ (::started-at base) (* (long time-limit-ms) 1000000)))]
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
  {:malli/schema [:=> [:cat [:or :nil :seon.sci.kernel/arm]
                       [:fn clojure.core/ifn?]]
                  :any]}
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

(defn failure-value
  "The ONE flat `:seon.error` value for a failure at the guarded boundary.

  Both entrances classify here so they cannot drift apart. A throwable that
  already carries a refusal — an instrument contract violation, a refused
  schema declaration, an unresolved invocation — keeps its own
  `:seon.error/kind` and gains this boundary's evidence; everything else
  becomes `::time-limit-kind` when the diagnostic record's outcome is `:time`
  and `::failure-kind` otherwise. `:seon.fn/sym` is the invoked function
  symbol when one exists: it prefixes the message and rides in the data. A
  form evaluation supplies no symbol, which is the ONLY difference between
  the two entrances — the classification itself is identical."
  {:malli/schema [:=> [:cat :seon.sci.kernel/failure-request
                       :any
                       :seon.sci.admit/record]
                  :seon.error/value]}
  [{subject :seon.fn/sym
    time-limit-kind ::time-limit-kind
    failure-kind ::failure-kind}
   throwable
   diagnostic-record]
  (let [timed-out? (= :time (:seon.eval/outcome diagnostic-record))
        evidence (cond-> {:seon.sci.eval/throwable (.getName (class throwable))
                          :seon.sci.admit/record diagnostic-record}
                   subject (assoc :seon.fn/sym subject))
        existing (error.refusal/refusal throwable)]
    (if (:seon.error/kind existing)
      ;; The refusal is already the boundary value. Wrapping it copied its
      ;; data, its ex-data (the whole refusal), and its throw-site message into
      ;; a `:nested-refusal` envelope, so the terminal renderer fitted six
      ;; copies of one blob. Accrete this boundary's one new observation.
      (cond-> (update existing :seon.error/data
                      (fn [data]
                        (merge (or data {}) evidence)))
        (not (string? (:seon.error/message existing)))
        (assoc :seon.error/message
               (or (ex-message throwable) "The operation was refused.")))
      (error/diagnostic
       (let [kind (if timed-out? time-limit-kind failure-kind)]
         {kind (or subject :evaluation)
        :seon.error/kind kind
      :seon.error/message
      (or (:seon.error/message existing)
          (cond->> (if timed-out?
                     (str "Ran out of time after "
                          (:seon.eval/duration-ms diagnostic-record) "ms.")
                     (or (ex-message throwable)
                         (.getName (class throwable))))
            subject (str "Invocation of " subject " failed: ")))
      :seon.error/diagnostic-layer :sci
      :seon.error/diagnostic-operation (or subject :evaluation)
      :seon.error/diagnostic-member :throwable
      :seon.error/diagnostic-expected :successful-evaluation
      :seon.error/diagnostic-offending
      (or (:sci.impl/symbol (ex-data throwable)) subject)
      :seon.error/diagnostic-cause
      (or (ex-message throwable) (.getName (class throwable)))
      :seon.error/diagnostic-evidence diagnostic-record
      :seon.error/data
      (cond-> evidence
        (ex-data throwable)
        (assoc :seon.sci.eval/data (ex-data throwable))

        (:sci.impl/symbol (ex-data throwable))
        (assoc :seon.sci.eval/symbol (:sci.impl/symbol (ex-data throwable)))

        (ex-message throwable)
        (assoc :seon.error/throw-site-message (ex-message throwable)))})))))

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
    function-symbol-string :seon.fn/sym
    arguments :seon.sci.eval/args
    time-limit-ms :seon.sci.eval/time-limit-ms
    caps :seon.sci.admit/caps
    read-evidence-sink :seon.db/read-evidence-sink
    on-core-error :seon.config/on-core-error}]
  (let [started-at (System/nanoTime)
        arm-state (volatile! nil)
        function-symbol (symbol function-symbol-string)]
    (try
      (let [{:keys [interrupt-fn] record-fn ::record :as armed}
            (arm ctx time-limit-ms)]
        (vreset! arm-state armed)
        (ensure-function! ctx database function-symbol)
        (binding [db/*conn*
                  (get-in ctx
                          [:seon.sci.eval/custody
                           :seon.db/connection])
                  db/*read-evidence-sink*
                  (or read-evidence-sink db/*read-evidence-sink*)]
          (let [sci-var (sci/resolve ctx function-symbol)]
            (when-not (sci.utils/var? sci-var)
              (throw
               (ex-info (str function-symbol " is not an installed SCI Var.")
                        {:seon.error/kind ::unresolved-invocation
                         :seon.fn/sym function-symbol-string :seon.sci.kernel/unresolved-invocation function-symbol-string})))
            (let [;; The SECOND of the two ruled call-preparation
                  ;; entrances. SCI's analyzed call path hooks itself; a
                  ;; named invocation applies the Var directly, so it
                  ;; consults the same hook here rather than growing a
                  ;; second preparation implementation. A `reduced`
                  ;; return is preparation refusing — the callee body is
                  ;; never entered and the flat value is the result,
                  ;; admitted like any other.
                  prepared (call-preparation/hook ctx sci-var
                                                  (vec arguments))
                  value (if (reduced? prepared)
                          (deref prepared)
                          (apply sci-var prepared))
                  invocation-record (record-fn :ok)]
              (admit/admit-value
               {:seon.sci.admit/value value
                :seon.sci.admit/interrupt-fn interrupt-fn
                :seon.sci.admit/caps caps
                :seon.config/on-core-error on-core-error
                :seon.sci.admit/record invocation-record})))))
      (catch Throwable throwable
        (let [record-value
              (if-let [record-fn (::record @arm-state)]
                (record-fn (if (interrupted? throwable) :time :error))
                (unarmed-record started-at))
              failure (failure-value
                       {:seon.fn/sym function-symbol-string
                        ::time-limit-kind ::time-limit
                        ::failure-kind ::invocation-failed}
                       throwable record-value)]
          (try
            (admit/admit-value
             {:seon.sci.admit/value failure
              :seon.sci.admit/interrupt-fn (constantly nil)
              :seon.sci.admit/caps caps
              :seon.config/on-core-error :record
              :seon.sci.admit/record record-value})
            (catch Throwable admission-failure
              {:seon.sci.admit/value
               {:seon.error/kind ::failure-admission-failed
                :seon.error/message
                (str (:seon.error/message failure)
                     " Failure admission also failed: "
                     (or (ex-message admission-failure)
                         (.getName (class admission-failure))))
                :seon.error/data
                {:seon.sci.eval/throwable
                 (.getName (class admission-failure))} :seon.sci.kernel/failure-admission-failed true}
               :seon.sci.admit/capped? false
               :seon.sci.admit/record record-value}))))
      (finally
        (when-let [stop! (::stop! @arm-state)]
          (try (stop!) (catch Throwable _ nil)))))))
