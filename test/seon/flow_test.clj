(ns seon.flow-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.core.async.flow :as flow]
            [clojure.core.async.flow-monitor :as flow-monitor]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as db]
            [datahike.core :as datahike]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.instrument :as mi]
            [seon.cluster.loop :as cluster.loop]
            [seon.config :as config]
            [seon.flow :as sut]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support])
  (:import [java.io File]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers
            WebSocket WebSocket$Listener]
           [java.nio.file ClosedWatchServiceException StandardWatchEventKinds
            WatchEvent$Kind WatchService]
           [java.util.concurrent CompletableFuture CountDownLatch
            Future TimeUnit]))

(def ^:private test-work-launcher (atom nil))

(def ^:private test-environment
  ;; Flow plumbing is a store-layer subject: these tests stand up no branch
  ;; and no facts, so they construct the subset environment every crossing
  ;; in this namespace names.
  (delay (test-support/environment "seon.flow-test")))

(def ^:private test-io-configuration
  {:seon.config.flow.io/queue-depth 2
   :seon.config.flow.io/concurrency 2})

(defn- earliest-resume-fault-step
  ([]
   {:ins {}
    :outs {}})
  ([args]
   args)
  ([state transition]
   (when (= ::flow/resume transition)
     (throw (ex-info "earliest resume fault"
                     {:seon.error/kind ::earliest-resume-fault})))
   state)
  ([state _input _message]
   [state nil]))

(deftest graph-construction-joins-every-declared-tap-before-resume
  (let [{::sut/keys [graph joins]}
        (sut/start-graph!
         {::sut/graph-definition
          {:procs
           {::earliest-fault
            {:proc (sut/var-process #'earliest-resume-fault-step
                                    :io
                                    {:seon.env/environment
                                     @test-environment})}}
           :conns []}
          ::sut/joins
          {::error-tap
           (fn [{::sut/keys [started]}]
             (let [error-mult (async/mult (:error-chan started))
                   tap (async/chan 1)]
               (async/tap error-mult tap)
               {::error-mult error-mult
                ::tap tap}))}})
        {::keys [error-mult tap]} (::error-tap joins)]
    (try
      (let [fault (test-support/await-event! tap ::earliest-resume-fault)]
        (is (= ::earliest-resume-fault
               (:seon.error/kind (ex-data (::flow/ex fault))))
            "the first resume transition reaches the declared tap"))
      (finally
        (flow/stop graph)
        (async/untap error-mult tap)
        (async/close! tap)))))

(defn- install-test-work-launcher!
  [request]
  (let [launcher
        (sut/start-work-launcher!
         (-> request
             (assoc :seon.env/environment @test-environment)
             (update ::sut/configuration
                     #(merge test-io-configuration %))))]
    (reset! test-work-launcher launcher)
    launcher))

(defn- stop-test-work-launcher!
  []
  (when-let [launcher
             (first (swap-vals! test-work-launcher (constantly nil)))]
    (sut/stop-work-launcher! launcher)))

(defn- submit-test!!
  [submission]
  (sut/submit!! @test-work-launcher
                (assoc submission
                       :seon.env/environment @test-environment)))

(def ^:private callback-schema-keys
  [:seon.flow/commit-drop!
   :seon.flow/commit-fault!
   :seon.flow/panic!
   :seon.flow/read-core-error-mode
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

(defn- source-testbed
  []
  (let [graph
        (flow/create-flow
         {:procs
          {:observer
           {:proc
            (sut/capacity-observer-proc
             {:seon.env/environment @test-environment
              ::sut/parallelism 1
              ::sut/active-work (atom {})})}}
          :conns []})
        started (flow/start graph)]
    (flow/resume graph)
    {::graph graph
     ::started started}))

(defn- stop-source-testbed!
  [{::keys [graph]}]
  (flow/stop graph))

(defn- synthetic-core-fault
  [ordinal]
  #::flow{:pid :source
          :status :running
          :cid ::source
          :msg ordinal
          :op ::work
          :ex (RuntimeException.
               (str "synthetic core fault " ordinal))})

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
         ::on-core-error :record}])
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
    [{:seon.error/signature signature} ::sut/committed false]))

(defn- committed-faults
  [database]
  (db/q
   '[:find ?proc ?message
     :where
     [?fault :seon.flow-test/fault-id]
     [?fault :seon.flow-test/fault-proc ?proc]
     [?fault :seon.flow-test/fault-message ?message]]
   database))

(defn- committed-overflow-count
  [database]
  (or (db/q
       '[:find (sum ?count) .
         :where
         [?drop :seon.error/kind :seon.flow/fault-channel-overflow]
         [?drop :seon.error/dropped-fault-count ?count]]
       database)
      0))

(defn- committed-overflows
  [database]
  (db/q
   '[:find ?count ?digest ?proc ?message
     :where
     [?drop :seon.error/kind :seon.flow/fault-channel-overflow]
     [?drop :seon.error/dropped-fault-count ?count]
     [?drop :seon.error/dropped-fault-digest ?digest]
     [?drop :seon.error/proc ?proc]
     [?drop :seon.error/message ?message]]
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
        (sut/start-work-launcher! {:seon.env/environment @test-environment
                                   ::sut/configuration configuration})
        terminals (mapv (fn [_] (promise)) (range 3))
        submission
        (fn [ordinal]
          {:seon.env/environment @test-environment
           ::sut/submission-id (keyword (str "background-" ordinal))
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
        (sut/start-work-launcher! {:seon.env/environment @test-environment
                                   ::sut/configuration configuration})]
    (let [result-a
          (future
            (sut/submit!!
             launcher-a
             {:seon.env/environment @test-environment
              ::sut/submission-id ::cluster-a-work
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
               {:seon.env/environment @test-environment
                ::sut/configuration configuration})]
          (try
            (is (= ::cluster-b-completed
                   (::sut/value
                    (sut/submit!!
                     launcher-b
                     {:seon.env/environment @test-environment
                      ::sut/submission-id ::cluster-b-work
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
           {:seon.env/environment @test-environment
            :seon.flow/work-launcher @test-work-launcher}
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

(defn- start-test-fanout!
  [connection graph started fault-buffer-capacity monitor-buffer-capacity]
  (sut/start-error-fanout!
   {:seon.env/environment @test-environment
    ::sut/graph graph
    ::sut/started started
    ::sut/fault-buffer-capacity fault-buffer-capacity
    ::sut/monitor-buffer-capacity monitor-buffer-capacity
    ::sut/read-core-error-mode #(core-error-mode connection)
    ::sut/commit-fault! #(commit-fault! connection %)
    ::sut/commit-drop! #(commit-fault! connection %)
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
        (let [{::keys [graph started] :as testbed}
              (source-testbed)
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
                  (async/>!! (:error-chan started)
                             (synthetic-core-fault 0))
                  (test-support/await-event!
                   monitor-messages
                   ::monitor-core-fault
                   (fn [{:keys [action data]}]
                     (and (= :error action)
                          (str/includes? data ":pid :source")
                          (str/includes? data "synthetic core fault 0"))))
                  (async/>!! (:report-chan started)
                             {::sut/result ::fanout-report})
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
             #(= #{[:source "synthetic core fault 0"]}
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
              (stop-source-testbed! testbed))))))))

(deftest an-instrumentation-fault-commits-without-an-ambient-projection
  (let [commit-core-fault!
        (var-get (ns-resolve 'seon.cluster 'commit-fault!))
        resolve-var #'schema.datahike/resolve-datahike-form-in
        resolve-filter (mi/-filter-var #{resolve-var})
        caps {:seon.config.eval.result/max-depth 8
              :seon.config.eval.result/max-collection 8
              :seon.config.eval.result/max-string 256
              :seon.config.eval.result/max-nodes 64}
        contract-fault
        {::flow/pid :render
         ::flow/op :step
         ::flow/ex
         (ex-info
          "seon.render.walk/root-acquisition violated its contract"
          {:seon.error/kind :seon.instrument/contract-violated
           :seon.error/data
           {:seon.instrument/fn "seon.render.walk/root-acquisition"
            :seon.instrument/arm :input
            :seon.instrument/schema
            ":seon.render.walk/acquisition-request"
            :seon.instrument/args "[{:seon.render.walk/lookup :root}]"}})}]
    (test-support/with-database
      (fn [connection]
        (try
          ;; Reproduce the live fault-committer boundary: this public bridge
          ;; is instrumented, while its Flow thread has no ambient projection.
          (mi/clj-collect! {:ns ['seon.schema.datahike]})
          (mi/instrument! {:filters [resolve-filter]})
          (with-redefs [config/effective
                        (fn [_database _cluster-name]
                          {:seon.config.error/recurrence-limit 3
                           :seon.config.eval.result/blob-threshold 4096})]
            (let [[fact outcome]
                  (commit-core-fault! connection "fault-test"
                                      "process-instrumentation" caps
                                      contract-fault)
                  stored
                  (db/pull @connection
                           '[*]
                           [:seon.error/id (:seon.error/id fact)])]
              (is (= ::sut/committed outcome))
              (is (= "seon.render.walk/root-acquisition"
                     (:seon.instrument/fn stored)))
              (is (= ":seon.render.walk/acquisition-request"
                     (:seon.instrument/expected stored)))
              (is (boolean? (:seon.error/capped? stored)))))
          (finally
            (mi/unstrument! {:filters [resolve-filter]})))))))

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
    (testing "132 equal faults produce 132 facts and one bounded stderr face"
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
                  (db/q '[:find ?error ?signature ?message ?capped? ?data-edn
                          :where
                          [?error :seon.error/signature ?signature]
                          [?error :seon.error/message ?message]
                          [?error :seon.error/capped? ?capped?]
                          [?error :seon.error/data-edn ?data-edn]]
                        @connection)
                  [_error signature message capped? data-edn] (first facts)
                  recurrence
                  (db/q '[:find (count ?error) .
                          :in $ ?signature ?process
                          :where
                          [?error :seon.error/signature ?signature]
                          [?error :seon.error/process ?process]]
                        @connection signature "process-1")
                  lines (str/split-lines (str stderr-writer))]
              (is (= 132 (count facts))
                  "every equal envelope owns a durable fact")
              (is (= 132 recurrence)
                  "recurrence is the query-derived count of those facts")
              (is (= 132 (::sut/committed final-state)))
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
            (let [[durable-fact durable-outcome _]
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
                  fact-count
                  (db/q '[:find (count ?error) .
                          :where [?error :seon.error/signature _]]
                        @connection)
                  lines (str/split-lines (str stderr-writer))]
              (is (= ::sut/committed durable-outcome))
              (is (empty? duplicate-stderr)
                  "the durable signature suppresses output after rebuild")
              (is (contains? signature-set
                             (:seon.error/signature durable-fact)))
              (is (= 2 (count signature-set)))
              (is (= 3 fact-count)
                  "the rebuilt proc commits the repeated occurrence too")
              (is (= signature-set (::sut/seen-signatures final-state)))
              (is (= 2 (::sut/committed final-state)))
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
            (is (= 3 @transaction-attempts)
                "every occurrence reaches the writer even while output is bounded")
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
  (let [{::keys [graph started] :as testbed} (source-testbed)
        commit-entered (CountDownLatch. 1)
        finish-commit (CountDownLatch. 1)
        fanout
        (sut/start-error-fanout!
         {:seon.env/environment @test-environment
          ::sut/graph graph
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
      (async/>!! (:error-chan started) (synthetic-core-fault 0))
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
        (stop-source-testbed! testbed)))))

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
              {::keys [graph started] :as testbed}
              (source-testbed)
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
            (doseq [fault (map synthetic-core-fault (range fault-count))]
              (async/>!! (:error-chan started) fault))
            (dotimes [_ fault-count]
              (test-support/await-event!
               (::sut/monitor-error-channel fanout)
               ::monitor-buffered-core-fault))
            (is (empty? (committed-faults @connection)))
            (flow/resume-proc fault-graph ::sut/fault-committer)
            (test-support/await-event!
             (::channel transactions)
             ::all-core-faults-committed
             #(<= fault-count
                  (count (committed-faults (:db-after %)))))
            (is (= (set (map #(str "synthetic core fault " %)
                             (range fault-count)))
                   (set (map second
                             (committed-faults @connection)))))
            (finally
              (stop-database-events! connection transactions)
              (sut/stop-error-fanout! fanout)
              (stop-source-testbed! testbed))))))))

(deftest fault-tap-overflow-commits-a-queryable-drop-fact
  (testing "faults beyond the bounded tap become one queryable drop batch"
    (test-support/with-database
      (fn [connection]
        (let [commit-core-fault!
              (var-get (ns-resolve 'seon.cluster 'commit-fault!))
              fault-buffer-capacity 2
              fault-count 5
              caps {:seon.config.eval.result/max-depth 8
                    :seon.config.eval.result/max-collection 8
                    :seon.config.eval.result/max-string 64
                    :seon.config.eval.result/max-nodes 64}
              effective
              (fn [_database _cluster-name]
                {:seon.config.error/recurrence-limit 3
                 :seon.config.eval.result/blob-threshold 256})
              {::keys [graph started] :as testbed} (source-testbed)
              fanout
              (sut/start-error-fanout!
               {:seon.env/environment @test-environment
                ::sut/graph graph
                ::sut/started started
                ::sut/fault-buffer-capacity fault-buffer-capacity
                ::sut/monitor-buffer-capacity fault-count
                ::sut/read-core-error-mode (constantly :record)
                ::sut/commit-fault!
                #(commit-core-fault! connection "fault-test" "process-overflow"
                                     caps %)
                ::sut/commit-drop!
                #(commit-core-fault! connection "fault-test" "process-overflow"
                                     caps %)
                ::sut/panic!
                (fn [fault]
                  (throw (ex-info "Record mode unexpectedly panicked."
                                  {::fault fault})))})
              fault-graph (::sut/fault-graph fanout)
              transactions (database-events connection)]
          (try
            (with-redefs [config/effective effective]
              (flow/pause-proc fault-graph ::sut/fault-committer)
              (is (= :paused
                     (::flow/status
                      (flow/ping-proc
                       fault-graph ::sut/fault-committer))))
              (doseq [fault (map synthetic-core-fault (range fault-count))]
                (async/>!! (:error-chan started) fault))
              (dotimes [_ fault-count]
                (test-support/await-event!
                 (::sut/monitor-error-channel fanout)
                 ::monitor-overflow-core-fault))
              (is (zero? (committed-overflow-count @connection))
                  "the paused committer proves the producer did not transact")
              (flow/resume-proc fault-graph ::sut/fault-committer)
              (test-support/await-event!
               (::channel transactions)
               ::fault-drops-committed
               #(<= (- fault-count fault-buffer-capacity)
                    (committed-overflow-count (:db-after %))))
              (test-support/await-event!
               (::channel transactions)
               ::retained-core-faults-committed
               #(<= (inc fault-buffer-capacity)
                    (db/q '[:find (count ?error) .
                            :where [?error :seon.error/id]]
                          (:db-after %))))
              (is (= 3 (committed-overflow-count @connection)))
              (let [[drop-count digest proc message]
                    (first (committed-overflows @connection))]
                (is (= 3 drop-count))
                (is (= 64 (count digest)))
                (is (= :source proc))
                (is (str/includes? message "dropped 3 faults"))))
            (finally
              (stop-database-events! connection transactions)
              (sut/stop-error-fanout! fanout)
              (stop-source-testbed! testbed))))))))

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
  (install-test-work-launcher!
   {::sut/configuration
    {:seon.config.flow.compute/queue-depth 2
     :seon.config.flow.compute/concurrency 1}})
  (let [{::sut/keys [graph started]} @test-work-launcher
        client (HttpClient/newHttpClient)
        fanout
        (sut/start-error-fanout!
         {:seon.env/environment @test-environment
          ::sut/graph graph
          ::sut/started started
          ::sut/fault-buffer-capacity 8
          ::sut/monitor-buffer-capacity 8
          ::sut/read-core-error-mode (constantly :record)
          ::sut/commit-fault! (fn [_])
          ::sut/commit-drop! (fn [_])
          ::sut/panic! (fn [_])})
        monitor-state
        (flow-monitor/start-server
         {:flow (::sut/graph fanout)
          :port 0})]
    (try
      (let [port (:port @monitor-state)
            request
            (-> (HttpRequest/newBuilder)
                (.uri (URI/create
                       (str "http://127.0.0.1:" port "/index.html")))
                .GET
                .build)
            response
            (.send client request (HttpResponse$BodyHandlers/ofString))
            [socket messages] (monitor-websocket-messages client port)
            graph-message (first messages)]
        (is (= 200 (.statusCode response)))
        (is (str/includes? (.body response) "<title>Flow Monitor</title>"))
        (is (str/includes? graph-message "~:datafy"))
        (doseq [pid ["~:seon.flow/work-launcher"
                     "~:seon.flow/capacity-observer"]]
          (is (str/includes? graph-message pid)
              (str "monitor graph data names " pid)))
        (.join (.sendClose
                ^WebSocket socket
                WebSocket/NORMAL_CLOSURE
                "test complete")))
      (finally
        (flow-monitor/stop-server monitor-state)
        (sut/stop-error-fanout! fanout)
        (stop-test-work-launcher!)))))
