(ns seon.flow-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.core.async.flow.impl.graph :as flow.graph]
            [clojure.core.async.flow-monitor :as flow-monitor]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.fault :as fault]
            [datahike.core :as datahike]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.instrument :as mi]
            [seon.config :as config]
            [seon.flow :as sut]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as test-support])
  (:import [java.io File]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers
            WebSocket WebSocket$Listener]
           [java.nio.file ClosedWatchServiceException StandardWatchEventKinds
            WatchEvent$Kind WatchService]
           [java.util.concurrent CompletableFuture CountDownLatch TimeUnit]))

(def ^:private test-work-launcher (atom nil))

(def ^:private test-environment
  ;; Flow plumbing is a store-layer subject: these tests stand up no branch
  ;; and no facts, so they construct the subset environment every crossing
  ;; in this namespace names.
  (delay (test-support/environment "seon.flow-test")))

(defn- recording-environment
  "A configured fixture cluster: the fault writer reads its dials."
  [connection]
  (test-support/apply-config! connection "seon.flow-test" {})
  (test-support/environment "seon.flow-test" connection))

(def ^:private test-launcher-configuration
  (select-keys (test-support/effective-config)
               sut/flow-workload-attributes))

(def ^:private test-io-configuration
  (assoc test-launcher-configuration
         :seon.config.flow.io/queue-depth 2
         :seon.config.flow.io/concurrency 2
         :seon.config.agent/turn-completion-backstop-ms 60000))

(defn- earliest-resume-fault-step
  ([]
   {:ins {}
    :outs {}})
  ([args]
   args)
  ([state transition]
   (when (= ::flow/resume transition)
     (throw (ex-info "earliest resume fault"
                     {::resume-transition transition})))
   state)
  ([state _input _message]
   [state nil]))

(deftest graph-construction-joins-every-declared-tap-before-resume
  (let [{::sut/keys [graph joins]}
        (sut/start-graph!
         {::sut/graph-definition
          {:procs
           {::earliest-fault
            ;; a bare Flow proc: `var-process` would record the throw
            ;; itself, and this test observes Flow's own error channel
            {:proc (flow/process #'earliest-resume-fault-step {:workload :io})}}
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
        (is (= ::flow/resume
               (::resume-transition (ex-data (::flow/ex fault))))
            "the first resume transition reaches the declared tap"))
      (finally
        (flow/stop graph)
        (async/untap error-mult tap)
        (async/close! tap)))))

;;; The join: a graph's own executor terminates only when every proc loop,
;;; stop transition included, has returned.
(defn- join-probe-step
  ([] {:ins {::join-in "One message the transform holds until released."}
       :outs {}
       :workload :io})
  ([args] (assoc args ::flow/in-ports {::join-in (::join-in args)}))
  ([state _transition] state)
  ([{::keys [entered release] :as state} _input _message]
   (.countDown ^CountDownLatch entered)
   (.await ^CountDownLatch release)
   [state nil]))

(defn- join-probe-graph!
  [entered release]
  (let [in (async/chan 1)]
    (assoc (sut/start-graph!
            {::sut/graph-definition
             {:procs {::join-probe
                      {:proc (sut/var-process #'join-probe-step :io
                                              {:seon.env/environment @test-environment
                                               ::join-in in
                                               ::entered entered
                                               ::release release})}}
              :conns []}})
           ::in in)))

(defn- join-bound
  [bound-ms]
  {:seon.await/config-attribute :seon.config.agent/turn-completion-backstop-ms
   :seon.await/config-value bound-ms
   :seon.await/deadline-nanos (+ (System/nanoTime) (* 1000000 bound-ms))})

(deftest stop-joins-the-graph-only-after-its-procs-exit
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        {::sut/keys [graph executor] ::keys [in]} (join-probe-graph! entered release)
        diagnostic {:seon.error/layer :flow
                    :seon.error/operation ::stop-joins
                    :seon.error/expected ::join-probe-exited}]
    (try
      (async/>!! in ::hold)
      (test-support/await-event! entered ::transform-entered)
      (flow/stop graph)
      (let [started (System/nanoTime)
            busy (sut/join-graph! {::sut/executor executor
                                   :seon.await/bound (join-bound 200)
                                   :seon.await/diagnostic diagnostic})
            elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
        (is (number? (:seon.await/elapsed-ms busy)) (pr-str busy))
        (is (= ::join-probe-exited (:seon.error/expected busy))
            "the expiry names the exit that did not arrive")
        (is (<= 190 elapsed-ms 450) (str "the one deadline bounds the join: " elapsed-ms))
        (is (not (.isTerminated ^java.util.concurrent.ExecutorService executor))
            "a missed deadline keeps the live proc visible"))
      (.countDown release)
      (is (nil? (sut/join-graph! {::sut/executor executor
                                  :seon.await/bound (join-bound 1000)
                                  :seon.await/diagnostic diagnostic}))
          "once the transform returns the stop transition runs and the loop exits")
      (is (.isTerminated ^java.util.concurrent.ExecutorService executor))
      (finally
        (.countDown release)
        (flow/stop graph)))))

(deftest an-idle-graph-joins-at-once
  (let [{::sut/keys [graph executor]} (join-probe-graph! (CountDownLatch. 1) (CountDownLatch. 1))]
    (flow/stop graph)
    (is (nil? (sut/join-graph! {::sut/executor executor
                                :seon.await/bound (join-bound 1000)
                                :seon.await/diagnostic {:seon.error/layer :flow
                                                        :seon.error/operation ::idle-join}})))))

;;; §5 gate 2: a stop transition that throws. Flow alone would park the loop
;;; forever (impl.clj:317-320); `var-process` records the throw and returns
;;; the state, so the loop exits and the join completes.
(defn- throwing-stop-step
  ([] {:ins {} :outs {} :workload :io})
  ([args] args)
  ([state transition]
   (when (= ::flow/stop transition)
     (throw (ex-info "gate-2 stop transition throws" {::gate 2})))
   state)
  ([state _input _message] [state nil]))

(deftest a-throwing-stop-transition-is-one-stored-fault-and-a-completed-join
  (test-support/with-database
    (fn [connection]
      (let [environment (recording-environment connection)
            {::sut/keys [graph executor]}
            (sut/start-graph!
             {::sut/graph-definition
              {:procs {::throwing-stop
                       {:proc (sut/var-process #'throwing-stop-step :io
                                               {:seon.env/environment environment})}}
               :conns []}})
            stderr (java.io.StringWriter.)
            exited (binding [*err* stderr]
                     (flow/stop graph)
                     (sut/join-graph! {::sut/executor executor
                                       :seon.await/bound (join-bound 2000)
                                       :seon.await/diagnostic
                                       {:seon.error/layer :flow
                                        :seon.error/operation ::throwing-stop}}))
            stored (db/q '[:find [?message ...]
                           :where [?o :seon.error.occurrence/message ?message]]
                         @connection)]
        (is (nil? exited) (pr-str exited))
        (is (.isTerminated ^java.util.concurrent.ExecutorService executor)
            "no proc thread of the graph survives the throw")
        (is (= 1 (count (filter #(str/includes? % "gate-2 stop transition throws") stored)))
            (pr-str stored))))))

;;; An unstored panic prints bounded text: the class regression for the
;;; 3.18 GB stderr lines of pid 68592 (issue
;;; unstored-panic-prints-whole-throwable-map-to-stderr). Each cause prints
;;; through `render.value/floor` (at most 8192 chars + ellipsis); the whole
;;; value stays on the failure's ex-data.
(deftest an-unstored-panic-prints-bounded-text-and-keeps-the-whole-cause
  (test-support/with-database
    (fn [connection]
      (let [big (apply str (repeat 51000000 \x))
            failure (ex-info "a proc step threw" {::big big})
            unconfigured @test-environment
            bound (* 3 8193)
            printed (fn [call]
                      (let [stderr (java.io.StringWriter.)
                            result (binding [*err* stderr] (call))]
                        [result (str stderr)]))
            [proc-result proc-text]
            (printed #(#'sut/proc-failed! #'throwing-stop-step
                                          {:seon.env/environment unconfigured}
                                          failure))
            [listener-result listener-text]
            (printed #(#'store/listener-failed!
                       unconfigured "process-1"
                       {:listener-key ::probe :exception failure
                        :tx-report {:tx-meta {} :db-after @connection}}))]
        (is (true? proc-result) "the proc sink answers the panic so its graph stops")
        (is (nil? listener-result) "the listener sink returns as before")
        (doseq [text [proc-text listener-text]]
          (is (< 0 (count text) bound) (str (count text) " stderr chars"))
          (is (str/includes? text "a proc step threw") "the cause is named"))
        (is (identical? big (::big (ex-data failure)))
            "the whole value stays on the throwable")))))

(defn- install-test-work-launcher!
  [request]
  (let [launcher
        (sut/start-work-launcher!
         (-> request
             (update :seon.env/environment #(or % @test-environment))
             (update ::sut/configuration
                     #(merge test-io-configuration %))))]
    (reset! test-work-launcher launcher)
    launcher))

(defn- stop-test-work-launcher!
  []
  (when-let [launcher
             (first (swap-vals! test-work-launcher (constantly nil)))]
    (sut/stop-work-launcher! launcher)))

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
            (sut/var-process #'join-probe-step :io
                             {:seon.env/environment @test-environment
                              ::join-in (async/chan)})}}
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
  (test-support/with-database
    {::test-support/extra-schema fault-schema}
    (fn [connection]
      (test-support/transacted!
                   connection
                   [{::core-error-config-id "testbed"
                     ::on-core-error :record}])
      (body connection))))

(defn- core-error-mode
  [connection]
  (db/q
   '[:find ?mode .
     :where
     [?config :seon.flow-test/core-error-config-id "testbed"]
     [?config :seon.flow-test/on-core-error ?mode]]
   @connection))

(defn- commit-fault!
  [connection observation]
  (let [fault (:seon.error/source observation)
        message (ex-message (::flow/ex fault))
        signature (str (::flow/pid fault) "|" message)]
    (test-support/transacted!
                 connection
                 [{::fault-id (random-uuid)
                   ::fault-proc (::flow/pid fault)
                   ::fault-message message}])
    [{:seon.error/signature signature} ::sut/committed nil]))

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
         [?error :seon.error/occurrences ?drop]
         [?drop :seon.error/dropped-fault-count ?count]]
       database)
      0))

(defn- committed-overflows
  [database]
  (db/q
   '[:find ?count ?digest ?proc ?message
     :where
     [?error :seon.error/occurrences ?drop]
     [?drop :seon.error/dropped-fault-count ?count]
     [?drop :seon.error/dropped-fault-digest ?digest]
     [?drop :seon.error/proc ?proc]
     [?drop :seon.error.occurrence/message ?message]]
   database))

;;; A throw during ordinary work: before, it waited in Flow's sliding error
;;; channel (100) and the fan-out's dropping buffer (64), which lost or
;;; collapsed a burst. Now each throw is written on the proc's own thread, so
;;; nothing waits in a buffer. Three throws, at ~115 ms per warm fault write.
(def ^:private work-throws 3)

(defn- throwing-work-step
  ([] {:ins {::work "One message the transform throws on."} :outs {} :workload :io})
  ([args] (assoc args ::flow/in-ports {::work (::work args)}))
  ([state _transition] state)
  ([_state _input message] (throw (ex-info "work throws" {::message message}))))

(deftest a-proc-throw-during-work-is-one-occurrence-never-dropped
  (test-support/with-database
    (fn [connection]
      (let [environment (recording-environment connection)
            ;; a panic stops the graph after one throw; recording continues
            _ (test-support/apply-config! connection "seon.flow-test"
                                          {:seon.config/on-core-error :record})
            in (async/chan work-throws)
            transactions (database-events connection)
            {::sut/keys [graph executor started]}
            (sut/start-graph!
             {::sut/graph-definition
              {:procs {::throwing-work
                       {:proc (sut/var-process #'throwing-work-step :io
                                               {:seon.env/environment environment
                                                ::work in})}}
               :conns []}})
            counted #(db/q '[:find (sum ?count) . :with ?occurrence
                             :where [?occurrence :seon.error.occurrence/message ?message]
                                    [(clojure.string/includes? ?message "work throws")]
                                    [?occurrence :seon.error.occurrence/count ?count]]
                           %)
            t0 (System/nanoTime)]
        (try
          (async/onto-chan!! in (range work-throws) false)
          (test-support/await-event! (::channel transactions) ::every-throw-stored
                                     #(= work-throws (counted (:db-after %))))
          (let [elapsed-ms (/ (- (System/nanoTime) t0) 1e6)]
            (is (= work-throws (counted @connection)) "every throw reached the writer")
            (is (= 1 (count (db/q '[:find [?occurrence ...]
                                    :where [?occurrence :seon.error.occurrence/message ?message]
                                           [(clojure.string/includes? ?message "work throws")]]
                                  @connection)))
                "equal throws are one occurrence, counted")
            (is (nil? (async/poll! (:error-chan started)))
                "nothing waits in Flow's error channel")
            (is (< elapsed-ms 2000.0) (str work-throws " fault writes took " elapsed-ms " ms")))
          (finally
            (stop-database-events! connection transactions)
            (flow/stop graph)
            (sut/join-graph! {::sut/executor executor
                              :seon.await/bound (join-bound 2000)
                              :seon.await/diagnostic {:seon.error/layer :flow
                                                      :seon.error/operation ::throwing-work}})))))))

;;; F4 (review-landings-2026-09-24): a panic stops its graph through Flow,
;;; so a queued input never runs after it; a recorded fault keeps the graph.
(defn- panicking-work-step
  ([] {:ins {::work "Messages; message 1 throws."} :outs {} :workload :io})
  ([args] (assoc args ::flow/in-ports {::work (::work args)}))
  ([state _transition] state)
  ([state _input message]
   (swap! (::seen state) conj message)
   (when (= 1 message) (throw (ex-info "panicking work" {::message message})))
   [state nil]))

(deftest a-panicking-transform-stops-its-graph-and-a-recorded-one-continues
  (doseq [mode [:panic :record]]
    (test-support/with-database
      (fn [connection]
        (test-support/apply-config! connection "seon.flow-test" {:seon.config/on-core-error mode})
        (let [in (async/chan 3)
              seen (atom [])
              _ (async/onto-chan!! in [0 1 2] false)
              {::sut/keys [graph executor]}
              (binding [*err* (java.io.StringWriter.)]
                (sut/start-graph!
                 {::sut/graph-definition
                  {:procs {::panicking-work
                           {:proc (sut/var-process #'panicking-work-step :io
                                                   {:seon.env/environment
                                                    (test-support/environment "seon.flow-test" connection)
                                                    ::work in ::seen seen})}}
                   :conns []}}))
              join! #(sut/join-graph! {::sut/executor executor
                                       :seon.await/bound (join-bound 2000)
                                       :seon.await/diagnostic {:seon.error/layer :flow
                                                               :seon.error/operation ::panicking-work}})]
          (if (= :panic mode)
            (do (is (nil? (join!)) "the panicked graph exits without an outside stop")
                (is (= [0 1] @seen) "the queued input never executes")
                (is (= 2 (async/poll! in)) "it stays queued"))
            (do (test-support/await-event! seen ::third-input #(= [0 1 2] %))
                (is (= [0 1 2] @seen) "a recorded fault keeps the graph running")
                (flow/stop graph)
                (is (nil? (join!))))))))))

;;; Lost-error seams P1: a panicked proc must not strand its graph. Flow fans
;;; control out through per-proc taps of 10 behind a control channel of 10
;;; (`impl.clj:99,150-165`). While a proc is stalled in its step, commands pile
;;; up; a proc that then stops ITSELF never reads its tap again, and `flow/stop`
;;; on its own thread blocks on the full control channel, so the graph and
;;; every later command froze (reproduced 2026-09-25 with 25 queued commands).
(defn- stalling-work-step
  ([] {:ins {::work "Messages; :stall waits on the gate, then throws."} :outs {} :workload :io})
  ([args] (assoc args ::flow/in-ports {::work (::work args)}))
  ([state _transition] state)
  ([state _input message]
   (when (= :stall message)
     (.countDown ^CountDownLatch (::entered state))
     (.await ^CountDownLatch (::gate state) 4 TimeUnit/SECONDS)
     (throw (ex-info "a stalled step failed" {::message message})))
   (swap! (::seen state) conj message)
   [state nil]))

(deftest a-panicked-proc-with-saturated-control-still-stops-its-graph
  (test-support/with-database
    (fn [connection]
      (test-support/apply-config! connection "seon.flow-test" {:seon.config/on-core-error :panic})
      (let [in (async/chan 3)
            seen (atom [])
            entered (CountDownLatch. 1)
            gate (CountDownLatch. 1)
            {::sut/keys [graph executor]}
            (binding [*err* (java.io.StringWriter.)]
              (sut/start-graph!
               {::sut/graph-definition
                {:procs {::stalling-work
                         {:proc (sut/var-process #'stalling-work-step :io
                                                 {:seon.env/environment
                                                  (test-support/environment "seon.flow-test" connection)
                                                  ::work in ::seen seen ::entered entered ::gate gate})}}
                 :conns []}}))
            issued (atom 0)
            done (atom 0)]
        (async/onto-chan!! in [:stall :after] false)
        (test-support/await-event! entered ::stalled)
        (let [commands (vec (repeatedly 25 #(async/thread (swap! issued inc)
                                                          (flow/ping-proc graph ::stalling-work 50)
                                                          (swap! done inc))))]
          (try
            ;; 25 commands exceed the 20 the stalled proc's tap and the control
            ;; channel can hold, so the later ones wait inside `send-command`
            (test-support/await-event! issued ::commands-issued #(= 25 %))
            (.countDown gate)
            (binding [*err* (java.io.StringWriter.)]
              (is (nil? (sut/join-graph! {::sut/executor executor
                                          :seon.await/bound (join-bound 3000)
                                          :seon.await/diagnostic {:seon.error/layer :flow
                                                                  :seon.error/operation ::stalling-work}}))
                  "the panicked graph stops and joins within its bound"))
            (doseq [command commands]
              (test-support/await-event! command ::queued-command-completed (constantly true) 3000))
            (is (= 25 @done) "every queued command completes once the graph stops")
            (is (= [] @seen) "the input queued behind the panic never runs")
            (finally
              (.countDown gate))))))))

(deftest background-io-submission-is-nonblocking-bounded-and-joined
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        virtual? (atom [])
        configuration
        (assoc test-launcher-configuration
               :seon.config.flow.io/queue-depth 1
               :seon.config.flow.io/concurrency 1
               :seon.config.agent/turn-completion-backstop-ms 60000)
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
        (is (= :io
               (get-in refused [::sut/value ::sut/submission-capacity]))))
      (sut/stop-work-launcher! launcher)
      (is (every? realized? (take 2 terminals))
          "stop joins terminal callbacks for every accepted submission")
      (is (every? true? @virtual?))
      (finally
        (.countDown release)
        (when-not (every? realized? (take 2 terminals))
          (sut/stop-work-launcher! launcher))))))

(deftest stopping-a-work-launcher-is-bounded-by-its-carried-config-fact
  (let [launcher
        (sut/start-work-launcher!
         {:seon.env/environment @test-environment
          ::sut/configuration
          (assoc test-io-configuration
                 :seon.config.agent/turn-completion-backstop-ms 20)})
        held (CountDownLatch. 1)
        ;; an executor whose one task never returns stands for a proc that
        ;; does not exit
        stand-in (doto (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
                   (.execute #(.await held)))
        result
        (sut/stop-work-launcher!
         (assoc launcher ::sut/executor stand-in))]
    (try
      (is (number? (:seon.await/elapsed-ms result)))
      (is (= ::sut/work-launcher-exited
             (get-in result
                     [:seon.await/requested-member])))
      (is (= :seon.config.agent/turn-completion-backstop-ms
             (get-in result
                     [:seon.await/config-attribute])))
      (finally
        (.countDown held)
        (.close ^java.util.concurrent.ExecutorService stand-in)
        (.close ^java.util.concurrent.ExecutorService (::sut/executor launcher))))))

(defn- start-test-fanout!
  ([connection graph started fault-buffer-capacity monitor-buffer-capacity]
   (start-test-fanout! connection graph started fault-buffer-capacity
                       monitor-buffer-capacity {}))
  ([connection graph started fault-buffer-capacity monitor-buffer-capacity overrides]
  (sut/start-error-fanout!
   (merge
   {:seon.env/environment (test-support/environment "fault-test" connection)
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
        {::fault fault})))}
    overrides))))

(def ^:private committer-losses
  "The channel the probed loss reporter offers to; nil drops."
  (atom nil))

(defn- failing-committer-step
  ;; handed as `::committer-step`: every arity but transform is production's
  ([] (#'sut/fault-committer-step))
  ([request] (#'sut/fault-committer-step request))
  ([state transition] (#'sut/fault-committer-step state transition))
  ([_state _input _fault]
   (throw (ex-info "committer wrapper failed"
                   {:seon.test/committer-wrapper-failed true}))))

(defn- probed-loss-report
  [escaped]
  (some-> @committer-losses (async/offer! escaped)))

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
            (testing "the dependency's unsupported protocol method refuses as data"
              (doseq [command [:probe :another-probe]]
                (let [result (flow.graph/command-proc
                              (::sut/graph fanout) :source command {})]
                  (is (= 'clojure.core.async.flow.impl.graph/command-proc (::sut/unsupported-method result)))
                  (is (m/validate :seon.error/value result
                                  {:registry (:seon.schema.projection/registry
                                              (schema/projection-from-database @connection))}))
                  (is (= [:source command {}]
                         (get-in result [:seon.error/offending]))))))
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
              (sut/stop-error-fanout! fanout (join-bound 5000))
              (stop-source-testbed! testbed))))))))

(deftest fault-committer-runs-with-the-projection-handed-at-construction
  (let [commit-core-fault!
        fault/record!
        caps (assoc (config/result-caps
                     (test-support/effective-config))
                    :seon.config.eval.result/max-depth 8
                    :seon.config.eval.result/max-collection 8
                    :seon.config.eval.result/max-string 256
                    :seon.config.eval.result/max-source 1048576
                    :seon.config.eval.result/max-nodes 64)
        contract-fault
        {::flow/pid :render
         ::flow/op :step
         ::flow/ex
         (ex-info
          "A Flow proc requires a pinned workload."
          (test-support/refusal-data
           #(sut/var-process #'earliest-resume-fault-step :mixed
                             {:seon.env/environment @test-environment})))}]
    (test-support/with-database
      (fn [connection]
        (let [cluster-name "fault-test"
              _ (config/apply! {:seon.db/connection connection
                                :seon.boot/cluster-name cluster-name})
              environment (test-support/environment cluster-name connection)
              {::keys [graph started] :as testbed} (source-testbed)
              transactions (database-events connection)
              fanout
              (sut/start-error-fanout!
               {:seon.env/environment environment
                ::sut/graph graph
                ::sut/started started
                ::sut/fault-buffer-capacity 4
                ::sut/monitor-buffer-capacity 4
                ::sut/read-core-error-mode
                (fn []
                  (:seon.config/on-core-error
                   (config/effective @connection cluster-name)))
                ::sut/commit-fault!
                #(commit-core-fault! connection cluster-name
                                     "process-instrumentation" caps %)
                ::sut/commit-drop!
                #(commit-core-fault! connection cluster-name
                                     "process-instrumentation" caps %)
                ::sut/panic! (fn [_])})]
          (try
            ;; Reproduce the live boundary: both config/effective and this
            ;; public bridge require the projection on the committer thread.
            (is (some? (-> #'schema.datahike/storage-schema
                           deref meta ::mi/original))
                "the canonical gate already armed the production bridge")
            (async/>!! (:error-chan started) contract-fault)
            (let [transaction
                  (test-support/await-event!
                   (::channel transactions)
                   ::projected-fault-committed
                   #(seq (committed-faults (:db-after %))))
                  stored
                  (db/q '[:find (pull ?error [*]) .
                          :where
                          [?error :seon.instrument/fn
                           seon.flow/var-process]]
                        (:db-after transaction))]
              (is (= 'seon.flow/var-process
                     (:seon.instrument/fn stored)))
              (is (= :input (:seon.instrument/check stored))))
            (finally
              (stop-database-events! connection transactions)
              (sut/stop-error-fanout! fanout (join-bound 5000))
              (stop-source-testbed! testbed))))))))

(deftest fault-committer-own-error-channel-reaches-the-last-resort
  (with-fault-database
    (fn [connection]
      (let [{::keys [graph started] :as testbed} (source-testbed)
        losses (async/chan 1)
        _ (reset! committer-losses losses)
        fanout (start-test-fanout! connection graph started 2 2
                                   {::sut/committer-step #'failing-committer-step
                                    ::sut/report-loss #'probed-loss-report})]
    (try
      (async/>!! (:error-chan started) (synthetic-core-fault 0))
      (let [escaped
            (test-support/await-event!
             losses ::committer-last-resort
             #(= "committer wrapper failed"
                 (ex-message (::flow/ex %))))]
        (is (= ::sut/fault-committer (::flow/pid escaped)))
        (is (= :step (::flow/op escaped))))
      (finally
        (reset! committer-losses nil)
        (sut/stop-error-fanout! fanout (join-bound 5000))
        (stop-source-testbed! testbed)
        (async/close! losses)))))))

(deftest core-fault-signatures-bound-durable-and-stderr-output
  (let [commit-core-fault!
        fault/record!
        emit-core-fault!
        (var-get (ns-resolve 'seon.cluster 'emit-core-fault!))
        committer-step
        (var-get (ns-resolve 'seon.flow 'fault-committer-step))
        inline-ceiling 96
        caps (assoc (config/result-caps
                     (test-support/effective-config))
                    :seon.config.eval.result/max-depth 8
                    :seon.config.eval.result/max-collection 8
                    :seon.config.eval.result/max-string 64
                    :seon.config.eval.result/max-source 1048576
                    :seon.config.eval.result/max-nodes 64)
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
        configure!
        (fn [connection]
          (config/apply!
           {:seon.db/connection connection
            :seon.boot/cluster-name "fault-test"
            :seon.config/manifest
            {:seon.config.error/recurrence-limit 3
             :seon.config.error/max-evidence-bytes inline-ceiling
             :seon.config.eval.result/blob-threshold inline-ceiling}}))]
    (testing "132 equal faults produce one error with count 132 and one stderr face"
      (test-support/with-database
        (fn [connection]
          (configure! connection)
          (let [initial-state
                  (committer-step
                   {::sut/fault-channel (async/chan 1)
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
                          [?error :seon.error/occurrences ?occurrence]
                          [?occurrence :seon.error.occurrence/message ?message]
                          [?occurrence :seon.error/capped? ?capped?]
                          [?occurrence :seon.error/data-edn ?data-edn]]
                        @connection)
                  [_error signature message capped? data-edn] (first facts)
                  recurrence
                  (db/q '[:find (sum ?count) . :with ?occurrence
                          :in $ ?signature ?process
                          :where
                          [?error :seon.error/signature ?signature]
                          [?error :seon.error/occurrences ?occurrence]
                          [?occurrence :seon.error/process ?process]
                          [?occurrence :seon.error.occurrence/count ?count]]
                        @connection signature "process-1")
                  lines (str/split-lines (str stderr-writer))]
              (is (= 1 (count facts))
                  "every equal delivery updates the same occurrence")
              (is (= 132 recurrence)
                  "recurrence sums the occurrence counts")
              (is (= 132 (::sut/committed final-state)))
              (is (= 1 (::sut/panicked final-state)))
              (is (= 1 (count (::sut/seen-signatures final-state))))
              (is (= :over-bound (:seon.sci.admit/reason (edn/read-string message))))
              (is (<= (count repeated-message)
                      (:seon.sci.admit/bytes (edn/read-string message))))
              (is (true? capped?))
              (is (< (count data-edn) 10000)
                  "the normalizer caps the retained stack and ex-data")
              (is (= 1 (count lines))
                  "all 132 equal envelopes own one emitted face")
              (is (str/includes? (first lines) message)
                  "the stderr sink receives the whole normalized message")))))

    (testing "a signature stored by an earlier JVM prints again in this one"
      (test-support/with-database
        (fn [connection]
          (configure! connection)
          (let [[durable-fact durable-outcome _]
                  (commit-core-fault! connection "fault-test" "process-3"
                                      caps {:seon.error/source repeated-fault
                                            :seon.error/declared-schema :seon.flow/exception-error})
                  initial-state
                  (committer-step
                   {::sut/fault-channel (async/chan 1)
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
              (is (seq duplicate-stderr)
                  "a durable signature is no reason for this JVM to stay silent")
              (is (contains? signature-set
                             (:seon.error/signature durable-fact)))
              (is (= 2 (count signature-set)))
              (is (= 2 fact-count)
                  "the rebuilt proc commits the repeated occurrence too")
              (is (= signature-set (::sut/seen-signatures final-state)))
              (is (= 2 (::sut/committed final-state)))
              (is (= 2 (::sut/panicked final-state)))
              (is (= 2 (count lines)) "each signature prints once in this JVM")
              (is (str/includes?
                   (second lines)
                   (db/q '[:find ?message .
                           :where
                           [?error :seon.error/throwable-class "java.lang.IllegalStateException"]
                           [?error :seon.error.occurrence/message ?message]]
                         @connection))))))

    (testing "a failed write panics every time, an already-seen signature included"
      (let [attempts (atom 0)
            emitted (atom [])
            writer-refusal {:seon.error/message "Writer is shut down; release and reconnect."}
            initial-state
            (committer-step
             {::sut/fault-channel (async/chan 1)
              ::sut/read-core-error-mode (constantly :record)
              ::sut/commit-fault!
              (fn [{fault :seon.error/source}]
                (swap! attempts inc)
                [{:seon.error/signature (.getName (class (::flow/ex fault)))
                  :seon.error/message (ex-message (::flow/ex fault))}
                 writer-refusal nil])
              ::sut/panic! #(swap! emitted conj (::sut/fault-fact %))})
            final-state
            (reduce (fn [state fault] (first (committer-step state ::sut/core-fault fault)))
                    initial-state
                    [repeated-fault repeated-fault distinct-fault])]
        (is (= 3 @attempts) "every occurrence reaches the writer")
        (is (= 3 (count @emitted) (::sut/panicked final-state))
            "the repeated signature's second failed write still panics")
        (is (zero? (::sut/committed final-state)))
        (is (= 2 (count (::sut/seen-signatures final-state)))))))))

(deftest stopping-the-fanout-awaits-an-active-fault-commit
  (let [{::keys [graph started] :as testbed} (source-testbed)
        commit-entered (CountDownLatch. 1)
        finish-commit (CountDownLatch. 1)
        fanout
        (sut/start-error-fanout!
         {:seon.env/environment @test-environment
          ::sut/projection (schema/current-projection)
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
      (let [stopped (future (sut/stop-error-fanout! fanout (join-bound 5000)))]
        (is (false? (realized? stopped))
            "the fanout keeps its database dependency while commit is active")
        (is (not (.isTerminated ^java.util.concurrent.ExecutorService (::sut/executor fanout))))
        (.countDown finish-commit)
        (is (nil? (test-support/await-event! stopped ::fanout-stopped))
            "the fault graph exits after the commit returns"))
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
             ::sut/tag {:seon.agent/id (str "agent-" ordinal)}}))
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
               (into #{} (map :seon.agent/id) faults))
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
              (sut/stop-error-fanout! fanout (join-bound 5000))
              (stop-source-testbed! testbed))))))))

(deftest fault-tap-overflow-commits-a-queryable-drop-fact
  (testing "faults beyond the bounded tap become one queryable drop batch"
    (test-support/with-database
      (fn [connection]
        (let [commit-core-fault!
              fault/record!
              fault-buffer-capacity 2
              fault-count 5
              caps (assoc (config/result-caps
                           (test-support/effective-config))
                          :seon.config.eval.result/max-depth 8
                          :seon.config.eval.result/max-collection 8
                          :seon.config.eval.result/max-string 64
                          :seon.config.eval.result/max-source 1048576
                          :seon.config.eval.result/max-nodes 64)
              _ (config/apply!
                 {:seon.db/connection connection
                  :seon.boot/cluster-name "fault-test"
                  :seon.config/manifest
                  {:seon.config.error/recurrence-limit 3
                   :seon.config.error/max-evidence-bytes 256
                   :seon.config.eval.result/blob-threshold 256}})
              {::keys [graph started] :as testbed} (source-testbed)
              fanout
              (sut/start-error-fanout!
               {:seon.env/environment (test-support/environment "fault-test" connection)
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
                    (db/q '[:find (sum ?count) . :with ?occurrence
                            :where [?occurrence :seon.error.occurrence/count ?count]]
                          (:db-after %))))
              (is (= 3 (committed-overflow-count @connection)))
              (let [[drop-count digest proc message]
                    (first (committed-overflows @connection))]
                (is (= 3 drop-count))
                (is (= 64 (count digest)))
                (is (= :source proc))
                (is (= :over-bound (:seon.sci.admit/reason (edn/read-string message))))
                (is (pos? (:seon.sci.admit/bytes (edn/read-string message)))))
            (finally
              (stop-database-events! connection transactions)
              (sut/stop-error-fanout! fanout (join-bound 5000))
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
        (test-support/await-event!
         (-> client
             .newWebSocketBuilder
             (.buildAsync
              (URI/create (str "ws://127.0.0.1:" port "/flow-socket"))
              listener))
         ::monitor-socket-open)]
    [socket complete-messages initial-and-ping]))

(deftest flow-monitor-attaches-and-publishes-the-render-graph
  (with-open [launcher
              (test-support/closeable
               (install-test-work-launcher!
                {::sut/configuration test-launcher-configuration})
               (fn [_] (stop-test-work-launcher!)))
              client
              (test-support/closeable
               (HttpClient/newHttpClient)
               (fn [^HttpClient value]
                 (.shutdownNow value)
                 (when-not (.awaitTermination
                            value (java.time.Duration/ofSeconds
                                   test-support/event-backstop-seconds))
                   (throw (ex-info "Monitor HTTP client did not terminate" {})))))
              fanout
              (test-support/closeable
               (sut/start-error-fanout!
                {:seon.env/environment @test-environment
                 ::sut/projection (schema/current-projection)
                 ::sut/graph (::sut/graph @launcher)
                 ::sut/started (::sut/started @launcher)
                 ::sut/fault-buffer-capacity 8
                 ::sut/monitor-buffer-capacity 8
                 ::sut/read-core-error-mode (constantly :record)
                 ::sut/commit-fault! (fn [_])
                 ::sut/commit-drop! (fn [_])
                 ::sut/panic! (fn [_])})
               #(sut/stop-error-fanout! % (join-bound 5000)))
              monitor
              (test-support/closeable
               (flow-monitor/start-server {:flow (::sut/graph @fanout) :port 0})
               flow-monitor/stop-server)]
    (let [port (:port @@monitor)
          request
          (-> (HttpRequest/newBuilder)
              (.uri (URI/create (str "http://127.0.0.1:" port "/index.html")))
              (.timeout (java.time.Duration/ofSeconds
                         test-support/event-backstop-seconds))
              .GET
              .build)
          response (.send ^HttpClient @client request (HttpResponse$BodyHandlers/ofString))
          [socket messages ready] (monitor-websocket-messages @client port)]
      (with-open [connection (test-support/closeable socket #(.abort ^WebSocket %))]
        (test-support/await-event! ready ::monitor-datafy-and-ping)
        (let [graph-message (first @messages)]
          (is (= 200 (.statusCode response)))
          (is (str/includes? (.body response) "<title>Flow Monitor</title>"))
          (is (str/includes? graph-message "~:datafy"))
          (doseq [pid ["~:seon.flow/work-launcher"]]
            (is (str/includes? graph-message pid)
                (str "monitor graph data names " pid)))
          (test-support/await-event!
           (.sendClose ^WebSocket @connection WebSocket/NORMAL_CLOSURE "test complete")
           ::monitor-socket-closed))))))
