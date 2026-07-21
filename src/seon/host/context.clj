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
   facts: park drops it, restore forks the base and replays the agent's
   home-ns corpus def sources ([[restore-context-defs!]] over
   [[agent-def-sources]] + [[replay-defs!]]).

   Recording (U4): [[start-eval-receipt!]] allocates a managed
   `:seon.eval/id` through the wire protocol's generated-candidates
   field and commits the `:running` receipt before a form may run;
   [[record-eval-terminal!]] terminalizes behind the receipt's CAS fence
   in ONE transaction with every program-graph row the form tees
   (`seon.host.record` builds the exact data the child tee writes).
   `seon.schema/register!` admits for real through the one bridge, and
   the registry diff around each form tees the canonical `:seon.schema`
   row."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sci.core :as sci]
            [sci.interrupt :as interrupt]
            [seon.ai.tokens :as tokens]
            [seon.db.id :as db.id]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.host.record :as record]
            [seon.schema :as schema]))

(set! *warn-on-reflection* true)

(def ^:dynamic *agent-id*
  "The invocation's agent identity, bound around every host eval.

   Threads per-agent provenance through the shared wrapper closures:
   reads carry `:seon.db/user`/`:seon.db/process` so the writer's read
   spend attributes to the exact agent instead of the empty identity,
   and writes carry the same two references as transaction metadata —
   the one provenance vocabulary the pod stamps."
  nil)

(defn- provenance
  "The two durable provenance references for the bound agent, or nil."
  []
  (when *agent-id*
    {:seon.db/user [:seon.agent/id *agent-id*]
     :seon.db/process [:seon.db.process/id :seon.db.process/repl]}))

(defn- with-read-provenance
  "Attach the bound agent's read provenance to one protocol request."
  [request]
  (merge request (provenance)))

(schema/register! ::writer-socket-path [:string {:min 1}])
(schema/register! ::database-name ::protocol/database-name)
(schema/register! ::backend ::protocol/backend)
(schema/register! ::database-path ::protocol/database-path)
(schema/register! ::channel-state 'some?)
(schema/register! ::call-lock 'some?)
(schema/register! ::eval-generator 'some?)
(schema/register!
 ::writer
 [:map
  [::writer-socket-path ::writer-socket-path]
  [::database-name ::database-name]
  [::backend {:optional true} ::backend]
  [::database-path {:optional true} ::database-path]
  [::channel-state ::channel-state]
  [::call-lock ::call-lock]
  [::eval-generator ::eval-generator]])
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
           ::call-lock (Object.)
           ::eval-generator (atom nil)}
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

(defn- db-query-with-evidence
  "Context `seon.db/query-with-evidence`: one read plus its own cost.

   Mirrors the pod surface of the same name: the returned map carries the
   result together with the writer's dependency, cache, and resource
   evidence, so an agent can see what its own query charged."
  [writer query-form & arguments]
  (let [head (resolve-head! writer)]
    (if (:seon/error head)
      head
      (let [response (writer-call!
                      writer
                      (protocol/query-request
                       (with-read-provenance
                         {::protocol/request-id (str (random-uuid))
                          :seon.db/db head
                          ::protocol/query-form query-form
                          ::protocol/arguments (vec arguments)})))]
        (if (::protocol/success? response)
          (select-keys response
                       [:datahike.query/result
                        :datahike.read/dependency-plan
                        :datahike.query/attribute-dependencies
                        :datahike.query/cache-evidence
                        :datahike.query/resource-evidence])
          (protocol-error-value response))))))

(defn- db-query
  "Context `seon.db/query`: one blocking Datalog read at the current head."
  [writer query-form & arguments]
  (let [response (apply db-query-with-evidence writer query-form arguments)]
    (if (:seon/error response)
      response
      (:datahike.query/result response))))

(defn- db-pull
  "Context `seon.db/pull`: one blocking pull read at the current head."
  [writer selector entity-id]
  (let [head (resolve-head! writer)]
    (if (:seon/error head)
      head
      (let [response (writer-call!
                      writer
                      (protocol/pull-request
                       (with-read-provenance
                         {::protocol/request-id (str (random-uuid))
                          :seon.db/db head
                          ::protocol/selector selector
                          ::protocol/entity-id entity-id})))]
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
                           (cond-> {::protocol/request-id op-id
                                    :seon.db/db head
                                    ::protocol/transaction-data (vec tx-data)}
                             (provenance)
                             (assoc ::protocol/transaction-meta
                                    (provenance)))))]
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
     'query-with-evidence
     {::wrapper-fn (partial db-query-with-evidence writer)
      ::arglists '([query-form & arguments])
      ::doc "Run one Datalog read and return its result with its own cost evidence."}
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
     ;; Real admission through the one `seon.schema/register!` bridge:
     ;; the host registry validates and admits exactly as the child's,
     ;; a banned/invalid shape returns the guidance as an error VALUE,
     ;; and the surrounding form's registry diff tees the canonical
     ;; `:seon.schema` row into the same transaction as its eval row.
     'register! {::wrapper-fn
                 (fn [schema-key schema-form]
                   (try
                     (schema/register! schema-key schema-form)
                     schema-key
                     (catch Throwable throwable
                       {:seon/error
                        {:seon.error/message
                         (str (.getMessage throwable))
                         :seon.error/kind :user-input}})))
                 ::arglists '([schema-key schema])
                 ::doc "Register one schema; the eval tee persists the canonical row."}}})
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

(defn- synthetic-ns-form
  "The synthetic `(ns …)` source establishing one context namespace.

   Stands in for the production augment-ns-source aliases, pointed at
   the host capability namespaces the registry provisions."
  [ns-sym]
  (str "(ns " ns-sym
       " (:require [clojure.string :as str]"
       " [clojure.set :as set]"
       " [clojure.edn :as edn]"
       " [clojure.walk :as walk]"
       " [seon.db :as db]"
       " [seon.schema :as schema]"
       " [seon.ai.tokens :as tokens]))"))

(defn ensure-context-ns!
  "Ensure `ns-sym` exists in `ctx` with the standard capability aliases.

   Idempotent: an existing namespace is left untouched (re-running the
   ns form would be harmless but wasteful under the shared load lock)."
  {:malli/schema [:=> [:catn [::ctx ::ctx] [::ns-sym :symbol]] :nil]}
  [ctx ns-sym]
  (when-not (sci/eval-string* ctx (str "(find-ns '" ns-sym ")"))
    (sci/eval-string* ctx (synthetic-ns-form ns-sym)))
  nil)

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
             (sci/eval-string* ctx (synthetic-ns-form ns-sym))
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
   sources come from the one program corpus ([[agent-def-sources]]);
   replay is reconstruction, never a fresh agent eval, so nothing here
   re-tees."
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

;;; Eval recording — host-tier turns are first-class corpus citizens (U4).

(def ^:private agent-def-sources-query
  '[:find ?source ?transaction
    :in $ ?ns-sym
    :where
    [?namespace :seon.ns/name ?ns-sym]
    [?fn :seon.fn/ns ?namespace]
    [?fn :seon.fn/source ?source ?transaction]])

(defn agent-def-sources
  "Ordered corpus def sources for one namespace, oldest tee first.

   The durable agent is database facts: restore forks the shared base
   and replays exactly these `:seon.fn/source` rows. Returns the error
   value on a failed read — the caller decides whether a restore without
   replay is acceptable."
  {:malli/schema [:=> [:catn [::writer ::writer] [::ns-sym :symbol]] :any]}
  [writer ns-sym]
  (let [rows (db-query writer agent-def-sources-query ns-sym)]
    (if (:seon/error rows)
      rows
      (into [] (map first) (sort-by second rows)))))

(defn restore-context-defs!
  "Replay one namespace's corpus defs into a freshly forked context.

   Establishes the namespace with the standard aliases, then replays
   each stored def source inside it. Returns the replay envelopes (an
   error value when the corpus read itself failed); individual replay
   failures are values inside the vector, never throws."
  {:malli/schema [:=> [:catn [::writer ::writer] [::ctx ::ctx]
                       [::ns-sym :symbol]] :any]}
  [writer ctx ns-sym]
  (let [sources (agent-def-sources writer ns-sym)]
    (if (:seon/error sources)
      sources
      (do (ensure-context-ns! ctx ns-sym)
          (replay-defs!
           ctx
           (mapv #(str "(in-ns '" ns-sym ")\n" %) sources))))))

(defn- record-transact!
  "One provenance-stamped transaction on the retained writer connection.

   `candidates` ride the protocol's `::generated-candidates` field, so
   the writer validates and commits managed identity allocation in the
   same transaction — the exact mechanism `seon.db.id/allocate!` uses."
  [writer {::keys [tx-data candidates]}]
  (let [head (resolve-head! writer)]
    (if (:seon/error head)
      head
      (let [response
            (writer-call!
             writer
             (protocol/transaction-request
              (cond-> {::protocol/request-id (str (random-uuid))
                       :seon.db/db head
                       ::protocol/transaction-data (vec tx-data)}
                (provenance)
                (assoc ::protocol/transaction-meta (provenance))
                (seq candidates)
                (assoc ::protocol/generated-candidates (vec candidates)))))]
        (if (::protocol/success? response)
          {:seon.db/ok? true}
          (protocol-error-value response))))))

(def ^:private eval-allocation-key ::eval-allocation)
(def ^:private max-allocation-attempts 16)

(defn- eval-id-generator
  "The stored generator policy for `:seon.eval/id`; cached per session.

   The policy is a database fact on the `:seon.schema` row; process-local
   caching is safe because a policy change would arrive with a new
   program, not mid-session."
  [writer]
  (or @(::eval-generator writer)
      (let [rows (db-query writer db.id/generator-policy-query
                           [:seon.eval/id])]
        (if (:seon/error rows)
          rows
          (if-let [generator (some (fn [[attr generator]]
                                     (when (= :seon.eval/id attr)
                                       generator))
                                   rows)]
            (reset! (::eval-generator writer) generator)
            {:seon/error
             {:seon.error/message
              "No stored generator policy for :seon.eval/id."
              :seon.error/kind :core-bug}})))))

(defn start-eval-receipt!
  "Allocate one managed eval id and commit its `:running` receipt.

   The durable execution boundary: the caller runs the form ONLY after
   this returns `{:seon.eval/id id}`. Candidate conflicts retry with a
   fresh candidate, bounded exactly like the allocator."
  {:malli/schema [:=> [:cat ::writer
                       [:map [:seon.agent.turn/id :string]
                        [:seon.eval/at :inst]
                        [:seon.eval/source :string]
                        [:seon.eval/narration :string]
                        [:seon.eval/ns :symbol]
                        [:seon.agent/id :string]]]
                  :map]}
  [writer {turn-id :seon.agent.turn/id
           at :seon.eval/at
           source :seon.eval/source
           narration :seon.eval/narration
           ns-sym :seon.eval/ns
           agent-id :seon.agent/id}]
  (let [generator (eval-id-generator writer)]
    (if (:seon/error generator)
      generator
      (loop [attempt 1]
        (let [manifest (db.id/candidate-manifest
                        {:seon.eval/id generator}
                        [{:seon.db.id/key eval-allocation-key
                          :seon.db.id/identity-attr :seon.eval/id}])
              eval-id (:seon.db.id/value (first manifest))
              outcome
              (record-transact!
               writer
               {::tx-data (record/start-tx-data
                           {:seon.agent.turn/id turn-id
                            :seon.eval/id eval-id
                            :seon.eval/at at
                            :seon.eval/source source
                            :seon.eval/narration narration
                            :seon.eval/ns ns-sym
                            :seon.eval/agent [:seon.agent/id agent-id]})
                ::candidates manifest})]
          (cond
            (:seon.db/ok? outcome) {:seon.eval/id eval-id}

            (and (= protocol/generated-candidate-conflict-error
                    (get-in outcome [:seon/error :seon.error/data
                                     ::protocol/error-kind]))
                 (< attempt max-allocation-attempts))
            (recur (inc attempt))

            :else outcome))))))

(defn record-eval-terminal!
  "Terminalize one receipt with its frozen outcome and program tee.

   One transaction carries the `:running` CAS fence, the complete eval
   row, and every program-graph row the form tees — the eval's committed
   transaction IS the transaction that wrote the corpus datom, exactly
   as the child records."
  {:malli/schema [:=> [:cat ::writer :map] :map]}
  [writer {eval-id :seon.eval/id
           ::keys [envelope at duration-ms source narration ns-sym
                   agent-id forms var-meta new-schema-keys]}]
  (let [tx-data
        (into (record/terminal-tx-data
               {:seon.eval/id eval-id
                ::record/envelope envelope
                ::record/at at
                ::record/duration-ms duration-ms
                ::record/source source
                ::record/narration narration
                ::record/ns-sym ns-sym
                ::record/agent-ref [:seon.agent/id agent-id]})
              (when (:seon.eval/ok? envelope)
                (record/tee-tx-data
                 {::record/forms (or forms [])
                  ::record/source source
                  ::record/ns-sym ns-sym
                  ::record/var-meta (or var-meta {})
                  ::record/new-schema-keys (or new-schema-keys #{})
                  ::record/at at})))]
    (record-transact! writer {::tx-data tx-data})))
