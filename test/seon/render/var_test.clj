(ns seon.render.var-test
  "The Var render's status: what it tells the agent about a definition's contract."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.render.var :as sut]
            [seon.test-support :as support]))

(deftest a-malformed-contract-renders-as-malformed-with-mallis-refusal
  ;; live run 11: the program chain wrote [:or :nil :vector] in a contract and was told for 12 calls it had none
  (support/with-database
   (fn [connection]
     (support/transacted!
      connection
      [(support/namespace-entity @connection 'sample.var-malformed)
       (support/program-fn-entity (db/db connection) 'sample.var-malformed/bare-vector
                                  "(defn bare-vector {:malli/schema [:=> [:cat :vector] :int]} [x] 1)")])
     (let [status (sut/status (db/db connection) 'sample.var-malformed/bare-vector false)]
       (is (not= :missing (:seon.render.var/contract status))
           "a contract the agent wrote is not called missing")
       (is (= :malformed (:seon.render.var/contract status))
           "an authored contract Malli refuses is called malformed")
       (is (contains? (:seon.render.var/malli-refusal status) :malli.core/child-error)
           "the status carries Malli's refusal of the contract, by its type")
       (is (not (contains? status :seon.render.var/example))
           "the example for a missing contract is not offered for a malformed one")))))
