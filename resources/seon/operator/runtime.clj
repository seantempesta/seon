(ns seon.operator.runtime
  "Process-root custody that is absent from every cluster program graph.

  This namespace deliberately lives on the runtime classpath outside the
  indexed `src` and `test` roots. Cluster code may refer to these Vars, but a
  cluster database has no `:seon.ns` row for this namespace, so SCI acquisition
  never installs it."
  (:require [clojure.core.async.impl.dispatch :as async.dispatch]
            [seon.flow :as flow]))

(defonce running-instances (atom {}))

(defonce root-store-holder (atom {}))

(defonce held-flocks (atom {}))

(defonce root-executor-pair
  (delay
    {:compute
     (flow/bounded-platform-executor
      (.availableProcessors (Runtime/getRuntime)))
     :io (async.dispatch/executor-for :io)}))

(defn root-executors
  "The process root's shared compute and I/O executors."
  {:malli/schema [:=> [:cat] :seon.boot/executors]}
  []
  @root-executor-pair)
