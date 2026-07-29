(ns seon.cluster.problem-routing-test
  "Owner routing and the seven-state plan-settlement derivation."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [my.message :as my.message]
            [seon.cluster.message :as message]
            [seon.cluster.work :as work]
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
     (d/transact
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
     (d/transact
      connection
      {:tx-data
       [{:seon.cluster.message/id "planner-goal"
         :seon.cluster.message/to [:seon.cluster.agent/id "planner"]
         :seon.cluster.message/from [:seon.cluster.agent/id "root"]
         :seon.cluster.message/content "Generate the program."
         :seon.cluster.message/at now}]
       :tx-meta {:seon.db/trigger [:seon.cluster.message/id "goal"]}})
     (d/transact
      connection
      {:tx-data
       [{:seon.cluster.run/id run-id
         :seon.cluster.run/agent [:seon.cluster.agent/id "planner"]
         :seon.cluster.run/opened-at now
         :seon.cluster.run/plan-digest "settlement-digest"}]
       :tx-meta
       {:seon.db/trigger [:seon.cluster.message/id "planner-goal"]}})
     (body connection))))

(defn- form-row
  [ordinal]
  {:seon.cluster.run.form/id (str "form-" ordinal)
   :seon.cluster.run.form/run [:seon.cluster.run/id run-id]
   :seon.cluster.run.form/ordinal ordinal
   :seon.cluster.run.form/source (str "(form-" ordinal ")")
   :seon.cluster.run.form/ns [:seon.ns/name 'my.gen.alpha]})

(defn- receipt-row
  [ordinal terminal]
  (merge
   {:seon.cluster.eval/id (str "receipt-" ordinal)
    :seon.problems/id (str "problem-" ordinal)
    :seon.cluster.eval/run [:seon.cluster.run/id run-id]
    :seon.cluster.eval/ordinal ordinal
    :seon.cluster.eval/at now}
   terminal))

(defn- deliver!
  [connection sender run-id value]
  (let [delivery
        (message/delivery
         @connection
         {:my.message/value value
          :seon.cluster.agent/id sender
          :seon.cluster.run/id run-id
          :seon.cluster.run.form/ordinal 0
          :seon.cluster.message/at now
          :seon.config.message/max-chain 16})]
    (is (empty? (:seon.error/values delivery)))
    (d/transact connection (:seon.cluster.message/rows delivery))))

(defn- assign!
  [connection ordinal]
  (deliver! connection
            "planner"
            (str "assignment-" ordinal)
            (my.message/send "alpha"
                             (str "repair " ordinal)
                             (str "problem-" ordinal))))

(defn- evaluation-error
  [message]
  {:seon.sci.admit/value
   {:seon.error/kind :seon.sci.eval/evaluation-failed
    :seon.error/message message
    :seon.error/data {}}
   :seon.cluster.eval/result-edn
   (pr-str {:seon.error/kind :seon.sci.eval/evaluation-failed
            :seon.error/message message
            :seon.error/data {}})
   :seon.cluster.eval/error message
   :seon.sci.admit/capped? false
   :seon.sci.admit/record
   {:seon.eval/fn-entries 1
    :seon.eval/duration-ms 1
    :seon.eval/allocated-bytes 1
    :seon.eval/outcome :error}})

(deftest parse-time-owner-wins-and-absence-falls-back-to-the-author
  (with-routing-database
   (fn [connection]
     (d/transact
      connection
      [(form-row 0)
       (dissoc (form-row 1) :seon.cluster.run.form/ns)
       (receipt-row 0 {})
       (receipt-row 1 {})])
     (let [failed (evaluation-error "boom")
           attributed
           (problems/form-problem
            @connection
            {:seon.cluster.run/id run-id
             :seon.cluster.run.form/ordinal 0
             :seon.sci.eval/evaluation failed})
           fallback
           (problems/form-problem
            @connection
            {:seon.cluster.run/id run-id
             :seon.cluster.run.form/ordinal 1
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
     (d/transact
      connection
      [(dissoc (form-row 0) :seon.cluster.run.form/ns)
       (receipt-row
        0
        {:seon.cluster.eval/result-edn
         (pr-str {:seon.error/kind :probe/self-owned-red})
         :seon.cluster.eval/error "self-owned red"
         :seon.error/kind :probe/self-owned-red})])
     (let [problem
           (problems/form-problem
            @connection
            {:seon.cluster.run/id run-id
             :seon.cluster.run.form/ordinal 0
             :seon.sci.eval/evaluation
             (evaluation-error "self-owned red")})]
       (is (= "planner" (:seon.cluster.agent/id problem)))
       (is (= "planner" (:seon.problems/author problem)))
       (when-let [assignment (problems/assignment-value problem)]
         (deliver! connection "planner" "self-assignment" assignment))
       (is (empty?
            (d/q '[:find ?message
                   :in $ ?problem-id
                   :where
                   [?message :seon.cluster.message/about ?problem]
                   [?problem :seon.problems/id ?problem-id]]
                 @connection
                 (:seon.problems/id problem)))
           "the ordinary loop shape emits no author-to-author message")
       (is (= {:seon.cluster.work/form-state :unrouted-red
               :seon.cluster.work/settled? false}
              (select-keys
               (work/form-settlement @connection "form-0")
               [:seon.cluster.work/form-state
                :seon.cluster.work/settled?]))
           "the red problem still keeps its plan unsettled")))))

(deftest historical-reds-are-outside-the-live-attempt-chain
  (with-routing-database
   (fn [connection]
     (d/transact
      connection
      [{:seon.cluster.run/id "historical-run"
        :seon.cluster.run/agent [:seon.cluster.agent/id "planner"]
        :seon.cluster.run/opened-at now
        :seon.cluster.run/plan-digest "historical-digest"}
       {:seon.cluster.run.form/id "historical-form"
        :seon.cluster.run.form/run [:seon.cluster.run/id "historical-run"]
        :seon.cluster.run.form/ordinal 0
        :seon.cluster.run.form/source "(my.store/get :obsolete)"
        :seon.cluster.run.form/ns [:seon.ns/name 'my.gen.alpha]}])
     (is (nil?
          (problems/form-problem
           @connection
           {:seon.cluster.run/id "historical-run"
            :seon.cluster.run.form/ordinal 0
            :seon.sci.eval/evaluation (evaluation-error "Unable to resolve")})))
     (is (empty?
          (d/q '[:find ?assignment
                 :where
                 [?assignment :seon.cluster.message/about _]]
               @connection))
         "a newly assigned owner has no historical problem to deliver"))))

(deftest every-form-has-exactly-one-of-the-seven-derived-states
  (with-routing-database
   (fn [connection]
     (d/transact
      connection
      (into
       (mapv form-row (range 7))
       [(receipt-row 1 {})
        (receipt-row 2 {:seon.cluster.eval/result-edn "2"})
        (receipt-row 3 {:seon.cluster.eval/result-edn
                        (pr-str {:seon.error/kind :probe/red})
                        :seon.cluster.eval/error "red 3"
                        :seon.error/kind :probe/red})
        (receipt-row 4 {:seon.cluster.eval/result-edn
                        (pr-str {:seon.error/kind :probe/red})
                        :seon.cluster.eval/error "red 4"
                        :seon.error/kind :probe/red})
        (receipt-row 5 {:seon.cluster.eval/result-edn "5"})
        (receipt-row 6 {:seon.cluster.eval/result-edn
                        (pr-str {:seon.error/kind :probe/red})
                        :seon.cluster.eval/error "red 6"
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
           forms (:seon.cluster.work/forms settlement)]
       (is (= [:unevaluated
               :running
               :succeeded
               :routed
               :unrouted-red
               :owner-fixed
               :owner-declared-cant]
              (mapv :seon.cluster.work/form-state forms)))
       (is (= [false false true false false true true]
              (mapv :seon.cluster.work/settled? forms)))
       (is (false? (:seon.cluster.work/settled? settlement))
           "one unsettled form keeps the plan unsettled regardless of run state")
       (is (schema/valid-candidate-value?
            :seon.cluster.work/plan-settlement settlement))
       (testing "closing the run cannot falsely settle its plan"
         (d/transact connection
                     [[:db/add [:seon.cluster.run/id run-id]
                       :seon.cluster.run/closed-at now]])
         (is (false?
              (:seon.cluster.work/settled?
               (work/plan-settlement @connection run-id)))))))))
