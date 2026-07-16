(ns seon.db.executor-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
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
   (start-worker processors execute (fn [_completion] nil)))
  ([processors execute complete!]
   (executor/start! {::executor/capacity (test-capacity processors)
                     ::executor/execute execute
                     ::executor/complete! complete!})))

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

(deftest invalid-capacity-is-rejected-before-workers-start
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"invalid process bound"
       (executor/start!
        {::executor/capacity (assoc (test-capacity 2)
                                    ::executor/maximum-request-bytes 0)
         ::executor/execute {:read :request/value}})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"absent or invalid"
       (executor/start!
        {::executor/capacity (update (test-capacity 2)
                                    ::executor/classes dissoc :read)
         ::executor/execute {:read :request/value}}))))

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

(deftest completed-database-names-leave-the-ready-queue
  (let [worker (start-worker {:read :request/value})]
    (try
      (doseq [index (range 512)
              :let [database-name (str "churn-" index)
                    exact-scope
                    (scope database-name (random-uuid) (random-uuid))]]
        (is (= index
               (executor/submit!
                (request worker database-name exact-scope
                         (str "churn/job-" index) index)))))
      (let [ready (get-in @(::executor/state worker)
                          [::executor/ready :read])]
        (is (empty? (::executor/database-order ready)))
        (is (empty? (::executor/by-database ready))))
      (is (zero? (::executor/queued (executor/evidence worker))))
      (is (zero? (::executor/running (executor/evidence worker))))
      (finally
        (executor/stop! {::executor/executor worker})))))

(deftest one-start-owned-completion-runs-once-outside-the-executor-lock
  (let [worker* (atom nil)
        completions (atom [])
        reentered (promise)
        second-completed (promise)
        database-id (random-uuid)
        generation (random-uuid)
        exact-scope (scope "a" database-id generation)
        complete!
        (fn [completion]
          (swap! completions conj completion)
          (case (::executor/job-id completion)
            "callback/first"
            (deliver
             reentered
             (executor/try-submit!
              (request @worker* "a" exact-scope "callback/second" :second)))

            "callback/second"
            (deliver second-completed completion)

            nil))
        worker (start-worker 1 {:read :request/value} complete!)]
    (reset! worker* worker)
    (try
      (is (= :first
             (executor/submit!
              (request worker "a" exact-scope "callback/first" :first))))
      (is (= {::executor/accepted? true ::executor/joined? false}
             (deref reentered 5000 nil))
          "completion can synchronously reenter admission")
      (is (= [::executor/value :second]
             (::executor/outcome (deref second-completed 5000 nil))))
      (is (= ["callback/first" "callback/second"]
             (mapv ::executor/job-id @completions)))
      (finally
        (executor/stop! {::executor/executor worker})))))

(deftest completion-distinguishes-queued-from-physically-running-cancellation
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        completions (atom [])
        worker
        (start-worker
         1
         {:read (fn [request]
                  (when (:request/block? request)
                    (.countDown entered)
                    (.await release))
                  (:request/value request))}
         #(swap! completions conj %))
        exact-scope (scope "a" (random-uuid) (random-uuid))
        running-request
        (assoc-in (request worker "a" exact-scope "cancel/running-callback" :run)
                  [::executor/request :request/block?] true)
        running (executor/submit-async! running-request)]
    (try
      (is (.await entered 5 TimeUnit/SECONDS))
      (let [queued
            (executor/submit-async!
             (request worker "a" exact-scope "cancel/queued-callback" :queued))]
        (is (await-queued worker 1))
        (is (= {::executor/cancellation :queued}
               (executor/cancel!
                {::executor/executor worker
                 ::executor/job-id "cancel/queued-callback"})))
        (is (= ::executor/throwable (first @queued)))
        (is (= ["cancel/queued-callback"]
               (mapv ::executor/job-id @completions)))
        (is (= :running
               (::executor/cancellation
                (executor/cancel!
                 {::executor/executor worker
                  ::executor/job-id "cancel/running-callback"}))))
        (is (= ::executor/throwable (first @running)))
        (is (= ["cancel/queued-callback"]
               (mapv ::executor/job-id @completions))
            "running cancellation does not claim physical completion")
        (.countDown release)
        (loop [attempt 0]
          (when (and (< attempt 10000) (< (count @completions) 2))
            (Thread/yield)
            (recur (inc attempt))))
        (is (= ["cancel/queued-callback" "cancel/running-callback"]
               (mapv ::executor/job-id @completions)))
        (is (= ::executor/throwable
               (first (::executor/outcome (second @completions))))))
      (finally
        (.countDown release)
        (executor/stop! {::executor/executor worker})))))

(deftest public-request-cancellation-does-not-require-executor-retention
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        worker
        (start-worker
         1
         {:read (fn [request]
                  (when (:request/block? request)
                    (.countDown entered)
                    (.await release))
                  (:request/value request))})
        exact-scope (scope "a" (random-uuid) (random-uuid))
        request-id "cancel/request-owned"
        running
        (executor/submit-async!
         (assoc (request worker "a" exact-scope [request-id 0] :running)
                ::executor/request-id request-id
                ::executor/request {:request/value :running
                                    :request/block? true}))]
    (try
      (is (.await entered 5 TimeUnit/SECONDS))
      (let [queued
            (executor/submit-async!
             (assoc (request worker "a" exact-scope [request-id 1] :queued)
                    ::executor/request-id request-id))]
        (is (await-queued worker 1))
        (is (= :running
               (::executor/cancellation
                (executor/cancel-request!
                 {::executor/executor worker
                  ::executor/request-id request-id}))))
        (is (= ::executor/throwable (first @queued)))
        (is (= ::executor/throwable (first @running))))
      (finally
        (.countDown release)
        (executor/stop! {::executor/executor worker})))))

(deftest rejection-and-abandoned-work-each-complete-once
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        completions (atom [])
        worker
        (start-worker
         1
         {:read (fn [_request]
                  (.countDown entered)
                  (.await release)
                  :late)}
         #(swap! completions conj %))
        exact-scope (scope "a" (random-uuid) (random-uuid))]
    (try
      (let [rejected
            (executor/submit-async!
             (assoc (request worker "a" exact-scope "callback/rejected" :x)
                    ::executor/request-bytes
                    (inc (::executor/maximum-request-bytes
                          (test-capacity 1)))))]
        (is (= ::executor/throwable (first @rejected)))
        (is (= ["callback/rejected"]
               (mapv ::executor/job-id @completions))))
      (let [running
            (executor/submit-async!
             (request worker "a" exact-scope "callback/abandoned" :late))]
        (is (.await entered 5 TimeUnit/SECONDS))
        (is (= {::executor/abandoned-count 0}
               (executor/remove-database!
                {::executor/executor worker ::executor/scope exact-scope})))
        (is (= ::executor/throwable (first @running)))
        (is (= ["callback/rejected" "callback/abandoned"]
               (mapv ::executor/job-id @completions)))
        (.countDown release)
        (loop [attempt 0]
          (when (and (< attempt 10000)
                     (pos? (::executor/running (executor/evidence worker))))
            (Thread/yield)
            (recur (inc attempt))))
        (is (= ["callback/rejected" "callback/abandoned"]
               (mapv ::executor/job-id @completions))
            "the late abandoned worker cannot publish a second completion"))
      (finally
        (.countDown release)
        (executor/stop! {::executor/executor worker})))))

(deftest throwing-completion-cannot-leak-executor-accounting
  (let [worker (start-worker 1 {:read :request/value}
                             (fn [_completion]
                               (throw (ex-info "injected completion failure" {}))))
        exact-scope (scope "a" (random-uuid) (random-uuid))]
    (try
      (is (= :value
             (executor/submit!
              (request worker "a" exact-scope "callback/throws" :value))))
      (is (zero? (::executor/retained-identities (executor/evidence worker))))
      (is (zero? (::executor/running (executor/evidence worker))))
      (finally
        (executor/stop! {::executor/executor worker})))))

(deftest nonblocking-results-retain-capacity-through-physical-completion
  (let [result-port (async/promise-chan)
        completions (atom [])
        worker (start-worker 1 {:mutation (fn [_request] result-port)}
                             #(swap! completions conj %))
        exact-scope (scope "a" (random-uuid) (random-uuid))
        result
        (executor/submit-async!
         (assoc (request worker "a" exact-scope "mutation/async" nil)
                ::executor/work-class :mutation))]
    (try
      (loop [attempt 0]
        (when (and (< attempt 10000)
                   (zero? (::executor/running (executor/evidence worker))))
          (Thread/yield)
          (recur (inc attempt))))
      (is (= {:mutation 1}
             (::executor/running-by-class (executor/evidence worker))))
      (is (empty? @completions))
      (is (async/put! result-port :committed))
      (is (= [::executor/value :committed] (deref result 5000 nil)))
      (loop [attempt 0]
        (when (and (< attempt 10000)
                   (pos? (::executor/running (executor/evidence worker))))
          (Thread/yield)
          (recur (inc attempt))))
      (is (= ["mutation/async"] (mapv ::executor/job-id @completions)))
      (is (zero? (::executor/running (executor/evidence worker))))
      (finally
        (async/close! result-port)
        (executor/stop! {::executor/executor worker})))))

(deftest closed-nonblocking-result-completes-as-an-error
  (let [result-port (async/promise-chan)
        completion (promise)
        worker (start-worker 1 {:mutation (fn [_request] result-port)}
                             #(deliver completion %))
        exact-scope (scope "a" (random-uuid) (random-uuid))
        result
        (executor/submit-async!
         (assoc (request worker "a" exact-scope "mutation/closed" nil)
                ::executor/work-class :mutation))]
    (try
      (async/close! result-port)
      (is (= ::executor/throwable (first (deref result 5000 nil))))
      (is (= ::executor/throwable
             (first (::executor/outcome (deref completion 5000 nil)))))
      (is (zero? (::executor/running (executor/evidence worker))))
      (finally
        (executor/stop! {::executor/executor worker})))))

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

(deftest released-database-scope-does-not-retain-its-fence
  (let [worker (start-worker {:read :request/value})
        closed-scope (scope "a" (random-uuid) (random-uuid))]
    (try
      (is (= {::executor/abandoned-count 0}
             (executor/fence-and-drain!
              {::executor/executor worker
               ::executor/scope closed-scope
               ::executor/cancel (constantly nil)})))
      (is (= 1 (::executor/fenced-scopes (executor/evidence worker))))
      (is (= {::executor/accepted? false ::executor/joined? false}
             (executor/try-submit!
              (request worker "a" closed-scope "closed" :closed))))
      (is (nil? (executor/release-scope!
                 {::executor/executor worker ::executor/scope closed-scope})))
      (is (zero? (::executor/fenced-scopes (executor/evidence worker))))
      (is (= :open
             (executor/submit!
              (request worker "a" closed-scope "reopened" :open))))
      (finally
        (executor/stop! {::executor/executor worker})))))

(deftest retained-request-closes-the-resolution-to-member-release-gap
  (let [worker (start-worker {:read :request/value})
        one-scope (scope "a" (random-uuid) (random-uuid))
        request-id "many/retained"]
    (try
      (is (nil? (executor/retain-request!
                 {::executor/executor worker
                  ::executor/scope one-scope
                  ::executor/request-id request-id})))
      (is (= 1 (::executor/retained-identities (executor/evidence worker))))
      (let [drained (future
                      (executor/fence-and-drain!
                       {::executor/executor worker
                        ::executor/scope one-scope
                        ::executor/cancel (constantly nil)}))]
        (Thread/sleep 25)
        (is (not (realized? drained))
            "scope release waits while a request owns the resolved value")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"still owns"
             (executor/release-scope!
              {::executor/executor worker ::executor/scope one-scope})))
        (is (nil? (executor/release-request!
                   {::executor/executor worker
                    ::executor/request-id request-id})))
        (is (= {::executor/abandoned-count 0} @drained))
        (is (zero? (::executor/retained-identities
                    (executor/evidence worker)))))
      (finally
        (executor/stop! {::executor/executor worker})))))

(deftest canceling-one-request-removes-all-of-its-queued-jobs
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        worker (start-worker
                {:read (fn [request]
                         (.countDown entered)
                         (.await release)
                         (:request/value request))})
        one-scope (scope "a" (random-uuid) (random-uuid))
        request-id "many/cancel"
        submit (fn [job-id value]
                 (executor/submit-async!
                  (assoc (request worker "a" one-scope job-id value)
                         ::executor/request-id request-id)))]
    (try
      (executor/retain-request!
       {::executor/executor worker ::executor/scope one-scope
        ::executor/request-id request-id})
      (let [running (submit "many/running" :running)
            _ (is (.await entered 5 TimeUnit/SECONDS))
            queued-a (submit "many/a" :a)
            queued-b (submit "many/b" :b)
            _ (is (await-queued worker 2))
            canceled (executor/cancel-request!
                      {::executor/executor worker
                       ::executor/request-id request-id})]
        (is (= :running (::executor/cancellation canceled)))
        (is (= 1 (count (::executor/requests canceled))))
        (is (executor/request-canceled?
             {::executor/executor worker ::executor/request-id request-id}))
        (is (= ::executor/throwable (first @queued-a)))
        (is (= ::executor/throwable (first @queued-b)))
        (.countDown release)
        (is (= ::executor/throwable (first @running)))
        (executor/release-request!
         {::executor/executor worker ::executor/request-id request-id}))
      (finally
        (.countDown release)
        (executor/stop! {::executor/executor worker})))))

(deftest scope-fence-reports-removed-members-to-the-retained-request
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        worker (start-worker
                {:read (fn [request]
                         (.countDown entered)
                         (.await release)
                         (:request/value request))})
        one-scope (scope "a" (random-uuid) (random-uuid))
        request-id "many/release"
        submit (fn [job-id]
                 (executor/submit-async!
                  (assoc (request worker "a" one-scope job-id job-id)
                         ::executor/request-id request-id)))]
    (try
      (executor/retain-request!
       {::executor/executor worker ::executor/scope one-scope
        ::executor/request-id request-id})
      (let [running (submit "many/release-running")
            _ (is (.await entered 5 TimeUnit/SECONDS))
            queued (submit "many/release-queued")
            _ (is (await-queued worker 1))
            drained (future
                      (executor/fence-and-drain!
                       {::executor/executor worker
                        ::executor/scope one-scope
                        ::executor/cancel (constantly nil)}))]
        (is (= "many/release-queued"
               (executor/await-completed!
                {::executor/executor worker ::executor/request-id request-id})))
        (is (= ::executor/throwable (first @queued)))
        (.countDown release)
        (is (= [::executor/value "many/release-running"] @running))
        (is (= "many/release-running"
               (executor/await-completed!
                {::executor/executor worker ::executor/request-id request-id})))
        (executor/release-request!
         {::executor/executor worker ::executor/request-id request-id})
        (is (= {::executor/abandoned-count 1} @drained)))
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

(deftest one-job-transitions-from-provider-to-knn-without-an-identity-gap
  (let [provider-entered (CountDownLatch. 1)
        release-provider (CountDownLatch. 1)
        knn-entered (CountDownLatch. 1)
        release-knn (CountDownLatch. 1)
        calls (atom [])
        completions (atom [])
        worker
        (start-worker
         2
         {:provider (fn [request]
                      (swap! calls conj [:provider request])
                      (.countDown provider-entered)
                      (.await release-provider)
                      (executor/continue-with :knn {:request/vector [1.0]}))
          :knn (fn [request]
                 (swap! calls conj [:knn request])
                 (.countDown knn-entered)
                 (.await release-knn)
                 :hits)}
         #(swap! completions conj %))
        one-scope (scope "a" (random-uuid) (random-uuid))
        submission (assoc (request worker "a" one-scope "semantic/1" :query)
                          ::executor/work-class :provider
                          ::executor/reserved-work-class :knn
                          ::executor/reserved-request-bytes 65536)]
    (try
      (let [result (executor/submit-async! submission)]
        (is (.await provider-entered 5 TimeUnit/SECONDS))
        (is (identical? result (executor/submit-async! submission)))
        (.countDown release-provider)
        (is (.await knn-entered 5 TimeUnit/SECONDS))
        (is (= {:knn 1}
               (::executor/running-by-class (executor/evidence worker))))
        (is (= 1 (::executor/retained-identities (executor/evidence worker))))
        (.countDown release-knn)
        (is (= [::executor/value :hits] @result))
        (is (= [[:provider {:request/value :query}]
                [:knn {:request/vector [1.0]}]]
               @calls))
        (is (= ["semantic/1"] (mapv ::executor/job-id @completions))
            "the provider phase emits no intermediate completion")
        (is (zero? (::executor/retained-identities
                    (executor/evidence worker)))))
      (finally
        (.countDown release-provider)
        (.countDown release-knn)
        (executor/stop! {::executor/executor worker})))))

(deftest canceled-provider-cannot-resurrect-as-knn
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        knn-calls (atom 0)
        worker
        (start-worker
         2
         {:provider (fn [_]
                      (.countDown entered)
                      (.await release)
                      (executor/continue-with :knn {:request/vector [1.0]}))
          :knn (fn [_] (swap! knn-calls inc) :hits)})
        one-scope (scope "a" (random-uuid) (random-uuid))
        submission (assoc (request worker "a" one-scope "semantic/cancel" :query)
                          ::executor/work-class :provider
                          ::executor/reserved-work-class :knn
                          ::executor/reserved-request-bytes 65536)]
    (try
      (let [result (executor/submit-async! submission)]
        (is (.await entered 5 TimeUnit/SECONDS))
        (is (= :running
               (::executor/cancellation
                (executor/cancel!
                 {::executor/executor worker
                  ::executor/job-id "semantic/cancel"}))))
        (is (= ::executor/throwable (first @result)))
        (.countDown release)
        (loop [attempt 0]
          (when (and (pos? (::executor/retained-identities
                            (executor/evidence worker)))
                     (< attempt 10000))
            (Thread/yield)
            (recur (inc attempt))))
        (is (zero? @knn-calls))
        (is (zero? (::executor/retained-identities
                    (executor/evidence worker)))))
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
                                :hnsw (execute :hnsw)})
        a (scope "a" (random-uuid) (random-uuid))
        b (scope "b" (random-uuid) (random-uuid))]
    (try
      (let [first (executor/submit-async! (request worker "a" a "fair/first" :first))]
        (is (.await entered 5 TimeUnit/SECONDS))
        (let [jobs [(executor/submit-async!
                     (assoc (request worker "a" a "fair/knn" :knn)
                            ::executor/work-class :knn))
                    (executor/submit-async!
                     (assoc (request worker "b" b "fair/hnsw" :hnsw)
                            ::executor/work-class :hnsw))
                    (executor/submit-async!
                     (request worker "b" b "fair/read" :read))]]
          (.countDown release)
          @first
          (run! deref jobs)
          (is (= [[:read :first] [:knn :knn] [:hnsw :hnsw] [:read :read]]
                 @selected))))
      (finally
        (.countDown release)
        (executor/stop! {::executor/executor worker})))))

(deftest mutations-serialize-per-database-and-progress-across-databases
  (let [entered (CountDownLatch. 2)
        release (CountDownLatch. 1)
        active (atom {})
        peak-by-database (atom {})
        execute (fn [request]
                  (let [database (:request/database request)
                        running (get (swap! active update database (fnil inc 0))
                                     database)]
                    (swap! peak-by-database update database (fnil max 0) running)
                    (.countDown entered)
                    (.await release)
                    (swap! active update database dec)
                    database))
        worker (start-worker 4 {:mutation execute})
        a (scope "a" (random-uuid) (random-uuid))
        b (scope "b" (random-uuid) (random-uuid))
        submit (fn [database-name one-scope job-id]
                 (executor/submit-async!
                  (assoc (request worker database-name one-scope job-id
                                  database-name)
                         ::executor/work-class :mutation
                         ::executor/request {:request/database database-name})))]
    (try
      (let [a1 (submit "a" a "mutation/a1")
            a2 (submit "a" a "mutation/a2")
            b1 (submit "b" b "mutation/b1")]
        (is (.await entered 5 TimeUnit/SECONDS)
            "independent database mutations start together")
        (is (= {"a" 1 "b" 1} @peak-by-database))
        (is (= {"a" 1 "b" 1}
               (::executor/running-by-database (executor/evidence worker))))
        (.countDown release)
        (is (= [::executor/value "a"] @a1))
        (is (= [::executor/value "a"] @a2))
        (is (= [::executor/value "b"] @b1)))
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
