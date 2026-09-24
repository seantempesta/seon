(ns seon.cluster.adoption-exclusion-test
  "No development adoption reloads or arms under a running test member:
  members hold `seon.cluster/call-excluding-adoption`, adoption waits in
  `call-with-program-change`, and a member queued behind it starts after it."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test]
            [seon.test-support :as support]))

(defn- queued?
  "Wait up to one second for `expected` threads queued on `lock`."
  {:malli/schema [:=> [:cat :seon.cluster/program-change-lock [:int {:min 1}]] :boolean]}
  [^java.util.concurrent.locks.ReentrantReadWriteLock lock expected]
  (let [deadline (+ (System/nanoTime) 1000000000)]
    (loop []
      (cond (>= (.getQueueLength lock) expected) true
            (> (System/nanoTime) deadline) false
            :else (do (Thread/onSpinWait) (recur))))))

(deftest a-member-running-across-an-adoption-sees-no-change-and-the-adoption-completes-after-it
  (let [lock (java.util.concurrent.locks.ReentrantReadWriteLock. true)
        validity (atom nil)
        commit (random-uuid)
        change (fn [lock database work]
                 (@#'cluster/call-with-program-change lock validity database commit work))
        database (db/db (:seon.db/connection (support/execution-handle nil)))
        program (atom :before)
        events (atom [])
        adopted {:seon.source/reloaded-namespaces [] :seon.source/arming-identities #{}}
        result (fn [label] {:seon.test/sym (symbol "seon.cluster.adoption-exclusion-test" label)
                            :seon.test/pass-count 1 :seon.test/fail-count 0 :seon.test/error-count 0})
        entered (promise)
        release (promise)
        member (future (cluster/call-excluding-adoption
                        lock validity (fn [] (let [seen @program]
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
                      lock validity (fn [] (swap! events conj [:late @program]) (result "late"))))]
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
                                            lock validity (fn [] (deliver own (change lock database (constantly adopted)))
                                                   (result "own"))))
                                   1000 nil)))))
        (is (= adopted (deref own 0 nil))))
      (is (zero? (.getReadLockCount lock)) "every member released its hold"))))

(deftest an-adoption-failing-after-roots-change-keeps-the-program-unavailable-until-one-records
  (let [lock (java.util.concurrent.locks.ReentrantReadWriteLock. true)
        validity (atom nil)
        database (db/db (:seon.db/connection (support/execution-handle nil)))
        adopted {:seon.source/reloaded-namespaces [] :seon.source/arming-identities #{}}
        entries (atom 0)
        member #(cluster/call-excluding-adoption
                 lock validity (fn [] (swap! entries inc)
                                 {:seon.test/sym `member :seon.test/pass-count 1
                                  :seon.test/fail-count 0 :seon.test/error-count 0}))
        adopt! (fn [commit work]
                 (try (@#'cluster/call-with-program-change lock validity database commit work)
                      (catch clojure.lang.ExceptionInfo failure (ex-message failure))))
        scratch "seon.cluster.adoption-exclusion-scratch"]
    (testing "a reload that fails after defining a Var"
      (let [commit (random-uuid)]
        (is (= "reload stopped midway"
               (adopt! commit #(load-string (str "(ns " scratch ") (def loaded :new)"
                                                 " (throw (ex-info \"reload stopped midway\" {}))")))))
        (is (= :new @(resolve (symbol scratch "loaded"))) "the root changed before the failure")
        (is (= commit (:seon.source/commit-id @validity)))
        (is (= ["reload stopped midway"] (keep :seon.error/message (:seon.error/chain @validity))))))
    (doseq [[label message] [["arming" "Development JVM instrumentation did not restore contracts."]
                             ["the record" "The cluster population transaction was refused: basis moved"]]]
      (testing (str label " refused after the reload")
        (let [commit (random-uuid)]
          (is (= message (adopt! commit #(@#'cluster/refused! message {:seon.cluster.source/phase :adoption}))))
          (is (= commit (:seon.source/commit-id @validity))))))
    (testing "no member enters the unknown program; it answers the diagnostic"
      (let [refusal (member)]
        (is (zero? @entries))
        (is (= (:seon.source/commit-id @validity) (:seon.source/commit-id refusal)))
        (is (schema/valid-candidate-value? (db/carried-projection database)
                                           :seon.test/not-runnable-error refusal))))
    (testing "a later adoption that records makes execution available again"
      (is (= adopted (adopt! (random-uuid) (constantly adopted))))
      (is (nil? @validity))
      (is (= `member (:seon.test/sym (member))))
      (is (= 1 @entries)))
    (remove-ns (symbol scratch))))

(deftest a-member-resolves-its-test-inside-the-exclusion-and-an-unknown-program-starts-nothing
  (let [lock (java.util.concurrent.locks.ReentrantReadWriteLock. true)
        validity (atom nil)
        database (db/db (:seon.db/connection (support/execution-handle nil)))
        owned (create-ns (gensym "seon.cluster.adoption-exclusion-test.member-"))
        probe (intern owned (with-meta 'probe {:test #(is true)}) nil)
        resolutions (atom [])
        resolve! #(do (swap! resolutions conj (::installed (meta probe))) probe)
        custody {:seon.sci.eval/ctx (:seon.sci.eval/ctx (support/execution-handle nil))}
        run! #(@#'seon.test/bounded-result lock validity (symbol probe) resolve! 5000 custody (fn [_] nil))]
    (try
      (testing "resolution waits for an adoption holding the exclusion and sees what it installed"
        (let [entered (promise) release (promise)
              adoption (future (@#'cluster/call-with-program-change
                                lock validity database (random-uuid)
                                (fn [] (deliver entered true) @release
                                  (alter-meta! probe assoc ::installed :adopted)
                                  {:seon.source/reloaded-namespaces [] :seon.source/arming-identities #{}})))
              _ (is (true? (deref entered 1000 false)))
              member (future (run!))]
          (is (queued? lock 1) "the member queues behind the adoption")
          (is (empty? @resolutions) "nothing resolved under the adoption")
          (deliver release true)
          @adoption
          (is (= 1 (:seon.test/pass-count (deref member 5000 nil))))
          (is (= [:adopted] @resolutions))))
      (testing "an unknown program resolves nothing and stops the request"
        (reset! resolutions [])
        (is (string? (try (@#'cluster/call-with-program-change lock validity database (random-uuid)
                            #(throw (ex-info "arming refused" {})))
                          (catch clojure.lang.ExceptionInfo failure (ex-message failure)))))
        (let [result (run!)]
          (is (= :program-unknown (:seon.test.run/stopped result)))
          (is (empty? @resolutions))))
      (finally (remove-ns (ns-name owned))))))
