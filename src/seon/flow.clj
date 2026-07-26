(ns seon.flow
  "Production-shaped core.async.flow launchers used by the standing testbed.

   This namespace deliberately does not own durable runtime state. Ordinary
   Flow processes retain only disposable counters and handles; the custom
   database process obtains facts and commits facts through supplied
   functions. Flow channels carry scheduling and wake signals."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.core.async.flow.spi :as flow.spi]
            [clojure.datafy :as datafy]
            [clojure.walk :as walk]
            [seon.schema :as schema])
  (:import [java.util.concurrent Executor Executors Semaphore]))

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

(schema/register-core-predicate! 'seon.flow/executor? executor?)
(schema/register-core-predicate! 'seon.flow/semaphore? semaphore?)
(schema/register-core-predicate! 'seon.flow/atom-reference? atom-reference?)
(schema/register-core-predicate! 'seon.flow/proc-launcher? proc-launcher?)

(schema/register! ::parallelism [:int {:min 1}])
(schema/register! ::executor [:fn 'seon.flow/executor?])
(schema/register! ::permits [:fn 'seon.flow/semaphore?])
(schema/register! ::active-evals [:fn 'seon.flow/atom-reference?])
(schema/register! ::compute-timeout-ms [:int {:min 1}])
(schema/register! ::deliver! 'fn?)
(schema/register! ::read-facts 'fn?)
(schema/register! ::step-fn 'fn?)
(schema/register! ::stopped! 'fn?)
(schema/register! ::launcher [:fn 'seon.flow/proc-launcher?])
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

(defn bounded-platform-executor
  "Create a bounded executor whose workers are platform threads."
  {:malli/schema [:=> [:catn [::parallelism ::parallelism]] ::executor]}
  [parallelism]
  (Executors/newFixedThreadPool (int parallelism)))

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
    clojure.core.protocols/Datafiable
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
