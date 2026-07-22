(ns seon.host-conformance-writer-test
  "Execution-protocol conformance for the JVM agent host (`seon.host`).

   The harness client replays the pod->child message sequences inventoried
   from `seon.execution`/`seon.execution.host`/`seon.execution.runtime`
   over UDS and asserts each response matches the child contract
   shape-for-shape. The writer is a local fake `uds/start-request-server!`
   handler (the same self-contained pattern as `seon.db.transport-uds-test`)
   so the suite needs no live cluster."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [datalog.parser :as datalog.parser]
            [seon.ai.tokens :as tokens]
            [seon.db.id :as db.id]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.host :as host]
            [seon.host.context :as context]
            [seon.host.eval :as host.eval]
            [seon.host.invoke :as host.invoke]
            [seon.host.preflight :as host.preflight]
            [seon.host.sample :as host.sample]
            [seon.host.session :as host.session]
            [seon.render.value :as render.value])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream DataOutputStream
            File IOException OutputStream PrintStream]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ServerSocketChannel SocketChannel]))

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

(deftest start-refuses-a-live-foreign-eval-socket-without-unlinking-it
  (let [path (socket-path "foreign-host")
        address (UnixDomainSocketAddress/of path)]
    (with-open [listener (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
      (.bind listener address)
      (let [failure
            (try
              (host/start! {::host/socket-path path
                            ::context/writer-socket-path "unused"
                            ::context/database-name "unused"})
              nil
              (catch clojure.lang.ExceptionInfo exception exception))]
        (is (= :seon.host.error/socket-owned
               (:seon.error/kind (ex-data failure))))
        (with-open [channel (uds/connect! path)]
          (is (.isConnected channel)
              "the foreign listener remains reachable after refusal"))))))

(def ^:private fake-agent-rows
  #{[1 "root"] [2 "task-a"] [3 "task-b"]})

(def ^:private transaction-requests (atom []))

(defn- run-fence-cas [agent-id run-id]
  (let [run-ref [:seon.agent.run/id run-id]]
    [:db.fn/cas [:seon.agent/id agent-id] :seon.agent/run run-ref run-ref]))

(defn- fake-writer-response [request]
  (let [request-id (::protocol/request-id request)]
    (condp = (::protocol/operation request)
      protocol/resolve-head-operation
      {::protocol/success? true
       ::protocol/request-id request-id
       :seon.db/db database}

      protocol/query-operation
      ;; The recording path issues two host-owned reads: the stored
      ;; :seon.eval/id generator policy (allocation) and the corpus
      ;; def-sources replay read (restore). Dispatch structurally on the
      ;; query form; every other read keeps the generic agent rows.
      (let [query-form (::protocol/query-form request)
            result (cond
                     (some #{:seon.config/id} (flatten (vec query-form)))
                     [32 4096 1024 3 80 2 12 16384 :symbols
                      true true true 1 50]

                     (= query-form db.id/generator-policy-query)
                     [[:seon.eval/id :seon.db.id.generator/compact]]

                     (some #{'?source} (flatten (vec query-form)))
                     []

                     :else fake-agent-rows)]
        {::protocol/success? true
         ::protocol/request-id request-id
         :datahike.query/result result
         :datahike.query/resource-evidence
         {:datahike.resource/work 42
          :datahike.resource/result-count 3
          :datahike.resource/result-weight 9
          :datahike.resource/limits {}}})

      protocol/execute-many-operation
      {::protocol/success? true
       ::protocol/request-id request-id
       ::protocol/results
       (mapv (fn [member]
               (let [query-form (::protocol/query-form member)]
                 {::protocol/success? true
                  ::protocol/request-id request-id
                  :datahike.query/result
                  (cond
                    (some #{:seon.schema/form} (flatten (vec query-form))) []
                    (some #{:seon.fn/spec} (flatten (vec query-form))) []
                    :else fake-agent-rows)}))
             (::protocol/members request))}

      protocol/pull-operation
      {::protocol/success? true
       ::protocol/request-id request-id
       ::protocol/result {:seon.agent/id "root"}}

      protocol/transact-operation
      (do
        (swap! transaction-requests conj request)
        (if (= [(run-fence-cas "fenced-agent" "stale-run")]
               (::protocol/transaction-data request))
          {::protocol/success? false
           ::protocol/request-id request-id
           ::protocol/error-kind protocol/database-error
           ::protocol/error "The run pointer CAS failed."}
          {::protocol/success? true
           ::protocol/request-id request-id
           :db-before database
           :db-after (update database :t inc)
           :tx-data []
           :tempids {}
           :tx-meta {}}))

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
   & {:keys [deadline-ms result-limit-bytes function-symbol identity-key
             invocation-database run-fence turn-id]
      :or {deadline-ms (+ (System/currentTimeMillis) 30000)
           result-limit-bytes 1000000
           function-symbol 'seon.execution.runtime/eval-batch!
           identity-key :seon.execution/artifact-digest
           invocation-database database
           turn-id "turn-conformance"}}]
  (cond->
   {:seon.execution/message :seon.execution.message/invoke
    :seon.execution/protocol-version 3
    :seon.execution/agent-id agent-id
    :seon.execution/invocation-id invocation-id
    :seon.db/db invocation-database
    :seon.execution/function-identity
    {:seon.execution/function-symbol function-symbol
     identity-key artifact-digest}
    :seon.execution/arguments
    [(cond-> {:seon.eval/parsed parsed
              :seon.eval/starting-ns 'my.agent.conformance}
       turn-id (assoc :seon.agent.turn/id-of-turn turn-id))]
    :seon.execution/deadline-ms deadline-ms
    :seon.execution/result-limit-bytes result-limit-bytes}
    run-fence (assoc :seon.execution/run-fence run-fence)))

(defn- form [source] {:seon.repl/kind :form :seon.repl/source source})

(defn- read-failure [source]
  {:seon.repl/kind :read
   :seon.repl/ok? false
   :seon.repl/source source
   :seon.repl/message "Unexpected EOF."})

(def ^:private sample-limits
  {:seon.config.render/value-max-path-segments 32
   :seon.config.render/value-max-path-bytes 4096
   :seon.config.render/value-max-realized-items 32
   :seon.config.render/value-max-depth 3
   :seon.config.render/value-max-string 80
   :seon.config.render/value-shape-sample 2
   :seon.render.value/page-size 3})

(def ^:private trusted-sample-limits
  {:seon.config.render/value-max-path-segments 32
   :seon.config.render/value-max-path-bytes 4096
   :seon.config.render/value-max-realized-items 1024
   :seon.config.render/value-max-depth 3
   :seon.config.render/value-max-string 80
   :seon.config.render/value-shape-sample 2
   :seon.render.value/page-size 12})

(defn- sample-value [agent-id request-id eval-id path]
  {:seon.execution/message :seon.execution.message/value-sample
   :seon.execution/protocol-version 3
   :seon.execution/agent-id agent-id
   :seon.execution/request-id request-id
   :seon.execution/eval-id eval-id
   :seon.render.value/path path
   :seon.render.value/offset 0
   :seon.render.value/effective-limits sample-limits})

(defn- error-of [message] (get-in message [:seon.execution/error
                                           :seon.error/message]))

(defn- recorded-tx-data []
  (mapcat ::protocol/transaction-data @transaction-requests))

(defn- await-condition! [predicate label]
  (loop [remaining 100]
    (cond
      (predicate) true
      (zero? remaining) (is false (str "timed out waiting for " label))
      :else (do (Thread/sleep 10) (recur (dec remaining))))))

(deftest sampling-policy-is-read-at-the-invocation-basis-and-fails-closed
  (is (some? (datalog.parser/parse
              (var-get #'host.sample/sampling-policy-query)))
      "the maintained invocation-policy tuple query parses at the writer boundary")
  (let [seen (atom [])
        acquire (var-get #'host.sample/acquire-sampling-policy!)
        invalid [nil
                 [32 4096 1024 3 80 2 12]
                 [32 4096 1024 3 80 2 12 16384 :symbols true true true 1]
                 [32 4096 1024 3 80 2 12 "16384" :symbols true true true 1 50]
                 [32 4096 1024 3 80 2 12 16384 :unknown true true true 1 50]
                 [32 4096 1024 3 80 2 12 16384 :symbols :yes true true 1 50]
                 [[32 4096 1024 3 80 2 12 16384 :symbols true true true 1 50]
                  [32 4096 1024 3 80 2 12 16384 :symbols true true true 1 50]]
                 {:seon/error {:seon.error/kind :core-bug}}]]
    (doseq [response invalid]
      (with-redefs-fn
        {#'context/query-writer-at!
         (fn [_ passed-database _ arguments]
           (swap! seen conj [passed-database arguments])
           response)}
        (fn []
          (try
            (acquire ::writer database)
            (is false (str "accepted invalid policy " (pr-str response)))
            (catch clojure.lang.ExceptionInfo error
              (is (= :core-bug (:seon.error/kind (ex-data error)))))))))
    (is (= (repeat (count invalid) [database ["cluster"]]) @seen)
        "every refusal queries the exact invocation database before eval")
    (let [row [32 4096 1024 3 80 2 12 16384 :symbols false true false 2 75]]
      (with-redefs-fn
        {#'context/query-writer-at!
         (fn [_ passed-database _ arguments]
           (is (= database passed-database))
           (is (= ["cluster"] arguments))
           row)}
        (fn []
          (is (= {:seon.config.render/value-max-path-segments 32
                  :seon.config.render/value-max-path-bytes 4096
                  :seon.config.render/value-max-realized-items 1024
                  :seon.config.render/value-max-depth 3
                  :seon.config.render/value-max-string 80
                  :seon.config.render/value-shape-sample 2
                  :seon.render.value/page-size 12
                  :seon.config.render/database-edn-cap 16384
                  :seon.config.repair/level :symbols
                  :seon.config.repair.class/delimiters? false
                  :seon.config.repair.class/def-vs-defn? true
                  :seon.config.repair.class/undeclared-var? false
                  :seon.config.repair/max-fixes-per-form 2
                  :seon.config.repair/budget-ms 75}
                 (acquire ::writer database))))))))

(deftest frame-preflight-falls-back-before-consuming-the-settle-cas
  (let [token {::host.invoke/invocation {}}
        active (atom token)
        bytes (ByteArrayOutputStream.)
        session {::host.session/active active
                 ::host.session/output bytes
                 ::host.session/write-lock (Object.)}
        huge (apply str (repeat (+ protocol/maximum-frame-bytes 1024) "x"))
        frame {:seon.execution/message :seon.execution.message/error
               :seon.execution/protocol-version 3
               :seon.execution/invocation-id "oversize"
               :seon.execution/error
               {:seon.error/message huge
                :seon.error/kind :agent
                :seon.error/data {:huge huge}}}]
    (is (true? (#'host.invoke/settle! session token frame)))
    (is (nil? @active))
    (let [response (uds/read-frame
                    (ByteArrayInputStream. (.toByteArray bytes)))]
      (is (= "oversize" (:seon.execution/invocation-id response)))
      (is (= :seon.execution.message/error
             (:seon.execution/message response)))
      (is (<= (tokens/estimate (error-of response)) 120))
      (is (< (alength (uds/encode response)) protocol/maximum-frame-bytes)))))

(deftest physical-frame-write-failure-records-one-fault-and-does-not-retry
  (reset! transaction-requests [])
  (let [token {::host.invoke/invocation {}}
        active (atom token)
        writes (atom 0)
        output (proxy [OutputStream] []
                 (write
                   ([_]
                    (swap! writes inc)
                    (throw (IOException. "closed transport")))
                   ([_ _ _]
                    (swap! writes inc)
                    (throw (IOException. "closed transport")))))
        session {::host.session/active active
                 ::host.session/output output
                 ::host.session/write-lock (Object.)}]
    (is (false?
         (#'host.invoke/settle!
          session token
          {:seon.execution/message :seon.execution.message/error
           :seon.execution/protocol-version 3
           :seon.execution/invocation-id "physical"
           :seon.execution/error
           {:seon.error/message "bounded" :seon.error/kind :core-bug}})))
    (is (nil? @active))
    (is (= 1 @writes) "a physical write failure never attempts a second frame")
    (is (some #(= :core (:seon.error/fault %)) (recorded-tx-data)))))

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

(deftest one-session-thread-start-failure-does-not-end-accepting
  (reset! transaction-requests [])
  (let [original (var-get #'host/start-session-thread!)
        first? (atom true)
        failed (promise)]
    (with-redefs-fn
      {#'host/start-session-thread!
       (fn [& arguments]
         (if (compare-and-set! first? true false)
           (do (deliver failed true)
               (throw (ex-info "session thread start failed" {})))
           (apply original arguments)))}
      (fn []
        (let [abandoned (session!)]
          (try
            (is (= true (deref failed 1000 false)))
            (await-condition!
             #(some (fn [row] (= :core (:seon.error/fault row)))
                    (recorded-tx-data))
             "acceptor fault record")
            (let [[replacement ready] (open-session! "after-thread-failure")]
              (try
                (is (= :seon.execution.message/ready
                       (:seon.execution/message ready)))
                (finally (close! replacement))))
            (finally (close! abandoned))))))))

(deftest silent-startup-is-released-and-the-acceptor-remains-live
  (with-redefs-fn
    {#'host/startup-read-timeout-ms 50}
    (fn []
      (let [silent (session!)]
        (try
          (is (nil? (recv! silent)))
          (let [[replacement ready] (open-session! "after-silent-startup")]
            (try
              (is (= :seon.execution.message/ready
                     (:seon.execution/message ready)))
              (finally (close! replacement))))
          (finally (close! silent)))))))

(deftest session-reader-throw-records-a-fault-and-only-that-session-dies
  (reset! transaction-requests [])
  (let [[session _ready] (open-session! "reader-fault-agent")
        raw (DataOutputStream. ^OutputStream (::output session))]
    (try
      (.writeInt raw -1)
      (.flush raw)
      (is (nil? (recv! session)))
      (await-condition!
       #(some (fn [row] (= :core (:seon.error/fault row)))
              (recorded-tx-data))
       "session reader fault record")
      (let [[replacement ready] (open-session! "after-reader-fault")]
        (try
          (is (= :seon.execution.message/ready
                 (:seon.execution/message ready)))
          (finally (close! replacement))))
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

(deftest unresolved-preflight-is-receipt-first-and-terminal
  (reset! transaction-requests [])
  (let [[session _ready] (open-session! "preflight-terminal-agent")]
    (try
      (send! session
             (invoke-value
              "preflight-terminal-agent" "preflight-seed"
              [(form "(defn thing-aa [] :aa)")
               (form "(defn thing-ab [] :ab)")]
              :turn-id nil))
      (let [seed-response (recv! session)]
        (is (= 2 (get-in seed-response
                         [:seon.execution/result :seon.eval/n-ok]))
            (pr-str seed-response)))
      (reset! transaction-requests [])
      (let [eval-calls (atom 0)
            receipt-seen? (atom false)
            original-eval (var-get #'host.eval/eval-form!)
            original-preflight (var-get #'host.preflight/preflight!)]
        (with-redefs-fn
          {#'host.eval/eval-form!
           (fn [& arguments]
             (swap! eval-calls inc)
             (apply original-eval arguments))
           #'host.preflight/preflight!
           (fn [& arguments]
             (reset! receipt-seen?
                     (boolean
                      (some #(and (map? %)
                                  (= :running (:seon.eval/status %)))
                            (mapcat #(tree-seq coll? seq %)
                                    (recorded-tx-data)))))
             (apply original-preflight arguments))}
          (fn []
            (send! session
                   (invoke-value "preflight-terminal-agent"
                                 "preflight-ambiguous"
                                 [(form "(thing-ac)")]))
            (let [result (:seon.execution/result (recv! session))
                  envelope (first (:seon.host/results result))]
              (is (true? @receipt-seen?)
                  "the running receipt commits before symbol preflight")
              (is (zero? @eval-calls)
                  "an ambiguous resolution never reaches eval-form!")
              (is (= 0 (:seon.eval/n-ok result)))
              (is (= 1 (:seon.eval/n-fail result)))
              (is (= :resolution
                     (get-in envelope [:seon/error :seon.error/data
                                       :seon.error.sci/class])))
              (is (= 2
                     (count
                      (get-in envelope [:seon/error :seon.error/data
                                        :seon.repl.parse.repair/suggestions]))))))))
      (finally (close! session)))))

(deftest repaired-read-redispatches-through-the-ordinary-recorded-path
  (letfn [(run-case [agent-id invocation-id first-entry]
            (reset! transaction-requests [])
            (let [[session _ready] (open-session! agent-id)]
              (try
                (send! session
                       (invoke-value
                        agent-id invocation-id
                        [first-entry
                         (form "(do (print \"repair-output\") (def answer 42))")
                         (form "answer")]))
                (let [result (:seon.execution/result (recv! session))]
                  {:result result
                   :tx-data (vec (recorded-tx-data))})
                (finally (close! session)))))]
    (let [broken (run-case "repair-broken-agent" "repair-broken"
                           (read-failure "(ns my.repair-equivalence"))
          correct (run-case "repair-correct-agent" "repair-correct"
                            (form "(ns my.repair-equivalence)"))
          broken-result (:result broken)
          correct-result (:result correct)
          eval-sources (fn [run]
                         (into [] (keep :seon.eval/source) (:tx-data run)))
          outputs (fn [run]
                    (into [] (keep :seon.eval/output) (:tx-data run)))]
      (is (= 3 (:seon.eval/n-ok broken-result)
             (:seon.eval/n-ok correct-result)))
      (is (= 0 (:seon.eval/n-fail broken-result)
             (:seon.eval/n-fail correct-result)))
      (is (= 42 (get-in broken-result [:seon.host/results 2
                                       :seon.eval/value])
             (get-in correct-result [:seon.host/results 2
                                     :seon.eval/value])))
      (is (= ["repair-output"] (outputs broken) (outputs correct)))
      (is (= (eval-sources correct) (eval-sources broken))
          "the repaired source is the receipt and terminal row source")
      (is (seq (get-in broken-result [:seon.host/results 0
                                      :seon.repl.parse.repair/changes])))
      (is (nil? (get-in correct-result [:seon.host/results 0
                                        :seon.repl.parse.repair/changes])))
      (is (= (into [] (keep :seon.ns/name) (:tx-data broken))
             (into [] (keep :seon.ns/name) (:tx-data correct)))
          "the repaired and correct namespace declarations tee equally"))))

(deftest held-run-fence-uses-the-invocation-database-and-preserves-results
  (reset! transaction-requests [])
  (let [invocation-database
        (assoc database
               :t (dec (:t database))
               :datahike/commit-id
               #uuid "ea0976b4-cd7a-55d6-9832-8279c6d62365")
        [session _ready] (open-session! "held-agent")]
    (try
      (send! session
             (invoke-value "held-agent" "without-fence" [(form "(+ 20 22)")]
                           :invocation-database invocation-database
                           :turn-id nil))
      (let [without-fence (:seon.execution/result (recv! session))]
        (send! session
               (invoke-value "held-agent" "with-fence" [(form "(+ 20 22)")]
                             :invocation-database invocation-database
                             :run-fence {:seon.agent.run/id "held-run"}
                             :turn-id nil))
        (let [with-fence (:seon.execution/result (recv! session))
              requests @transaction-requests
              request (first requests)]
          (is (= without-fence with-fence)
              "a held fence leaves the batch result equal")
          (is (= (seq (uds/encode without-fence))
                 (seq (uds/encode with-fence)))
              "a held fence leaves the batch result byte-identical")
          (is (= 1 (count requests))
              "the held receiptless batch adds only its fence transaction")
          (is (= invocation-database (:seon.db/db request)))
          (is (= [(run-fence-cas "held-agent" "held-run")]
                 (::protocol/transaction-data request))
              "the transaction contains exactly one run-pointer CAS")))
      (reset! transaction-requests [])
      (send! session
             (invoke-value "held-agent" "held-recorded"
                           [(form "(do (print \"held-output\") 42)")]
                           :invocation-database invocation-database
                           :run-fence {:seon.agent.run/id "held-run"}))
      (let [recorded (:seon.execution/result (recv! session))
            requests @transaction-requests
            transaction-data (mapcat ::protocol/transaction-data requests)]
        (is (= 3 (count requests))
            "the fence precedes the ordinary running and terminal receipts")
        (is (= 1 (count (:seon.eval/ids recorded))))
        (is (= 1 (:seon.eval/n-ok recorded)))
        (is (= 42 (get-in recorded [:seon.host/results 0 :seon.eval/value])))
        (is (= ["held-output"]
               (into [] (keep :seon.eval/output) transaction-data))))
      (finally (close! session)))))

(deftest lost-run-fence-skips-receipts-and-evaluation-and-settles-as-a-result
  (reset! transaction-requests [])
  (let [[session _ready] (open-session! "fenced-agent")
        eval-calls (atom 0)
        original (var-get #'host.eval/eval-form!)]
    (try
      (with-redefs-fn
        {#'host.eval/eval-form!
         (fn [& arguments]
           (swap! eval-calls inc)
           (apply original arguments))}
        (fn []
          (send! session
                 (invoke-value "fenced-agent" "lost-fence"
                               [(form "(def forbidden 42)")]
                               :run-fence {:seon.agent.run/id "stale-run"}))
          (let [response (recv! session)]
            (is (= :seon.execution.message/result
                   (:seon.execution/message response)))
            (is (= "lost-fence" (:seon.execution/invocation-id response)))
            (is (= {:seon.eval/ids []
                    :seon.eval/n-ok 0
                    :seon.eval/n-fail 0
                    :seon.host/results []
                    :seon.eval/fenced? true}
                   (:seon.execution/result response))))))
      (is (zero? @eval-calls))
      (is (= 1 (count @transaction-requests))
          "the rejected fence is the only transaction, so no receipt exists")
      (is (= [(run-fence-cas "fenced-agent" "stale-run")]
             (::protocol/transaction-data (first @transaction-requests))))
      (finally (close! session)))))

(deftest oversized-eval-error-is-bounded-and-the-session-survives
  (let [[session _ready] (open-session! "oversize-error-agent")]
    (try
      (send! session
             (invoke-value
              "oversize-error-agent" "oversize-error"
              [(form "(throw (ex-info (apply str (repeat 1100000 \"xxxx\")) {}))")]))
      (let [response (recv! session)]
        (is (contains? #{:seon.execution.message/error
                         :seon.execution.message/result}
                       (:seon.execution/message response)))
        (let [message (or (error-of response)
                          (get-in response
                                  [:seon.execution/result :seon.host/results 0
                                   :seon/error :seon.error/message]))]
          (is (<= (tokens/estimate message) 120)))
        (is (< (alength (uds/encode response)) protocol/maximum-frame-bytes)))
      (send! session
             (invoke-value "oversize-error-agent" "after-oversize"
                           [(form "(+ 20 22)")]))
      (is (= 42 (get-in (recv! session)
                        [:seon.execution/result :seon.host/results 0
                         :seon.eval/value])))
      (finally (close! session)))))

(deftest sci-output-is-per-form-capped-persisted-and-absent-from-host-stdout
  (reset! transaction-requests [])
  (let [[session _ready] (open-session! "printing-agent")
        host-bytes (ByteArrayOutputStream.)
        original-out System/out]
    (try
      (System/setOut (PrintStream. host-bytes true "UTF-8"))
      (send! session
             (invoke-value
              "printing-agent" "printing"
              [(form "(do (print \"first-only\") 1)")
               (form "(do (print (apply str (repeat 20000 \"flood\"))) 2)")]))
      (let [response (recv! session)]
        (is (= :seon.execution.message/result
               (:seon.execution/message response))))
      (System/setOut original-out)
      (let [outputs (into [] (keep :seon.eval/output) (recorded-tx-data))]
        (is (= 2 (count outputs)))
        (is (str/includes? (first outputs) "first-only"))
        (is (not (str/includes? (second outputs) "first-only"))
            "output attribution is per form, not cumulative")
        (is (str/includes? (second outputs) "truncated"))
        (is (<= (count (second outputs)) 16384)))
      (is (zero? (.size host-bytes)) "SCI prints never reach host stdout")
      (finally
        (System/setOut original-out)
        (close! session)))))

(deftest late-interrupt-after-eval-return-records-and-leaves-next-form-clean
  (reset! transaction-requests [])
  (let [[session _ready] (open-session! "late-interrupt-agent")
        original (var-get #'host.eval/finish-evaluation!)
        fired? (atom false)]
    (try
      (with-redefs-fn
        {#'host.eval/finish-evaluation!
         (fn [session envelope]
           (when (compare-and-set! fired? false true)
             (.interrupt (Thread/currentThread)))
           (original session envelope))}
        (fn []
        (send! session
               (invoke-value "late-interrupt-agent" "late-interrupt"
                             [(form "(+ 1 1)")]))
        (let [response (recv! session)]
          (is (= :seon.execution.message/error
                 (:seon.execution/message response)))
          (is (= :interrupt
                 (get-in response [:seon.execution/error :seon.error/data
                                   :seon.error.sci/class])))
          (is (= :timeout
                 (get-in response [:seon.execution/error :seon.error/data
                                   :seon.error/kind]))))))
      (is (some #(= :interrupted (:seon.eval/status %))
                (recorded-tx-data))
          "the terminal record commits despite the late interrupt")
      (send! session
             (invoke-value "late-interrupt-agent" "after-late-interrupt"
                           [(form "(+ 20 22)")]))
      (is (= 42 (get-in (recv! session)
                        [:seon.execution/result :seon.host/results 0
                         :seon.eval/value])))
      (finally (close! session)))))

(deftest managed-eval-values-are-sampled-only-in-the-owning-host-session
  (let [[session _ready] (open-session! "sample-agent")]
    (try
      (send! session
             (invoke-value "sample-agent" "sample-source"
                           [(form "{:payload (vec (range 10))}")]))
      (let [invocation-result (:seon.execution/result (recv! session))
            eval-id (first (:seon.eval/ids invocation-result))
            valid (sample-value "sample-agent" "sample-live"
                                eval-id [:payload])
            malformed
            [(assoc valid :host.test/extra true)
             (assoc valid :seon.execution/message :host.test/unknown)
             (assoc valid :seon.execution/protocol-version 2)
             (assoc valid :seon.execution/agent-id "")
             (assoc valid :seon.execution/request-id "")
             (assoc valid :seon.execution/eval-id 42)
             (assoc valid :seon.render.value/path (vec (repeat 33 :x)))
             (-> valid
                 (assoc :seon.render.value/offset 31)
                 (assoc-in [:seon.render.value/effective-limits
                            :seon.render.value/page-size] 3))]]
        (is (string? eval-id))
        (let [raw-lookups (atom 0)
              original (var-get #'host.sample/retained-live-value)]
          (with-redefs-fn
            {#'host.sample/retained-live-value
             (fn [& args] (swap! raw-lookups inc) (apply original args))}
            (fn []
              (doseq [[field maximum] trusted-sample-limits]
                (send! session
                       (assoc-in valid
                                 [:seon.render.value/effective-limits field]
                                 (inc maximum)))
                (let [refused (recv! session)]
                  (is (= :seon.execution.message/value-sample-result
                         (:seon.execution/message refused)))
                  (is (= (render.value/sampling-policy-refusal)
                         (:seon.render.value/result refused)))))))
          (is (zero? @raw-lookups)
              "a widened policy is refused before raw slot access"))
        (let [raw-lookups (atom 0)]
          (with-redefs-fn
            {#'host.sample/retained-live-entry
             (fn [_ _] {::host.session/found? true
                        ::host.session/limits sample-limits})
             #'host.sample/retained-live-value
             (fn [& _] (swap! raw-lookups inc) :forbidden)}
            (fn []
              (send! session valid)
              (let [unavailable (recv! session)]
                (is (= :seon.execution.message/value-sample-error
                       (:seon.execution/message unavailable)))
                (is (= {:seon.error/message
                        render.value/sampling-policy-unavailable-message
                        :seon.error/kind :seon.runtime/unavailable}
                       (:seon.execution/error unavailable))))))
          (is (zero? @raw-lookups)
              "corrupt retained metadata is rejected before raw slot access"))
        (doseq [bad malformed]
          (send! session bad)
          (let [refused (recv! session)]
            (is (= :seon.execution.message/value-sample-error
                   (:seon.execution/message refused)))
            (is (string? (:seon.execution/request-id refused)))))
        ;; Every malformed frame was rejected before slot lookup; the same
        ;; retained id remains available on the same reader loop.
        (send! session valid)
        (let [sampled (recv! session)]
          (is (= :seon.execution.message/value-sample-result
                 (:seon.execution/message sampled)))
          (is (= "sample-live" (:seon.execution/request-id sampled)))
          (is (= :available
                 (get-in sampled [:seon.render.value/result
                                  :seon.render.value/availability])))
          (is (= [0 1 2]
                 (get-in sampled [:seon.render.value/result
                                  :seon.render.value/projection
                                  :seon.render.value/tree
                                  :seon.render.value/shown]))))
        (send! session {:seon.execution/message
                        :seon.execution.message/shutdown
                        :seon.execution/protocol-version 3})
        (is (= :seon.execution.message/stopped
               (:seon.execution/message (recv! session))))
        (close! session)
        (let [[replacement _] (open-session! "sample-agent")]
          (try
            (send! replacement (sample-value "sample-agent" "sample-retired"
                                             eval-id [:payload]))
            (let [retired (recv! replacement)]
              (is (= :unavailable
                     (get-in retired [:seon.render.value/result
                                      :seon.render.value/availability])))
              (is (true?
                   (get-in retired [:seon.render.value/result
                                    :seon.render.value/recompute?]))))
            (finally (close! replacement)))))
      (finally (close! session)))))

(deftest sample-render-throw-answers-an-error-and-keeps-the-session-live
  (let [[session _ready] (open-session! "sample-throw-agent")]
    (try
      (send! session
             (invoke-value "sample-throw-agent" "sample-throw-source"
                           [(form "{:payload [1 2 3]}")]))
      (let [eval-id (first (get-in (recv! session)
                                   [:seon.execution/result :seon.eval/ids]))
            sample (sample-value "sample-throw-agent" "sample-throw"
                                 eval-id [:payload])]
        (with-redefs [render.value/drill-value
                      (fn [& _] (throw (ex-info "sample failed" {})))]
          (send! session sample)
          (let [response (recv! session)]
            (is (= :seon.execution.message/value-sample-error
                   (:seon.execution/message response)))
            (is (= "sample-throw" (:seon.execution/request-id response)))))
        (send! session (assoc sample :seon.execution/request-id "still-live"
                             :host.test/extra true))
        (is (= "still-live" (:seon.execution/request-id (recv! session))))
        (send! session
               (invoke-value "sample-throw-agent" "after-sample-throw"
                             [(form "(+ 20 22)")]))
        (is (= 42 (get-in (recv! session)
                          [:seon.execution/result :seon.host/results 0
                           :seon.eval/value]))))
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

(deftest context-query-with-evidence-returns-its-own-cost
  (let [[session _ready] (open-session! "cost-agent")]
    (try
      (send! session
             (invoke-value
              "cost-agent" "invocation-cost"
              [(form (str "(:datahike.query/resource-evidence"
                          " (seon.db/query-with-evidence"
                          " '[:find ?e ?id :where [?e :seon.agent/id ?id]]))"))]))
      (let [result (:seon.execution/result (recv! session))]
        (is (= {:datahike.resource/work 42
                :datahike.resource/result-count 3
                :datahike.resource/result-weight 9
                :datahike.resource/limits {}}
               (get-in result [:seon.host/results 0 :seon.eval/value]))
            "a host-context query surfaces the writer's own cost evidence"))
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
        (is (= :interrupt
               (get-in timeout [:seon.execution/error
                                :seon.error/data
                                :seon.error.sci/class]))))
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

(deftest authored-identity-verifies-the-pinned-database-source
  (let [[session _ready] (open-session! "authored-agent")]
    (try
      (send! session (invoke-value "authored-agent" "invocation-authored" []
                                   :function-symbol 'my.agent.authored/helper
                                   :identity-key
                                   :seon.execution/source-digest))
      (is (= "The requested current agent function does not exist."
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
        (is (= :interrupt
               (get-in response [:seon.execution/error
                                 :seon.error/data
                                 :seon.error.sci/class])))
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
    (is (pos? (get (::context/report base) ::context/loaded)))))
