(ns seon.dev.harness-costs
  "Cost measurements retired from the test suite (one-test-harness S12).

  They measure and print; they never assert, so a slow result is read here,
  not reported as a red test. Evaluate at the development REPL with an
  explicit database value:

    (require 'seon.dev.harness-costs)
    (seon.dev.harness-costs/measure
     (seon.db/db (seon.cluster.boot/connection \"default\")))

  A warm validator acquisition near one microsecond is the populated Malli
  cache; a regression to recompiling shows as hundreds of microseconds."
  (:require [malli.core :as m]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.db :as db]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.sci.kernel :as kernel]))

(defn- mean-us
  "Mean microseconds of `samples` calls of `f` after a warm-up."
  {:malli/schema [:=> [:cat fn? pos-int?] :double]}
  [f samples]
  (dotimes [_ (quot samples 5)] (f))
  (let [start (System/nanoTime)]
    (dotimes [_ samples] (f))
    (/ (double (- (System/nanoTime) start)) samples 1000.0)))

(defn- elapsed-ms
  "Milliseconds of one call of `f`."
  {:malli/schema [:=> [:cat fn?] :double]}
  [f]
  (let [start (System/nanoTime)]
    (f)
    (/ (double (- (System/nanoTime) start)) 1e6)))

(def ^:private contract-shapes
  [[:scalar [:=> [:cat :int] :int] 1]
   [:ordinary-map [:=> [:cat :map] :map] {::value 1}]
   [:declared-error [:=> [:cat :map] [:or :map :seon.agent/error]]
    {:seon.error/at #inst "2026-09-18T00:00:00Z" :seon.error/layer ::benchmark
     :seon.error/operation 'user/benchmark :seon.agent/error-agent-id "benchmark"}]])

(defn- plain-malli
  "Malli's own instrumentation of `original` under `contract`: the baseline."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/value fn?] fn?]}
  [projection contract original]
  (m/-instrument {:schema contract :scope #{:input :output :guard}
                  :report (fn [kind data] (throw (ex-info (str kind) data)))}
                 original (:seon.schema.projection/compile-options projection)))

(defn- wrapper-overhead
  "Per-call microseconds of Malli's wrapper and Seon's host and SCI wrappers."
  {:malli/schema [:=> [:cat :seon.schema/projection] [:vector :map]]}
  [projection]
  (let [caps (config/result-caps config/defaults)
        ctx (sci.eval/build-base-ctx projection)
        interpreted (sci/eval-string* ctx "(fn [value] value)")]
    (kernel/with-arm
     ctx 60000
     (fn [_]
       (mapv (fn [[label contract value]]
               (let [malli (plain-malli projection contract identity)
                     host (binding [instrument/*compiling-contract* true]
                            (#'instrument/compiled-wrapper
                             projection 'user/benchmark contract identity caps
                             {}))
                     sci-malli (plain-malli projection contract interpreted)
                     sci-wrapped (instrument/wrap-interpreted
                                  'user/benchmark (pr-str contract) projection
                                  caps interpreted)]
                 {::shape label
                  ::malli-us (mean-us #(malli value) 20000)
                  ::host-us (mean-us #(host value) 20000)
                  ::sci-malli-us (mean-us #(sci-malli value) 20000)
                  ::sci-us (mean-us #(sci-wrapped value) 20000)}))
             contract-shapes)))))

(defn measure
  "The harness cost numbers for one database value."
  {:malli/schema [:=> [:cat :seon.db/database-value] :map]}
  [database]
  (let [projection (db/carried-projection database)
        forms (:seon.schema.projection/forms projection)
        result
        {::schemas (count forms)
         ::validator-acquisition-us
         (mean-us #(schema/projection-validator projection :seon.agent/id) 1000)
         ::explainer-acquisition-us
         (mean-us #(schema/projection-explainer projection :seon.agent/id) 1000)
         ::projection-build-ms (elapsed-ms #(schema/build-projection forms))
         ::wrappers (wrapper-overhead projection)}]
    result))
