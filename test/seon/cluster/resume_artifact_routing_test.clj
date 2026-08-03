(ns seon.cluster.resume-artifact-routing-test
  "X2 keeps interrupted process history out of namespace-owner routing."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [my.message :as my.message]
            [seon.cluster.message :as message]
            [seon.cluster.work :as work]
            [seon.problems :as problems]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(def ^:private now (Date. 1785283200000))
(def ^:private run-id "resume-artifact-run")

(def ^:private failed
  {:seon.sci.admit/value
   {:seon.error/kind :seon.sci.eval/evaluation-failed
    :seon.error/message "Unable to resolve symbol: prefix-def"
    :seon.error/data {}}
   :seon.cluster.eval/result-edn
   (pr-str {:seon.error/kind :seon.sci.eval/evaluation-failed
            :seon.error/message "Unable to resolve symbol: prefix-def"
            :seon.error/data {}})
   :seon.cluster.eval/error "Unable to resolve symbol: prefix-def"
   :seon.sci.admit/capped? false
   :seon.sci.admit/record
   {:seon.eval/fn-entries 1
    :seon.eval/duration-ms 1
    :seon.eval/allocated-bytes 1
    :seon.eval/outcome :error}})

(deftest resume-artifacts-stay-red-and-are-excluded-from-owner-routing
  (test-support/with-database
   (fn [connection]
     (db/transact!
      connection
      [{:seon.ns/name 'my.gen.planner}
       {:seon.ns/name 'my.gen.alpha}
       {:seon.cluster.agent/id "planner"
        :seon.cluster.agent/namespace [:seon.ns/name 'my.gen.planner]}
       {:seon.cluster.agent/id "alpha"
        :seon.cluster.agent/namespace [:seon.ns/name 'my.gen.alpha]}
       {:seon.cluster.run/id run-id
        :seon.cluster.run/agent [:seon.cluster.agent/id "planner"]
        :seon.cluster.run/opened-at now
        :seon.cluster.run/plan-digest "resume-artifact-digest"}])
     (db/transact!
      connection
      [{:seon.cluster.run.form/id "resume-form-0"
        :seon.cluster.run.form/run [:seon.cluster.run/id run-id]
        :seon.cluster.run.form/ordinal 0
        :seon.cluster.run.form/source "(def prefix-def 1)"
        :seon.cluster.run.form/ns [:seon.ns/name 'my.gen.alpha]}
       {:seon.cluster.run.form/id "resume-form-1"
        :seon.cluster.run.form/run [:seon.cluster.run/id run-id]
        :seon.cluster.run.form/ordinal 1
        :seon.cluster.run.form/source "prefix-def"
        :seon.cluster.run.form/ns [:seon.ns/name 'my.gen.alpha]}
       {:seon.cluster.eval/id "resume-receipt-0"
        :seon.problems/id "resume-problem-0"
        :seon.cluster.eval/run [:seon.cluster.run/id run-id]
        :seon.cluster.eval/ordinal 0
        :seon.cluster.eval/at now
        :seon.cluster.eval/interrupted-at now}
       {:seon.cluster.eval/id "resume-receipt-1"
        :seon.problems/id "resume-problem-1"
        :seon.cluster.eval/run [:seon.cluster.run/id run-id]
        :seon.cluster.eval/ordinal 1
        :seon.cluster.eval/at now}])
     (is (nil?
          (problems/form-problem
           @connection
           {:seon.cluster.run/id run-id
            :seon.cluster.run.form/ordinal 1
            :seon.sci.eval/evaluation failed}))
         "one X2 clause prevents process-history breakage becoming owner blame")
     (db/transact! connection
                 [[:db/add [:seon.cluster.eval/id "resume-receipt-1"]
                   :seon.cluster.eval/result-edn
                   (:seon.cluster.eval/result-edn failed)]
                  [:db/add [:seon.cluster.eval/id "resume-receipt-1"]
                   :seon.cluster.eval/error
                   (:seon.cluster.eval/error failed)]
                  [:db/add [:seon.cluster.eval/id "resume-receipt-1"]
                   :seon.error/kind
                   :seon.sci.eval/evaluation-failed]])
     (let [delivery
           (message/delivery
            @connection
            {:my.message/value
             (my.message/send "alpha" "stale assignment" "resume-problem-1")
             :seon.cluster.agent/id "planner"
             :seon.cluster.run/id "stale-assignment-run"
             :seon.cluster.run.form/ordinal 0
             :seon.cluster.message/at now
             :seon.config.message/max-chain 16})]
       (db/transact! connection (:seon.cluster.message/rows delivery)))
     (is (= :unrouted-red
            (:seon.cluster.work/form-state
             (work/form-settlement @connection "resume-form-1")))
         "even a stale/manual assignment cannot turn X2 into routed"))))
