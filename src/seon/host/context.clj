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
            [seon.ai.provider :as ai.provider]
            [seon.ai.tokens :as tokens]
            [seon.content-hash :as content-hash]
            [seon.db.id :as db.id]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.host.record :as record]
            [seon.repair.candidates :as candidates]
            [seon.schema :as schema]
            [seon.time :as time]))

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
(schema/register! ::projection-state 'some?)
(schema/register! ::committed-basis :int)
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
;; An SCI var may hold ordinary immutable data as well as a function. This is
;; the genuinely polymorphic interpreter-binding boundary; callers still use
;; the closed `::wrapper` union so a registration cannot supply both shapes.
(schema/register! ::wrapper-value :any)
(schema/register! ::arglists [:sequential [:vector :symbol]])
(schema/register! ::doc [:string {:min 1}])
(schema/register!
 ::wrapper
 [:or
  [:map {:closed true}
   [::wrapper-fn ::wrapper-fn]
   [::arglists {:optional true} ::arglists]
   [::doc {:optional true} ::doc]]
  [:map {:closed true}
   [::wrapper-value ::wrapper-value]
  [::arglists {:optional true} ::arglists]
   [::doc {:optional true} ::doc]]])
(schema/register! ::wrappers [:map-of :symbol ::wrapper])
(schema/register!
 ::register-request
 [:map {:closed true}
  [::registry ::registry]
  [::lib ::lib]
  [::wrappers ::wrappers]])
(schema/register!
 ::install-request
 [:map {:closed true}
  [::registry ::registry]
  [::ctx ::ctx]
  [::lib ::lib]])
(schema/register! :seon.capability/op-id [:string {:min 1}])
(schema/register! :seon.capability/replayed? :boolean)
(schema/register! ::files [:int {:min 0}])
(schema/register! ::pure-blocks [:int {:min 0}])
(schema/register! ::loaded [:int {:min 0}])
(schema/register! ::failed [:int {:min 0}])
(schema/register! ::excluded [:int {:min 0}])
(schema/register! ::source-path :string)
(schema/register! ::namespace :symbol)
(schema/register! ::status [:enum :loaded :failed :excluded])
(schema/register! ::reason [:string {:min 1}])
(schema/register!
 ::block
 [:map {:closed true}
  [::source-path ::source-path]
  [::namespace ::namespace]
  [::block-name :string]
  [::status ::status]
  [::reason {:optional true} ::reason]])
(schema/register! ::blocks [:vector ::block])
(schema/register!
 ::failures
 [:vector [:map {:closed true}
           [::source-path ::source-path]
           [::namespace ::namespace]
           [::block-name :string]
           [::failure :string]]])
(schema/register!
 ::report
 [:map {:closed true}
  [::files ::files]
  [::pure-blocks ::pure-blocks]
  [::loaded ::loaded]
  [::failed ::failed]
  [::excluded ::excluded]
  [::blocks ::blocks]
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
                  (fn [acc fn-sym {::keys [wrapper-fn wrapper-value arglists doc]
                                  :as wrapper}]
                    (let [value (if (contains? wrapper ::wrapper-fn)
                                  wrapper-fn wrapper-value)]
                    (if-let [live (get acc fn-sym)]
                      (do (sci/alter-var-root live (constantly value))
                          acc)
                      (assoc acc fn-sym
                             (sci/new-var
                              fn-sym value
                              (cond-> {:ns sci-ns :name fn-sym}
                                arglists (assoc :arglists arglists)
                                doc (assoc :doc doc)))))))
                  (or (::vars entry) {})
                  wrappers)]
             (assoc entries lib {::sci-ns sci-ns ::vars vars}))))
  nil)

(defn install-registered-wrappers!
  "Link one context to a namespace's exact shared registry vars."
  {:malli/schema [:=> [:cat ::install-request] :nil]}
  [{::keys [registry ctx lib]}]
  (when-let [vars (get-in @registry [lib ::vars])]
    (sci/add-namespace! ctx lib vars))
  nil)

(defn- registry-load-fn
  "Shared sci `:load-fn` over the registry; injects wrappers on require.

   Called by sci only on the FIRST require of an unknown lib. The body is
   a map lookup plus an env swap — it must stay that cheap because the
   JVM require path holds one process-global load lock. Returning `{}`
   (no source) leaves the `:as`/`:refer` wiring to sci itself."
  [registry]
  (fn [{:keys [libname ctx]}]
    (when (get-in @registry [libname ::vars])
      (install-registered-wrappers!
       {::registry registry ::ctx ctx ::lib libname})
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
    ::lib 'seon.ai.provider
    ::wrappers
    {'provider-locality {::wrapper-value ai.provider/provider-locality}
     'frontier-provider? {::wrapper-fn ai.provider/frontier-provider?
                          ::arglists '([provider])}}})
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
    ::lib 'seon.db.id
    ::wrappers
    {'candidate-manifest {::wrapper-fn db.id/candidate-manifest
                          ::arglists '([generator-policies allocations])
                          ::doc "Generate one validated identity-candidate manifest."}
     'generator-policy-query {::wrapper-value db.id/generator-policy-query
                              ::doc "Query for stored generated-identity policies."}}})
  (register-wrappers!
   {::registry registry
    ::lib 'seon.db.protocol
    ::wrappers
    {'query-operation {::wrapper-value protocol/query-operation}
     'pull-operation {::wrapper-value protocol/pull-operation}
     'success? {::wrapper-value ::protocol/success?}
     'result {::wrapper-value ::protocol/result}}})
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
                 ::doc "Register one schema; the eval tee persists the canonical row."}
     'schema-definition {::wrapper-fn schema/schema-definition
                         ::arglists '([schema-key])
                         ::doc "Return one registered schema's canonical definition."}}})
  (register-wrappers!
   {::registry registry
    ::lib 'seon.ai.tokens
    ::wrappers
    {'estimate {::wrapper-fn tokens/estimate
                ::arglists '([value])
                ::doc "Estimated token size of one value."}
     'estimate-chars {::wrapper-fn tokens/estimate-chars
                      ::arglists '([character-count])
                      ::doc "Estimated token size of a character count."}
     'clip-str {::wrapper-fn tokens/clip-str
                ::arglists '([value budget] [value budget marker])
                ::doc "Clip text to an estimated token budget."}}})
  (register-wrappers!
   {::registry registry
    ::lib 'seon.content-hash
    ::wrappers
    {'sha-256 {::wrapper-fn content-hash/sha-256
               ::arglists '([content])}}})
  (register-wrappers!
   {::registry registry
    ::lib 'seon.time
    ::wrappers
    {'iso-string {::wrapper-fn time/iso-string
                  ::arglists '([instant])}}})
  (register-wrappers!
   {::registry registry
    ::lib 'seon.repair.candidates
    ::wrappers
    {'rank-candidates {::wrapper-fn candidates/rank-candidates
                       ::arglists '([from names])}}})
  (register-wrappers!
   {::registry registry
    ::lib 'seon.repl.internal
    ::wrappers
    {'read-forms {::wrapper-fn
                  (fn
                    ([source]
                     (record/read-forms {::record/source (or source "")}))
                    ([source options]
                     (record/read-forms
                      {::record/source (or source "")
                       ::record/ns-sym (:seon.repl/current-ns options)
                       ::record/aliases (:seon.repl/aliases options)})))
                  ::arglists '([source])}}})
  (register-wrappers!
   {::registry registry
    ::lib 'seon.agent.ctx
    ::wrappers
    {'read-file-text
     {::wrapper-fn (fn [path]
                     (try
                       (let [file (io/file path)]
                         (when (and (.isFile file) (.canRead file))
                           (slurp file)))
                       (catch Throwable _ nil)))
      ::arglists '([path])
      ::doc "Read one repo-relative UTF-8 text file, or nil when unreadable."}
     'list-skill-files
     {::wrapper-fn
      (fn [dir]
        (try
          (let [root (io/file dir)]
            (if-not (.isDirectory root)
              []
              (into []
                    (mapcat
                     (fn [name]
                       (let [path (io/file root name)]
                         (cond
                           (.isDirectory path)
                           (let [skill (io/file path "SKILL.md")]
                             (when (.isFile skill) [(.getPath skill)]))

                           (and (.isFile path) (str/ends-with? name ".md"))
                           [(.getPath path)]))))
                    (or (seq (.list root)) []))))
          (catch Throwable _ [])))
      ::arglists '([dir])
      ::doc "List the readable skill markdown files under one corpus directory."}}})
  (register-wrappers!
   {::registry registry
    ::lib 'seon.render.canvas
    ::wrappers
    {'field-signal
     {::wrapper-fn
      (fn [field]
        (str "seon_"
             (.encodeToString
              (.withoutPadding (java.util.Base64/getUrlEncoder))
              (.getBytes ^String (str field)
                         java.nio.charset.StandardCharsets/UTF_8))))
      ::arglists '([field])
      ::doc "Encode a qualified field keyword as a Datastar-safe signal identifier."}}}))

;;; Portable `my.*` slice, loaded from the real sources.

(def ^:private toolkit-source-root (io/file "src/my"))

(defn- toolkit-source-files
  "Every toolkit Clojure source path in deterministic discovery order."
  []
  (->> (file-seq toolkit-source-root)
       (filter #(.isFile ^java.io.File %))
       (filter #(re-find #"\.clj[sc]$" (.getName ^java.io.File %)))
       (mapv #(.getPath ^java.io.File %))
       sort
       vec))

(defn- definition-form?
  [form]
  (and (seq? form) (contains? '#{def defn defn-} (first form))))

(defn- definition-blocks
  "Top-level definition blocks from the one tools.reader source read."
  [source ns-sym aliases]
  (into []
        (comp (filter definition-form?)
              (map (fn [form]
                     (let [source (or (:source (meta form)) (pr-str form))]
                       {::block-name (str (second form))
                        ::source source
                        ::host-source (some-> (record/read-host-form source)
                                              pr-str)}))))
        (record/read-forms {::record/source source
                            ::record/ns-sym ns-sym
                            ::record/aliases aliases})))

(defn- pure-block?
  "True when a defn block has no async, js-interop, or db-boundary marker."
  [block]
  (and (string? block)
       (not (re-find #"\^:async|\(await |js/|#js|\(\.\-|\(\. |\(\.[a-zA-Z]|db/transact!|db/query|db/pull|db/entity|db/db\b|blob/"
                     block))))

(defn- edge-aliases
  [edges]
  (into {}
        (keep (fn [{:seon.ns.require/keys [target alias]}]
                (when (and target alias) [alias target])))
        edges))

(defn- source-unit
  [path]
  (let [source (slurp (io/file path))
        ns-form (record/read-ns-form source)
        ns-sym (second ns-form)
        edges (if ns-form (record/ns-require-edges ns-form) #{})
        aliases (edge-aliases edges)]
    {::source-path path
     ::namespace ns-sym
     ::require-edges edges
     ::blocks (if ns-sym (definition-blocks source ns-sym aliases) [])}))

(defn dependency-order
  "Topologically order source units by their parsed namespace requires.

   Only edges between supplied units constrain the result. Input position is
   the deterministic tie-breaker; a cycle is returned as data."
  {:malli/schema [:=> [:cat [:vector :map]]
                  [:map [::ordered [:vector :map]]
                   [::cycle [:vector :symbol]]]]}
  [units]
  (let [names (mapv ::namespace units)
        candidates (set names)
        position (zipmap names (range))
        by-name (into {} (map (juxt ::namespace identity)) units)
        needs (into {}
                    (map (fn [{::keys [namespace require-edges]}]
                           [namespace
                            (into #{}
                                  (comp (map :seon.ns.require/target)
                                        (filter candidates))
                                  require-edges)]))
                    units)]
    (loop [remaining needs ordered []]
      (if (empty? remaining)
        {::ordered (mapv by-name ordered) ::cycle []}
        (let [ready (->> remaining
                         (keep (fn [[name required]]
                                 (when (empty? required) name)))
                         (sort-by position)
                         vec)]
          (if (empty? ready)
            {::ordered (mapv by-name ordered)
             ::cycle (->> (keys remaining) (sort-by position) vec)}
            (let [released (set ready)]
              (recur (into {}
                           (map (fn [[name required]]
                                  [name (apply disj required released)]))
                           (apply dissoc remaining ready))
                     (into ordered ready)))))))))

(defn- require-spec
  [{:seon.ns.require/keys [target alias refers refer-all? as-alias?]}]
  (cond-> [target]
    (and alias (not as-alias?)) (conj :as alias)
    (seq refers) (conj :refer (vec (sort refers)))
    refer-all? (conj :refer :all)))

(defn- synthetic-ns-form
  "The synthetic `(ns …)` source establishing one context namespace.

   Stands in for the production augment-ns-source aliases, pointed at
   the host capability namespaces the registry provisions."
  [ns-sym require-edges available-libs]
  (let [parsed (->> require-edges
                    (remove :seon.ns.require/as-alias?)
                    (filter #(contains? available-libs
                                        (:seon.ns.require/target %)))
                    (sort-by (comp str :seon.ns.require/target))
                    (mapv require-spec))
        defaults [['clojure.string :as 'str]
                  ['clojure.set :as 'set]
                  ['clojure.edn :as 'edn]
                  ['clojure.walk :as 'walk]
                  ['seon.db :as 'db]
                  ['seon.schema :as 'schema]
                  ['seon.ai.tokens :as 'tokens]]
        parsed-targets (set (map first parsed))
        specs (into parsed (remove #(contains? parsed-targets (first %))) defaults)]
    (pr-str (list 'ns ns-sym (cons :require specs)))))

(defn ensure-context-ns!
  "Ensure `ns-sym` exists in `ctx` with the standard capability aliases.

   Idempotent: an existing namespace is left untouched (re-running the
   ns form would be harmless but wasteful under the shared load lock)."
  {:malli/schema [:=> [:catn [::ctx ::ctx] [::ns-sym :symbol]] :nil]}
  [ctx ns-sym]
  (when-not (sci/eval-string* ctx (str "(find-ns '" ns-sym ")"))
    (sci/eval-string* ctx (synthetic-ns-form ns-sym #{}
                                             '#{clojure.string clojure.set
                                                clojure.edn clojure.walk
                                                seon.db seon.schema
                                                seon.ai.tokens})))
  nil)

(defn- block-row
  [unit block status reason]
  (cond-> {::source-path (::source-path unit)
           ::namespace (::namespace unit)
           ::block-name (::block-name block)
           ::status status}
    reason (assoc ::reason reason)))

(defn load-portable-slice!
  "Eval every pure `my.*` defn block from its real source into `ctx`.

   Returns the honest ledger: block counts plus each failure's first error
   line. Failures are references to impure private helpers the pure slice
   does not carry, recorded — never silently skipped."
  [ctx registry]
  (let [units (mapv source-unit (toolkit-source-files))
        {::keys [ordered cycle]} (dependency-order units)
        candidate-libs (set (map ::namespace units))
        available-libs (into candidate-libs (keys @registry))
        loaded-rows
        (into []
              (mapcat
               (fn [{::keys [namespace require-edges blocks] :as unit}]
                 (let [portable (filterv (comp pure-block? ::host-source) blocks)
                       excluded-blocks (remove (comp pure-block? ::host-source) blocks)
                       excluded-rows
                       (mapv #(block-row unit % :excluded
                                         "The block is outside the portable C1 class (async, JS, database, or blob capability evidence).")
                             excluded-blocks)
                       ns-error
                       (try
                         (sci/eval-string*
                          ctx (synthetic-ns-form namespace require-edges
                                                 available-libs))
                         nil
                         (catch Throwable throwable
                           (first (str/split-lines
                                   (str (.getMessage throwable))))))]
                   (into excluded-rows
                         (if ns-error
                           (map #(block-row unit % :failed ns-error) portable)
                           (map (fn [{::keys [host-source] :as block}]
                                  (try
                                    (sci/eval-string*
                                     ctx (str "(in-ns '" namespace ")\n" host-source))
                                    (block-row unit block :loaded nil)
                                    (catch Throwable throwable
                                      (block-row
                                       unit block :failed
                                       (first (str/split-lines
                                               (str (.getMessage throwable))))))))
                                portable))))))
              ordered)
        cycle-rows
        (into []
              (mapcat
               (fn [namespace]
                 (let [unit (first (filter #(= namespace (::namespace %)) units))]
                   (map #(block-row unit % :failed
                                    (str "Namespace require cycle: "
                                         (str/join ", " cycle)))
                        (filter (comp pure-block? ::host-source) (::blocks unit)))))
               cycle))
        initial-rows (into loaded-rows cycle-rows)
        excluded-names
        (reduce (fn [by-ns row]
                  (if (= :excluded (::status row))
                    (update by-ns (::namespace row) (fnil conj #{})
                            (::block-name row))
                    by-ns))
                {}
                initial-rows)
        rows
        (mapv
         (fn [row]
           (if-let [[_ dependency]
                    (and (= :failed (::status row))
                         (re-matches #"Unable to resolve symbol: (.+)"
                                     (::reason row)))]
             (if (contains? (get excluded-names (::namespace row) #{})
                            dependency)
               (assoc row
                      ::status :excluded
                      ::reason (str "Depends on excluded non-portable helper `"
                                    dependency "`."))
               row)
             row))
         initial-rows)
        failures (into []
                       (comp (filter #(= :failed (::status %)))
                             (map (fn [row]
                                    {::source-path (::source-path row)
                                     ::namespace (::namespace row)
                                     ::block-name (::block-name row)
                                     ::failure (::reason row)})))
                       rows)]
    {::files (count units)
     ::pure-blocks (count (remove #(= :excluded (::status %)) rows))
     ::loaded (count (filter #(= :loaded (::status %)) rows))
     ::failed (count failures)
     ::excluded (count (filter #(= :excluded (::status %)) rows))
     ::blocks rows
     ::failures failures}))

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
        report (load-portable-slice! ctx wrapper-registry)]
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
          {:seon.db/ok? true
           :db-after (select-keys (:db-after response)
                                  [:db-name :t :datahike/commit-id])}
          (protocol-error-value response))))))

(defn query-writer!
  "Run one host-internal query through the retained writer session."
  {:malli/schema [:=> [:cat ::writer :any [:sequential :any]] :any]}
  [writer query-form arguments]
  (apply db-query writer query-form arguments))

(defn query-writer-at!
  "Run one host query at the caller's explicit immutable database value."
  {:malli/schema
   [:=> [:cat ::writer :seon.db/db :any [:sequential :any]] :any]}
  [writer database query-form arguments]
  (let [response
        (writer-call!
         writer
         (protocol/query-request
          (with-read-provenance
            {::protocol/request-id (str (random-uuid))
             :seon.db/db database
             ::protocol/query-form query-form
             ::protocol/arguments (vec arguments)})))]
    (if (::protocol/success? response)
      (:datahike.query/result response)
      (protocol-error-value response))))

(def ^:private committed-schema-query
  '[:find ?key ?form
    :where
    [?schema :seon.schema/key ?key]
    [?schema :seon.schema/form ?form]])

(def ^:private committed-function-contract-query
  '[:find ?sym ?form
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/spec ?form]])

(def ^:private committed-projection-row-limit 4096)

(defn acquire-committed-projection!
  "Acquire and compile the complete committed program at one database value."
  {:malli/schema [:=> [:catn [::writer ::writer]] :map]}
  [writer]
  (try
    (let [database (resolve-head! writer)]
      (if (:seon/error database)
        database
        (let [member (fn [query]
                       {::protocol/operation protocol/query-operation
                        :seon.db/db database
                        ::protocol/query-form query
                        ::protocol/arguments []
                        :datahike.resource/max-work 1000000
                        ;; One sentinel proves the claimed complete population
                        ;; did not stop at Datahike's early-stop row limit.
                        :datahike.resource/max-results
                        (inc committed-projection-row-limit)
                        :datahike.resource/max-result-weight (* 3 1024 1024)})
              response
              (writer-call!
                writer
                (protocol/execute-many-request
                  {::protocol/request-id (str (random-uuid))
                   ::protocol/members
                   [(member committed-schema-query)
                    (member committed-function-contract-query)]
                   :datahike.resource/max-result-weight (* 6 1024 1024)}))]
          (if-not (::protocol/success? response)
            (protocol-error-value response)
            (let [[schemas contracts] (::protocol/results response)]
              (if-not (every? ::protocol/success? [schemas contracts])
                {:seon/error
                 {:seon.error/message
                  "Committed schema projection acquisition failed."
                  :seon.error/kind :core-bug
                  :seon.error/data
                  {:seon.host.context/member-results [schemas contracts]}}}
                (let [schema-rows (:datahike.query/result schemas)
                      contract-rows (:datahike.query/result contracts)]
                  (if (or (> (count schema-rows)
                             committed-projection-row-limit)
                          (> (count contract-rows)
                             committed-projection-row-limit))
                    {:seon/error
                     {:seon.error/message
                      "Committed schema projection exceeds its complete-row bound."
                      :seon.error/kind :core-bug}}
                    {::database database
                     ::projection
                     (schema/projection-from-rows
                       {:seon.schema/schema-rows schema-rows
                        :seon.schema/function-contract-rows
                        contract-rows})}))))))))
    (catch Throwable throwable
      {:seon/error
       {:seon.error/message
        (str "Committed schema projection is invalid: "
             (.getMessage throwable))
        :seon.error/kind :core-bug
        :seon.error/data (ex-data throwable)}})))

(defn publish-committed-projection!
  "Publish `acquired` only when its basis transaction is newer."
  {:malli/schema [:=> [:catn [::projection-state ::projection-state]
                             [::acquired :map]]
                  :map]}
  [projection-state acquired]
  (swap! projection-state
         (fn [current]
           (let [floor (max (get-in current [::database :t] -1)
                            (get current ::committed-basis -1))
                 acquired-basis (get-in acquired [::database :t] -1)]
             (if (or (< floor acquired-basis)
                     (and (::fault current) (= floor acquired-basis)))
               acquired
               current)))))

(defn current-committed-projection
  "Return the complete retained projection or its newer-generation fault."
  {:malli/schema [:=> [:catn [::projection-state ::projection-state]] :map]}
  [projection-state]
  (let [current @projection-state]
    (if-let [fault (::fault current)]
      {:seon/error fault}
      {::database (::database current)
       ::projection (::projection current)})))

(defn refresh-committed-projection!
  "Rebuild and monotonically publish the writer's complete projection."
  {:malli/schema [:=> [:catn [::writer ::writer]
                             [::projection-state ::projection-state]
                             [::committed-basis ::committed-basis]]
                  :map]}
  [writer projection-state committed-basis]
  ;; The durable commit is already newer than the served projection. Publish
  ;; an unavailable floor before any reacquire/build work can block, so a
  ;; concurrent browser never observes the old generation as current truth.
  (swap! projection-state
         (fn [current]
           (let [floor (max (get-in current [::database :t] -1)
                            (get current ::committed-basis -1))]
             (if (< floor committed-basis)
               {::fault
                {:seon.error/message
                 "Committed schema projection refresh is pending."
                 :seon.error/kind :core-bug}
                ::pending? true
                ::committed-basis committed-basis}
               current))))
  (let [read-result (acquire-committed-projection! writer)
        acquired
        (if (and (not (:seon/error read-result))
                 (< (get-in read-result [::database :t] -1)
                    committed-basis))
          {:seon/error
           {:seon.error/message
            "Committed schema projection refresh returned a stale database value."
            :seon.error/kind :core-bug}}
          read-result)]
    (if (:seon/error acquired)
      (do
        (swap! projection-state
               (fn [current]
                 (if (< (max (get-in current [::database :t] -1)
                             (get current ::committed-basis -1))
                        committed-basis)
                   {::fault (:seon/error acquired)
                    ::committed-basis committed-basis}
                   (if (and (::pending? current)
                            (= committed-basis
                               (::committed-basis current)))
                     {::fault (:seon/error acquired)
                      ::committed-basis committed-basis}
                     current))))
        (let [current @projection-state]
          (if (>= (get-in current [::database :t] -1) committed-basis)
            current
            acquired)))
      (publish-committed-projection! projection-state acquired))))

(defn transact-writer!
  "Commit host-derived transaction data through the retained writer."
  {:malli/schema [:=> [:cat ::writer [:vector :any]] :map]}
  [writer tx-data]
  (record-transact! writer {::tx-data tx-data}))

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
    (let [result (record-transact! writer {::tx-data tx-data})
          projection-change?
          (boolean
            (some (fn [operation]
                    (or (and (map? operation)
                             (or (contains? operation :seon.schema/form)
                                 (contains? operation :seon.fn/spec)))
                        (and (vector? operation)
                             (contains? #{:seon.schema/form :seon.fn/spec}
                                        (nth operation 2 nil)))))
                  tx-data))]
      (cond-> result
        (:seon.db/ok? result)
        (assoc ::projection-changed? projection-change?)))))
