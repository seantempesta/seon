(ns my.effect-contract-test
  "Standing contracts for the step-1 effect surface.

  Fable-authored with the contract layer (2026-07-26). Today these prove
  the schemas admit the ruled shapes and that every stub is an honest
  error value. The ^:seon.contract/pending tests assert the stub truth
  now and carry the real post-implementation property in a comment block
  the step-1 implementation lane activates."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.schema :as schema]
            [seon.effect :as effect]
            [my.message :as my.message]
            [my.db :as my.db]))

(deftest request-envelope-admits-the-ruled-shape
  (testing "a minimal well-formed request is admissible"
    (is (schema/valid-candidate-value?
         :seon.effect/request
         {:seon.effect/family :message
          :seon.effect/args {:seon.agent.message/content "hi"
                             :seon.agent.message/to ["root"]}})))
  (testing "an unknown family is refused"
    (is (not (schema/valid-candidate-value?
              :seon.effect/request
              {:seon.effect/family :teleport
               :seon.effect/args {}}))))
  (testing "a lazy value is refused by construction, not by review"
    (is (not (effect/ordinary-request-value? (map inc (range))))))
  (testing "ordinary nested data is admissible"
    (is (effect/ordinary-request-value?
         {:a [1 2 {:b #{:c "d"}}]}))))

(deftest stubs-are-honest-error-values
  (testing "request! returns a flat not-implemented error, never throws"
    (let [result (effect/request! {:seon.effect/family :db
                                   :seon.effect/args {}})]
      (is (= :seon.effect/not-implemented (:seon.error/kind result)))
      (is (string? (:seon.error/message result)))))
  (testing "my.message/message! surfaces the same honest error"
    (is (= :seon.effect/not-implemented
           (:seon.error/kind
            (my.message/message!
             {:seon.agent.message/content "hello"
              :seon.agent.message/to ["root"]})))))
  (testing "my.db/transact surfaces the same honest error"
    (is (= :seon.effect/not-implemented
           (:seon.error/kind
            (my.db/transact
             {:tx-data [{:my.db/probe true}]}))))))

(deftest ^:seon.contract/pending message-identity-is-derived
  ;; ACTIVATE WITH STEP-1 IMPLEMENTATION. The real property: two
  ;; executions of the same sending form — same (run, ordinal, epoch) —
  ;; commit ONE message entity; the recipient observes exactly one
  ;; delivery. Generative over seeded (run, ordinal, epoch) triples.
  (testing "today: the stub cannot send at all, so no identity exists"
    (is (= :seon.effect/not-implemented
           (:seon.error/kind
            (my.message/message!
             {:seon.agent.message/content "replay probe"
              :seon.agent.message/to ["root"]}))))))

(deftest ^:seon.contract/pending replay-identity-makes-writes-idempotent
  ;; ACTIVATE WITH STEP-1 IMPLEMENTATION. The real property: two
  ;; transact! calls carrying the same :seon.capability/op-id commit one
  ;; write; the second returns the first's basis (replay, not repeat).
  (testing "today: the stub cannot write at all"
    (is (= :seon.effect/not-implemented
           (:seon.error/kind
            (my.db/transact
             {:tx-data [{:my.db/probe true}]}))))))
