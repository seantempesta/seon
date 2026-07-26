(ns seon.effect-contract-test
  "Standing contracts for effect replay identity.

  Agents call the owning APIs directly (`seon.db/transact!`,
  `seon.agent.message/message!`); `seon.effect` is the one place the
  replay identity is derived. These tests pin the identity contract:
  derived from (run, form-ordinal, effect-ordinal), distinct within a
  form, identical across re-execution, absent outside a run. The
  database-transition replay properties live with the writer suite."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.effect :as effect]))

(deftest admission-refuses-unrealized-values
  (testing "a lazy value is refused by construction, not by review"
    (is (not (effect/ordinary-request-value? (map inc (range))))))
  (testing "ordinary nested data is admissible"
    (is (effect/ordinary-request-value?
         {:a [1 2 {:b #{:c "d"}}]}))))

(deftest effect-identity-is-positional-and-epoch-free
  (testing "identity carries run, form ordinal, and effect ordinal only"
    (is (= (pr-str ["run" 4 0]) (effect/op-id "run" 4 0))))
  (testing "two effects in one form execution derive distinct identities"
    (binding [effect/*request-context* (effect/request-context "run" 4)
              effect/*effect-counter* (effect/effect-counter)]
      (is (= [(effect/op-id "run" 4 0) (effect/op-id "run" 4 1)]
             [(effect/next-op-id!) (effect/next-op-id!)]))))
  (testing "re-execution derives the identical sequence — claim epoch is
            a fence, never identity, so recovery replays instead of
            repeating"
    (let [sequence-of (fn []
                        (binding [effect/*request-context*
                                  (effect/request-context "run" 4)
                                  effect/*effect-counter*
                                  (effect/effect-counter)]
                          [(effect/next-op-id!) (effect/next-op-id!)]))]
      (is (= (sequence-of) (sequence-of)))))
  (testing "distinct forms never share an identity"
    (is (not= (effect/op-id "run" 0 0) (effect/op-id "run" 1 0)))))

(deftest system-callers-have-no-replay-coordinates
  (testing "outside a run the derivation is honestly absent"
    (is (nil? (effect/next-op-id!)))))
