(ns seon.cluster
  "Process entry for starting, inspecting, and stopping named clusters.

  `start!` resolves the closed bootstrap configuration, opens and
  advertises an io-prepl, then builds the remaining instance from the
  process-root store and executors, a source-digest ancestor, the
  cluster's database branch and configuration, recovered run facts,
  the root agent, agent and render flows, and the web server. The
  io-prepl and the partially built instance remain available when a
  later layer fails.

  A JVM may host several named cluster instances. They share the
  process-root store and executor pair; branch connections, flows,
  routing state, advertisements, and web servers remain per cluster.
  `readiness` derives its report from the instance and its database.
  `stop!` idempotently unwinds only the addressed instance and releases
  the shared store when its last holder stops."
  (:refer-clojure :exclude [reset!])
  (:require [clojure.core.async :as async]
            [seon.ai :as ai]
            [clojure.core.async.flow :as flow.core]
            [clojure.core.server]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.process :as cluster.process]
            [seon.cluster.wake :as wake]
            [seon.error :as error]
            [seon.cluster.run :as run]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test.check.generators :as gen]
            [datahike.api :as d]
            [seon.cluster.ancestor :as ancestor]
            [seon.cluster.registry :as registry]
            [seon.cluster.run]
            [seon.cluster.store :as store]
            [clojure.string :as str]
            [seon.config :as config]
            [seon.flow :as flow]
            [seon.fn :as seon.fn]
            [seon.problems :as problems]
            [seon.render.block :as block]
            [seon.render.root :as root]
            [seon.render.web :as web]
            [taoensso.timbre :as log]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Bootstrap configuration — the CLOSED pre-store key set.
;;; A key that the database could own does not belong here; the closed
;;; map makes that a review-time refusal, not a convention.
;;; ---------------------------------------------------------------------------

;;; The running instance value returned by start!. Named predicates for
;;; the genuinely opaque platform objects; everything else is ordinary
;;; data.

(defn socket-server?
  "True for the java.net.ServerSocket an io-prepl listens on."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? java.net.ServerSocket value))

(schema/register-core-predicate! 'seon.cluster/socket-server?
                                 socket-server?)

(defonce ^:private generator-server
  (delay (java.net.ServerSocket. 0)))

(def socket-server-generator
  (gen/fmap (fn [_] @generator-server) (gen/return nil)))

(schema.edn/load! {})
(schema/activate! (schema/snapshot))

;;; ---------------------------------------------------------------------------
;;; Pure resolution — defaults are THE defaults document for this layer
;;; ---------------------------------------------------------------------------

(defn- refused!
  [message offense]
  (throw (ex-info message
                  {:seon.error/kind :seon.boot/refused
                   :seon.boot/offense offense})))

(defn- require-candidate-value
  [schema-key value message]
  (if (schema/valid-candidate-value? schema-key value)
    value
    (refused! message
              {:seon.boot/schema schema-key
               :seon.boot/value value
               :seon.boot/explanation
               (schema/explain-candidate-value schema-key value)})))

(declare cluster-paths)

(defn resolve-bootstrap
  "Resolve overrides into one complete bootstrap configuration.
  Every key optional; absent = default. Defaults: cluster-name
  \"default\" (just a name, nothing special), root \"data/clusters\",
  prepl-host \"127.0.0.1\", prepl-port 0 (ephemeral — the advertisement
  carries the real port), log-dir derived as <root>/<name>/logs,
  store-dir derived as <root>/store — the PROCESS-root store every
  cluster branches from (ancestor-branch stays absent unless supplied).
  Refuses (throws ex-info {:seon.error/kind :seon.boot/refused ...}) on
  any unknown key or invalid value — the closed schema is the gate, not
  a convention."
  {:malli/schema [:=> [:cat :seon.boot/overrides] :seon.boot/config]}
  [overrides]
  (require-candidate-value
   :seon.boot/overrides
   overrides
   "The bootstrap overrides were refused.")
  (let [defaults {:seon.boot/cluster-name "default"
                  :seon.boot/root "data/clusters"
                  :seon.boot/prepl-host "127.0.0.1"
                  :seon.boot/prepl-port 0}
        base (merge defaults overrides)
        derived-store-dir
        (str (io/file (:seon.boot/root base) "store"))
        derived-log-dir
        (:seon.boot/log-dir
         (cluster-paths (:seon.boot/root base)
                        (:seon.boot/cluster-name base)))]
    (require-candidate-value
     :seon.boot/config
     (merge {:seon.boot/log-dir derived-log-dir
             :seon.boot/store-dir derived-store-dir}
            base)
     "The resolved bootstrap configuration was refused.")))

(defn cluster-paths
  "Derive every per-cluster path from (root, cluster-name).
  Convention owns the layout: the cluster directory, its log directory,
  and its advertisement file. The STORE is per process root under
  branch-per-cluster (b2-plan section 0); its path is bootstrap config,
  never a per-cluster derivation. One derivation — no other code builds
  these paths."
  {:malli/schema [:=> [:cat :seon.boot/root :seon.boot/cluster-name]
                  [:map {:closed true}
                   [:seon.boot/cluster-dir :string]
                   [:seon.boot/advertisement-file :string]
                   [:seon.boot/log-dir :string]]]}
  [root cluster-name]
  (let [cluster-dir (io/file root cluster-name)]
    {:seon.boot/cluster-dir (str cluster-dir)
     :seon.boot/advertisement-file
     (str (io/file cluster-dir "prepl.edn"))
     :seon.boot/log-dir (str (io/file cluster-dir "logs"))}))

;;; ---------------------------------------------------------------------------
;;; The shared root executors — created once per JVM, never per cluster
;;; ---------------------------------------------------------------------------

(defonce ^:private root-executor-pair
  (delay
    {:compute
     (flow/bounded-platform-executor
      (.availableProcessors (Runtime/getRuntime)))
     :io (java.util.concurrent.Executors/newCachedThreadPool)}))

(defn root-executors
  "The process root's two shared executors.
  One bounded `:compute` platform-thread executor (parallelism =
  available processors — a computed hardware fact) and one `:io`
  executor for blocking transport. Idempotent per JVM: repeated calls return the SAME
  executor objects (the root owns them; cluster graphs share them).
  This is deliberately process-global state — the one sanctioned kind:
  a genuinely process-local artifact, like a compiler state or a
  connection."
  ;; the shape is REGISTERED (boot.edn), not inlined here: a
  ;; `:malli/schema` goes straight to malli, which has no resolver for a
  ;; bare `[:fn sym]`, so the inlined form could never compile and this
  ;; contract had never once been checked until instrumentation
  ;; collected it
  {:malli/schema [:=> [:cat] :seon.boot/executors]}
  []
  @root-executor-pair)

;;; ---------------------------------------------------------------------------
;;; The instance lifecycle
;;; ---------------------------------------------------------------------------

(defonce ^:private running-instances (atom {}))

(def ^:private starting ::starting)

(defn- server-name
  [cluster-name]
  (str "seon.cluster/" cluster-name))

(defn- reserve-cluster!
  [cluster-name]
  (loop []
    (let [instances @running-instances]
      (if (contains? instances cluster-name)
        (refused! "The cluster already has an instance in this process."
                  {:seon.boot/cluster-name cluster-name})
        (when-not (compare-and-set! running-instances
                                    instances
                                    (assoc instances cluster-name starting))
          (recur))))))

(defn- release-reservation!
  [cluster-name]
  (swap! running-instances
         (fn [instances]
           (if (= starting (get instances cluster-name))
             (dissoc instances cluster-name)
             instances))))

(defn- create-directories!
  [config paths]
  (doseq [path [(:seon.boot/cluster-dir paths)
                (:seon.boot/log-dir config)]]
    (.mkdirs (io/file path))))

(defn- write-advertisement!
  [paths advertisement]
  (spit (:seon.boot/advertisement-file paths)
        (str (pr-str advertisement) "\n")))

;;; ---------------------------------------------------------------------------
;;; The process-root store — opened once, shared by every instance
;;; ---------------------------------------------------------------------------

;;; Deliberately process-global, the same sanctioned kind as the root
;;; executors: a genuinely process-local artifact (a connection under a
;;; lifetime flock) that clusters SHARE rather than each opening. The
;;; count is the holder count, not a status flag — the last instance out
;;; releases the store, and the flock with it.
(defonce ^:private root-store-holder (atom {}))

(defn- acquire-root-store!
  "The ONE store at `store-dir`, opened on first use and shared after."
  [store-dir]
  (locking root-store-holder
    (if-let [held (get @root-store-holder store-dir)]
      (do
        (swap! root-store-holder update-in [store-dir ::holders] inc)
        (:seon.store/store held))
      ; open OUTSIDE the map first: a failed open must leave no entry
      (let [store (store/open-store! {:seon.store/dir store-dir})]
        (swap! root-store-holder assoc store-dir
               {:seon.store/store store ::holders 1})
        store))))

(defn- release-root-store!
  "Drop one holder; the LAST one releases the store and its flock."
  [store-dir]
  (locking root-store-holder
    (when-let [held (get @root-store-holder store-dir)]
      (let [remaining (dec (long (::holders held)))]
        (if (pos? remaining)
          (swap! root-store-holder assoc-in [store-dir ::holders] remaining)
          (do
            ; release FIRST: a failure leaves this exact flock-held store
            ; addressable here for the stop retry
            (store/release-store! (:seon.store/store held))
            (swap! root-store-holder dissoc store-dir))))))
  nil)

;;; ---------------------------------------------------------------------------
;;; The default ancestor population
;;; ---------------------------------------------------------------------------

(def boot-process-identity
  "The opaque provenance identity for the boot schema population."
  "seon.db.process/boot")

(def ^:private schema-row-pattern
  [:seon.schema/key
   :seon.schema/form
   :seon.schema/created-at
   :seon.db.id/generator
   {:seon.schema/ns [:seon.ns/name]}])

(defn- declaration-changes
  "Missing declarations, refusing non-accretive storage changes."
  [db]
  (into
   []
   (keep
    (fn [{attribute :db/ident :as declaration}]
      (if-let [installed (get (:schema db) attribute)]
        (when-not
         (= (dissoc declaration :db/ident)
            (select-keys installed (keys (dissoc declaration :db/ident))))
          (refused!
           "The cluster schema cannot be changed in place; reset it."
           {:seon.boot/attribute attribute
            :seon.boot/installed installed
            :seon.boot/current declaration}))
        declaration)))
   (schema.datahike/malli->datahike-schema
    (schema/canonical-database-attributes))))

(defn- missing-process-rows
  [db]
  (let [present
        (into
         #{}
         (d/q '[:find [?id ...]
                :where [_ :seon.db.process/id ?id]]
              db))]
    (into
     []
     (comp
      (remove present)
      (map (fn [process-id] {:seon.db.process/id process-id})))
     [boot-process-identity config/managing-process-identity])))

(defn- schema-row-changes
  [db now]
  (into
   []
   (keep
    (fn [{schema-key :seon.schema/key :as desired}]
      (let [current
            (some-> (d/pull db schema-row-pattern
                            [:seon.schema/key schema-key])
                    (dissoc :db/id))
            current-created-at (:seon.schema/created-at current)
            desired
            (cond-> desired
              current-created-at
              (assoc :seon.schema/created-at
                     current-created-at))]
        (when-not (= desired (select-keys current (keys desired)))
          desired))))
   (schema/canonical-schema-rows now)))

(defn- accrete-schema-population!
  "Install the current additive schema population on one branch.

  Registration and database installation are separate in Datahike's
  `:write` schema mode. Every opened branch therefore passes through this
  choke point before any domain transaction. Missing declarations and
  canonical rows accrete; an incompatible declaration refuses loudly and
  names reset as the remedy. A converged reopen issues no transaction."
  [connection]
  (let [declarations (declaration-changes @connection)]
    (when (seq declarations)
      (d/transact connection {:tx-data declarations})))
  (let [process-rows (missing-process-rows @connection)]
    (when (seq process-rows)
      (d/transact connection {:tx-data process-rows})))
  (let [schema-rows (schema-row-changes @connection (java.util.Date.))]
    (when (seq schema-rows)
      (d/transact connection
                  {:tx-data schema-rows
                   :tx-meta
                   {:seon.db/process
                    [:seon.db.process/id boot-process-identity]}})))
  nil)

(defn populate-ancestor!
  "The default ancestor content: this code's own schema population.
  Named by symbol in `ancestor/ensure!`'s request, so the producer is
  data and N5's program-graph indexer replaces it without touching the
  boot path. The convergent population transactions are DERIVED, never
  hand-written:
  the Datahike declarations of every registered database attribute, the
  core process entities the provenance refs resolve to (genesis data —
  bootstrap content lives in the ancestor), and the canonical schema rows
  asserted with that process provenance."
  {:malli/schema
   [:=> [:cat [:map [:seon.store/branch-connection
                     :seon.store/branch-connection]]]
    :nil]}
  [{connection :seon.store/branch-connection}]
  (accrete-schema-population! connection)
  (seon.fn/index! {:seon.store/branch-connection connection
                   :seon.db/process
                   [:seon.db.process/id boot-process-identity]
                   :seon.fn/roots seon.fn/source-roots})
  nil)

;;; ---------------------------------------------------------------------------
;;; The tower above the REPL
;;; ---------------------------------------------------------------------------

;;; The roots the ancestor's identity is computed over. Today the
;;; population above is derived from the Clojure program plus the
;;; classpath schema population. The indexer reads only Clojure files,
;;; while the ancestor digest also covers the EDN declarations whose
;;; Datahike schema and canonical rows are installed into the ancestor.
(def ancestor-roots
  "The complete file roots whose content identifies an ancestor."
  (conj seon.fn/source-roots "resources"))

(defn- current-source-digest
  []
  (ancestor/digest {:seon.ancestor/roots ancestor-roots}))

(defn- ensure-current-ancestor!
  [store]
  (ancestor/ensure!
   {:seon.store/store store
    :seon.ancestor/digest (current-source-digest)
    :seon.ancestor/populate `populate-ancestor!}))

(defn- ancestor-branch!
  "The ancestor branch this cluster forks from.
  A supplied `:seon.boot/ancestor-branch` is used AS GIVEN — the caller
  named an existing ancestor and `ensure-cluster!` refuses if it is not
  in the roster. Absent, the ancestor of this source tree is ensured
  (idempotent; the roster is the whole cache)."
  [store config]
  (or (:seon.boot/ancestor-branch config)
      (:seon.ancestor/branch
       (ensure-current-ancestor! store))))

(defn- count-installed
  [db attribute]
  (if (contains? (:schema db) attribute)
    (or
     (d/q '[:find (count ?entity) .
            :in $ ?attribute
            :where [?entity ?attribute]]
          db
          attribute)
     0)
    0))

(defn- program-currentness
  [db current-digest]
  (let [recorded-digests
        (if (contains? (:schema db) :seon.ancestor/digest)
          (into
           #{}
           (d/q '[:find [?digest ...]
                  :where [_ :seon.ancestor/digest ?digest]]
                db))
          #{})
        namespace-count (count-installed db :seon.ns/name)
        function-count (count-installed db :seon.fn/sym)
        namespace-populated? (pos? namespace-count)
        function-populated? (pos? function-count)
        partial? (not= namespace-populated? function-populated?)
        populated? (and namespace-populated? function-populated?)
        one-digest? (= 1 (count recorded-digests))
        digest-current? (= #{current-digest} recorded-digests)]
    {:seon.ancestor/coherent? (and one-digest? populated?)
     :seon.ancestor/current? (and digest-current? populated?)
     :seon.ancestor/stale? (and one-digest?
                                populated?
                                (not digest-current?))
     :seon.ancestor/partial? partial?
     :seon.ancestor/recorded-digests recorded-digests
     :seon.ancestor/current-digest current-digest
     :seon.ancestor/namespace-count namespace-count
     :seon.ancestor/function-count function-count}))

(defn- require-coherent-program!
  [connection cluster-name current-digest]
  (let [currentness (program-currentness @connection current-digest)]
    (when-not (:seon.ancestor/coherent? currentness)
      (let [condition
            (cond
              (:seon.ancestor/partial? currentness)
              (str "partial ("
                   (:seon.ancestor/namespace-count currentness)
                   " namespace rows and "
                   (:seon.ancestor/function-count currentness)
                   " function rows)")

              (empty? (:seon.ancestor/recorded-digests currentness))
              "unprimed (no recorded source digest)"

              (> (count (:seon.ancestor/recorded-digests currentness)) 1)
              (str "incoherent (multiple recorded source digests "
                   (pr-str (:seon.ancestor/recorded-digests currentness))
                   ")")

              :else
              "unprimed (the source-owned program rows are absent)")]
        (refused!
         (str
          "Cluster `" cluster-name "` was not started because its program "
          "graph is " condition ". "
          "`bin/seon index " cluster-name "` preserves history: it refreshes "
          "source-owned namespace, function, schema, and test facts while "
          "leaving messages, runs, agents, and agent-authored facts intact. "
          "`bin/seon reset " cluster-name "` destroys history and reforks a "
          "clean cluster from the current ancestor.")
         currentness)))
    (when (:seon.ancestor/stale? currentness)
      (log/info
       (str "seon " cluster-name " source: independent older corpus "
            (first (:seon.ancestor/recorded-digests currentness))
            " (current baseline " current-digest
            "); start allowed, `bin/seon index " cluster-name
            "` explicitly synchronizes it")))
    currentness))

(defn refresh-baseline!
  "Create or reuse the current content-addressed ancestor."
  {:malli/schema [:=> [:cat :seon.boot/root] :seon.ancestor/ensured]}
  [root]
  (let [config (resolve-bootstrap {:seon.boot/root root})
        store-dir (:seon.boot/store-dir config)
        held-store (acquire-root-store! store-dir)]
    (try
      (ensure-current-ancestor! held-store)
      (finally
        (release-root-store! store-dir)))))

(defn index!
  "Prime one cluster from source while preserving independent facts.

  The current additive schema population is installed first, then the
  ONE `seon.fn/index!` exact-reconciles only rows whose defining datoms
  carry the boot process identity. Messages, agents, runs, and
  agent-authored declarations remain outside that owned slice.

  The recorded `:seon.ancestor/digest` advances in the index
  transaction. On a primed cluster it means the source digest whose
  source-owned program facts were last synchronized while independent
  facts were preserved; it no longer means the ancestor branch from
  which this cluster was originally forked. A converged call writes
  nothing."
  {:malli/schema [:=> [:cat :seon.boot/instance] :seon.reconcile/result]}
  [instance]
  (let [connection
        (or
         (:seon.boot/cluster-connection instance)
         (refused!
          "Source indexing requires an addressable cluster connection."
          {:seon.boot/cluster-name
           (get-in instance [:seon.boot/config :seon.boot/cluster-name])}))]
    (accrete-schema-population! connection)
    (seon.fn/index!
     {:seon.store/branch-connection connection
      :seon.db/process [:seon.db.process/id boot-process-identity]
      :seon.fn/roots seon.fn/source-roots
      :seon.ancestor/digest (current-source-digest)})))

;;; ---------------------------------------------------------------------------
;;; Recovery — the pass that runs before anything resumes
;;; ---------------------------------------------------------------------------

(defn process-identity
  "This process's identity as a run holder: `<pid>-<start-millis>`.
  (pid, start-instant) is the process identity the whole system already
  uses; a bare pid is recyclable and a recycled pid claiming to hold a
  run is the one confusion recovery must not have. The run loop's
  handle should carry THIS value as `:seon.cluster.run/process`, so the
  holder a run names and the holder recovery judges are the same string."
  {:malli/schema [:=> [:cat :seon.boot/advertisement] :seon.cluster.run/process]}
  [advertisement]
  (str (:seon.boot/pid advertisement) "-"
       (inst-ms (:seon.boot/start-instant advertisement))))

(defn- recover-runs!
  "Settle every run held by a dead process, before anything resumes.
  BY FACT, NEVER BY CLOCK: a run whose holder is not in the live set is
  closed immediately, its custody and agent pointer are retracted, and
  its dangling receipts — those carrying no terminal fact — get
  `interrupted-at` asserted (presence is the state; there is no status).
  No clock is consulted at all: this process just started, so on this
  branch every other holder is provably gone (one connection per
  branch, one process per store).

  Nothing here re-opens, re-plans, or re-executes. `recover-tx` is pure
  over the values it is handed and returns [] for a run needing
  nothing, so a clean boot commits nothing at all."
  [connection process]
  (let [db @connection
        now (java.util.Date.)
        open-runs (d/q '[:find [?run-id ...]
                         :where
                         [?run :seon.cluster.run/id ?run-id]
                         (not [?run :seon.cluster.run/closed-at _])]
                       db)
        ;; the decision moved INSIDE the transaction (custody revision,
        ;; Revision 4): `recover-call` reads each run's receipts at
        ;; transaction time, so this caller only names the open runs —
        ;; a stale-basis recovery stamping a settled receipt is
        ;; unrepresentable
        operations (into []
                         (mapcat
                          (fn [run-id]
                            (run/recover-tx
                             {:seon.cluster.run/id run-id
                              :seon.cluster.run/live-processes #{process}
                              :seon.cluster.run/now now})))
                         open-runs)]
    (when (seq operations)
      (d/transact connection operations))
    {:seon.boot/recovered-runs (count open-runs)
     :seon.boot/recovery-operations (count operations)}))

;;; ---------------------------------------------------------------------------
;;; The armed layers — the fault consumer, the root agent, and the loop
;;; ---------------------------------------------------------------------------

;;; THE ROOT AGENT. One entity, seeded at boot, idempotent by identity.
;;; It costs one datom and no process: an agent is attributes and
;;; connections, so "exists" is the id and nothing else. It exists so
;;; escalation has somewhere honest to go — before it, the escalation
;;; dial had to ship absent because naming an agent that might not
;;; exist would have been a lie.
(def root-agent-id "root")

(defn- seed-root-agent!
  "The root agent, and the blocks that make it a PAGE.

  Both in one place because they are one fact about a fresh cluster:
  every cluster has a root from its first transaction, and a root with
  no blocks would serve a blank page while claiming to be a view. The
  block install is an idempotent upsert by name, so a reboot rewrites
  the same set and any block an agent added itself survives."
  [connection]
  (d/transact connection
              (cluster.agent/creation-tx
               {:seon.cluster.agent/id root-agent-id
                :seon.ns/name 'my.agents.root
                ;; Root's page is already its own complete block set. Suppress
                ;; the ordinary prompt seed here; root/seed-tx below remains
                ;; the one convergent owner of the root page.
                :seon.cluster.agent/seed-blocks []}))
  (let [seed (root/seed-tx @connection root-agent-id)]
    (when (seq seed)
      (d/transact connection seed))))

(defn- serve!
  "Bind the cluster's web view, or refuse LOUDLY.

  The last layer, deliberately: everything it renders must already
  stand, and a failure here must not be able to cost the run loop. It
  throws like any other tower layer, so the REPL survives and the
  degraded instance carries what stood — which is the honest behaviour
  for a port that is already taken, rather than a silent fallback to a
  no-UI-today state that nobody would notice until they opened a
  browser.

  THE PORT IS DERIVED FROM THE CLUSTER NAME, so a named cluster answers
  on the same port after every restart and a browser tab keeps working.
  A manifest dial still wins — an explicit port is somebody's decision
  and outranks a derivation. When the derived port is taken, the view
  binds an ephemeral one and says BOTH numbers, because a name collision
  must not look like a broken build and a moved bookmark must not fail
  silently."
  [connection cluster-name dials view]
  (let [wanted (or (:seon.config.web/port dials)
                   (web/derived-port cluster-name))
        served (web/start!
                ;; THE VIEW HALF comes from the armed layer, not from
                ;; here: the mult, the watched registration, and the
                ;; render wake channel all belong to the render proc's
                ;; pipeline, and the web service only taps and offers.
                ;; The coalesce floor is no longer passed — the PROC
                ;; reads it from the config facts per pass (F2 §1.2), so
                ;; a live dial change applies without restarting a tab.
                (merge {:seon.render.web/port wanted
                        :seon.store/connection connection
                        :seon.cluster.agent/id root-agent-id
                        :seon.sci.admit/caps (config/result-caps dials)}
                       (select-keys view
                                    [:seon.render.web/pages-mult
                                     :seon.render.web/registration
                                     :seon.render.web/render-channel
                                     :seon.cluster.run/process])))]
    (if-let [unavailable (:seon.render.web/wanted-port served)]
      (log/warn (str "seon " cluster-name " view: port " unavailable
                     " was taken, serving on "
                     (:seon.render.web/url served)
                     " instead — a bookmark on " unavailable
                     " will not reach this cluster"))
      (log/info (str "seon " cluster-name " view: "
                     (:seon.render.web/url served))))
    served))

(defn- tagged-run
  "The run the TAGGED agent points at and this process holds, or nil.
  Attribution is STRUCTURAL: an agent graph's fault arrives tagged with
  its agent (structural provenance from the error-channel join), so
  attribution is that agent's one held run — exact under concurrency,
  where the serial-era global query stopped being. That global query
  (`attributed-run`) is deleted at F2 §3.3."
  [db agent-id process]
  (d/q '[:find ?id .
         :in $ ?agent-id ?process
         :where
         [?agent :seon.cluster.agent/id ?agent-id]
         [?agent :seon.cluster.agent/run ?run]
         [?run :seon.cluster.run/process ?process]
         [?run :seon.cluster.run/id ?id]]
       db agent-id process))

(defn- commit-fault!
  "Commit one escaped Throwable as durable facts. TOTAL, never throws.
  Everything it needs is read fresh: the dials from the config
  singleton, the attribution from the database value at the fault's
  own basis. A fault from an agent graph carries its agent as a
  structural tag (F1 §6) and attributes through `tagged-run`. An
  UNTAGGED fault — the cluster graph's own, from the armer or the
  render proc — attributes to NO run, and that is correct rather than
  missing: it is not a run's fault. The serial-era fallback query is
  gone (F2 §3.3). It goes through
  `store/transact!`, which never throws, and it ignores its own
  outcome — if the database refuses the fault, the answer is not to
  fault about the fault (the recursion fence)."
  [connection cluster-name process caps fault]
  (try
    (let [db @connection
          dials (config/effective db cluster-name)
          agent-id (:seon.cluster.agent/id fault)
          run-id (when agent-id (tagged-run db agent-id process))]
      (store/transact!
       connection
       (error/commit-tx
        db
        (cond-> {:seon.error/source fault
                 :seon.error/id (str (random-uuid))
                 :seon.error/at (java.util.Date.)
                 :seon.error/process process
                 :seon.sci.admit/caps caps
                 :seon.error/basis-t (:max-tx db)
                 :seon.config.error/recurrence-limit
                 (:seon.config.error/recurrence-limit dials)}
          (:seon.config.error/escalate-to dials)
          (assoc :seon.config.error/escalate-to
                 (:seon.config.error/escalate-to dials))
          run-id (assoc :seon.cluster.run/id run-id)
          agent-id (assoc :seon.cluster.agent/id agent-id)))))
    (catch Throwable failure
      ;; the last resort, and it is deliberately not a fact: the fault
      ;; path failed, so the one place left that cannot fail is stderr
      (binding [*out* *err*]
        (println "seon.error commit-fault! failed:" (ex-message failure))
        (flush))))
  nil)

(defn- loop-handle
  "The cluster handle the loop proc carries, derived from FACTS.
  Everything in it comes from the instance and the effective dials, so
  the assembly the live drives were doing by hand happens once, here,
  where production does it."
  [connection cluster-name process wake-channel stream-channel completion]
  (let [dials (config/effective @connection cluster-name)]
    (cond-> (merge
             ;; MERGED WHOLE, never re-keyed: `seon.ai/targets` owns the
             ;; role names, so a backup cannot arrive here under a name
             ;; only this function knows. Its `:seon.ai/backup` key is
             ;; ABSENT when no backup is configured, and that absence is
             ;; the whole failover contract.
             (ai/targets dials)
             {:seon.store/branch-connection connection
              :seon.cluster.run/process process
              :seon.cluster.wake/channel wake-channel
              ;; the cluster's ONE stream conn (F2 §2.1): sliding-1, so
              ;; the newest complete snapshot wins and the provider fold
              ;; is never parked by presentation
              :seon.cluster.loop/stream-channel stream-channel
              :seon.cluster.loop/completion completion
              :seon.ai.retry/strategy (ai/retry-strategy dials)
              :seon.cluster.loop/evaluate 'seon.sci.eval/evaluate
              :seon.sci.admit/caps (config/result-caps dials)
              :seon.config.eval/time-limit-ms
              (:seon.config.eval/time-limit-ms dials)
              :seon.config/on-core-error (:seon.config/on-core-error dials)
              :seon.config.error/recurrence-limit
              (:seon.config.error/recurrence-limit dials)
              ;; the conversation bound: every delivery a turn makes is
              ;; measured against it, so the loop must carry it the same
              ;; way it carries every other dial — derived from facts
              ;; once, here, never read at the call site
              :seon.config.message/max-chain
              (:seon.config.message/max-chain dials)})
      (:seon.config.error/escalate-to dials)
      (assoc :seon.config.error/escalate-to
             (:seon.config.error/escalate-to dials)))))

(defn- cluster-graph-definition
  "The cluster's OWN small graph (F1 R7, F2 §1): the armer proc and the
  render proc — a schedule proc later. One cluster graph per cluster,
  so the components that arm agents and derive pages have exactly the
  ping/error/pause uniformity every other proc has. The render proc's
  channels are external ports (created by `arm-agents!`, carried on the
  handle and the view), so the graph definition stays pure data."
  [handle routing view]
  {:procs {:seon.cluster.agent/armer
           {:proc (flow/var-process #'cluster.agent/armer-step :io
                                    {:seon.cluster.loop/cluster handle
                                     :seon.cluster.agent/routing routing})}
           :seon.render.web/render
           {:proc (flow/var-process #'web/render-step :io
                                    (assoc view
                                           :seon.cluster.loop/cluster
                                           handle))}}
   :conns []})

(defn- arm-agents!
  "Arm this cluster: the armer graph, fan-out, routing listener, prime.
  ARMED AND IDLE — the per-agent successor of the single run loop
  (F1). The armer's prime derives (agents in facts) − (armed set) and
  arms one graph per agent (R6: arm-all-at-boot); each arm ends with
  its own mailbox prime, whose first pass derives that agent's work
  from FACTS. A fresh cluster has no triggers, so boot makes zero
  model calls. A rebooted cluster never resumes interrupted work:
  recovery has already closed it. The first pass can start a new
  episode only for an unanswered durable message, including one that
  arrived before the crash.

  ORDER IS THE CONTRACT. The cluster graph starts first because the
  fan-out taps ITS channels; the routing listener comes after the
  fan-out because its fault channel is THE FAN-OUT'S; the armer prime
  comes LAST, after the listener, so an agent created between the
  prime's derivation and the listener's registration cannot exist —
  anything committed earlier is in the facts the prime's pass reads.
  This is the wiring whose absence meant every core fault in a live
  cluster was dropped by a sliding buffer nobody read."
  [instance connection cluster-name]
  (let [process (process-identity (:seon.boot/advertisement instance))
        armer-channel (async/chan (async/sliding-buffer 1))
        stream-channel (async/chan (async/sliding-buffer 1))
        completion (async/promise-chan)
        handle (loop-handle connection cluster-name process armer-channel
                            stream-channel completion)
        routing (cluster.agent/routing)
        ;; the render pipeline's external ports (F2 §1): the wake
        ;; channel route! delivers into, the pages channel the proc's
        ;; snapshots exit on (multed here, tapped per tab), the watched
        ;; registration the feed writes, and the proc's own orderly-stop
        ;; completion — all process-local, all free to lose
        render-channel (async/chan (async/sliding-buffer 1))
        pages-channel (async/chan (async/sliding-buffer 1))
        view {:seon.render.web/render-channel render-channel
              :seon.render.web/pages-channel pages-channel
              :seon.render.web/registration (atom {})
              :seon.render.web/completion (async/promise-chan)
              ;; THE ONE THING THE DATABASE CANNOT ANSWER, carried to
              ;; the page boundary rather than defaulted at it. On this
              ;; branch the live set is a singleton by construction —
              ;; one connection per branch, one process per store, the
              ;; same invariant `recover-runs!` reasons from — so the
              ;; holder a run names and the holder a rendered page
              ;; judges are the same string.
              :seon.cluster.run/process (:seon.cluster.run/process handle)}
        drops (atom 0)
        graph (flow.core/create-flow
               (cluster-graph-definition handle routing view))
        started (flow.core/start graph)
        _ (flow.core/resume graph)
        fanout (flow/start-error-fanout!
                {:seon.flow/graph graph
                 :seon.flow/started started
                 :seon.flow/fault-buffer-capacity 64
                 :seon.flow/monitor-buffer-capacity 64
                 :seon.flow/read-core-error-mode
                 (fn []
                   (or (:seon.config/on-core-error
                        (config/effective @connection cluster-name))
                       :record))
                 :seon.flow/commit-fault!
                 (fn [fault]
                   (commit-fault! connection cluster-name process
                                  (:seon.sci.admit/caps handle) fault))
                 :seon.flow/commit-drop!
                 (fn [dropped]
                   ;; CHEAP ON PURPOSE: this runs on the thread of the
                   ;; proc that faulted, inside the buffer's own add!,
                   ;; so a transaction here would make an overflowing
                   ;; error path slow down the code that is failing.
                   ;; Counted and said out loud; never silent.
                   (swap! drops inc)
                   (binding [*out* *err*]
                     (println "seon.error DROPPED a fault:"
                              (pr-str (:clojure.core.async.flow/pid dropped)))
                     (flush)))
                 :seon.flow/panic!
                 (fn [fault]
                   ;; FAIL LOUD IS NOT FALL DOWN (owner ruling): dev
                   ;; panic makes the fault impossible to miss, and it
                   ;; still COMMITS it — a panic that destroyed the
                   ;; record would be the fire alarm burning the house.
                   (commit-fault! connection cluster-name process
                                  (:seon.sci.admit/caps handle) fault)
                   (binding [*out* *err*]
                     (println "SEON CORE FAULT (dev panic):"
                              (or (ex-message
                                   (:clojure.core.async.flow/ex fault))
                                  (pr-str fault)))
                     (flush)))})]
    ;; the fault channel joins the routing entry so every later arm
    ;; can tap its agent graph's errors into the ONE committer inbox
    (swap! routing assoc :seon.cluster.agent/fault-channel
           (:seon.flow/fault-channel fanout))
    ;; THE ROUTING DELIVERY (F1 §4): one listener per cluster, and its
    ;; own faults ride the same path as every other fault
    (wake/route! {:seon.cluster.wake/connection connection
                  :seon.cluster.wake/channels
                  (fn [] (cluster.agent/channels routing))
                  :seon.cluster.wake/fenced?
                  (fn [agent-eid channel]
                    (cluster.agent/fenced-route? routing agent-eid channel))
                  :seon.cluster.wake/armer-channel armer-channel
                  :seon.cluster.wake/render-channel render-channel
                  :seon.cluster.wake/fault-channel
                  (:seon.flow/fault-channel fanout)
                  :seon.cluster.wake/key :seon.cluster.agent/route})
    ;; ARM ALL AT BOOT (R6), synchronously, through the armer's ONE
    ;; derivation. The listener is already registered, so every arm's
    ;; mailbox prime and every commit concurrent with it are conserved.
    ;; Direct invocation publishes readiness: a returned instance is
    ;; armed, while the running proc owns every later wake.
    (cluster.agent/armer-step
     (cluster.agent/armer-step
      {:seon.cluster.loop/cluster handle
       :seon.cluster.agent/routing routing})
     ::cluster.agent/arm
     ::cluster.agent/boot)
    {:seon.cluster.loop/cluster handle
     :seon.flow/graph graph
     :seon.flow/error-fanout fanout
     :seon.cluster.agent/routing routing
     ;; the view half `serve!` hands to the web service: one mult over
     ;; the proc's pages out-port, the shared registration, and the
     ;; wake channel a freshly opened tab offers into
     :seon.render.web/view (assoc view
                                  :seon.render.web/pages-mult
                                  (async/mult pages-channel))
     :seon.error/drops drops}))

(defn- disarm-agents!
  "Unwind the armed layers of ONE instance, newest first.
  The routing LISTENER goes first so nothing new is routed while the
  graphs unwind; the ARMER next, so no new agent graph can appear
  mid-teardown (which is also what makes arm/disarm races
  unrepresentable rather than locked around); then each agent graph,
  each joined at its own turn proc's completion; then the fan-out
  detaches its taps. Each layer is released only if it stands — a
  degraded instance disarms the same way.

  ORDERLY STOP WAITS FOR THE ACTIVE PASS. `flow/stop` only queues
  `::flow/stop`; it does not join the proc (`flow/impl.clj:174-183`).
  Each proc therefore publishes its own completion from the stop
  transition, which Flow invokes only after the active transform
  returns. This wait honestly includes a seconds-long model call and
  any transaction it starts; only then may the branch connection be
  released. There is no sleep or deadline standing in for that event.

  This is orderly-stop behavior only. A process kill cannot await a
  completion and may lose an in-flight transaction by design; the crash
  model owns that row and the next boot settles its durable wreckage."
  [instance]
  ;; the VIEW goes first: it is the newest layer and the only one
  ;; holding sockets belonging to somebody outside this process
  (when-let [served (:seon.render.web/served instance)]
    (web/stop! served))
  (when-let [handle (:seon.cluster.loop/cluster instance)]
    (wake/unlisten! {:seon.cluster.wake/connection
                     (:seon.store/branch-connection handle)
                     :seon.cluster.wake/key :seon.cluster.agent/route}))
  (when-let [graph (:seon.flow/graph instance)]
    (flow.core/stop graph)
    ;; BOTH cluster-graph procs are joined at their own completions —
    ;; `flow/stop` only queues `::flow/stop`, so a render pass holding
    ;; the branch connection would otherwise still be deriving when the
    ;; connection is released
    (async/<!! (:seon.cluster.loop/completion
                (:seon.cluster.loop/cluster instance)))
    (some-> (get-in instance [:seon.render.web/view
                              :seon.render.web/completion])
            async/<!!))
  (when-let [routing (:seon.cluster.agent/routing instance)]
    (doseq [agent-id (sort (keys (:seon.cluster.agent/armed @routing)))]
      (cluster.agent/disarm! {:seon.cluster.agent/id agent-id
                              :seon.cluster.agent/routing routing})))
  (when-let [fanout (:seon.flow/error-fanout instance)]
    (flow/stop-error-fanout! fanout))
  (when-let [handle (:seon.cluster.loop/cluster instance)]
    (async/close! (:seon.cluster.wake/channel handle))
    (some-> (:seon.cluster.loop/stream-channel handle) async/close!))
  ;; the render pipeline's own ports, after the proc that reads them has
  ;; published its completion: a tab still looping on a tap sees its tap
  ;; close and falls out of the loop
  (when-let [view (:seon.render.web/view instance)]
    (async/close! (:seon.render.web/render-channel view))
    (async/close! (:seon.render.web/pages-channel view)))
  nil)

(defn- stack-tower!
  "Stack store → ancestor → fork → connection → config onto `instance`.
  Each layer is assoc'd as it stands, and the whole value is republished
  to the registry at every step, so the instance a failure carries is
  exactly what stands: absence marks where boot stopped."
  [instance publish! config-request]
  (let [config (:seon.boot/config instance)
        cluster-name (:seon.boot/cluster-name config)
        current-digest (current-source-digest)
        store (acquire-root-store! (:seon.boot/store-dir config))
        instance (publish! (assoc instance :seon.store/store store))
        forked (registry/ensure-cluster!
                {:seon.store/store store
                 :seon.boot/cluster-name cluster-name
                 :seon.ancestor/branch
                 (ancestor-branch! store config)})
        connection (store/open-branch! store (:seon.store/branch forked))
        instance (publish!
                  (assoc instance :seon.boot/cluster-connection connection))
        ;; The source tree is consulted only for its digest. Indexing remains
        ;; an explicit fork/index operation and never runs on this reopen path.
        ;; This gate precedes schema accretion, recovery, config, and arming:
        ;; an incoherent program graph gets no runtime semantics. A complete
        ;; older corpus remains a legitimate sovereign world.
        _ (require-coherent-program! connection cluster-name current-digest)
        ;; A branch may predate this process's additive schema population.
        ;; Install it before recovery or config can transact a newly added
        ;; attribute. This is the same population that creates an ancestor;
        ;; converged reopens issue zero transactions.
        _ (accrete-schema-population! connection)
        ;; BEFORE anything resumes: a previous process's wreckage is
        ;; settled here, so the first pass of any loop derives work from
        ;; facts that already tell the truth about who holds what
        recovery (recover-runs!
                  connection
                  (process-identity (:seon.boot/advertisement instance)))
        instance (publish! (merge instance recovery))
        instance (publish!
                  (assoc instance
                         :seon.boot/config-result
                         (config/apply!
                          (merge
                           {:seon.config/connection connection
                            :seon.boot/cluster-name cluster-name}
                           config-request))))
        ;; AFTER the dials are facts, because the root agent is who the
        ;; escalation dial names, and BEFORE the loop is armed, because
        ;; an armed loop may need to address it on its first pass
        _ (seed-root-agent! connection)
        ;; INSTRUMENTATION IS NOT WIRED HERE, and the reason is
        ;; evidence rather than taste. Wiring `seon.instrument/apply!`
        ;; into boot was tried: every test that boots a cluster then
        ;; instruments the whole JVM, so a suite's outcome depends on
        ;; whether an earlier suite happened to boot one — and a
        ;; CLUSTER-scoped dial silently mutating PROCESS-global var
        ;; roots is the wrong seam besides. The dev loop turns it on
        ;; (`bin/repl`, and the drive scripts), which is where a human
        ;; is watching. See `seon.instrument`.
        _ (flow/install-work-launcher!
           {::flow/configuration
            (select-keys (config/effective @connection cluster-name)
                         flow/flow-workload-attributes)})]
    (let [instance (publish!
                    (merge instance
                           (arm-agents! instance connection cluster-name)))
          ;; LAST, and after the loop, because the view renders what the
          ;; loop produces and must never be able to cost it
          ;; a database VALUE, not the connection: `effective` reads
          ;; the dials at a basis like every other derivation
          dials (config/effective @connection cluster-name)
          served (serve! connection cluster-name dials
                         (:seon.render.web/view instance))
          ;; THE ADVERTISEMENT GAINS THE URL, so discovery never parses a
          ;; log. The operator reads advertisements as its only truth,
          ;; and a URL scraped from stdout would be a second source that
          ;; drifts. Staleness semantics are unchanged: pid and
          ;; start-instant still say whether this process is real.
          advertisement (assoc (:seon.boot/advertisement instance)
                               :seon.render.web/url
                               (:seon.render.web/url served)
                               :seon.render.web/port
                               (:seon.render.web/port served))]
      (write-advertisement!
       (cluster-paths (:seon.boot/root config) cluster-name)
       advertisement)
      (publish! (assoc instance
                       :seon.render.web/served served
                       :seon.boot/advertisement advertisement)))))

(defn start!
  "Start one cluster instance in this JVM, REPL FIRST, then the tower.
  Order: resolve paths and create directories → open the io-prepl
  socket server and write the advertisement (real bound port, pid,
  start-instant — the REPL is live from here NO MATTER WHAT) → open the
  process-root store (first instance; siblings reuse the held store) →
  ancestor/ensure! (population from :seon.boot/ancestor-branch when
  supplied, else the default schema population) → registry/
  ensure-cluster! → store/open-branch! → require one recorded source
  digest and a coherent program graph (a complete older corpus is
  sovereign and allowed) → accrete the current schema
  population → config/apply! with the shipped defaults → return the complete
  instance. A later-layer failure THROWS
  with the DEGRADED INSTANCE in the ex-data under :seon.boot/instance
  (tower fields absent from the failure point) while the REPL and
  advertisement survive; the instance stays registered, and the caller
  stops it through that carried value like any other. Two instances in one JVM share the root store and executors,
  nothing else. Refuses a second start! for a cluster this JVM already
  has running."
  {:malli/schema [:=> [:cat :seon.boot/start-request] :seon.boot/instance]}
  [request]
  (let [began (System/nanoTime)
        config-request
        (select-keys request
                     [:seon.config/manifest :seon.config/environment])
        config
        (resolve-bootstrap
         (apply dissoc request (keys config-request)))
        cluster-name (:seon.boot/cluster-name config)
        paths (cluster-paths (:seon.boot/root config) cluster-name)
        name (server-name cluster-name)]
    (create-directories! config paths)
    (reserve-cluster! cluster-name)
    (let [server (volatile! nil)
          ;; LAYER 0 — the REPL. Its own failure unwinds completely
          ;; (socket closed, reservation released); once it succeeds,
          ;; nothing below may take it down.
          instance
          (try
            (let [prepl-server
                  (clojure.core.server/start-server
                   {:accept 'clojure.core.server/io-prepl
                    :port (:seon.boot/prepl-port config)
                    :name name
                    :address (:seon.boot/prepl-host config)})
                  _ (vreset! server prepl-server)
                  advertisement
                  (merge
                   {:seon.boot/cluster-name cluster-name
                    :seon.boot/prepl-host (:seon.boot/prepl-host config)
                    :seon.boot/prepl-port (.getLocalPort prepl-server)}
                   (cluster.process/current-identity))
                  instance
                  {:seon.boot/config config
                   :seon.boot/advertisement advertisement
                   :seon.boot/prepl-server prepl-server
                   :seon.boot/executors (root-executors)}
                  instance
                  (require-candidate-value
                   :seon.boot/instance
                   instance
                   "The started cluster instance was refused.")]
              (write-advertisement! paths advertisement)
              (swap! running-instances assoc cluster-name instance)
              instance)
            (catch Throwable throwable
              (when @server
                (clojure.core.server/stop-server name))
              (release-reservation! cluster-name)
              (throw throwable)))
          ;; the registry always holds the instance AS IT STANDS, so a
          ;; stop! of the carried value and a stop! of the registered
          ;; one release the same resources
          published (volatile! instance)
          publish! (fn [value]
                     (vreset! published value)
                     (swap! running-instances
                            (fn [instances]
                              (if (contains? instances cluster-name)
                                (assoc instances cluster-name value)
                                instances)))
                     value)]
      (try
        ;; the elapsed measure belongs to boot, not to whoever prints
        ;; the banner: a caller timing `start!` from outside measures
        ;; its own require time too
        (let [stood (stack-tower! instance publish! config-request)]
          (publish! (assoc stood :seon.boot/ready-ms
                           (quot (- (System/nanoTime) began) 1000000))))
        (catch Throwable failure
          ;; LOUD, and the REPL survives: the degraded instance rides the
          ;; refusal so the caller can diagnose over the live socket and
          ;; stop it like any other instance.
          (throw (ex-info
                  (str "The cluster instance failed above the REPL: "
                       (ex-message failure))
                  {:seon.error/kind :seon.boot/refused
                   :seon.boot/offense {:seon.boot/cluster-name cluster-name}
                   :seon.boot/instance @published}
                  failure)))))))

(defn- active-instance?
  [registered instance]
  (and (map? registered)
       (identical? (:seon.boot/prepl-server registered)
                   (:seon.boot/prepl-server instance))))

(defn- claim-stop!
  [cluster-name instance marker]
  (loop []
    (let [instances @running-instances]
      (if-not (active-instance? (get instances cluster-name) instance)
        false
        (if (compare-and-set! running-instances
                              instances
                              (assoc instances cluster-name marker))
          true
          (recur))))))

(defn readiness
  "The boot banner, DERIVED from a started instance. Never duplicated.

  Everything here is read back out of the instance and the database it
  points at, so the banner cannot say something the system does not.
  That is the whole discipline: a banner assembled from variables the
  boot path happened to have in hand drifts the first time a layer
  changes, and a banner nobody can regenerate is a log line rather than
  a readout.

  Returns ordinary data; `bin/repl` prints it. A caller that wants one
  field takes one field."
  {:malli/schema [:=> [:cat :seon.boot/instance] :seon.boot/readiness]}
  [instance]
  (let [connection (:seon.boot/cluster-connection instance)
        db (some-> connection deref)
        served (:seon.render.web/served instance)
        advertisement (:seon.boot/advertisement instance)
        agents (if db
                 (or (d/q '[:find (count ?a) . :where
                            [?a :seon.cluster.agent/id _]] db)
                     0)
                 0)
        blocks (if db
                 (count (block/blocks db root-agent-id))
                 0)
        found (if db
                (problems/problems
                 db {:seon.cluster.run/live-processes
                     #{(process-identity advertisement)}})
                {})]
    (cond-> {:seon.boot/cluster-name (:seon.boot/cluster-name advertisement)
             :seon.boot/pid (:seon.boot/pid advertisement)
             :seon.boot/prepl-port (:seon.boot/prepl-port advertisement)
             :seon.cluster.agent/count agents
             :seon.block/count blocks
             ;; `{}` when healthy — the same value `problems` derives, so
             ;; the banner screams exactly when the facts do and nobody
             ;; maintains a second notion of "fine"
             :seon.problems/problems found}
      served (assoc :seon.render.web/url (:seon.render.web/url served))
      (:seon.render.web/wanted-port served)
      (assoc :seon.render.web/wanted-port
             (:seon.render.web/wanted-port served))
      (:seon.boot/recovered-runs instance)
      (assoc :seon.boot/recovered-runs
             (:seon.boot/recovered-runs instance))
      (:seon.boot/ready-ms instance)
      (assoc :seon.boot/ready-ms (:seon.boot/ready-ms instance)))))

(defn banner
  "`readiness` as the block a person reads at a terminal.

  THE URL LEADS, because it is the one thing somebody is about to use.
  A fallback port is called out on its own line rather than folded into
  the URL line — a bookmark that stopped working deserves a sentence,
  not a number somebody has to notice."
  {:malli/schema [:=> [:cat :seon.boot/readiness] :string]}
  [{:seon.render.web/keys [url wanted-port]
    :seon.problems/keys [problems]
    :as ready}]
  (str/join
   "\n"
   (cond-> [(str "seon " (:seon.boot/cluster-name ready) " ready")
            (str "  view         " (or url "(not serving)"))]
     wanted-port
     (conj (str "  port         " wanted-port
                " was taken — a bookmark on it will not reach this cluster"))
     true
     (into [(str "  repl         " (:seon.boot/prepl-port ready)
                 "  (pid " (:seon.boot/pid ready) ")")
            (str "  agents       " (:seon.cluster.agent/count ready))
            (str "  blocks       " (:seon.block/count ready)
                 " live on root")
            (str "  problems     " (if (empty? problems)
                                     "none"
                                     (str (count problems) " families — "
                                          (str/join ", " (sort (map name (keys problems)))))))])
     ;; only when there WAS wreckage: a zero here is noise on every
     ;; healthy boot, and noise is what makes a banner unread
     (pos? (or (:seon.boot/recovered-runs ready) 0))
     (conj (str "  recovered    " (:seon.boot/recovered-runs ready)
                " run(s) from a dead process"))
     (:seon.instrument/instrumented ready)
     (conj (str "  instrumented " (:seon.instrument/instrumented ready)
                " vars"))
     (:seon.boot/ready-ms ready)
     (conj (format "  boot         %.2fs"
                   (/ (double (:seon.boot/ready-ms ready)) 1000))))))

(defn stop!
  "Stop exactly THIS instance, instance-addressed never name-addressed.
  Unwinds the tower in reverse: releases ITS cluster branch connection,
  drops its hold on the process-root store — the LAST instance out
  releases the store and with it the lifetime flock, a sibling's hold
  keeps it open — then closes ITS prepl server socket and deletes ITS
  advertisement. The database resources go FIRST so a failure to release
  one restores this exact instance to the registry with its REPL up for
  diagnosis and a later stop retry. A DEGRADED instance stops the same
  way: absence marks what was never built, so each layer is released
  only if it stands. A delayed stop! of an old instance value
  must not touch a replacement started under the same cluster name (the
  replacement's socket, advertisement, and registry entry all survive).
  Idempotent — stopping a stopped instance is a no-op returning nil.
  Never touches the shared root executors."
  {:malli/schema [:=> [:cat :seon.boot/instance] :nil]}
  [instance]
  (let [config (:seon.boot/config instance)
        cluster-name (:seon.boot/cluster-name config)
        marker (Object.)]
    (when (claim-stop! cluster-name instance marker)
      (try
        ;; the armed layers first: nothing new may be derived while the
        ;; database resources are being released
        (disarm-agents! instance)
        (when (= 1 (count @running-instances))
          (flow/stop-installed-work-launcher!))
        (when-let [connection (:seon.boot/cluster-connection instance)]
          (d/release connection))
        (when (:seon.store/store instance)
          (release-root-store! (:seon.boot/store-dir config)))
        (when-not
         (clojure.core.server/stop-server (server-name cluster-name))
          (when-not
           (.isClosed ^java.net.ServerSocket (:seon.boot/prepl-server instance))
            (refused!
             "The cluster's registered prepl server is unavailable."
             {:seon.boot/cluster-name cluster-name})))
        (let [advertisement-file
              (io/file
               (:seon.boot/advertisement-file
                (cluster-paths (:seon.boot/root config) cluster-name)))]
          (when (= (:seon.boot/advertisement instance)
                   (try
                     (edn/read-string (slurp advertisement-file))
                     (catch Throwable _ nil)))
            (.delete advertisement-file)))
        (swap! running-instances
               (fn [instances]
                 (if (identical? marker (get instances cluster-name))
                   (dissoc instances cluster-name)
                   instances)))
        (catch Throwable failure
          ;; A resource that failed to release remains the addressed
          ;; generation. Restoring the exact instance makes stop retryable
          ;; while its live REPL and registry fence exclude a replacement.
          (swap! running-instances
                 (fn [instances]
                   (if (identical? marker (get instances cluster-name))
                     (assoc instances cluster-name instance)
                     instances)))
          (throw failure)))))
  nil)

(defn reset!
  "Destroy one cluster branch and refork the current ancestor.

  An extra hold keeps the process-root store and its flock alive while
  `stop!` releases the addressed instance and its branch connection.
  The registry's existing `reset-cluster!` remains the sole
  delete/refork owner. The current content-addressed ancestor is ensured
  explicitly here; neither indexing nor reset enters the boot path."
  {:malli/schema
   [:=> [:cat :seon.boot/instance]
    :seon.cluster.registry/branch-result]}
  [instance]
  (let [config (:seon.boot/config instance)
        cluster-name (:seon.boot/cluster-name config)
        store-dir (:seon.boot/store-dir config)
        held-store (acquire-root-store! store-dir)]
    (try
      (stop! instance)
      (registry/reset-cluster!
       {:seon.store/store held-store
        :seon.boot/cluster-name cluster-name
        :seon.ancestor/branch
        (:seon.ancestor/branch
         (ensure-current-ancestor! held-store))})
      (finally
        (release-root-store! store-dir)))))

(defn read-advertisement
  "Read and validate one cluster's advertisement, or nil.
  Returns the advertisement map only when the file exists, parses,
  validates against :seon.boot/advertisement, AND its (pid,
  start-instant) matches a live process — a stale file from a killed
  instance reads as nil, never as a coordinate. (ProcessHandle/of pid →
  startInstant comparison; tolerate the platform's millisecond
  truncation.)"
  {:malli/schema [:=> [:cat :seon.boot/root :seon.boot/cluster-name]
                  [:maybe :seon.boot/advertisement]]}
  [root cluster-name]
  (try
    (let [path (:seon.boot/advertisement-file
                (cluster-paths root cluster-name))
          advertisement (edn/read-string (slurp path))]
      (when (and
             (schema/valid-candidate-value?
              :seon.boot/advertisement advertisement)
             (= cluster-name (:seon.boot/cluster-name advertisement))
             (cluster.process/live?
              (select-keys advertisement
                           [:seon.boot/pid :seon.boot/start-instant])))
        advertisement))
    (catch Throwable _
      nil)))
