(ns seon.cluster.resume-artifact-routing-test
  "X2 keeps interrupted process history out of namespace-owner routing."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.cluster.message :as my.message]
            [seon.cluster.message :as message]
            [seon.turn :as turn]
            [seon.problems :as problems]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(def ^:private now (Date. 1785283200000))
(def ^:private run-id "resume-artifact-run")

(def ^:private failed
  {:seon.sci.admit/value
   {
    :seon.error/message "Unable to resolve symbol: prefix-def"
    :seon.error/data {}}
   :seon.eval/shown
   (pr-str {
            :seon.error/message "Unable to resolve symbol: prefix-def"
            :seon.error/data {}})
   :seon.cluster.eval/error "Unable to resolve symbol: prefix-def"
   :seon.sci.admit/record
   {:seon.eval/fn-entries 1
    :seon.eval/duration-ms 1
    :seon.eval/allocated-bytes 1
    :seon.eval/outcome :error}})

(deftest resume-artifacts-stay-red-and-are-excluded-from-owner-routing
  (test-support/with-database
   (fn [connection]
     (test-support/transacted!
                  connection
                  [{:seon.ns/name 'my.gen.planner}
                   {:seon.ns/name 'my.gen.alpha}
                   {:seon.agent/id "planner"
                    :seon.agent/namespace [:seon.ns/name 'my.gen.planner]}
                   {:seon.agent/id "alpha"
                    :seon.agent/namespace [:seon.ns/name 'my.gen.alpha]}
                   {:seon.turn/id run-id :seon.turn/agent [:seon.agent/id "planner"] :seon.turn/opened-tx "datomic.tx"}])
     (test-support/transacted!
                  connection
                  ;; ONE ENTITY PER (run, ordinal): the frozen source and the terminal
                  ;; facts are the same evaluation.
                  [{:seon.cluster.eval/id "resume-receipt-0"
                    :seon.problems/id "resume-problem-0"
                    :seon.cluster.eval/run [:seon.turn/id run-id]
                    :seon.cluster.eval/ordinal 0
                    :seon.cluster.eval/source "(def prefix-def 1)"
                    :seon.cluster.eval/ns [:seon.ns/name 'my.gen.alpha]
                    :seon.cluster.eval/at now
                    :seon.cluster.eval/interrupted-at now}
                   {:seon.cluster.eval/id "resume-receipt-1"
                    :seon.problems/id "resume-problem-1"
                    :seon.cluster.eval/run [:seon.turn/id run-id]
                    :seon.cluster.eval/ordinal 1
                    :seon.cluster.eval/source "prefix-def"
                    :seon.cluster.eval/ns [:seon.ns/name 'my.gen.alpha]
                    :seon.cluster.eval/at now}])
     (is (nil?
          (problems/form-problem
           @connection
           {:seon.turn/id run-id
            :seon.cluster.eval/ordinal 1
            :seon.sci.eval/evaluation failed}))
         "one X2 clause prevents process-history breakage becoming owner blame")
     (test-support/transacted! connection
                             [[:db/add [:seon.cluster.eval/id "resume-receipt-1"]
                               :seon.eval/shown
                               (:seon.eval/shown failed)]
                              [:db/add [:seon.cluster.eval/id "resume-receipt-1"]
                               :seon.cluster.eval/error
                               (:seon.cluster.eval/error failed)]
                              [:db/add [:seon.cluster.eval/id "resume-receipt-1"]
                               :seon.cluster.eval/error
                               (:seon.cluster.eval/error failed)]])
     (let [delivery
           (message/delivery
            @connection
            {:my.message/value (seon.cluster.message/send "alpha" "stale assignment" "resume-problem-1") :seon.agent/id "planner" :seon.turn/id "stale-assignment-run" :seon.cluster.eval/ordinal 0 :seon.config.message/max-chain 16})]
       (test-support/transacted! connection (:seon.message/rows delivery)))
     (is (= :unrouted-red
            (:seon.turn.work/form-state
             (turn/form-settlement @connection "resume-receipt-1")))
         "even a stale/manual assignment cannot turn X2 into routed"))))
