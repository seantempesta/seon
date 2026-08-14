(ns seon.await
  "One bounded owner for synchronous completion awaits."
  (:require [clojure.core.async :as async]
            [clojure.test.check.generators :as gen]
            [seon.error :as error]
            [seon.schema :as schema])
  (:import [clojure.lang IBlockingDeref]
           [java.util.concurrent Future TimeUnit TimeoutException]))

(set! *warn-on-reflection* true)

(defn blocking-deref?
  "True when a value supports Clojure's bounded blocking dereference."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (instance? IBlockingDeref value))

(def blocking-deref-generator
  (gen/fmap (fn [value]
              (doto (promise) (deliver value)))
            (gen/large-integer* {:min -1000 :max 1000})))

(schema/register-core-predicate! 'seon.await/blocking-deref?
                                 blocking-deref?)

(def ^:private timed-out (Object.))

(defn- diagnostic
  [request cause]
  (let [{attribute :seon.await/config-attribute
         backstop-ms :seon.await/config-value}
        (:seon.await/bound request)
        observation (:seon.await/diagnostic request)
        member (:seon.error/diagnostic-member observation)
        operation (:seon.error/diagnostic-operation observation)
        evidence
        {:seon.await/config-attribute attribute
         :seon.await/config-value backstop-ms
         :seon.await/observation
         (:seon.error/diagnostic-evidence observation)}]
    (error/diagnostic
     (merge
      observation
      {:seon.error/kind cause
       :seon.error/message
       (str (pr-str member) " never arrived for " (pr-str operation)
            " within the declared " (pr-str attribute) " bound of "
            backstop-ms " ms.")
       :seon.error/diagnostic-cause cause
       :seon.error/diagnostic-evidence evidence}))))

(defn- remaining-ms
  [deadline-nanos]
  (let [remaining (- deadline-nanos (System/nanoTime))]
    (when (pos? remaining)
      (max 1 (long (Math/ceil (/ (double remaining) 1000000.0)))))))

(defn- closed-operation?
  [operation value]
  (if (vector? operation)
    (false? value)
    (nil? value)))

(defn- await-port-operations
  [{operations :seon.await/port-operations
    accept? :seon.await/accept?
    {backstop-ms :seon.await/config-value} :seon.await/bound
    :as request}]
  (let [deadline-nanos (+ (System/nanoTime) (* 1000000 backstop-ms))]
    (loop [remaining-operations operations]
      (if-let [backstop-ms (remaining-ms deadline-nanos)]
        (let [operation (first remaining-operations)
              backstop (async/timeout backstop-ms)
              [value selected]
              (async/alts!! [operation backstop] :priority true)]
          (cond
            (= selected backstop)
            (diagnostic request ::backstop-fired)

            (closed-operation? operation value)
            (diagnostic request ::completion-closed)

            (next remaining-operations)
            (recur (next remaining-operations))

            (and accept? (not (accept? value)))
            (recur remaining-operations)

            :else value))
        (diagnostic request ::backstop-fired)))))

(defn await!
  "Await one exact completion event under its carried config fact.

  The request must identify the config attribute and its positive millisecond
  value; this owner never invents or defaults a bound. Core.async operations
  are raced with `timeout` through `alts!!`, preserving put/take semantics and
  one deadline across a sequential request/reply or filtered take. Java
  futures use their timed `get`, and Clojure promises use bounded `deref`.

  Completion returns the event's value. Expiry or a port closing before the
  expected event returns one evidence-complete `:seon.error` value naming what
  never arrived, the operation waiting for it, and the exact config fact. The
  caller still owns cleanup of its exact task, reply channel, or response."
  {:malli/schema
   [:=> [:cat :seon.await/request]
    [:or :seon.schema/value :seon.error/value]]}
  [{java-future :seon.await/future
    blocking-deref :seon.await/blocking-deref
    {backstop-ms :seon.await/config-value} :seon.await/bound
    :as request}]
  (cond
    (:seon.await/port-operations request)
    (await-port-operations request)

    java-future
    (try
      (.get ^Future java-future (long backstop-ms) TimeUnit/MILLISECONDS)
      (catch TimeoutException _
        (diagnostic request ::backstop-fired)))

    blocking-deref
    (let [value (deref blocking-deref (long backstop-ms) timed-out)]
      (if (identical? timed-out value)
        (diagnostic request ::backstop-fired)
        value))))
