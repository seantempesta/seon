(ns seon.test.evaluator-evidence-test
  "A recorded result proves what its evaluator ran: compiled evidence never answers an interpreted request."
  (:require [clojure.test :refer [deftest is]]
            [seon.test :as sut]
            [seon.test-support :as support]))

(defn- check-as
  "The counts of one named request for `test-symbol` executed by `handle` as agent `agent-id`, recorded on its connection."
  {:malli/schema [:=> [:cat :seon.agent/execution-handle :seon.agent/id :qualified-symbol] :map]}
  [handle agent-id test-symbol]
  (select-keys (sut/run {:seon.test/execution (assoc handle :seon.agent/id agent-id)
                         :seon.test/recording-connection (:seon.db/connection handle)
                         :seon.test/policy :named :seon.test/identities #{test-symbol}})
               [:seon.test/passed? :seon.test/executed-count :seon.test/reused-count :seon.error/message]))

(deftest ^{:seon/tasks #{'seon.test/a-result-is-reused-only-by-the-evaluator-that-produced-it}}
  a-compiled-green-is-not-reused-by-an-interpreted-request
  (let [handle (support/execution-handle nil)
        test-symbol 'seon.id-test/a-host-object-has-no-identity
        compiled (check-as handle "root" test-symbol)
        interpreted (check-as handle "an-agent" test-symbol)
        again (check-as handle "an-agent" test-symbol)]
    (is (= 1 (:seon.test/executed-count compiled)) "root runs the test compiled")
    (is (true? (:seon.test/passed? compiled)))
    (is (= 0 (:seon.test/reused-count interpreted)) "an agent on root's branch does not reuse root's compiled green")
    (is (= 1 (:seon.test/executed-count interpreted)) "the agent runs the test interpreted")
    (is (true? (:seon.test/passed? interpreted)))
    (is (= 1 (:seon.test/reused-count again)) "an unchanged interpreted request reuses the interpreted green")))
