(ns seon.host-hostile-battery-writer-test
  "Permanent live containment battery for the JVM SCI agent host."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [sci.core :as sci]
            [seon.ai.tokens :as tokens]
            [seon.db.host :as db.host]
            [seon.db.id :as db.id]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.db.writer-test-support :as writer-test]
            [seon.db.writer :as writer]
            [seon.error.sci :as error.sci]
            [seon.host :as host]
            [seon.host.context :as context]
            [seon.host.eval :as host.eval]
            [seon.host-registry-writer-test :as registry-test])
  (:import [java.io ByteArrayOutputStream DataOutputStream File OutputStream
            PrintStream]
           [java.nio.channels Channels SocketChannel]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private artifact-digest (apply str (repeat 64 "7")))
(def ^:private eval-threads 4)
(def ^:private controls (atom {}))

(def ^:private ^:dynamic *host-socket* nil)
(def ^:private ^:dynamic *writer-session* nil)
(def ^:private ^:dynamic *database-name* nil)
(def ^:private ^:dynamic *host* nil)

(defn- registry-value [sym]
  (var-get (ns-resolve 'seon.host-registry-writer-test sym)))

(defn- socket-path [label]
  ((registry-value 'socket-path) label))

(defn- host-private [sym]
  (ns-resolve 'seon.host sym))

(defn- await-release-uninterruptibly! [release]
  (loop []
    (when-not (realized? release)
      (try
        (deref release 25 false)
        (catch InterruptedException _))
      (recur))))

(defn- install-control-wrappers! [started]
  (context/register-wrappers!
   {::context/registry (::context/registry (::host/base started))
    ::context/lib 'seon.host.hostile-battery
    ::context/wrappers
    {'block
     {::context/wrapper-fn
      (fn [label]
        (let [{::keys [entered release]} (get @controls label)]
          (deliver entered true)
          (await-release-uninterruptibly! release)
          :released))
      ::context/arglists '([label])
      ::context/doc "Block until the hostile-battery test releases this call."}}}))

(defn- raw-session! []
  (let [^SocketChannel channel (uds/connect! *host-socket*)]
    {::channel channel
     ::output (Channels/newOutputStream channel)
     ::input (Channels/newInputStream channel)}))

(defn- startup-value
  ([agent-id] (startup-value agent-id *database-name*))
  ([agent-id database-name]
   {:seon.execution/protocol-version 3
    :seon.execution/agent-id agent-id
    :seon.execution/artifact-digest artifact-digest
    :seon.execution/shadow-build-id "host-hostile-battery-writer-test"
    :seon.execution/database-selection
    {:seon.db/socket-path "unused-by-the-host"
     :seon.db/database-name database-name}}))

(defn- open-session! [agent-id]
  (let [session (raw-session!)]
    (uds/write-frame! (::output session) (startup-value agent-id))
    (assoc session ::ready (uds/read-frame (::input session)))))

(defn- close-session! [session]
  (try (.close ^SocketChannel (::channel session)) (catch Throwable _ nil)))

(defn- form [source]
  {:seon.repl/kind :form :seon.repl/source source})

(defn- invocation-value
  [{:keys [agent-id invocation-id sources turn-id duration-ms database]
    :or {duration-ms 30000}}]
  {:seon.execution/message :seon.execution.message/invoke
   :seon.execution/protocol-version 3
   :seon.execution/agent-id agent-id
   :seon.execution/invocation-id invocation-id
   :seon.db/db (or database (context/resolve-head! *writer-session*))
   :seon.execution/function-identity
   {:seon.execution/function-symbol 'seon.execution.runtime/eval-batch!
    :seon.execution/artifact-digest artifact-digest}
   :seon.execution/arguments
   [(cond-> {:seon.eval/parsed (mapv form sources)
             :seon.eval/starting-ns (symbol (str "my.agent." agent-id))}
      turn-id (assoc :seon.agent.turn/id-of-turn turn-id))]
   :seon.execution/deadline-ms (+ (System/currentTimeMillis) duration-ms)
   :seon.execution/result-limit-bytes 1000000})

(defn- send-invoke! [session request]
  (uds/write-frame! (::output session) (invocation-value request)))

(defn- receive! [session]
  (uds/read-frame (::input session)))

(defn- invoke! [session request]
  (send-invoke! session request)
  (receive! session))

(defn- send-cancel! [session invocation-id]
  (uds/write-frame!
   (::output session)
   {:seon.execution/message :seon.execution.message/cancel
    :seon.execution/protocol-version 3
    :seon.execution/invocation-id invocation-id}))

(defn- eval-result [response]
  (get-in response [:seon.execution/result :seon.host/results 0]))

(defn- eval-value [response]
  (:seon.eval/value (eval-result response)))

(defn- eval-error [response]
  (or (:seon.execution/error response)
      (:seon/error (eval-result response))))

(defn- sci-class [error]
  (get-in error [:seon.error/data :seon.error.sci/class]))

(defn- assert-steering-error! [response expected-class]
  (let [error (eval-error response)
        head (:seon.error/message error)]
    (is (map? error) (pr-str response))
    (is (= expected-class (sci-class error)) (pr-str error))
    (is (and (string? head) (not (str/blank? head))) (pr-str error))
    (is (<= (tokens/estimate head) error.sci/default-error-head-token-cap)
        (pr-str error))
    error))

(defn- allocate-identities! [turn?]
  (let [policies
        (cond-> {:seon.agent/id :seon.db.id.generator/human-readable}
          turn? (assoc :seon.agent.turn/id :seon.db.id.generator/compact))
        allocations
        (cond-> [{:seon.db.id/key :fixture/agent
                  :seon.db.id/identity-attr :seon.agent/id}]
          turn? (conj {:seon.db.id/key :fixture/turn
                       :seon.db.id/identity-attr :seon.agent.turn/id}))
        candidates (db.id/candidate-manifest policies allocations)
        agent-id (:seon.db.id/value (first candidates))
        turn-id (some-> (second candidates) :seon.db.id/value)
        database (db.host/resolve-db! *writer-session* nil false)
        result
        (db.host/call!
         *writer-session*
         (protocol/transaction-request
          {::protocol/request-id (str (random-uuid))
           :seon.db/db database
           ::protocol/transaction-data
           (cond-> [{:seon.agent/id agent-id}]
             turn? (conj {:seon.agent.turn/id turn-id}))
           ::protocol/generated-candidates candidates}))]
    (is (true? (::protocol/success? result)) (pr-str result))
    (if turn? [agent-id turn-id] agent-id)))

(defn- turn-eval-count [turn-id]
  (count
   (context/query-writer!
    *writer-session*
    '[:find [?eval ...]
      :in $ ?turn-id
      :where
      [?turn :seon.agent.turn/id ?turn-id]
      [?turn :seon.agent.turn/evals ?eval]]
    [turn-id])))

(defn- sentinel-count [sentinel]
  (count
   (context/query-writer!
    *writer-session*
    '[:find [?entity ...]
      :in $ ?sentinel
      :where
      [?entity :seon.host-hostile-battery-writer-test/sentinel ?sentinel]]
    [sentinel])))

(defn- turn-outputs [turn-id]
  (context/query-writer!
   *writer-session*
   '[:find [?output ...]
     :in $ ?turn-id
     :where
     [?turn :seon.agent.turn/id ?turn-id]
     [?turn :seon.agent.turn/evals ?eval]
     [?eval :seon.eval/output ?output]]
   [turn-id]))

(defn- turn-statuses [turn-id]
  (context/query-writer!
   *writer-session*
   '[:find [?status ...]
     :in $ ?turn-id
     :where
     [?turn :seon.agent.turn/id ?turn-id]
     [?turn :seon.agent.turn/evals ?eval]
     [?eval :seon.eval/status ?status]]
   [turn-id]))

(defn- wait-for! [p label]
  (is (= true (deref p 5000 ::timed-out))
      (str "timed out waiting for " label)))

(defn- assert-ready! [session]
  (is (= :seon.execution.message/ready
         (:seon.execution/message (::ready session)))
      (pr-str (::ready session))))

(defn- assert-success! [session agent-id source]
  (let [response (invoke!
                  session
                  {:agent-id agent-id
                   :invocation-id (str "survivor-" (random-uuid))
                   :sources [source]
                   :duration-ms 5000})]
    (is (= :seon.execution.message/result
           (:seon.execution/message response))
        (pr-str response))
    response))

(defn- assert-survivor! [session agent-id]
  (is (= 42 (eval-value (assert-success! session agent-id "(+ 20 22)")))))

(use-fixtures
  :once
  (fn [run-tests!]
    (let [database-name (str "host-hostile-battery-" (random-uuid))
          request-path (socket-path "writer")
          host-socket (socket-path "host")
          pool-defaults (assoc db.host/defaults
                               ::db.host/pool-size 2
                               ::db.host/pool-wait-timeout-ms 100)]
      (with-redefs-fn
        {#'db.host/defaults pool-defaults}
        (fn []
          (let [server
                (writer-test/start!
                 {::writer/dependencies ((registry-value 'dependencies))
                  ::writer/database-name database-name
                  ::writer/backend :memory
                  ::writer/selected-processors 3
                  ::writer/request-socket-path request-path})
                writer-session
                (context/writer-session
                 {::context/writer-socket-path request-path
                  ::context/database-name database-name
                  ::context/backend :memory})]
            (try
              (let [seeded
                    (writer-test/seed-canonical-schema!
                     writer-session
                     database-name
                     [(registry-value 'value-sampling-policy)
                      {:seon.user/id "user"}
                      {:seon.db.process/id :seon.db.process/repl}])]
                (is (true? (::protocol/success? seeded)) (pr-str seeded)))
              (let [declared
                    (context/transact-writer!
                     writer-session
                     [{:seon.schema/key
                       :seon.host-hostile-battery-writer-test/sentinel
                       :seon.schema/form ":string"}])]
                (is (:seon.db/ok? declared) (pr-str declared)))
              (let [installed
                    (sci/eval-string*
                     (context/fork-context
                      (context/build-base! writer-session))
                     (str "(require 'seon.db)"
                          "(seon.db/transact!"
                          " {:seon.db/tx-data"
                          "  [{:seon.db/user [:seon.user/id \"user\"]"
                          "    :seon.db/process"
                          "    [:seon.db.process/id :seon.db.process/repl]"
                          "    :seon.host-hostile-battery-writer-test/sentinel"
                          "    \"schema-install-probe\"}"
                          "   {:seon.fn/sym \"hostile/install-probe\""
                          "    :seon.fn/spec \"[:=> [:cat :int] :int]\""
                          "    :seon.fn/schema-error \"none\""
                          "    :seon.fn/read-attrs [:hostile/probe]}]})"))]
                (is (map? (:db-after installed)) (pr-str installed)))
              (let [started
                    (host/start!
                     {::host/socket-path host-socket
                      ::host/eval-threads eval-threads
                      ::context/writer-socket-path request-path
                      ::context/database-name database-name
                      ::context/backend :memory})]
                (try
                  (install-control-wrappers! started)
                  (binding [*host-socket* host-socket
                            *writer-session* writer-session
                            *database-name* database-name
                            *host* started]
                    (run-tests!))
                  (finally
                    (host/stop! started))))
              (finally
                (reset! controls {})
                (context/close-session! writer-session)
                (writer/stop! server)
                (.delete (File. ^String request-path))
                (.delete (File. ^String host-socket))))))))))

(deftest cpu-runaways-interrupt-and-both-sessions-recover
  (let [attacker-id "battery-cpu-attacker"
        survivor-id "battery-cpu-survivor"
        attacker (open-session! attacker-id)
        survivor (open-session! survivor-id)]
    (try
      (assert-ready! attacker)
      (assert-ready! survivor)
      (doseq [source ["(reduce + (range))" "(loop [] (recur))"]]
        (let [started (System/nanoTime)
              response
              (invoke! attacker
                       {:agent-id attacker-id
                        :invocation-id (str "cpu-" (random-uuid))
                        :sources [source]
                        :duration-ms 100})
              elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
          (is (= :seon.execution.message/error
                 (:seon.execution/message response))
              (pr-str response))
          (assert-steering-error! response :interrupt)
          (is (<= elapsed-ms 500.0) (str source " took " elapsed-ms "ms"))
          (assert-survivor! survivor survivor-id)
          (assert-survivor! attacker attacker-id)))
      (finally
        (close-session! attacker)
        (close-session! survivor)))))

(deftest bounded-allocation-pressure-leaves-the-other-agent-serving
  (let [attacker-id "battery-memory-attacker"
        survivor-id "battery-memory-survivor"
        attacker (open-session! attacker-id)
        survivor (open-session! survivor-id)]
    (try
      (assert-ready! attacker)
      (assert-ready! survivor)
      (let [allocation
            (future
              (invoke! attacker
                       {:agent-id attacker-id
                        :invocation-id "bounded-allocation"
                        :sources ["(count (vec (range 2000000)))"]
                        :duration-ms 10000}))]
        (assert-survivor! survivor survivor-id)
        (let [response (deref allocation 15000 ::timed-out)]
          (is (not= ::timed-out response))
          (is (= 2000000 (eval-value response)) (pr-str response))))
      (assert-survivor! survivor survivor-id)
      (finally
        (close-session! attacker)
        (close-session! survivor)))))

(deftest oversized-error-frame-is-bounded-and-both-sessions-survive
  (let [attacker-id "battery-oversize-attacker"
        survivor-id "battery-oversize-survivor"
        attacker (open-session! attacker-id)
        survivor (open-session! survivor-id)]
    (try
      (let [response
            (invoke! attacker
                     {:agent-id attacker-id
                      :invocation-id "oversized-error"
                      :sources
                      ["(throw (ex-info (apply str (repeat 1100000 \"xxxx\")) {}))"]
                      :duration-ms 10000})]
        (assert-steering-error! response :runtime)
        (is (< (alength (uds/encode response)) protocol/maximum-frame-bytes)))
      (assert-survivor! attacker attacker-id)
      (assert-survivor! survivor survivor-id)
      (finally
        (close-session! attacker)
        (close-session! survivor)))))

(deftest print-flood-is-loud-persisted-and-absent-from-host-stdout
  (let [[attacker-id turn-id] (allocate-identities! true)
        survivor-id "battery-print-survivor"
        attacker (open-session! attacker-id)
        survivor (open-session! survivor-id)
        host-bytes (ByteArrayOutputStream.)
        original-out System/out]
    (try
      (System/setOut (PrintStream. host-bytes true "UTF-8"))
      (let [response
            (invoke! attacker
                     {:agent-id attacker-id
                      :invocation-id "print-flood"
                      :turn-id turn-id
                      :sources ["(do (dotimes [_ 20000] (print \"flood\")) :done)"]
                      :duration-ms 10000})]
        (is (= :seon.execution.message/error
               (:seon.execution/message response))
            (pr-str response))
        (is (= :seon.config.guard/output-cap
               (get-in response [:seon.execution/error :seon.error/data
                                 :seon.host.guard/config-key]))
            (pr-str response)))
      (System/setOut original-out)
      (let [output (first (turn-outputs turn-id))]
        (is (string? output))
        (is (str/includes? output "truncated"))
        (is (<= (count output) 16384)))
      (is (zero? (.size host-bytes)))
      (assert-survivor! survivor survivor-id)
      (finally
        (System/setOut original-out)
        (close-session! attacker)
        (close-session! survivor)))))

(deftest concurrent-sessions-keep-output-attributed-to-own-eval-row
  (let [[left-id left-turn] (allocate-identities! true)
        [right-id right-turn] (allocate-identities! true)
        left-label :output-left
        right-label :output-right
        left-entered (promise)
        right-entered (promise)
        left-release (promise)
        right-release (promise)
        left (open-session! left-id)
        right (open-session! right-id)]
    (swap! controls assoc
           left-label {::entered left-entered ::release left-release}
           right-label {::entered right-entered ::release right-release})
    (try
      (assert-ready! left)
      (assert-ready! right)
      (send-invoke!
       left
       {:agent-id left-id
        :invocation-id "concurrent-output-left"
        :turn-id left-turn
        :sources
        ["(do (require 'seon.host.hostile-battery) (print \"LEFT-BEGIN\") (seon.host.hostile-battery/block :output-left) (print \"LEFT-END\") :left)"]})
      (send-invoke!
       right
       {:agent-id right-id
        :invocation-id "concurrent-output-right"
        :turn-id right-turn
        :sources
        ["(do (require 'seon.host.hostile-battery) (print \"RIGHT-BEGIN\") (seon.host.hostile-battery/block :output-right) (print \"RIGHT-END\") :right)"]})
      (wait-for! left-entered "left output capture")
      (wait-for! right-entered "right output capture")
      (deliver left-release true)
      (deliver right-release true)
      (let [left-response (receive! left)
            right-response (receive! right)
            left-output (first (turn-outputs left-turn))
            right-output (first (turn-outputs right-turn))]
        (is (= :left (eval-value left-response)) (pr-str left-response))
        (is (= :right (eval-value right-response)) (pr-str right-response))
        (is (= 1 (turn-eval-count left-turn)))
        (is (= 1 (turn-eval-count right-turn)))
        (is (str/includes? left-output "LEFT-BEGIN"))
        (is (str/includes? left-output "LEFT-END"))
        (is (not (str/includes? left-output "RIGHT")))
        (is (str/includes? right-output "RIGHT-BEGIN"))
        (is (str/includes? right-output "RIGHT-END"))
        (is (not (str/includes? right-output "LEFT"))))
      (finally
        (deliver left-release true)
        (deliver right-release true)
        (swap! controls dissoc left-label right-label)
        (close-session! left)
        (close-session! right)))))

(deftest shared-built-in-vars-refuse-root-mutation-and-bystander-stays-correct
  (let [attacker-id "battery-var-attacker"
        survivor-id "battery-var-survivor"
        attacker (open-session! attacker-id)
        survivor (open-session! survivor-id)]
    (try
      (let [before (eval-value
                    (assert-success!
                     survivor survivor-id
                     "(seon.ai.tokens/estimate-chars 20)"))
            def-response
            (invoke! attacker
                     {:agent-id attacker-id
                      :invocation-id "built-in-def"
                      :sources
                      ["(in-ns 'seon.ai.tokens) (defn estimate-chars [& _] :evil)"]})]
        (assert-steering-error! def-response :refusal)
        (is (= before
               (eval-value
                (assert-success!
                 survivor survivor-id
                 "(seon.ai.tokens/estimate-chars 20)")))))
      (let [alter-response
            (invoke! attacker
                     {:agent-id attacker-id
                      :invocation-id "built-in-alter"
                      :sources
                      ["(alter-var-root #'clojure.core/reduce (constantly nil))"]})]
        (assert-steering-error! alter-response :refusal)
        (is (= 6 (eval-value
                  (assert-success! survivor survivor-id
                                   "(reduce + [1 2 3])")))))
      (finally
        (close-session! attacker)
        (close-session! survivor)))))

(deftest queued-cancel-leaves-zero-receipts-and-writes
  (let [busy-sessions
        (mapv #(open-session! (str "battery-cancel-busy-" %))
              (range eval-threads))
        [target-id target-turn] (allocate-identities! true)
        invocation-id "battery-cancel-queued"
        target (open-session! target-id)
        sentinel "battery-cancel-ghost"
        blockers
        (mapv (fn [index]
                (let [label (str "cancel-blocker-" index)
                      entered (promise)
                      release (promise)]
                  (swap! controls assoc label
                         {::entered entered ::release release})
                  {:label label :entered entered :release release}))
              (range eval-threads))]
    (try
      (doseq [[index session] (map-indexed vector busy-sessions)]
        (send-invoke!
         session
         {:agent-id (str "battery-cancel-busy-" index)
          :invocation-id (str "cancel-busy-" index)
          :sources
          ["(require 'seon.host.hostile-battery)"
           (str "(seon.host.hostile-battery/block \"cancel-blocker-"
                index "\")")]}))
      (doseq [{:keys [entered]} blockers]
        (wait-for! entered "cancel pool saturator"))
      (send-invoke!
       target
       {:agent-id target-id
        :invocation-id invocation-id
        :turn-id target-turn
        :sources
        [(str "(seon.db/transact! {:seon.db/tx-data "
              "[{:seon.host-hostile-battery-writer-test/sentinel \""
              sentinel "\"}]})")
         "(def canceled-before-start 41)"]})
      (send-cancel! target invocation-id)
      (let [response (receive! target)]
        (is (= :seon.execution.message/error
               (:seon.execution/message response)))
        (is (= :agent (get-in response [:seon.execution/error
                                        :seon.error/kind]))))
      (doseq [{:keys [release]} blockers] (deliver release true))
      (doseq [session busy-sessions]
        (is (= :seon.execution.message/result
               (:seon.execution/message (receive! session)))))
      (is (zero? (turn-eval-count target-turn)))
      (is (zero? (sentinel-count sentinel)))
      (finally
        (doseq [{:keys [label release]} blockers]
          (deliver release true)
          (swap! controls dissoc label))
        (doseq [session busy-sessions] (close-session! session))
        (close-session! target)))))

(deftest deadline-at-return-records-interrupt-and-leaves-next-form-clean
  (let [[attacker-id turn-id] (allocate-identities! true)
        survivor-id "battery-late-survivor"
        attacker (open-session! attacker-id)
        survivor (open-session! survivor-id)
        original (var-get #'host.eval/finish-evaluation!)
        fired? (atom false)]
    (try
      (let [response
            (with-redefs-fn
              {#'host.eval/finish-evaluation!
               (fn [session envelope]
                 (when (compare-and-set! fired? false true)
                   (.interrupt (Thread/currentThread)))
                 (original session envelope))}
              #(invoke! attacker
                        {:agent-id attacker-id
                         :invocation-id "deadline-at-return"
                         :turn-id turn-id
                         :sources ["(+ 1 1)"]}))]
        (assert-steering-error! response :interrupt))
      (is (some #{:interrupted} (turn-statuses turn-id)))
      (assert-survivor! attacker attacker-id)
      (assert-survivor! survivor survivor-id)
      (finally
        (close-session! attacker)
        (close-session! survivor)))))

(deftest connect-and-silence-times-out-without-ending-acceptance
  (let [silent
        (with-redefs-fn
          {(host-private 'startup-read-timeout-ms) 50}
          (fn []
            (let [session (raw-session!)]
              (is (nil? (receive! session)))
              session)))
        survivor (open-session! "battery-silent-survivor")]
    (try
      (assert-ready! survivor)
      (assert-survivor! survivor "battery-silent-survivor")
      (finally
        (close-session! silent)
        (close-session! survivor)))))

(deftest malformed-and-foreign-startups-do-not-harm-other-sessions
  (let [garbage (raw-session!)
        truncated (raw-session!)]
    (try
      (let [raw (DataOutputStream. ^OutputStream (::output garbage))]
        (.writeInt raw -1)
        (.flush raw)
        (is (nil? (receive! garbage))))
      (let [raw (DataOutputStream. ^OutputStream (::output truncated))]
        (.writeInt raw 10)
        (.write raw (byte-array [1 2]))
        (.flush raw)
        (.close ^SocketChannel (::channel truncated)))
      (let [foreign (raw-session!)]
        (try
          (uds/write-frame! (::output foreign)
                            (startup-value "battery-foreign" "another-database"))
          (let [response (receive! foreign)]
            (is (= :seon.execution.message/error
                   (:seon.execution/message response)))
            (is (= :core-bug
                   (get-in response [:seon.execution/error :seon.error/kind]))))
          (finally
            (close-session! foreign))))
      (let [survivor (open-session! "battery-malformed-survivor")]
        (try
          (assert-ready! survivor)
          (assert-survivor! survivor "battery-malformed-survivor")
          (finally
            (close-session! survivor))))
      (finally
        (close-session! garbage)
        (close-session! truncated)))))

(deftest concurrent-failed-form-does-not-drop-anothers-schema
  ;; audit-host-robustness-2026-07-21.md §2c / ranked gap 7.
  ;; This known failure is the W0.8 trigger: A's failed form restores its
  ;; pre-form process-global snapshot and drops B's successful registration.
  (let [agent-a "battery-schema-a"
        agent-b "battery-schema-b"
        survivor-id "battery-schema-survivor"
        session-a (open-session! agent-a)
        session-b (open-session! agent-b)
        survivor (open-session! survivor-id)
        entered (promise)
        release (promise)
        label "schema-stale-restore"]
    (swap! controls assoc label {::entered entered ::release release})
    (try
      (let [failed
            (future
              (invoke! session-a
                       {:agent-id agent-a
                        :invocation-id "schema-a-fails"
                        :sources
                        [(str "(do (require 'seon.host.hostile-battery)"
                              " (seon.host.hostile-battery/block \"" label
                              "\") (schema/register! :battery.schema/a :string)"
                              " (throw (ex-info \"rollback\" {})))")]}))]
        (wait-for! entered "schema A stale snapshot")
        (let [registered
              (invoke! session-b
                       {:agent-id agent-b
                        :invocation-id "schema-b-succeeds"
                        :sources
                        ["(schema/register! :battery.schema/b :string)"]})]
          (is (= :battery.schema/b (eval-value registered))
              (pr-str registered)))
        (deliver release true)
        (is (= :runtime (sci-class (eval-error (deref failed 5000 {})))))
        (let [definition
              (eval-value
               (assert-success! survivor survivor-id
                                "(schema/schema-definition :battery.schema/b)"))]
          (is (= :string definition)
              "W0.8: a concurrent failed eval reverts only its own delta, so B's registration survives")))
      (assert-survivor! survivor survivor-id)
      (finally
        (deliver release true)
        (swap! controls dissoc label)
        (close-session! session-a)
        (close-session! session-b)
        (close-session! survivor)))))

(deftest eval-error-storm-does-not-prevent-another-agents-real-work
  (let [attacker-id "battery-error-storm"
        survivor-id (allocate-identities! false)
        attacker (open-session! attacker-id)
        survivor (open-session! survivor-id)
        sentinel "battery-error-storm-survivor"
        attacks [["missing-battery-symbol" :resolution]
                 ["((fn [x] x))" :arity]
                 ["(/ 1 0)" :runtime]]]
    (try
      (let [storm
            (future
              (doall
               (for [round (range 5)
                     [source expected-class] attacks]
                 [expected-class
                  (invoke! attacker
                           {:agent-id attacker-id
                            :invocation-id (str "storm-" round "-"
                                                (name expected-class))
                            :sources [source]})])))
            survivor-response
            (invoke! survivor
                     {:agent-id survivor-id
                      :invocation-id "storm-survivor-work"
                      :sources
                      [(str "(seon.db/transact! {:seon.db/tx-data "
                            "[{:seon.host-hostile-battery-writer-test/sentinel \""
                            sentinel "\"}]})")]})]
        (is (map? (get-in survivor-response
                          [:seon.execution/result :seon.host/results 0
                           :seon.eval/value :db-after]))
            (pr-str survivor-response))
        (is (= 1 (sentinel-count sentinel)))
        (doseq [[expected-class response] (deref storm 15000 [])]
          (assert-steering-error! response expected-class)))
      (assert-survivor! survivor survivor-id)
      (finally
        (close-session! attacker)
        (close-session! survivor)))))

(deftest writer-pool-exhaustion-is-bounded-and-recovers-after-reads-drain
  ;; q5 owns the separate capacity-reservation/fairness question. W0.4's
  ;; containment contract is bounded :pool-exhausted plus later recovery.
  (let [attackers [(open-session! "battery-pool-attacker-1")
                   (open-session! "battery-pool-attacker-2")]
        survivor-id "battery-pool-survivor"
        survivor (open-session! survivor-id)
        head (context/resolve-head! *writer-session*)
        entered (CountDownLatch. 2)
        release (CountDownLatch. 1)
        execute-read! (var-get #'writer/execute-read!)
        saturating-read?
        (fn [work]
          (str/includes?
           (pr-str (get-in work [::writer/request ::protocol/query-form] ""))
           ":db/txInstant"))]
    (try
      (with-redefs-fn
        {#'writer/execute-read!
         (fn [runtime work]
           (when (saturating-read? work)
             (.countDown entered)
             (.await release))
           (execute-read! runtime work))}
        (fn []
          (let [attack-futures
                (mapv
                 (fn [index session]
                   (future
                     (invoke! session
                              {:agent-id (str "battery-pool-attacker-" index)
                               :invocation-id (str "pool-saturator-" index)
                               :database head
                               :sources
                               ["(seon.db/query '[:find ?e :where [?e :db/txInstant]])"]})))
                 [1 2] attackers)]
            (try
              (is (.await entered 5 TimeUnit/SECONDS))
              ;; The W0.4 saturation rejection shape, observed at the same
              ;; exhausted shared pool (host_pool_writer_test.clj:285).
              (let [direct
                    (context/query-writer-at!
                     (::host/writer *host*) head
                     '[:find ?e :where [?e :seon.db.process/id]] [])
                    data (:seon.error/data direct)]
                (is (= :pool-exhausted (::db.host/pool-reason data))
                    (pr-str direct))
                (is (= 2 (get-in data [::db.host/pool
                                       ::db.host/in-flight-members]))
                    (pr-str direct)))
              ;; An invocation during saturation is REJECTED bounded — an
              ;; error frame, never a hang (bounded-rejection, not service).
              (let [rejected
                    (future
                      (invoke! survivor
                               {:agent-id survivor-id
                                :invocation-id "pool-exhausted-survivor"
                                :database head
                                :sources ["(+ 20 22)"]}))
                    response (deref rejected 5000 ::timed-out)]
                (is (not= ::timed-out response))
                (is (= :seon.execution.message/error
                       (:seon.execution/message response))
                    (pr-str response)))
              (finally
                (.countDown release)))
            (doseq [attack attack-futures]
              (is (= :seon.execution.message/result
                     (:seon.execution/message (deref attack 10000 {}))))))))
      (assert-survivor! survivor survivor-id)
      (finally
        (.countDown release)
        (doseq [session attackers] (close-session! session))
        (close-session! survivor)))))
