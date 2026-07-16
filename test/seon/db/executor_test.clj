(ns seon.db.executor-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db.coordinate :as coordinate]
            [seon.db.executor :as executor]
            [seon.db.registry :as registry])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- call-private
  [name & arguments]
  (apply (var-get (ns-resolve 'seon.db.executor name)) arguments))

(defn- test-capacity [processors]
  (executor/capacity processors))

(defn- start-worker
  ([execute] (start-worker 1 execute))
  ([processors execute]
   (executor/start! {::executor/capacity (test-capacity processors)
                     ::executor/execute execute})))

(defn- await-queued
  [worker expected]
  (loop [attempt 0]
    (cond
      (= expected (::executor/queued (executor/evidence worker))) true
      (< attempt 10000) (do (Thread/yield) (recur (inc attempt)))
      :else false)))

(deftest capacity-is-one-bounded-startup-decision
  (let [two (test-capacity 2)
        four (test-capacity 4)
        eight (test-capacity 8)]
    (is (= [1 3 7] (mapv ::executor/cpu-workers [two four eight])))
    (is (= [2 4 6]
           (mapv #(get-in % [::executor/classes :provider
                             ::executor/maximum-active])
                 [two four eight])))
    (is (every? #(= (* 4 1024 1024) (::executor/maximum-request-bytes %))
                [two four eight]))))

(defn- request
  [worker database-name scope job-id value]
  {::executor/executor worker
   ::executor/work-class :read
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

(defn- registry-scope
  [database-name]
  (let [{::registry/keys [conn attachment]}
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})
        identity (d/committed-value-identity (d/db conn))]
    {::executor/database-name database-name
     ::coordinate/attachment attachment
     ::executor/connection-id (:datahike.value/connection-id identity)
     ::executor/generation (:datahike.value/generation identity)}))

(deftest one-start-owned-execute-function-serves-data-only-jobs
  (let [worker (start-worker {:read :request/value})
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
        worker (start-worker
                {:read (fn [request]
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
        worker (start-worker
                {:read (fn [request]
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

(deftest exact-scope-drain-cancels-running-and-waits-for-worker-release
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        canceled (atom [])
        worker
        (start-worker
         {:read (fn [request]
                  (.countDown entered)
                  (.await release)
                  (:request/value request))})
        scope (scope "a" (random-uuid) (random-uuid))
        running (executor/submit-async!
                 (request worker "a" scope "read/running" :value))]
    (try
      (is (.await entered 5 TimeUnit/SECONDS))
      (let [queued (executor/submit-async!
                    (request worker "a" scope "read/queued" :queued))
            _ (is (await-queued worker 1))
            drained
            (future
              (executor/fence-and-drain!
               {::executor/executor worker
                ::executor/scope scope
                ::executor/cancel
                (fn [job-id]
                  (swap! canceled conj job-id)
                  (.countDown release))}))]
        (is (= {::executor/abandoned-count 1} @drained))
        (is (= ["read/running"] @canceled))
        (is (= [::executor/value :value] @running))
        (is (= ::executor/throwable (first @queued)))
        (is (zero? (::executor/running (executor/evidence worker))))
        (is (zero? (::executor/retained-identities
                    (executor/evidence worker)))))
      (finally
        (.countDown release)
        (executor/stop! {::executor/executor worker})))))

(deftest cancellation-distinguishes-queued-running-and-absent-jobs
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        worker
        (start-worker
         {:read (fn [request]
                  (.countDown entered)
                  (.await release)
                  (:request/value request))})
        scope (scope "a" (random-uuid) (random-uuid))
        running-request (request worker "a" scope "cancel/running" :running)
        running (executor/submit-async! running-request)]
    (try
      (is (.await entered 5 TimeUnit/SECONDS))
      (let [queued (executor/submit-async!
                    (request worker "a" scope "cancel/queued" :queued))]
        (is (await-queued worker 1))
        (is (= {::executor/cancellation :queued}
               (executor/cancel!
                {::executor/executor worker
                 ::executor/job-id "cancel/queued"})))
        (is (= ::executor/throwable (first @queued)))
        (is (= {::executor/cancellation :running
                ::executor/request (::executor/request running-request)}
               (executor/cancel!
                {::executor/executor worker
                 ::executor/job-id "cancel/running"})))
        (is (= {::executor/cancellation :not-found}
               (executor/cancel!
                {::executor/executor worker
                 ::executor/job-id "cancel/absent"}))))
      (finally
        (.countDown release)
        @running
        (executor/stop! {::executor/executor worker})))))

(deftest provider-waits-do-not-consume-the-cpu-worker
  (let [read-entered (CountDownLatch. 1)
        provider-entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        worker (start-worker
                2
                {:read (fn [_]
                         (.countDown read-entered)
                         (.await release)
                         :read)
                 :provider (fn [_]
                             (.countDown provider-entered)
                             (.await release)
                             :provider)})
        read-scope (scope "read" (random-uuid) (random-uuid))
        provider-scope (scope "provider" (random-uuid) (random-uuid))]
    (try
      (let [read-result (executor/submit-async!
                         (request worker "read" read-scope "cpu/read" :read))
            provider-result
            (executor/submit-async!
             (assoc (request worker "provider" provider-scope
                             "provider/wait" :provider)
                    ::executor/work-class :provider))]
        (is (.await read-entered 5 TimeUnit/SECONDS))
        (is (.await provider-entered 5 TimeUnit/SECONDS))
        (is (= {:read 1 :provider 1}
               (::executor/running-by-class (executor/evidence worker))))
        (.countDown release)
        (is (= [::executor/value :read] @read-result))
        (is (= [::executor/value :provider] @provider-result)))
      (finally
        (.countDown release)
        (executor/stop! {::executor/executor worker})))))

(deftest queued-request-bytes-are-bounded-globally
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        cap (assoc (test-capacity 2)
                   ::executor/maximum-request-bytes 10
                   ::executor/maximum-queued-request-bytes 10)
        worker (executor/start!
                {::executor/capacity cap
                 ::executor/execute
                 {:read (fn [request]
                          (when (:request/block? request)
                            (.countDown entered)
                            (.await release))
                          (:request/value request))}})
        one-scope (scope "a" (random-uuid) (random-uuid))]
    (try
      (let [running (executor/submit-async!
                     (assoc-in (request worker "a" one-scope "bytes/running" :a)
                               [::executor/request :request/block?] true))]
        (is (.await entered 5 TimeUnit/SECONDS))
        (is (= {::executor/accepted? true ::executor/joined? false}
               (executor/try-submit!
                (assoc (request worker "a" one-scope "bytes/queued" :b)
                       ::executor/request-bytes 8))))
        (is (= 8 (::executor/queued-request-bytes
                  (executor/evidence worker))))
        (is (= {::executor/accepted? false ::executor/joined? false}
               (executor/try-submit!
                (assoc (request worker "a" one-scope "bytes/rejected" :c)
                       ::executor/request-bytes 3))))
        (.countDown release)
        (is (= [::executor/value :a] @running)))
      (finally
        (.countDown release)
        (executor/stop! {::executor/executor worker})))))

(deftest cpu-selection-rotates-class-then-database
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        selected (atom [])
        execute (fn [work-class]
                  (fn [request]
                    (swap! selected conj [work-class (:request/value request)])
                    (when (= :first (:request/value request))
                      (.countDown entered)
                      (.await release))
                    (:request/value request)))
        worker (start-worker 2 {:read (execute :read)
                                :knn (execute :knn)
                                :encode (execute :encode)})
        a (scope "a" (random-uuid) (random-uuid))
        b (scope "b" (random-uuid) (random-uuid))]
    (try
      (let [first (executor/submit-async! (request worker "a" a "fair/first" :first))]
        (is (.await entered 5 TimeUnit/SECONDS))
        (let [jobs [(executor/submit-async!
                     (assoc (request worker "a" a "fair/knn" :knn)
                            ::executor/work-class :knn))
                    (executor/submit-async!
                     (assoc (request worker "b" b "fair/encode" :encode)
                            ::executor/work-class :encode))
                    (executor/submit-async!
                     (request worker "b" b "fair/read" :read))]]
          (.countDown release)
          @first
          (run! deref jobs)
          (is (= [[:read :first] [:knn :knn] [:encode :encode] [:read :read]]
                 @selected))))
      (finally
        (.countDown release)
        (executor/stop! {::executor/executor worker})))))

(deftest real-independent-database-reads-use-the-shared-workers-in-parallel
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})
        database-names (mapv #(str "executor-real-" % "-" (random-uuid))
                             (range 8))
        entered (CountDownLatch. 4)
        release (CountDownLatch. 1)
        running (atom 0)
        peak (atom 0)
        worker
        (start-worker
         5
         {:read
          (fn [{:request/keys [database-name]}]
            (let [{::registry/keys [conn]}
                  (registry/resolve-connection
                   {::registry/database-name (keyword database-name)})
                  active (swap! running inc)]
              (swap! peak max active)
              (.countDown entered)
              (.await release)
              (try
                (d/q '[:find (count ?entity) .
                       :where [?entity :executor.test/value]]
                     (d/db conn))
                (finally
                  (swap! running dec)))))} )]
    (try
      (doseq [[index database-name] (map-indexed vector database-names)]
        (let [entry
              (registry/ensure-database!
               {::registry/database-name (keyword database-name)
                ::registry/backend :memory
                ::registry/initialize-connection!
                (fn [{::registry/keys [conn]}]
                  (d/transact
                   conn
                   [{:db/ident :executor.test/value
                     :db/valueType :db.type/long
                     :db/cardinality :db.cardinality/one}
                    {:executor.test/value index}]))})]
          (is (= (::registry/attachment entry)
                 (::coordinate/attachment (registry-scope database-name))))))
      (let [results
            (mapv
             (fn [database-name]
               (executor/submit-async!
                {::executor/executor worker
                 ::executor/work-class :read
                 ::executor/database-name database-name
                 ::executor/scope (registry-scope database-name)
                 ::executor/job-id (str "read/" database-name)
                 ::executor/request {:request/database-name database-name}}))
             database-names)]
        (is (.await entered 5 TimeUnit/SECONDS)
            "four immutable reads from independent databases start together")
        (is (= 4 @peak)
            "worker count, not database count, bounds parallel CPU work")
        (.countDown release)
        (is (= (repeat 8 [::executor/value 1]) (map deref results)))
        (is (zero? (::executor/retained-identities
                    (executor/evidence worker)))))
      (finally
        (.countDown release)
        (executor/stop! {::executor/executor worker})
        (doseq [database-name database-names]
          (registry/release-database!
           {::registry/database-name (keyword database-name)}))
        (registry/restore-registry! {::registry/snapshot snapshot})))))
