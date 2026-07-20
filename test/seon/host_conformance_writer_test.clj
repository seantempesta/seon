(ns seon.host-conformance-writer-test
  "Execution-protocol conformance for the JVM agent host (`seon.host`).

   The harness client replays the pod->child message sequences inventoried
   from `seon.execution`/`seon.execution.host`/`seon.execution.runtime`
   over UDS and asserts each response matches the child contract
   shape-for-shape. The writer is a local fake `uds/start-request-server!`
   handler (the same self-contained pattern as `seon.db.transport-uds-test`)
   so the suite needs no live cluster."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.host :as host]
            [seon.host.context :as context])
  (:import [java.io File]
           [java.nio.channels Channels SocketChannel]))

(def ^:private artifact-digest (apply str (repeat 64 "a")))

(def ^:private database
  {:db-name "host-conformance"
   :store-id [#uuid "ca2dd867-e51c-4165-b3b7-430bfe199f2e" :db]
   :t 536870929
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "6a56b426-c836-5817-9f6b-20584f2e81d5"})

(defn- socket-path [label]
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory (str "seon-" label "-" (random-uuid) ".sock")))))

(def ^:private fake-agent-rows
  #{[1 "root"] [2 "task-a"] [3 "task-b"]})

(defn- fake-writer-response [request]
  (let [request-id (::protocol/request-id request)]
    (condp = (::protocol/operation request)
      protocol/resolve-head-operation
      {::protocol/success? true
       ::protocol/request-id request-id
       :seon.db/db database}

      protocol/query-operation
      {::protocol/success? true
       ::protocol/request-id request-id
       :datahike.query/result fake-agent-rows}

      protocol/pull-operation
      {::protocol/success? true
       ::protocol/request-id request-id
       ::protocol/result {:seon.agent/id "root"}}

      protocol/transact-operation
      {::protocol/success? true
       ::protocol/request-id request-id
       :db-before database
       :db-after (update database :t inc)
       :tx-data []
       :tempids {}
       :tx-meta {}}

      {::protocol/success? false
       ::protocol/request-id request-id
       ::protocol/error-kind :seon.db.protocol.error/unsupported
       ::protocol/error "unsupported by the fake writer"})))

(defn- start-fake-writer! [path]
  (uds/start-request-server!
   {::uds/socket-path path
    ::uds/open-connection! (fn [_control] {::connection (random-uuid)})
    ::uds/close-connection! (fn [_owner] nil)
    ::uds/handler
    (fn [_owner request _frame-bytes complete!]
      (complete! (fake-writer-response request)))}))

;;; One host + fake writer per test run; contexts reset per test.

(def ^:private ^:dynamic *host* nil)
(def ^:private ^:dynamic *host-socket* nil)

(use-fixtures :once
  (fn [run-tests!]
    (let [writer-socket (socket-path "host-fake-writer")
      host-socket (socket-path "host-conformance")
          writer (start-fake-writer! writer-socket)
          started (host/start!
                   {::host/socket-path host-socket
                    ::context/writer-socket-path writer-socket
                    ::context/database-name "host-conformance"})]
      (try
        (binding [*host* started
                  *host-socket* host-socket]
          (run-tests!))
        (finally
          (host/stop! started)
          (uds/close-request-server! writer))))))

;;; Harness client — plays the pod side of the inventoried contract.

(defn- session! []
  (let [channel (uds/connect! *host-socket*)]
    {::channel channel
     ::input (Channels/newInputStream ^SocketChannel channel)
     ::output (Channels/newOutputStream ^SocketChannel channel)}))

(defn- send! [session message]
  (uds/write-frame! (::output session) message))

(defn- recv! [session]
  (uds/read-frame (::input session)))

(defn- close! [session]
  (try (.close ^SocketChannel (::channel session)) (catch Throwable _)))

(defn- startup-value
  ([] (startup-value "conformance-agent"))
  ([agent-id]
   {:seon.execution/protocol-version 3
    :seon.execution/agent-id agent-id
    :seon.execution/artifact-digest artifact-digest
    :seon.execution/shadow-build-id "host-conformance-build"
    :seon.execution/database-selection
    {:seon.db/socket-path "unused-by-the-host"
     :seon.db/database-name "host-conformance"
     :seon.db/backend :file
     :seon.db/database-advanced? false}}))

(defn- open-session!
  "Startup handshake; returns [session ready-message]."
  ([] (open-session! "conformance-agent"))
  ([agent-id]
   (let [session (session!)]
     (send! session (startup-value agent-id))
     [session (recv! session)])))

(defn- invoke-value
  [agent-id invocation-id parsed
   & {:keys [deadline-ms result-limit-bytes function-symbol identity-key]
      :or {deadline-ms (+ (System/currentTimeMillis) 30000)
           result-limit-bytes 1000000
           function-symbol 'seon.execution.runtime/eval-batch!
           identity-key :seon.execution/artifact-digest}}]
  {:seon.execution/message :seon.execution.message/invoke
   :seon.execution/protocol-version 3
   :seon.execution/agent-id agent-id
   :seon.execution/invocation-id invocation-id
   :seon.db/db database
   :seon.execution/function-identity
   {:seon.execution/function-symbol function-symbol
    identity-key artifact-digest}
   :seon.execution/arguments
   [{:seon.eval/parsed parsed
     :seon.eval/starting-ns 'my.agent.conformance
     :seon.agent.turn/id-of-turn "turn-conformance"}]
   :seon.execution/deadline-ms deadline-ms
   :seon.execution/result-limit-bytes result-limit-bytes})

(defn- form [source] {:seon.repl/kind :form :seon.repl/source source})

(defn- error-of [message] (get-in message [:seon.execution/error
                                           :seon.error/message]))

;;; Contract: startup handshake

(deftest ready-echoes-startup-identity-and-carries-the-database-value
  (let [[session ready] (open-session! "handshake-agent")]
    (try
      (is (= :seon.execution.message/ready (:seon.execution/message ready)))
      (is (= 3 (:seon.execution/protocol-version ready)))
      (is (= "handshake-agent" (:seon.execution/agent-id ready)))
      (is (= "host-conformance-build" (:seon.execution/shadow-build-id ready)))
      (is (= artifact-digest (:seon.execution/artifact-digest ready)))
      (is (string? (:seon.execution/bun-version ready)))
      (is (= database (:seon.db/db ready)))
      (finally (close! session)))))

(deftest invalid-startup-errors-with-the-startup-invocation-id
  (let [session (session!)]
    (try
      (send! session {:seon.execution/protocol-version 2
                      :seon.execution/agent-id "bad"})
      (let [response (recv! session)]
        (is (= :seon.execution.message/error
               (:seon.execution/message response)))
        (is (= "startup" (:seon.execution/invocation-id response)))
        (is (= :core-bug (get-in response [:seon.execution/error
                                           :seon.error/kind])))
        (is (nil? (recv! session))))
      (finally (close! session)))))

(deftest startup-naming-another-database-is-refused
  (let [session (session!)]
    (try
      (send! session (assoc-in (startup-value)
                               [:seon.execution/database-selection
                                :seon.db/database-name]
                               "another-cluster"))
      (let [response (recv! session)]
        (is (= "startup" (:seon.execution/invocation-id response)))
        (is (= "The startup names another cluster database."
               (error-of response))))
      (finally (close! session)))))

;;; Contract: invoke -> result

(deftest eval-batch-invoke-returns-a-correlated-bounded-result
  (let [[session _ready] (open-session! "eval-agent")]
    (try
      (send! session
             (invoke-value "eval-agent" "invocation-1"
                           [(form "(def working-state (vec (range 10)))")
                            (form "(reduce + working-state)")]))
      (let [response (recv! session)
            result (:seon.execution/result response)]
        (is (= :seon.execution.message/result
               (:seon.execution/message response)))
        (is (= "invocation-1" (:seon.execution/invocation-id response)))
        (is (= database (:seon.db/db response)))
        (is (pos-int? (:seon.execution/result-bytes response)))
        (is (= 2 (:seon.eval/n-ok result)))
        (is (= 0 (:seon.eval/n-fail result)))
        (is (vector? (:seon.eval/ids result)))
        (is (= 45 (get-in result [:seon.host/results 1 :seon.eval/value]))))
      ;; Value defs persist across invocations in one context (B1
      ;; improvement 1) — the second invoke reads the first's def.
      (send! session (invoke-value "eval-agent" "invocation-2"
                                   [(form "(count working-state)")]))
      (is (= 10 (get-in (recv! session)
                        [:seon.execution/result :seon.host/results 0
                         :seon.eval/value])))
      (finally (close! session)))))

(deftest context-reaches-the-writer-through-the-one-db-binding
  (let [[session _ready] (open-session! "db-agent")]
    (try
      (send! session
             (invoke-value
              "db-agent" "invocation-db"
              [(form "(count (seon.db/query '[:find ?e ?id :where [?e :seon.agent/id ?id]]))")
               (form "(:seon.agent/id (seon.db/pull [:seon.agent/id] 1))")]))
      (let [result (:seon.execution/result (recv! session))]
        (is (= 3 (get-in result [:seon.host/results 0 :seon.eval/value])))
        (is (= "root" (get-in result [:seon.host/results 1
                                      :seon.eval/value]))))
      (finally (close! session)))))

(deftest failed-forms-are-error-values-inside-an-ok-result
  (let [[session _ready] (open-session! "error-agent")]
    (try
      (send! session (invoke-value "error-agent" "invocation-err"
                                   [(form "(unresolvable-function 1)")
                                    (form "42")]))
      (let [result (:seon.execution/result (recv! session))]
        (is (= 1 (:seon.eval/n-ok result)))
        (is (= 1 (:seon.eval/n-fail result)))
        (is (false? (get-in result [:seon.host/results 0 :seon.eval/ok?])))
        (is (string? (get-in result [:seon.host/results 0 :seon/error
                                     :seon.error/message])))
        (is (= 42 (get-in result [:seon.host/results 1 :seon.eval/value]))))
      (finally (close! session)))))

;;; Contract: invoke guards

(deftest invoke-naming-another-agent-errors-core-bug
  (let [[session _ready] (open-session! "guard-agent")]
    (try
      (send! session (invoke-value "someone-else" "invocation-wrong" []))
      (let [response (recv! session)]
        (is (= "The invocation names another agent." (error-of response)))
        (is (= :core-bug (get-in response [:seon.execution/error
                                           :seon.error/kind]))))
      (finally (close! session)))))

(deftest elapsed-deadline-errors-agent-kind
  (let [[session _ready] (open-session! "deadline-agent")]
    (try
      (send! session (invoke-value "deadline-agent" "invocation-late" []
                                   :deadline-ms 5))
      (let [response (recv! session)]
        (is (= "The invocation deadline has elapsed." (error-of response)))
        (is (= :agent (get-in response [:seon.execution/error
                                        :seon.error/kind]))))
      (finally (close! session)))))

(deftest second-invoke-while-active-errors-busy
  (let [[session _ready] (open-session! "busy-agent")]
    (try
      (send! session (invoke-value "busy-agent" "invocation-long"
                                   [(form "(loop [i 0] (recur (inc i)))")]
                                   :deadline-ms
                                   (+ (System/currentTimeMillis) 1200)))
      (Thread/sleep 100)
      (send! session (invoke-value "busy-agent" "invocation-second" []))
      (let [busy (recv! session)]
        (is (= "invocation-second" (:seon.execution/invocation-id busy)))
        (is (= "The execution child already has an active invocation."
               (error-of busy))))
      (let [timeout (recv! session)]
        (is (= "invocation-long" (:seon.execution/invocation-id timeout)))
        (is (= "The invocation timed out." (error-of timeout))))
      (finally (close! session)))))

(deftest untrusted-artifact-digest-errors-core-bug
  (let [[session _ready] (open-session! "digest-agent")]
    (try
      (send! session
             (assoc-in (invoke-value "digest-agent" "invocation-digest" [])
                       [:seon.execution/function-identity
                        :seon.execution/artifact-digest]
                       (apply str (repeat 64 "b"))))
      (is (= "The compiled function identity is not trusted by this artifact."
             (error-of (recv! session))))
      (finally (close! session)))))

(deftest render-entrypoints-answer-with-pod-steering-errors
  (let [[session _ready] (open-session! "render-agent")]
    (try
      (doseq [selected ['seon.execution.runtime/render-prompt!
                        'seon.execution.runtime/render-agent-view!]]
        (send! session (invoke-value "render-agent"
                                     (str "invocation-" selected) []
                                     :function-symbol selected))
        (let [response (recv! session)]
          (is (= :core-bug (get-in response [:seon.execution/error
                                             :seon.error/kind])))
          (is (re-find #"rendering stay on the pod"
                       (error-of response)))))
      (finally (close! session)))))

(deftest authored-identity-names-the-recorded-seam
  (let [[session _ready] (open-session! "authored-agent")]
    (try
      (send! session (invoke-value "authored-agent" "invocation-authored" []
                                   :function-symbol 'my.agent.authored/helper
                                   :identity-key
                                   :seon.execution/source-digest))
      (is (= "Authored function invocation is not yet served by the JVM host."
             (error-of (recv! session))))
      (finally (close! session)))))

(deftest oversized-results-error-with-the-byte-limit
  (let [[session _ready] (open-session! "bytes-agent")]
    (try
      (send! session (invoke-value "bytes-agent" "invocation-big"
                                   [(form "(vec (range 10000))")]
                                   :result-limit-bytes 64))
      (let [response (recv! session)]
        (is (= "The function result exceeded its byte limit."
               (error-of response)))
        (is (= :agent (get-in response [:seon.execution/error
                                        :seon.error/kind]))))
      (finally (close! session)))))

(deftest invalid-messages-error-with-the-invalid-invocation-id
  (let [[session _ready] (open-session! "invalid-agent")]
    (try
      (send! session {:seon.execution/message :seon.execution.message/invoke
                      :seon.execution/protocol-version 3})
      (is (= "invalid" (:seon.execution/invocation-id (recv! session))))
      (finally (close! session)))))

;;; Contract: deadline interrupt, cancel, shutdown

(deftest timeout-interrupts-the-runaway-and-the-context-survives
  (let [[session _ready] (open-session! "runaway-agent")
        started (System/currentTimeMillis)]
    (try
      (send! session (invoke-value "runaway-agent" "invocation-runaway"
                                   [(form "(def before-runaway 7)")
                                    (form "(loop [i 0] (recur (inc i)))")]
                                   :deadline-ms
                                   (+ (System/currentTimeMillis) 600)))
      (let [response (recv! session)
            elapsed (- (System/currentTimeMillis) started)]
        (is (= :seon.execution.message/error
               (:seon.execution/message response)))
        (is (= "The invocation timed out." (error-of response)))
        (is (= :agent (get-in response [:seon.execution/error
                                        :seon.error/kind])))
        (is (< elapsed 5000)))
      ;; sci's in-process interrupt leaves the context healthy — the
      ;; favorable divergence from the poisoned child.
      (send! session (invoke-value "runaway-agent" "invocation-after"
                                   [(form "(inc before-runaway)")]))
      (is (= 8 (get-in (recv! session)
                       [:seon.execution/result :seon.host/results 0
                        :seon.eval/value])))
      (finally (close! session)))))

(deftest cancel-settles-the-active-invocation-and-ends-the-session
  (let [[session _ready] (open-session! "cancel-agent")]
    (try
      (send! session (invoke-value "cancel-agent" "invocation-canceled"
                                   [(form "(def before-cancel 11)")
                                    (form "(loop [i 0] (recur (inc i)))")]
                                   :deadline-ms
                                   (+ (System/currentTimeMillis) 30000)))
      (Thread/sleep 150)
      (send! session {:seon.execution/message :seon.execution.message/cancel
                      :seon.execution/protocol-version 3
                      :seon.execution/invocation-id "invocation-canceled"})
      (let [response (recv! session)]
        (is (= "invocation-canceled"
               (:seon.execution/invocation-id response)))
        (is (= "The invocation was canceled." (error-of response)))
        (is (= :agent (get-in response [:seon.execution/error
                                        :seon.error/kind]))))
      ;; The session ends (a child process exits here) …
      (is (nil? (recv! session)))
      (finally (close! session)))
    ;; … while the agent's context survives in the host.
    (let [[reconnected _ready] (open-session! "cancel-agent")]
      (try
        (send! reconnected (invoke-value "cancel-agent" "invocation-revisit"
                                         [(form "before-cancel")]))
        (is (= 11 (get-in (recv! reconnected)
                          [:seon.execution/result :seon.host/results 0
                           :seon.eval/value])))
        (finally (close! reconnected))))))

(deftest shutdown-acknowledges-stopped-and-parks-the-context
  (let [[session _ready] (open-session! "shutdown-agent")]
    (try
      (send! session (invoke-value "shutdown-agent" "invocation-park"
                                   [(form "(def parked-state 21)")]))
      (recv! session)
      (send! session {:seon.execution/message :seon.execution.message/shutdown
                      :seon.execution/protocol-version 3})
      (is (= :seon.execution.message/stopped
             (:seon.execution/message (recv! session))))
      (finally (close! session)))
    ;; Park = drop: a fresh session forks a fresh context from the base.
    (let [[reconnected _ready] (open-session! "shutdown-agent")]
      (try
        (send! reconnected (invoke-value "shutdown-agent" "invocation-fresh"
                                         [(form "parked-state")]))
        (let [result (:seon.execution/result (recv! reconnected))]
          (is (= 1 (:seon.eval/n-fail result))))
        (finally (close! reconnected))))))

;;; Restore: fork the shared base + replay defs (the kill-drill mechanics)

(deftest restore-forks-the-base-and-replays-defs
  (let [base (::host/base *host*)
        ctx (context/fork-context base)
        replayed (context/replay-defs!
                  ctx
                  ["(def plan-rows (vec (range 200)))"
                   "(def total (reduce + plan-rows))"])]
    (is (every? :seon.eval/ok? replayed))
    (is (pos? (get-in (::context/report base) [::context/loaded])))))
