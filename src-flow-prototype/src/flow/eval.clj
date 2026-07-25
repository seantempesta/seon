(ns flow.eval
  "Evaluate ONE form on a :compute thread. Returns a value or a flat
   :seon/error. Never throws into the loop.

   :compute / :io are core.async's own tags (impl/dispatch.clj:122-134).
   :compute must never block, so it gets PLATFORM threads -- load-bearing:
   allocation is only measurable there, and the interrupt-fn must be ARMED
   ON THAT THREAD or it measures the caller's allocation instead.
   The bound is a semaphore whose permit count is a config fact; at
   exhaustion the :io caller BLOCKS (queue, never bounce the claim)."
  (:require [flow.ctx :as ctx]
            [flow.interrupt :as interrupt]
            [sci.core :as sci])
  (:import (java.util.concurrent Callable Executors Semaphore)))

(defonce ^:private compute-pool
  (Executors/newCachedThreadPool
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ r] (doto (Thread. r) (.setName "flow-compute") (.setDaemon true))))))

(defonce ^:private permits (atom nil))

(defn open! [n] (reset! permits (Semaphore. (int n))))
(defn available [] (.availablePermits ^Semaphore @permits))

(defn- diagnose
  "Same fact, two messages. Many entries inside the limit reads as a loop that
   never ends; almost none reads as blocked inside one host call."
  [{:seon.eval/keys [outcome fn-entries ms allocated-bytes]}]
  (case outcome
    :time (if (> fn-entries 1000000)
            (format "Ran out of time after %dms: %,d fn entries -- this looks like a loop that never ends." ms fn-entries)
            (format "Ran out of time after %dms with only %,d fn entries -- this looks like a call into host code that blocked." ms fn-entries))
    :memory (format "Allocated %,d bytes, past the limit, in %dms." allocated-bytes ms)
    (format "Threw after %dms." ms)))

(defn evaluate
  "source -> {:flow/value v :flow/record r}. `db` is this step's basis."
  [{:keys [source db time-limit-ms allocation-limit-bytes]}]
  (let [sem ^Semaphore @permits
        parked (System/nanoTime)
        _ (.acquire sem)
        waited (quot (- (System/nanoTime) parked) 1000000)]
    (try
      (-> (.get (.submit
                 ^java.util.concurrent.ExecutorService compute-pool
                 ^Callable
                 (fn []
                   (let [{:keys [interrupt-fn record stop]}
                         (interrupt/start {:time-limit-ms time-limit-ms
                                           :allocation-limit-bytes allocation-limit-bytes})
                         ctx (ctx/fork interrupt-fn)]
                     (binding [ctx/*db* db]
                       (try (let [v (sci/eval-form ctx (read-string source))]
                              (stop)
                              {:flow/value v :flow/record (record :ok)})
                            (catch Throwable t
                              (stop)
                              (let [r (record (if (interrupt/interrupted? t) :time :error))]
                                {:flow/value {:seon.error/message (diagnose r)
                                              :seon.error/kind (:seon.eval/outcome r)
                                              :seon.error/raw (.getMessage t)
                                              :seon.error/record r}
                                 :flow/record r}))))))))
          (assoc-in [:flow/record :flow/semaphore-wait-ms] waited))
      (finally (.release sem)))))

(defn error? [v] (and (map? v) (contains? v :seon.error/message)))
