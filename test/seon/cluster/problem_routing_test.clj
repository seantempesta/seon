(ns seon.cluster.problem-routing-test
  "Owner routing and the seven-state plan-settlement derivation."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [my.message :as my.message]
            [seon.cluster.message :as message]
            [seon.turn :as work]
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
     (db/transact!
      connection
      [{:seon.ns/name 'my.gen.planner}
       {:seon.ns/name 'my.gen.alpha}
       {:seon.cluster.agent/id "planner"
        :seon.cluster.agent/namespace [:seon.ns/name 'my.gen.planner]}
       {:seon.cluster.agent/id "alpha"
        :seon.cluster.agent/namespace [:seon.ns/name 'my.gen.alpha]}
       {:seon.cluster.agent/id "root"}
       {:seon.cluster.message/id "goal"
        :seon.cluster.message/to [:seon.cluster.agent/id "root"]
        :seon.cluster.message/content "Generate the program."
        :seon.cluster.message/at now}])
     (db/transact!
      connection
      [{:seon.cluster.message/id "planner-goal"
        :seon.cluster.message/to [:seon.cluster.agent/id "planner"]
        :seon.cluster.message/from [:seon.cluster.agent/id "root"]
        :seon.cluster.message/caused-by [:seon.cluster.message/id "goal"]
        :seon.cluster.message/content "Generate the program."
        :seon.cluster.message/at now}])
     (db/transact!
      connection
      [{:seon.turn/id run-id
        :seon.turn/agent [:seon.cluster.agent/id "planner"]
        :seon.turn/trigger
        [:seon.cluster.message/id "planner-goal"]
        :seon.turn/opened-at now
        :seon.turn/plan-digest "settlement-digest"}])
     (body connection))))

(defn- form-row
  [ordinal]
  {:seon.eval/id (str "form-" ordinal)
   :seon.eval/turn [:seon.turn/id run-id]
   :seon.eval/ordinal ordinal
   :seon.eval/source (str "(form-" ordinal ")")
   :seon.eval/ns [:seon.ns/name 'my.gen.alpha]})

(defn- receipt-row
  "The started/settled facts of the SAME evaluation entity `form-row` freezes.

  One entity per (run, ordinal): a started evaluation carries
  `:seon.eval/at`, and its terminal facts accrete onto it."
  [ordinal terminal]
  (merge
   (form-row ordinal)
   {:seon.problems/id (str "problem-" ordinal)
    :seon.eval/at now}
   terminal))

(defn- deliver!
  [connection sender run-id value]
  (let [delivery
        (message/delivery
         @connection
         {:my.message/value value
          :seon.cluster.agent/id sender
          :seon.turn/id run-id
          :seon.eval/ordinal 0
          :seon.cluster.message/at now
          :seon.config.message/max-chain 16})]
    (is (empty? (:seon.error/values delivery)))
    (db/transact! connection (:seon.cluster.message/rows delivery))))

(defn- assign!
  [connection ordinal]
  (deliver! connection
            "planner"
            (str "assignment-" ordinal)
            (my.message/send "alpha"
                             (str "repair " ordinal)
                             (str "problem-" ordinal))))

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
   :seon.eval/result-edn
   (pr-str {:seon.error/kind :seon.sci.eval/evaluation-failed
            :seon.error/message message
            :seon.error/data {}})
   :seon.eval/error message
   :seon.eval/ns [:seon.ns/name 'my.gen.alpha]
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
     (db/transact!
      connection
      [(receipt-row 0 {})
       (dissoc (receipt-row 1 {}) :seon.eval/ns)])
     (let [failed (evaluation-error "boom")
           attributed
           (problems/form-problem
            @connection
            {:seon.turn/id run-id
             :seon.eval/ordinal 0
             :seon.sci.eval/evaluation failed})
           fallback
           (problems/form-problem
            @connection
            {:seon.turn/id run-id
             :seon.eval/ordinal 1
             :seon.sci.eval/evaluation failed})]
       (is (= "alpha" (:seon.cluster.agent/id attributed))
           "the reader-projected namespace owns the red form")
       (is (= "planner" (:seon.problems/author attributed)))
       (is (= "planner" (:seon.cluster.agent/id fallback))
           "pre-reader absence and an unowned namespace both fall back to author")
       (is (schema/valid-candidate-value?
            :seon.problems/form-problem attributed))))))

(deftest an-author-owned-red-form-remains-unsettled-without-self-assignment
  (with-routing-database
   (fn [connection]
     (db/transact!
      connection
      [(dissoc
        (receipt-row
         0
         {:seon.eval/result-edn
          (pr-str {:seon.error/kind :probe/self-owned-red})
          :seon.eval/error "self-owned red"
          :seon.error/kind :probe/self-owned-red})
        :seon.eval/ns)])
     (let [problem
           (problems/form-problem
            @connection
            {:seon.turn/id run-id
             :seon.eval/ordinal 0
             :seon.sci.eval/evaluation
             (evaluation-error "self-owned red")})]
       (is (= "planner" (:seon.cluster.agent/id problem)))
       (is (= "planner" (:seon.problems/author problem)))
       (when-let [assignment (problems/assignment-value problem)]
         (deliver! connection "planner" "self-assignment" assignment))
       (is (empty?
            (db/q '[:find ?message
                   :in $ ?problem-id
                   :where
                   [?message :seon.cluster.message/about ?problem]
                   [?problem :seon.problems/id ?problem-id]]
                 @connection
                 (:seon.problems/id problem)))
           "the ordinary loop shape emits no author-to-author message")
       (is (= {:seon.turn/form-state :unrouted-red
               :seon.turn/settled? false}
              (select-keys
               (work/form-settlement @connection "form-0")
               [:seon.turn/form-state
                :seon.turn/settled?]))
           "the red problem still keeps its plan unsettled")))))

(deftest historical-reds-are-outside-the-live-attempt-chain
  (with-routing-database
   (fn [connection]
     (db/transact!
      connection
      [{:seon.turn/id "historical-run"
        :seon.turn/agent [:seon.cluster.agent/id "planner"]
        :seon.turn/opened-at now
        :seon.turn/plan-digest "historical-digest"}
       {:seon.eval/id "historical-form"
        :seon.eval/turn [:seon.turn/id "historical-run"]
        :seon.eval/ordinal 0
        :seon.eval/source "(my.store/get :obsolete)"
        :seon.eval/ns [:seon.ns/name 'my.gen.alpha]}])
     (is (nil?
          (problems/form-problem
           @connection
           {:seon.turn/id "historical-run"
            :seon.eval/ordinal 0
            :seon.sci.eval/evaluation (evaluation-error "Unable to resolve")})))
     (is (empty?
          (db/q '[:find ?assignment
                 :where
                 [?assignment :seon.cluster.message/about _]]
               @connection))
         "a newly assigned owner has no historical problem to deliver"))))

(deftest every-form-has-exactly-one-of-the-seven-derived-states
  (with-routing-database
   (fn [connection]
     (db/transact!
      connection
      (into
       [(form-row 0)]
       [(receipt-row 1 {})
        (receipt-row 2 {:seon.eval/result-edn "2"})
        (receipt-row 3 {:seon.eval/result-edn
                        (pr-str {:seon.error/kind :probe/red})
                        :seon.eval/error "red 3"
                        :seon.error/kind :probe/red})
        (receipt-row 4 {:seon.eval/result-edn
                        (pr-str {:seon.error/kind :probe/red})
                        :seon.eval/error "red 4"
                        :seon.error/kind :probe/red})
        (receipt-row 5 {:seon.eval/result-edn "5"})
        (receipt-row 6 {:seon.eval/result-edn
                        (pr-str {:seon.error/kind :probe/red})
                        :seon.eval/error "red 6"
                        :seon.error/kind :probe/red})]))
     (assign! connection 3)
     (assign! connection 5)
     (assign! connection 6)
     (deliver! connection
               "alpha"
               "declination-6"
               (my.message/decline
                "planner" "problem-6" "The required contract is absent."))
     (let [settlement (work/plan-settlement @connection run-id)
           forms (:seon.turn/evaluation-settlements settlement)]
       (is (= [:unevaluated
               :running
               :succeeded
               :routed
               :unrouted-red
               :owner-fixed
               :owner-declared-cant]
              (mapv :seon.turn/form-state forms)))
       (is (= [false false true false false true true]
              (mapv :seon.turn/settled? forms)))
       (is (false? (:seon.turn/settled? settlement))
           "one unsettled form keeps the plan unsettled regardless of run state")
       (is (schema/valid-candidate-value?
            :seon.turn/plan-settlement settlement))
       (testing "closing the run cannot falsely settle its plan"
         (db/transact! connection
                     [[:db/add [:seon.turn/id run-id]
                       :seon.turn/closed-at now]])
         (is (false?
              (:seon.turn/settled?
               (work/plan-settlement @connection run-id)))))))))
