(ns seon.host-registry-writer-test
  "Wrapper-registry provisioning and capability op-id receipts (U2).

   Two proof families:
   - the registry is the ONE capability-provisioning mechanism — a
     namespace registered AFTER contexts exist becomes require-able in
     every live context through the shared `:load-fn` closure, and a
     re-registered implementation reaches already-required contexts on
     their next call (the var-epoch upgrade property);
   - `seon.db/transact!` through the registry is exactly-once — the
     `:seon.capability/op-id` crosses the boundary as the writer's
     durable `:seon.db.protocol/request-id` receipt, so a connection
     killed between commit and acknowledgement never re-executes and a
     retry with the same op-id returns the recorded outcome. The writer
     here is the REAL `seon.db.writer` on a memory backend."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.capability :as capability]
            [seon.ai.core]
            [seon.agent.lifecycle]
            [seon.agent.ctx]
            [seon.agent.home]
            [seon.db.host :as db.host]
            [seon.db.id :as db.id]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer]
            [seon.db.writer-test-support :as writer-test]
            [seon.host :as host]
            [seon.host.context :as context]
            [seon.agent.ctx.render-fns]
            [seon.schema :as schema])
  (:import [java.io File]
           [java.nio.channels Channels SocketChannel]))

;;; Registry provisioning over an unconnected writer session — the
;;; wrappers only dial the writer when called, so provisioning proofs
;;; need no server.

(defn- unconnected-writer []
  (context/writer-session
   {::context/writer-socket-path "tmp/unused-registry-test.sock"
    ::context/database-name "registry-test"}))

(deftest registering-after-forks-exist-provisions-every-live-context
  (let [base (context/build-base! (unconnected-writer))
        fork-a (context/fork-context base)
        fork-b (context/fork-context base)
        hits (atom [])]
    (context/register-wrappers!
     {::context/registry (::context/registry base)
      ::context/lib 'seon.net
      ::context/wrappers
      {'fetch {::context/wrapper-fn (fn [request]
                                      (swap! hits conj request)
                                      [:fetched request])
               ::context/arglists '([request])
               ::context/doc "Fetch one request."}}})
    (is (= [:fetched 1]
           (sci/eval-string*
            fork-a "(require '[seon.net :as net]) (net/fetch 1)")))
    (is (= [:fetched 2]
           (sci/eval-string*
            fork-b "(require '[seon.net :as net]) (net/fetch 2)")))
    (is (= [1 2] @hits))
    (is (= {:arglists '([request]) :doc "Fetch one request."}
           (sci/eval-string*
            fork-a
            "(select-keys (meta #'seon.net/fetch) [:arglists :doc])")))))

(deftest re-registering-upgrades-live-contexts-on-their-next-call
  (let [base (context/build-base! (unconnected-writer))
        fork-a (context/fork-context base)
        fork-b (context/fork-context base)
        register!
        (fn [implementation]
          (context/register-wrappers!
           {::context/registry (::context/registry base)
            ::context/lib 'seon.upgrade
            ::context/wrappers
            {'answer {::context/wrapper-fn implementation
                      ::context/arglists '([x])
                      ::context/doc "Probe answer."}}}))]
    (register! (fn [x] [:v1 x]))
    (is (= [:v1 1] (sci/eval-string*
                    fork-a "(require '[seon.upgrade :as up]) (up/answer 1)")))
    (is (= [:v1 2] (sci/eval-string*
                    fork-b "(require '[seon.upgrade :as up]) (up/answer 2)")))
    ;; Swap the implementation live: no re-require anywhere, both
    ;; contexts' next calls use it (shared vars, root alteration).
    (register! (fn [x] [:v2 x]))
    (is (= [:v2 1] (sci/eval-string* fork-a "(up/answer 1)")))
    (is (= [:v2 2] (sci/eval-string* fork-b "(up/answer 2)")))))

(deftest the-db-family-provisions-through-the-one-registry-path
  (let [base (context/build-base! (unconnected-writer))
        ctx (context/fork-context base)
        registered (get-in @(::context/registry base)
                           ['seon.db ::context/vars 'transact!])
        resolved (sci/eval-string*
                  ctx "(require 'seon.db) #'seon.db/transact!")]
    ;; The var an agent context resolves IS the registry's cached var —
    ;; no second binding path exists for the db capability family.
    (is (some? registered))
    (is (identical? registered resolved))))

(deftest claimant-toolkit-schema-introspection-uses-the-committed-projection
  (let [writer (unconnected-writer)
        projection
        (schema/build-projection
         {:my.plan/title [:string {:min 1}]
          :my.plan/goal :string
          :my.plan/pace [:enum :one-shot :multi-session]
          :my.plan/children [:vector :map]
          :my.plan/plan-request
          [:map
           [:my.plan/title :my.plan/title]
           [:my.plan/goal {:optional true} :my.plan/goal]
           [:my.plan/pace {:optional true} :my.plan/pace]
           [:my.plan/children {:optional true} :my.plan/children]]
          :my.kb/claim [:string {:min 1}]})
        base (context/build-base! writer)
        _ (reset! (::context/projection-state writer)
                  {::context/database {:db-name "registry-test" :t 1}
                   ::context/projection projection})
        ctx (context/fork-context base)]
    (is (= #{:my.plan/title
             :my.plan/goal
             :my.plan/pace
             :my.plan/children}
           (sci/eval-string*
            ctx
            "(my.plan.internal/schema-map-keys :my.plan/plan-request)")))
    (is (= [:string {:min 1}]
           (sci/eval-string*
            ctx
            "(seon.schema/schema-definition :my.kb/claim)")))))

(deftest capability-installation-publishes-its-leaf-inventory
  (let [base (context/build-base! (unconnected-writer))
        inventory (::context/tier-inventory base)
        artifact-inventory
        (capability/installed-artifact-inventory inventory)]
    (is (= :jvm (:seon.execution.inventory/tier inventory)))
    (is (contains? (:seon.execution.inventory/bindings inventory)
                   "seon.db/query"))
    (is (contains? (:seon.execution.inventory/bindings inventory)
                   "seon.agent.fs/read-file"))
    (is (contains? (:seon.execution.inventory/pure-bindings inventory)
                   "seon.db/as-of"))
    (is (re-matches #"[0-9a-f]{64}"
                    (:seon.execution.inventory/digest inventory)))
    (is (= #{:jvm}
           (set
            (keys
             (:seon.execution.inventory/exports-by-tier
              artifact-inventory)))))
    (is (= (:seon.execution.inventory/bindings inventory)
           (get-in artifact-inventory
                   [:seon.execution.inventory/exports-by-tier :jvm])))
    (is (= (:seon.execution.inventory/digest inventory)
           (:seon.execution.inventory/digest artifact-inventory)))))

;;; Op-id receipts against the real writer.

(defn- socket-path [label]
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory
            (str "host-registry-" label "-" (random-uuid) ".sock")))))

(defn- dependencies []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_db-value _vector _k _eids] [])})

(defn- note-count [ctx value]
  (sci/eval-string*
   ctx
   (str "(count (seon.db/query"
        " (quote [:find ?e :in $ ?v"
        "         :where [?e :seon.host-registry-writer-test/note ?v]])"
        " " (pr-str value) "))")))

(defn- transact-note-form [value op-id]
  (str "(seon.db/transact!"
       " {:seon.db/tx-data"
       "  [{:seon.host-registry-writer-test/note " (pr-str value) "}]"
       (when op-id (str " :seon.capability/op-id " (pr-str op-id)))
       "})"))

(defn- register-runtime-schemas!
  [rows]
  (doseq [{:seon.schema/keys [key form]} rows]
    (schema/register! key (edn/read-string form)))
  nil)

(defn- seed-schema-rows!
  "Commit the fresh database's one deliberately unattributed genesis."
  [session rows]
  (let [database (db.host/resolve-db! session nil false)]
    (db.host/call!
     session
     (protocol/transaction-request
      {::protocol/request-id (str (random-uuid))
       :seon.db/db database
       ::protocol/transaction-data (vec rows)}))))

(deftest transact-through-the-registry-is-exactly-once-by-op-id-receipt
  (let [database-name (str "host-registry-" (random-uuid))
        request-path (socket-path "writer")
        server (writer-test/start! {::writer/dependencies (dependencies)
                               ::writer/database-name database-name
                               ::writer/backend :memory
                               ::writer/request-socket-path request-path})
        session (context/writer-session
                 {::context/writer-socket-path request-path
                  ::context/database-name database-name
                  ::context/backend :memory})
        base (context/build-base! session)
        ctx (context/fork-context base)]
    (try
      (schema/register! :seon.host-registry-writer-test/note
                        [:string {:min 1}])
      (let [note-schema-rows
            (into []
                  (filter
                   #(= :seon.host-registry-writer-test/note
                       (:seon.schema/key %)))
                  (writer-test/canonical-schema-rows))
            seeded
            (writer-test/seed-canonical-schema!
             session database-name
             [{:seon.user/id "user"}
              {:seon.db.process/id :seon.db.process/repl}]
             note-schema-rows)]
        (is (true? (::protocol/success? seeded)) (pr-str seeded)))
      (let [installed
            (seed-schema-rows!
             session
             [{:seon.user/id "bootstrap"
               :seon.db/user [:seon.user/id "user"]
               :seon.db/process
               [:seon.db.process/id :seon.db.process/repl]}])]
        (is (true? (::protocol/success? installed)) (pr-str installed)))
      ;; Declare the note attribute's canonical schema (with the
      ;; self-describing `:seon.schema` rows a fresh database needs —
      ;; the same shapes the pod's compiled-program reconcile writes),
      ;; then commit one fact with a wrapper-generated op-id.
      (let [first-outcome
            (sci/eval-string*
             ctx
             (str "(require 'seon.db)"
                  "(seon.db/transact!"
                  " {:seon.db/tx-data"
                  "  [{:seon.host-registry-writer-test/note \"first\"}]})"))]
        (is (map? (:db-after first-outcome)) (pr-str first-outcome))
        (is (string? (:seon.capability/op-id first-outcome)))
        (is (not (contains? first-outcome :seon.capability/replayed?))))

      ;; The claimant wrapper delegates to the portable allocator rather
      ;; than reinterpreting its transaction-builder contract. In
      ;; particular, the builder returns a transaction request map; that
      ;; map must never be mistaken for transaction data at the writer
      ;; boundary.
      (let [allocated
            (sci/eval-string*
             ctx
             (str "(require '[seon.db.id :as id])"
                  "(let [database (seon.db/db)]"
                  "  (id/allocate!"
                  "   {:seon.db/db database"
                  "    :seon.db.id/allocations"
                  "    [{:seon.db.id/key :fixture/turn"
                  "      :seon.db.id/identity-attr :seon.agent.turn/id}]"
                  "    :seon.db.id/transaction-builder"
                  "    (fn [ids]"
                  "      {:seon.db/tx-data"
                  "       [{:seon.agent.turn/id (:fixture/turn ids)}]})}))"))]
        (is (map? (:db-after allocated)) (pr-str allocated))
        (is (string? (get-in allocated
                             [:seon.db.id/ids :fixture/turn]))
            (pr-str allocated))
        (is (= 1
               (sci/eval-string*
                ctx
                (str "(count"
                     " (seon.db/query"
                     "  (quote [:find ?e"
                     "          :where [?e :seon.agent.turn/id]])))")))))

      ;; A caller-supplied op-id commits once; the repeated call returns
      ;; the recorded outcome instead of re-executing.
      (let [outcome (sci/eval-string*
                     ctx (transact-note-form "alpha" "op-alpha"))
            replay (sci/eval-string*
                    ctx (transact-note-form "alpha" "op-alpha"))]
        (is (map? (:db-after outcome)) (pr-str outcome))
        (is (= "op-alpha" (:seon.capability/op-id outcome)))
        (is (not (contains? outcome :seon.capability/replayed?)))
        (is (map? (:db-after replay)) (pr-str replay))
        (is (true? (:seon.capability/replayed? replay)) (pr-str replay))
        (is (= 1 (note-count ctx "alpha"))))

      ;; Crash drill: the request is delivered and committed, then the
      ;; connection dies before the acknowledgement arrives. The retry
      ;; with the same op-id replays the receipt; the effect happened
      ;; exactly once.
      (let [head (context/resolve-head! session)
            raw-session (writer-test/open-session! request-path)
            ^SocketChannel raw (writer-test/channel raw-session)
            raw-output (Channels/newOutputStream raw)]
        (writer-test/call!
         raw-session
         (protocol/ensure-database-request
          {::protocol/request-id "op-crash/ensure"
           ::protocol/database-name database-name
           ::protocol/backend :memory}))
        (uds/write-frame!
         raw-output
         (protocol/transaction-request
          {::protocol/request-id "op-crash"
           :seon.db/db head
           ::protocol/transaction-data
           [{:seon.host-registry-writer-test/note "crash"}]
           ::protocol/transaction-meta
           {:seon.db/user [:seon.user/id "user"]
            :seon.db/process
            [:seon.db.process/id :seon.db.process/repl]}})
         (::protocol/maximum-frame-bytes raw-session))
        ;; Wait until the fact is committed (observed over the surviving
        ;; wrapper connection), then kill the raw connection so the
        ;; acknowledgement is lost mid-call.
        (let [deadline (+ (System/currentTimeMillis) 5000)]
          (loop []
            (when (and (zero? (note-count ctx "crash"))
                       (< (System/currentTimeMillis) deadline))
              (Thread/sleep 20)
              (recur))))
        (is (= 1 (note-count ctx "crash")))
        (try (.close raw) (catch Throwable _))
        (let [retry (sci/eval-string*
                     ctx (transact-note-form "crash" "op-crash"))]
          (is (map? (:db-after retry)))
          (is (true? (:seon.capability/replayed? retry)))
          (is (= 1 (note-count ctx "crash"))))
        ;; "Did it happen?" is answered by query — the receipt is the
        ;; writer's durable request-id fact on the transaction entity.
        (is (number?
             (sci/eval-string*
              ctx
              (str "(seon.db/query"
                   " (quote [:find ?tx . :in $ ?op"
                   "         :where [?tx :seon.db.protocol/request-id ?op]])"
                   " \"op-crash\")")))))
      (finally
        (context/close-session! session)
        (writer/stop! server)
        (.delete (File. ^String request-path))))))

;;; U4 — host-tier eval recording through the one corpus mechanism.

(def ^:private corpus-schema-rows
  "The loaded canonical schema population used by recording fixtures."
  (writer-test/canonical-schema-rows))

(def ^:private value-sampling-policy
  (merge
   {:seon.config/id "cluster"
    :seon.config.render/value-max-path-segments 32
    :seon.config.render/value-max-path-bytes 4096
    :seon.config.render/value-max-realized-items 1024
    :seon.config.render/value-max-depth 3
    :seon.config.render/value-max-string 80
    :seon.config.render/value-shape-sample 8
    :seon.config.render/value-max-items 12
    :seon.config.repair/level :symbols
    :seon.config.repair/max-fixes-per-form 1
    :seon.config.repair/budget-ms 50}
   writer-test/guard-policy))

(def ^:private value-sampling-policy-query
  '[:find [?path-segments ?path-bytes ?realized ?depth ?string ?shape ?items]
    :in $ ?id
    :where
    [?config :seon.config/id ?id]
    [?config :seon.config.render/value-max-path-segments ?path-segments]
    [?config :seon.config.render/value-max-path-bytes ?path-bytes]
    [?config :seon.config.render/value-max-realized-items ?realized]
    [?config :seon.config.render/value-max-depth ?depth]
    [?config :seon.config.render/value-max-string ?string]
    [?config :seon.config.render/value-shape-sample ?shape]
    [?config :seon.config.render/value-max-items ?items]])

(def ^:private parity-digest (apply str (repeat 64 "b")))

(defn- host-session! [host-socket agent-id database-name]
  (let [^SocketChannel channel (uds/connect! host-socket)
        output (Channels/newOutputStream channel)
        input (Channels/newInputStream channel)]
    (uds/write-frame!
     output
     {:seon.execution/protocol-version 3
      :seon.execution/agent-id agent-id
      :seon.execution/artifact-digest parity-digest
      :seon.execution/shadow-build-id "writer-test"
      :seon.execution/database-selection
      {:seon.db/socket-path "unused-by-the-host"
       :seon.db/database-name database-name}})
    {::channel channel ::output output ::input input
     ::ready (uds/read-frame input)}))

(defn- invoke-batch!
  [session agent-id turn-id invocation-id database parsed]
  (uds/write-frame!
   (::output session)
   {:seon.execution/message :seon.execution.message/invoke
    :seon.execution/protocol-version 3
    :seon.execution/agent-id agent-id
    :seon.execution/invocation-id invocation-id
    :seon.db/db database
    :seon.execution/function-identity
    {:seon.execution/function-symbol 'seon.execution.runtime/eval-batch!
     :seon.execution/artifact-digest parity-digest}
    :seon.execution/arguments
    [{:seon.eval/parsed parsed
      :seon.eval/starting-ns (symbol (str "my.agent." agent-id))
      :seon.agent.turn/id-of-turn turn-id}]
    :seon.execution/deadline-ms (+ (System/currentTimeMillis) 30000)
    :seon.execution/result-limit-bytes 1000000})
  (uds/read-frame (::input session)))

(deftest host-evals-record-the-same-corpus-data-as-the-child-tee
  (let [database-name (str "host-u4-" (random-uuid))
        request-path (socket-path "u4-writer")
        host-socket (socket-path "u4-host")
        agent-candidates
        (db.id/candidate-manifest
         {:seon.agent/id :seon.db.id.generator/human-readable
          :seon.agent.turn/id :seon.db.id.generator/compact}
         [{:seon.db.id/key :fixture/agent
           :seon.db.id/identity-attr :seon.agent/id}
          {:seon.db.id/key :fixture/caller
           :seon.db.id/identity-attr :seon.agent/id}
          {:seon.db.id/key :fixture/turn
           :seon.db.id/identity-attr :seon.agent.turn/id}])
        agent-id (:seon.db.id/value (first agent-candidates))
        caller-agent-id (:seon.db.id/value (second agent-candidates))
        turn-id (:seon.db.id/value (nth agent-candidates 2))
        server (writer-test/start! {::writer/dependencies (dependencies)
                               ::writer/database-name database-name
                               ::writer/backend :memory
                               ::writer/request-socket-path request-path})
        session (context/writer-session
                 {::context/writer-socket-path request-path
                  ::context/database-name database-name
                  ::context/backend :memory})
        base (context/build-base! session)
        seed-ctx (context/fork-context base)
        started (host/start! {::host/socket-path host-socket
                              ::context/writer-socket-path request-path
                              ::context/database-name database-name
                              ::context/backend :memory})
        query! (fn [form]
                 (sci/eval-string*
                  seed-ctx (str "(seon.db/query (quote " (pr-str form) "))")))]
    (try
      (register-runtime-schemas! corpus-schema-rows)
      (let [genesis
            (writer-test/seed-canonical-schema!
             session database-name
             [{:seon.user/id "user"}
              {:seon.db.process/id :seon.db.process/repl}])]
        (is (true? (::protocol/success? genesis)) (pr-str genesis)))
      (let [database (db.host/resolve-db! session nil false)
            allocated
            (db.host/call!
             session
             (protocol/transaction-request
              {::protocol/request-id (str (random-uuid))
               :seon.db/db database
               ::protocol/transaction-data
               [{:seon.agent/id agent-id}
                {:seon.agent/id caller-agent-id}
                {:seon.agent.turn/id turn-id}]
               ::protocol/generated-candidates agent-candidates}))]
        (is (true? (::protocol/success? allocated)) (pr-str allocated)))
      (let [installed (seed-schema-rows!
                       session
                       [{:seon.user/id "bootstrap"
                         :seon.db/user [:seon.user/id "user"]
                         :seon.db/process
                         [:seon.db.process/id :seon.db.process/repl]}])]
        (is (true? (::protocol/success? installed)) (pr-str installed)))
      ;; Seed the self-describing schema rows, the agent, the process
      ;; identity, and the owning turn.
      (let [seeded (sci/eval-string*
                    seed-ctx
                    (str "(require 'seon.db)"
                         "(seon.db/transact! {:seon.db/tx-data "
                         (pr-str [value-sampling-policy
                                  {:seon.agent/id agent-id}
                                        {:seon.agent/id caller-agent-id}
                                  {:seon.agent.turn/id turn-id}])
                         "})"))]
        (is (map? (:db-after seeded)) (pr-str seeded)))
      (is (:seon.db/ok?
           (context/transact-writer!
            session
            [{:seon.ns/name 'my.corpus-only
              :seon.ns/source
              (str "(ns my.corpus-only "
                   "(:require [my.shared :as shared]))")
              :seon.ns/require-edges
              [{:seon.ns.require/target 'my.shared
                :seon.ns.require/alias 'shared}]}]))
          "the corpus-only namespace source is ordinary database data")
      (is (= [32 4096 1024 3 80 8 12]
             (context/query-writer! session value-sampling-policy-query
                                    ["cluster"])))
      ;; Attributes install on first tx-data ASSERTION; the live database
      ;; installs these at genesis, and the recorder's exact-set
      ;; retractAttribute ops name optional attrs that must already be
      ;; installed. One probe row asserts each once.
      (let [probe (sci/eval-string*
                   seed-ctx
                   (str "(seon.db/transact! {:seon.db/tx-data "
                        (pr-str [{:seon.db/user [:seon.agent/id agent-id]
                                  :seon.db/process
                                  [:seon.db.process/id
                                   :seon.db.process/repl]}
                                 {:seon.fn/sym "seed/install-probe"
                                  :seon.fn/spec "[:=> [:cat :int] :int]"
                                  :seon.fn/schema-error "none"
                                  :seon.fn/read-attrs [:seed/attr]}])
                        "})"))]
        (is (map? (:db-after probe)) (pr-str probe)))
      (let [head (context/resolve-head! session)
            live (host-session! host-socket agent-id database-name)
            caller-live
            (host-session! host-socket caller-agent-id database-name)]
        (try
          (is (= :seon.execution.message/ready
                 (:seon.execution/message (::ready live)))
              (pr-str (::ready live)))
          (let [response
                (invoke-batch!
                 live agent-id turn-id "parity-invoke-1" head
                 [{:seon.repl/kind :form
                   :seon.repl/source
                   (str "(defn parity-double \"Double x.\" "
                        "{:malli/schema [:=> [:catn [:x :int]] :int]} "
                        "[x] (* 2 x))")}
                  {:seon.repl/kind :form
                   :seon.repl/source "(parity-double 21)"}
                  {:seon.repl/kind :form
                   :seon.repl/source
                   "(schema/register! :my.parity/amount [:int {:min 0}])"}])
                result (:seon.execution/result response)]
            (is (= :seon.execution.message/result
                   (:seon.execution/message response))
                (pr-str response))
            (is (= 3 (:seon.eval/n-ok result)) (pr-str result))
            (is (= 3 (count (:seon.eval/ids result)))
                "every executed form records one eval receipt")
            (is (= 42 (get-in result [:seon.host/results 1
                                      :seon.eval/value])))
            ;; Eval rows: receipts terminalized under the owning turn
            ;; with the agent connection and the frozen outcome.
            (let [rows (query!
                        `[:find ?source ?ok ?status ?edn
                          :where
                          [?turn :seon.agent.turn/id ~turn-id]
                          [?turn :seon.agent.turn/evals ?eval]
                          [?eval :seon.eval/source ?source]
                          [?eval :seon.eval/ok? ?ok]
                          [?eval :seon.eval/status ?status]
                          [?eval :seon.eval/result-edn ?edn]])]
              (is (= 3 (count rows)) (pr-str rows))
              (is (contains? (set (map second rows)) true))
              (is (contains? (set rows)
                             ["(parity-double 21)" true :done "42"])))
            (is (= 3 (count (query!
                             [:find '?eval
                              :where
                              ['?eval :seon.eval/agent
                               [:seon.agent/id agent-id]]])))
                "eval rows carry the agent connection")
            ;; The :seon.fn row: the def is a corpus citizen.
            (let [fn-row (first
                          (query!
                           [:find '?source '?arglists '?doc '?ns-sym
                             :where
                            ['?fn :seon.fn/sym
                             (str "my.agent." agent-id "/parity-double")]
                            ['?fn :seon.fn/source '?source]
                            ['?fn :seon.fn/arglists '?arglists]
                            ['?fn :seon.fn/doc '?doc]
                            ['?fn :seon.fn/ns '?ns]
                            ['?ns :seon.ns/name '?ns-sym]]))]
              (is (= (str "(defn parity-double \"Double x.\" "
                          "{:malli/schema [:=> [:catn [:x :int]] :int]} "
                          "[x] (* 2 x))")
                     (first fn-row)))
              (is (= "([x])" (second fn-row)) (pr-str fn-row))
              (is (= "Double x." (nth fn-row 2)))
              (is (= (symbol (str "my.agent." agent-id))
                     (nth fn-row 3))))
            ;; The :seon.schema row: register! admission plus its tee.
            (is (= [[":my.parity/amount" "[:int {:min 0}]"]]
                   (mapv (fn [[k form]] [(pr-str k) form])
                         (query!
                          '[:find ?key ?form
                            :where
                            [?row :seon.schema/key :my.parity/amount]
                            [?row :seon.schema/key ?key]
                            [?row :seon.schema/form ?form]])))))
          (let [authored
                (invoke-batch!
                 live agent-id turn-id "live-require-author"
                 (context/resolve-head! session)
                 [{:seon.repl/kind :form
                   :seon.repl/source "(ns my.shared)"}
                  {:seon.repl/kind :form
                   :seon.repl/source
                   (str "(defn f "
                        "{:malli/schema [:=> [:cat :int] :int]} "
                        "[x] (inc x))")}
                  {:seon.repl/kind :form
                   :seon.repl/source
                   (str "(ns my.consumer "
                        "(:require [my.shared :as shared]))")}
                  {:seon.repl/kind :form
                   :seon.repl/source
                   (str "(defn call-f "
                        "{:malli/schema [:=> [:cat :int] :int]} "
                        "[x] (shared/f x))")}])
                caller
                (invoke-batch!
                 caller-live caller-agent-id turn-id "live-require-caller"
                 (context/resolve-head! session)
                 [{:seon.repl/kind :form
                   :seon.repl/source
                   "(require '[my.shared :as shared])"}
                  {:seon.repl/kind :form
                   :seon.repl/source "(shared/f 4)"}
                  {:seon.repl/kind :form
                   :seon.repl/source
                   "(require '[my.consumer :as consumer])"}
                  {:seon.repl/kind :form
                   :seon.repl/source "(consumer/call-f 8)"}
                  {:seon.repl/kind :form
                   :seon.repl/source "(shared/f :wrong)"}
                  {:seon.repl/kind :form
                   :seon.repl/source "(require 'my.corpus-only)"}
                  {:seon.repl/kind :form
                   :seon.repl/source
                   "(boolean (get (ns-aliases 'my.corpus-only) 'shared))"}])
                caller-results
                (get-in caller [:seon.execution/result
                                :seon.host/results])]
            (is (= 4 (get-in authored [:seon.execution/result
                                       :seon.eval/n-ok]))
                (pr-str authored))
            (is (= 6 (get-in caller [:seon.execution/result
                                     :seon.eval/n-ok]))
                (pr-str caller))
            (is (= 1 (get-in caller [:seon.execution/result
                                     :seon.eval/n-fail]))
                (pr-str caller))
            (is (= 5 (get-in caller-results [1 :seon.eval/value]))
                "agent B requires and calls agent A's live registry var")
            (is (= 9 (get-in caller-results [3 :seon.eval/value]))
                "a cold consumer namespace closes over its authored require")
            (is (re-find #"malli/instrument-input.*my.shared/f"
                         (get-in caller-results
                                 [4 :seon/error :seon.error/message]))
                (pr-str (nth caller-results 4)))
            (is (true? (get-in caller-results [6 :seon.eval/value]))
                "a registry miss loads stored namespace source and its edge"))
          (finally
            (try (.close ^SocketChannel (::channel live))
                 (catch Throwable _))
            (try (.close ^SocketChannel (::channel caller-live))
                 (catch Throwable _)))))
      ;; Restart drill: a brand-new host process state (fresh base, no
      ;; contexts) restores the agent by replaying the corpus defs — the
      ;; U1.5 turn-5 gap closes.
      (host/stop! started)
      (let [restarted (host/start!
                       {::host/socket-path host-socket
                        ::context/writer-socket-path request-path
                        ::context/database-name database-name
                        ::context/backend :memory})
            head (context/resolve-head! session)
            live (host-session! host-socket agent-id database-name)]
        (try
          (let [response (invoke-batch!
                          live agent-id turn-id "parity-invoke-2" head
                          [{:seon.repl/kind :form
                            :seon.repl/source "(parity-double 4)"}])]
            (is (= 8 (get-in response [:seon.execution/result
                                       :seon.host/results 0
                                       :seon.eval/value]))
                (str "pre-restart def must replay from the corpus: "
                     (pr-str response))))
          (finally
            (try (.close ^SocketChannel (::channel live))
                 (catch Throwable _))
            (host/stop! restarted))))
      (finally
        (context/close-session! session)
        (try (host/stop! started) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. ^String request-path))
        (.delete (File. ^String host-socket))))))

(deftest uninstalled-attribute-query-is-no-fact-while-pull-rejects
  ;; The execution tier lookup (`seon.execution.host`) and every other
  ;; presence read of an OPTIONAL registered attribute rely on this
  ;; boundary contract: on a database where the attribute was never
  ;; transacted (so never installed), a Datalog query treats it as zero
  ;; datoms — no fact, not an error — while a pull SELECTOR naming it is
  ;; rejected by the engine. The tier dispatch therefore queries; if this
  ;; contract ever changes, the lookup must be redesigned with it
  ;; (issue eval-host-tier-pull-fails-on-uninstalled-schema).
  (let [database-name (str "host-registry-" (random-uuid))
        request-path (socket-path "uninstalled")
        server (writer-test/start! {::writer/dependencies (dependencies)
                               ::writer/database-name database-name
                               ::writer/backend :memory
                               ::writer/request-socket-path request-path})
        session (context/writer-session
                 {::context/writer-socket-path request-path
                  ::context/database-name database-name
                  ::context/backend :memory})
        base (context/build-base! session)
        ctx (context/fork-context base)]
    (try
      (let [seeded
            (writer-test/seed-canonical-schema!
             session database-name [])]
        (is (true? (::protocol/success? seeded)) (pr-str seeded)))
      (is (nil? (sci/eval-string*
                 ctx
                 (str "(seon.db/query"
                      " (quote [:find ?v ."
                      "         :where [?e"
                      "                 :seon.host-registry-writer-test.uninstalled/value"
                      "                 ?v]]))")))
          "query over the uninstalled attribute is NO FACT, never an error")
      (let [pulled (sci/eval-string*
                    ctx
                    (str "(seon.db/pull"
                         " [:seon.host-registry-writer-test.uninstalled/value]"
                         " [:seon.schema/key :seon.schema/form])"))]
        (is (string? (:seon.error/message pulled))
            "a pull selector naming the uninstalled attribute is rejected"))
      (finally
        (context/close-session! session)
        (writer/stop! server)
        (.delete (File. ^String request-path))))))
