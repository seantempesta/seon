(ns seon.flow-configuration-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow.spi :as flow.spi]
            [clojure.test :refer [deftest is]]
            [seon.cluster]
            [seon.cluster.agent]
            [seon.flow :as sut])
  (:import [java.util.concurrent ExecutorService]))

(defn- private-var
  [namespace function-name]
  (or (ns-resolve namespace function-name)
      (throw
       (ex-info
        "The expected graph-definition owner is absent."
        {::namespace namespace
         ::function-name function-name}))))

(defn- inert-step
  ([]
   {:workload :io})
  ([args]
   args)
  ([state _transition]
   state)
  ([state _input _message]
   [state nil]))

(deftest proc-construction-refuses-the-mixed-scaling-cliff
  (let [constructor (private-var 'seon.flow 'var-process)]
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"must declare either :io or :compute"
      (constructor #'inert-step :mixed {})))))

(deftest every-built-graph-proc-declares-a-specific-workload
  (let [compute-executor (sut/bounded-platform-executor 1)
        fault-channel (async/chan)
        completion (async/promise-chan)]
    (try
      (let [graph-definitions
            [[:work-launcher
              ((private-var 'seon.flow 'work-launcher-graph-definition)
               {::sut/parallelism 1
                ::sut/active-work (atom {})
                ::sut/queue-depth 1
                ::sut/compute-executor compute-executor})]
             [:fault
              ((private-var 'seon.flow 'fault-graph-definition)
               {::sut/fault-channel fault-channel
                ::sut/completion completion
                ::sut/read-core-error-mode (constantly :record)
                ::sut/commit-fault! identity
                ::sut/panic! identity})]
             [:cluster
              ((private-var 'seon.cluster 'cluster-graph-definition)
               {} (atom {}))]
             [:agent
              (seon.cluster.agent/graph-definition
               {:seon.cluster.loop/cluster
                {:seon.cluster.wake/channel
                 (async/chan (async/sliding-buffer 1))}
                :seon.cluster.agent/id "census"})]]
            proc-facts
            (into
             []
             (mapcat
              (fn [[graph-name graph-definition]]
                (map
                 (fn [[pid {:keys [proc]}]]
                   {::graph-name graph-name
                    ::pid pid
                    ::workload
                    (:workload (flow.spi/describe proc))})
                 (:procs graph-definition))))
             graph-definitions)]
        (is (seq proc-facts))
        (is (= #{:io :compute} (set (map ::workload proc-facts))))
        (is (every? #(contains? #{:io :compute} (::workload %))
                    proc-facts)
            (pr-str proc-facts)))
      (finally
        (async/close! fault-channel)
        (async/close! completion)
        (.shutdownNow ^ExecutorService compute-executor)))))
