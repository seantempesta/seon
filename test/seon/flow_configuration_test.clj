(ns ^{:seon.test/platform
       "Moving part: production Flow graph construction and lifecycle."}
    seon.flow-configuration-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow.spi :as flow.spi]
            [clojure.test :refer [deftest is]]
            [seon.cluster]
            [seon.cluster.agent]
            [seon.flow :as sut]
            [seon.test-support :as test-support])
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

(def ^:private test-environment
  (delay (test-support/environment "seon.flow-configuration-test")))

(deftest proc-construction-refuses-the-mixed-scaling-cliff
  (let [constructor (private-var 'seon.flow 'var-process)]
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"must declare either :io or :compute"
      (constructor #'inert-step :mixed
                   {:seon.env/environment @test-environment})))))

(deftest every-built-graph-proc-declares-a-specific-workload
  (let [compute-executor (sut/bounded-platform-executor 1)
        fault-channel (async/chan)
        completion (async/promise-chan)]
    (try
      (let [graph-definitions
            [[:work-launcher
              ((private-var 'seon.flow 'work-launcher-graph-definition)
               {:seon.env/environment @test-environment
                ::sut/parallelism 1
                ::sut/active-work (atom {})
                ::sut/queue-depth 1
                ::sut/compute-executor compute-executor
                ::sut/task-executor compute-executor
                ::sut/io-parallelism 1
                ::sut/io-queue-depth 1
                ::sut/io-submissions (atom {})
                ::sut/proc-stopped (promise)})]
             [:fault
              ((private-var 'seon.flow 'fault-graph-definition)
               {:seon.env/environment @test-environment
                ::sut/fault-channel fault-channel
                ::sut/completion completion
                ::sut/read-core-error-mode (constantly :record)
                ::sut/commit-fault! identity
                ::sut/panic! identity})]
             [:cluster
              ;; the render proc joins the census automatically: it is
              ;; built through `var-process`, which refuses `:mixed` at
              ;; construction, and `describe` needs no live channels
              ((private-var 'seon.cluster 'cluster-graph-definition)
               {:seon.env/environment @test-environment} (atom {}) {})]
             [:agent
              (seon.cluster.agent/graph-definition
               {:seon.cluster.loop/cluster
                {:seon.env/environment @test-environment
                 :seon.cluster.wake/channel
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
