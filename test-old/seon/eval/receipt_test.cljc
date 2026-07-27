(ns seon.eval.receipt-test
  "Portable durable eval receipt ordering regressions."
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing]])
   [seon.eval.receipt :as receipt]
   [seon.schema :as schema]))

(def start-request
  {:seon.agent.turn/id "turnorder00001"
   :seon.agent.run/id "runorder000001"
   :seon.eval/at #inst "2026-07-25T21:00:00.000-00:00"
   :seon.eval/ordinal 2
   :seon.eval/total 7
   :seon.eval/claim-epoch 4
   :seon.eval/source "(+ 1 2)"
   :seon.eval/narration "Add two values."
   :seon.eval/ns 'my.agent.order})

(deftest ordinal-and-total-are-owned-receipt-schemas
  (testing "the receipt owner supplies constrained Malli-backed attributes"
    (is (= [:int {:min 0}]
           (schema/schema-definition :seon.eval/ordinal)))
    (is (= [:int {:min 1}]
           (schema/schema-definition :seon.eval/total)))
    (is (schema/valid-candidate-value? :seon.eval/ordinal 0))
    (is (not (schema/valid-candidate-value? :seon.eval/ordinal -1)))
    (is (schema/valid-candidate-value? :seon.eval/total 1))
    (is (not (schema/valid-candidate-value? :seon.eval/total 0)))))

(deftest running-receipt-freezes-form-three-of-seven
  (let [tx-data (receipt/start-tx-data start-request)
        eval-row (-> tx-data first :seon.agent.turn/evals first)]
    (is (set? (-> tx-data first :seon.agent.turn/evals))
        "the component edge stores membership; the receipt stores its ordinal")
    (testing "receipt identity is exactly run, ordinal, and claim epoch"
      (is (= (receipt/receipt-id "runorder000001" 2 4)
             (receipt/receipt-id "runorder000001" 2 4)))
      (is (not= (receipt/receipt-id "runorder000001" 2 4)
                (receipt/receipt-id "runorder000001" 2 5)))
      (is (not= (receipt/receipt-id "runorder000001" 2 4)
                (receipt/receipt-id "runorder000001" 3 4))))
    (testing "ordering is durable before the form executes"
      (is (= 2 (:seon.eval/ordinal eval-row)))
      (is (= 7 (:seon.eval/total eval-row)))
      (is (= 4 (:seon.eval/claim-epoch eval-row)))
      (is (= [:seon.agent.run/id "runorder000001"]
             (:seon.eval/run eval-row)))
      (is (= (pr-str ["runorder000001" 2 4])
             (:seon.eval/id eval-row)))
      (is (= :running (:seon.eval/status eval-row))))
    (testing "terminalization updates the same receipt without another order"
      (is (= [[:db.fn/cas
               [:seon.eval/id (pr-str ["runorder000001" 2 4])]
               :seon.eval/status :running :running]
              {:seon.eval/id (pr-str ["runorder000001" 2 4])
               :seon.eval/status :interrupted
               :seon.eval/ok? false}]
             (receipt/terminal-tx-data
              {:seon.eval/id (pr-str ["runorder000001" 2 4])
               :seon.eval/status :interrupted}))))))

(deftest resume-advances-only-past-terminal-receipts
  (let [receipts [{:seon.eval/ordinal 0
                   :seon.eval/claim-epoch 1
                   :seon.eval/status :done}
                  {:seon.eval/ordinal 1
                   :seon.eval/claim-epoch 1
                   :seon.eval/status :running}
                  {:seon.eval/ordinal 2
                   :seon.eval/claim-epoch 1
                   :seon.eval/status :done}]]
    (is (= 1 (receipt/next-ordinal 7 receipts))
        "a running receipt from an abandoned epoch does not consume its ordinal")
    (is (= 3
           (receipt/next-ordinal
            7
            (assoc-in receipts [1 :seon.eval/status] :interrupted)))
        "the first true gap follows contiguous terminal receipts")))
