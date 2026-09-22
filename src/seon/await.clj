(ns seon.await
  "One bounded owner for synchronous completion awaits."
  (:require [clojure.core.async :as async]
            [clojure.test.check.generators :as gen]
            [seon.error.refusal :as error]
            [seon.schema :as schema])
  (:import [clojure.lang IBlockingDeref]
           [java.util.concurrent Future TimeUnit TimeoutException]))

(set! *warn-on-reflection* true)

(defn blocking-deref?
  "True when a value supports Clojure's bounded blocking dereference."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
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
  {:malli/schema
   [:=> [:cat :seon.await/request
         [:or [:map [:seon.await/elapsed-ms :seon.await/elapsed-ms]]
          [:map [:seon.await/closed-operation :seon.await/closed-operation]
           [:seon.await/operation-index :seon.await/operation-index]]]]
    [:or :seon.await/timeout-error :seon.await/closed-error]]}
  [request outcome]
  (let [{attribute :seon.await/config-attribute
         backstop-ms :seon.await/config-value}
        (:seon.await/bound request)
        observation (:seon.await/diagnostic request)]
    (merge
     (dissoc observation :seon.error/member)
     (when-let [member (:seon.error/member observation)]
       {:seon.await/requested-member member})
     outcome
     {:seon.error/at (java.util.Date.)
      :seon.error/layer :seon.await/completion
      :seon.error/operation 'seon.await/diagnostic
      :seon.await/config-attribute attribute
      :seon.await/config-value backstop-ms
      :seon.await/requested-layer (:seon.error/layer observation)
      :seon.await/requested-operation (:seon.error/operation observation)
      :seon.error/message
      (if (:seon.await/closed-operation outcome)
        "The awaited channel closed before completion. Fix: publish the completion before closing the channel."
        "The declared await bound fired before completion. Fix: inspect the awaited operation and its bound.")})))

(defn- timeout-observation
  {:malli/schema [:=> [:cat :seon.await/request :int] :seon.await/timeout-error]}
  [request started]
  (diagnostic request {:seon.await/elapsed-ms
                       (/ (double (- (System/nanoTime) started)) 1000000.0)}))

(defn- remaining-ms
  {:malli/schema [:=> [:cat :int] [:maybe [:int {:min 1}]]]}
  [deadline-nanos]
  (let [remaining (- deadline-nanos (System/nanoTime))]
    (when (pos? remaining)
      (max 1 (long (Math/ceil (/ (double remaining) 1000000.0)))))))

(defn- closed-operation?
  {:malli/schema [:=> [:cat :seon.await/port-operation :seon.schema/value] :boolean]}
  [operation value]
  (if (vector? operation)
    (false? value)
    (nil? value)))

(defn- await-port-operations
  {:malli/schema [:=> [:cat :seon.await/port-request :int]
                  [:or :seon.schema/value :seon.error/base
                   :seon.await/timeout-error :seon.await/closed-error]]}
  [{operations :seon.await/port-operations
    accept? :seon.await/accept?
    {backstop-ms :seon.await/config-value} :seon.await/bound
    :as request} started]
  (let [deadline-nanos (+ started (* 1000000 backstop-ms))]
    (loop [remaining-operations operations]
      (if-let [backstop-ms (remaining-ms deadline-nanos)]
        (let [operation (first remaining-operations)
              backstop (async/timeout backstop-ms)
              [value selected]
              (async/alts!! [operation backstop] :priority true)]
          (cond
            (= selected backstop)
            (timeout-observation request started)

            (closed-operation? operation value)
            (diagnostic request
                        {:seon.await/closed-operation (if (vector? operation) :put :take)
                         :seon.await/operation-index (- (count operations) (count remaining-operations))})

            (next remaining-operations)
            (recur (next remaining-operations))

            (and accept? (not (accept? value)))
            (recur remaining-operations)

            :else value))
        (timeout-observation request started)))))

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
    ;; Completion is genuinely polymorphic and preserves complete errors from
    ;; arbitrary work. Only this boundary's own failures have await declared-schemas.
    [:or :seon.schema/value :seon.error/base
     :seon.await/timeout-error :seon.await/closed-error]]}
  [{java-future :seon.await/future
    blocking-deref :seon.await/blocking-deref
    {backstop-ms :seon.await/config-value} :seon.await/bound
    :as request}]
  (let [started (System/nanoTime)]
   (cond
    (:seon.await/port-operations request)
    (await-port-operations request started)

    java-future
    (try
      (.get ^Future java-future (long backstop-ms) TimeUnit/MILLISECONDS)
      (catch TimeoutException _
        (timeout-observation request started)))

    blocking-deref
    (let [value (deref blocking-deref (long backstop-ms) timed-out)]
      (if (identical? timed-out value)
        (timeout-observation request started)
        value)))))
