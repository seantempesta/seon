(ns seon.sci.eval
  "Evaluate one source form on a bounded `:compute` platform thread."
  (:require [sci.core :as sci]
            [seon.schema :as schema]
            [seon.sci.ctx :as ctx]
            [seon.sci.interrupt :as interrupt])
  (:import (java.util.concurrent Callable Executors Semaphore TimeUnit
                                 TimeoutException)))

(defn evaluation-value?
  "Whether a value is a possible result from SCI evaluation."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [_]
  true)

(schema/register! ::source :string)
(schema/register! ::base-ctx 'some?)
(schema/register! ::concurrency 'pos-int?)
(schema/register! ::semaphore-wait-ms :int)
(schema/register! ::value 'seon.sci.eval/evaluation-value?)
(schema/register! ::record :map)
(schema/register! ::evaluation
                  [:map {:closed true}
                   [::value ::value]
                   [::record :map]])
(schema/register! ::evaluate-request
                  [:map {:closed true}
                   [::source ::source]
                   [::base-ctx {:optional true} ::base-ctx]
                   [:seon.sci.interrupt/time-limit-ms
                    :seon.sci.interrupt/time-limit-ms]])

(defonce ^:private compute-pool
  (Executors/newCachedThreadPool
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. runnable "seon-sci-compute")
         (.setDaemon true))))))

(defonce ^:private permits
  (atom nil))

(defn open!
  "Set the number of concurrent SCI evaluations."
  {:malli/schema
   [:=> [:cat [:map {:closed true} [::concurrency ::concurrency]]]
    :nil]}
  [{::keys [concurrency]}]
  (reset! permits (Semaphore. (int concurrency)))
  nil)

(defn available
  "Available concurrent SCI evaluation permits."
  {:malli/schema [:=> [:cat] :int]}
  []
  (.availablePermits ^Semaphore @permits))

(defn- diagnosis
  [throwable {:seon.eval/keys [outcome duration-ms]}]
  (case outcome
    :time
    (format "Ran out of time after %dms." duration-ms)

    (or (.getMessage ^Throwable throwable)
        (.getName (class throwable)))))

(defn- error-value
  [throwable record]
  (let [exception-data (ex-data throwable)]
    {:seon.error/message (diagnosis throwable record)
     :seon.error/kind (:seon.eval/outcome record)
     :seon.error/data
     (cond->
      {:seon.sci.eval/throwable-class (.getName (class throwable))
       :seon.sci.eval/record record}
       (.getMessage ^Throwable throwable)
       (assoc :seon.sci.eval/raw-message
              (.getMessage ^Throwable throwable))
       (:sci.impl/symbol exception-data)
       (assoc :sci.impl/symbol (:sci.impl/symbol exception-data)))}))

(defn evaluate
  "Evaluate one source form and return a value plus diagnostics."
  {:malli/schema [:=> [:cat ::evaluate-request] ::evaluation]}
  [{::keys [source base-ctx]
    time-limit-ms :seon.sci.interrupt/time-limit-ms}]
  (let [semaphore ^Semaphore @permits
        waiting-at (System/nanoTime)]
    (when-not semaphore
      (throw
       (ex-info "seon.sci.eval/open! must be called before evaluate."
                {:seon.error/kind :configuration})))
    (.acquire semaphore)
    (let [semaphore-wait-ms
          (quot (- (System/nanoTime) waiting-at) 1000000)
          {:keys [interrupt-fn]
           stop! ::interrupt/stop!
           record ::interrupt/record}
          (interrupt/start
           {::interrupt/time-limit-ms time-limit-ms})
          evaluation-ctx
          (ctx/fork
           (cond-> {:interrupt-fn interrupt-fn}
             base-ctx (assoc ::ctx/base-ctx base-ctx)))
          task
          (.submit
           ^java.util.concurrent.ExecutorService compute-pool
           ^Callable
           (fn []
             (try
               ;; D7: parse inside the armed SCI context. `#=` is refused
               ;; by SCI's reader and never reaches host evaluation.
               (let [form (sci/parse-string evaluation-ctx source)
                     value (sci/eval-form evaluation-ctx form)]
                 {::value value
                  ::record (record :ok)})
               (catch Throwable throwable
                 (let [evaluation-record
                       (record
                        (if (interrupt/interrupted? throwable)
                          :time
                          :error))]
                   {::value (error-value throwable evaluation-record)
                    ::record evaluation-record}))
               (finally
                 (stop!)
                 ;; A blocked host call consumes exactly this one permit until
                 ;; the platform thread actually returns. Other permits remain
                 ;; usable, so one wedge cannot release imaginary capacity.
                 (.release semaphore)))))]
      (try
        (-> (.get task (long time-limit-ms) TimeUnit/MILLISECONDS)
            (assoc-in [::record ::semaphore-wait-ms] semaphore-wait-ms))
        (catch TimeoutException throwable
          (let [evaluation-record (record :time)]
            {::value (error-value throwable evaluation-record)
             ::record
             (assoc evaluation-record
                    ::semaphore-wait-ms semaphore-wait-ms)}))))))

(defn error?
  "Whether a value is Seon's flat error value."
  {:malli/schema [:=> [:catn [::candidate ::value]] :boolean]}
  [candidate]
  (and (map? candidate)
       (contains? candidate :seon.error/message)))
