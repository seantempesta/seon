(ns submit-probe.startup-wait
  (:require [clojure.core.async.flow :as async.flow]
            [clojure.datafy :as datafy]
            [seon.flow :as flow])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn elapsed-ms
  [started-at]
  (double (/ (- (System/nanoTime) started-at) 1000000.0)))

(defn submit
  [submission-id work-fn time-limit-ms]
  (flow/submit!!
   {::flow/submission-id submission-id
    ::flow/workload :compute
    ::flow/work-fn work-fn
    ::flow/time-limit-ms time-limit-ms}))

(defn queue-state
  [graph]
  (select-keys
   (get-in
    (datafy/datafy graph)
    [:chans :ins [::flow/work-launcher ::flow/compute-submission] :buffer])
   [:count :capacity]))

(defn paused-startup-defect
  []
  (let [launcher
        (flow/install-work-launcher!
         {::flow/configuration
          {:seon.config.flow.compute/queue-depth 2
           :seon.config.flow.compute/concurrency 1}})
        graph (::flow/graph launcher)
        started-at (System/nanoTime)]
    (async.flow/pause graph)
    (let [task
          (future
            (submit
             :paused-before-start
             (fn [{::flow/keys [started!]}]
               (started!)
               :ran-after-resume)
             30))
          observed (deref task 150 ::still-waiting-before-start)
          observed-at-ms (elapsed-ms started-at)
          queue (queue-state graph)]
      (async.flow/resume graph)
      (let [settled (deref task 1000 ::did-not-settle-after-resume)]
        (flow/stop-installed-work-launcher!)
        {:submit-probe/declared-time-limit-ms 30
         :submit-probe/observed-after-ms observed-at-ms
         :submit-probe/outcome-while-paused observed
         :submit-probe/queue queue
         :submit-probe/outcome-after-resume settled}))))

(defn backpressure
  []
  (let [launcher
        (flow/install-work-launcher!
         {::flow/configuration
          {:seon.config.flow.compute/queue-depth 2
           :seon.config.flow.compute/concurrency 1}})
        graph (::flow/graph launcher)]
    (async.flow/pause graph)
    (let [tasks
          (mapv
           (fn [submission-id]
             (future
               (submit
                submission-id
                (fn [{::flow/keys [started!]}]
                  (started!)
                  submission-id)
                1000)))
           [:queued-0 :queued-1 :backpressured-2])
          queue-before (loop [attempt 0]
                         (let [state (queue-state graph)]
                           (if (or (= 2 (:count state)) (= attempt 100))
                             state
                             (do
                               (Thread/yield)
                               (recur (inc attempt))))))
          before-resume
          (mapv #(deref % 25 ::blocked) tasks)]
      (async.flow/resume graph)
      (let [settled (mapv #(deref % 2000 ::did-not-settle) tasks)
            queue-after (queue-state graph)]
        (flow/stop-installed-work-launcher!)
        {:submit-probe/queue-before-resume queue-before
         :submit-probe/callers-before-resume before-resume
         :submit-probe/settled-in-order settled
         :submit-probe/queue-after-drain queue-after}))))

(defn wedged-worker-startup-defect
  []
  (let [release (CountDownLatch. 1)
        entered (CountDownLatch. 1)
        launcher
        (flow/install-work-launcher!
         {::flow/configuration
          {:seon.config.flow.compute/queue-depth 2
           :seon.config.flow.compute/concurrency 1}})
        graph (::flow/graph launcher)
        first-result
        (future
          (submit
           :wedged-worker
           (fn [{::flow/keys [started!]}]
             (started!)
             (.countDown entered)
             (.await release)
             :released)
           30))]
    (.await entered 1 TimeUnit/SECONDS)
    (let [time-limit-outcome
          (deref first-result 1000 ::first-did-not-time-out)
          observer-before
          (::async.flow/state
           (async.flow/ping-proc graph ::flow/capacity-observer))
          second-started-at (System/nanoTime)
          queued-result
          (future
            (submit
             :queued-behind-wedge
             (fn [{::flow/keys [started!]}]
               (started!)
               :ran-after-wedge-released)
             30))
          queued-observation
          (deref queued-result 150 ::still-waiting-before-start)
          queued-observed-at-ms (elapsed-ms second-started-at)]
      (.countDown release)
      (let [queued-settled
            (deref queued-result 1000 ::did-not-settle-after-release)]
        (flow/stop-installed-work-launcher!)
        {:submit-probe/first-outcome time-limit-outcome
         :submit-probe/capacity-while-wedged
         (select-keys
          observer-before
          [::flow/active-submissions
           ::flow/wedged-submissions
           ::flow/available-capacity
           ::flow/platform-threads?])
         :submit-probe/queued-declared-time-limit-ms 30
         :submit-probe/queued-observed-after-ms queued-observed-at-ms
         :submit-probe/queued-outcome-before-release queued-observation
         :submit-probe/queued-outcome-after-release queued-settled}))))

(def result
  {:submit-probe/backpressure (backpressure)
   :submit-probe/paused-startup-defect (paused-startup-defect)
   :submit-probe/wedged-worker-startup-defect
   (wedged-worker-startup-defect)})

(prn result)

(shutdown-agents)

(when (= "--expect-fixed" (first *command-line-args*))
  (let [paused
        (get-in
         result
         [:submit-probe/paused-startup-defect
          :submit-probe/outcome-while-paused])
        queued
        (get-in
         result
         [:submit-probe/wedged-worker-startup-defect
          :submit-probe/queued-outcome-before-release])]
    (when (or (= ::still-waiting-before-start paused)
              (= ::still-waiting-before-start queued))
      (throw
       (ex-info
        "submit!! still waits beyond its declared limit before work starts."
        {:submit-probe/paused paused
         :submit-probe/queued-behind-wedge queued})))))
