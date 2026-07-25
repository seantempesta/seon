(ns flow.interrupt
  "The one `:interrupt-fn` (sci/doc/interrupt.md). Fires at every fn body
   entrance and every recur (sci/impl/fns.cljc:52).

   Time is a volatile flag set by ONE scheduled timer, never a clock poll.
   Allocation is sampled every 1024 entries; it is only readable on a
   PLATFORM thread (getCurrentThreadAllocatedBytes returns -1 on a virtual
   thread, JDK ThreadImpl.java:347)."
  (:require [sci.interrupt :as interrupt])
  (:import (java.lang.management ManagementFactory)
           (java.util.concurrent Executors TimeUnit)))

(def ^:private mx (ManagementFactory/getThreadMXBean))
(def ^:private sample-mask 1023)

(defn allocated-bytes
  "Bytes allocated by the calling thread, or -1 when unmeasurable."
  []
  (.getCurrentThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx))

(defonce ^:private timer
  (Executors/newSingleThreadScheduledExecutor
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ r] (doto (Thread. r "flow-time-limit") (.setDaemon true))))))

(defn start
  "Arm one interrupt-fn. Returns {:interrupt-fn f :record (fn [outcome]) :stop f}.
   The record is the whole observability surface: fn-entries, ms,
   allocated-bytes, outcome."
  [{:keys [time-limit-ms allocation-limit-bytes]}]
  (let [entries (long-array 1)
        expired (volatile! false)
        peak (long-array 1)
        outcome (volatile! nil)
        t0 (System/nanoTime)
        a0 (allocated-bytes)
        task (.schedule timer ^Runnable #(vreset! expired true)
                        (long time-limit-ms) TimeUnit/MILLISECONDS)
        ifn (fn interrupt-fn []
              (let [n (unchecked-inc (aget entries 0))]
                (aset entries 0 n)
                (when (zero? (bit-and n sample-mask))
                  (when @expired
                    (vreset! outcome :time)
                    (interrupt/interrupt! "time-limit"))
                  (when (nat-int? a0)
                    (let [used (- (allocated-bytes) a0)]
                      (aset peak 0 used)
                      (when (and allocation-limit-bytes (> used (long allocation-limit-bytes)))
                        (vreset! outcome :memory)
                        (interrupt/interrupt! "allocation-limit")))))))]
    {:interrupt-fn ifn
     :stop (fn [] (.cancel task false))
     :record (fn [final]
               {:seon.eval/fn-entries (aget entries 0)
                :seon.eval/ms (quot (- (System/nanoTime) t0) 1000000)
                :seon.eval/allocated-bytes (if (nat-int? a0)
                                             (max (aget peak 0) (- (allocated-bytes) a0))
                                             -1)
                :seon.eval/outcome (or @outcome final)})}))

(defn interrupted?
  "True when the throwable is sci's uncatchable interrupt marker."
  [t]
  (contains? (ex-data t) :sci.impl/interrupt))
