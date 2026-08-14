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
            [seon.await :as await]
            [seon.env :as env]
            [seon.schema :as schema]
            [seon.sci.kernel :as kernel]
            [seon.schema.edn :as schema.edn])
  (:import [clojure.lang Counted]
           [java.util LinkedList]
           [java.util.concurrent Executor Executors Future FutureTask]
           [java.util.concurrent.atomic AtomicLong]))

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

(schema/register-core-predicate! 'seon.flow/executor? executor?)
(schema/register-core-predicate! 'seon.flow/atom-reference? atom-reference?)
(schema/register-core-predicate! 'seon.flow/java-future? java-future?)
(schema/register-core-predicate! 'seon.flow/proc-launcher? proc-launcher?)
(schema/register-core-predicate! 'seon.flow/graph? graph?)
(schema/register-core-predicate! 'seon.flow/channel? channel?)
(schema/register-core-predicate! 'seon.flow/step-var? step-var?)

(defn start-graph!
  "Create, join declared fan-outs, and resume one Flow graph."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [::graph-definition :map]
      [::joins {:optional true}
       [:map-of :qualified-keyword :seon.flow/join!]]]]
    [:map
     [::graph ::graph]
     [::started ::started]
     [::joins :map]]]}
  [{::keys [graph-definition joins]}]
  (let [graph (flow/create-flow graph-definition)
        started (flow/start graph)
        construction {::graph graph
                      ::started started}
        joined (reduce-kv (fn [results join-key join!]
                            (assoc results join-key (join! construction)))
                          {}
                          (or joins {}))]
    (flow/resume graph)
    (assoc construction ::joins joined)))

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

(defn var-process
  "Build one Flow proc launcher from a step VAR and a pinned workload.
  THE construction door for every proc in the system (F0(a), the F1
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
  truthy — falsified live 2026-08-07), so this door makes it. `args`
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

(defn- with-current-arm
  "Capture the submitting thread's interrupt arm onto an AWAITED submission.

  The arm travels exactly as the environment does — as data on the
  submission — so the work counts its fn-entries into the arm that admitted
  it, observes that deadline, and is reachable by `interrupt!`. An unarmed
  submitter carries no arm, which is ordinary system-side work and never a
  refusal.

  THIS APPLIES TO `submit!!` ONLY, and the asymmetry is the whole point.
  `submit!!` blocks its submitter until the work settles, so the submitter's
  limit is exactly the limit the work must honour. `submit!` is detached by
  construction — its whole reason to exist is work that OUTLIVES the frame
  that started it (`my.background`) — so inheriting the submitting turn's
  deadline latch would kill background work at the turn's limit, which
  inverts the surface's meaning. A detached submission therefore carries
  only the arm its own data names — and since the 2026-08-08 background
  bounds ruling that is a FRESH arm the submitter builds at its own limit
  (`seon.effect/request*` via `kernel/detached-arm`), never this capture."
  [submission]
  (if-let [armed (kernel/current-arm)]
    (assoc submission :seon.env/environment
           (env/refuse-incomplete-environment!
            (env/scope (env/of submission) {:seon.sci.kernel/arm armed})))
    submission))

(defn- carried-arm
  [work]
  (:seon.sci.kernel/arm (env/of work)))

(defn- submission-capacity-error
  [submission-id workload]
  {:seon.error/kind ::submission-capacity
   :seon.flow/submission-capacity workload
   :seon.error/message "The bounded work submission queue is full."
   :seon.error/data {::submission-id submission-id
                     ::workload workload}})

(defn- refuse-compute-submission!
  [{::keys [submission-id result status]}]
  (when (compare-and-set! status ::queued ::refused)
    (deliver result {::started-at (System/nanoTime)
                     ::value (submission-capacity-error submission-id
                                                        :compute)})))

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

(defn- execute-work!
  [compute-executor completion active-work
   {::keys [submission-id work-fn result status] :as work}]
  ;; The environment travels as DATA on the submission and is merged into
  ;; what the work-fn receives, so the compute thread names its own cluster
  ;; instead of reading whatever binding frame it inherited.
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
                     (vreset! started-at (System/nanoTime))))
                 terminal
                 (try
                   (let [value
                         (kernel/adopt-arm
                          (carried-arm work)
                          #(work-fn (env/carry {::started! started!}
                                               (env/of work))))]
                     (started!)
                     {::started-at @started-at
                      ::value value})
                   (catch Throwable throwable
                     (started!)
                     {::started-at @started-at
                      ::throwable throwable}))]
             (reset! status ::completed)
             (swap! active-work dissoc submission-id)
             (deliver result terminal)
             (async/offer!
              completion
              (assoc terminal
                     ::submission-id submission-id
                     ::workload :compute
                     ::work
                     (dissoc work ::work-fn ::result ::status))))))
        (catch Throwable throwable
          (reset! status ::completed)
          (swap! active-work dissoc submission-id)
          (let [terminal
                {::started-at (System/nanoTime)
                 ::throwable throwable}]
            (deliver result terminal)
            (async/offer!
             completion
             (assoc terminal
                    ::submission-id submission-id
                    ::workload :compute
                    ::work
                    (dissoc work ::work-fn ::result ::status)))))))))

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
  [{::keys [parallelism active-count io-parallelism io-active-count]
    :as state}]
  (assoc
   state
   ::flow/input-filter
   (fn [input-id]
     (case input-id
       ::compute-submission (< active-count parallelism)
       ::io-submission (< io-active-count io-parallelism)
       true))))

(defn- work-launcher-step
  ([]
   {:ins {::compute-submission
          "One disposable compute submission backed by durable work."
          ::io-submission
          "One nonblocking IO submission backed by a durable receipt."}
    :workload :io
    :ping-map-fn
    (fn [{::keys [parallelism active-work]}]
      (capacity-facts parallelism active-work))})
  ([{::keys [parallelism io-parallelism] :as args}]
   (with-submission-filter
     (assoc args
            ::active-count 0
            ::io-active-count 0
            ::flow/in-ports
            {::completion (async/chan (+ parallelism io-parallelism))})))
  ([{::keys [proc-stopped] :as state} transition]
   (when (= ::flow/stop transition)
     (deliver proc-stopped ::stopped))
   state)
  ([{::keys [active-count active-work admission-buffer task-executor]
     :as state}
    input-id
    message]
   (case input-id
     ::compute-submission
     (do
       (execute-work!
        task-executor
        (get-in state [::flow/in-ports ::completion])
        active-work
        message)
       [(with-submission-filter
          (assoc state ::active-count (inc active-count)))
        nil])

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
       (let [io? (= :io (::workload message))
             buffer (if io? (::io-admission-buffer state) admission-buffer)]
         (release-admission! buffer))
       [(with-submission-filter
          (update state
                  (if (= :io (::workload message))
                    ::io-active-count
                    ::active-count)
                  dec))
        (if-let [throwable (::throwable message)]
          {::flow/error
           [#::flow{:pid (::flow/pid state)
                    :status :running
                    :cid (if (= :io (::workload message))
                           ::io-submission
                           ::compute-submission)
                    :msg (::work message)
                    :op ::work
                    :ex throwable}]}
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
  [:seon.config.flow.compute/queue-depth
   :seon.config.flow.compute/concurrency
   :seon.config.flow.io/queue-depth
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
        {:seon.error/kind :configuration
         :seon.flow/configuration true
         :seon.error/message
         "The work launcher is not ready: required config facts are missing."
         ::missing-config-facts (vec missing)})))
    selected))

(defn- work-launcher-graph-definition
  [{::keys [parallelism active-work queue-depth compute-executor
            task-executor io-parallelism io-queue-depth io-submissions
            proc-stopped]
    :as request}]
  (let [environment (env/of request)
        admission-buffer
        (refusing-buffer (+ parallelism queue-depth)
                         refuse-compute-submission!)
        io-admission-buffer
        (refusing-buffer
         (+ io-parallelism io-queue-depth)
         (partial refuse-io-submission! io-submissions))]
    {:procs
     {::work-launcher
      {:proc
       (work-launcher-proc
        (env/carry
         {::parallelism parallelism
          ::active-work active-work
          ::admission-buffer admission-buffer
          ::task-executor task-executor
          ::io-parallelism io-parallelism
          ::io-submissions io-submissions
          ::io-admission-buffer io-admission-buffer
          ::proc-stopped proc-stopped}
         environment))
       :chan-opts
       {::compute-submission {:buf-or-n admission-buffer}
        ::io-submission {:buf-or-n io-admission-buffer}}}
      ::capacity-observer
      {:proc
       (capacity-observer-proc
        (env/carry
         {::parallelism parallelism
          ::active-work active-work}
         environment))}}
     :conns []
     :compute-exec compute-executor}))

(defn start-work-launcher!
  "Start one cluster-owned bounded work launcher from acquired config facts."
  {:malli/schema
   [:=> [:cat :seon.flow/work-launcher-request]
    :seon.flow/work-launcher]}
  [{::keys [configuration] :as request}]
  (let [environment (env/refuse-absent-environment!
                     request ::start-work-launcher!)
        configuration (required-launcher-configuration configuration)
        queue-depth
        (:seon.config.flow.compute/queue-depth configuration)
        parallelism
        (:seon.config.flow.compute/concurrency configuration)
        io-queue-depth
        (:seon.config.flow.io/queue-depth configuration)
        io-parallelism
        (:seon.config.flow.io/concurrency configuration)
        active-work (atom {})
        io-submissions (atom {})
        accepting? (atom true)
        drained (promise)
        proc-stopped (promise)
        _
        (add-watch
         io-submissions
         ::drained
         (fn [_ _ _ next-submissions]
           (when (and (not @accepting?) (empty? next-submissions))
             (deliver drained ::drained))))
        root-executors
        ((requiring-resolve 'seon.operator.runtime/root-executors))
        task-executor (:io root-executors)
        {::keys [graph started]}
        (start-graph!
         {::graph-definition
          (work-launcher-graph-definition
           (env/carry
            {::parallelism parallelism
             ::active-work active-work
             ::queue-depth queue-depth
             ::io-queue-depth io-queue-depth
             ::io-parallelism io-parallelism
             ::io-submissions io-submissions
             ::proc-stopped proc-stopped
             ::compute-executor (:compute root-executors)
             ::task-executor task-executor}
            environment))})]
    {::graph graph
     ::started started
     ::active-work active-work
     ::io-submissions io-submissions
     ::accepting? accepting?
     ::drained drained
     ::proc-stopped proc-stopped
     ::compute-executor task-executor
     ::configuration configuration}))

(defn stop-work-launcher!
  "Close IO admission, settle accepted work, then stop the Flow graph."
  {:malli/schema
   [:=> [:cat :seon.flow/work-launcher]
    [:or :nil :seon.error/value]]}
  [{::keys [graph accepting? io-submissions drained proc-stopped active-work
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
        {:seon.error/kind ::launcher-stopped
         :seon.flow/launcher-stopped ::work-launcher
         :seon.error/message
         "The work launcher stopped before the background call completed."
         :seon.error/data {::submission-id (::submission-id work)}}}))
    (when (empty? @io-submissions)
      (deliver drained ::drained))
    (let [bound
          {:seon.await/config-attribute
           :seon.config.agent/turn-completion-backstop-ms
           :seon.await/config-value
           (:seon.config.agent/turn-completion-backstop-ms configuration)}
          observation
          (fn [member]
            {:seon.error/diagnostic-layer :flow
             :seon.error/diagnostic-operation ::stop-work-launcher
             :seon.error/diagnostic-member member
             :seon.error/diagnostic-expected ::completion
             :seon.error/diagnostic-offending ::pending
             :seon.error/diagnostic-evidence
             {:seon.flow/active-work-count (count @active-work)
              :seon.flow/io-submission-count (count @io-submissions)}})
          drained-result
          (await/await!
           {:seon.await/bound bound
            :seon.await/diagnostic (observation ::launcher-drained)
            :seon.await/blocking-deref drained})
          _ (flow/stop graph)
          stopped-result
          (await/await!
           {:seon.await/bound bound
            :seon.await/diagnostic (observation ::work-launcher-proc-stopped)
            :seon.await/blocking-deref proc-stopped})]
      (cond
        (:seon.error/kind drained-result) drained-result
        (:seon.error/kind stopped-result) stopped-result
        :else nil))))

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
  submitter that genuinely wants its own limit to govern awaits the work
  with `submit!!` or puts an arm in the submission's environment itself."
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
              {:seon.error/kind ::launcher-stopped
               :seon.flow/launcher-stopped ::work-launcher
               :seon.error/message
               "The work launcher is no longer accepting background calls."
               :seon.error/data {::submission-id submission-id}}})
            false)))
      (do
        (completion
         (env/carry
          {::started-at (System/nanoTime)
           ::value
           {:seon.error/kind ::launcher-stopped
            :seon.flow/launcher-stopped ::work-launcher
            :seon.error/message
            "A background call requires a running work launcher."
            :seon.error/data {::submission-id submission-id}}}
          (env/of submission)))
        false))))

(defn submit!!
  "Submit bounded compute work and await its terminal value.

  Admission never blocks: a full queue completes immediately with a flat
  `::submission-capacity` error value. For accepted work, `time-limit-ms`
  bounds the interval from the injection request through queued waiting and
  execution. `::submission-wait-ms` measures time to start, refusal, or the
  limit, whichever terminates the submission wait."
  {:malli/schema
   [:=> [:cat :seon.flow/work-launcher :seon.flow/work-submission]
    :seon.flow/work-result]}
  [work-launcher submission]
  (when-not work-launcher
    (throw
     (ex-info
      "A compute submission must name its cluster's work launcher."
      {:seon.error/kind :configuration
       :seon.flow/configuration true
       :seon.error/message
       "A compute submission must name its cluster's work launcher."})))
  (env/refuse-absent-environment! submission ::submit!!)
  (let [{::keys [submission-id workload work-fn time-limit-ms] :as submission}
        (with-current-arm submission)
        {::keys [graph active-work]} work-launcher
        result (promise)
        status (atom ::queued)
        submitted-at (System/nanoTime)
        _
        (flow/inject
         graph
         [::work-launcher ::compute-submission]
         [(env/carry
           {::submission-id submission-id
            ::workload workload
            ::work-fn work-fn
            ::result result
            ::status status}
           (env/of submission))])
        injection-elapsed-ms
        (quot (+ (- (System/nanoTime) submitted-at) 999999) 1000000)
        remaining-ms (max 0 (- time-limit-ms injection-elapsed-ms))
        settled (deref result remaining-ms ::time-limit)
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
         ::submission-wait-ms submission-wait-ms}))))

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
      (:seon.cluster.agent/id fault)
      (assoc :seon.cluster.agent/id (:seon.cluster.agent/id fault)))))

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
     {:seon.error/kind ::fault-channel-overflow
      ::dropped-fault-count dropped-fault-count
      ::dropped-fault-digest dropped-fault-digest
      ::dropped-fault dropped-fault :seon.flow/fault-channel-overflow true})
    ::dropped-fault-count dropped-fault-count
    ::dropped-fault-digest dropped-fault-digest
    ::dropped-fault dropped-fault}
    (::dropped-fault-cid dropped-fault)
    (assoc ::flow/cid (::dropped-fault-cid dropped-fault))

    (:seon.cluster.agent/id dropped-fault)
    (assoc :seon.cluster.agent/id
           (:seon.cluster.agent/id dropped-fault))))

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

(defn- fault-committer-step
  ([]
   {:workload :io
    :ping-map-fn #(select-keys % [::committed ::panicked])})
  ([{::keys [fault-channel] :as args}]
   (assoc args
          ::flow/in-ports {::core-fault fault-channel}
          ::committed 0
          ::panicked 0
          ::seen-signatures #{}))
  ([{::keys [completion] :as state} transition]
   (when (= ::flow/stop transition)
     ;; Flow observes this transition only after an active transform
     ;; returns, so the marker joins an in-flight durable commit without
     ;; inventing a clock.
     (async/put! completion ::stopped))
   state)
  ([{::keys [read-core-error-mode commit-fault! commit-drop! panic!
             seen-signatures]
     :as state}
    _input fault]
   ;; A closed source error channel presents one terminal nil before
   ;; Flow removes that input. It is lifecycle, not a core fault.
   (if (nil? fault)
     [state nil]
    (let [mode (read-core-error-mode)
          [fact outcome previously-reported?]
          ((if (::dropped-fault-count fault) commit-drop! commit-fault!) fault)
          signature (:seon.error/signature fact)
          repeated? (and signature (contains? seen-signatures signature))
          already-reported?
          (or repeated? previously-reported?)
          next-state (cond-> state
                       signature (update ::seen-signatures conj signature)
                       (= ::committed outcome) (update ::committed inc))
          reported (assoc fault
                          ::fault-fact fact
                          ::commit-outcome outcome
                          ::core-error-mode mode)
          commit-refused?
          (not= ::committed outcome)]
      (cond
        already-reported?
        [next-state nil]

        (= :record mode)
        (do
          (when commit-refused? (panic! reported))
          [next-state nil])

        (= :panic mode)
        (do
          (panic! reported)
          [(update next-state ::panicked inc) nil])

        :else
        (throw
         (ex-info
          "Unknown fake :seon.config/on-core-error value."
          {::core-error-mode mode})))))))

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
   processed by a dedicated Flow proc. Overflow is retained as one bounded
   synthetic core fault carrying a dropped count and digest, then handed to
   commit-drop! on that same proc. Flow Monitor receives independent
   sliding-buffer taps through the returned datafiable graph, so it never
   competes for the source channels or delays fault commitment."
  {:malli/schema
   [:=> [:catn [::request ::error-fanout-request]] ::error-fanout]}
  [{::keys [graph started fault-buffer-capacity monitor-buffer-capacity
            read-core-error-mode commit-fault! commit-drop! panic!]
    :as request}]
  (let [environment (env/refuse-absent-environment!
                     request ::start-error-fanout!)
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
        completion (async/promise-chan)
        {fault-graph ::graph}
        (start-graph!
         {::graph-definition
          (fault-graph-definition
           (env/carry
            {::fault-channel fault-channel
             ::completion completion
             ::read-core-error-mode read-core-error-mode
             ::commit-fault! commit-fault!
             ::commit-drop! commit-drop!
             ::panic! panic!}
            environment))})
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
        (:io ((requiring-resolve 'seon.operator.runtime/root-executors)))]
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
