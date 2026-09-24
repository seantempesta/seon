(ns seon.cluster.adoption-exclusion-test
  "No development adoption reloads or arms under a running test member:
  members hold `seon.cluster/call-excluding-adoption`, adoption waits in
  `call-with-program-change`, and a member queued behind it starts after it."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.test-support :as support]))

(defn- queued?
  "Wait up to one second for `expected` threads queued on `lock`."
  {:malli/schema [:=> [:cat [:fn seon.cluster/program-change-lock?] [:int {:min 1}]] :boolean]}
  [^java.util.concurrent.locks.ReentrantReadWriteLock lock expected]
  (let [deadline (+ (System/nanoTime) 1000000000)]
    (loop []
      (cond (>= (.getQueueLength lock) expected) true
            (> (System/nanoTime) deadline) false
            :else (do (Thread/onSpinWait) (recur))))))

(deftest a-member-running-across-an-adoption-sees-no-change-and-the-adoption-completes-after-it
  (let [lock (java.util.concurrent.locks.ReentrantReadWriteLock. true)
        change @#'cluster/call-with-program-change
        database (db/db (:seon.db/connection (support/execution-handle nil)))
        program (atom :before)
        events (atom [])
        adopted {:seon.source/reloaded-namespaces [] :seon.source/arming-identities #{}}
        result (fn [label] {:seon.test/sym (symbol "seon.cluster.adoption-exclusion-test" label)
                            :seon.test/pass-count 1 :seon.test/fail-count 0 :seon.test/error-count 0})
        entered (promise)
        release (promise)
        member (future (cluster/call-excluding-adoption
                        lock (fn [] (let [seen @program]
                                      (deliver entered true)
                                      @release
                                      (swap! events conj [:member seen @program])
                                      (result "member")))))
        _ (is (true? (deref entered 1000 false)) "the member started")
        adoption (future (change lock database (fn [] (reset! program :after)
                                                 (swap! events conj [:adopted])
                                                 adopted)))
        _ (is (queued? lock 1) "the adoption waits for the running member")
        late (future (cluster/call-excluding-adoption
                      lock (fn [] (swap! events conj [:late @program]) (result "late"))))]
    (is (queued? lock 2) "a member starting after the queued adoption waits behind it")
    (is (= :before @program) "nothing was reloaded or armed under the member")
    (deliver release true)
    (is (= "member" (name (:seon.test/sym (deref member 1000 nil)))))
    (is (= adopted (deref adoption 1000 nil)) "the adoption completes after the member exits")
    (is (= "late" (name (:seon.test/sym (deref late 1000 nil)))))
    (is (= [[:member :before :before] [:adopted] [:late :after]] @events)
        "member, then adoption, then the later member on the adopted program")
    (testing "an adoption a member runs itself is its own work, not a wait on itself"
      (let [own (promise)]
        (is (= "own" (name (:seon.test/sym
                            (deref (future (cluster/call-excluding-adoption
                                            lock (fn [] (deliver own (change lock database (constantly adopted)))
                                                   (result "own"))))
                                   1000 nil)))))
        (is (= adopted (deref own 0 nil))))
      (is (zero? (.getReadLockCount lock)) "every member released its hold"))))
