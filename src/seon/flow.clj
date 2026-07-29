(ns seon.flow
  "Production-shaped core.async.flow launchers used by the standing testbed.

   This namespace deliberately does not own durable runtime state. Ordinary
   Flow processes retain only disposable counters and handles. Flow channels
   carry scheduling and wake signals."
  (:require [clojure.core.protocols :as core.protocols]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.core.async.flow.impl.graph :as flow.graph]
            [clojure.core.async.flow.spi :as flow.spi]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.datafy :as datafy]
            [clojure.test.check.generators :as gen]
            [clojure.walk :as walk]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn])
  (:import [clojure.lang Counted]
           [java.util LinkedList]
           [java.util.concurrent Executor ExecutorService Executors Future
            TimeUnit]))

(set! *warn-on-reflection* true)

(defn- executor?
  [value]
  (instance? Executor value))

(defn- atom-reference?
  [value]
  (instance? clojure.lang.IAtom value))

(defn- java-future?
  [value]
  (instance? Future value))

(defn- proc-launcher?
  [value]
  (satisfies? flow.spi/ProcLauncher value))

(defn- graph?
  [value]
  (satisfies? flow.graph/Graph value))

(defn- channel?
  [value]
  (satisfies? async.impl/Channel value))

(schema/register-core-predicate! 'seon.flow/executor? executor?)
(schema/register-core-predicate! 'seon.flow/atom-reference? atom-reference?)
(schema/register-core-predicate! 'seon.flow/java-future? java-future?)
(schema/register-core-predicate! 'seon.flow/proc-launcher? proc-launcher?)
(schema/register-core-predicate! 'seon.flow/graph? graph?)
(schema/register-core-predicate! 'seon.flow/channel? channel?)

(defonce ^:private generator-values
  (delay
    {:executor (Executors/newSingleThreadExecutor)
     :atom-reference (atom {})
     :future (java.util.concurrent.FutureTask. (fn [] nil))
     :proc-launcher
     (reify flow.spi/ProcLauncher
       (describe [_] {:params {} :ins {} :outs {}})
       (start [_ _] nil))
     :graph (flow/create-flow {:procs {} :conns []})
     :channel (async/chan)}))

(def executor-generator
  (gen/fmap (fn [_] (:executor @generator-values)) (gen/return nil)))
(def atom-reference-generator
  (gen/fmap (fn [_] (:atom-reference @generator-values)) (gen/return nil)))
(def java-future-generator
  (gen/fmap (fn [_] (:future @generator-values)) (gen/return nil)))
(def proc-launcher-generator
  (gen/fmap (fn [_] (:proc-launcher @generator-values)) (gen/return nil)))
(def graph-generator
  (gen/fmap (fn [_] (:graph @generator-values)) (gen/return nil)))
(def channel-generator
  (gen/fmap (fn [_] (:channel @generator-values)) (gen/return nil)))

(schema.edn/load! {})

(defn var-process
  "Build one Flow proc launcher from a step VAR and a pinned workload.
  THE construction door for every proc in the system (F0(a), the F1
  blueprint): it REFUSES a non-var step — an anonymous step captures
  its closures and hot reload silently stops applying to running
  graphs — and REFUSES a missing or `:mixed` workload, because the
  `:mixed` default pins one platform thread per proc forever and is
  the one measured scaling cliff (flow-mechanics 2026-07-28 §1). Both
  refusals are construction-time throws, never review items. `args`
  merge into the start options' `:args` so `create-flow` definitions
  stay pure data."
  {:malli/schema
   [:function
    [:=> [:cat :any [:enum :io :compute] :map] ::launcher]
    [:=> [:cat :any [:enum :io :compute] :map :map] ::launcher]]}
  ([step-var workload args]
   (var-process step-var workload args {}))
  ([step-var workload args options]
   (when-not (var? step-var)
     (throw
      (ex-info
       "A Flow proc step must be a Var so running graphs hot reload."
       {::step step-var})))
   (when-not (contains? #{:io :compute} workload)
     (throw
      (ex-info
       "A Flow proc must declare either :io or :compute workload."
       {::step step-var
        ::workload workload})))
   (let [launcher
         (flow/process
          step-var
          (assoc options :workload workload))]
     (reify
       core.protocols/Datafiable
       (datafy [_]
         (datafy/datafy launcher))

       flow.spi/ProcLauncher
       (describe [_]
         (flow.spi/describe launcher))
       (start [_ start-options]
         (flow.spi/start
          launcher
          (update start-options :args #(merge args %))))))))

(defn bounded-platform-executor
  "Create a bounded executor whose workers are platform threads."
  {:malli/schema [:=> [:catn [::parallelism ::parallelism]] ::executor]}
  [parallelism]
  (Executors/newFixedThreadPool (int parallelism)))

(defn- virtual-task-executor
  []
  (Executors/newVirtualThreadPerTaskExecutor))

(defn- capacity-facts
  [parallelism active-work]
  (let [active @active-work
        compute-active
        (into {}
              (filter (fn [[_ facts]]
                        (= :compute (::workload facts))))
              active)]
    {::active-submissions (set (keys compute-active))
     ::active-procs (set (keys compute-active))
     ::wedged-submissions
     (into #{}
           (keep (fn [[submission-id facts]]
                   (when (::wedged? facts) submission-id)))
           compute-active)
     ::wedged-procs
     (into #{}
           (keep (fn [[submission-id facts]]
                   (when (::wedged? facts) submission-id)))
           compute-active)
     ::available-capacity (- parallelism (count compute-active))
     ::available-permits (- parallelism (count compute-active))
     ::platform-threads?
     (every? ::platform-thread? (vals compute-active))}))

(defn- capacity-observer-step
  ([]
   {:ins {::observe "A process-local request to refresh observations."}
    :workload :compute
    :ping-map-fn
    (fn [{::keys [parallelism active-work]}]
      (capacity-facts parallelism active-work))})
  ([args]
   (assoc args ::observations 0))
  ([state _transition]
   state)
  ([{::keys [parallelism active-work] :as state} _input _message]
   [(update state ::observations inc)
    {::flow/report
     [(assoc (capacity-facts parallelism active-work)
             ::event ::capacity)]}]))

(defn capacity-observer-proc
  "Create a responsive proc that reports current compute occupancy."
  {:malli/schema
   [:=> [:catn [::request ::capacity-observer-request]] ::launcher]}
  [request]
  (var-process #'capacity-observer-step :compute request))

(defn- launcher-ping
  [pid status count ins outs parallelism active-work]
  (walk/postwalk
   datafy/datafy
   #::flow{:pid pid
           :status status
           :count count
           :ins (dissoc ins ::flow/control ::flow/casts)
           :outs (dissoc outs ::flow/error ::flow/report)
           :state (capacity-facts parallelism active-work)}))

(defn- execute-work!
  [pid compute-executor error report completion active-work
   {::keys [submission-id work-fn result status] :as work}]
  (if-not (compare-and-set! status ::queued ::running)
    (async/offer!
     completion
     {::submission-id submission-id
      ::workload :compute})
    (do
      (swap! active-work
             assoc submission-id
             {::workload :compute
              ::wedged? false
              ::platform-thread? false})
      (try
        (.execute
         ^Executor compute-executor
         ^Runnable
         (fn []
           (swap! active-work
                  update submission-id
                  assoc
                  ::platform-thread?
                  (not (.isVirtual (Thread/currentThread))))
           (let [started-at (volatile! nil)
                 started!
                 (fn []
                   (when (nil? @started-at)
                     (vreset! started-at (System/nanoTime))))]
             (try
               (let [value (work-fn {::started! started!})]
                 (started!)
                 (deliver result {::started-at @started-at
                                  ::value value})
                 (async/offer!
                  report
                  {::pid pid
                   ::event ::work-complete
                   ::submission-id submission-id
                   ::value value}))
               (catch Throwable throwable
                 (started!)
                 (deliver result {::started-at @started-at
                                  ::throwable throwable})
                 (async/>!!
                  error
                  #::flow{:pid pid
                          :status :running
                          :cid ::compute-submission
                          :msg (dissoc work ::work-fn ::result ::status)
                          :op ::work
                          :ex throwable}))
               (finally
                 (reset! status ::completed)
                 (swap! active-work dissoc submission-id)
                 (when-not
                  (async/offer!
                   completion
                   {::submission-id submission-id
                    ::workload :compute})
                   (async/offer!
                    error
                    #::flow{:pid pid
                            :status :running
                            :cid ::compute-submission
                            :op ::completion-overflow
                            :ex
                            (ex-info
                             "The launcher completion channel overflowed."
                             {::submission-id submission-id})})))))))
        (catch Throwable throwable
          (reset! status ::completed)
          (swap! active-work dissoc submission-id)
          (deliver result {::started-at (System/nanoTime)
                           ::throwable throwable})
          (async/offer!
           completion
           {::submission-id submission-id
            ::workload :compute}))))))

(defn- work-launcher-description
  []
  {:ins {::compute-submission
         "One disposable compute submission backed by durable work."}
   :workload :io})

(defn- work-launcher-proc
  [{::keys [parallelism active-work task-executor]}]
  (reify
    core.protocols/Datafiable
    (datafy [_]
      {::launcher ::work-launcher
       :desc (work-launcher-description)})

    flow.spi/ProcLauncher
    (describe [_]
      (work-launcher-description))
    (start [_ {:keys [pid ins outs resolver]}]
      (let [control (::flow/control ins)
            submission (::compute-submission ins)
            error (::flow/error outs)
            report (::flow/report outs)
            completion (async/chan parallelism)
            compute-executor
            (or task-executor (flow.spi/get-exec resolver :compute))]
        (.execute
         ^Executor (flow.spi/get-exec resolver :io)
         ^Runnable
         (fn []
           ;; A channel is a scheduling buffer over durable work, never the
           ;; record of the work. Its fixed buffer parks submitters. Only this
           ;; loop consumes class channels and accounts live compute slots.
           (loop [status :paused
                  count 0
                  active-count 0]
             (let [channels
                   (cond-> [control completion]
                     (and (= status :running)
                          (< active-count parallelism))
                     (conj submission))
                   [message channel] (async/alts!! channels)]
               (cond
                 (= channel completion)
                 (recur status count (dec active-count))

                 (= channel submission)
                 (do
                   (execute-work!
                    pid compute-executor error report completion active-work
                    message)
                   (recur status (inc count) (inc active-count)))

                 (= channel control)
                 (let [command (::flow/command message)
                       addressed?
                       (contains? #{pid ::flow/all} (::flow/to message))]
                   (cond
                     (not addressed?)
                     (recur status count active-count)

                     (= command ::flow/stop)
                     nil

                     (= command ::flow/pause)
                     (recur :paused count active-count)

                     (= command ::flow/resume)
                     (recur :running count active-count)

                     (= command ::flow/ping)
                     (do
                       (async/>!!
                        (::flow/reply-chan message)
                        (launcher-ping
                         pid status count ins outs parallelism active-work))
                       (recur status count active-count))

                     :else
                     (recur status count active-count)))

                 :else
                 nil)))))))))

(def flow-workload-attributes
  "Flat config-singleton attributes consumed by the work launcher."
  [:seon.config.flow.compute/queue-depth
   :seon.config.flow.compute/concurrency])

(defn- required-launcher-configuration
  [configuration]
  (let [selected
        (select-keys configuration flow-workload-attributes)
        required flow-workload-attributes
        missing (remove #(contains? selected %) required)]
    (when (seq missing)
      (throw
       (ex-info
        "The compute work launcher is not ready: required config facts are missing."
        {:seon.error/kind :configuration
         ::missing-config-facts (vec missing)})))
    selected))

(defn- work-launcher-graph-definition
  [{::keys [parallelism active-work queue-depth compute-executor
            io-executor task-executor]}]
  {:procs
   {::work-launcher
    {:proc
     (work-launcher-proc
      {::parallelism parallelism
       ::active-work active-work
       ::task-executor task-executor})
     :chan-opts
     {::compute-submission {:buf-or-n queue-depth}}}
    ::capacity-observer
    {:proc
     (capacity-observer-proc
      {::parallelism parallelism
       ::active-work active-work})}}
   :conns []
   :compute-exec compute-executor
   :io-exec io-executor})

(defn start-work-launcher!
  "Start the one bounded work launcher from acquired config facts."
  [{::keys [configuration]}]
  (let [configuration (required-launcher-configuration configuration)
        queue-depth
        (:seon.config.flow.compute/queue-depth configuration)
        parallelism
        (:seon.config.flow.compute/concurrency configuration)
        active-work (atom {})
        root-executors
        ((requiring-resolve 'seon.cluster/root-executors))
        task-executor (virtual-task-executor)
        graph
        (flow/create-flow
         (work-launcher-graph-definition
           {::parallelism parallelism
           ::active-work active-work
           ::queue-depth queue-depth
           ::compute-executor (:compute root-executors)
           ::io-executor (:io root-executors)
           ::task-executor task-executor}))
        started (flow/start graph)]
    (flow/resume graph)
    {::graph graph
     ::started started
     ::active-work active-work
     ::compute-executor task-executor
     ::configuration configuration}))

(defn stop-work-launcher!
  "Stop a work launcher and interrupt its owned compute executor."
  [{::keys [graph compute-executor]}]
  (when graph
    (flow/stop graph))
  (when compute-executor
    (.shutdownNow ^ExecutorService compute-executor))
  nil)

(defonce ^:private installed-work-launcher
  (atom nil))

(defn install-work-launcher!
  "Replace the process work launcher with one built from acquired facts."
  [{::keys [configuration]}]
  (let [launcher
        (start-work-launcher! {::configuration configuration})
        [previous _]
        (swap-vals! installed-work-launcher (constantly launcher))]
    (when previous
      (stop-work-launcher! previous))
    launcher))

(defn stop-installed-work-launcher!
  "Stop and forget the process work launcher."
  []
  (when-let [launcher
             (first
              (swap-vals! installed-work-launcher (constantly nil)))]
    (stop-work-launcher! launcher))
  nil)

(defn current-work-launcher
  "Return the installed work launcher or fail the readiness check."
  []
  (or @installed-work-launcher
      (throw
       (ex-info
        "The bounded work launcher is not installed."
        {:seon.error/kind :configuration}))))

(defn submit!!
  "Submit compute work with fixed-buffer backpressure and await its result."
  [{::keys [submission-id workload work-fn time-limit-ms]}]
  (let [{::keys [graph active-work]} (current-work-launcher)
        result (promise)
        status (atom ::queued)
        work-fn (bound-fn* work-fn)
        submitted-at (System/nanoTime)
        injection
        (flow/inject
         graph
         [::work-launcher ::compute-submission]
         [{::submission-id submission-id
           ::workload workload
           ::work-fn work-fn
           ::result result
           ::status status}])]
    (.get ^Future injection)
    (let [settled (deref result time-limit-ms ::time-limit)
          settled-at (System/nanoTime)
          submission-wait-ms
          (quot (- (long (if (= ::time-limit settled)
                           settled-at
                           (::started-at settled)))
                   submitted-at)
                1000000)]
      (if (= ::time-limit settled)
        (do
          (when-not (compare-and-set! status ::queued ::cancelled)
            (swap! active-work
                   (fn [active]
                     (if (contains? active submission-id)
                       (assoc-in active [submission-id ::wedged?] true)
                       active))))
          {::outcome ::time-limit
           ::submission-wait-ms submission-wait-ms})
        (if-let [throwable (::throwable settled)]
          (throw throwable)
          {::outcome ::completed
           ::value (::value settled)
           ::submission-wait-ms submission-wait-ms})))))

(deftype CountedDroppingBuffer
  [^LinkedList buffer ^long capacity drop!]
  async.impl/UnblockingBuffer
  async.impl/Buffer
  (full? [_]
    false)
  (remove! [_]
    (.removeLast buffer))
  (add!* [this value]
    (if (>= (.size buffer) capacity)
      (drop! value)
      (.addFirst buffer value))
    this)
  (close-buf! [_])
  Counted
  (count [_]
    (.size buffer))
  async.impl/Capacity
  (capacity [_]
    capacity)
  core.protocols/Datafiable
  (datafy [_]
    {:type 'CountedDroppingBuffer
     :count (.size buffer)
     :capacity capacity}))

(defn- counted-dropping-buffer
  [capacity drop!]
  (CountedDroppingBuffer. (LinkedList.) (long capacity) drop!))

(defn- fault-committer-step
  ([]
   {:workload :io
    :ping-map-fn #(select-keys % [::committed ::panicked])})
  ([{::keys [fault-channel] :as args}]
   (assoc args
          ::flow/in-ports {::core-fault fault-channel}
          ::committed 0
          ::panicked 0))
  ([{::keys [completion] :as state} transition]
   (when (= ::flow/stop transition)
     ;; Flow observes this transition only after an active transform
     ;; returns, so the marker joins an in-flight durable commit without
     ;; inventing a clock.
     (async/put! completion ::stopped))
   state)
  ([{::keys [read-core-error-mode commit-fault! panic!] :as state}
    _input fault]
   ;; A closed source error channel presents one terminal nil before
   ;; Flow removes that input. It is lifecycle, not a core fault.
   (if (nil? fault)
     [state nil]
     (case (read-core-error-mode)
       :record
       (do
         (commit-fault! fault)
         [(update state ::committed inc) nil])

       :panic
       (do
         (panic! fault)
         [(update state ::panicked inc) nil])

       (throw
        (ex-info
         "Unknown fake :seon.config/on-core-error value."
         {::core-error-mode (read-core-error-mode)}))))))

(defn fault-committer-proc
  "Create the Flow proc that turns core faults into durable facts.

   The supplied mode reader represents the database-backed
   :seon.config/on-core-error decision. Record mode commits the fault; panic
   mode invokes the supplied development panic function."
  {:malli/schema
   [:=> [:catn [::request ::fault-committer-proc-request]] ::launcher]}
  [request]
  (var-process #'fault-committer-step :io request))

(defn- monitor-graph
  [graph report-channel error-channel]
  (reify
    core.protocols/Datafiable
    (datafy [_]
      (-> (datafy/datafy graph)
          (assoc-in [:chans :report] (datafy/datafy report-channel))
          (assoc-in [:chans :error] (datafy/datafy error-channel))))

    flow.graph/Graph
    (start [_] (flow.graph/start graph))
    (stop [_] (flow.graph/stop graph))
    (pause [_] (flow.graph/pause graph))
    (resume [_] (flow.graph/resume graph))
    (ping [_ timeout-ms] (flow.graph/ping graph timeout-ms))
    (pause-proc [_ pid] (flow.graph/pause-proc graph pid))
    (resume-proc [_ pid] (flow.graph/resume-proc graph pid))
    (ping-proc [_ pid timeout-ms]
      (flow.graph/ping-proc graph pid timeout-ms))
    (command-proc [_ pid command more-kvs]
      (flow.graph/command-proc graph pid command more-kvs))
    (inject [_ coordinate messages]
      (flow.graph/inject graph coordinate messages))))

(defn- fault-graph-definition
  [request]
  {:procs
   {::fault-committer
    {:proc (fault-committer-proc request)}}
   :conns []})

(defn start-error-fanout!
  "Own report/error fan-out for one already-started Flow graph.

   Core faults enter a bounded nonblocking tap. Every admitted fault is
   processed by a dedicated Flow proc; each overflow calls commit-drop! with
   the dropped fault. Flow Monitor receives independent sliding-buffer taps
   through the returned datafiable graph, so it never competes for the source
   channels or delays fault commitment."
  {:malli/schema
   [:=> [:catn [::request ::error-fanout-request]] ::error-fanout]}
  [{::keys [graph started fault-buffer-capacity monitor-buffer-capacity
            read-core-error-mode commit-fault! commit-drop! panic!]}]
  (let [report-mult (async/mult (:report-chan started))
        error-mult (async/mult (:error-chan started))
        application-report-channel
        (async/chan (async/sliding-buffer monitor-buffer-capacity))
        monitor-report-channel
        (async/chan (async/sliding-buffer monitor-buffer-capacity))
        monitor-error-channel
        (async/chan (async/sliding-buffer monitor-buffer-capacity))
        fault-channel
        (async/chan
         (counted-dropping-buffer fault-buffer-capacity commit-drop!))
        completion (async/promise-chan)
        fault-graph
        (flow/create-flow
         (fault-graph-definition
          {::fault-channel fault-channel
           ::completion completion
           ::read-core-error-mode read-core-error-mode
           ::commit-fault! commit-fault!
           ::panic! panic!}))
        _ (flow/start fault-graph)
        _ (flow/resume fault-graph)
        monitor-view
        (monitor-graph
         graph monitor-report-channel monitor-error-channel)]
    (async/tap report-mult application-report-channel)
    (async/tap report-mult monitor-report-channel)
    (async/tap error-mult fault-channel)
    (async/tap error-mult monitor-error-channel)
    {::graph monitor-view
     ::source-graph graph
     ::fault-graph fault-graph
     ::report-mult report-mult
     ::error-mult error-mult
     ::application-report-channel application-report-channel
     ::monitor-report-channel monitor-report-channel
     ::monitor-error-channel monitor-error-channel
     ::fault-channel fault-channel
     ::completion completion}))

(defn join-error-fanout!
  "Feed one more started graph's errors into an existing fan-out.
  The N-source generalization (F1 §6): every agent graph's error
  channel joins the cluster's ONE counted-dropping fault channel,
  tagged with structural provenance —
  `(async/pipeline 1 fault-channel (map #(merge % tag)) error-chan
  false)`. `close?` is false so one source graph's stop never closes
  the committer's inbox; the pipeline itself ends when the source's
  error channel closes (its graph stopped), so nothing needs an
  explicit unjoin. Returns the pipeline's result channel."
  {:malli/schema [:=> [:cat ::join-error-request] ::channel]}
  [{::keys [started fault-channel tag]}]
  (async/pipeline 1
                  fault-channel
                  (map #(merge % tag))
                  (:error-chan started)
                  false))

(defn stop-error-fanout!
  "Detach and stop one error fan-out without stopping its source graph."
  {:malli/schema
   [:=> [:catn [::fanout ::error-fanout]] :boolean]}
  [{::keys [fault-graph report-mult error-mult completion
            application-report-channel monitor-report-channel
            monitor-error-channel fault-channel]}]
  (let [stopped? (boolean (flow/stop fault-graph))]
    ;; Join the proc's lifecycle event before its caller can release the
    ;; database connection used by an active fault commit.
    (async/<!! completion)
    (async/untap report-mult application-report-channel)
    (async/untap report-mult monitor-report-channel)
    (async/untap error-mult monitor-error-channel)
    (async/untap error-mult fault-channel)
    (doseq [channel [application-report-channel monitor-report-channel
                     monitor-error-channel]]
      (async/close! channel))
    stopped?))

(defn seeded-outcome
  "Return a deterministic fake-owner outcome for an explicit seed."
  {:malli/schema
   [:=> [:catn [::request ::seeded-outcome-request]] ::fix-outcome]}
  [{::keys [seed owner-ordinal attempt]}]
  (let [mixed-seed
        (bit-xor
         (long seed)
         (unchecked-multiply
          6364136223846793005
          (long (inc owner-ordinal)))
         (unchecked-multiply
          1442695040888963407
          (long (inc attempt))))
        random (java.util.SplittableRandom. mixed-seed)]
    (nth [::fix-succeeds ::fix-fails ::fix-breaks-other-namespace
          ::no-response]
         (.nextInt random 4))))

(defn lineage-status
  "Derive the prototype lineage status from current facts."
  {:malli/schema
   [:=> [:catn [::request ::lineage-status-request]] ::lineage-status]}
  [{::keys [owner-count successful-owners escalated? admitted?]}]
  (cond
    escalated? ::escalated
    (and admitted? (= owner-count (count successful-owners))) ::done
    :else ::iterating))

(defn escalate-lineage?
  "Whether an open prototype lineage has exhausted either fact budget."
  {:malli/schema
   [:=> [:catn [::request ::escalation-request]] :boolean]}
  [{::keys [turn-count failure-count max-turns max-failures]}]
  (or (>= turn-count max-turns)
      (>= failure-count max-failures)))

(defn- planner-step
  ([]
   {:ins {::planner-wake "A fact-derived planner wake value."}
    :workload :io
    :ping-map-fn #(select-keys % [::attempts])})
  ([args]
   (assoc args ::attempts 0))
  ([state _transition]
   state)
  ([{::keys [plan-step-fn] :as state} _input message]
   (let [result (plan-step-fn message)]
     [(update state ::attempts inc)
      {::flow/report
       [{::event ::planner-attempt
         ::result result}]}])))

(defn planner-proc
  "Create a fake planner proc whose supplied step returns ordinary data."
  {:malli/schema
   [:=> [:catn [::request ::planner-proc-request]] ::launcher]}
  [request]
  (var-process #'planner-step :io request))

(defn- namespace-owner-step
  ([]
   {:ins {::owner-step
          "An initial fact-derived wake or open-run continuation."}
    :workload :io
    :ping-map-fn #(select-keys % [::attempts])})
  ([args]
   (assoc args ::attempts 0))
  ([state _transition]
   state)
  ([{::keys [fix-step-fn] :as state} _input message]
   (let [outcome (fix-step-fn message)]
     [(update state ::attempts inc)
      {::flow/report
       [{::event ::owner-attempt
         ::outcome outcome}]}])))

(defn namespace-owner-proc
  "Create a fake namespace-owner proc returning one scripted outcome."
  {:malli/schema
   [:=> [:catn [::request ::namespace-owner-proc-request]] ::launcher]}
  [request]
  (var-process #'namespace-owner-step :io request))

(defn- source-enumerator-step
  ([]
   {:ins {::source-event
          "A full-drain request or one changed namespace source."}
    :outs {::index-request
           "One namespace source submitted to the indexer."}
    :workload :io
    :ping-map-fn #(select-keys % [::emitted ::sources])})
  ([{::keys [read-sources]}]
   {::sources (read-sources)
    ::emitted 0})
  ([state _transition]
   state)
  ([state _input message]
   (let [changed-namespace (::changed-namespace message)
         sources
         (cond-> (::sources state)
           changed-namespace
           (assoc changed-namespace (::changed-source message)))
         selected
         (if changed-namespace
           [changed-namespace]
           (sort (keys sources)))
         requests
         (mapv
          (fn [namespace]
            {::changed-namespace namespace
             ::changed-source (get sources namespace)})
          selected)]
     [(-> state
          (assoc ::sources sources)
          (update ::emitted + (count requests)))
      {::index-request requests
       ::flow/report
       [{::event ::source-enumerated
         ::namespaces (set selected)}]}])))

(defn source-enumerator-proc
  "Create an I/O proc that emits fixture namespaces and changed events."
  {:malli/schema
   [:=> [:catn [::request ::source-enumerator-proc-request]] ::launcher]}
  [request]
  (var-process #'source-enumerator-step :io request))

(defn- indexer-step
  ([]
   {:ins {::index-request "One namespace source to compile."}
    :outs {::tx-page "Transaction data for the database committer."}
    :workload :compute
    :ping-map-fn #(select-keys % [::compiled])})
  ([args]
   (assoc args ::compiled 0))
  ([state _transition]
   state)
  ([{::keys [compile-namespace-fn] :as state} _input request]
   (let [page (compile-namespace-fn request)]
     [(update state ::compiled inc)
      {::tx-page [page]
       ::flow/report
       [{::event ::namespace-indexed
         ::namespace (::changed-namespace request)}]}])))

(defn indexer-proc
  "Create a compute proc that compiles one namespace into transaction data."
  {:malli/schema
   [:=> [:catn [::request ::indexer-proc-request]] ::launcher]}
  [{::keys [compute-timeout-ms] :as request}]
  (var-process
   #'indexer-step :compute request
   {:compute-timeout-ms compute-timeout-ms}))

(defn- eval-step
  ([]
   {:ins {::submission "A process-local evaluation submission."}
    :outs {::result "The disposable result delivered downstream."}
    :workload :compute
    :ping-map-fn
    (fn [{::keys [pid active-evals] :as state}]
      (assoc (select-keys state [::pid ::completed])
             ::active? (contains? @active-evals pid)))})
  ([{pid ::flow/pid :as args}]
   (assoc args
          ::pid pid
          ::completed 0))
  ([state transition]
   (assoc state ::transition transition))
  ([{::keys [pid active-evals] :as state} _input
    {::keys [work-fn wedged? interrupt-fn]}]
   (let [armed? (atom true)
         deadline-interrupt-fn (or interrupt-fn (constantly nil))
         armed-interrupt-fn
         (fn []
           (deadline-interrupt-fn)
           (when-not @armed?
             (throw
              (ex-info
               "The fake interrupt function was called after disarm."
               {::pid pid}))))]
     (swap! active-evals
            assoc pid
            {::workload :compute
             ::interrupt-fn armed-interrupt-fn
             ::platform-thread? (not (.isVirtual (Thread/currentThread)))
             ::wedged? (true? wedged?)})
     (try
       (armed-interrupt-fn)
       (let [result
             (try
               (work-fn {::interrupt-fn armed-interrupt-fn})
               (catch clojure.lang.ExceptionInfo throwable
                 (if (::interrupted? (ex-data throwable))
                   {:seon.error/message "Synthetic interrupt fired."
                    :seon.error/kind :timeout
                    :seon.error/data {::pid pid}}
                   (throw throwable))))
             next-state (update state ::completed inc)]
         [next-state
          {::flow/report
           [{::pid pid
             ::event ::eval-complete
             ::result result}]
           ::result [result]}])
       (finally
         (reset! armed? false)
         (swap! active-evals dissoc pid))))))

(defn eval-proc
  "Create a compute proc that simulates one guarded evaluation."
  {:malli/schema
   [:=> [:catn [::request ::eval-proc-request]] ::launcher]}
  [{::keys [compute-timeout-ms] :as request}]
  (var-process
   #'eval-step :compute request
   {:compute-timeout-ms compute-timeout-ms}))

(defn- mailbox-step
  ([]
   {:ins {::mailbox "A presentation (sliding-buffer 1) tap."}
    :workload :io
    :ping-map-fn #(select-keys % [::delivered])})
  ([args]
   (assoc args ::delivered 0))
  ([state _transition]
   state)
  ([{::keys [deliver!] :as state} _input message]
   (deliver! message)
   [(update state ::delivered inc) nil]))

(defn mailbox-proc
  "Create a proc that delivers each message to a supplied sink."
  {:malli/schema
   [:=> [:catn [::request ::mailbox-request]] ::launcher]}
  [request]
  (var-process #'mailbox-step :io request))
