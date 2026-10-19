(ns seon.flow-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.core.async.flow-monitor :as flow-monitor]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [datahike.api :as d]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [datahike.core :as datahike]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.flow :as sut]
            [seon.profile :as profile]
            [seon.sci.eval :as sci.eval]
            [seon.schema :as schema]
            [seon.schema.handed :as schema.handed]
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

(deftest a-go-loop-started-with-root-bindings-keeps-none-of-the-callers
  (let [sink (atom [])]
    (is (= {:sink nil :caller-sink-after sink}
           (binding [db/*read-evidence-sink* sink]
             {:sink (async/<!! (db/call-with-root-bindings #(async/go db/*read-evidence-sink*)))
              :caller-sink-after db/*read-evidence-sink*})))))

(deftest a-generated-connections-writer-keeps-none-of-the-callers-bindings
  ;; batch-C review 2026-10-07: seon.db/connection-generator connected inside
  ;; the caller's frame, so the writer's go-loop conveyed a sampling
  ;; evaluation's bindings to every later transaction on that connection.
  (let [sink (atom [])
        connection (binding [db/*read-evidence-sink* sink] (gen/generate db/connection-generator))
        seen (promise)]
    (d/transact connection [[:db.fn/call (fn [_] (deliver seen db/*read-evidence-sink*) [])]])
    (is (nil? (deref seen 5000 ::no-transaction)))))

(defn- idle-step
  ([] {:ins {} :outs {}})
  ([args] args)
  ([state _transition] state)
  ([state _input _message] [state nil]))

(deftest a-graph-started-inside-an-evaluation-keeps-none-of-its-bindings
  ;; start-graph! is the graph-side site of call-with-root-bindings: Flow's
  ;; mult and tap go-loops start in the frame its joins run in, so a graph
  ;; opened inside an evaluation's binding never holds that evaluation's sink
  (let [sink (atom [])
        {::sut/keys [graph joins]}
        (binding [db/*read-evidence-sink* sink]
          (sut/start-graph!
           {::sut/graph-definition
            {:procs {::idle {:proc (flow/process #'idle-step {:workload :io})}}
             :conns []}
            ::sut/joins {::frame (fn [_] {:sink db/*read-evidence-sink*})}}))]
    (try
      (is (= {:sink nil} (::frame joins)) "joins, Flow's go-loops' frame, hold no caller binding")
      (is (= [] @sink))
      (finally
        (flow/stop graph)))))

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
        (is (number? (:seon.await/elapsed-ms busy)))
        (is (= ::join-probe-exited (:seon.error/expected busy))
            "the expiry names the exit that did not arrive")
        (is (<= 190 elapsed-ms 450) "the one deadline bounds the join")
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

(deftest a-proc-thread-lives-in-its-projection-world-without-one-armed-call
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "flow-projection-thread")
     (test-support/apply-config! connection "flow-projection-thread" {})
     (let [database (db/db connection)
           state (sci.eval/projection-state database (schema/projection-from-database database)
                                            "flow-projection-thread")
           wrapper (first (filter #(= 'seon.schema/call-with-projection-state (:seon.profile/sym %))
                                  (profile/cells)))
           executor (#'sut/graph-executor {::sut/projection-state state})
           seen (.submit ^java.util.concurrent.ExecutorService executor
                         ^java.util.concurrent.Callable
                         (fn [] {:state @#'schema.handed/*projection-state*
                                 :entered (some-> ^ThreadLocal (:seon.profile/entered wrapper) .get)}))]
       (try
         (is (some? wrapper) "the wrapper is armed, so a counted call would show")
         (let [{:keys [entered] :as observed} (.get seen 5 TimeUnit/SECONDS)]
           (is (identical? state (:state observed)) "the thread runs in the graph's projection state")
           (is (nil? entered) "the thread's life is not a call of the wrapper"))
         (finally
           (.shutdown executor)))))))

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
        (is (nil? exited))
        (is (.isTerminated ^java.util.concurrent.ExecutorService executor)
            "no proc thread of the graph survives the throw")
        (is (= 1 (count (filter #(str/includes? % "gate-2 stop transition throws") stored))))))))

;;; An unstored panic prints bounded text: the class regression for the
;;; 3.18 GB stderr lines of pid 68592 (issue
;;; unstored-panic-prints-whole-throwable-map-to-stderr). Each cause prints
;;; through the value printer's core (`seon.render.value/core-text`, its fixed
;;; budget); the whole value stays on the failure's ex-data.
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
          (is (< 0 (count text) bound))
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
  [:seon.flow/commit-fault!
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
          (is (m/validate output result)))))))

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
                                          {})
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
            ;; equal throws are one signature whose occurrences are capped
            ;; (recurrence-limit), each counted (7781d115c)
            (is (<= 1 (count (db/q '[:find [?occurrence ...]
                                     :where [?occurrence :seon.error.occurrence/message ?message]
                                            [(clojure.string/includes? ?message "work throws")]]
                                   @connection))
                    (:seon.config.error/recurrence-limit (config/effective @connection "seon.flow-test")))
                "equal throws are counted over at most the capped occurrences")
            (is (nil? (async/poll! (:error-chan started)))
                "nothing waits in Flow's error channel")
            (is (< elapsed-ms 2000.0) "fault writes stay within their bound"))
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

(deftest a-recorded-transform-fault-keeps-its-graph-running
  (test-support/with-database
      (fn [connection]
        (test-support/apply-config! connection "seon.flow-test" {})
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
          (test-support/await-event! seen ::third-input #(= [0 1 2] %))
          (is (= [0 1 2] @seen) "a recorded fault keeps the graph running")
          (flow/stop graph)
          (is (nil? (join!)))))))

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
      (test-support/apply-config! connection "seon.flow-test" {})
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
                                                 ;; A PANIC IS A FAULT THAT CANNOT BE STORED: the
                                                 ;; environment names a cluster this database holds no
                                                 ;; configuration for, so the recorder cannot read its
                                                 ;; dials. A recorded fault keeps the graph running
                                                 ;; (the test above); only this path stops it.
                                                 {:seon.env/environment
                                                  (test-support/environment "no-such-flow-cluster" connection)
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
      (is (false? (sut/submit! launcher (submission 2)))
          "a submission past concurrency + queue-depth is refused at submit!, without waiting for work")
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

(deftest ^{:seon.test/long
           "Forcibly terminates a child JVM to cover committed-fact survival across process death."
           :seon/tasks #{'seon.flow/forced-child-jvm-death-fixture-write-is-refused-for-its-projection}}
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
              monitor
              (test-support/closeable
               (flow-monitor/start-server {:flow (::sut/graph @launcher) :port 0})
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
                "monitor graph data names"))
          (test-support/await-event!
           (.sendClose ^WebSocket @connection WebSocket/NORMAL_CLOSURE "test complete")
           ::monitor-socket-closed))))))

(deftest a-proc-thread-is-a-platform-thread
  (let [{::sut/keys [graph executor]} (sut/start-graph! {::sut/graph-definition {:procs {} :conns []}})]
    (try
      (is (false? (.get (.submit ^java.util.concurrent.ExecutorService executor
                                 ^java.util.concurrent.Callable (fn [] (.isVirtual (Thread/currentThread))))
                        5 TimeUnit/SECONDS))
          "a proc's step runs on a platform thread, whose allocation and CPU counters are its own")
      (finally
        (flow/stop graph)
        (.shutdown ^java.util.concurrent.ExecutorService executor)))))

;;; A stop transition runs no evaluation, so it needs no world. Flow reads a
;;; throwing transition as "still running" and re-parks the proc, so a stop
;;; that had to build an unbuildable world never exited and `join-graph!`
;;; waited for its whole backstop (the hang of
;;; `seon.cluster.db-flow-test/every-agent-start-has-a-task`).
(deftest a-work-launcher-stops-though-its-cluster-admits-no-world
  (test-support/with-database
    (fn [connection]
      (let [environment (test-support/environment "never-configured-cluster" connection)
            launcher (sut/start-work-launcher!
                      {:seon.env/environment environment
                       ::sut/configuration (assoc test-launcher-configuration
                                                  :seon.config.agent/turn-completion-backstop-ms 3000)})
            stopped (future (sut/stop-work-launcher! launcher))]
        (is (nil? (deref stopped 10000 ::still-joining))
            "the stop returned its exit, not a timeout")
        (is (.isTerminated ^java.util.concurrent.ExecutorService (::sut/executor launcher))
            "the launcher's proc thread exited")))))
