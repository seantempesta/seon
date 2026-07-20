(ns seon.host.context
  "Own the JVM agent host's shared sci base and per-agent contexts.

   One base context is built once per host process: the portable pure slice
   of the `my.*` toolkit loaded from its real sources, plus every capability
   namespace provisioned through the ONE wrapper registry
   ([[register-wrappers!]]). The registry backs the base's sci `:load-fn`:
   registering a namespace makes it lazily require-able in EVERY live
   context (the load-fn closure is shared by all forks — probed in the seam
   study), first require injects the cached wrapper vars, and re-registering
   an implementation upgrades the shared vars in place so existing
   contexts' next calls use it. The `seon.db` wrappers are synchronous UDS
   round-trips to the cluster writer through the one existing
   `seon.db.transport.uds` client. Every agent context is a `sci/fork` of
   that base (persistent-structure sharing; forked defs stay private).

   Effectful capability calls carry `:seon.capability/op-id`. For
   `seon.db/transact!` the op-id IS the database protocol's durable
   idempotency receipt: it crosses the boundary as
   `:seon.db.protocol/request-id`, the writer stamps it on the committed
   transaction entity, and a repeated call with the same op-id returns the
   recorded outcome (`:seon.capability/replayed? true`) instead of
   re-executing — no second receipt entity exists.

   The durable agent is database facts. A context is a cache of those
   facts: park drops it, restore forks the base and replays the agent's def
   sources through [[replay-defs!]].

   TODO SEAM (recorded, deliberately unbuilt — owner:
   sci-execution-runtime U4 with `seon.eval`'s corpus machinery):
   - def persistence: successful defs evaluated here must tee into the one
     `:seon.fn`/`:seon.ns` program corpus exactly as
     `seon.eval/eval-batch!` records them today. Until that tee exists the
     caller supplies replay sources from the corpus it already holds.
   - `seon.schema/register!` inside a context records the request and
     returns nil; real admission (validator compilation + Datahike bridge)
     is the same U4 unit."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sci.core :as sci]
            [sci.interrupt :as interrupt]
            [seon.ai.tokens :as tokens]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.schema :as schema]))

(set! *warn-on-reflection* true)

(schema/register! ::writer-socket-path [:string {:min 1}])
(schema/register! ::database-name ::protocol/database-name)
(schema/register! ::backend ::protocol/backend)
(schema/register! ::database-path ::protocol/database-path)
(schema/register! ::channel-state 'some?)
(schema/register! ::call-lock 'some?)
(schema/register!
 ::writer
 [:map
  [::writer-socket-path ::writer-socket-path]
  [::database-name ::database-name]
  [::backend {:optional true} ::backend]
  [::database-path {:optional true} ::database-path]
  [::channel-state ::channel-state]
  [::call-lock ::call-lock]])
(schema/register! ::ctx 'some?)
(schema/register! ::registry 'some?)
(schema/register! ::lib :symbol)
(schema/register! ::wrapper-fn 'fn?)
(schema/register! ::arglists [:sequential [:vector :symbol]])
(schema/register! ::doc [:string {:min 1}])
(schema/register!
 ::wrapper
 [:map {:closed true}
  [::wrapper-fn ::wrapper-fn]
  [::arglists {:optional true} ::arglists]
  [::doc {:optional true} ::doc]])
(schema/register! ::wrappers [:map-of :symbol ::wrapper])
(schema/register!
 ::register-request
 [:map {:closed true}
  [::registry ::registry]
  [::lib ::lib]
  [::wrappers ::wrappers]])
(schema/register! :seon.capability/op-id [:string {:min 1}])
(schema/register! :seon.capability/replayed? :boolean)
(schema/register! ::files [:int {:min 0}])
(schema/register! ::pure-blocks [:int {:min 0}])
(schema/register! ::loaded [:int {:min 0}])
(schema/register! ::failed [:int {:min 0}])
(schema/register!
 ::failures
 [:vector [:map {:closed true}
           [::block-name :string]
           [::failure :string]]])
(schema/register!
 ::report
 [:map {:closed true}
  [::files ::files]
  [::pure-blocks ::pure-blocks]
  [::loaded ::loaded]
  [::failed ::failed]
  [::failures ::failures]])
(schema/register!
 ::base
 [:map {:closed true}
  [::ctx ::ctx]
  [::report ::report]
  [::registry ::registry]])
(schema/register! ::def-sources [:vector :string])
(schema/register!
 ::replay-envelope
 [:map
  [:seon.eval/ok? :boolean]])
(schema/register! ::replay-envelopes [:vector ::replay-envelope])

(defn writer-session
  "Build one host writer session over one retained physical connection.

   The writer scopes database access to physical connections and releases
   the shared indexes when the last one closes, so the host keeps ONE
   standing channel (the connection is genuinely process-local state) and
   serializes its synchronous round-trips — C1 measured ~2 ms per call."
  {:malli/schema [:=> [:cat [:map [::writer-socket-path ::writer-socket-path]
                             [::database-name ::database-name]
                             [::backend {:optional true} ::backend]
                             [::database-path {:optional true}
                              ::database-path]]]
                  ::writer]}
  [{::keys [writer-socket-path database-name backend database-path]}]
  (cond-> {::writer-socket-path writer-socket-path
           ::database-name database-name
           ::channel-state (atom nil)
           ::call-lock (Object.)}
    backend (assoc ::backend backend)
    database-path (assoc ::database-path database-path)))

(defn close-session!
  "Close the writer session's retained connection."
  {:malli/schema [:=> [:cat ::writer] :nil]}
  [{::keys [channel-state]}]
  (when-let [channel @channel-state]
    (reset! channel-state nil)
    (try (.close ^java.nio.channels.SocketChannel channel)
         (catch Throwable _)))
  nil)

(defn- writer-call!
  "One serialized round-trip on the retained connection; reconnect once."
  [{::keys [writer-socket-path channel-state call-lock]} request]
  (locking call-lock
    (let [call (fn []
                 (let [channel (or @channel-state
                                   (reset! channel-state
                                           (uds/connect! writer-socket-path)))]
                   (uds/call! {::uds/channel channel ::uds/message request})))]
      (try
        (call)
        (catch Throwable _
          (when-let [channel @channel-state]
            (reset! channel-state nil)
            (try (.close ^java.nio.channels.SocketChannel channel)
                 (catch Throwable _)))
          (call))))))

(defn- protocol-error-value
  [response]
  {:seon/error
   {:seon.error/message
    (str "The database writer rejected the call: "
         (or (::protocol/error response) (::protocol/error-kind response)))
    :seon.error/kind :agent
    :seon.error/data (select-keys response [::protocol/error-kind])}})

(defn- ensure-database!
  "Ensure the configured database on the writer; explicit config only.

   The writer releases a database when its last physical connection
   closes; ensuring re-opens it. The backend and path come ONLY from the
   host's explicit configuration (the same facts the child's
   database-selection carries) — never guessed from a name."
  [{::keys [database-name backend database-path] :as writer}]
  (writer-call!
   writer
   (protocol/ensure-database-request
    (cond-> {::protocol/request-id (str (random-uuid))
             ::protocol/database-name database-name
             ::protocol/backend backend}
      database-path (assoc ::protocol/database-path database-path)))))

(defn resolve-head!
  "Resolve the writer's current database value for the host's database.

   A not-found answer for a configured backend means the writer released
   the database; one explicit ensure re-opens it before the retry."
  {:malli/schema [:=> [:cat ::writer] :map]}
  [{::keys [database-name backend] :as writer}]
  (let [resolve-once
        (fn []
          (writer-call!
           writer
           (protocol/resolve-head-request
            {::protocol/request-id (str (random-uuid))
             ::protocol/database-name database-name})))
        response (resolve-once)
        response (if (and backend
                          (not (::protocol/success? response))
                          (= :seon.db.protocol.error/not-found
                             (::protocol/error-kind response)))
                   (do (ensure-database! writer) (resolve-once))
                   response)]
    (if (::protocol/success? response)
      (:seon.db/db response)
      (protocol-error-value response))))

(defn- db-query
  "Context `seon.db/query`: one blocking Datalog read at the current head."
  [writer query-form & arguments]
  (let [head (resolve-head! writer)]
    (if (:seon/error head)
      head
      (let [response (writer-call!
                      writer
                      (protocol/query-request
                       {::protocol/request-id (str (random-uuid))
                        :seon.db/db head
                        ::protocol/query-form query-form
                        ::protocol/arguments (vec arguments)}))]
        (if (::protocol/success? response)
          (:datahike.query/result response)
          (protocol-error-value response))))))

(defn- db-pull
  "Context `seon.db/pull`: one blocking pull read at the current head."
  [writer selector entity-id]
  (let [head (resolve-head! writer)]
    (if (:seon/error head)
      head
      (let [response (writer-call!
                      writer
                      (protocol/pull-request
                       {::protocol/request-id (str (random-uuid))
                        :seon.db/db head
                        ::protocol/selector selector
                        ::protocol/entity-id entity-id}))]
        (if (::protocol/success? response)
          (::protocol/result response)
          (protocol-error-value response))))))

(defn- receipt-basis
  "Basis transaction of the committed receipt for `op-id`, or nil.

   The receipt is the writer's own durable idempotency fact: the
   `:seon.db.protocol/request-id` datom stamped on every committed
   transaction entity. Its presence answers \"did it happen?\" by query;
   its basis transaction is the completed-at fact — nothing extra is
   stored."
  [writer head op-id]
  (let [response (writer-call!
                  writer
                  (protocol/query-request
                   {::protocol/request-id (str (random-uuid))
                    :seon.db/db head
                    ::protocol/query-form
                    '[:find ?transaction .
                      :in $ ?op-id
                      :where [?transaction :seon.db.protocol/request-id
                              ?op-id]]
                    ::protocol/arguments [op-id]}))]
    (if (::protocol/success? response)
      {::receipt-transaction (:datahike.query/result response)}
      (protocol-error-value response))))

(defn- replayed-outcome
  "Recorded outcome for an op-id whose receipt already committed."
  [head op-id receipt-transaction]
  {:seon.db/ok? true
   :seon.capability/op-id op-id
   :seon.capability/replayed? true
   :db-after (cond-> (select-keys head [:db-name :t :datahike/commit-id])
               (< receipt-transaction (:t head))
               (assoc :as-of receipt-transaction))})

(defn- db-transact!
  "Context `seon.db/transact!`: one exactly-once write at the current head.

   Accepts the pod's shapes — raw transaction data, or a map carrying
   `:seon.db/tx-data` plus an optional `:seon.capability/op-id`. The
   wrapper generates the op-id when absent and sends it as the protocol's
   `::protocol/request-id`, so the writer's durable idempotency receipt
   makes the retained-connection resend in [[writer-call!]] safe: a
   connection killed between commit and acknowledgement recovers the
   recorded outcome instead of re-executing. A caller-supplied op-id is
   first checked against the receipt so an agent-level retry after any
   crash also returns the recorded outcome (`:seon.capability/replayed?
   true`) exactly once."
  [writer request]
  (let [{tx-data :seon.db/tx-data op-id :seon.capability/op-id}
        (if (and (map? request) (contains? request :seon.db/tx-data))
          request
          {:seon.db/tx-data (vec request)})
        supplied-op-id? (some? op-id)
        op-id (or op-id (str (random-uuid)))
        head (resolve-head! writer)]
    (if (:seon/error head)
      head
      (let [receipt (when supplied-op-id?
                      (receipt-basis writer head op-id))]
        (cond
          (:seon/error receipt)
          receipt

          (some? (::receipt-transaction receipt))
          (replayed-outcome head op-id (::receipt-transaction receipt))

          :else
          (let [response (writer-call!
                          writer
                          (protocol/transaction-request
                           {::protocol/request-id op-id
                            :seon.db/db head
                            ::protocol/transaction-data (vec tx-data)}))]
            (if (::protocol/success? response)
              (cond-> {:seon.db/ok? true
                       :seon.capability/op-id op-id
                       :db-after (select-keys (:db-after response)
                                              [:db-name :t
                                               :datahike/commit-id])
                       :tempids (:tempids response)}
                (::protocol/recovered? response)
                (assoc :seon.capability/replayed? true))
              (protocol-error-value response))))))))

;;; Wrapper registry — the ONE capability-provisioning mechanism.

(defn registry
  "Create one empty wrapper registry for one host base.

   Process-local derived state: a restart rebuilds it by re-registration
   from the host's configuration, never from persistence."
  {:malli/schema [:=> [:cat] ::registry]}
  []
  (atom {}))

(defn register-wrappers!
  "Register or upgrade one capability namespace's wrapper vars.

   Registering a namespace makes it lazily require-able in EVERY live
   context: the registry backs the shared `:load-fn` closure, so first
   require injects the cached wrapper vars with `:arglists`/`:doc` live
   on real sci vars. Re-registering a function alters the shared var's
   root in place, so every context that already required the namespace
   uses the new implementation on its next call (the var-epoch upgrade
   property; plain var alteration on the JVM interpreter)."
  {:malli/schema [:=> [:cat ::register-request] :nil]}
  [{::keys [registry lib wrappers]}]
  (swap! registry
         (fn [entries]
           (let [entry (get entries lib)
                 sci-ns (or (::sci-ns entry) (sci/create-ns lib))
                 vars
                 (reduce-kv
                  (fn [acc fn-sym {::keys [wrapper-fn arglists doc]}]
                    (if-let [live (get acc fn-sym)]
                      (do (sci/alter-var-root live (constantly wrapper-fn))
                          acc)
                      (assoc acc fn-sym
                             (sci/new-var
                              fn-sym wrapper-fn
                              (cond-> {:ns sci-ns :name fn-sym}
                                arglists (assoc :arglists arglists)
                                doc (assoc :doc doc))))))
                  (or (::vars entry) {})
                  wrappers)]
             (assoc entries lib {::sci-ns sci-ns ::vars vars}))))
  nil)

(defn- registry-load-fn
  "Shared sci `:load-fn` over the registry; injects wrappers on require.

   Called by sci only on the FIRST require of an unknown lib. The body is
   a map lookup plus an env swap — it must stay that cheap because the
   JVM require path holds one process-global load lock. Returning `{}`
   (no source) leaves the `:as`/`:refer` wiring to sci itself."
  [registry]
  (fn [{:keys [libname ctx]}]
    (when-let [vars (get-in @registry [libname ::vars])]
      (swap! (:env ctx) assoc-in [:namespaces libname] vars)
      {})))

(defn- register-host-capabilities!
  "Seed the registry with the host's capability families over `writer`.

   This is the one provisioning path: `seon.db` reads/writes close over
   the pure-data writer boundary, `seon.schema` and `seon.ai.tokens` wrap
   the compiled host functions. Restart re-registers from configuration;
   nothing here persists."
  [registry writer]
  (register-wrappers!
   {::registry registry
    ::lib 'seon.db
    ::wrappers
    {'query {::wrapper-fn (partial db-query writer)
             ::arglists '([query-form & arguments])
             ::doc "Run one Datalog read at the writer's current head."}
     'pull {::wrapper-fn (partial db-pull writer)
            ::arglists '([selector entity-id])
            ::doc "Pull one entity's selection at the current head."}
     'transact! {::wrapper-fn (partial db-transact! writer)
                 ::arglists '([request])
                 ::doc "Commit transaction data exactly once; an op-id retry replays the receipt."}
     'head {::wrapper-fn (partial resolve-head! writer)
            ::arglists '([])
            ::doc "Resolve the writer's current database value."}}})
  (register-wrappers!
   {::registry registry
    ::lib 'seon.schema
    ::wrappers
    {'validate {::wrapper-fn (fn [schema-key value]
                               (schema/valid-candidate-value? schema-key
                                                              value))
                ::arglists '([schema-key value])
                ::doc "True when the value satisfies the registered schema."}
     ;; TODO SEAM (U4): real admission through the one
     ;; `seon.schema/register!` bridge; recording only for now.
     'register! {::wrapper-fn (fn [_key _schema] nil)
                 ::arglists '([schema-key schema])
                 ::doc "Record a schema registration request (admission is the U4 seam)."}}})
  (register-wrappers!
   {::registry registry
    ::lib 'seon.ai.tokens
    ::wrappers
    {'estimate {::wrapper-fn tokens/estimate
                ::arglists '([value])
                ::doc "Estimated token size of one value."}
     'estimate-chars {::wrapper-fn tokens/estimate-chars
                      ::arglists '([character-count])
                      ::doc "Estimated token size of a character count."}}}))

;;; Portable `my.*` slice, loaded from the real sources.

(def ^:private my-source-files
  ["src/my/data.cljs" "src/my/plan.cljs" "src/my/kb.cljs" "src/my/ns.cljs"
   "src/my/canvas.cljs" "src/my/ui.cljs" "src/my/skills.cljs"
   "src/my/blob.cljs"])

(defn- defn-blocks
  "Top-level defn blocks of one source string."
  [source]
  (let [lines (vec (str/split-lines source))
        tops (vec (keep-indexed
                   (fn [index line]
                     (when (re-find #"^\((defn|def )" line) index))
                   lines))]
    (for [[from to] (map vector tops (concat (rest tops) [(count lines)]))
          :let [block (str/join "\n" (subvec lines from to))]
          :when (str/starts-with? block "(defn")]
      block)))

(defn- pure-block?
  "True when a defn block has no async, js-interop, or db-boundary marker."
  [block]
  (not (re-find #"\^:async|\(await |js/|#js|\(\.\-|\(\. |\(\.[a-zA-Z]|db/transact!|db/query|db/pull|db/entity|db/db\b|blob/"
                block)))

(defn- block-name [block]
  (or (second (re-find #"\(defn-? \^?[:a-z]*\s*([^\s]+)" block)) "unknown"))

(defn- file-ns-name [path]
  (-> path
      (str/replace #"^src/" "")
      (str/replace #"\.cljs$" "")
      (str/replace "/" ".")
      symbol))

(defn- load-portable-slice!
  "Eval every pure `my.*` defn block from its real source into `ctx`.

   Returns the honest ledger: block counts plus each failure's first error
   line. Failures are references to impure private helpers the pure slice
   does not carry, recorded — never silently skipped."
  [ctx]
  (let [loads
        (vec
         (for [path my-source-files
               :let [ns-sym (file-ns-name path)
                     source (slurp (io/file path))
                     pure (filterv pure-block? (defn-blocks source))]]
           (do
             ;; A synthetic ns form stands in for the production
             ;; augment-ns-source aliases, pointed at the host namespaces.
             (sci/eval-string*
              ctx (str "(ns " ns-sym
                       " (:require [clojure.string :as str]"
                       " [clojure.set :as set]"
                       " [clojure.edn :as edn]"
                       " [clojure.walk :as walk]"
                       " [seon.db :as db]"
                       " [seon.schema :as schema]"
                       " [seon.ai.tokens :as tokens]))"))
             (reduce
              (fn [tally block]
                (let [outcome
                      (try (sci/eval-string*
                            ctx (str "(in-ns '" ns-sym ")\n" block))
                           ::ok
                           (catch Throwable throwable
                             (first (str/split-lines
                                     (str (.getMessage throwable))))))]
                  (if (= ::ok outcome)
                    (update tally ::loaded inc)
                    (update tally ::failures conj
                            {::block-name (block-name block)
                             ::failure (str outcome)}))))
              {::pure-blocks (count pure) ::loaded 0 ::failures []}
              pure))))]
    {::files (count my-source-files)
     ::pure-blocks (reduce + (map ::pure-blocks loads))
     ::loaded (reduce + (map ::loaded loads))
     ::failed (reduce + (map (comp count ::failures) loads))
     ::failures (into [] (mapcat ::failures) loads)}))

(defn build-base!
  "Build the one shared base context for a host serving one cluster.

   Every capability namespace provisions through the one wrapper
   registry: [[register-host-capabilities!]] seeds it from the writer
   coordinates, and the registry-backed `:load-fn` serves first requires
   lazily in the base and every fork. The portable `my.*` pure slice
   loads from its real sources (its requires exercise that lazy path).
   The returned report is the honest real-vs-failed load ledger."
  {:malli/schema [:=> [:cat ::writer] ::base]}
  [writer]
  (let [wrapper-registry (registry)
        _ (register-host-capabilities! wrapper-registry writer)
        ctx (sci/init
             {:load-fn (registry-load-fn wrapper-registry)
              :interrupt-fn
              (fn []
                (when (.isInterrupted (Thread/currentThread))
                  (interrupt/interrupt! "eval deadline exceeded")))})
        report (load-portable-slice! ctx)]
    {::ctx ctx ::report report ::registry wrapper-registry}))

(defn fork-context
  "Fork one private agent context from the shared base."
  {:malli/schema [:=> [:cat ::base] ::ctx]}
  [{::keys [ctx]}]
  (sci/fork ctx))

(defn replay-defs!
  "Replay def sources into a context; restore = fork base + this replay.

   Each source string evaluates in order; every outcome is a value. The
   sources come from the one program corpus the caller holds — teeing NEW
   defs back into that corpus is the recorded U2 seam, not this function."
  {:malli/schema [:=> [:cat ::ctx ::def-sources] ::replay-envelopes]}
  [ctx def-sources]
  (mapv (fn [source]
          (try
            {:seon.eval/ok? true
             :seon.eval/value (sci/eval-string* ctx source)}
            (catch Throwable throwable
              {:seon.eval/ok? false
               :seon/error {:seon.error/message
                            (str (first (str/split-lines
                                         (str (.getMessage throwable)))))
                            :seon.error/kind :agent}})))
        def-sources))
