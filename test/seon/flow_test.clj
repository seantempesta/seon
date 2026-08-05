(ns seon.flow-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.core.async.flow :as flow]
            [clojure.core.async.flow-monitor :as flow-monitor]
            [clojure.datafy :as datafy]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as db]
            [datahike.core :as datahike]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.cluster.loop :as cluster.loop]
            [seon.config :as config]
            [seon.flow :as sut]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support])
  (:import [java.io File]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers
            WebSocket WebSocket$Listener]
           [java.nio.file ClosedWatchServiceException StandardWatchEventKinds
            WatchEvent$Kind WatchService]
           [java.util.concurrent CompletableFuture CountDownLatch
            ExecutorService Future TimeUnit]))

(def ^:private test-work-launcher (atom nil))

(def ^:private test-io-configuration
  {:seon.config.flow.io/queue-depth 2
   :seon.config.flow.io/concurrency 2})

(defn- install-test-work-launcher!
  [request]
  (let [launcher
        (sut/start-work-launcher!
         (update request ::sut/configuration
                 #(merge test-io-configuration %)))]
    (reset! test-work-launcher launcher)
    launcher))

(defn- stop-test-work-launcher!
  []
  (when-let [launcher
             (first (swap-vals! test-work-launcher (constantly nil)))]
    (sut/stop-work-launcher! launcher)))

(defn- submit-test!!
  [submission]
  (sut/submit!! @test-work-launcher submission))

(def ^:private callback-schema-keys
  [:seon.flow/commit-drop!
   :seon.flow/commit-fault!
   :seon.flow/compile-namespace-fn
   :seon.flow/deliver!
   :seon.flow/fix-step-fn
   :seon.flow/panic!
   :seon.flow/plan-step-fn
   :seon.flow/read-core-error-mode
   :seon.flow/read-sources
   :seon.flow/work-fn])

(deftest callback-contracts-construct-functions-that-honor-their-outputs
  (let [registry
        (:seon.schema.projection/registry (schema/current-projection))]
    (doseq [[ordinal schema-key] (map-indexed vector callback-schema-keys)]
      (testing (str schema-key)
        (let [compiled (m/schema schema-key {:registry registry})
              function-schema (m/deref compiled)
              {:keys [input output]} (m/-function-info function-schema)
              callback (mg/generate compiled {:seed (+ 2026072900 ordinal)})
              args (mg/generate input {:seed (+ 2026072910 ordinal)})
              result (apply callback args)]
          (is (ifn? callback))
          (is (m/validate compiled callback))
          (is (m/validate output result)
              (pr-str {:schema schema-key
                       :args args
                       :result result})))))))

(def ^:private fault-schema
  [{:db/ident ::core-error-config-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::on-core-error
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident ::fault-id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::fault-proc
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident ::fault-message
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident ::fault-drop-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::fault-drop-count
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}])

(defn- database-events
  [connection]
  (let [channel (async/chan 16)
        listener-key (random-uuid)]
    (datahike/listen! connection listener-key #(async/put! channel %))
    {::channel channel
     ::listener-key listener-key}))

(defn- stop-database-events!
  [connection {::keys [channel listener-key]}]
  (datahike/unlisten! connection listener-key)
  (async/close! channel))

(defn- testbed-procs
  [{::keys [parallelism active-evals deliver!]}]
  {:eval
   {:proc
    (sut/eval-proc
     {::sut/parallelism parallelism
      ::sut/active-evals active-evals
      ::sut/compute-timeout-ms 10000})
    :chan-opts
    {::sut/submission {:buf-or-n 2}}}
   :mailbox
   {:proc (sut/mailbox-proc {::sut/deliver! deliver!})
    :chan-opts
    {::sut/mailbox {:buf-or-n (async/sliding-buffer 1)}}}
   :observer
   {:proc
    (sut/capacity-observer-proc
     {::sut/parallelism parallelism
      ::sut/active-work active-evals})
    :chan-opts
    {::sut/observe {:buf-or-n 2}}}})

(defn- create-testbed-flow
  [procs compute-executor]
  (flow/create-flow
   {:procs procs
    :conns [[[:eval ::sut/result] [:mailbox ::sut/mailbox]]]
    :compute-exec compute-executor}))

(defn- stop-executor!
  [^ExecutorService executor]
  (.shutdownNow executor)
  (when-not (.awaitTermination
             executor test-support/event-backstop-seconds TimeUnit/SECONDS)
    (throw
     (ex-info
      "The bounded Flow compute executor did not terminate."
      {::event ::executor-termination}))))

(defn- eval-procs
  [count parallelism active-evals]
  (into {}
        (map
         (fn [ordinal]
           [(keyword (str "eval-" ordinal))
            {:proc
             (sut/eval-proc
              {::sut/parallelism parallelism
               ::sut/active-evals active-evals
               ::sut/compute-timeout-ms 10000})
             :chan-opts
             {::sut/submission {:buf-or-n 2}}}]))
        (range count)))

(defn- channel-data
  [graph pid input-id]
  (get-in (datafy/datafy graph) [:chans :ins [pid input-id]]))

(defn- work-message
  [completed value]
  {::sut/work-fn
   (fn [{::sut/keys [interrupt-fn]}]
     (interrupt-fn)
     (swap! completed conj value)
     value)
   ::sut/wedged? false})

(defn- single-eval-testbed
  [buffer-size]
  (let [parallelism 1
        compute-executor (sut/bounded-platform-executor parallelism)
        active-evals (atom {})
        completed (atom [])
        graph
        (flow/create-flow
         {:procs
          {:eval
           {:proc
            (sut/eval-proc
             {::sut/parallelism parallelism
              ::sut/active-evals active-evals
              ::sut/compute-timeout-ms 10000})
            :chan-opts
            {::sut/submission {:buf-or-n buffer-size}}}
           :sink
           {:proc (sut/mailbox-proc {::sut/deliver! (fn [_])})
            :chan-opts
            {::sut/mailbox {:buf-or-n 1}}}}
          :conns [[[:eval ::sut/result] [:sink ::sut/mailbox]]]
          :compute-exec compute-executor})]
    {::graph graph
     ::compute-executor compute-executor
     ::parallelism parallelism
     ::active-evals active-evals
     ::completed completed}))

(defn- stop-testbed!
  [{::keys [graph compute-executor]}]
  (try
    (flow/stop graph)
    (catch Throwable _))
  (stop-executor! compute-executor))

(defn- with-fault-database
  [body]
  (let [configuration
        {:store {:backend :memory :id (random-uuid)}
         :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (db/transact! connection fault-schema)
      (db/transact!
       connection
       [{::core-error-config-id "testbed"
         ::on-core-error :record}
        {::fault-drop-id "fault-committer"
         ::fault-drop-count 0}])
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- core-error-mode
  [connection]
  (db/q
   '[:find ?mode .
     :where
     [?config :seon.flow-test/core-error-config-id "testbed"]
     [?config :seon.flow-test/on-core-error ?mode]]
   @connection))

(defn- commit-fault!
  [connection fault]
  (let [message (ex-message (::flow/ex fault))
        signature (str (::flow/pid fault) "|" message)]
    (db/transact!
     connection
     [{::fault-id (random-uuid)
       ::fault-proc (::flow/pid fault)
       ::fault-message message}])
    [{:seon.error/signature signature} ::sut/committed]))

(defn- commit-fault-drop!
  [connection _fault]
  (let [count
        (db/q
         '[:find ?count .
           :where
           [?counter :seon.flow-test/fault-drop-id "fault-committer"]
           [?counter :seon.flow-test/fault-drop-count ?count]]
         @connection)]
    (db/transact!
     connection
     [{::fault-drop-id "fault-committer"
       ::fault-drop-count (inc count)}])))

(defn- committed-faults
  [database]
  (db/q
   '[:find ?proc ?message
     :where
     [?fault :seon.flow-test/fault-id]
     [?fault :seon.flow-test/fault-proc ?proc]
     [?fault :seon.flow-test/fault-message ?message]]
   database))

(defn- committed-drop-count
  [database]
  (db/q
   '[:find ?count .
     :where
     [?counter :seon.flow-test/fault-drop-id "fault-committer"]
     [?counter :seon.flow-test/fault-drop-count ?count]]
   database))

(deftest production-launcher-wedges-degrade-capacity-by-exactly-n
  (let [parallelism 4
        wedge-count 2
        wedge-started (CountDownLatch. wedge-count)
        release-wedges (CountDownLatch. 1)
        configuration
        {:seon.config.flow.compute/queue-depth 2
         :seon.config.flow.compute/concurrency parallelism}
        launcher
        (install-test-work-launcher!
         {::sut/configuration configuration})
        wedge-ids #{:wedge-0 :wedge-1}
        wedges
        (mapv
         (fn [submission-id]
           (future
             (submit-test!!
              {::sut/submission-id submission-id
               ::sut/workload :compute
               ::sut/time-limit-ms 25
               ::sut/work-fn
               (fn [{::sut/keys [started!]}]
                 (started!)
                 (.countDown wedge-started)
                 (test-support/await-event! release-wedges ::release-production-wedges)
                 ::released)})))
         wedge-ids)]
    (try
      (test-support/await-event! wedge-started ::production-wedges-started)
      (is (every? #(= ::sut/time-limit (::sut/outcome @%)) wedges))
      (let [observer-state
            (::flow/state
             (flow/ping-proc
              (::sut/graph launcher)
              ::sut/capacity-observer))]
        (is (= wedge-ids (::sut/active-submissions observer-state)))
        (is (= wedge-ids (::sut/wedged-submissions observer-state)))
        (is (= (- parallelism wedge-count)
               (::sut/available-capacity observer-state)))
        (is (false? (::sut/platform-threads? observer-state))))
      (let [remaining
            (mapv
             (fn [submission-id]
               (future
                 (submit-test!!
                  {::sut/submission-id submission-id
                   ::sut/workload :compute
                   ::sut/time-limit-ms 1000
                   ::sut/work-fn
                   (fn [{::sut/keys [started!]}]
                     (started!)
                     submission-id)})))
             [:remaining-0 :remaining-1])]
        (is (= #{:remaining-0 :remaining-1}
               (into #{} (map (comp ::sut/value deref)) remaining))
            "all and only the remaining compute slots make progress"))
      (finally
        (.countDown release-wedges)
        (dotimes [_ wedge-count]
          (test-support/await-event!
           (get-in launcher [::sut/started :report-chan])
           ::production-wedge-released
           #(contains? wedge-ids (::sut/submission-id %))))
        (is (empty? @(::sut/active-work launcher)))
        (stop-test-work-launcher!)))))

(deftest production-launcher-bounds-virtual-task-lifetimes
  (let [parallelism 2
        entered (CountDownLatch. parallelism)
        release (CountDownLatch. 1)
        active (atom 0)
        maximum-active (atom 0)
        virtual? (atom [])
        _ (install-test-work-launcher!
           {::sut/configuration
            {:seon.config.flow.compute/queue-depth 3
             :seon.config.flow.compute/concurrency parallelism}})
        submissions
        (mapv
         (fn [ordinal]
           (future
             (submit-test!!
              {::sut/submission-id (keyword (str "bounded-" ordinal))
               ::sut/workload :compute
               ::sut/time-limit-ms 5000
               ::sut/work-fn
               (fn [{::sut/keys [started!]}]
                 (started!)
                 (let [now-active (swap! active inc)]
                   (swap! maximum-active max now-active)
                   (swap! virtual? conj (.isVirtual (Thread/currentThread)))
                   (.countDown entered)
                   (try
                     (test-support/await-event! release ::release-bounded-work)
                     {:seon.flow-test/ordinal ordinal}
                     (finally
                       (swap! active dec)))))})))
         (range 5))]
    (try
      (test-support/await-event! entered ::bounded-work-entered)
      (is (= parallelism @active))
      (is (= parallelism @maximum-active))
      (.countDown release)
      (let [results (mapv deref submissions)]
        (is (= (set (range 5))
               (into #{}
                     (map #(get-in % [::sut/value
                                     :seon.flow-test/ordinal]))
                     results)))
        (is (every? #(= ::sut/completed (::sut/outcome %)) results))
        (is (= parallelism @maximum-active))
        (is (every? true? @virtual?)))
      (finally
        (.countDown release)
        (stop-test-work-launcher!)))))

(deftest background-io-submission-is-nonblocking-bounded-and-joined
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        virtual? (atom [])
        configuration
        {:seon.config.flow.compute/queue-depth 1
         :seon.config.flow.compute/concurrency 1
         :seon.config.flow.io/queue-depth 1
         :seon.config.flow.io/concurrency 1}
        launcher
        (sut/start-work-launcher! {::sut/configuration configuration})
        terminals (mapv (fn [_] (promise)) (range 3))
        submission
        (fn [ordinal]
          {::sut/submission-id (keyword (str "background-" ordinal))
           ::sut/workload :io
           ::sut/work-fn
           (fn [_]
             (swap! virtual? conj (.isVirtual (Thread/currentThread)))
             (.countDown entered)
             (test-support/await-event! release ::release-background-io)
             ordinal)
           ::sut/complete!
           #(deliver (nth terminals ordinal) %)})]
    (try
      (is (true? (sut/submit! launcher (submission 0))))
      (test-support/await-event! entered ::background-io-entered)
      (is (true? (sut/submit! launcher (submission 1))))
      (is (true? (sut/submit! launcher (submission 2)))
          "submission returns after injection rather than after work")
      (let [refused
            (test-support/await-event!
             (future @(nth terminals 2))
             ::background-io-refused)]
        (is (= ::sut/submission-capacity
               (get-in refused [::sut/value :seon.error/kind]))))
      (sut/stop-work-launcher! launcher)
      (is (every? realized? (take 2 terminals))
          "stop joins terminal callbacks for every accepted submission")
      (is (every? true? @virtual?))
      (finally
        (.countDown release)
        (when-not (every? realized? (take 2 terminals))
          (sut/stop-work-launcher! launcher))))))

(deftest submission-time-limit-covers-the-pre-start-wait
  (testing "paused before start"
    (let [launcher
          (install-test-work-launcher!
           {::sut/configuration
            {:seon.config.flow.compute/queue-depth 2
             :seon.config.flow.compute/concurrency 1}})
          graph (::sut/graph launcher)]
      (try
        (flow/pause graph)
        (is (= :paused
               (::flow/status
                (flow/ping-proc graph ::sut/work-launcher)))
            "the launcher observes pause before the submission")
        (let [result
              (deref
               (future
                 (submit-test!!
                  {::sut/submission-id ::paused-before-start
                   ::sut/workload :compute
                   ::sut/time-limit-ms 30
                   ::sut/work-fn (fn [_] ::unexpected-start)}))
               (* 1000 test-support/event-backstop-seconds)
               ::did-not-settle)]
          (is (= ::sut/time-limit (::sut/outcome result))))
        (finally
          (flow/resume graph)
          (stop-test-work-launcher!)))))
  (testing "queued behind a fully occupied owner"
    (let [entered (CountDownLatch. 1)
          release (CountDownLatch. 1)
          _ (install-test-work-launcher!
             {::sut/configuration
              {:seon.config.flow.compute/queue-depth 2
               :seon.config.flow.compute/concurrency 1}})
          occupied
          (future
            (submit-test!!
             {::sut/submission-id ::occupied
              ::sut/workload :compute
              ::sut/time-limit-ms 30
              ::sut/work-fn
              (fn [{::sut/keys [started!]}]
                (started!)
                (.countDown entered)
                (test-support/await-event! release ::release-occupied)
                ::released)}))]
      (try
        (test-support/await-event! entered ::occupied-entered)
        (is (= ::sut/time-limit (::sut/outcome @occupied)))
        (let [result
              (deref
               (future
                 (submit-test!!
                  {::sut/submission-id ::queued-behind-occupied
                   ::sut/workload :compute
                   ::sut/time-limit-ms 30
                   ::sut/work-fn (fn [_] ::unexpected-start)}))
               (* 1000 test-support/event-backstop-seconds)
               ::did-not-settle)]
          (is (= ::sut/time-limit (::sut/outcome result))))
        (finally
          (.countDown release)
          (stop-test-work-launcher!))))))

(deftest saturated-submission-refuses-without-blocking-the-caller
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        launcher
        (install-test-work-launcher!
         {::sut/configuration
          {:seon.config.flow.compute/queue-depth 1
           :seon.config.flow.compute/concurrency 1}})
        graph (::sut/graph launcher)
        occupied
        (future
          (submit-test!!
           {::sut/submission-id ::occupied-for-refusal
            ::sut/workload :compute
            ::sut/time-limit-ms 60000
            ::sut/work-fn
            (fn [{::sut/keys [started!]}]
              (started!)
              (.countDown entered)
              (.await release)
              ::released)}))]
    (try
      (test-support/await-event! entered ::refusal-owner-entered)
      (let [buffered-result (promise)
            buffered-status (atom ::sut/queued)
            buffered
            {::sut/submission-id ::buffered-before-refusal
             ::sut/workload :compute
             ::sut/work-fn (fn [_] ::buffered-completed)
             ::sut/result buffered-result
             ::sut/status buffered-status}]
        (.get ^Future
              (flow/inject
               graph
               [::sut/work-launcher ::sut/compute-submission]
               [buffered])
              test-support/event-backstop-seconds
              TimeUnit/SECONDS)
        (let [refused
              (future
                (submit-test!!
                 {::sut/submission-id ::refused-at-capacity
                  ::sut/workload :compute
                  ::sut/time-limit-ms 60000
                  ::sut/work-fn (fn [_] ::unexpected-refused-run)}))
              result
              (test-support/await-event!
               refused
               ::saturated-submission-refused)]
          (is (= ::sut/completed (::sut/outcome result)))
          (is (= ::sut/submission-capacity
                 (get-in result [::sut/value :seon.error/kind])))
          (is (= ::sut/queued @buffered-status)
              "capacity refusal retains the already-buffered submission"))
        (.countDown release)
        (is (= ::released
               (::sut/value
                (test-support/await-event!
                 occupied
                 ::refusal-owner-released))))
        (is (= ::buffered-completed
               (::sut/value
                (test-support/await-event!
                 (future @buffered-result)
                 ::buffered-submission-completed)))))
      (finally
        (.countDown release)
        (stop-test-work-launcher!)))))

(deftest launcher-stop-precedes-a-flood-of-ready-submissions
  (let [queue-depth 256
        launcher
        (install-test-work-launcher!
         {::sut/configuration
          {:seon.config.flow.compute/queue-depth queue-depth
           :seon.config.flow.compute/concurrency 1}})
        graph (::sut/graph launcher)
        step-var (ns-resolve 'seon.flow 'work-launcher-step)
        original-step @step-var
        resume-transition (CountDownLatch. 1)
        release-resume (CountDownLatch. 1)
        stop-transition (CountDownLatch. 1)
        queued
        (mapv
         (fn [ordinal]
           {::sut/submission-id (keyword (str "queued-before-stop-" ordinal))
            ::sut/workload :compute
            ::sut/work-fn (fn [_] ::unexpected-queued-run)
            ::sut/result (promise)
            ::sut/status (atom ::sut/queued)})
         (range queue-depth))]
    (try
      (flow/pause graph)
      (is (= :paused
             (::flow/status
              (flow/ping-proc graph ::sut/work-launcher)))
          "the launcher observes pause before its queue is filled")
      (.get ^Future
            (flow/inject
             graph
             [::sut/work-launcher ::sut/compute-submission]
             queued)
            test-support/event-backstop-seconds
            TimeUnit/SECONDS)
      (alter-var-root
       step-var
       (constantly
        (fn
          ([]
           (original-step))
          ([args]
           (original-step args))
          ([state transition]
           (when (= ::flow/resume transition)
             (.countDown resume-transition)
             (test-support/await-event!
              release-resume
              ::release-work-launcher-resume-transition))
           (let [next-state (original-step state transition)]
             (when (= ::flow/stop transition)
               (.countDown stop-transition))
             next-state))
          ([state input-id message]
           (original-step state input-id message)))))
      (flow/resume graph)
      (test-support/await-event!
       resume-transition
       ::work-launcher-resume-transition)
      (flow/stop graph)
      (.countDown release-resume)
      (test-support/await-event!
       stop-transition
       ::work-launcher-stop-transition)
      (let [completed
            (count (filter #(= ::sut/completed @(::sut/status %)) queued))]
        (is (< completed queue-depth)
            "the stop transition completes without draining the flood")
        (is (= completed
               (count (filter #(realized? (::sut/result %)) queued)))
            "only work selected before the stop tap became ready can finish"))
      (finally
        (.countDown release-resume)
        (alter-var-root step-var (constantly original-step))
        (stop-test-work-launcher!)))))

(deftest starting-a-sibling-launcher-does-not-interrupt-accepted-work
  (let [configuration
        (merge
         test-io-configuration
         {:seon.config.flow.compute/queue-depth 2
          :seon.config.flow.compute/concurrency 1})
        entered-a (CountDownLatch. 1)
        release-a (CountDownLatch. 1)
        calls-a (atom 0)
        launcher-a
        (sut/start-work-launcher! {::sut/configuration configuration})]
    (let [result-a
          (future
            (sut/submit!!
             launcher-a
             {::sut/submission-id ::cluster-a-work
              ::sut/workload :compute
              ::sut/time-limit-ms 5000
              ::sut/work-fn
              (fn [_]
                (swap! calls-a inc)
                (.countDown entered-a)
                (.await release-a)
                ::cluster-a-completed)}))]
      (try
        (test-support/await-event! entered-a ::cluster-a-work-entered)
        (let [launcher-b
              (sut/start-work-launcher!
               {::sut/configuration configuration})]
          (try
            (is (= ::cluster-b-completed
                   (::sut/value
                    (sut/submit!!
                     launcher-b
                     {::sut/submission-id ::cluster-b-work
                      ::sut/workload :compute
                      ::sut/time-limit-ms 5000
                      ::sut/work-fn (fn [_] ::cluster-b-completed)}))))
            (.countDown release-a)
            (is (= ::cluster-a-completed
                   (::sut/value
                    (test-support/await-event!
                     result-a
                     ::cluster-a-work-completed))))
            (is (= 1 @calls-a))
            (finally
              (sut/stop-work-launcher! launcher-b))))
        (finally
          (.countDown release-a)
          (sut/stop-work-launcher! launcher-a))))))

(deftest turn-evaluation-completion-is-a-flat-diagnostic-value
  (install-test-work-launcher!
   {::sut/configuration
    {:seon.config.flow.compute/queue-depth 1
     :seon.config.flow.compute/concurrency 1}})
  (try
    (let [evaluation
          (#'cluster.loop/submit-evaluation!!
           {:seon.flow/work-launcher @test-work-launcher}
           sci.eval/evaluate
           "turn-boundary-0"
           {:seon.cluster.run.form/source
            "(reduce + (map inc (range 500)))"
            :seon.sci.admit/caps
            (config/result-caps (config/defaults))
            :seon.sci.eval/time-limit-ms 1000
            :seon.config/on-core-error :panic})
          record (:seon.sci.admit/record evaluation)]
      (is (map? evaluation))
      (is (= 125250 (:seon.sci.admit/value evaluation)))
      (is (pos? (:seon.eval/fn-entries record)))
      (is (int? (:seon.eval/duration-ms record)))
      (is (= -1 (:seon.eval/allocated-bytes record))))
    (finally
      (stop-test-work-launcher!))))

(deftest wedged-steps-degrade-capacity-exactly-and-name-themselves
  (let [parallelism 4
        wedge-count 2
        compute-executor (sut/bounded-platform-executor parallelism)
        active-evals (atom {})
        wedge-started (CountDownLatch. wedge-count)
        release-wedges (CountDownLatch. 1)
        remaining-work-finished
        (CountDownLatch. (- parallelism wedge-count))
        observer
        {:proc
         (sut/capacity-observer-proc
          {::sut/parallelism parallelism
           ::sut/active-work active-evals})
         :chan-opts
         {::sut/observe {:buf-or-n 2}}}
        procs (assoc (eval-procs parallelism parallelism active-evals)
                     :observer observer)
        graph (flow/create-flow
               {:procs procs
                :conns []
                :compute-exec compute-executor})
        started (flow/start graph)
        wedged-pids #{:eval-0 :eval-1}]
    (try
      (flow/resume graph)
      (doseq [pid wedged-pids]
        @(flow/inject
          graph
          [pid ::sut/submission]
          [{::sut/work-fn
            (fn [{::sut/keys [interrupt-fn]}]
              (interrupt-fn)
              (.countDown wedge-started)
              (when-not (.await
                         release-wedges
                         test-support/event-backstop-seconds
                         TimeUnit/SECONDS)
                (throw
                 (ex-info
                  "The wedge release event did not arrive."
                  {::event ::wedge-release})))
              ::released)
            ::sut/wedged? true}]))
      (test-support/await-event! wedge-started ::wedges-started)

      (testing "ping and report name every wedge and the exact capacity loss"
        (let [observer-state
              (::flow/state (flow/ping-proc graph :observer))]
          (is (= wedged-pids (::sut/active-procs observer-state)))
          (is (= wedged-pids (::sut/wedged-procs observer-state)))
          (is (= (- parallelism wedge-count)
                 (::sut/available-permits observer-state)))
          (is (true? (::sut/platform-threads? observer-state))))
        @(flow/inject graph [:observer ::sut/observe] [::observe])
        (let [report (test-support/await-event! (:report-chan started) ::capacity-report)]
          (is (= ::sut/capacity (::sut/event report)))
          (is (= wedged-pids (::sut/wedged-procs report))))
        (let [all-pings (flow/ping graph :timeout-ms 100)]
          (is (empty? (select-keys all-pings wedged-pids))
              "wedged built-in proc loops cannot answer their own ping")
          (is (= wedged-pids
                 (get-in all-pings
                         [:observer ::flow/state ::sut/wedged-procs]))
              "the responsive observer names the missing proc replies")))

      (testing "all and only the remaining compute slots still make progress"
        (doseq [pid [:eval-2 :eval-3]]
          @(flow/inject
            graph
            [pid ::sut/submission]
            [{::sut/work-fn
              (fn [{::sut/keys [interrupt-fn]}]
                (interrupt-fn)
                (.countDown remaining-work-finished)
                ::completed)
              ::sut/wedged? false}]))
        (test-support/await-event! remaining-work-finished ::remaining-capacity)
        (dotimes [_ (- parallelism wedge-count)]
          (test-support/await-event!
           (:report-chan started)
           ::normal-work-completed
           #(contains? #{:eval-2 :eval-3} (::sut/pid %))))
        (is (= wedge-count (count @active-evals))))
      (finally
        (.countDown release-wedges)
        (dotimes [_ wedge-count]
          (test-support/await-event!
           (:report-chan started)
           ::wedged-work-released
           #(contains? wedged-pids (::sut/pid %))))
        (is (empty? @active-evals))
        (flow/stop graph)
        (stop-executor! compute-executor)))))

(deftest fixed-buffer-capacity-drains-in-order-without-loss
  (testing "a full fixed buffer accepts its capacity and loses nothing"
    (let [{::keys [graph completed] :as testbed}
          (single-eval-testbed 2)]
      (try
        (let [started (flow/start graph)
              filled
              (flow/inject
               graph
               [:eval ::sut/submission]
               (mapv #(work-message completed %) (range 2)))
              _ (.get ^Future filled
                      test-support/event-backstop-seconds
                      TimeUnit/SECONDS)
              injection
              (flow/inject
               graph
               [:eval ::sut/submission]
               (mapv #(work-message completed %) (range 2 6)))]
          (is (= 2
                 (get-in
                  (channel-data graph :eval ::sut/submission)
                  [:buffer :count])))
          (flow/resume graph)
          (.get ^Future injection
                test-support/event-backstop-seconds
                TimeUnit/SECONDS)
          (dotimes [_ 6]
            (test-support/await-event!
             (:report-chan started)
             ::fixed-buffer-item-completed
             #(= ::sut/eval-complete (::sut/event %))))
          (is (= (range 6) @completed))
          (is (zero?
               (get-in
                (channel-data graph :eval ::sut/submission)
                [:buffer :count]))))
        (finally
          (stop-testbed! testbed))))))

(deftest fake-interrupt-ends-a-spin-and-the-proc-survives
  (testing "an armed interrupt returns a timeout value without losing capacity"
    (let [{::keys [graph completed active-evals] :as testbed}
          (single-eval-testbed 2)
          {:keys [report-chan error-chan]} (flow/start graph)
          deadline (+ (System/nanoTime) (* 1000000 30))
          interrupt-fn
          (fn []
            (when (<= deadline (System/nanoTime))
              (throw
               (ex-info
                "Synthetic time limit."
                {::sut/interrupted? true}))))]
      (try
        (flow/resume graph)
        @(flow/inject
          graph
          [:eval ::sut/submission]
          [{::sut/work-fn
            (fn [{armed ::sut/interrupt-fn}]
              (loop [entries 0]
                (armed)
                (recur (unchecked-inc entries))))
            ::sut/interrupt-fn interrupt-fn
            ::sut/wedged? false}])
        (let [report (test-support/await-event! report-chan ::interrupt-report)
              result (::sut/result report)]
          (is (= :timeout (:seon.error/kind result)))
          (is (= :eval (get-in result
                               [:seon.error/data ::sut/pid])))
          (is (nil? (async/poll! error-chan))
              "an agent error value is not a Flow core fault"))
        (is (= :running
               (::flow/status (flow/ping-proc graph :eval))))
        @(flow/inject
          graph
          [:eval ::sut/submission]
          [(work-message completed ::after-interrupt)])
        (test-support/await-event!
         report-chan
         ::after-interrupt
         #(= ::after-interrupt (::sut/result %)))
        (is (= [::after-interrupt] @completed))
        (is (= 2 (::flow/count (flow/ping-proc graph :eval))))
        (is (empty? @active-evals))
        (finally
          (stop-testbed! testbed))))))

(deftest thrown-step-reports-error-and-keeps-pre-step-state
  (testing "Flow reports an ordinary Throwable and the proc continues"
    (let [{::keys [graph completed] :as testbed}
          (single-eval-testbed 2)
          {:keys [error-chan report-chan]} (flow/start graph)]
      (try
        (flow/resume graph)
        @(flow/inject
          graph
          [:eval ::sut/submission]
          [{::sut/work-fn
            (fn [_]
              (throw (RuntimeException. "synthetic step failure")))
            ::sut/wedged? false}])
        (let [error (test-support/await-event! error-chan ::flow-step-error)]
          (is (= :eval (::flow/pid error)))
          (is (= :step (::flow/op error)))
          (is (instance? Throwable (::flow/ex error))))
        (let [ping (flow/ping-proc graph :eval)]
          (is (zero? (::flow/count ping)))
          (is (zero? (get-in ping [::flow/state ::sut/completed]))))
        @(flow/inject
          graph
          [:eval ::sut/submission]
          [(work-message completed ::after-throw)])
        (test-support/await-event!
         report-chan
         ::after-throw
         #(= ::after-throw (::sut/result %)))
        (is (= [::after-throw] @completed))
        (is (= 1 (::flow/count (flow/ping-proc graph :eval))))
        (finally
          (stop-testbed! testbed))))))

(defn- throwing-work-message
  [ordinal]
  {::sut/work-fn
   (fn [_]
     (throw
      (RuntimeException.
       (str "synthetic core fault " ordinal))))
   ::sut/wedged? false})

(defn- start-test-fanout!
  [connection graph started fault-buffer-capacity monitor-buffer-capacity]
  (sut/start-error-fanout!
   {::sut/graph graph
    ::sut/started started
    ::sut/fault-buffer-capacity fault-buffer-capacity
    ::sut/monitor-buffer-capacity monitor-buffer-capacity
    ::sut/read-core-error-mode #(core-error-mode connection)
    ::sut/commit-fault! #(commit-fault! connection %)
    ::sut/commit-drop! #(commit-fault-drop! connection %)
    ::sut/panic!
    (fn [fault]
      (throw
       (ex-info
        "The record-mode testbed unexpectedly selected panic."
        {::fault fault})))}))

(deftest core-fault-fanout-commits-and-copies-without-competition
  (testing "one throwing step reaches durable facts and the monitor tap"
    (with-fault-database
      (fn [connection]
        (let [{::keys [graph completed] :as testbed}
              (single-eval-testbed 2)
              started (flow/start graph)
              fanout (start-test-fanout! connection graph started 4 4)
              monitor-messages (async/chan 8)
              transactions (database-events connection)]
          (try
            (with-redefs
              [flow-monitor/send-message
               (fn [_state message]
                 (async/>!! monitor-messages message))]
              (let [monitor-state
                    (flow-monitor/start-server
                     {:flow (::sut/graph fanout)
                      :port 0})]
                (try
                  (flow/resume graph)
                  @(flow/inject
                    graph
                    [:eval ::sut/submission]
                    [(throwing-work-message 0)])
                  (test-support/await-event!
                   monitor-messages
                   ::monitor-core-fault
                   (fn [{:keys [action data]}]
                     (and (= :error action)
                          (str/includes? data ":pid :eval")
                          (str/includes? data "synthetic core fault 0"))))
                  @(flow/inject
                    graph
                    [:eval ::sut/submission]
                    [(work-message completed ::fanout-report)])
                  (let [application-copy
                        (test-support/await-event!
                         (::sut/application-report-channel fanout)
                         ::application-report-copy)]
                    (is (= ::fanout-report
                           (::sut/result application-copy))))
                  (test-support/await-event!
                   monitor-messages
                   ::monitor-report-copy
                   (fn [{:keys [action data]}]
                     (and (= :message action)
                          (= ::fanout-report (::sut/result data)))))
                  (finally
                    (flow-monitor/stop-server monitor-state)))))
            (test-support/await-event!
             (::channel transactions)
             ::core-fault-committed
             #(= #{[:eval
                    "java.lang.RuntimeException: synthetic core fault 0"]}
                 (set (committed-faults (:db-after %)))))
            (is (= 1
                   (get-in
                    (flow/ping-proc
                     (::sut/fault-graph fanout)
                     ::sut/fault-committer)
                    [::flow/state ::sut/committed])))
            (finally
              (stop-database-events! connection transactions)
              (async/close! monitor-messages)
              (sut/stop-error-fanout! fanout)
              (stop-testbed! testbed))))))))

(deftest core-fault-signatures-bound-durable-and-stderr-output
  (let [commit-core-fault!
        (var-get (ns-resolve 'seon.cluster 'commit-fault!))
        emit-core-fault!
        (var-get (ns-resolve 'seon.cluster 'emit-core-fault!))
        committer-step
        (var-get (ns-resolve 'seon.flow 'fault-committer-step))
        inline-ceiling 96
        caps {:seon.config.eval.result/max-depth 8
              :seon.config.eval.result/max-collection 8
              :seon.config.eval.result/max-string 64
              :seon.config.eval.result/max-nodes 64}
        repeated-message
        (str "one repeated cause: " (apply str (repeat 10000 "x")))
        repeated-fault
        {::flow/pid :eval
         ::flow/op :step
         ::flow/ex (StackOverflowError. repeated-message)}
        distinct-fault
        {::flow/pid :eval
         ::flow/op :step
         ::flow/ex (IllegalStateException. "a distinct cause")}
        effective
        (fn [_database _cluster-name]
          {:seon.config.error/recurrence-limit 3
           :seon.config.eval.result/blob-threshold inline-ceiling})]
    (testing "132 equal faults produce one bounded durable fact and stderr face"
      (test-support/with-database
        (fn [connection]
          (with-redefs [config/effective effective]
            (let [initial-state
                  (committer-step
                   {::sut/fault-channel (async/chan 1)
                    ::sut/completion (async/promise-chan)
                    ::sut/read-core-error-mode (constantly :panic)
                    ::sut/commit-fault!
                    #(commit-core-fault! connection "fault-test" "process-1"
                                         caps %)
                    ::sut/panic!
                    #(emit-core-fault!
                      {:seon.config.eval.result/blob-threshold inline-ceiling}
                      %)})
                  stderr-writer (java.io.StringWriter.)
                  final-state
                  (binding [*err* stderr-writer]
                    (reduce
                     (fn [state _]
                       (first
                        (committer-step state ::sut/core-fault repeated-fault)))
                     initial-state
                     (range 132)))
                  facts
                  (db/q '[:find ?signature ?message ?capped? ?data-edn
                          :where
                          [?error :seon.error/signature ?signature]
                          [?error :seon.error/message ?message]
                          [?error :seon.error/capped? ?capped?]
                          [?error :seon.error/data-edn ?data-edn]]
                        @connection)
                  [_signature message capped? data-edn] (first facts)
                  lines (str/split-lines (str stderr-writer))]
              (is (= 1 (count facts))
                  "all 132 equal envelopes own one durable fact")
              (is (= 1 (::sut/committed final-state)))
              (is (= 1 (::sut/panicked final-state)))
              (is (= 1 (count (::sut/seen-signatures final-state))))
              (is (= inline-ceiling (count message)))
              (is (str/ends-with? message "…"))
              (is (true? capped?))
              (is (< (count data-edn) 10000)
                  "the normalizer caps the retained stack and ex-data")
              (is (= 1 (count lines))
                  "all 132 equal envelopes own one emitted face")
              (is (<= (count (first lines)) (+ (* 2 inline-ceiling) 192))
                  "the emitted face remains bounded"))))))

    (testing "a rebuilt proc does not print a signature already durable"
      (test-support/with-database
        (fn [connection]
          (with-redefs [config/effective effective]
            (let [[durable-fact durable-outcome]
                  (commit-core-fault! connection "fault-test" "process-3"
                                      caps repeated-fault)
                  initial-state
                  (committer-step
                   {::sut/fault-channel (async/chan 1)
                    ::sut/completion (async/promise-chan)
                    ::sut/read-core-error-mode (constantly :panic)
                    ::sut/commit-fault!
                    #(commit-core-fault! connection "fault-test" "process-3"
                                         caps %)
                    ::sut/panic!
                    #(emit-core-fault!
                      {:seon.config.eval.result/blob-threshold inline-ceiling}
                      %)})
                  stderr-writer (java.io.StringWriter.)
                  [after-duplicate _]
                  (binding [*err* stderr-writer]
                    (committer-step initial-state ::sut/core-fault
                                    repeated-fault))
                  duplicate-stderr (str stderr-writer)
                  [final-state _]
                  (binding [*err* stderr-writer]
                    (committer-step after-duplicate ::sut/core-fault
                                    distinct-fault))
                  signatures
                  (db/q '[:find ?signature
                          :where [_ :seon.error/signature ?signature]]
                        @connection)
                  signature-set (set (map first signatures))
                  lines (str/split-lines (str stderr-writer))]
              (is (= ::sut/committed durable-outcome))
              (is (empty? duplicate-stderr)
                  "the durable signature suppresses output after rebuild")
              (is (contains? signature-set
                             (:seon.error/signature durable-fact)))
              (is (= 2 (count signature-set)))
              (is (= signature-set (::sut/seen-signatures final-state)))
              (is (= 1 (::sut/committed final-state)))
              (is (= 1 (::sut/panicked final-state)))
              (is (= 1 (count lines))
                  "one genuinely new signature remains visible")
              (is (str/includes? (first lines) "a distinct cause"))
              (is (<= (count (first lines)) (+ (* 2 inline-ceiling) 192))
                  "the new signature's face remains bounded"))))))

    (testing "a dead writer still emits each signature only once"
      (test-support/with-database
        (fn [connection]
          (let [writer-refusal
                {:seon.error/message
                 "Writer is shut down; release and reconnect."}
                initial-state
                (committer-step
                 {::sut/fault-channel (async/chan 1)
                  ::sut/completion (async/promise-chan)
                  ::sut/read-core-error-mode (constantly :panic)
                  ::sut/commit-fault!
                  #(commit-core-fault! connection "fault-test" "process-2"
                                       caps %)
                  ::sut/panic!
                  #(emit-core-fault!
                    {:seon.config.eval.result/blob-threshold inline-ceiling}
                    %)})
                transaction-attempts (atom 0)
                final-state (atom nil)
                stderr-writer (java.io.StringWriter.)
                _
                (binding [*err* stderr-writer]
                  (with-redefs [config/effective effective
                                db/transact! (fn [_connection _transaction-data]
                                               (swap! transaction-attempts inc)
                                               writer-refusal)]
                    (reset!
                     final-state
                     (reduce
                      (fn [state fault]
                        (first (committer-step state ::sut/core-fault fault)))
                      initial-state
                      [repeated-fault repeated-fault distinct-fault]))))
                stderr (str stderr-writer)
                lines (str/split-lines stderr)]
            (is (= 2 (count lines))
                "the repeated signature and the distinct signature print once")
            (is (= 2 (count (::sut/seen-signatures @final-state))))
            (is (= 2 @transaction-attempts)
                "the repeated signature does not reach the dead writer twice")
            (is (zero? (::sut/committed @final-state)))
            (is (= 2 (::sut/panicked @final-state)))
            (is (every? #(str/includes? % "durable record refused") lines))
            (is (every? #(str/includes? % "signature ") lines))
            (is (every? #(<= (count %) (+ (* 2 inline-ceiling) 192)) lines)
                "the configured string ceiling bounds each single-line face")
            (is (not (str/includes? stderr (apply str (repeat 100 "x")))))
            (is (empty?
                 (db/q '[:find ?error
                         :where [?error :seon.error/signature _]]
                       @connection)))))))))

(deftest stopping-the-fanout-awaits-an-active-fault-commit
  (let [{::keys [graph] :as testbed} (single-eval-testbed 1)
        started (flow/start graph)
        commit-entered (CountDownLatch. 1)
        finish-commit (CountDownLatch. 1)
        fanout
        (sut/start-error-fanout!
         {::sut/graph graph
          ::sut/started started
          ::sut/fault-buffer-capacity 1
          ::sut/monitor-buffer-capacity 1
          ::sut/read-core-error-mode (constantly :record)
          ::sut/commit-fault!
          (fn [_fault]
            (.countDown commit-entered)
            (test-support/await-event! finish-commit ::finish-fault-commit)
            [{:seon.error/signature "active-fault-commit"}
             ::sut/committed])
          ::sut/commit-drop! (fn [_])
          ::sut/panic! (fn [_])})]
    (try
      (flow/resume graph)
      @(flow/inject graph
                    [:eval ::sut/submission]
                    [(throwing-work-message 0)])
      (test-support/await-event! commit-entered ::fault-commit-entered)
      (let [awaiting-completion (CountDownLatch. 1)
            completion (::sut/completion fanout)
            observed-completion
            (reify async.impl/ReadPort
              (take! [_ handler]
                (.countDown awaiting-completion)
                (async.impl/take! completion handler)))
            stopped
            (future
              (sut/stop-error-fanout!
               (assoc fanout ::sut/completion observed-completion)))]
        (test-support/await-event!
         awaiting-completion
         ::fanout-awaiting-completion)
        (is (false? (realized? stopped))
            "the fanout keeps its database dependency while commit is active")
        (.countDown finish-commit)
        (is (true? (test-support/await-event! stopped ::fanout-stopped))
            "the fault proc publishes completion after the commit returns"))
      (finally
        (.countDown finish-commit)
        (stop-testbed! testbed)))))

(defn- async-mixed-platform-threads
  []
  (into #{}
        (comp
         (filter (fn [^Thread thread]
                   (and (not (.isVirtual thread))
                        (str/starts-with? (.getName thread)
                                          "async-mixed-"))))
         (map #(.getName ^Thread %)))
        (keys (Thread/getAllStackTraces))))

(defn- prime-agent-error-fanout!
  [source-count]
  (let [fault-channel (async/chan source-count)
        sources (repeatedly source-count #(async/chan 1))
        joins
        (mapv
         (fn [source]
           (sut/join-error-fanout!
            {::sut/started {:error-chan source}
             ::sut/fault-channel fault-channel
             ::sut/tag {}}))
         sources)]
    (doseq [source sources]
      (async/>!! source {::flow/pid ::prime}))
    (dotimes [_ source-count]
      (test-support/await-event! fault-channel ::prime-agent-error-fanout))
    (doseq [source sources]
      (async/close! source))
    (doseq [join joins]
      (test-support/await-event!
       (future (async/<!! join) ::prime-fanout-stopped)
       ::prime-fanout-stopped))
    (async/close! fault-channel)))

(deftest agent-error-fanout-parks-without-platform-workers
  (let [source-count 64
        fault-channel (async/chan source-count)
        _ (prime-agent-error-fanout! source-count)
        baseline (async-mixed-platform-threads)
        sources (repeatedly source-count #(async/chan 1))
        joins
        (mapv
         (fn [ordinal source]
           (sut/join-error-fanout!
            {::sut/started {:error-chan source}
             ::sut/fault-channel fault-channel
             ::sut/tag {:seon.cluster.agent/id (str "agent-" ordinal)}}))
         (range source-count)
         sources)]
    (try
      (doseq [[ordinal source] (map-indexed vector sources)]
        (async/>!! source {::flow/pid (keyword (str "proc-" ordinal))}))
      (let [faults
            (into #{}
                  (map (fn [_]
                         (test-support/await-event!
                          fault-channel
                          ::tagged-agent-fault)))
                  (range source-count))]
        (is (= (set (map #(str "agent-" %) (range source-count)))
               (into #{} (map :seon.cluster.agent/id) faults))
            "every source fault retains its agent provenance"))
      (is (= baseline (async-mixed-platform-threads))
          "parking one fan-out per agent adds no platform worker")
      (doseq [source sources]
        (async/close! source))
      (doseq [join joins]
        (is (= ::agent-error-fanout-stopped
               (test-support/await-event!
                (future
                  (async/<!! join)
                  ::agent-error-fanout-stopped)
                ::agent-error-fanout-stopped))))
      (is (true? (async/offer! fault-channel ::still-open))
          "stopping every source leaves the committer inbox open")
      (is (= ::still-open
             (test-support/await-event!
              fault-channel
              ::fault-channel-remains-open)))
      (finally
        (doseq [source sources]
          (async/close! source))
        (async/close! fault-channel)))))

(deftest fault-tap-retains-every-fault-under-capacity
  (testing "N admitted faults below the bounded tap capacity lose nothing"
    (with-fault-database
      (fn [connection]
        (let [fault-count 6
              {::keys [graph] :as testbed}
              (single-eval-testbed fault-count)
              started (flow/start graph)
              fanout
              (start-test-fanout!
               connection graph started fault-count fault-count)
              fault-graph (::sut/fault-graph fanout)
              transactions (database-events connection)]
          (try
            (flow/pause-proc fault-graph ::sut/fault-committer)
            (is (= :paused
                   (::flow/status
                    (flow/ping-proc
                     fault-graph ::sut/fault-committer))))
            (flow/resume graph)
            @(flow/inject
              graph
              [:eval ::sut/submission]
              (mapv throwing-work-message (range fault-count)))
            (dotimes [_ fault-count]
              (test-support/await-event!
               (::sut/monitor-error-channel fanout)
               ::monitor-buffered-core-fault))
            (is (empty? (committed-faults @connection)))
            (is (zero? (committed-drop-count @connection)))
            (flow/resume-proc fault-graph ::sut/fault-committer)
            (test-support/await-event!
             (::channel transactions)
             ::all-core-faults-committed
             #(<= fault-count
                  (count (committed-faults (:db-after %)))))
            (is (= (set (map #(str "java.lang.RuntimeException: "
                                   "synthetic core fault " %)
                             (range fault-count)))
                   (set (map second
                             (committed-faults @connection)))))
            (finally
              (stop-database-events! connection transactions)
              (sut/stop-error-fanout! fanout)
              (stop-testbed! testbed))))))))

(deftest fault-tap-overflow-commits-a-loud-drop-counter
  (testing "faults beyond the bounded tap are counted as durable drops"
    (with-fault-database
      (fn [connection]
        (let [fault-buffer-capacity 2
              fault-count 5
              {::keys [graph] :as testbed}
              (single-eval-testbed fault-count)
              started (flow/start graph)
              fanout
              (start-test-fanout!
               connection graph started
               fault-buffer-capacity fault-count)
              fault-graph (::sut/fault-graph fanout)
              transactions (database-events connection)]
          (try
            (flow/pause-proc fault-graph ::sut/fault-committer)
            (is (= :paused
                   (::flow/status
                    (flow/ping-proc
                     fault-graph ::sut/fault-committer))))
            (flow/resume graph)
            @(flow/inject
              graph
              [:eval ::sut/submission]
              (mapv throwing-work-message (range fault-count)))
            (dotimes [_ fault-count]
              (test-support/await-event!
               (::sut/monitor-error-channel fanout)
               ::monitor-overflow-core-fault))
            (test-support/await-event!
             (::channel transactions)
             ::fault-drops-committed
             #(<= (- fault-count fault-buffer-capacity)
                  (committed-drop-count (:db-after %))))
            (flow/resume-proc fault-graph ::sut/fault-committer)
            (test-support/await-event!
             (::channel transactions)
             ::retained-core-faults-committed
             #(<= fault-buffer-capacity
                  (count (committed-faults (:db-after %)))))
            (is (= 3 (committed-drop-count @connection)))
            (finally
              (stop-database-events! connection transactions)
              (sut/stop-error-fanout! fanout)
              (stop-testbed! testbed))))))))

(deftest re-evaluated-step-var-changes-a-running-graph
  (let [delivered (async/chan 2)
        step-var (ns-resolve 'seon.flow 'mailbox-step)
        original-step @step-var
        graph
        (flow/create-flow
         {:procs
          {:mailbox
           {:proc
            (sut/mailbox-proc
             {::sut/deliver! #(async/put! delivered %)})
            :chan-opts
            {::sut/mailbox {:buf-or-n 1}}}}
          :conns []})]
    (try
      (flow/start graph)
      (flow/resume graph)
      @(flow/inject graph [:mailbox ::sut/mailbox] [::before-reload])
      (is (= ::before-reload
             (test-support/await-event! delivered ::before-reload)))

      (alter-var-root
       step-var
       (constantly
        (fn
          ([] (original-step))
          ([args] (original-step args))
          ([state transition] (original-step state transition))
          ([state _input message]
           ((::sut/deliver! state) [::reloaded message])
           [(update state ::sut/delivered inc) nil]))))

      @(flow/inject graph [:mailbox ::sut/mailbox] [::after-reload])
      (is (= [::reloaded ::after-reload]
             (test-support/await-event! delivered ::after-reload))
          "the already-running graph invokes the Var's new root")
      (is (= 2 (::flow/count (flow/ping-proc graph :mailbox))))
      (finally
        (alter-var-root step-var (constantly original-step))
        (flow/stop graph)
        (async/close! delivered)))))

(deftest sliding-mailbox-is-nonblocking-bounded-and-latest-only
  (testing "a paused sliding buffer of one retains only the latest snapshot"
    (let [delivered (async/promise-chan)
          graph
          (flow/create-flow
           {:procs
            {:mailbox
             {:proc
              (sut/mailbox-proc
               {::sut/deliver! #(async/put! delivered %)})
              :chan-opts
              {::sut/mailbox
               {:buf-or-n (async/sliding-buffer 1)}}}}
            :conns []})]
      (try
        (flow/start graph)
        (let [injection
              (flow/inject
               graph
               [:mailbox ::sut/mailbox]
               (vec (range 100)))]
          (.get ^Future injection
                test-support/event-backstop-seconds
                TimeUnit/SECONDS)
          (is (.isDone ^Future injection))
          (is (= {:type 'SlidingBuffer :count 1 :capacity 1}
                 (:buffer
                  (channel-data graph :mailbox ::sut/mailbox))))
          (flow/resume graph)
          (is (= 99 (test-support/await-event! delivered ::latest-mail))))
        (finally
          (flow/stop graph))))))

(deftest pause-resume-mid-load-preserves-fixed-buffer-order
  (testing "pause takes effect between transforms without losing input"
    (let [{::keys [graph completed] :as testbed}
          (single-eval-testbed 20)
          mid-step (CountDownLatch. 1)
          release-mid-step (CountDownLatch. 1)
          messages
          (mapv
           (fn [ordinal]
             {::sut/work-fn
              (fn [{::sut/keys [interrupt-fn]}]
                (interrupt-fn)
                (when (= ordinal 3)
                  (.countDown mid-step)
                  (test-support/await-event! release-mid-step ::release-mid-step))
                (swap! completed conj ordinal)
                ordinal)
              ::sut/wedged? false})
           (range 20))]
      (try
        (let [started (flow/start graph)
              injection
              (flow/inject
               graph
               [:eval ::sut/submission]
               messages)]
          (.get ^Future injection
                test-support/event-backstop-seconds
                TimeUnit/SECONDS)
          (flow/resume graph)
          (test-support/await-event! mid-step ::load-mid-step)
          (flow/pause graph)
          (.countDown release-mid-step)
          (test-support/await-event!
           (:report-chan started)
           ::load-paused-after-current-step
           #(= 3 (::sut/result %)))
          (is (= :paused
                 (::flow/status (flow/ping-proc graph :eval))))
          (is (nil? (async/poll! (:report-chan started)))
              "the acknowledged pause publishes no next completion")
          (flow/resume graph)
          (test-support/await-event!
           (:report-chan started)
           ::load-complete
           #(= 19 (::sut/result %)))
          (is (= (range 20) @completed)))
        (finally
          (stop-testbed! testbed))))))

(deftest stopping-one-flow-does-not-affect-another
  (testing "two Flow graph lifecycles in one JVM remain isolated"
    (let [a (single-eval-testbed 2)
          b (single-eval-testbed 2)
          graph-a (::graph a)
          graph-b (::graph b)]
      (try
        (flow/start graph-a)
        (let [started-b (flow/start graph-b)]
        (flow/resume graph-a)
        (flow/resume graph-b)
        (flow/stop graph-a)
        (stop-executor! (::compute-executor a))
        @(flow/inject
          graph-b
          [:eval ::sut/submission]
          [(work-message (::completed b) ::flow-b)])
        (test-support/await-event!
         (:report-chan started-b)
         ::flow-b
         #(= ::flow-b (::sut/result %)))
        (is (= [::flow-b] @(::completed b)))
        (is (= :running
               (::flow/status (flow/ping-proc graph-b :eval))))
        (is (thrown? Throwable
                     (flow/ping graph-a :timeout-ms 20))))
        (finally
          (stop-testbed! a)
          (stop-testbed! b))))))

(deftest ^{:seon.test/long
           "Forcibly terminates a child JVM to cover committed-fact survival across process death."}
  forced-child-jvm-death-preserves-committed-facts
  (testing "SIGKILL loses process-local compute but not committed state"
    (let [store-id (random-uuid)
          root (File. "tmp" (str "flow-kill-" store-id))
          database-path (.getPath (File. root "db"))
          ready-file (File. root "committed.ready")
          _ (.mkdirs root)
          root-path (.toPath root)
          watch-service (.newWatchService (.getFileSystem root-path))
          _ (.register
             root-path
             watch-service
             (into-array WatchEvent$Kind
                         [StandardWatchEventKinds/ENTRY_CREATE]))
          readiness (CompletableFuture.)
          watcher
          (future
            (try
              (loop []
                (let [watch-key (.take ^WatchService watch-service)
                      ready?
                      (some
                       #(= (.getFileName (.toPath ready-file))
                           (.context ^java.nio.file.WatchEvent %))
                       (.pollEvents watch-key))]
                  (.reset watch-key)
                  (if ready?
                    (.complete readiness ::child-committed)
                    (recur))))
              (catch ClosedWatchServiceException _)))
          java-command
          (.getPath
           (File. (System/getProperty "java.home") "bin/java"))
          process
          (->
           (ProcessBuilder.
            ^java.util.List
            [java-command
             "-cp" (System/getProperty "java.class.path")
             "clojure.main"
             "-m" "seon.flow.kill-child"
             database-path
             (str store-id)
             (.getPath ready-file)])
           (.redirectErrorStream true)
           (.start))
          _
          (.thenAccept
           (.onExit process)
           (reify java.util.function.Consumer
             (accept [_ exited]
               (.complete readiness exited))))
          configuration
          {:store {:backend :file
                   :path database-path
                   :id store-id}
           :schema-flexibility :write
           :keep-history? true}]
      (try
        ;; A cold JVM must load Clojure and Datahike before it can publish the
        ;; committed-fact event. The event is authoritative; this longer clock
        ;; is only the foreign-process failure backstop.
        (let [observed
              (test-support/await-event! readiness ::child-readiness)]
          (when (instance? Process observed)
            (throw
             (ex-info
              "The child JVM exited before committing its durable fact."
              {::event ::child-exited
               ::exit (.exitValue process)
               ::output (slurp (.getInputStream process))}))))
        (let [kill-process
              (.start
               (ProcessBuilder.
                ^java.util.List
                ["kill" "-9" (str (.pid process))]))]
          (is (.waitFor kill-process
                        test-support/event-backstop-seconds
                        TimeUnit/SECONDS))
          (is (zero? (.exitValue kill-process))))
        (is (.waitFor process
                      test-support/event-backstop-seconds
                      TimeUnit/SECONDS))
        (is (false? (.isAlive process)))
        (let [connection (d/connect configuration)]
          (try
            (is (= 1
                   (db/q
                    '[:find ?count .
                      :where
                      [?entity :seon.flow.kill/id "durable-step"]
                      [?entity :seon.flow.kill/count ?count]]
                    @connection)))
            (finally
              (d/release connection))))
        (finally
          (.close ^WatchService watch-service)
          (future-cancel watcher)
          (when (.isAlive process)
            (.destroyForcibly process))
          (when (d/database-exists? configuration)
            (d/delete-database configuration))
          (test-support/delete-recursively! root))))))

(defn- monitor-websocket-messages
  [^HttpClient client port]
  (let [complete-messages (atom [])
        partial-message (atom "")
        initial-and-ping (CountDownLatch. 2)
        listener
        (reify WebSocket$Listener
          (onOpen [_ socket]
            (.request socket 1))
          (onText [_ socket text last?]
            (swap! partial-message str text)
            (when last?
              (swap! complete-messages conj @partial-message)
              (reset! partial-message "")
              (.countDown initial-and-ping))
            (.request socket 1)
            nil))
        socket
        (-> client
            .newWebSocketBuilder
            (.buildAsync
             (URI/create (str "ws://127.0.0.1:" port "/flow-socket"))
             listener)
            .join)]
    (test-support/await-event! initial-and-ping ::monitor-datafy-and-ping)
    [socket @complete-messages]))

(deftest flow-monitor-attaches-and-publishes-the-render-graph
  (let [parallelism 1
        compute-executor (sut/bounded-platform-executor parallelism)
        active-evals (atom {})
        procs
        (testbed-procs
         {::parallelism parallelism
          ::active-evals active-evals
          ::deliver! (fn [_])})
        graph (create-testbed-flow procs compute-executor)
        client (HttpClient/newHttpClient)
        wedge-started (CountDownLatch. 1)
        release-wedge (CountDownLatch. 1)
        post-wedge-results (atom [])
        started (flow/start graph)
        fanout
        (sut/start-error-fanout!
         {::sut/graph graph
          ::sut/started started
          ::sut/fault-buffer-capacity 8
          ::sut/monitor-buffer-capacity 8
          ::sut/read-core-error-mode (constantly :record)
          ::sut/commit-fault! (fn [_])
          ::sut/commit-drop! (fn [_])
          ::sut/panic! (fn [_])})]
        (flow/resume graph)
        @(flow/inject
          graph
          [:eval ::sut/submission]
          [{::sut/work-fn
            (fn [{::sut/keys [interrupt-fn]}]
              (interrupt-fn)
              (.countDown wedge-started)
              (test-support/await-event! release-wedge ::release-monitor-wedge)
              ::released)
            ::sut/wedged? true}])
        (test-support/await-event! wedge-started ::monitor-wedge-started)
        (let [filled-injection
              (flow/inject
               graph
               [:eval ::sut/submission]
               (mapv #(work-message post-wedge-results %) (range 2)))
              _ (.get ^Future filled-injection
                      test-support/event-backstop-seconds
                      TimeUnit/SECONDS)
              _ (is (= 2
                       (get-in
                        (channel-data graph :eval ::sut/submission)
                        [:buffer :count])))
              parked-injection
              (flow/inject
               graph
               [:eval ::sut/submission]
               [(work-message post-wedge-results 2)])
              monitor-state
              (flow-monitor/start-server
               {:flow (::sut/graph fanout)
                :port 0})]
          (try
            (let [port (:port @monitor-state)
                  request
                  (-> (HttpRequest/newBuilder)
                      (.uri (URI/create
                             (str "http://127.0.0.1:"
                                  port
                                  "/index.html")))
                      .GET
                      .build)
                  response
                  (.send client request
                         (HttpResponse$BodyHandlers/ofString))
                  [socket messages]
                  (monitor-websocket-messages client port)
                  graph-message (first messages)
                  rendered-evidence (str/join "\n" messages)]
              (is (= 200 (.statusCode response)))
              (is (str/includes? (.body response) "<title>Flow Monitor</title>"))
              (is (str/includes? graph-message "~:datafy"))
              (doseq [pid ["~:eval" "~:mailbox" "~:observer"]]
                (is (str/includes? graph-message pid)
                    (str "monitor graph data names " pid)))
              (is (str/includes? rendered-evidence "FixedBuffer"))
              (is (str/includes? rendered-evidence
                                 "~:seon.flow/wedged-procs"))
              (is (str/includes? rendered-evidence "~:eval")
                  "the monitor ping names the wedged eval via the observer")
              (is (= #{:eval}
                     (get-in
                      (flow/ping-proc graph :observer)
                      [::flow/state ::sut/wedged-procs])))
              (.join (.sendClose
                      ^WebSocket socket
                      WebSocket/NORMAL_CLOSURE
                      "test complete")))
            (finally
              (flow-monitor/stop-server monitor-state)
              (.countDown release-wedge)
              (.get ^Future parked-injection
                    test-support/event-backstop-seconds
                    TimeUnit/SECONDS)
              (test-support/await-event!
               (::sut/application-report-channel fanout)
               ::monitor-post-wedge-drain
               #(= 2 (::sut/result %)))
              (is (= 3 (count @post-wedge-results)))
              (sut/stop-error-fanout! fanout)
              (flow/stop graph)
              (stop-executor! compute-executor))))))
