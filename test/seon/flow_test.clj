(ns seon.flow-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.core.async.flow-monitor :as flow-monitor]
            [clojure.datafy :as datafy]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.core :as datahike]
            [seon.flow :as sut])
  (:import [java.io File]
           [java.net ServerSocket URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers
            WebSocket WebSocket$Listener]
           [java.util.concurrent CountDownLatch ExecutorService Future
            TimeUnit]))

(def ^:private event-backstop-seconds
  ;; Every wait below has a real publisher. This clock turns a missing event
  ;; into a named failure instead of wedging the recurring writer gate.
  20)

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

(defn- await-latch!
  [^CountDownLatch latch event]
  (when-not (.await latch event-backstop-seconds TimeUnit/SECONDS)
    (throw
     (ex-info
      "The Flow testbed did not observe its required event."
      {::event event}))))

(defn- take-event!
  ([channel event]
   (take-event! channel event (constantly true)))
  ([channel event accept?]
   (let [backstop
         (async/timeout
          (.toMillis TimeUnit/SECONDS event-backstop-seconds))]
     (loop []
       (let [[value selected] (async/alts!! [channel backstop])]
         (cond
           (= selected backstop)
           (throw
            (ex-info
             "The Flow testbed channel did not publish its required event."
             {::event event}))

           (accept? value)
           value

           :else
           (recur)))))))

(defn- database-events
  [connection]
  (let [channel (async/chan 16)
        listener-key (random-uuid)]
    (datahike/listen! connection listener-key #(async/>!! channel %))
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
             executor event-backstop-seconds TimeUnit/SECONDS)
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
  ([completed value]
   (work-message completed value 0))
  ([completed value work-ms]
   {::sut/work-fn
    (fn [{::sut/keys [interrupt-fn]}]
      (interrupt-fn)
      (when (pos? work-ms)
        (Thread/sleep work-ms))
      (swap! completed conj value)
      value)
    ::sut/wedged? false}))

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
      (d/transact connection fault-schema)
      (d/transact
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
  (d/q
   '[:find ?mode .
     :where
     [?config :seon.flow-test/core-error-config-id "testbed"]
     [?config :seon.flow-test/on-core-error ?mode]]
   @connection))

(defn- commit-fault!
  [connection fault]
  (d/transact
   connection
   [{::fault-id (random-uuid)
     ::fault-proc (::flow/pid fault)
     ::fault-message (ex-message (::flow/ex fault))}]))

(defn- commit-fault-drop!
  [connection _fault]
  (let [count
        (d/q
         '[:find ?count .
           :where
           [?counter :seon.flow-test/fault-drop-id "fault-committer"]
           [?counter :seon.flow-test/fault-drop-count ?count]]
         @connection)]
    (d/transact
     connection
     [{::fault-drop-id "fault-committer"
       ::fault-drop-count (inc count)}])))

(defn- committed-faults
  [database]
  (d/q
   '[:find ?proc ?message
     :where
     [?fault :seon.flow-test/fault-id]
     [?fault :seon.flow-test/fault-proc ?proc]
     [?fault :seon.flow-test/fault-message ?message]]
   database))

(defn- committed-drop-count
  [database]
  (d/q
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
        (sut/install-work-launcher!
         {::sut/configuration configuration})
        wedge-ids #{:wedge-0 :wedge-1}
        wedges
        (mapv
         (fn [submission-id]
           (future
             (sut/submit!!
              {::sut/submission-id submission-id
               ::sut/workload :compute
               ::sut/time-limit-ms 25
               ::sut/work-fn
               (fn [{::sut/keys [started!]}]
                 (started!)
                 (.countDown wedge-started)
                 (await-latch! release-wedges ::release-production-wedges)
                 ::released)})))
         wedge-ids)]
    (try
      (await-latch! wedge-started ::production-wedges-started)
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
        (is (true? (::sut/platform-threads? observer-state))))
      (let [remaining
            (mapv
             (fn [submission-id]
               (future
                 (sut/submit!!
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
          (take-event!
           (get-in launcher [::sut/started :report-chan])
           ::production-wedge-released
           #(contains? wedge-ids (::sut/submission-id %))))
        (is (empty? @(::sut/active-work launcher)))
        (sut/stop-installed-work-launcher!)))))

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
                         event-backstop-seconds
                         TimeUnit/SECONDS)
                (throw
                 (ex-info
                  "The wedge release event did not arrive."
                  {::event ::wedge-release})))
              ::released)
            ::sut/wedged? true}]))
      (await-latch! wedge-started ::wedges-started)

      (testing "ping and report name every wedge and the exact capacity loss"
        (let [observer-state
              (::flow/state (flow/ping-proc graph :observer))]
          (is (= wedged-pids (::sut/active-procs observer-state)))
          (is (= wedged-pids (::sut/wedged-procs observer-state)))
          (is (= (- parallelism wedge-count)
                 (::sut/available-permits observer-state)))
          (is (true? (::sut/platform-threads? observer-state))))
        @(flow/inject graph [:observer ::sut/observe] [::observe])
        (let [report (take-event! (:report-chan started) ::capacity-report)]
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
        (await-latch! remaining-work-finished ::remaining-capacity)
        (dotimes [_ (- parallelism wedge-count)]
          (take-event!
           (:report-chan started)
           ::normal-work-completed
           #(contains? #{:eval-2 :eval-3} (::sut/pid %))))
        (is (= wedge-count (count @active-evals))))
      (finally
        (.countDown release-wedges)
        (dotimes [_ wedge-count]
          (take-event!
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
                      event-backstop-seconds
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
                event-backstop-seconds
                TimeUnit/SECONDS)
          (dotimes [_ 6]
            (take-event!
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
        (let [report (take-event! report-chan ::interrupt-report)
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
        (take-event!
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
        (let [error (take-event! error-chan ::flow-step-error)]
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
        (take-event!
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

(declare free-port)

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
                      :port (free-port)})]
                (try
                  (flow/resume graph)
                  @(flow/inject
                    graph
                    [:eval ::sut/submission]
                    [(throwing-work-message 0)])
                  (take-event!
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
                        (take-event!
                         (::sut/application-report-channel fanout)
                         ::application-report-copy)]
                    (is (= ::fanout-report
                           (::sut/result application-copy))))
                  (take-event!
                   monitor-messages
                   ::monitor-report-copy
                   (fn [{:keys [action data]}]
                     (and (= :message action)
                          (= ::fanout-report (::sut/result data)))))
                  (finally
                    (flow-monitor/stop-server monitor-state)))))
            (take-event!
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
            (await-latch! finish-commit ::finish-fault-commit))
          ::sut/commit-drop! (fn [_])
          ::sut/panic! (fn [_])})]
    (try
      (flow/resume graph)
      @(flow/inject graph
                    [:eval ::sut/submission]
                    [(throwing-work-message 0)])
      (await-latch! commit-entered ::fault-commit-entered)
      (let [stopped (future (sut/stop-error-fanout! fanout))]
        (is (= ::still-stopping
               (deref stopped 1000 ::still-stopping))
            "the fanout keeps its database dependency while commit is active")
        (.countDown finish-commit)
        (is (true? (deref stopped 5000 ::stop-stuck))
            "the fault proc publishes completion after the commit returns"))
      (finally
        (.countDown finish-commit)
        (stop-testbed! testbed)))))

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
              (take-event!
               (::sut/monitor-error-channel fanout)
               ::monitor-buffered-core-fault))
            (is (empty? (committed-faults @connection)))
            (is (zero? (committed-drop-count @connection)))
            (flow/resume-proc fault-graph ::sut/fault-committer)
            (take-event!
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
              (take-event!
               (::sut/monitor-error-channel fanout)
               ::monitor-overflow-core-fault))
            (take-event!
             (::channel transactions)
             ::fault-drops-committed
             #(<= (- fault-count fault-buffer-capacity)
                  (committed-drop-count (:db-after %))))
            (flow/resume-proc fault-graph ::sut/fault-committer)
            (take-event!
             (::channel transactions)
             ::retained-core-faults-committed
             #(<= fault-buffer-capacity
                  (count (committed-faults (:db-after %)))))
            (is (= 3 (committed-drop-count @connection)))
            (finally
              (stop-database-events! connection transactions)
              (sut/stop-error-fanout! fanout)
              (stop-testbed! testbed))))))))

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
                event-backstop-seconds
                TimeUnit/SECONDS)
          (is (.isDone ^Future injection))
          (is (= {:type 'SlidingBuffer :count 1 :capacity 1}
                 (:buffer
                  (channel-data graph :mailbox ::sut/mailbox))))
          (flow/resume graph)
          (is (= 99 (take-event! delivered ::latest-mail))))
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
                  (await-latch! release-mid-step ::release-mid-step))
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
                event-backstop-seconds
                TimeUnit/SECONDS)
          (flow/resume graph)
          (await-latch! mid-step ::load-mid-step)
          (flow/pause graph)
          (.countDown release-mid-step)
          (take-event!
           (:report-chan started)
           ::load-paused-after-current-step
           #(= 3 (::sut/result %)))
          (is (= :paused
                 (::flow/status (flow/ping-proc graph :eval))))
          (is (nil? (async/poll! (:report-chan started)))
              "the acknowledged pause publishes no next completion")
          (flow/resume graph)
          (take-event!
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
        (take-event!
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

(deftest forced-child-jvm-death-preserves-committed-facts
  (testing "SIGKILL loses process-local compute but not committed state"
    (let [store-id (random-uuid)
          root (File. "tmp" (str "flow-kill-" store-id))
          database-path (.getPath (File. root "db"))
          ready-file (File. root "committed.ready")
          _ (.mkdirs root)
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
        (let [limit (+ (System/nanoTime)
                       (.toNanos TimeUnit/SECONDS 20))]
          (loop []
            (cond
              (.exists ready-file) true
              (not (.isAlive process))
              (throw
               (ex-info
                "The child JVM exited before committing its durable fact."
                {::event ::child-exited
                 ::exit (.exitValue process)
                 ::output (slurp (.getInputStream process))}))
              (< (System/nanoTime) limit)
              (do (Thread/sleep 10) (recur))
              :else
              (throw
               (ex-info
                "The child JVM did not publish committed-fact readiness."
                {::event ::child-committed})))))
        (let [kill-process
              (.start
               (ProcessBuilder.
                ^java.util.List
                ["kill" "-9" (str (.pid process))]))]
          (is (.waitFor kill-process
                        event-backstop-seconds
                        TimeUnit/SECONDS))
          (is (zero? (.exitValue kill-process))))
        (is (.waitFor process
                      event-backstop-seconds
                      TimeUnit/SECONDS))
        (is (false? (.isAlive process)))
        (let [connection (d/connect configuration)]
          (try
            (is (= 1
                   (d/q
                    '[:find ?count .
                      :where
                      [?entity :seon.flow.kill/id "durable-step"]
                      [?entity :seon.flow.kill/count ?count]]
                    @connection)))
            (finally
              (d/release connection))))
        (finally
          (when (.isAlive process)
            (.destroyForcibly process))
          (when (d/database-exists? configuration)
            (d/delete-database configuration))
          (.delete ready-file)
          (.delete root))))))

(defn- free-port
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

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
    (await-latch! initial-and-ping ::monitor-datafy-and-ping)
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
        port (free-port)
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
              (await-latch! release-wedge ::release-monitor-wedge)
              ::released)
            ::sut/wedged? true}])
        (await-latch! wedge-started ::monitor-wedge-started)
        (let [filled-injection
              (flow/inject
               graph
               [:eval ::sut/submission]
               (mapv #(work-message post-wedge-results %) (range 2)))
              _ (.get ^Future filled-injection
                      event-backstop-seconds
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
                :port port})]
          (try
            (let [request
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
                    event-backstop-seconds
                    TimeUnit/SECONDS)
              (take-event!
               (::sut/application-report-channel fanout)
               ::monitor-post-wedge-drain
               #(= 2 (::sut/result %)))
              (is (= 3 (count @post-wedge-results)))
              (sut/stop-error-fanout! fanout)
              (flow/stop graph)
              (stop-executor! compute-executor))))))
