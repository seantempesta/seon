(ns seon.flow
  "Production-shaped core.async.flow launchers used by the standing testbed.

   This namespace deliberately does not own durable runtime state. Ordinary
   Flow processes retain only disposable counters and handles; the custom
   database process obtains facts and commits facts through supplied
   functions. Flow channels carry scheduling and wake signals."
  (:require [clojure.core.protocols :as core.protocols]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.core.async.flow.impl.graph :as flow.graph]
            [clojure.core.async.flow.spi :as flow.spi]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.datafy :as datafy]
            [clojure.walk :as walk]
            [seon.schema :as schema])
  (:import [clojure.lang Counted]
           [java.util LinkedList]
           [java.util.concurrent Executor Executors Semaphore]))

(set! *warn-on-reflection* true)

(defn- executor?
  [value]
  (instance? Executor value))

(defn- semaphore?
  [value]
  (instance? Semaphore value))

(defn- atom-reference?
  [value]
  (instance? clojure.lang.IAtom value))

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
(schema/register-core-predicate! 'seon.flow/semaphore? semaphore?)
(schema/register-core-predicate! 'seon.flow/atom-reference? atom-reference?)
(schema/register-core-predicate! 'seon.flow/proc-launcher? proc-launcher?)
(schema/register-core-predicate! 'seon.flow/graph? graph?)
(schema/register-core-predicate! 'seon.flow/channel? channel?)

(schema/register! ::parallelism [:int {:min 1}])
(schema/register! ::executor [:fn 'seon.flow/executor?])
(schema/register! ::permits [:fn 'seon.flow/semaphore?])
(schema/register! ::active-evals [:fn 'seon.flow/atom-reference?])
(schema/register! ::compute-timeout-ms [:int {:min 1}])
(schema/register! ::deliver! 'fn?)
(schema/register! ::read-facts 'fn?)
(schema/register! ::step-fn 'fn?)
(schema/register! ::stopped! 'fn?)
(schema/register! ::commit-fault! 'fn?)
(schema/register! ::commit-drop! 'fn?)
(schema/register! ::read-core-error-mode 'fn?)
(schema/register! ::panic! 'fn?)
(schema/register! ::launcher [:fn 'seon.flow/proc-launcher?])
(schema/register! ::graph [:fn 'seon.flow/graph?])
(schema/register! ::channel [:fn 'seon.flow/channel?])
(schema/register! ::buffer-capacity [:int {:min 1}])
(schema/register! ::seed :int)
(schema/register! ::owner-ordinal [:int {:min 0}])
(schema/register! ::attempt [:int {:min 0}])
(schema/register!
 ::fix-outcome
 [:enum ::fix-succeeds ::fix-fails ::fix-breaks-other-namespace
  ::no-response])
(schema/register! ::plan-step-fn 'fn?)
(schema/register! ::fix-step-fn 'fn?)
(schema/register! ::read-sources 'fn?)
(schema/register! ::compile-namespace-fn 'fn?)
(schema/register! ::sources [:map-of :symbol :string])
(schema/register! ::changed-namespace :symbol)
(schema/register! ::changed-source :string)
(schema/register! ::owner-count [:int {:min 1}])
(schema/register! ::successful-owners [:set :keyword])
(schema/register! ::escalated? :boolean)
(schema/register! ::admitted? :boolean)
(schema/register! ::lineage-status [:enum ::iterating ::escalated ::done])
(schema/register! ::turn-count [:int {:min 0}])
(schema/register! ::failure-count [:int {:min 0}])
(schema/register! ::max-turns [:int {:min 1}])
(schema/register! ::max-failures [:int {:min 1}])
(schema/register!
 ::eval-proc-request
 [:map
  [::permits ::permits]
  [::active-evals ::active-evals]
  [::compute-timeout-ms ::compute-timeout-ms]])
(schema/register!
 ::capacity-observer-request
 [:map
  [::permits ::permits]
  [::active-evals ::active-evals]])
(schema/register! ::mailbox-request [:map [::deliver! ::deliver!]])
(schema/register!
 ::database-proc-request
 [:map
  [::read-facts ::read-facts]
  [::step-fn ::step-fn]
  [::stopped! ::stopped!]])
(schema/register!
 ::fault-committer-proc-request
 [:map
  [::fault-channel ::channel]
  [::read-core-error-mode ::read-core-error-mode]
  [::commit-fault! ::commit-fault!]
  [::panic! ::panic!]])
(schema/register!
 ::error-fanout-request
 [:map
  [::graph ::graph]
  [::started
   [:map
    [:report-chan ::channel]
    [:error-chan ::channel]]]
  [::fault-buffer-capacity ::buffer-capacity]
  [::monitor-buffer-capacity ::buffer-capacity]
  [::read-core-error-mode ::read-core-error-mode]
  [::commit-fault! ::commit-fault!]
  [::commit-drop! ::commit-drop!]
  [::panic! ::panic!]])
(schema/register!
 ::error-fanout
 [:map
  [::graph ::graph]
  [::fault-graph ::graph]
  [::application-report-channel ::channel]
  [::monitor-report-channel ::channel]
  [::monitor-error-channel ::channel]
  [::fault-channel ::channel]])
(schema/register!
 ::seeded-outcome-request
 [:map
  [::seed ::seed]
  [::owner-ordinal ::owner-ordinal]
  [::attempt ::attempt]])
(schema/register!
 ::lineage-status-request
 [:map
  [::owner-count ::owner-count]
  [::successful-owners ::successful-owners]
  [::escalated? ::escalated?]
  [::admitted? ::admitted?]])
(schema/register!
 ::escalation-request
 [:map
  [::turn-count ::turn-count]
  [::failure-count ::failure-count]
  [::max-turns ::max-turns]
  [::max-failures ::max-failures]])
(schema/register!
 ::planner-proc-request
 [:map [::plan-step-fn ::plan-step-fn]])
(schema/register!
 ::namespace-owner-proc-request
 [:map [::fix-step-fn ::fix-step-fn]])
(schema/register!
 ::source-enumerator-proc-request
 [:map [::read-sources ::read-sources]])
(schema/register!
 ::indexer-proc-request
 [:map
  [::compile-namespace-fn ::compile-namespace-fn]
  [::compute-timeout-ms ::compute-timeout-ms]])

(defn bounded-platform-executor
  "Create a bounded executor whose workers are platform threads."
  {:malli/schema [:=> [:catn [::parallelism ::parallelism]] ::executor]}
  [parallelism]
  (Executors/newFixedThreadPool (int parallelism)))

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

(defn fault-committer-proc
  "Create the Flow proc that turns core faults into durable facts.

   The supplied mode reader represents the database-backed
   :seon.config/on-core-error decision. Record mode commits the fault; panic
   mode invokes the supplied development panic function."
  {:malli/schema
   [:=> [:catn [::request ::fault-committer-proc-request]] ::launcher]}
  [{::keys [fault-channel read-core-error-mode commit-fault! panic!]}]
  (flow/process
   (flow/map->step
    {:describe
     (fn []
       {:workload :io
        :ping-map-fn #(select-keys % [::committed ::panicked])})
     :init
     (fn [_]
       {::flow/in-ports {::core-fault fault-channel}
        ::committed 0
        ::panicked 0})
     :transform
     (fn [state _input fault]
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
           {::core-error-mode (read-core-error-mode)}))))})))

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
        fault-graph
        (flow/create-flow
         {:procs
          {::fault-committer
           {:proc
            (fault-committer-proc
             {::fault-channel fault-channel
              ::read-core-error-mode read-core-error-mode
              ::commit-fault! commit-fault!
              ::panic! panic!})}}
          :conns []})
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
     ::fault-channel fault-channel}))

(defn stop-error-fanout!
  "Detach and stop one error fan-out without stopping its source graph."
  {:malli/schema
   [:=> [:catn [::fanout ::error-fanout]] :boolean]}
  [{::keys [fault-graph report-mult error-mult
            application-report-channel monitor-report-channel
            monitor-error-channel fault-channel]}]
  (let [stopped? (boolean (flow/stop fault-graph))]
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

(defn planner-proc
  "Create a fake planner proc whose supplied step returns ordinary data."
  {:malli/schema
   [:=> [:catn [::request ::planner-proc-request]] ::launcher]}
  [{::keys [plan-step-fn]}]
  (flow/process
   (flow/map->step
    {:describe
     (fn []
       {:ins {::planner-wake "A fact-derived planner wake value."}
        :workload :io
        :ping-map-fn #(select-keys % [::attempts])})
     :init (fn [_] {::attempts 0})
     :transform
     (fn [state _input message]
       (let [result (plan-step-fn message)]
         [(update state ::attempts inc)
          {::flow/report
           [{::event ::planner-attempt
             ::result result}]}]))})))

(defn namespace-owner-proc
  "Create a fake namespace-owner proc returning one scripted outcome."
  {:malli/schema
   [:=> [:catn [::request ::namespace-owner-proc-request]] ::launcher]}
  [{::keys [fix-step-fn]}]
  (flow/process
   (flow/map->step
    {:describe
     (fn []
       {:ins {::owner-step
              "An initial fact-derived wake or open-run continuation."}
        :workload :io
        :ping-map-fn #(select-keys % [::attempts])})
     :init (fn [_] {::attempts 0})
     :transform
     (fn [state _input message]
       (let [outcome (fix-step-fn message)]
         [(update state ::attempts inc)
          {::flow/report
           [{::event ::owner-attempt
             ::outcome outcome}]}]))})))

(defn source-enumerator-proc
  "Create an I/O proc that emits fixture namespaces and changed events."
  {:malli/schema
   [:=> [:catn [::request ::source-enumerator-proc-request]] ::launcher]}
  [{::keys [read-sources]}]
  (flow/process
   (flow/map->step
    {:describe
     (fn []
       {:ins {::source-event
              "A full-drain request or one changed namespace source."}
        :outs {::index-request
               "One namespace source submitted to the indexer."}
        :workload :io
        :ping-map-fn #(select-keys % [::emitted ::sources])})
     :init
     (fn [_]
       {::sources (read-sources)
        ::emitted 0})
     :transform
     (fn [state _input message]
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
             ::namespaces (set selected)}]}]))})))

(defn indexer-proc
  "Create a compute proc that compiles one namespace into transaction data."
  {:malli/schema
   [:=> [:catn [::request ::indexer-proc-request]] ::launcher]}
  [{::keys [compile-namespace-fn compute-timeout-ms]}]
  (flow/process
   (flow/map->step
    {:describe
     (fn []
       {:ins {::index-request "One namespace source to compile."}
        :outs {::tx-page "Transaction data for the database committer."}
        :workload :compute
        :ping-map-fn #(select-keys % [::compiled])})
     :init (fn [_] {::compiled 0})
     :transform
     (fn [state _input request]
       (let [page (compile-namespace-fn request)]
         [(update state ::compiled inc)
          {::tx-page [page]
           ::flow/report
           [{::event ::namespace-indexed
             ::namespace (::changed-namespace request)}]}]))})
   {:workload :compute
    :compute-timeout-ms compute-timeout-ms}))

(defn eval-proc
  "Create a compute proc that simulates one guarded evaluation."
  {:malli/schema
   [:=> [:catn [::request ::eval-proc-request]] ::launcher]}
  [{::keys [permits active-evals compute-timeout-ms]}]
  (flow/process
   (flow/map->step
    {:describe
     (fn []
       {:ins {::submission "A process-local evaluation submission."}
        :outs {::result "The disposable result delivered downstream."}
        :workload :compute
        :ping-map-fn
        (fn [state]
          (assoc (select-keys state [::pid ::completed])
                 ::active? (contains? @active-evals (::pid state))))})
     :init
     (fn [{pid ::flow/pid}]
       {::pid pid
        ::completed 0})
     :transition
     (fn [state transition]
       (assoc state ::transition transition))
     :transform
     (fn [state _input
          {::keys [work-fn wedged? interrupt-fn]}]
       (let [pid (::pid state)
             armed? (atom true)
             deadline-interrupt-fn (or interrupt-fn (constantly nil))
             armed-interrupt-fn
             (fn []
               (deadline-interrupt-fn)
               (when-not @armed?
                 (throw
                  (ex-info
                   "The fake interrupt function was called after disarm."
                   {::pid pid}))))]
         (.acquire ^Semaphore permits)
         (swap! active-evals
                assoc pid
                {::interrupt-fn armed-interrupt-fn
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
             (swap! active-evals dissoc pid)
             (.release ^Semaphore permits)))))})
   {:workload :compute
    :compute-timeout-ms compute-timeout-ms}))

(defn- capacity-facts
  [permits active-evals]
  (let [active @active-evals]
    {::active-procs (set (keys active))
     ::wedged-procs
     (into #{}
           (keep (fn [[pid facts]]
                   (when (::wedged? facts) pid)))
           active)
     ::available-permits
     (.availablePermits ^Semaphore permits)
     ::platform-threads?
     (every? ::platform-thread? (vals active))}))

(defn capacity-observer-proc
  "Create a responsive proc that reports current compute occupancy."
  {:malli/schema
   [:=> [:catn [::request ::capacity-observer-request]] ::launcher]}
  [{::keys [permits active-evals]}]
  (flow/process
   (flow/map->step
    {:describe
     (fn []
       {:ins {::observe "A process-local request to refresh observations."}
        :ping-map-fn
        (fn [_state] (capacity-facts permits active-evals))})
     :init (fn [_] {::observations 0})
     :transform
     (fn [state _input _message]
       [(update state ::observations inc)
        {::flow/report
         [(assoc (capacity-facts permits active-evals)
                 ::event ::capacity)]}])})))

(defn mailbox-proc
  "Create a proc that delivers each message to a supplied sink."
  {:malli/schema
   [:=> [:catn [::request ::mailbox-request]] ::launcher]}
  [{::keys [deliver!]}]
  (flow/process
   (flow/map->step
    {:describe
     (fn []
       {:ins {::mailbox "A presentation (sliding-buffer 1) tap."}
        :ping-map-fn #(select-keys % [::delivered])})
     :init (fn [_] {::delivered 0})
     :transform
     (fn [state _input message]
       (deliver! message)
       [(update state ::delivered inc) nil])})))

(defn- addressed?
  [pid command]
  (contains? #{pid ::flow/all} (::flow/to command)))

(defn- ping-map
  [pid status count ins outs facts]
  (walk/postwalk
   datafy/datafy
   #::flow{:pid pid
           :status status
           :state facts
           :count count
           :ins (dissoc ins ::flow/control ::flow/casts)
           :outs (dissoc outs ::flow/error ::flow/report)}))

(defn- next-status
  [pid status command facts-fn count ins outs]
  (if-not (addressed? pid command)
    status
    (case (::flow/command command)
      ::flow/stop ::exit
      ::flow/pause :paused
      ::flow/resume :running
      ::flow/ping
      (do
        (async/>!! (::flow/reply-chan command)
                   (ping-map pid status count ins outs (facts-fn)))
        status)
      status)))

(defn database-proc
  "Create a Flow SPI proc whose durable state lives behind functions."
  {:malli/schema
   [:=> [:catn [::request ::database-proc-request]] ::launcher]}
  [{::keys [read-facts step-fn stopped!]}]
  (reify
    core.protocols/Datafiable
    (datafy [_]
      {::launcher ::database})

    flow.spi/ProcLauncher
    (describe [_]
      {:ins {::wake "A process-local wake signal; facts hold the work."}
       :outs {::facts-changed
              "A disposable wake signal for database-derived consumers."}})
    (start [_ {:keys [pid ins outs resolver]}]
      (let [control (::flow/control ins)
            wake (::wake ins)
            error (::flow/error outs)
            facts-changed (::facts-changed outs)
            run
            (fn []
              (try
                (loop [status :paused
                       count 0]
                  (let [[message channel]
                        (async/alts!!
                         (if (= status :paused)
                           [control]
                           [control wake])
                         :priority true)]
                    (if (= channel control)
                      (let [status'
                            (next-status
                             pid status message read-facts count ins outs)]
                        (when-not (= status' ::exit)
                          (recur status' count)))
                      (let [committed?
                            (try
                              (step-fn (read-facts) message)
                              true
                              (catch Throwable throwable
                                (async/>!!
                                 error
                                 #::flow{:pid pid
                                         :status status
                                         :count count
                                         :cid ::wake
                                         :msg message
                                         :op ::database-step
                                         :ex throwable})
                                false))
                            _ (when (and committed? facts-changed)
                                (async/offer! facts-changed ::facts-changed))
                            count' (if committed? (inc count) count)]
                        (recur status (long count'))))))
                (finally
                  (stopped! {::pid pid}))))]
        (.execute ^Executor (flow.spi/get-exec resolver :io) ^Runnable run)))))
