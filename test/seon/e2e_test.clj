(ns seon.e2e-test
  "A run ends on what the system observes: quiescence, or a recorded defect (audit FENCES
  #26: a flight whose answer is lost never ends, so quiescence never comes; a root proc's
  defect recorded on main ends the wait instead). No clock."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as pdb]
            [seon.e2e :as e2e]))

(defn- conn [] (second (pdb/test-branch!)))

(deftest await-quiet-ends-on-a-recorded-defect
  (let [c (conn)
        sys {:conn c}
        t (:max-tx @c)
        start (System/currentTimeMillis)
        waiting (future (e2e/await-quiet sys t))]
    (d/transact c {:tx-data [{:seon.error/signature "0123456789abcdef" :seon.error/class :eval :seon.error/count 1 :seon.error/agents 1
                              :seon.error/first-ms start :seon.error/last-ms start}]})
    (is (not (realized? (do (d/transact c {:tx-data [{:note/text "work goes on"}]}) waiting))) "an agent's eval error is no defect of the run")
    (d/transact c {:tx-data [{:seon.error/signature "fedcba9876543210" :seon.error/class :platform :seon.error/count 1 :seon.error/agents 0
                              :seon.error/first-ms start :seon.error/last-ms start :seon.error/message "seon.reconcile/reconciler-proc: boom"}]})
    (let [r (deref waiting)]
      (is (= :defect (:ended r)))
      (is (= "seon.reconcile/reconciler-proc: boom" (get-in r [:problem :seon.error/message])) "the problem, as recorded"))
    (testing "quiescence ends it as before"
      (let [c (conn) t (:max-tx @c) waiting (future (e2e/await-quiet {:conn c} t))]
        (d/transact c {:tx-data [{:seon.root/id :root :seon.root/quiescent (pr-str {:t (inc t) :stuck []})}]})
        (is (= {:t (inc t) :stuck []} (deref waiting)))))))
