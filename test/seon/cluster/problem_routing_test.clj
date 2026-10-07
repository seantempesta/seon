(ns seon.cluster.problem-routing-test
  "Stored evaluation settlement over explicit assignment messages."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.cluster.message :as my.message]
            [seon.cluster.message :as message]
            [seon.turn :as turn]
            [seon.schema :as schema]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(def ^:private now (Date. 1785283200000))
(def ^:private run-id "settlement-run")

(defn- with-routing-database
  [body]
  (test-support/with-database
   (fn [connection]
     (test-support/transacted!
                  connection
                  (into (test-support/agent-tx @connection "root")
                        cat
                        [[(test-support/namespace-entity @connection 'my.gen.planner)
                          (test-support/namespace-entity @connection 'my.gen.alpha)]
                         (test-support/agent-tx @connection "planner" 'my.gen.planner)
                         (test-support/agent-tx @connection "alpha" 'my.gen.alpha)
                         [{:seon.message/id "goal" :seon.message/to [:seon.agent/id "root"] :seon.message/content "Generate the program."}]]))
     (test-support/transacted!
                  connection
                  [{:seon.message/id "planner-goal" :seon.message/to [:seon.agent/id "planner"] :seon.message/from [:seon.agent/id "root"] :seon.message/caused-by [:seon.message/id "goal"] :seon.message/content "Generate the program."}])
     (test-support/transacted!
                  connection
                  [{:seon.turn/id run-id :seon.turn/agent [:seon.agent/id "planner"] :seon.turn/trigger [:seon.message/id "planner-goal"] :seon.turn/opened-tx "datomic.tx"}])
     (body connection))))

(defn- form-entity
  [ordinal]
  {:seon.cluster.eval/id (str "form-" ordinal)
   :seon.cluster.eval/run [:seon.turn/id run-id]
   :seon.cluster.eval/ordinal ordinal
   ;; Every evaluation carries the instant it was frozen at.
   :seon.cluster.eval/at #inst "2026-09-06T20:00:00Z"
   :seon.cluster.eval/source (str "(form-" ordinal ")")
   :seon.cluster.eval/ns [:seon.ns/name 'my.gen.alpha]})

(defn- receipt-entity
  "The started/settled facts of the SAME evaluation entity `form-row` freezes.

  One entity per (run, ordinal): a started evaluation carries
  `:seon.cluster.eval/at`, and its terminal facts accrete onto it."
  [ordinal terminal]
  (merge
   (form-entity ordinal)
   {:seon.problems/id (str "problem-" ordinal)
    :seon.cluster.eval/at now}
   terminal))

(defn- deliver!
  [connection sender run-id value]
  (let [delivery
        (message/delivery
         @connection
         {:my.message/value value :seon.agent/id sender :seon.turn/id run-id :seon.cluster.eval/ordinal 0 :seon.config.message/max-chain 16})]
    (is (empty? (:seon.error/values delivery)))
    (test-support/transacted! connection (:seon.message/rows delivery))))

(defn- assign!
  [connection ordinal]
  (deliver! connection
            "planner"
            (str "assignment-" ordinal)
            (assoc (message/send "alpha" (str "repair " ordinal))
                   :my.message/assignment (str "form-" ordinal))))

(deftest stored-evaluations-derive-routing-and-settlement
  (with-routing-database
   (fn [connection]
     (test-support/transacted!
                  connection
                  [(receipt-entity 1 {})
                    (receipt-entity 2 {:seon.eval/shown "2"})
                    (receipt-entity 3 {:seon.eval/shown
                                    (pr-str {})
                                    :seon.cluster.eval/error "red 3"
                                    })
                    (receipt-entity 4 {:seon.eval/shown
                                    (pr-str {})
                                    :seon.cluster.eval/error "red 4"
                                    })
                    (receipt-entity 5 {:seon.eval/shown "5"})
                    (receipt-entity 6 {:seon.eval/shown
                                    (pr-str {})
                                    :seon.cluster.eval/error "red 6"
                                    })])
     (assign! connection 3)
     (assign! connection 5)
     (assign! connection 6)
     (deliver! connection
               "alpha"
               "declination-6"
               (my.message/decline
                "planner" "form-6" "The required contract is absent."))
     (let [settlement (turn/plan-settlement @connection run-id)
           forms (:seon.turn.work/forms settlement)]
       (is (= [:running
               :succeeded
               :routed
               :unrouted-red
               :owner-fixed
               :owner-declared-cant]
              (mapv :seon.turn.work/form-state forms)))
       (is (= [false true false false true true]
              (mapv :seon.turn.work/settled? forms)))
       (is (false? (:seon.turn.work/settled? settlement))
           "one unsettled form keeps the plan unsettled regardless of run state")
       (is (schema/valid-candidate-value? (schema/handed-projection)
            :seon.turn.work/plan-settlement settlement))
       (testing "closing the run cannot falsely settle its plan"
         (test-support/transacted! connection
                                 [[:db/add [:seon.turn/id run-id]
                                   :seon.turn/closed-tx "datomic.tx"]])
         (is (false?
              (:seon.turn.work/settled?
               (turn/plan-settlement @connection run-id)))))))))
