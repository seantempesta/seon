(ns seon.agent-cost-test
  (:require [clojure.test :refer [deftest is]]
            [seon.agent-cost :as cost]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Prepare a branch with 24 reaching tests, then measure two complete MCP requests and its verdict."
           :seon.test/long-ms 120000}
  chain-probe-measures-the-complete-request
  (support/with-running-agent
   (fn [running _ _]
     (let [result (assoc (cost/group-run running 24 1)
                         :root [(cost/request running "root" "(+ 1 2)")])]
       (cost/report result)
       (is (= 26 (:members (first (:verdict result)))))
       (is (= 1 (:members (first (:miss result)))))
       (is (= 2 (get-in result [:repeat 0 :derivations 'seon.test/bounded-result] 0)))
       (is (zero? (get-in result [:repeat 0 :derivations 'seon.fn/gate-edges] 0)))
       (is (zero? (get-in result [:unchanged 0 :derivations 'seon.test/run] 0)))
       (is (zero? (get-in result [:root 0 :derivations 'seon.db/entities-changed-after] 0)))
       (doseq [request (concat (:first result) (:repeat result) (:edge result) (:unchanged result))]
         (is (false? (:failed? request)))
         (is (pos? (get-in request [:request :seon.cost/ms])))
         (is (pos? (:seon.profile/ms request))))))))

(deftest ^{:seon.test/long "Measure 3 and 6 concurrent own-branch agents and join every request before retirement."
           :seon.test/long-ms 120000}
  concurrent-chain-requests
  (support/with-running-agent
   (fn [running _ _]
     (doseq [k [3 6]]
       (let [result (cost/group-run running 24 k)]
         (cost/report result)
         (is (= (repeat k 26) (map :members (:verdict result))))
         (is (= (repeat k 1) (map :members (:miss result))))
         (is (every? (comp false? :failed?) (mapcat result [:first :repeat :edge :unchanged]))))))))
