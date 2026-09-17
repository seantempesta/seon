(ns seon.cluster.problem-routing-test
  "Owner routing and stored evaluation settlement."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.cluster.message :as my.message]
            [seon.cluster.message :as message]
            [seon.turn :as turn]
            [seon.print :as print]
            [seon.problems :as problems]
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
                  [{:seon.ns/name 'my.gen.planner}
                   {:seon.ns/name 'my.gen.alpha}
                   {:seon.agent/id "planner"
                    :seon.agent/namespace [:seon.ns/name 'my.gen.planner]}
                   {:seon.agent/id "alpha"
                    :seon.agent/namespace [:seon.ns/name 'my.gen.alpha]}
                   {:seon.agent/id "root"}
                   {:seon.message/id "goal" :seon.message/to [:seon.agent/id "root"] :seon.message/content "Generate the program."}])
     (test-support/transacted!
                  connection
                  [{:seon.message/id "planner-goal" :seon.message/to [:seon.agent/id "planner"] :seon.message/from [:seon.agent/id "root"] :seon.message/caused-by [:seon.message/id "goal"] :seon.message/content "Generate the program."}])
     (test-support/transacted!
                  connection
                  [{:seon.turn/id run-id :seon.turn/agent [:seon.agent/id "planner"] :seon.turn/trigger [:seon.message/id "planner-goal"] :seon.turn/opened-tx "datomic.tx"}])
     (body connection))))

(defn- form-row
  [ordinal]
  {:seon.cluster.eval/id (str "form-" ordinal)
   :seon.cluster.eval/run [:seon.turn/id run-id]
   :seon.cluster.eval/ordinal ordinal
   ;; Every evaluation carries the instant it was frozen at.
   :seon.cluster.eval/at #inst "2026-09-06T20:00:00Z"
   :seon.cluster.eval/source (str "(form-" ordinal ")")
   :seon.cluster.eval/ns [:seon.ns/name 'my.gen.alpha]})

(defn- receipt-row
  "The started/settled facts of the SAME evaluation entity `form-row` freezes.

  One entity per (run, ordinal): a started evaluation carries
  `:seon.cluster.eval/at`, and its terminal facts accrete onto it."
  [ordinal terminal]
  (merge
   (form-row ordinal)
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

(defn- evaluation-error
  "One failed evaluation, carrying every member `:seon.sci.eval/evaluation`
  declares — the namespaces it started and ended in, its print options and
  its complete arm record — exactly as the evaluator returns them. A partial
  stand-in is a shape the declared contract forbids."
  [message]
  {:seon.sci.admit/value
   {:seon.error/kind :seon.sci.eval/evaluation-failed
    :seon.error/message message
    :seon.error/data {}}
   :seon.eval/shown
   (pr-str {:seon.error/kind :seon.sci.eval/evaluation-failed
            :seon.error/message message
            :seon.error/data {}})
   :seon.cluster.eval/error message
   :seon.cluster.eval/ns [:seon.ns/name 'my.gen.alpha]
   :seon.sci.eval/ending-ns 'my.gen.alpha
   :seon.print/options (print/default-options)
   :seon.sci.admit/record
   {:seon.eval/fn-entries 1
    :seon.eval/host-interop-count 0
    :seon.eval/duration-ms 1
    :seon.eval/allocated-bytes 1
    :seon.eval/outcome :error}})

(deftest parse-time-owner-wins-and-absence-falls-back-to-the-author
  (with-routing-database
   (fn [connection]
     (test-support/transacted!
                  connection
                  [(receipt-row 0 {})
                   (dissoc (receipt-row 1 {}) :seon.cluster.eval/ns)])
     (let [failed (evaluation-error "boom")
           attributed
           (problems/form-problem
            @connection
            {:seon.turn/id run-id
             :seon.cluster.eval/ordinal 0
             :seon.sci.eval/evaluation failed})
           fallback
           (problems/form-problem
            @connection
            {:seon.turn/id run-id
             :seon.cluster.eval/ordinal 1
             :seon.sci.eval/evaluation failed})]
       (is (= "alpha" (:seon.agent/id attributed))
           "the reader-projected namespace owns the red form")
       (is (= "planner" (:seon.problems/author attributed)))
       (is (= "planner" (:seon.agent/id fallback))
           "pre-reader absence and an unowned namespace both fall back to author")
       (is (schema/valid-candidate-value?
            :seon.problems/form-problem attributed))))))

(deftest an-author-owned-red-form-remains-unsettled-without-self-assignment
  (with-routing-database
   (fn [connection]
     (test-support/transacted!
                  connection
                  [(dissoc
                    (receipt-row
                     0
                     {:seon.eval/shown
                      (pr-str {:seon.error/kind :probe/self-owned-red})
                      :seon.cluster.eval/error "self-owned red"
                      :seon.error/kind :probe/self-owned-red})
                    :seon.cluster.eval/ns)])
     (let [problem
           (problems/form-problem
            @connection
            {:seon.turn/id run-id
             :seon.cluster.eval/ordinal 0
             :seon.sci.eval/evaluation
             (evaluation-error "self-owned red")})]
       (is (= "planner" (:seon.agent/id problem)))
       (is (= "planner" (:seon.problems/author problem)))
       (when-let [assignment (problems/assignment-value problem)]
         (deliver! connection "planner" "self-assignment" assignment))
       (is (empty?
            (db/q '[:find ?message
                   :in $ ?problem-id
                   :where
                   [?message :seon.message/assignment ?problem-id]]
                 @connection
                 (:seon.problems/id problem)))
           "the ordinary loop shape emits no author-to-author message")
       (is (= {:seon.turn.work/form-state :unrouted-red
               :seon.turn.work/settled? false}
              (select-keys
               (turn/form-settlement @connection "form-0")
               [:seon.turn.work/form-state
                :seon.turn.work/settled?]))
           "the red problem still keeps its plan unsettled")))))

(deftest historical-reds-are-outside-the-live-attempt-chain
  (with-routing-database
   (fn [connection]
     (test-support/transacted!
                  connection
                  [{:seon.turn/id "historical-run" :seon.turn/agent [:seon.agent/id "planner"] :seon.turn/opened-tx "datomic.tx"}
                   {:seon.cluster.eval/id "historical-form"
                    :seon.cluster.eval/run [:seon.turn/id "historical-run"]
                    :seon.cluster.eval/ordinal 0
                    :seon.cluster.eval/at #inst "2026-09-06T20:00:00Z"
                    :seon.cluster.eval/source "(my.store/get :obsolete)"
                    :seon.cluster.eval/ns [:seon.ns/name 'my.gen.alpha]}])
     (is (nil?
          (problems/form-problem
           @connection
           {:seon.turn/id "historical-run"
            :seon.cluster.eval/ordinal 0
            :seon.sci.eval/evaluation (evaluation-error "Unable to resolve")})))
     (is (empty?
          (db/q '[:find ?assignment
                 :where
                 [?assignment :seon.message/assignment _]]
               @connection))
         "a newly assigned owner has no historical problem to deliver"))))

(deftest stored-evaluations-derive-routing-and-settlement
  (with-routing-database
   (fn [connection]
     (test-support/transacted!
                  connection
                  [(receipt-row 1 {})
                    (receipt-row 2 {:seon.eval/shown "2"})
                    (receipt-row 3 {:seon.eval/shown
                                    (pr-str {:seon.error/kind :probe/red})
                                    :seon.cluster.eval/error "red 3"
                                    :seon.error/kind :probe/red})
                    (receipt-row 4 {:seon.eval/shown
                                    (pr-str {:seon.error/kind :probe/red})
                                    :seon.cluster.eval/error "red 4"
                                    :seon.error/kind :probe/red})
                    (receipt-row 5 {:seon.eval/shown "5"})
                    (receipt-row 6 {:seon.eval/shown
                                    (pr-str {:seon.error/kind :probe/red})
                                    :seon.cluster.eval/error "red 6"
                                    :seon.error/kind :probe/red})])
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
       (is (schema/valid-candidate-value?
            :seon.turn.work/plan-settlement settlement))
       (testing "closing the run cannot falsely settle its plan"
         (test-support/transacted! connection
                                 [[:db/add [:seon.turn/id run-id]
                                   :seon.turn/closed-tx "datomic.tx"]])
         (is (false?
              (:seon.turn.work/settled?
               (turn/plan-settlement @connection run-id)))))))))
