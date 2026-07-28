(ns seon.cluster
  "The entry: one JVM process hosting cluster instances, REPL-first.

  CONTRACT LAYER (orchestrator-authored, 2026-07-27 — the B0 rung). The
  schemas and function contracts are SEALED: the implementation lane
  fills the stub bodies until test/seon/cluster/boot_test.clj is green
  and may not loosen a schema or a test. Friction is reported, never
  resolved by weakening.

  The boot tower (plan README, rulings 2026-07-27): each layer reads
  only the one below it and publishes its own readiness —

  0. PROCESS. `start!` consumes one complete bootstrap configuration —
     the closed, deliberately tiny key set the process needs before any
     store exists. Everything else lives in the database (B2). The REPL
     (io-prepl) opens FIRST, before anything else, and the instance
     advertises its coordinate; the ten-second ruling is this layer's
     bound.
  1. STORE. start! opens the ONE process-root store (B1's open-store!,
     first instance only — later instances of this process reuse it),
     ensures the bootstrap ancestor (seon.cluster.ancestor/ensure!),
     forks/finds the cluster's branch
     (seon.cluster.registry/ensure-cluster!) and opens its connection
     (store/open-branch!).
  2. FACTS. Config applies (seon.config/apply! — converged = zero
     writes) against the cluster branch; runtime reads the database
     from here on, and the root agent is seeded (one datom, no process,
     no tokens) so escalation has somewhere real to go.
  3. FLOW. The run loop is INSTALLED AND ARMED: its graph runs, the
     error fan-out consumes flow's error channel into durable error
     facts, the wake listener is registered with the fan-out's own
     fault channel, and the wake is primed once. Armed is not busy —
     a wake says only `look`, so a cluster with no triggers makes no
     model call, while a REBOOTED one picks up the work its
     predecessor left by the same mechanism.

  FAILED BOOT SEMANTICS (owner ruling 2026-07-27): the REPL is never
  hostage to anything downstream. When a later layer fails, start!
  THROWS — loud, the error dial decides panic vs record — but the prepl
  socket and its advertisement STAY UP for live diagnosis, and the
  degraded instance stays in the registry so stop! cleans it normally.
  Which layers stand is readable from the instance value itself: the
  tower fields are absent exactly where boot stopped — absence over
  status booleans.
  2. FACTS and 3. FLOW (B2/N3): the flow graph definition is data
     derived from database facts at a basis; graph transforms are
     referenced as VARS so re-evaluating a defn updates a running proc
     with no restart, and topology changes rebuild the graph (measured
     ~0.3 ms) — nothing in a flow channel is durable
     (research/flow-dynamic-update-2026-07-27.md).

  Multi-instance from day 0: the process identity is
  (cluster-name, pid, start-instant); every path derives from
  (root, cluster-name) by convention; each instance advertises its own
  REPL coordinate under its cluster directory; a JVM may host several
  instances and NOTHING here is an ambient one-cluster singleton — no
  process-global connection, cache, or session keyed by \"the\"
  cluster.

  The root of the process owns exactly two shared executors — one
  bounded `:compute` (default parallelism = available processors, a
  computed hardware fact, never a literal) and one `:io` — created once
  per JVM and shared by every cluster's flow graph
  (research/flow-per-cluster-2026-07-27.md).

  Crash walk: a kill at any instant leaves at most an orphan
  advertisement file plus whatever the tower's own crash rows already
  describe — the ancestor build (rename-at-end, scratch owned by
  (pid, start-instant)), the fork roster, and config reconciliation
  (one atomic transaction) each own their row; the OS releases the
  store's flock with the process. The advertisement carries
  (pid, start-instant) so a reader detects staleness against the live
  process table rather than trusting the file. `stop!` is idempotent;
  a killed process's next boot simply re-advertises."
  (:require [clojure.core.async :as async]
            [seon.ai :as ai]
            [clojure.core.async.flow :as flow.core]
            [clojure.core.server]
            [seon.cluster.loop :as cluster.loop]
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
            [seon.config :as config]
            [seon.flow :as flow]
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

(defn- current-process-identity
  []
  (let [handle (java.lang.ProcessHandle/current)
        optional (.startInstant (.info handle))]
    (when-not (.isPresent optional)
      (refused! "The process start instant is unavailable."
                {:seon.boot/pid (.pid handle)}))
    {:seon.boot/pid (.pid handle)
     :seon.boot/start-instant (java.util.Date/from (.get optional))}))

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
            ; drop the entry FIRST: a failing release must not leave a
            ; released store advertised as held
            (swap! root-store-holder dissoc store-dir)
            (store/release-store! (:seon.store/store held)))))))
  nil)

;;; ---------------------------------------------------------------------------
;;; The default ancestor population
;;; ---------------------------------------------------------------------------

(defn populate-ancestor!
  "The default ancestor content: this code's own schema population.
  Named by symbol in `ancestor/ensure!`'s request, so the producer is
  data and N5's program-graph indexer replaces it without touching the
  boot path. Three transactions, each DERIVED and none hand-written:
  the Datahike declarations of every registered database attribute, the
  core process entities the provenance refs resolve to (genesis data —
  bootstrap content lives in the ancestor), and the canonical schema rows
  asserted with that process provenance."
  {:malli/schema
   [:=> [:cat [:map [:seon.store/branch-connection
                     :seon.store/branch-connection]]]
    :nil]}
  [{connection :seon.store/branch-connection}]
  (d/transact connection
              {:tx-data (schema.datahike/malli->datahike-schema
                         (schema/canonical-database-attributes))})
  (d/transact connection
              {:tx-data [{:seon.db.process/id
                          config/managing-process-identity}]})
  (d/transact connection
              {:tx-data (schema/canonical-schema-rows (java.util.Date.))
               :tx-meta
               {:seon.db/process
                [:seon.db.process/id config/managing-process-identity]}})
  nil)

;;; ---------------------------------------------------------------------------
;;; The tower above the REPL
;;; ---------------------------------------------------------------------------

;;; The roots the ancestor's identity is computed over. Today the
;;; population above is derived from these sources; at N5 the indexer
;;; reads the same tree, so one digest keeps answering "what was I born
;;; from?" without a second mechanism.
(def ^:private ancestor-roots ["src"])

(defn- ancestor-branch!
  "The ancestor branch this cluster forks from.
  A supplied `:seon.boot/ancestor-branch` is used AS GIVEN — the caller
  named an existing ancestor and `ensure-cluster!` refuses if it is not
  in the roster. Absent, the ancestor of this source tree is ensured
  (idempotent; the roster is the whole cache)."
  [store config]
  (or (:seon.boot/ancestor-branch config)
      (:seon.ancestor/branch
       (ancestor/ensure!
        {:seon.store/store store
         :seon.ancestor/digest (ancestor/digest
                                {:seon.ancestor/roots ancestor-roots})
         :seon.ancestor/populate `populate-ancestor!}))))

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
  released immediately, and its dangling `:running` receipts become
  `:interrupted` — the 60-second lease is not waited out, because the
  lease exists to bound a holder we cannot ask about and here we can:
  this process just started, so on this branch every other holder is
  provably gone (one connection per branch, one process per store).

  Nothing here re-opens, re-plans, or re-executes. `recover-tx` is pure
  over the values it is handed and returns [] for a run needing
  nothing, so a clean boot commits nothing at all."
  [connection process]
  (let [db @connection
        open-runs (d/q '[:find [(pull ?run [*]) ...]
                         :where
                         [?run :seon.cluster.run/id _]
                         (not [?run :seon.cluster.run/closed-at _])]
                       db)
        receipts-of (fn [run-id]
                      (d/q '[:find [(pull ?receipt [*]) ...]
                             :in $ ?run-id
                             :where
                             [?run :seon.cluster.run/id ?run-id]
                             [?receipt :seon.cluster.eval/run ?run]]
                           db run-id))
        operations (into []
                         (mapcat
                          (fn [run]
                            (run/recover-tx
                             {:seon.cluster.run/run run
                              :seon.cluster.run/receipts
                              (receipts-of (:seon.cluster.run/id run))
                              :seon.cluster.run/live-processes #{process}})))
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
  [connection]
  (d/transact connection [{:seon.cluster.agent/id root-agent-id}]))

(defn- attributed-run
  "The run this process holds and has not closed, or nil.
  EXACT today because turns are serial within a cluster
  (`loop.cljc:36-40`), so there is at most one — which is why the fault
  committer can derive attribution instead of the loop having to carry
  the current run in its proc state for the error path's benefit. The
  day turns go concurrent this stops being exact and the run must ride
  in `::flow/state`; that is a contract note, not a hypothetical."
  [db process]
  (d/q '[:find [?id ?agent-id]
         :in $ ?process
         :where
         [?run :seon.cluster.run/process ?process]
         [?run :seon.cluster.run/id ?id]
         (not [?run :seon.cluster.run/closed-at _])
         [?run :seon.cluster.run/agent ?agent]
         [?agent :seon.cluster.agent/id ?agent-id]]
       db process))

(defn- commit-fault!
  "Commit one escaped Throwable as durable facts. TOTAL, never throws.
  Everything it needs is read fresh: the dials from the config
  singleton, the attribution from the database value at the fault's
  own basis. It goes through `store/transact!`, which never throws, and
  it ignores its own outcome — if the database refuses the fault, the
  answer is not to fault about the fault (the recursion fence)."
  [connection cluster-name process caps fault]
  (try
    (let [db @connection
          dials (config/effective db cluster-name)
          [run-id agent-id] (attributed-run db process)]
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
  [connection cluster-name process wake-channel]
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
              :seon.ai.retry/strategy (ai/retry-strategy dials)
              :seon.cluster.loop/evaluate 'seon.sci.eval/evaluate
              :seon.sci.admit/caps
              (select-keys dials [:seon.config.eval.result/max-depth
                                  :seon.config.eval.result/max-collection
                                  :seon.config.eval.result/max-string
                                  :seon.config.eval.result/max-nodes])
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

(defn- arm-loop!
  "Start the run loop for this cluster: graph, fan-out, listener, wake.
  ARMED AND IDLE. The graph is running and the wake channel is primed
  with one look, and that spends NOTHING: a wake says only \"look\", and
  `next-work` finds work only where facts already are. A fresh cluster
  has no triggers, so boot makes zero model calls; a REBOOTED cluster
  finds the work its predecessor left, which is the same mechanism and
  is exactly what makes interrupted+adapt happen without a recovery
  code path.

  ORDER IS THE CONTRACT. The graph starts first because the fan-out
  taps ITS channels; the listener comes last because its fault channel
  is THE FAN-OUT'S — the wake listener's own failures must land where
  every other fault lands, not in a channel somebody invented. This is
  the wiring whose absence meant every core fault in a live cluster was
  dropped by a sliding buffer nobody read."
  [instance connection cluster-name]
  (let [process (process-identity (:seon.boot/advertisement instance))
        wake-channel (async/chan (async/sliding-buffer 1))
        handle (loop-handle connection cluster-name process wake-channel)
        drops (atom 0)
        graph (flow.core/create-flow
               {:procs {:seon.cluster.loop/loop
                        {:proc (flow.core/process #'cluster.loop/step
                                                  {:workload :io})
                         :args handle}}
                :conns []})
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
    (wake/listen! {:seon.cluster.wake/connection connection
                   :seon.cluster.wake/attributes (wake/wake-attributes)
                   :seon.cluster.wake/channel wake-channel
                   ;; the listener's own faults ride the same path as
                   ;; every other fault
                   :seon.cluster.wake/fault-channel
                   (:seon.flow/fault-channel fanout)
                   ;; the key the loop's own ::flow/stop arity unlistens
                   :seon.cluster.wake/key :seon.cluster.loop/wake})
    (async/offer! wake-channel :seon.cluster.loop/boot)
    {:seon.cluster.loop/cluster handle
     :seon.flow/graph graph
     :seon.flow/error-fanout fanout
     :seon.error/drops drops}))

(defn- disarm-loop!
  "Unwind the armed layers of ONE instance, newest first.
  The graph goes first so nothing new is derived, and stopping it runs
  the loop's own `::flow/stop` arity, which unlistens. Then the
  fan-out detaches its taps. Each layer is released only if it stands —
  a degraded instance disarms the same way.

  A STOP DURING AN IN-FLIGHT TURN IS A KILL, and it is treated as one
  rather than waited out. `flow/stop` sends `::flow/stop` and closes
  the channels (`flow/impl.clj:177-182`); it does not join the proc's
  thread, and flow exposes no completion to wait for. So a transaction
  already dispatched when the connection is released fails in
  Datahike's own writer thread and is LOST — which is precisely a crash
  row: nothing re-executes, the next boot's `recover-tx` settles the
  dangling receipt, and the agent adapts. Do not paper this over with a
  sleep; if it ever needs to be clean, the honest fix is a completion
  the proc publishes, not a clock."
  [instance]
  (when-let [graph (:seon.flow/graph instance)]
    (flow.core/stop graph))
  (when-let [fanout (:seon.flow/error-fanout instance)]
    (flow/stop-error-fanout! fanout))
  (when-let [handle (:seon.cluster.loop/cluster instance)]
    (async/close! (:seon.cluster.wake/channel handle)))
  nil)

(defn- stack-tower!
  "Stack store → ancestor → fork → connection → config onto `instance`.
  Each layer is assoc'd as it stands, and the whole value is republished
  to the registry at every step, so the instance a failure carries is
  exactly what stands: absence marks where boot stopped."
  [instance publish!]
  (let [config (:seon.boot/config instance)
        cluster-name (:seon.boot/cluster-name config)
        store (acquire-root-store! (:seon.boot/store-dir config))
        instance (publish! (assoc instance :seon.store/store store))
        forked (registry/ensure-cluster!
                {:seon.store/store store
                 :seon.boot/cluster-name cluster-name
                 :seon.ancestor/branch (ancestor-branch! store config)})
        connection (store/open-branch! store (:seon.store/branch forked))
        instance (publish!
                  (assoc instance :seon.boot/cluster-connection connection))
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
                          {:seon.config/connection connection
                           :seon.config/manifest (config/defaults)
                           :seon.boot/cluster-name cluster-name})))
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
        ]
    (publish! (merge instance (arm-loop! instance connection cluster-name)))))

(defn start!
  "Start one cluster instance in this JVM, REPL FIRST, then the tower.
  Order: resolve paths and create directories → open the io-prepl
  socket server and write the advertisement (real bound port, pid,
  start-instant — the REPL is live from here NO MATTER WHAT) → open the
  process-root store (first instance; siblings reuse the held store) →
  ancestor/ensure! (population from :seon.boot/ancestor-branch when
  supplied, else the default schema population) → registry/
  ensure-cluster! → store/open-branch! → config/apply! with the shipped
  defaults → return the complete instance. A later-layer failure THROWS
  with the DEGRADED INSTANCE in the ex-data under :seon.boot/instance
  (tower fields absent from the failure point) while the REPL and
  advertisement survive; the instance stays registered, and the caller
  stops it through that carried value like any other. Two instances in one JVM share the root store and executors,
  nothing else. Refuses a second start! for a cluster this JVM already
  has running."
  {:malli/schema [:=> [:cat :seon.boot/overrides] :seon.boot/instance]}
  [overrides]
  (let [config (resolve-bootstrap overrides)
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
                   (current-process-identity))
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
        (stack-tower! instance publish!)
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

(defn stop!
  "Stop exactly THIS instance, instance-addressed never name-addressed.
  Unwinds the tower in reverse: releases ITS cluster branch connection,
  drops its hold on the process-root store — the LAST instance out
  releases the store and with it the lifetime flock, a sibling's hold
  keeps it open — then closes ITS prepl server socket and deletes ITS
  advertisement. The database resources go FIRST so a failure to release
  one still leaves the REPL up to diagnose it. A DEGRADED instance stops
  the same way: absence marks what was never built, so each layer is
  released only if it stands. A delayed stop! of an old instance value
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
        (disarm-loop! instance)
        (when-let [connection (:seon.boot/cluster-connection instance)]
          (d/release connection))
        (when (:seon.store/store instance)
          (release-root-store! (:seon.boot/store-dir config)))
        (.close ^java.net.ServerSocket (:seon.boot/prepl-server instance))
        (let [advertisement-file
              (io/file
               (:seon.boot/advertisement-file
                (cluster-paths (:seon.boot/root config) cluster-name)))]
          (when (= (:seon.boot/advertisement instance)
                   (try
                     (edn/read-string (slurp advertisement-file))
                     (catch Throwable _ nil)))
            (.delete advertisement-file)))
        (finally
          (swap! running-instances
                 (fn [instances]
                   (if (identical? marker (get instances cluster-name))
                     (dissoc instances cluster-name)
                     instances)))))))
  nil)

(defn- matching-live-process?
  [advertisement]
  (try
    (let [optional
          (java.lang.ProcessHandle/of
           (long (:seon.boot/pid advertisement)))]
      (when (.isPresent optional)
        (let [handle (.get optional)
              start (.startInstant (.info handle))]
          (and (.isAlive handle)
               (.isPresent start)
               (= (.getTime
                   ^java.util.Date (:seon.boot/start-instant advertisement))
                  (.toEpochMilli ^java.time.Instant (.get start)))))))
    (catch Throwable _
      false)))

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
             (matching-live-process? advertisement))
        advertisement))
    (catch Throwable _
      nil)))
