(ns seon.sci.interrupt
  "Own the one SCI `:interrupt-fn` and its evaluation diagnostics.

   The time flag is read at every interpreted function-body entrance.
   Allocated bytes are sampled only as a diagnostic and never as a limit."
  (:require [sci.interrupt :as sci.interrupt]
            [seon.schema :as schema])
  (:import (java.lang.management ManagementFactory)
           (java.util.concurrent ScheduledThreadPoolExecutor TimeUnit)))

(schema/register! ::time-limit-ms 'pos-int?)
(schema/register! ::final-outcome [:enum :ok :time :error])
(schema/register! ::stop! 'fn?)
(schema/register! ::record 'fn?)
(schema/register! ::started
                  [:map {:closed true}
                   [:interrupt-fn 'fn?]
                   [::stop! 'fn?]
                   [::record 'fn?]])
(schema/register! :seon.eval/fn-entries :int)
(schema/register! :seon.eval/allocated-bytes :int)
(schema/register! :seon.eval/outcome [:enum :ok :time :error])
(schema/register! ::evaluation-record
                  [:map {:closed true}
                   [:seon.eval/fn-entries :int]
                   [:seon.eval/duration-ms :int]
                   [:seon.eval/allocated-bytes :int]
                   [:seon.eval/outcome [:enum :ok :time :error]]])

(def ^:private thread-mx
  (ManagementFactory/getThreadMXBean))

(def ^:private allocation-sample-mask
  1023)

(defonce ^:private time-limit-timer
  (doto
   (ScheduledThreadPoolExecutor.
    1
    (reify java.util.concurrent.ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable "seon-sci-time-limit")
          (.setDaemon true)))))
    (.setRemoveOnCancelPolicy true)))

(defn allocated-bytes
  "Allocated bytes for the calling platform thread, or `-1`."
  {:malli/schema [:=> [:cat] :int]}
  []
  (.getCurrentThreadAllocatedBytes
   ^com.sun.management.ThreadMXBean thread-mx))

(defn start
  "Arm the `:interrupt-fn` and return its diagnostics operations."
  {:malli/schema
   [:=> [:cat [:map {:closed true}
               [::time-limit-ms ::time-limit-ms]]]
    ::started]}
  [{::keys [time-limit-ms]}]
  (let [entries (long-array 1)
        time-limit-reached? (volatile! false)
        sampled-allocated-bytes (long-array 1)
        outcome (volatile! nil)
        started-at (System/nanoTime)
        allocated-at-start (allocated-bytes)
        measurable-allocation? (not (neg? allocated-at-start))
        timer-task
        (.schedule time-limit-timer
                   ^Runnable #(vreset! time-limit-reached? true)
                   (long time-limit-ms)
                   TimeUnit/MILLISECONDS)
        interrupt-fn
        (fn []
          (let [entry-count (unchecked-inc (aget entries 0))]
            (aset entries 0 entry-count)
            ;; D8: this volatile read is intentionally performed on EVERY
            ;; interpreted function-body entrance, independent of sampling.
            (when @time-limit-reached?
              (vreset! outcome :time)
              (sci.interrupt/interrupt! "time-limit"))
            (when (and measurable-allocation?
                       (zero? (bit-and entry-count allocation-sample-mask)))
              (aset sampled-allocated-bytes 0
                    (- (allocated-bytes) allocated-at-start)))))
        stop! (fn [] (.cancel timer-task false))
        record
        (fn [final-outcome]
          {:seon.eval/fn-entries (aget entries 0)
           :seon.eval/duration-ms
           (quot (- (System/nanoTime) started-at) 1000000)
           :seon.eval/allocated-bytes
           (if measurable-allocation?
             (max (aget sampled-allocated-bytes 0)
                  (- (allocated-bytes) allocated-at-start))
             -1)
           :seon.eval/outcome (or @outcome final-outcome)})]
    {:interrupt-fn interrupt-fn
     ::stop! stop!
     ::record record}))

(defn interrupted?
  "Whether a throwable carries SCI's uncatchable interrupt marker."
  {:malli/schema [:=> [:catn [::throwable 'some?]] :boolean]}
  [throwable]
  (contains? (ex-data throwable) :sci.impl/interrupt))
