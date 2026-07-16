(ns seon.db.executor-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db.coordinate :as coordinate]
            [seon.db.executor :as executor])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- call-private
  [name & arguments]
  (apply (var-get (ns-resolve 'seon.db.executor name)) arguments))

(defn- empty-ready [] (call-private 'empty-ready))
(defn- add-database [state database-name]
  (call-private 'add-database state database-name))
(defn- enqueue [state database-name request maximum-queued]
  (call-private 'enqueue state database-name request maximum-queued))
(defn- take-ready [state] (call-private 'take-ready state))
(defn- remove-database [state database-name]
  (call-private 'remove-database state database-name))

(defn- accepted
  [state database-name requests]
  (reduce
   (fn [current request]
     (let [[next-state accepted?]
           (enqueue current database-name request 32)]
       (is accepted?)
       next-state))
   state
   requests))

(defn- drain
  [state]
  (loop [current state
         requests []]
    (let [[next-state request] (take-ready current)]
      (if request
        (recur next-state (conj requests request))
        [next-state requests]))))

(defn- await-queued
  [worker expected]
  (loop [attempt 0]
    (cond
      (= expected (::executor/queued (executor/evidence worker))) true
      (< attempt 10000) (do (Thread/yield) (recur (inc attempt)))
      :else false)))

(deftest one-database-retains-arrival-order
  (let [state (accepted (empty-ready) "a" [:a1 :a2 :a3])
        [_ requests] (drain state)]
    (is (= [:a1 :a2 :a3] requests))))

(deftest databases-take-equal-turns
  (let [state (-> (empty-ready)
                  (accepted "a" [:a1 :a2 :a3])
                  (accepted "b" [:b1 :b2 :b3])
                  (accepted "c" [:c1 :c2 :c3]))
        [_ requests] (drain state)]
    (is (= [:a1 :b1 :c1 :a2 :b2 :c2 :a3 :b3 :c3]
           requests))))

(deftest a-busy-database-does-not-delay-a-sparse-database
  (loop [state (-> (empty-ready)
                   (accepted "a" [:a1])
                   (accepted "b" [:b1])
                   (accepted "c" [:c1]))
         selected []
         next-a 2]
    (if (= 5 (count selected))
      (is (= [:a1 :b1 :c1 :a2 :a3] selected))
      (let [[after-take request] (take-ready state)
            [after-enqueue accepted?]
            (enqueue after-take "a" (keyword (str "a" next-a)) 32)]
        (is accepted?)
        (recur after-enqueue (conj selected request) (inc next-a))))))

(deftest empty-databases-are-skipped-without-changing-requests
  (let [state (-> (empty-ready)
                  (add-database "a")
                  (add-database "b")
                  (accepted "c" [{:request/value 1}]))
        [after request] (take-ready state)]
    (is (= {:request/value 1} request))
    (is (= 0 (::executor/cursor after)))))

(deftest removing-a-database-adjusts-the-next-turn-and-reports-abandonment
  (let [state (-> (empty-ready)
                  (accepted "a" [:a1])
                  (accepted "b" [:b1 :b2])
                  (accepted "c" [:c1]))
        [after-a request] (take-ready state)
        [after-remove abandoned] (remove-database after-a "b")
        [_ remaining] (drain after-remove)]
    (is (= :a1 request))
    (is (= [:b1 :b2] (vec abandoned)))
    (is (= [:c1] remaining))))

(deftest bounded-enqueue-rejects-without-changing-the-queue
  (let [[one accepted?] (enqueue (empty-ready) "a" :a1 1)
        [rejected second-accepted?] (enqueue one "a" :a2 1)
        [_ requests] (drain rejected)]
    (is accepted?)
    (is (false? second-accepted?))
    (is (= one rejected))
    (is (= [:a1] requests))))

(deftest taking-from-an-empty-state-is-an-identity-transition
  (let [state (-> (empty-ready)
                  (add-database "a")
                  (add-database "b"))
        [same request] (take-ready state)]
    (is (identical? state same))
    (is (nil? request))))

(deftest independent-values-share-no-queue-state
  (let [left (accepted (empty-ready) "a" [:left])
        right (accepted (empty-ready) "a" [:right])
        [_ left-values] (drain left)
        [_ right-values] (drain right)]
    (is (= [:left] left-values))
    (is (= [:right] right-values))))

(defn- request
  [worker database-name scope job-id value]
  {::executor/executor worker
   ::executor/database-name database-name
   ::executor/scope scope
   ::executor/job-id job-id
   ::executor/request {:request/value value}})

(defn- scope
  [database-name database-id generation]
  {::executor/database-name database-name
   ::coordinate/attachment {::coordinate/database-id database-id
                            ::coordinate/branch :db}
   ::executor/connection-id [database-id :db]
   ::executor/generation generation})

(deftest one-start-owned-execute-function-serves-data-only-jobs
  (let [worker (executor/start! {::executor/name :query
                                 ::executor/workers 1
                                 ::executor/maximum-queued 4
                                 ::executor/execute :request/value})
        scope (scope "a" (random-uuid) (random-uuid))]
    (try
      (is (= :value (executor/submit!
                     (request worker "a" scope "job-1" :value))))
      (is (= {} (::executor/running-by-database
                 (executor/evidence worker))))
      (is (zero? (::executor/retained-identities
                  (executor/evidence worker))))
      (finally (executor/stop! {::executor/executor worker})))))

(deftest duplicate-jobs-join-one-running-result
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        calls (atom 0)
        worker (executor/start! {::executor/name :query
                                 ::executor/workers 1
                                 ::executor/maximum-queued 4
                                 ::executor/execute
                                 (fn [request]
                                   (swap! calls inc)
                                   (.countDown entered)
                                   (.await release)
                                   (:request/value request))})
        scope (scope "a" (random-uuid) (random-uuid))
        submission (request worker "a" scope "same-job" :value)]
    (try
      (let [first-result (executor/submit-async! submission)]
        (is (.await entered 5 TimeUnit/SECONDS))
        (is (= {::executor/accepted? false ::executor/joined? true}
               (executor/try-submit! submission)))
        (let [joined-result (executor/submit-async! submission)]
          (is (identical? first-result joined-result))
          (is (= {"a" 1} (::executor/running-by-database
                           (executor/evidence worker))))
          (.countDown release)
          (is (= [::executor/value :value] @first-result))
          (is (= 1 @calls))))
      (finally
        (.countDown release)
        (executor/stop! {::executor/executor worker})))))

(deftest exact-scope-close-settles-queued-and-fences-running-without-aba
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        worker (executor/start! {::executor/name :background
                                 ::executor/workers 1
                                 ::executor/maximum-queued 4
                                 ::executor/execute
                                 (fn [request]
                                   (when (:request/block? request)
                                     (.countDown entered)
                                     (.await release))
                                   (:request/value request))})
        database-id (random-uuid)
        old-scope (scope "a" database-id (random-uuid))
        new-scope (scope "a" database-id (random-uuid))
        running (executor/submit-async!
                 (assoc-in (request worker "a" old-scope "old-running" :old)
                           [::executor/request :request/block?] true))]
    (try
      (is (.await entered 5 TimeUnit/SECONDS))
      (let [queued (executor/submit-async!
                    (request worker "a" old-scope "old-queued" :queued))]
        (is (await-queued worker 1))
        (let [queued-work
              (peek (get-in @(::executor/state worker)
                            [::executor/ready "a"]))]
          (is (not-any? fn? (tree-seq coll? seq queued-work))
              "queued work contains no per-job callback or execute closure"))
        (is (= {::executor/abandoned-count 1}
               (executor/remove-database!
                {::executor/executor worker ::executor/scope old-scope})))
        (is (= ::executor/throwable (first @queued)))
        (is (= ::executor/throwable (first @running)))
        (is (zero? (::executor/retained-identities
                    (executor/evidence worker)))
            "scope close retains no old job identity while execution unwinds")
        (is (= {::executor/accepted? false ::executor/joined? false}
               (executor/try-submit!
                (request worker "a" old-scope "old-after-close" :stale)))
            "the closed generation cannot admit later work")
        (let [replacement (executor/submit-async!
                           (request worker "a" new-scope "new" :new))]
          (.countDown release)
          (is (= [::executor/value :new] @replacement))
          (is (zero? (::executor/retained-identities
                      (executor/evidence worker))))))
      (finally
        (.countDown release)
        (executor/stop! {::executor/executor worker})))))
