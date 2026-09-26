(ns seon.flow
  "Production-shaped core.async.flow launchers used by the standing testbed.

   This namespace deliberately does not own durable runtime state. Ordinary
   Flow processes retain only disposable counters and handles. Flow channels
   carry scheduling and wake signals."
  (:require [seon.error.refusal]
            [clojure.core.protocols :as core.protocols]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.core.async.flow.impl.graph :as flow.graph]
            [clojure.core.async.flow.spi :as flow.spi]
            [clojure.core.async.impl.channels :as async.channels]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.datafy :as datafy]
            [clojure.test.check.generators :as gen]
            [seon.await :as await]
            [seon.cluster.process :as process]
            [seon.env :as env]
            [seon.error :as error]
            [seon.fault :as fault]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.sci.kernel :as kernel]
            [seon.schema.edn :as schema.edn])
  (:import [clojure.lang Counted IBlockingDeref]
           [java.util LinkedList]
           [java.util.concurrent Executor Executors ExecutorService Future FutureTask
            ThreadFactory TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

;;; OPTIONAL LATE DEPENDENCY. `seon.operator.runtime` lives in
;;; `resources/seon/operator/runtime.clj`, off the source path, and is loaded
;;; by the operator, not by this namespace. Resolved once at first use rather
;;; than on every graph start (AGENTS §2.1).
(defonce ^:private operator-runtime-root-executors
  (delay (requiring-resolve 'seon.operator.runtime/root-executors)))

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

(defn- step-var?
  [value]
  (var? value))

(defn- write-port?
  [value]
  (satisfies? async.impl/WritePort value))

(schema/register-core-predicate! 'seon.flow/executor? executor?)
(schema/register-core-predicate! 'seon.flow/atom-reference? atom-reference?)
(schema/register-core-predicate! 'seon.flow/java-future? java-future?)
(schema/register-core-predicate! 'seon.flow/proc-launcher? proc-launcher?)
(schema/register-core-predicate! 'seon.flow/graph? graph?)
(schema/register-core-predicate! 'seon.flow/channel? channel?)
(schema/register-core-predicate! 'seon.flow/step-var? step-var?)
(schema/register-core-predicate! 'seon.flow/write-port? write-port?)

(defn- graph-executor
  "One graph's own proc-loop executor: a virtual thread per task, running in
  the projection world the graph was handed. It is the join: after
  `.shutdown`, `awaitTermination` is true only when every proc loop,
  stop transition included, has returned
  (`core.async/.../flow/impl.clj:29-36,262,323`)."
  {:malli/schema [:=> [:cat [:map [::projection-state {:optional true} :seon.sci.eval/projection-state]
                             [::projection {:optional true} :seon.schema/projection]]]
                  ::executor]}
  [{::keys [projection-state projection]}]
  (Executors/newThreadPerTaskExecutor
   (reify ThreadFactory
     (newThread [_ runnable]
       (.unstarted (Thread/ofVirtual)
                   (cond
                     projection-state
                     #(schema/call-with-projection-state projection-state (fn [] (.run runnable)))
                     projection
                     #(schema/call-with-projection projection (fn [] (.run runnable)))
                     :else runnable))))))

(defn start-graph!
  "Create one Flow graph on its own executor, join declared fan-outs, resume."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [::graph-definition :map]
      [::projection-state {:optional true} :seon.sci.eval/projection-state]
      [::projection {:optional true} :seon.schema/projection]
      [::joins {:optional true}
       [:map-of :qualified-keyword :seon.flow/join!]]]]
    [:map
     [::graph ::graph]
     [::started ::started]
     [::executor ::executor]
     [::joins :map]]]}
  [{::keys [graph-definition joins] :as request}]
  (let [executor (graph-executor request)
        ;; every proc's args name its own graph, so a panicking `var-process`
        ;; stops it through Flow; delivered before any proc starts
        graph-ref (async/promise-chan)
        graph (flow/create-flow
               (-> graph-definition
                   (update :procs update-vals #(assoc-in % [:args ::graph-ref] graph-ref))
                   (assoc :io-exec executor)))
        _ (async/put! graph-ref graph)
        started (flow/start graph)
        construction {::graph graph
                      ::started started
                      ::executor executor}
        joined (reduce-kv (fn [results join-key join!]
                            (assoc results join-key (join! construction)))
                          {}
                          (or joins {}))]
    (flow/resume graph)
    (assoc construction ::joins joined)))

(defn join-graph!
  "Join a stopped graph: every proc loop has returned before the deadline.

  The caller has already sent `flow/stop`, which is a command, not a join
  (`core.async/.../flow/impl.clj:174-183`). `.shutdown` admits no new loop;
  the executor's termination is the exit fact. Returns nil once every loop
  exited, or the typed timeout naming the graph that did not exit; the
  executor keeps its threads, so a restart reads `.isTerminated` and refuses.
  A graph started before its executor existed (a JVM that adopted this code
  in place) has no join: its loops run on the shared `:io` pool, so stop stays
  a command for it until the next start."
  {:malli/schema [:=> [:cat [:map [::executor {:optional true} ::executor]
                             [:seon.await/bound :seon.await/bound]
                             [:seon.await/diagnostic :seon.await/diagnostic]]]
                  [:or :nil :seon.await/timeout-error]]}
  [{::keys [executor] :as request}]
  (when-let [^ExecutorService executor executor]
    (.shutdown executor)
    (let [result (await/await!
                  (assoc (select-keys request [:seon.await/bound :seon.await/diagnostic])
                         :seon.await/blocking-deref
                         (reify IBlockingDeref
                           (deref [_ ms timeout-value]
                             (if (.awaitTermination executor ms TimeUnit/MILLISECONDS)
                               ::exited
                               timeout-value)))))]
      (when-not (= ::exited result) result))))

;;; Every generation makes a FRESH sample. One shared delayed object
;;; satisfied its predicate once but could never explore lifecycle or
;;; freshness, and a consumer that mutated or closed it changed every
;;; later sample. None of these constructors starts a thread, binds a
;;; port, or leaves a graph running: an unsubmitted executor has no worker,
;;; an uncalled FutureTask never runs, and the empty graph is stopped before
;;; the sample is returned.
(def executor-generator
  (gen/fmap (fn [_] (Executors/newSingleThreadExecutor)) (gen/return nil)))
(def atom-reference-generator
  (gen/fmap (fn [_] (atom {})) (gen/return nil)))
(def java-future-generator
  (gen/fmap (fn [_] (java.util.concurrent.FutureTask. (fn [] nil)))
            (gen/return nil)))
(def proc-launcher-generator
  (gen/fmap (fn [_] (reify flow.spi/ProcLauncher
                      (describe [_] {:params {} :ins {} :outs {}})
                      (start [_ _] nil)))
            (gen/return nil)))
(def graph-generator
  (gen/fmap (fn [_]
              (let [{::keys [graph]}
                    (start-graph! {::graph-definition
                                   {:procs {} :conns []}})]
                (flow/stop graph)
                graph))
            (gen/return nil)))
(def channel-generator
  (gen/fmap (fn [_] (async/chan)) (gen/return nil)))
;;; A Var is a first-class handle, not a lifecycle resource, so varying
;;; over real Vars is the honest domain.
(def step-var-generator
  (gen/elements [#'executor? #'java-future? #'channel?]))

(schema.edn/load! {})

(defn- proc-failed!
  "Record one proc step's throw as one core fault on the proc's own thread.
  Every arity Flow would catch goes here, so a fault reaches the writer
  synchronously and no buffer can drop it (Flow's own error channel is a
  sliding buffer, `core.async/.../flow/impl.clj:101-102,312-316`). The writer
  decides occurrence and wake (`seon.fault/fault!`); an agent graph's
  environment names its agent, which attributes the fault to that agent's run.
  A panic (`:panic`, or the fault not stored) is printed on stderr, its causes
  through `seon.render.value/floor` when unstored (bounded text; the whole
  values stay on the throwables), and answers true so the caller stops the
  graph; the step still returns to Flow, because a stop transition must exit
  for the graph's join to complete (`impl.clj:181,317-320`). Answers false
  when the fault was recorded and the graph continues. A supplied `stop!` is
  the owner's stop request (`:seon.fault/stop-request!`): `fault!` calls it
  BEFORE recording under `:panic`, so the stop never waits on a stalled or
  failing write, nor on the printing below."
  {:malli/schema [:function
                  [:=> [:cat ::step-var :map :seon.error/throwable] :boolean]
                  [:=> [:cat ::step-var :map :seon.error/throwable [:maybe :seon.fault/stop-request!]] :boolean]]}
  ([step-var args failure]
   (proc-failed! step-var args failure nil))
  ([step-var args failure stop!]
  (try
    (fault/fault! (env/of args) failure
                  (cond-> (merge {:seon.error/layer ::proc
                                  :seon.error/operation (symbol step-var)
                                  :seon.db.process/id (process/id (process/current-identity))}
                                 (select-keys (env/of args) [:seon.agent/id]))
                    stop! (assoc :seon.fault/stop-request! stop!)))
    false
    (catch Throwable panic
      (let [receipt (:seon.fault/recorded (ex-data panic))]
        (binding [*out* *err*]
          ;; a stored fault's whole cause is its evidence; print it only
          ;; when no store holds it
          (prn {:seon.error/message (ex-message panic)
                :seon.error/operation (symbol step-var)
                :seon.fault/recorded receipt})
          (when-not (:seon.fault/committed? receipt)
            (println (render.value/floor panic))
            (println (render.value/floor failure)))
          (flush))
        true)))))

(defn- request-graph-stop!
  "Request the whole graph's stop on a virtual thread, never the panicked proc's.

  `flow/stop` takes Flow's lock and puts its command with a blocking `>!!`
  onto a control channel of 10, fanned out by a mult to per-proc taps of 10
  (`impl.clj:71-75,99,150-165,174-183`). A proc that stops ITSELF through its
  own tap never reads that tap again, so about twenty later commands (pings)
  wedge the mult and every command after them, `flow/stop` included, blocks
  forever; and `flow/stop` on the panicked proc's own thread, while its tap is
  full, deadlocks on itself. So the panicked proc keeps reading control (its
  inputs closed by `::flow/input-filter`) and the graph-wide stop is sent from
  another thread: every proc, the panicked one included, receives the one
  stop through the mult. A graph not started by `start-graph!` names no graph;
  only its panicked proc stops, through its own tap. The graph reference is the
  promise channel `start-graph!` fills before any proc starts, polled."
  {:malli/schema [:=> [:cat ::channel [:maybe ::channel]] :boolean]}
  [control graph-ref]
  (if-let [graph (some-> graph-ref async/poll!)]
    (do (Thread/startVirtualThread ^Runnable (fn [] (flow/stop graph))) true)
    (async/put! control #::flow{:command ::flow/stop :to ::flow/all})))

(defn- fault-port
  "The `::flow/error` output a proc's loop reports to (`impl.clj:312-319`),
  disposed on the reporting thread: `>!!` commits the synchronous handler under
  its lock and `dispose!` runs before the completed box returns
  (`impl/protocols.clj:18-29`, `async.clj:200-211`). It replaces Flow's sliding
  graph error channel (`impl.clj:102`), which drops faults and which `flow/stop`
  closes before an unwinding proc reports (`impl.clj:179`). Only Flow's
  synchronous `>!!` is served; an inactive handler is refused with nil."
  {:malli/schema [:=> [:cat :seon.instrument/callable] ::write-port]}
  [dispose!]
  (reify async.impl/WritePort
    (put! [_ report handler]
      (.lock ^java.util.concurrent.locks.Lock handler)
      (let [committed (try (when (async.impl/active? handler) (async.impl/commit handler))
                           (finally (.unlock ^java.util.concurrent.locks.Lock handler)))]
        (when committed
          (dispose! report)
          (async.channels/box true))))))

(defn var-process
  "Build one Flow proc launcher from a step VAR and a pinned workload.
  THE construction seam for every proc in the system (F0(a), the F1
  blueprint): it REFUSES a non-var step — an anonymous step captures
  its closures and hot reload silently stops applying to running
  graphs — and REFUSES a missing or `:mixed` workload, because the
  `:mixed` default pins one platform thread per proc forever and is
  the one measured scaling cliff (flow-mechanics 2026-07-28 §1). Both
  refusals are construction-time throws, never review items. It also
  REFUSES `args` that name no `:seon.env/environment`: flow conveys no
  bindings anywhere by design, so a proc that cannot name its cluster
  runs with whatever thread-local state its executor happens to carry —
  invisible on `:compute` and fatal on `:io`, the exact audited
  signature. flow's own `:params` assertion cannot make that refusal
  (`start-proc` assoc's `::flow/pid` into args, so args is always
  truthy — falsified live 2026-08-07), so this seam makes it. `args`
  merge into the start options' `:args` so `create-flow` definitions
  stay pure data."
  {:malli/schema
   [:function
    [:=> [:cat ::step-var [:enum :io :compute] :map] ::launcher]
    [:=> [:cat ::step-var [:enum :io :compute] :map :map] ::launcher]]}
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
   (env/refuse-absent-environment! args ::var-process)
   (let [options (assoc options :workload workload)
         ;; every arity dispatches through the Var (hot reload); a throwing
         ;; transition or transform is one fault through `fault!`. A recorded
         ;; fault keeps the proc's state; a panic stops the graph, except
         ;; while the proc already acknowledges a stop
         ;; a panicked proc reads no further input until the graph's stop
         ;; reaches it: Flow reads control regardless of the filter
         closed (fn [state] (assoc state ::flow/input-filter (constantly false)))
         step (fn [stop!]
                (let [failed! (fn [failure]
                                (let [panicked? (proc-failed! step-var args failure stop!)]
                                  (when panicked? (stop!))
                                  panicked?))]
                  (fn
                    ([] (step-var))
                    ([init-args] (step-var (dissoc init-args ::graph-ref)))
                    ([state transition]
                     (try (step-var state transition)
                          (catch Throwable failure
                            (if (and (failed! failure) (not= ::flow/stop transition))
                              (closed state)
                              state))))
                    ([state input message]
                     (try (step-var state input message)
                          (catch Throwable failure
                            [(cond-> state (failed! failure) closed) nil]))))))
         launcher (flow/process (step (fn [])) options)]
     (reify
       core.protocols/Datafiable
       (datafy [_]
         (assoc (datafy/datafy launcher) :step (datafy/datafy step-var)))

       flow.spi/ProcLauncher
       (describe [_]
         (flow.spi/describe launcher))
       (start [_ start-options]
         (let [requested (atom false)
               stop! (fn []
                       (when (compare-and-set! requested false true)
                         (request-graph-stop! (get-in start-options [:ins ::flow/control])
                                              (get-in start-options [:args ::graph-ref]))))
               ;; a loop-level failure Flow reports outside the step
               ;; (`send-outputs`, the outer loop) is one fault by reference:
               ;; the proc's state is named by its pid, never copied
               dispose! (fn [report]
                          (try
                            (let [failure (ex-info "A Flow proc loop failed outside its step."
                                                   (select-keys report [::flow/pid ::flow/status ::flow/cid
                                                                        ::flow/op ::flow/count])
                                                   (::flow/ex report))]
                              (when (proc-failed! step-var args failure stop!) (stop!)))
                            (catch Throwable failure
                              (stop!)
                              (try (binding [*out* *err*]
                                     (prn {:seon.error/message "SEON CORE FAULT (unrecorded Flow report)"
                                           ::flow/pid (::flow/pid report)
                                           :seon.error/throwable-class (.getName (class failure))}))
                                   (catch Throwable _ nil)))))]
           (flow.spi/start
            (flow/process (step stop!) options)
            (-> start-options
                (update :args #(merge args %))
                (assoc-in [:outs ::flow/error] (fault-port dispose!))))))))))

(defn bounded-platform-executor
  "Create a bounded executor whose workers are platform threads."
  {:malli/schema [:=> [:catn [::parallelism ::parallelism]] ::executor]}
  [parallelism]
  (Executors/newFixedThreadPool (int parallelism)))

(defn- carried-arm
  [work]
  (:seon.sci.kernel/arm (env/of work)))

(defn- submission-capacity-error
  {:malli/schema [:=> [:cat ::submission-id ::workload]
                  ::submission-capacity-error]}
  [submission-id workload]
  (seon.error.refusal/diagnostic (java.util.Date.) ::submission 'seon.flow/submission-capacity-error
   {:seon.flow/submission-capacity workload
    ::submission-id submission-id
    :seon.error/message "The bounded work submission queue is full."
    :seon.error/expected "available submission capacity"
    :seon.error/data {::submission-id submission-id ::workload workload}}))

(defn- acquire-admission!
  [^AtomicLong admitted capacity]
  (loop [current (.get admitted)]
    (cond
      (>= current capacity)
      false

      (.compareAndSet admitted current (inc current))
      true

      :else
      (recur (.get admitted)))))

(deftype RefusingBuffer
  [^LinkedList buffer ^long capacity ^AtomicLong admitted refuse!]
  async.impl/UnblockingBuffer
  async.impl/Buffer
  (full? [_]
    false)
  (remove! [_]
    (.removeLast buffer))
  (add!* [this value]
    (when (= ::queued @(::status value))
      (if (acquire-admission! admitted capacity)
        (.addFirst buffer value)
        (refuse! value)))
    this)
  (close-buf! [_])
  Counted
  (count [_]
    (.size buffer))
  async.impl/Capacity
  (capacity [_]
    (inc capacity))
  core.protocols/Datafiable
  (datafy [_]
    {:type 'RefusingBuffer
     :count (.size buffer)
     :admitted (.get admitted)
     :capacity capacity}))

(defn- refusing-buffer
  [capacity refuse!]
  (RefusingBuffer.
   (LinkedList.)
   (long capacity)
   (AtomicLong.)
   refuse!))

(defn- release-admission!
  [^RefusingBuffer buffer]
  (.decrementAndGet ^AtomicLong (.-admitted buffer)))

;;; Settle one io submission exactly once. UNTRACKED settlement — a
;;; refusal that never entered `active-work` and has no completion
;;; channel — takes the three-argument arity, so there is no tracking
;;; argument to supply wrongly and no mutable no-op to allocate.
(defn- io-terminal!
  ([submissions work terminal]
   (io-terminal! nil nil submissions work terminal))
  ([completion active-work submissions
    {::keys [submission-id complete! status active?] :as work}
    terminal]
   (when (or (compare-and-set! status ::queued ::completed)
             (compare-and-set! status ::running ::completed))
     (try
       ;; The terminal callback runs on whichever thread settled the work —
       ;; the io task, the launcher proc, or a stopping caller — so it too
       ;; receives its submission's environment as data, under its arm.
       (kernel/adopt-arm
        (carried-arm work)
        #(complete! (env/carry terminal (env/of work))))
       (finally
         (some-> active-work (swap! dissoc submission-id))
         (swap! submissions dissoc submission-id)
         (when @active?
           (async/offer!
            completion
            (assoc terminal
                   ::submission-id submission-id
                   ::workload :io
                   ::work
                   (dissoc work ::work-fn ::complete! ::status
                           ::active? ::task)))))))))

(defn- refuse-io-submission!
  [submissions work]
  (io-terminal!
   submissions
   work
   {::started-at (System/nanoTime)
    ::value (submission-capacity-error (::submission-id work) :io)}))

(defn- execute-io-work!
  [task-executor completion active-work submissions
   {::keys [submission-id work-fn status active? task] :as work}]
  (when (compare-and-set! status ::queued ::running)
    (reset! active? true)
    (swap! active-work
           assoc submission-id
           {::workload :io
            ::wedged? false
            ::platform-thread? false})
    (let [runnable
          (fn []
            (swap! active-work
                   update submission-id
                   assoc
                   ::platform-thread?
                   (not (.isVirtual (Thread/currentThread))))
            (let [started-at (System/nanoTime)
                  terminal
                  (try
                    {::started-at started-at
                     ::value
                     (kernel/adopt-arm
                      (carried-arm work)
                      #(work-fn (env/carry {::started! (fn [])}
                                           (env/of work))))}
                    (catch Throwable throwable
                      {::started-at started-at
                       ::throwable throwable}))]
              (io-terminal!
               completion active-work submissions work terminal)))
          future-task (FutureTask. ^Runnable runnable nil)]
      (reset! task future-task)
      (try
        (.execute ^Executor task-executor future-task)
        (catch Throwable throwable
          (io-terminal!
           completion active-work submissions work
           {::started-at (System/nanoTime)
            ::throwable throwable}))))
    true))

(defn- with-submission-filter
  [{::keys [io-parallelism io-active-count] :as state}]
  (assoc
   state
   ::flow/input-filter
   (fn [input-id]
     (case input-id
       ::io-submission (< io-active-count io-parallelism)
       true))))

(defn- work-launcher-step
  ([]
   {:ins {::io-submission
          "One nonblocking IO submission backed by a durable receipt."}
    :workload :io})
  ([{::keys [io-parallelism] :as args}]
   (with-submission-filter
     (assoc args
            ::io-active-count 0
            ::flow/in-ports
            {::completion (async/chan io-parallelism)})))
  ([state _transition]
   state)
  ([{::keys [active-work task-executor] :as state} input-id message]
   (case input-id
     ::io-submission
     (if (execute-io-work!
          task-executor
          (get-in state [::flow/in-ports ::completion])
          active-work
          (::io-submissions state)
          message)
       [(with-submission-filter
          (update state ::io-active-count inc))
        nil]
       [state nil])

     ::completion
     (do
       (release-admission! (::io-admission-buffer state))
       [(with-submission-filter
          (update state ::io-active-count dec))
        (if-let [throwable (::throwable message)]
          (proc-failed! #'work-launcher-step state throwable)
          {::flow/report
           [{::pid (::flow/pid state)
             ::event ::work-complete
             ::submission-id (::submission-id message)
             ::value (::value message)}]})]))))

(defn- work-launcher-proc
  [request]
  (var-process #'work-launcher-step :io request))

(def flow-workload-attributes
  "Flat config-singleton attributes consumed by the work launcher."
  [:seon.config.flow.io/queue-depth
   :seon.config.flow.io/concurrency
   :seon.config.agent/turn-completion-backstop-ms])

(defn- required-launcher-configuration
  [configuration]
  (let [selected
        (select-keys configuration flow-workload-attributes)
        required flow-workload-attributes
        missing (remove #(contains? selected %) required)]
    (when (seq missing)
      (throw
       (ex-info
        "The work launcher is not ready: required config facts are missing."
        (seon.error.refusal/diagnostic (java.util.Date.) ::configuration 'seon.flow/required-launcher-configuration
         {:seon.error/offending configuration
          :seon.error/message "The work launcher is not ready: required config facts are missing."
          ::missing-config-facts (vec missing)
          :seon.error/expected flow-workload-attributes}))))
    selected))

(defn- work-launcher-graph-definition
  [{::keys [active-work task-executor io-parallelism io-queue-depth io-submissions]
    :as request}]
  (let [io-admission-buffer
        (refusing-buffer
         (+ io-parallelism io-queue-depth)
         (partial refuse-io-submission! io-submissions))]
    {:procs
     {::work-launcher
      {:proc
       (work-launcher-proc
        (env/carry
         {::active-work active-work
          ::task-executor task-executor
          ::io-parallelism io-parallelism
          ::io-submissions io-submissions
          ::io-admission-buffer io-admission-buffer}
         (env/of request)))
       :chan-opts
       {::io-submission {:buf-or-n io-admission-buffer}}}}
     :conns []}))

(defn start-work-launcher!
  "Start one cluster-owned bounded work launcher from acquired config facts."
  {:malli/schema
   [:=> [:cat :seon.flow/work-launcher-request]
    :seon.flow/work-launcher]}
  [{::keys [configuration] :as request}]
  (let [environment (env/refuse-absent-environment!
                     request ::start-work-launcher!)
        configuration (required-launcher-configuration configuration)
        io-queue-depth
        (:seon.config.flow.io/queue-depth configuration)
        io-parallelism
        (:seon.config.flow.io/concurrency configuration)
        active-work (atom {})
        io-submissions (atom {})
        accepting? (atom true)
        drained (promise)
        _
        (add-watch
         io-submissions
         ::drained
         (fn [_ _ _ next-submissions]
           (when (and (not @accepting?) (empty? next-submissions))
             (deliver drained ::drained))))
        {::keys [graph started executor]}
        (start-graph!
         {::graph-definition
          (work-launcher-graph-definition
           (env/carry
            {::active-work active-work
             ::io-queue-depth io-queue-depth
             ::io-parallelism io-parallelism
             ::io-submissions io-submissions
             ::task-executor (:io (@operator-runtime-root-executors))}
            environment))})]
    {::graph graph
     ::started started
     ::active-work active-work
     ::io-submissions io-submissions
     ::accepting? accepting?
     ::drained drained
     ::executor executor
     ::configuration configuration}))

(defn stop-work-launcher!
  "Close IO admission, settle accepted work, then stop the Flow graph."
  {:malli/schema
   [:=> [:cat :seon.flow/work-launcher]
    [:or :nil :seon.await/timeout-error]]}
  [{::keys [graph executor accepting? io-submissions drained active-work
            configuration]}]
  (when graph
    (reset! accepting? false)
    (doseq [[_ {::keys [task] :as work}] @io-submissions]
      (when-let [^Future running @task]
        (.cancel running true))
      ;; The graph is stopping, so no proc counter needs a completion input.
      (reset! (::active? work) false)
      (io-terminal!
       nil active-work io-submissions work
       {::started-at (System/nanoTime)
        ::value
        {:seon.error/at (java.util.Date.)
          :seon.error/layer ::submission
          :seon.error/operation 'seon.flow/stop-work-launcher!
          :seon.flow/launcher-stopped ::work-launcher
          ::submission-id (::submission-id work)
          :seon.error/message "The work launcher stopped before the background call completed."
          :seon.error/expected "a running launcher accepting work"
          :seon.error/data {::launcher-stopped ::work-launcher}}}))
    (when (empty? @io-submissions)
      (deliver drained ::drained))
    (let [backstop-ms (:seon.config.agent/turn-completion-backstop-ms configuration)
          bound
          {:seon.await/config-attribute
           :seon.config.agent/turn-completion-backstop-ms
           :seon.await/config-value backstop-ms
           :seon.await/deadline-nanos (+ (System/nanoTime) (* 1000000 backstop-ms))}
          observation
          (fn [member]
            {:seon.error/layer :flow
             :seon.error/operation ::stop-work-launcher
             :seon.error/expected ::completion
             :seon.error/offending ::pending
             :seon.await/subject {:seon.flow/active-work-count (count @active-work)
              :seon.flow/io-submission-count (count @io-submissions)}
             :seon.error/member member})
          drained-result
          (await/await!
           {:seon.await/bound bound
            :seon.await/diagnostic (observation ::launcher-drained)
            :seon.await/blocking-deref drained})
          _ (flow/stop graph)
          exited-result
          (join-graph!
           (cond-> {:seon.await/bound bound
                    :seon.await/diagnostic (observation ::work-launcher-exited)}
             executor (assoc ::executor executor)))]
      (if (:seon.await/elapsed-ms drained-result)
        drained-result
        exited-result))))

(defn submit!
  "Submit bounded IO work without waiting for its terminal callback.

  The submission must name its cluster's `:seon.env/environment`, which
  is merged into the maps the work-fn and `complete!` receive. This is
  the crossing the isolation audit found empty: io work runs on a
  virtual thread with both dynamic carriers at their root nil, so an
  environment that is not data does not arrive.

  DETACHED BY CONSTRUCTION: nothing here captures the submitting thread —
  not its bindings and not its interrupt arm. The work observes exactly
  what its submission names, which is why `my.background` work can outlive
  the turn that started it instead of dying at that turn's deadline. A
  submitter that wants a limit to govern puts an arm in the submission's
  environment itself."
  {:malli/schema
   [:=> [:cat :seon.flow/work-launcher :seon.flow/io-submission]
    :boolean]}
  [work-launcher submission]
  (env/refuse-absent-environment! submission ::submit!)
  (let [{::keys [submission-id complete!]} submission
        {::keys [graph accepting? io-submissions]} work-launcher
        work
        (assoc submission
               ::status (atom ::queued)
               ::active? (atom false)
               ::task (atom nil))
        completion complete!]
    (if (and graph @accepting?)
      (do
        (swap! io-submissions assoc submission-id work)
        (if @accepting?
          (do
            (flow/inject graph [::work-launcher ::io-submission] [work])
            true)
          (do
            (io-terminal!
             io-submissions work
             {::started-at (System/nanoTime)
              ::value
              {:seon.error/at (java.util.Date.)
          :seon.error/layer ::submission
          :seon.error/operation 'seon.flow/submit!
          :seon.flow/launcher-stopped ::work-launcher
          ::submission-id submission-id
          :seon.error/message "The work launcher is no longer accepting background calls."
          :seon.error/expected "a running launcher accepting work"
          :seon.error/data {::launcher-stopped ::work-launcher}}})
            false)))
      (do
        (completion
         (env/carry
          {::started-at (System/nanoTime)
           ::value
           {:seon.error/at (java.util.Date.)
          :seon.error/layer ::submission
          :seon.error/operation 'seon.flow/submit!
          :seon.flow/launcher-stopped ::work-launcher
          ::submission-id submission-id
          :seon.error/message "A background call requires a running work launcher."
          :seon.error/expected "a running launcher accepting work"
          :seon.error/data {::launcher-stopped ::work-launcher}}}
          (env/of submission)))
        false))))

(defn- dropped-fault-descriptor
  [fault]
  (let [failure (::flow/ex fault)
        ^String message (or (when (instance? Throwable failure)
                              (ex-message failure))
                            "")
        frame (when (instance? Throwable failure)
                (some-> ^Throwable failure .getStackTrace first str))]
    (cond-> {::dropped-fault-proc (::flow/pid fault)
             ::dropped-fault-status (::flow/status fault)
             ::dropped-fault-class
             (when (instance? Throwable failure)
               (.getName (class failure)))
             ::dropped-fault-message-digest
             (schema/sha-256 [(.getBytes message "UTF-8")])}
      (::flow/cid fault) (assoc ::dropped-fault-cid (::flow/cid fault))
      (::flow/op fault) (assoc ::dropped-fault-op (::flow/op fault))
      frame (assoc ::dropped-fault-frame frame)
      (:seon.agent/id fault)
      (assoc :seon.agent/id (:seon.agent/id fault)))))

(defn- merge-dropped-fault
  [summary fault]
  (let [descriptor (dropped-fault-descriptor fault)
        previous-digest (::dropped-fault-digest summary)]
    {::dropped-fault-count (inc (long (or (::dropped-fault-count summary) 0)))
     ::dropped-fault-digest
     (schema/sha-256
      [(.getBytes (pr-str [previous-digest descriptor]) "UTF-8")])
     ::dropped-fault descriptor}))

(defn- overflow-core-fault
  [{::keys [dropped-fault-count dropped-fault-digest dropped-fault]}]
  (cond->
   {::flow/pid (::dropped-fault-proc dropped-fault)
    ::flow/status (::dropped-fault-status dropped-fault)
    ::flow/op (or (::dropped-fault-op dropped-fault) ::fault-channel-overflow)
    ::flow/ex
    (ex-info
     (str "The core-fault channel overflowed and dropped "
          dropped-fault-count
          (if (= 1 dropped-fault-count) " fault." " faults."))
     (seon.error.refusal/diagnostic (java.util.Date.) ::fault-channel 'seon.flow/overflow-core-fault
      {:seon.error/message "The core-fault channel dropped faults because its buffer was full."
       ::dropped-fault-count dropped-fault-count
       ::dropped-fault-digest dropped-fault-digest
       ::dropped-fault dropped-fault
       :seon.error/expected "capacity for every reported fault"}))
    ::dropped-fault-count dropped-fault-count
    ::dropped-fault-digest dropped-fault-digest
    ::dropped-fault dropped-fault}
    (::dropped-fault-cid dropped-fault)
    (assoc ::flow/cid (::dropped-fault-cid dropped-fault))

    (:seon.agent/id dropped-fault)
    (assoc :seon.agent/id
           (:seon.agent/id dropped-fault))))

(deftype CountedDroppingBuffer
  [^LinkedList buffer
   ^long capacity
   ^:unsynchronized-mutable dropped-summary]
  async.impl/UnblockingBuffer
  async.impl/Buffer
  (full? [_]
    false)
  (remove! [_]
    (if dropped-summary
      (let [fault (overflow-core-fault dropped-summary)]
        (set! dropped-summary nil)
        fault)
      (.removeLast buffer)))
  (add!* [this value]
    (if (>= (.size buffer) capacity)
      (set! dropped-summary (merge-dropped-fault dropped-summary value))
      (.addFirst buffer value))
    this)
  (close-buf! [_])
  Counted
  (count [_]
    (+ (.size buffer) (if dropped-summary 1 0)))
  async.impl/Capacity
  (capacity [_]
    capacity)
  core.protocols/Datafiable
  (datafy [_]
    {:type 'CountedDroppingBuffer
     :count (+ (.size buffer) (if dropped-summary 1 0))
     :capacity (inc capacity)
     ::dropped-fault-count (::dropped-fault-count dropped-summary)}))

(defn- counted-dropping-buffer
  [capacity]
  (CountedDroppingBuffer. (LinkedList.) (long capacity) nil))

(declare report-committer-loss!)

(defn- fault-committer-step
  ([]
   {:workload :io
    :ping-map-fn #(select-keys % [::committed ::panicked ::lost])})
  ([{::keys [fault-channel] :as args}]
   (assoc args
          ::flow/in-ports {::core-fault fault-channel}
          ::committed 0
          ::panicked 0
          ::lost 0
          ::seen-signatures #{}))
  ([state _transition]
   state)
  ([{::keys [read-core-error-mode commit-fault! commit-drop! panic!
             seen-signatures]
     :as state}
    _input fault]
   ;; A closed source error channel presents one terminal nil before
   ;; Flow removes that input. It is lifecycle, not a core fault.
   (if (nil? fault)
     [state nil]
     (try
       (let [mode (read-core-error-mode)
             [fact outcome]
             ((if (::dropped-fault-count fault) commit-drop! commit-fault!)
              {:seon.error/source fault
               :seon.error/declared-schema
               (if (::dropped-fault-count fault)
                 :seon.flow/fault-channel-overflow-error
                 (or (:seon.error/declared-schema (meta (ex-data (::flow/ex fault))))
                     :seon.flow/exception-error))})
             signature (:seon.error/signature fact)
             committed? (= ::committed outcome)
             ;; one line per signature per JVM, but only for a stored fault:
             ;; a failed write panics every time (`seon.fault/policy`)
             repeated? (and committed? signature (contains? seen-signatures signature))
             next-state (cond-> state
                          signature (update ::seen-signatures conj signature)
                          committed? (update ::committed inc))]
         (if (or repeated? (= :record (fault/policy mode committed?)))
           [next-state nil]
           (do (panic! (assoc fault ::fault-fact fact ::commit-outcome outcome
                              ::core-error-mode mode))
               [(update next-state ::panicked inc) nil])))
       (catch Throwable failure
         (report-committer-loss!
          {::flow/pid ::fault-committer
           ::flow/op :step
           ::flow/ex failure
           ::lost-fault fault})
         [(update state ::lost inc) nil])))))

(defn fault-committer-proc
  "Create the Flow proc that turns core faults into durable facts.

   The supplied mode reader represents the database-backed
   :seon.config/on-core-error decision. Record mode commits the fault; panic
   mode invokes the supplied development panic function."
  {:malli/schema
   [:=> [:catn [::request ::fault-committer-proc-request]] ::launcher]}
  [request]
  (var-process (or (::committer-step request) #'fault-committer-step) :io request))

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
      {:seon.error/at (java.util.Date.)
        :seon.error/layer ::monitor
        :seon.error/operation 'seon.flow/monitor-graph
        ::unsupported-method 'clojure.core.async.flow.impl.graph/command-proc
        :seon.error/offending [pid command more-kvs]
        :seon.error/message "core.async.flow does not implement command-proc."
        :seon.error/expected :implemented-protocol-method
        :seon.flow/unsupported-implementation 'clojure.core.async.flow.impl/create-flow})
    (inject [_ coordinate messages]
      (flow.graph/inject graph coordinate messages))))

(defn- fault-loss-name
  [value]
  (str value))

(defn- report-committer-loss!
  "Report whole identities when the fault committer itself fails."
  [escaped]
  (try
    (let [failure (::flow/ex escaped)
          lost (::lost-fault escaped)]
      (binding [*out* *err*]
        (println
         (str "SEON FAULT COMMITTER LOSS: pid="
              (fault-loss-name (or (::flow/pid lost) (::flow/pid escaped)))
              " op="
              (fault-loss-name (or (::flow/op lost) (::flow/op escaped)))
              " failure="
              (fault-loss-name
               (if (instance? Throwable failure)
                 (.getName (class failure))
                 (type failure)))))
        (flush)))
    (catch Throwable _
      ;; The output device itself is the last external boundary. There is no
      ;; second process-local path whose failure could be reported honestly.
      nil))
  nil)

(defn- join-fault-committer-errors!
  "Drain the committer graph's own error channel on that graph's executor, so
  the graph's join also joins this reader: it ends when `flow/stop` closes
  the channel."
  [^Executor executor error-channel report-loss]
  (.execute
   executor
   ^Runnable
   (fn []
     (loop []
       (when-some [escaped (async/<!! error-channel)]
         (report-loss escaped)
         (recur))))))

(defn- fault-graph-definition
  [request]
  {:procs
   {::fault-committer
    {:proc (fault-committer-proc request)}}
   :conns []})

(defn start-error-fanout!
  "Own report/error fan-out for one already-started Flow graph.

   Core faults enter a bounded nonblocking tap. Every admitted fault is
   processed by a dedicated Flow proc. Overflow is retained as one bounded
   synthetic core fault carrying a dropped count and digest, then handed to
   commit-drop! on that same proc. Flow Monitor receives independent
   sliding-buffer taps through the returned datafiable graph, so it never
   competes for the source channels or delays fault commitment."
  {:malli/schema
   [:=> [:catn [::request ::error-fanout-request]] ::error-fanout]}
  [{::keys [graph started fault-buffer-capacity monitor-buffer-capacity
            read-core-error-mode commit-fault! commit-drop! panic! projection
            committer-step report-loss]
    :as request}]
  (let [environment (env/refuse-absent-environment!
                     request ::start-error-fanout!)
        projection
        (or projection
            (:seon.schema/projection environment)
            (throw
             (ex-info
              "The fault committer requires its schema projection at construction."
              {::missing-fault-projection true})))
        report-mult (async/mult (:report-chan started))
        error-mult (async/mult (:error-chan started))
        application-report-channel
        (async/chan (async/sliding-buffer monitor-buffer-capacity))
        monitor-report-channel
        (async/chan (async/sliding-buffer monitor-buffer-capacity))
        monitor-error-channel
        (async/chan (async/sliding-buffer monitor-buffer-capacity))
        fault-channel
        (async/chan
         (counted-dropping-buffer fault-buffer-capacity))
        {fault-graph ::graph
         executor ::executor}
        (start-graph!
         {::projection projection
          ::graph-definition
          (fault-graph-definition
           (env/carry
            (cond-> {::fault-channel fault-channel
                     ::projection projection
                     ::read-core-error-mode read-core-error-mode
                     ::commit-fault! commit-fault!
                     ::commit-drop! commit-drop!
                     ::panic! panic!}
              committer-step (assoc ::committer-step committer-step))
            environment))
          ::joins
          {::fault-committer-error-join
           (fn [{::keys [started executor]}]
             (join-fault-committer-errors! executor (:error-chan started)
                                           (or report-loss #'report-committer-loss!)))}})
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
     ::executor executor}))

(defn join-error-fanout!
  "Feed one more started graph's errors into an existing fan-out.
  The N-source generalization (F1 §6): every agent graph's error
  channel joins the cluster's ONE counted-dropping fault channel,
  tagged with structural provenance. One task on the process-root `:io`
  executor blocks per source; these are virtual threads, so a parked agent
  does not retain a platform thread. The task ends when the source graph
  closes its error channel and never closes the committer's inbox, so nothing
  needs an explicit unjoin. Returns the task's completion channel."
  {:malli/schema [:=> [:cat ::join-error-request] ::channel]}
  [{::keys [started fault-channel tag]}]
  (let [error-channel (:error-chan started)
        completion (async/promise-chan)
        io-executor
        (:io (@operator-runtime-root-executors))]
    (.execute
     ^Executor io-executor
     ^Runnable
     (fn []
       (try
         (loop []
           (when-some [fault (async/<!! error-channel)]
             (async/>!! fault-channel (merge fault tag))
             (recur)))
         (finally
           (async/put! completion ::error-fanout-stopped)))))
    completion))

(defn stop-error-fanout!
  "Detach and stop one error fan-out without stopping its source graph.
  The committer graph is joined before its caller can release the database
  connection an active fault commit uses. Returns nil, or the typed timeout."
  {:malli/schema
   [:=> [:catn [::fanout ::error-fanout] [::bound :seon.await/bound]]
    [:or :nil :seon.await/timeout-error]]}
  [{::keys [fault-graph executor report-mult error-mult
            application-report-channel monitor-report-channel
            monitor-error-channel fault-channel]}
   bound]
  (flow/stop fault-graph)
  (let [exited (join-graph!
                (cond-> {:seon.await/bound bound
                         :seon.await/diagnostic {:seon.error/layer :flow
                                                 :seon.error/operation ::stop-error-fanout
                                                 :seon.error/expected ::fault-graph-exited}}
                  executor (assoc ::executor executor)))]
    (async/untap report-mult application-report-channel)
    (async/untap report-mult monitor-report-channel)
    (async/untap error-mult monitor-error-channel)
    (async/untap error-mult fault-channel)
    (doseq [channel [application-report-channel monitor-report-channel
                     monitor-error-channel]]
      (async/close! channel))
    exited))
