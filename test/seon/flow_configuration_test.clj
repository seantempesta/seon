(ns seon.flow-configuration-test
  (:require [clojure.string :as str]
            [clojure.core.async.flow.spi :as flow.spi]
            [clojure.test :refer [deftest is]]
            [seon.cluster]
            [seon.cluster.agent]
            [seon.flow :as sut]
            [seon.test-support :as test-support]
            [seon.print.value])
  (:import [java.util.concurrent Executors ExecutorService]))

(defn- private-var
  [namespace-symbol function-name]
  (or (ns-resolve namespace-symbol function-name)
      (throw
       (ex-info
        "The expected graph-definition owner is absent."
        {::namespace namespace-symbol
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

(deftest ^{:seon.test/platform
           "Moving part: production Flow construction refuses the mixed workload."}
  proc-construction-refuses-the-mixed-scaling-cliff
  ;; THE MIXED WORKLOAD IS UNCONSTRUCTABLE, and under the contracts every
  ;; cluster arms (and the gate now arms) the DECLARED contract is what says
  ;; so first — a typed value naming the function, the member and the
  ;; offending workload, which is the refusal law 2.4 asks for.
  (let [constructor (private-var 'seon.flow 'var-process)
        refusal (test-support/refusal-data
                 (fn []
                   (constructor #'inert-step :mixed
                                {:seon.env/environment @test-environment})))]
    (is (= :input (:seon.instrument/check refusal)))
    (is (= 'seon.flow/var-process
           (:seon.error/operation refusal)))
    (is (str/includes? (:seon.error/message refusal)
                       "either :io or :compute"))))

;; Graph definitions reference schedule execution and its root cleanup owner.
;; This census belongs to the ordinary tier under the same reach rule.
(deftest every-built-graph-proc-declares-a-specific-workload
  ;; the fault committer carries the schema projection, so this census
  ;; hands one over explicitly, like the production caller
  (test-support/with-database
    (fn [connection]
      (let [environment (test-support/environment
                         "seon.flow-configuration-test" connection)
            task-executor (Executors/newVirtualThreadPerTaskExecutor)]
        (try
          (let [graph-definitions
                [[:work-launcher
                  ((private-var 'seon.flow 'work-launcher-graph-definition)
                   {:seon.env/environment environment
                    ::sut/active-work (atom {})
                    ::sut/task-executor task-executor
                    ::sut/io-parallelism 1
                    ::sut/io-queue-depth 1
                    ::sut/io-submissions (atom {})})]
                 [:cluster
                  ;; the render proc joins the census automatically: it is
                  ;; built through `var-process`, which refuses `:mixed` at
                  ;; construction, and `describe` needs no live channels
                  ((private-var 'seon.cluster 'cluster-graph-definition)
                   {:seon.env/environment environment} (atom {}) {})]
                 [:agent
                  ;; EVERY DECLARED MEMBER of the cluster handle, like the
                  ;; production caller: the blueprint is pure data, but its
                  ;; declared request is the handle a running cluster owns.
                  (seon.cluster.agent/graph-definition
                   {:seon.turn.loop/cluster
                    (test-support/cluster-handle
                     {:seon.env/environment environment
                      :seon.db/connection connection
                      :seon.cluster/name "seon.flow-configuration-test"
                      :seon.db.process/id "census-0"
                      :seon.sci.eval/ctx
                      (test-support/fork-cluster-ctx connection)
                      :seon.config.eval/time-limit-ms 1000
                      :seon.config.error/recurrence-limit 3
                      :seon.config.message/max-chain 64})
                    :seon.agent/id "census"})]]
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
            (is (every? #(contains? #{:io :compute} (::workload %))
                        proc-facts)
                (seon.print.value/bounded-text proc-facts)))
          (finally
            (.shutdownNow ^ExecutorService task-executor)))))))
