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
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.reader :as tools.reader]
            [my.blob :as blob]
            [my.blob.host :as blob.host]
            [sci.core :as sci]
            [sci.ctx-store]
            [sci.interrupt :as interrupt]
            [seon.ai.provider :as ai.provider]
            [seon.ai.tokens :as tokens]
            [seon.agent.fs.leaf :as fs.leaf]
            [seon.agent.lifecycle :as lifecycle]
            [seon.agent.lifecycle.leaf :as lifecycle.leaf]
            [seon.agent.message :as message]
            [seon.agent.message.leaf :as message.leaf]
            [seon.agent.shell.leaf :as shell.leaf]
            [seon.agent.web :as agent.web]
            [seon.agent.web.host :as web.host]
            [seon.capability :as capability]
            [seon.content-hash :as content-hash]
            [seon.db :as db]
            [seon.db.host :as db.host]
            [seon.db.id :as db.id]
            [seon.db.protocol :as protocol]
            [seon.host.record :as record]
            [seon.host.guard :as guard]
            [seon.repl.parse.repair :as candidates]
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

(def ^:dynamic *tx-context*
  "Ordinary invocation context visible to portable database functions."
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
(schema/register! ::pool-state 'some?)
(schema/register! ::pool-lock 'some?)
(schema/register! ::pool-condition 'some?)
(schema/register! ::call-executor 'some?)
(schema/register! ::eval-generator 'some?)
(schema/register! ::projection-state 'some?)
(schema/register! ::artifact-exports [:set :symbol])
(schema/register! ::session 'some?)
(schema/register! ::committed-basis :int)
(schema/register!
 ::writer
 [:map
  [::writer-socket-path ::writer-socket-path]
  [::database-name ::database-name]
  [::backend {:optional true} ::backend]
  [::database-path {:optional true} ::database-path]
  [::pool-state ::pool-state]
  [::pool-lock ::pool-lock]
  [::pool-condition ::pool-condition]
  [::call-executor ::call-executor]
  [::eval-generator ::eval-generator]])
(schema/register! ::ctx 'some?)
(schema/register! ::registry 'some?)
(schema/register! ::tier-inventory :seon.execution.inventory/tier)
(schema/register! ::lib :symbol)
(schema/register! ::wrapper-fn 'fn?)
(schema/register! ::reconcile-ephemeral! 'fn?)
;; An SCI var may hold ordinary immutable data as well as a function. This is
;; the genuinely polymorphic interpreter-binding boundary; callers still use
;; the closed `::wrapper` union so a registration cannot supply both shapes.
(schema/register! ::wrapper-value :any)
(schema/register! ::arglists [:sequential [:vector :symbol]])
(schema/register! ::doc [:string {:min 1}])
(schema/register! ::effect [:enum :pure :read :idempotent :external])
(schema/register!
 ::wrapper
 [:or
  [:map {:closed true}
   [::wrapper-fn ::wrapper-fn]
   [::arglists {:optional true} ::arglists]
   [::doc {:optional true} ::doc]
   [::effect {:optional true} ::effect]]
  [:map {:closed true}
  [::wrapper-value ::wrapper-value]
  [::arglists {:optional true} ::arglists]
   [::doc {:optional true} ::doc]
   [::effect {:optional true} ::effect]]])
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
(schema/register! ::base-load-plan :map)
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
  [::registry ::registry]
  [::tier-inventory ::tier-inventory]])
(schema/register! ::def-sources [:vector :string])
(schema/register!
 ::replay-envelope
 [:map
  [:seon.eval/ok? :boolean]])
(schema/register! ::replay-envelopes [:vector ::replay-envelope])
(schema/register! ::materialized-function [:tuple ::ctx 'some?])
(schema/register! ::function-rows [:vector :map])

 ;; Compatibility entry points while host callers move to `seon.db.host`.
;; The retained pool itself is owned exclusively by that leaf namespace.
(defn writer-session
  "Build the host database leaf and retain legacy context coordinates."
  [{::keys [writer-socket-path database-name backend database-path] :as options}]
  (let [host-writer
        (db.host/writer-session
         (cond-> {::db.host/writer-socket-path writer-socket-path
                  ::db.host/database-name database-name
                  ::db.host/pool-size (or (::pool-size options)
                                          (::db.host/pool-size db.host/defaults))
                  ::db.host/recoverable-transaction-delivery?
                  db.host/recoverable-transaction-delivery?}
           backend (assoc ::db.host/backend backend)
           database-path (assoc ::db.host/database-path database-path)))]
    (merge host-writer
           {::writer-socket-path writer-socket-path
            ::database-name database-name
            ::pool-state (::db.host/pool-state host-writer)
            ::pool-lock (::db.host/pool-lock host-writer)
            ::pool-condition (::db.host/pool-condition host-writer)
            ::call-executor (::db.host/call-executor host-writer)
            ::eval-generator (atom nil)
            ::projection-state (atom nil)}
           (when backend {::backend backend})
           (when database-path {::database-path database-path}))))

(defn close-session!
  "Close the database pool owned by `seon.db.host`."
  [writer]
  (db.host/close-session! writer))

(defn- writer-call!
  "Dispatch a bounded wire request through the host database leaf."
  [writer request]
  (db.host/call! writer request))

(defn resolve-head!
  "Resolve the host writer's current immutable database value."
  [writer]
  (db.host/resolve-db! writer nil false))

(declare bound-database-functions)

(defn load-base-projection!
  "Load and verify one manifest-bound EDN-only projection artifact."
  [path expected-digest]
  (let [text (slurp path)
        actual-digest (content-hash/sha-256 text)
        artifact (edn/read-string text)
        projection (:seon.dev.artifact/base-projection artifact)
        recomposed (schema/compose-projection-data projection {})]
    (when-not (= expected-digest actual-digest)
      (throw
       (ex-info "The admitted base-projection artifact digest changed."
                {:seon.dev.artifact/base-projection-path path
                 :seon.dev.artifact/expected-digest expected-digest
                 :seon.dev.artifact/actual-digest actual-digest})))
    (when-not
     (= (:seon.schema.projection/fingerprint projection)
        (:seon.schema.projection/fingerprint recomposed))
      (throw
       (ex-info "The base-projection population fingerprint changed."
                {:seon.dev.artifact/base-projection-path path
                 :seon.dev.artifact/stored-fingerprint
                 (:seon.schema.projection/fingerprint projection)
                 :seon.dev.artifact/recomputed-fingerprint
                 (:seon.schema.projection/fingerprint recomposed)})))
    artifact))

(defn verify-applied-identity!
  "Verify the cluster's three applied-identity facts at one database value."
  [writer database cluster expected]
  (let [entity-fn (get (bound-database-functions writer) 'entity)
        actual
        (select-keys
         (entity-fn database [:seon.db.initialization/id "database"])
         [:seon.db.initialization/fingerprint
          :seon.db.initialization/status
          :seon.db.initialization/release-digest
          :seon.db.initialization/config-manifest-digest])
        matched?
        (and (= :seon.db.initialization.status/complete
                (:seon.db.initialization/status actual))
             (= expected (select-keys actual (keys expected))))
        short-digest
        (fn [digest]
          (if (and (string? digest) (<= 8 (count digest)))
            (subs digest 0 8)
            (pr-str digest)))]
    (when-not matched?
      (throw
       (ex-info
        (str "this cluster was applied at release "
             (short-digest
              (:seon.db.initialization/release-digest actual))
             "/config "
             (short-digest
              (:seon.db.initialization/config-manifest-digest actual))
             "; this artifact is "
             (short-digest
              (:seon.db.initialization/release-digest expected))
             "/config "
             (short-digest
              (:seon.db.initialization/config-manifest-digest expected))
             "; run `bin/seon cluster apply " cluster "`.")
        {:seon.startgate/cluster cluster
         :seon.startgate/applied-identity actual
         :seon.startgate/launch-identity expected
         :seon.startgate/remedy (str "bin/seon cluster apply " cluster)})))
    actual))

(defn acquire-preprocessed-projection!
  "Read, verify, compose, and rematerialize the cluster projection cache."
  [writer database base artifact-exports]
  (let [entity-fn (get (bound-database-functions writer) 'entity)
        cache
        (entity-fn
         database
         [:seon.runtime.admission.cache/id "committed-projection"])
        delta-string (:seon.runtime.admission.cache/delta cache)
        delta (when (string? delta-string)
                (edn/read-string delta-string))
        composed (when (map? delta)
                   (schema/compose-projection-data base delta))
        current?
        (and
         (= (:seon.schema.projection/fingerprint base)
            (:seon.runtime.admission.cache/base-fingerprint cache))
         (= (:t database)
            (:seon.runtime.admission.cache/basis-t cache))
         (= (schema/canonical-data-fingerprint delta)
            (:seon.runtime.admission.cache/divergence-fingerprint cache))
         (= (:seon.schema.projection/fingerprint composed)
            (:seon.runtime.admission.cache/composed-fingerprint cache)))]
    (when-not current?
      (throw
       (ex-info
        "The divergence projection cache is not current at the database basis."
        {:seon.runtime.admission.cache/base-fingerprint
         (:seon.runtime.admission.cache/base-fingerprint cache)
         :seon.runtime.admission.cache/basis-t
         (:seon.runtime.admission.cache/basis-t cache)
         :seon.db/basis-t (:t database)})))
    {::database database
     ::projection
     (schema/materialize-projection
      (schema/compose-projection-data
       composed
       {:seon.schema.projection/artifact-exports artifact-exports}))}))

(defn- database-context
  [writer]
  {:seon.db.leaf/current-tx-context (fn [] *tx-context*)
   :seon.db.leaf/current-agent-id (fn [] *agent-id*)
   :seon.db.leaf/with-read-evidence (fn [f] (f))
   :seon.db.leaf/record-read-evidence! (fn [_] nil)
   :seon.db.leaf/with-agent (fn [agent-id f]
                              (binding [*agent-id* agent-id] (f)))
   :seon.db.leaf/without-agent (fn [f]
                                (binding [*agent-id* nil] (f)))
   :seon.db.leaf/with-tx-context (fn [context f]
                                  (binding [*tx-context*
                                            (merge *tx-context* context)]
                                    (f)))
   :seon.db.leaf/install-configuration-context! (fn [_] nil)
   :seon.db.leaf/schema-projection
   (fn []
     (let [projection (::projection @(::projection-state writer))]
       (when (seq (:seon.schema.projection/forms projection)) projection)))
   :seon.db.leaf/cache-schema-projection!
   (fn [projection]
     (reset! (::projection-state writer) {::projection projection}))
   :seon.db.leaf/schema-validation?
   (fn []
     (boolean
      (seq (:seon.schema.projection/forms
            (::projection @(::projection-state writer))))))})

(defn committed-schema-definition
  "Return one schema definition from the claimant's committed projection."
  {:malli/schema
   [:=> [:catn [::writer ::writer]
                [:seon.schema/registry-key :seon.schema/registry-key]]
    :any]}
  [writer schema-key]
  (let [current @(::projection-state writer)]
    (when-let [fault (::fault current)]
      (throw
       (ex-info "The committed schema projection is unavailable."
                {:seon.error/kind :core-bug
                 :seon/error fault})))
    (get-in current
            [::projection :seon.schema.projection/forms schema-key])))

(defn- bound-database-functions
  [writer]
  (db/bind-leaf (db.host/leaf writer #(database-context writer))))

(defn- host-message-leaf []
  {::message.leaf/available? (constantly true)
   ::message.leaf/unavailable (constantly {:seon.error/message "Host admission is unavailable."
                                           :seon.error/kind :core-bug})
   ::message.leaf/now #(java.util.Date.)
   ::message.leaf/uuid #(str (random-uuid))
   ::message.leaf/hop-cap 8})

(defn- host-lifecycle-leaf []
  {::lifecycle.leaf/available? (constantly true)
   ::lifecycle.leaf/unavailable (constantly {:seon.error/message "Host admission is unavailable."
                                             :seon.error/kind :core-bug})
   ::lifecycle.leaf/now #(java.util.Date.)
   ::lifecycle.leaf/uuid #(str (random-uuid))})

(defn- toolkit-call
  [delegates function-symbol]
  (fn [& arguments]
    (if-let [implementation (get @delegates function-symbol)]
      (apply implementation arguments)
      {:seon.error/message
       (str function-symbol " is unavailable on this host.")
       :seon.error/kind :core-bug})))

(defn- host-context-blocks
  [database-functions agent-id]
  (let [entity
        ((get database-functions 'pull)
         {:seon.db/pull-pattern '[{:seon.agent/ctx [*]}]
          :seon.db/ref [:seon.agent/id agent-id]
          :datahike.resource/max-work 100000
          :datahike.resource/max-results 2048
          :datahike.resource/max-result-weight 262144})]
    (if (:seon.error/message entity)
      entity
      (->> (:seon.agent/ctx entity)
           (sort-by (juxt :seon.agent.ctx/priority
                          (comp str :seon.agent.ctx/name)))
           (mapv #(dissoc % :db/id))))))

(defn- host-context-transaction
  [database-functions operation names agent-id blocks]
  (let [transaction-data
        (into [[:db.fn/retractAttribute
                [:seon.agent/id agent-id] :seon.agent/ctx]]
              (when (seq blocks)
                [{:seon.agent/id agent-id
                  :seon.agent/ctx (vec blocks)}]))
        result ((get database-functions 'transact!)
                {:seon.db/tx-data transaction-data})]
    (if-let [message (:seon.error/message result)]
      {:seon.agent.ctx/ok? false
       :seon.agent.ctx/error
       (str operation " transact failed: " message)}
      {:seon.agent.ctx/ok? true
       :seon.agent.ctx/names names})))

(defn- host-context-install!
  [database-functions block-or-blocks]
  (if-not *agent-id*
    {:seon.agent.ctx/ok? false
     :seon.agent.ctx/error
     (str "install!: no agent in scope — call inside "
          "(seon.db/with-agent id …).")}
    (let [current (host-context-blocks database-functions *agent-id*)]
      (if-let [message (:seon.error/message current)]
        {:seon.agent.ctx/ok? false :seon.agent.ctx/error message}
        (let [blocks (if (vector? block-or-blocks)
                       block-or-blocks [block-or-blocks])
              names (into #{} (map :seon.agent.ctx/name) blocks)
              kept (remove #(contains? names (:seon.agent.ctx/name %))
                           current)]
          (host-context-transaction database-functions "install!"
                                    (vec names) *agent-id*
                                    (into (vec kept) blocks)))))))

(defn- host-context-remove!
  [database-functions block-name]
  (if-not *agent-id*
    {:seon.agent.ctx/ok? false
     :seon.agent.ctx/error
     (str "remove!: no agent in scope — call inside "
          "(seon.db/with-agent id …).")}
    (let [current (host-context-blocks database-functions *agent-id*)]
      (if-let [message (:seon.error/message current)]
        {:seon.agent.ctx/ok? false :seon.agent.ctx/error message}
        (host-context-transaction
         database-functions "remove!" [block-name] *agent-id*
         (remove #(= block-name (:seon.agent.ctx/name %)) current))))))

;;; Wrapper registry — the ONE capability-provisioning mechanism.

(defn registry
  "Create one empty wrapper registry for one host base.

   Process-local derived state: a restart rebuilds it by re-registration
   from the host's configuration, never from persistence."
  {:malli/schema [:=> [:cat] ::registry]}
  []
  (atom {}))

(defn- shared-var-meta
  "Mark host-authored metadata as read-only in every agent fork."
  [host-authored? var-meta]
  (cond-> var-meta host-authored? (assoc :sci/built-in true)))

(defn- stamp-shared-base-vars!
  "Stamp every var SCI or the portable loader installed in the base.

   The base is still host-owned here. SCI's write guard therefore runs in
   its privileged context while this one walk marks every interned Var;
   agent forks never receive that privilege."
  [ctx]
  (sci.ctx-store/with-ctx (assoc ctx :unrestricted true)
    (doseq [shared-var
            (sci/eval-string*
             ctx "(vec (mapcat (comp vals ns-interns) (all-ns)))")
            :when (and (instance? sci.lang.Var shared-var)
                       (not (:sci/built-in (meta shared-var))))]
      (alter-meta! shared-var (partial shared-var-meta true))))
  nil)

(defn- register-wrapper-vars!
  [host-authored? {::keys [registry lib wrappers]}]
  (swap! registry
         (fn [entries]
           (let [entry (get entries lib)
                 sci-ns (or (::sci-ns entry) (sci/create-ns lib))
                 vars
                 (reduce-kv
                  (fn [acc fn-sym {::keys [wrapper-fn wrapper-value arglists doc effect]
                                  :as wrapper}]
                    (let [value (if (contains? wrapper ::wrapper-fn)
                                  wrapper-fn wrapper-value)]
                      (if-let [live (get acc fn-sym)]
                        (do (sci/alter-var-root live (constantly value))
                            acc)
                        (assoc acc fn-sym
                               (sci/new-var
                                fn-sym value
                                (shared-var-meta
                                 host-authored?
                                 (cond-> {:ns sci-ns :name fn-sym}
                                   arglists (assoc :arglists arglists)
                                   doc (assoc :doc doc)
                                   effect (assoc :seon.capability/effect effect))))))))
                  (or (::vars entry) {})
                  wrappers)]
             (assoc entries lib {::sci-ns sci-ns ::vars vars}))))
  nil)

(defn register-wrappers!
  "Register or upgrade agent-authored corpus function vars.

   Registering a namespace makes it lazily require-able in EVERY live
   context: the registry backs the shared `:load-fn` closure, so first
   require injects the cached wrapper vars with `:arglists`/`:doc` live
   on real sci vars. Corpus vars remain writable because eval-side `defn`
   is their deliberate recorded edit path. Re-registering alters the
   shared var's root through SCI's privileged host API, so every context
   that already required it sees the upgrade. A registry `(lib, symbol)`
   keeps its ownership class for its lifetime."
  {:malli/schema [:=> [:cat ::register-request] :nil]}
  [request]
  (register-wrapper-vars! false request))

(defn register-host-wrappers!
  "Register or upgrade host-authored read-only SCI built-in vars."
  {:malli/schema [:=> [:cat ::register-request] :nil]}
  [request]
  (register-wrapper-vars! true request))

(defn install-registered-wrappers!
  "Link one context to a namespace's exact shared registry vars."
  {:malli/schema [:=> [:cat ::install-request] :nil]}
  [{::keys [registry ctx lib]}]
  (when-let [vars (get-in @registry [lib ::vars])]
    (sci/add-namespace! ctx lib vars))
  nil)

(declare query-writer! query-writer-at!)

(def ^:private corpus-namespace-source-query
  '[:find ?source .
    :in $ ?lib
    :where
    [?namespace :seon.ns/name ?lib]
    [?namespace :seon.ns/source ?source]])

(defn- corpus-namespace-source
  "Stored namespace source at one current immutable database value."
  [writer lib]
  (let [database (resolve-head! writer)]
    (if (:seon/error database)
      database
      (query-writer-at! writer database corpus-namespace-source-query [lib]))))

(defn- registry-load-fn
  "Shared sci `:load-fn`; registry first, then stored corpus source.

   Called by sci only on the FIRST require of an unknown lib. The body is
   registry-first so provisioned wrappers remain the cheap path under the
   JVM's process-global load lock. A missing registry namespace resolves
   `:seon.ns/source` at one current immutable database value; sci evaluates
   that source and recursively materializes its declared require closure."
  [registry writer]
  (fn [{:keys [libname ctx]}]
    (if (get-in @registry [libname ::vars])
      (do
        (install-registered-wrappers!
         {::registry registry ::ctx ctx ::lib libname})
        {})
      (let [source (corpus-namespace-source writer libname)]
        (when (:seon/error source)
          (throw
           (ex-info (get-in source [:seon/error :seon.error/message])
                    {:seon.error/kind :core-bug})))
        (when source {:source source})))))

(defn- register-host-capabilities!
  "Seed the registry with the host's capability families over `writer`.

   This is the one provisioning path: `seon.db` reads/writes close over
   the pure-data writer boundary, `seon.schema` and `seon.ai.tokens` wrap
   the compiled host functions. Restart re-registers from configuration;
   nothing here persists."
  [registry writer toolkit-delegates]
  (let [installed-leaves (volatile! [])
        install!
        (fn [{::keys [lib wrappers] :as request}]
          (register-host-wrappers! request)
          (vswap! installed-leaves into
                  (capability/installation-leaves lib wrappers))
          nil)]
  (install!
   {::registry registry
    ::lib 'seon.ai.provider
    ::wrappers
    {'provider-locality {::wrapper-value ai.provider/provider-locality}
     'frontier-provider? {::wrapper-fn ai.provider/frontier-provider?
                          ::arglists '([provider])}}})
  (install!
   {::registry registry
    ::lib 'seon.db
    ::wrappers
    (into {}
          (map (fn [[function-symbol implementation]]
                 (let [source-var (ns-resolve 'seon.db function-symbol)
                       source-meta (meta source-var)]
                   [function-symbol
                    (cond-> {::wrapper-fn implementation}
                      (:arglists source-meta)
                      (assoc ::arglists (:arglists source-meta))
                      (:doc source-meta)
                      (assoc ::doc (:doc source-meta))
                      (:seon.capability/effect source-meta)
                      (assoc ::effect
                             (:seon.capability/effect source-meta)))])))
          (bound-database-functions writer))})
  (install!
   {::registry registry
    ::lib 'seon.db
    ::wrappers
    {'current-agent-id {::wrapper-fn (fn [] *agent-id*)
                        ::arglists '([])}
     'current-tx-context {::wrapper-fn (fn [] *tx-context*)
                         ::arglists '([])}}})
  (let [database-leaf (db.host/leaf writer #(database-context writer))]
    (doseq [[lib functions]
            [['seon.agent.message
              (message/bind-leaf (host-message-leaf) database-leaf)]
             ['seon.agent.lifecycle
              (lifecycle/bind-leaf (host-lifecycle-leaf) database-leaf)]]]
      (install!
       {::registry registry
        ::lib lib
        ::wrappers
        (into {}
              (map (fn [[function-symbol implementation]]
                     (let [source-var (ns-resolve lib function-symbol)
                           source-meta (meta source-var)]
                       [function-symbol
                        (cond-> {::wrapper-fn implementation}
                          (:arglists source-meta) (assoc ::arglists (:arglists source-meta))
                          (:doc source-meta) (assoc ::doc (:doc source-meta))
                          (:seon.capability/effect source-meta)
                          (assoc ::effect (:seon.capability/effect source-meta)))])))
              functions)})))
  (let [database-leaf (db.host/leaf writer #(database-context writer))]
    (install!
      {::registry registry
       ::lib 'seon.agent.message
       ::wrappers
       {'message-transaction-for
        {::wrapper-fn
         (fn [database request]
           (binding [message/*leaf* (host-message-leaf)
                     db/*leaf* database-leaf]
             (message/message-transaction-for database request)))
         ::arglists '([database request])}}}))
  (install!
   {::registry registry
    ::lib 'seon.agent.home
    ::wrappers
    {'home-ns {::wrapper-fn
               (fn [agent-id] (symbol (str "my.agent." agent-id)))
               ::arglists '([agent-id])}}})
  (install!
   {::registry registry
    ::lib 'seon.embed
    ::wrappers
    {'enabled? {::wrapper-fn (constantly false) ::arglists '([])}
     'search-pull
     {::wrapper-fn
      (fn [_]
        {:seon/error
         {:seon.error/message
          "Embeddings are not enabled on this JVM host."
          :seon.error/kind :user-input}})
      ::arglists '([request])}}})
  (install!
   {::registry registry
    ::lib 'seon.agent.fs
    ::wrappers
    {'configure! {::wrapper-fn fs.leaf/configure!}
     'grants {::wrapper-fn fs.leaf/grants ::effect :read}
     'read-file {::wrapper-fn fs.leaf/read-file ::effect :read}
     'write-file {::wrapper-fn fs.leaf/write-file ::effect :external}
     'edit-file {::wrapper-fn fs.leaf/edit-file ::effect :external}
     'list-dir {::wrapper-fn fs.leaf/list-dir ::effect :read}
     'stat {::wrapper-fn fs.leaf/stat ::effect :read}
     'file-exists? {::wrapper-fn fs.leaf/file-exists? ::effect :read}
     'home-dir {::wrapper-fn fs.leaf/home-dir ::effect :read}
     'walk-dir {::wrapper-fn fs.leaf/walk-dir ::effect :read}
     'view {::wrapper-fn fs.leaf/view ::effect :read}
     'replace! {::wrapper-fn fs.leaf/replace! ::effect :external}
     'insert! {::wrapper-fn fs.leaf/insert! ::effect :external}}})
  (install!
   {::registry registry
    ::lib 'seon.agent.shell
    ::wrappers
    {'grants {::wrapper-fn shell.leaf/grants ::effect :read}
     'run {::wrapper-fn
           (fn [request]
             (shell.leaf/run
              request (:seon.config/configuration *tx-context*)))
           ::effect :external}
     'py-run {::wrapper-fn
              (fn [request]
                (shell.leaf/py-run
                 request (:seon.config/configuration *tx-context*)))
              ::effect :external}
     'run-bg! {::wrapper-fn shell.leaf/run-bg! ::effect :external}
     'list-jobs {::wrapper-fn shell.leaf/list-jobs ::effect :read}
     'job-status {::wrapper-fn shell.leaf/job-status ::effect :read}
     'job-output {::wrapper-fn shell.leaf/job-output ::effect :read}
     'job-stop! {::wrapper-fn shell.leaf/job-stop! ::effect :external}}})
  (let [database-functions (bound-database-functions writer)
        blob-functions
        (blob/bind-leaf
         (blob.host/services
          {::blob.host/current-db! (get database-functions 'db)
           ::blob.host/query! (get database-functions 'query)
           ::blob.host/transact! (get database-functions 'transact!)}))
        functions
        ((deref #'agent.web/bind-leaf)
         (web.host/services
          {::web.host/put! (get blob-functions 'put!)
           ::web.host/transact! (get database-functions 'transact!)}))]
    (install!
     {::registry registry
      ::lib 'seon.agent.web
      ::wrappers
      {'grants {::wrapper-fn (get functions 'grants) ::effect :read}
       'fetch {::wrapper-fn (get functions 'fetch) ::effect :external}
       'search {::wrapper-fn (get functions 'search) ::effect :external}}}))
  (let [database-functions (bound-database-functions writer)
        blob-functions
        (blob/bind-leaf
         (blob.host/services
          {::blob.host/current-db! (get database-functions 'db)
           ::blob.host/query! (get database-functions 'query)
           ::blob.host/transact! (get database-functions 'transact!)}))]
    (install!
     {::registry registry
      ::lib 'my.blob
      ::wrappers
      {'put! {::wrapper-fn (get blob-functions 'put!) ::effect :idempotent}
       'get {::wrapper-fn (get blob-functions 'get) ::effect :read}
       'concat! {::wrapper-fn (get blob-functions 'concat!)
                 ::effect :idempotent}
       'text {::wrapper-fn (get blob-functions 'text) ::effect :read}
       'stat {::wrapper-fn (get blob-functions 'stat) ::effect :read}}}))
  (install!
   {::registry registry
    ::lib 'seon.db.id
    ::wrappers
    {'allocate! {::wrapper-fn
                 (fn [request]
                   (binding [db/*leaf*
                             (db.host/leaf
                              writer #(database-context writer))]
                     (db.id/allocate! request)))
                 ::arglists '([request])}
     'candidate-manifest {::wrapper-fn db.id/candidate-manifest
                          ::arglists '([generator-policies allocations])
                          ::doc "Generate one validated identity-candidate manifest."}
     'generator-policy-query {::wrapper-value db.id/generator-policy-query
                              ::doc "Query for stored generated-identity policies."}}})
  (install!
   {::registry registry
    ::lib 'seon.db.protocol
    ::wrappers
    {'query-operation {::wrapper-value protocol/query-operation}
     'pull-operation {::wrapper-value protocol/pull-operation}
     'success? {::wrapper-value ::protocol/success?}
     'result {::wrapper-value ::protocol/result}}})
  (install!
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
     'schema-definition {::wrapper-fn
                         (fn [schema-key]
                           (committed-schema-definition writer schema-key))
                         ::arglists '([schema-key])
                         ::doc "Return one registered schema's canonical definition."}}})
  (install!
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
  (install!
   {::registry registry
    ::lib 'seon.content-hash
    ::wrappers
    {'sha-256 {::wrapper-fn content-hash/sha-256
               ::arglists '([content])}}})
  (install!
   {::registry registry
    ::lib 'seon.time
    ::wrappers
    {'iso-string {::wrapper-fn time/iso-string
                  ::arglists '([instant])}}})
  (install!
   {::registry registry
    ::lib 'seon.repl.parse.repair
    ::wrappers
    {'rank-candidates {::wrapper-fn candidates/rank-candidates
                       ::arglists '([from names])}}})
  (install!
   {::registry registry
    ::lib 'seon.repl.parse
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
  (install!
   {::registry registry
    ::lib 'seon.agent.ctx
    ::wrappers
    {'install!
     {::wrapper-fn
      (fn [block-or-blocks]
        (host-context-install! (bound-database-functions writer)
                               block-or-blocks))
      ::arglists '([block-or-blocks])}
     'remove!
     {::wrapper-fn
      (fn [block-name]
        (host-context-remove! (bound-database-functions writer)
                              block-name))
      ::arglists '([block-name])}
     'read-file-text
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
  (install!
   {::registry registry
    ::lib 'my.plan
    ::wrappers
    {'active! {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/active!)}
     'blocked! {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/blocked!)}
     'document {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/document)}
     'done! {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/done!)}
     'drop! {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/drop!)}
     'list-open {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/list-open)}
     'move! {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/move!)}
     'needs! {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/needs!)}
     'next {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/next)}
     'plan! {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/plan!)}
     'reconcile! {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/reconcile!)}
     'reopen! {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/reopen!)}
     'status {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/status)}
     'step! {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/step!)}
     'tree {::wrapper-fn (toolkit-call toolkit-delegates 'my.plan/tree)}}})
  (install!
   {::registry registry
    ::lib 'my.kb
    ::wrappers
    {'recall {::wrapper-fn (toolkit-call toolkit-delegates 'my.kb/recall)}
     'remember {::wrapper-fn (toolkit-call toolkit-delegates 'my.kb/remember)}}})
  (install!
   {::registry registry
    ::lib 'my.kb.shared
    ::wrappers
    {'instructions
     {::wrapper-fn
      (toolkit-call toolkit-delegates 'my.kb.shared/instructions)}}})
  (install!
   {::registry registry
    ::lib 'my.skills
    ::wrappers
    {'list {::wrapper-fn (toolkit-call toolkit-delegates 'my.skills/list)}
     'load {::wrapper-fn (toolkit-call toolkit-delegates 'my.skills/load)}
     'unload {::wrapper-fn (toolkit-call toolkit-delegates 'my.skills/unload)}}})
  (install!
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
      ::doc "Encode a qualified field keyword as a Datastar-safe signal identifier."}}})
  (capability/installed-leaf-inventory :jvm @installed-leaves)))

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

(defn- host-form
  "Read one recorded block for the JVM with its source alias table intact."
  [source ns-sym aliases]
  (try
    (binding [tools.reader/*alias-map* aliases
              *ns* (or (find-ns ns-sym) (create-ns ns-sym))]
      (tools.reader/read-string {:read-cond :allow :features #{:clj}} source))
    (catch Throwable _ nil)))

(defn- definition-blocks
  "Top-level definition blocks from the one tools.reader source read."
  [source ns-sym aliases]
  (into []
        (comp (filter definition-form?)
              (map (fn [form]
                     (let [source (or (:source (meta form)) (pr-str form))
                           host-form (host-form source ns-sym aliases)]
                       {::block-name (str (second form))
                        ::source source
                        ;; The recorded source retains reader conditionals.
                        ;; Re-read it for `:clj`, preserving the namespace's
                        ;; alias table so `::db/db` cannot drift into `user`.
                        ::host-source (some-> host-form pr-str)}))))
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

(defn base-load-plan
  "Return the artifact-derived ordered toolkit forms rematerialized by SCI."
  {:malli/schema [:=> [:cat] ::base-load-plan]}
  []
  (let [units (mapv source-unit (toolkit-source-files))
        {::keys [ordered cycle]} (dependency-order units)]
    {::units units
     ::ordered ordered
     ::cycle cycle}))

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
  [unit block status reason unresolved-symbol]
  (cond-> {::source-path (::source-path unit)
           ::namespace (::namespace unit)
           ::block-name (::block-name block)
           ::status status}
    reason (assoc ::reason reason)
    unresolved-symbol (assoc ::unresolved-symbol unresolved-symbol)))

(defn- eval-block!
  [ctx namespace {::keys [host-form host-source]}]
  (if host-form
    (let [sci-namespace
          (sci/eval-string* ctx (str "(or (find-ns '" namespace
                                     ") (create-ns '" namespace "))"))]
      (sci/with-bindings {sci/ns sci-namespace}
        (sci/eval-form ctx host-form)))
    (sci/eval-string*
     ctx (str "(in-ns '" namespace ")\n" host-source))))

(defn load-portable-slice!
  "Eval every pure `my.*` defn block from its real source into `ctx`.

   Returns the honest ledger: block counts plus each failure's first error
   line. Failures are references to impure private helpers the pure slice
   does not carry, recorded — never silently skipped."
  ([ctx registry]
   (load-portable-slice! ctx registry (base-load-plan)))
  ([ctx registry {::keys [units ordered cycle]}]
  (let [
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
                                         "The block is outside the portable C1 class (async, JS, database, or blob capability evidence)."
                                         nil)
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
                           (map #(block-row unit % :failed ns-error nil) portable)
                           (map (fn [{::keys [host-source] :as block}]
                                  (try
                                    (eval-block! ctx namespace block)
                                    (block-row unit block :loaded nil nil)
                                    (catch Throwable throwable
                                      (block-row
                                       unit block :failed
                                       (first (str/split-lines
                                               (str (.getMessage throwable))))
                                       (:sci.impl/symbol (ex-data throwable))))))
                                portable))))))
              ordered)
        cycle-rows
        (into []
              (mapcat
               (fn [namespace]
                 (let [unit (first (filter #(= namespace (::namespace %)) units))]
                   (map #(block-row unit % :failed
                                    (str "Namespace require cycle: "
                                         (str/join ", " cycle))
                                    nil)
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
           (if-let [dependency (and (= :failed (::status row))
                                    (::unresolved-symbol row))]
             (if (contains? (get excluded-names (::namespace row) #{})
                            (name dependency))
               (-> row
                   (assoc ::status :excluded
                          ::reason (str "Depends on excluded non-portable helper `"
                                        dependency "`."))
                   (dissoc ::unresolved-symbol))
               (dissoc row ::unresolved-symbol))
             (dissoc row ::unresolved-symbol)))
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
     ::failures failures})))

(def ^:private host-toolkit-bindings
  {'my.plan
   '#{active! blocked! document done! drop! list-open move! needs! next
      plan! reconcile! reopen! status step! tree}
   'my.kb '#{recall remember}
   'my.kb.shared '#{instructions}
   'my.skills '#{list load unload}})

(def ^:private host-toolkit-implementation-namespaces
  '#{my.plan.generation my.plan.internal my.plan
     my.kb my.kb.shared my.skills})

(defn- load-host-toolkit-bindings!
  "Load the effectful toolkit closure, then reinstall its public host wrappers.

   JVM database calls are synchronous, so the source vocabulary's unqualified
   `await` is identity at this tier. Definitions load from the same alias-aware
   source units as the portable base. A small fixed-point pass honors forward
   declarations without inventing a second implementation."
  [ctx registry delegates {::keys [ordered]}]
  (doseq [namespace host-toolkit-implementation-namespaces]
    (sci/eval-string* ctx
                      (str "(in-ns '" namespace ")\n"
                           "(def await identity)")))
  (let [candidates
        (into []
              (comp
               (filter #(contains? host-toolkit-implementation-namespaces
                                   (::namespace %)))
               (mapcat
                (fn [unit]
                  (for [block (::blocks unit)
                        :when (and (string? (::host-source block))
                                   (not (pure-block? (::host-source block))))]
                    [unit block]))))
              ordered)
        unresolved
        (loop [pending candidates
               previous-count nil]
          (if (or (empty? pending) (= previous-count (count pending)))
            pending
            (let [failed
                  (into []
                        (keep
                         (fn [[unit block :as candidate]]
                           (try
                             (eval-block! ctx (::namespace unit) block)
                             nil
                             (catch Throwable _ candidate))))
                        pending)]
              (recur failed (count pending)))))
        implementations
        (into {}
              (mapcat
               (fn [[lib function-symbols]]
                 (for [function-symbol function-symbols
                       :let [qualified (symbol (str lib)
                                               (str function-symbol))
                             sci-var (sci/resolve ctx qualified)]
                       :when sci-var]
                   [qualified @sci-var])))
              host-toolkit-bindings)
        expected
        (into #{}
              (mapcat
               (fn [[lib function-symbols]]
                 (map #(symbol (str lib) (str %)) function-symbols)))
              host-toolkit-bindings)
        missing (remove #(contains? implementations %) expected)]
    (when (seq missing)
      (throw
       (ex-info "The JVM host toolkit binding closure did not load."
                {:seon.host/missing-toolkit-bindings (vec (sort missing))
                 :seon.host/unresolved-toolkit-blocks
                 (mapv (fn [[unit block]]
                         (symbol (str (::namespace unit))
                                 (::block-name block)))
                       unresolved)})))
    (reset! delegates implementations)
    (doseq [lib (keys host-toolkit-bindings)]
      (install-registered-wrappers!
       {::registry registry ::ctx ctx ::lib lib}))
    nil))

(defn build-base!
  "Build the one shared base context for a host serving one cluster.

   Every capability namespace provisions through the one wrapper
   registry: [[register-host-capabilities!]] seeds it from the writer
   coordinates, and the registry-backed `:load-fn` serves first requires
   lazily in the base and every fork. The portable `my.*` pure slice
   loads from its real sources (its requires exercise that lazy path).
   The returned report is the honest real-vs-failed load ledger."
  {:malli/schema
   [:function
    [:=> [:cat ::writer] ::base]
    [:=> [:cat ::writer ::base-load-plan] ::base]]}
  ([writer]
   (build-base! writer (base-load-plan)))
  ([writer load-plan]
  (let [wrapper-registry (registry)
        toolkit-delegates (atom {})
        tier-inventory
        (register-host-capabilities! wrapper-registry writer
                                     toolkit-delegates)
        ctx (sci/init
             {:load-fn (registry-load-fn wrapper-registry writer)
              :namespaces {'clojure.core interrupt/clojure-core
                           'clojure.string interrupt/clojure-string}
              :classes {'java.util.Date java.util.Date
                        'java.lang.Long java.lang.Long
                        'Long java.lang.Long}
              :interrupt-fn
              (fn []
                (when-let [holder (::guard/holder (sci.ctx-store/get-ctx))]
                  ((::guard/check! holder))))})
        report (load-portable-slice! ctx wrapper-registry load-plan)
        _ (load-host-toolkit-bindings! ctx wrapper-registry
                                       toolkit-delegates load-plan)
        _ (stamp-shared-base-vars! ctx)]
    {::ctx ctx
     ::report report
     ::registry wrapper-registry
     ::tier-inventory tier-inventory})))

(defn fork-context
  "Fork one private agent context from the shared base."
  {:malli/schema [:=> [:cat ::base] ::ctx]}
  [{::keys [ctx]}]
  (let [holder (guard/holder)]
    (assoc (sci/fork ctx)
           ::guard/holder holder
           :interrupt-fn (guard/interrupt-fn holder))))

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
  (let [rows (query-writer! writer agent-def-sources-query [ns-sym])]
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

   An explicit `database` becomes the protocol request's `:seon.db/db`;
   omission preserves the current-head behavior used by receipt recording.
   `candidates` ride the protocol's `::generated-candidates` field, so
   the writer validates and commits managed identity allocation in the
   same transaction — the exact mechanism `seon.db.id/allocate!` uses."
  [writer {::keys [tx-data candidates database]}]
  (let [transact! (get (bound-database-functions writer) 'transact!)
        request (cond-> {:seon.db/tx-data (vec tx-data)}
                  database (assoc :seon.db/db database)
                  (seq candidates)
                  (assoc :seon.db.id/generated-candidates (vec candidates)))
        report (transact! request)]
    (if (:seon.error/message report)
      report
      {:seon.db/ok? true
       :db-after (select-keys (:db-after report)
                              [:db-name :t :datahike/commit-id])})))

(defn query-writer!
  "Run one host-internal query through the retained writer session."
  {:malli/schema [:=> [:cat ::writer :any [:sequential :any]] :any]}
  [writer query-form arguments]
  (apply (get (bound-database-functions writer) 'query)
         query-form arguments))

(defn query-writer-at!
  "Run one host query at the caller's explicit immutable database value."
  {:malli/schema
   [:=> [:cat ::writer :seon.db/db :any [:sequential :any]] :any]}
  [writer database query-form arguments]
  ((get (bound-database-functions writer) 'query)
   {:seon.db/query query-form
    :seon.db/db database
    :seon.db/args (vec arguments)}))

(def ^:private pinned-namespace-sources-query
  '[:find ?sym ?source ?transaction
    :in $ ?ns-sym
    :where
    [?namespace :seon.ns/name ?ns-sym]
    [?function :seon.fn/ns ?namespace]
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/source ?source ?transaction]
    [?transaction :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/repl]])

(defn verify-pinned-function!
  "Verify one authored source identity at an explicit immutable database value."
  {:malli/schema
   [:=> [:cat ::writer :seon.db/db :qualified-symbol :string]
    [:vector [:tuple :string :string :int]]]}
  [writer database function-symbol source-fingerprint]
  (let [ns-sym (symbol (namespace function-symbol))
        rows (query-writer-at! writer database pinned-namespace-sources-query
                               [ns-sym])]
    (when (:seon/error rows)
      (throw (ex-info (get-in rows [:seon/error :seon.error/message])
                      {:seon.error/kind :core-bug})))
    (let [target-source
          (some (fn [[sym source _transaction]]
                  (when (= function-symbol (symbol sym)) source))
                rows)]
      (when-not target-source
        (throw (ex-info "The requested current agent function does not exist."
                        {:seon.execution/function-symbol function-symbol
                         :seon.error/kind :agent})))
      (when-not (= source-fingerprint
                   (content-hash/sha-256 target-source))
        (throw (ex-info "The requested function source is no longer current."
                        {:seon.execution/function-symbol function-symbol
                         :seon.error/kind :agent})))
      (vec rows))))

(defn- stamp-source-root!
  [sci-var source]
  (let [source-fingerprint (content-hash/sha-256 source)
        root @sci-var]
    (sci/alter-var-root
     sci-var
     (constantly
      (with-meta root
        (assoc (meta root)
               :seon.fn/source-fingerprint source-fingerprint)))))
  sci-var)

(defn materialize-pinned-function!
  "Materialize one pinned authored function in a detached disposable context."
  {:malli/schema
   [:=> [:catn [::writer ::writer]
               [::ctx ::ctx]
               [:seon.db/db :seon.db/db]
               [::function-symbol :qualified-symbol]
               [::source-fingerprint :string]
               [::reconcile-ephemeral! ::reconcile-ephemeral!]]
    ::materialized-function]}
  [writer retained-ctx database function-symbol source-fingerprint
   reconcile-ephemeral!]
  (let [ns-sym (symbol (namespace function-symbol))
        rows (verify-pinned-function! writer database function-symbol
                                      source-fingerprint)]
    (let [ctx (sci/fork retained-ctx)]
        ;; A plain fork retains identical SCI Vars. Remove then recreate the
        ;; authored namespace before replay so pinned definitions cannot bind
        ;; shared roots in the retained context or registry.
        (sci/eval-string* ctx (str "(remove-ns '" ns-sym ")"))
        (ensure-context-ns! ctx ns-sym)
        (let [ordered (sort-by #(nth % 2) rows)
              envelopes
              (replay-defs!
               ctx
               (mapv (fn [[_ source _]]
                       (str "(in-ns '" ns-sym ")\n" source))
                     ordered))]
          (when-let [failed (first (remove :seon.eval/ok? envelopes))]
            (throw (ex-info (get-in failed [:seon/error :seon.error/message])
                            {:seon.error/kind :agent})))
          (let [vars-by-symbol
                (into {}
                      (keep (fn [[sym source _]]
                              (when-let [sci-var (sci/resolve ctx (symbol sym))]
                                [(symbol sym)
                                 (stamp-source-root! sci-var source)])))
                      ordered)
                target-var (get vars-by-symbol function-symbol)]
            (when-not target-var
              (throw
               (ex-info "The requested current agent function did not load."
                        {:seon.execution/function-symbol function-symbol
                         :seon.error/kind :core-bug})))
            (let [reconciled (reconcile-ephemeral! vars-by-symbol)]
              (when (:seon/error reconciled)
                (throw
                 (ex-info
                  (get-in reconciled [:seon/error :seon.error/message])
                  {:seon.error/kind :core-bug}))))
            [ctx target-var])))))

(def ^:private acquisition-page-size 32)
(def ^:private acquisition-page-max-result-weight 60000)

(def ^:private committed-row-query
  '[:find ?identity ?form (pull ?tx ?provenance-pattern)
    :in $ [?e ...] ?identity-attr ?form-attr ?provenance-pattern
    :where
    [?e ?identity-attr ?identity]
    [?e ?form-attr ?form ?tx]])

(defn- failed-read?
  [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- acquisition-error!
  [stage identity-attr form-attr value]
  (throw
    (ex-info
      (str "Committed program acquisition failed while reading "
           identity-attr " + " form-attr " at stage " stage ".")
      {:seon.db/error value
       :seon.host.context/stage stage
       :seon.host.context/identity-attribute identity-attr
       :seon.host.context/form-attribute form-attr
       :seon.error/kind :core-bug})))

(defn- acquire-row-pages!
  [query! database entity-ids identity-attr form-attr]
  (reduce
    (fn [rows entity-id]
      (let [page-rows
            (query!
              {:seon.db/db database
               :seon.db/query committed-row-query
               ;; One canonical row is the minimum exact page. Forms are
               ;; variable-length strings, so a larger entity-count cannot
               ;; imply a bounded result weight.
               :seon.db/args
               [[entity-id] identity-attr form-attr
                schema/asserting-transaction-provenance-pattern]
               :seon.db/max-result-weight
               acquisition-page-max-result-weight})]
        (when (failed-read? page-rows)
          (acquisition-error! :query identity-attr form-attr page-rows))
        (into rows page-rows)))
    []
    entity-ids))

(defn- acquire-identity-stream!
  [index-page! query! database identity-attr form-attr]
  (loop [cursor nil
         rows []]
    (let [page
          (index-page!
            (cond-> {:seon.db/db database
                     :seon.db/index :aevt
                     :seon.db/components [identity-attr]
                     :seon.db/direction :forward
                     :seon.db/limit acquisition-page-size
                     :seon.db/max-result-weight
                     acquisition-page-max-result-weight}
              cursor (assoc :seon.db/cursor cursor)))]
      (when (failed-read? page)
        (acquisition-error! :index-page identity-attr form-attr page))
      (let [entity-ids (mapv first (:datahike.index-page/datoms page))
            page-rows
            (acquire-row-pages!
              query! database entity-ids identity-attr form-attr)
            next-rows (into rows page-rows)]
        (if (:datahike.index-page/complete? page)
          next-rows
          (recur (:datahike.index-page/cursor page) next-rows))))))

(defn acquire-committed-projection!
  "Acquire and compile the complete committed program at one database value.

   Each identity stream is paged through AEVT and every variable-size form is
   then read one entity at a time. The immutable database value pins all reads
   while the per-request result-weight bound remains independent of total
   corpus size."
  {:malli/schema
   [:function
    [:=> [:catn [::writer ::writer]] :map]
    [:=> [:catn [::writer ::writer] [::artifact-exports [:set :symbol]]]
     :map]]}
  ([writer]
   (acquire-committed-projection! writer #{}))
  ([writer artifact-exports]
  (try
    (let [database (resolve-head! writer)]
      (if (:seon/error database)
        database
        (let [database-functions (bound-database-functions writer)
              index-page! (get database-functions 'index-page)
              query! (get database-functions 'query)
              schema-rows
              (acquire-identity-stream!
                index-page! query! database
                :seon.schema/key :seon.schema/form)
              contract-rows
              (acquire-identity-stream!
                index-page! query! database
                :seon.fn/sym :seon.fn/spec)
              source-rows
              (acquire-identity-stream!
                index-page! query! database
                :seon.fn/sym :seon.fn/source)]
          {::database database
           ::projection
           (schema/projection-from-rows
             {:seon.schema/schema-rows schema-rows
              :seon.schema/function-contract-rows contract-rows
              :seon.schema/function-source-rows source-rows
              :seon.schema/artifact-exports artifact-exports})})))
    (catch Throwable throwable
      {:seon/error
       {:seon.error/message
        (str "Committed schema projection is invalid: "
             (.getMessage throwable))
        :seon.error/kind :core-bug
        :seon.error/data (ex-data throwable)}}))))

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
                ::artifact-exports
                (or (::artifact-exports current)
                    (:seon.schema.projection/artifact-exports
                     (::projection current))
                    #{})
                ::committed-basis committed-basis}
               current))))
  (let [read-result
        (acquire-committed-projection!
         writer (or (::artifact-exports @projection-state) #{}))
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
                    ::artifact-exports
                    (or (::artifact-exports current)
                        (:seon.schema.projection/artifact-exports
                         (::projection current))
                        #{})
                    ::committed-basis committed-basis}
                   (if (and (::pending? current)
                            (= committed-basis
                               (::committed-basis current)))
                     {::fault (:seon/error acquired)
                      ::artifact-exports
                      (or (::artifact-exports current)
                          (:seon.schema.projection/artifact-exports
                           (::projection current))
                          #{})
                      ::committed-basis committed-basis}
                     current))))
        (let [current @projection-state]
          (if (>= (get-in current [::database :t] -1) committed-basis)
            current
            acquired)))
      (publish-committed-projection! projection-state acquired))))

(defn transact-writer!
  "Commit host-derived transaction data through the retained writer.

   The three-argument form sends the caller's immutable database value in the
   transaction request. The two-argument form resolves the current head."
  {:malli/schema
   [:function
    [:=> [:cat ::writer [:vector :any]] :map]
    [:=> [:cat ::writer :seon.db/db [:vector :any]] :map]]}
  ([writer tx-data]
   (record-transact! writer {::tx-data tx-data}))
  ([writer database tx-data]
   (record-transact! writer {::database database ::tx-data tx-data})))

(def ^:private eval-allocation-key ::eval-allocation)
(def ^:private max-allocation-attempts 16)

(defn- eval-id-generator
  "The stored generator policy for `:seon.eval/id`; cached per session.

   The policy is a database fact on the `:seon.schema` row; process-local
   caching is safe because a policy change would arrive with a new
   program, not mid-session."
  [writer]
  (or @(::eval-generator writer)
      (let [rows (query-writer! writer db.id/generator-policy-query
                                [[:seon.eval/id]])]
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
                   agent-id forms var-meta new-schema-keys output resolution
                   database-edn-cap]}]
  (let [program-tx-data
        (when (:seon.eval/ok? envelope)
          (record/tee-tx-data
           {::record/forms (or forms [])
            ::record/source source
            ::record/ns-sym ns-sym
            ::record/resolution resolution
            ::record/var-meta (or var-meta {})
            ::record/new-schema-keys (or new-schema-keys #{})
            ::record/at at}))
        function-rows
        (into []
              (filter #(and (map? %)
                            (contains? % :seon.fn/sym)
                            (contains? % :seon.fn/source)))
              program-tx-data)
        tx-data
        (into (record/terminal-tx-data
               {:seon.eval/id eval-id
                ::record/envelope envelope
                ::record/at at
                ::record/duration-ms duration-ms
                ::record/source source
                ::record/narration narration
                ::record/ns-sym ns-sym
                ::record/agent-ref [:seon.agent/id agent-id]
                ::record/output output
                ::record/database-edn-cap database-edn-cap})
              program-tx-data)]
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
        (assoc ::projection-changed? projection-change?
               ::function-rows function-rows)))))
