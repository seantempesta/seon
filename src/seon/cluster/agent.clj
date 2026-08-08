(ns seon.cluster.agent
  "The agent-graph blueprint: every agent is its own sequential process
  hosted as a running flow (F1, sealed contract
  docs/prds/sci-execution-runtime/plan/f1-agent-graph-contracts-2026-07-28.md).

  ONE BLUEPRINT stamps EVERY agent's graph — a pure function of
  (agent-id, cluster-handle) returning a `create-flow` definition. Two
  agents differ only in the agent-id their procs carry and the mailbox
  channel routed to them. No dispatcher, no active set, no scheduler
  entity: parked between episodes (one parked virtual thread + ~8.5 KB
  per proc, flow-mechanics §1), kicked off by the messages the agent
  receives, pausable/resumable through flow's own graph commands,
  parallel across agents by construction.

  THREE PROCS, all pinned `:io`, all var step-fns through the one
  `seon.flow/var-process` door (F0(a)):

  - `::mailbox` — total and instant: forward one payload-free
    `::episode` signal downstream and count deliveries. It never reads
    the database (the turn pass owns the basis) and never blocks (its
    downstream conn is sliding-1). It exists so the graph answers
    ping/pause within microseconds at ALL times — the turn transform
    contains a multi-second provider call, and a single-proc graph
    would go deaf for its length. The pause boundary IS the episode
    boundary: pausing the graph pauses the mailbox instantly, so no
    new episode begins, while an in-flight turn runs to its durable
    terminal.
  - `::turn` — one episode pass per signal, the central pass's proven
    shape narrowed to one agent: settle this agent's orphan, pin ONE
    database value, derive `next-agent-work`, execute the situation
    through the surviving `seon.cluster.loop/turn` owner (custody law,
    pre-provider capture, terminal transactions all unchanged), then
    self-rewake into this agent's OWN mailbox when more remains.
  - `::schedule` — one disposable timer and fact listener scoped to this
    agent. It derives due nominal instants, atomically commits fire+message,
    and waits on `:io`; it never polls another agent's schedules.

  Evals are NOT a proc here: the turn's resume branch submits every form
  through this cluster's `seon.flow/submit!!` launcher. That owner admits
  at most configured C eval lifetimes with Q more queued, runs admitted
  tasks on virtual threads, and returns the evaluator's flat value.
  `:mixed` appears nowhere — `var-process` refuses it at construction.

  THE GRAPH IS DERIVED STATE — never stored, always re-derivable from
  (agent-id + the handle's dials). Arm = stamp → start → resume → join
  the cluster's one fault channel → register the routing entry → prime
  one wake. The prime is the conservation idiom verbatim: the first
  pass derives the agent's work from FACTS, so anything committed
  before the graph existed is seen, and a wake lost to any gap costs
  nothing the arm prime does not recover.

  THE ROUTING ENTRY is a process-local atom (armed entries by agent id;
  mailbox channels by recipient ENTITY id, because that is what a
  committed `message/to` datom carries) — a disposable artifact rebuilt
  by arming at boot, never a fact. The cluster's one `wake/route!`
  listener delivers through it; `::armer` (hosted in the cluster's own
  graph, R7) closes the created-and-messaged-in-one-commit window by
  deriving (agents in facts) − (armed set) under a payload-free wake.

  Crash walk: everything on any channel is losable by the transport
  law. Buffered wakes → boot re-stamps every graph and primes each
  mailbox once. An in-flight turn's process state → recovery stamps
  dead custody's running receipts `interrupted-at` and retracts
  custody; NOTHING re-executes a form or refires a paid call. No row
  depends on a channel for recovery."
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async.protocols]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.flow :as seon.flow]
            [seon.schedule :as schedule]
            [seon.schema.edn :as schema.edn])
  (:import [java.util Date]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Namespace assignment
;;; ---------------------------------------------------------------------------

(defn creation-tx
  "Create one agent with its namespace and cluster connection.

  Pure transaction data. Context is derived from the entity graph, so agent
  creation stores no blocks or other presentation state."
  {:malli/schema [:=> [:cat :seon.cluster.agent/creation-request]
                  :seon.cluster.agent/creation-tx]}
  [{agent-id :seon.cluster.agent/id
    namespace-name :seon.ns/name
    cluster-name :seon.cluster/name}]
  (let [namespace-tempid (str "namespace:" namespace-name)]
    [{:db/id namespace-tempid
      :seon.ns/name namespace-name}
     {:seon.cluster.agent/id agent-id
      :seon.cluster.agent/namespace namespace-tempid
      :seon.cluster.agent/cluster [:seon.cluster/name cluster-name]}]))

(defn render-creation-ai
  "`:seon.render/ai` — the compact result of creating or resuming an agent."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (when-let [agent-id (:seon.cluster.agent/id unit)]
    (str "Agent " agent-id " · namespace " (:seon.ns/name unit)
         " · cluster " (:seon.cluster/name unit)
         " · bootstrap run " (:seon.cluster.run/id unit) ".")))

(defn render-creation-html
  "`:seon.render/html` — the compact agent-creation result card."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [agent-id (:seon.cluster.agent/id unit)]
    [:article {:class "seon-family-entry seon-agent-creation-entry"}
     [:h3 (str "Agent " agent-id)]
     [:dl
      [:div [:dt "Namespace"] [:dd [:code (str (:seon.ns/name unit))]]]
      [:div [:dt "Cluster"] [:dd (:seon.cluster/name unit)]]
      [:div [:dt "Bootstrap run"]
       [:dd [:code (:seon.cluster.run/id unit)]]]]]))

(defn owner-of
  "The agent id assigned to `namespace-name`, or nil."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ns/name]
                  [:maybe :seon.cluster.agent/id]]}
  [db namespace-name]
  (db/q '[:find ?agent-id .
         :in $ ?namespace-name
         :where
         [?namespace :seon.ns/name ?namespace-name]
         [?agent :seon.cluster.agent/namespace ?namespace]
         [?agent :seon.cluster.agent/id ?agent-id]]
       db namespace-name))

;;; ---------------------------------------------------------------------------
;;; The agent proc steps
;;; ---------------------------------------------------------------------------

(defn mailbox-step
  "The mailbox transform, in Flow's four arities.
  Total and instant: one payload-free `::episode` signal downstream per
  wake, deliveries counted in the ping map. The wake channel arrives as
  an in-port — the same channel the routing entry names — so listener
  routing, the arm prime, and the self-rewake all target ONE edge."
  {:malli/schema [:function
                  [:=> [:cat] [:map]]
                  [:=> [:cat :map] :map]
                  [:=> [:cat :map :keyword] :map]
                  [:=> [:cat :map :keyword :any]
                   [:tuple :map [:maybe [:map-of :keyword [:vector :some]]]]]]}
  ([]
   {:ins {}
    :outs {::episode
           "One payload-free episode signal: a wake says only \"look\"."}
    :workload :io
    :ping-map-fn (fn [state] (select-keys state [::deliveries]))})
  ([args]
   (assoc args
          ::flow/in-ports {::wake (:seon.cluster.wake/channel args)}
          ::deliveries 0))
  ([state _transition]
   state)
  ([state _input _message]
   [(update state ::deliveries inc)
    {::episode [::wake]}]))

(defn- held-run-id
  "The id of the run `agent-id` points at and `process` holds, or nil.
  The turn proc's ping-state derivation — the current run rides in
  `::flow/state`, which is what retired the serial-dependent global
  query F2 §3.3 deleted."
  [db agent-id process]
  (db/q '[:find ?id .
         :in $ ?agent-id ?process
         :where
         [?agent :seon.cluster.agent/id ?agent-id]
         [?agent :seon.cluster.agent/run ?run]
         [?run :seon.cluster.run/process ?process]
         [?run :seon.cluster.run/id ?id]]
       db agent-id process))

(defn turn-step
  "The turn transform, in Flow's four arities: ONE episode pass.
  Settle this agent's orphan (the wedge fence, per-agent), pin one
  database value, derive `next-agent-work`, run the situation through
  `seon.cluster.loop/turn` — the surviving owner of open/call/resume/
  close, the pass-local custody law, the pre-provider capture, and the
  resume branch's mandatory `seon.flow/submit!!` eval hop — then
  `offer!` one wake into this agent's OWN mailbox when
  `more-agent-work?`. Coalescing on sliding-1 keeps the rewake
  non-recursive. Failures inside the pass stay VALUES (the existing
  `refused!`/`error-tx` owners); a Throwable that escapes anyway is a
  core fault and rides this graph's error channel into the cluster's
  fault committer, tagged with the agent. The completion channel is an
  armed-ready permit: arm publishes it before Flow scheduling, an active
  transform holds it, and `finally` republishes it without an interruptible
  park. Disarm consumes and
  closes that event, so it waits for real active work without depending
  on a proc that may never have started."
  {:malli/schema [:function
                  [:=> [:cat] [:map]]
                  [:=> [:cat :map] :map]
                  [:=> [:cat :map :keyword] :map]
                  [:=> [:cat :map :keyword :any]
                   [:tuple :map [:maybe [:map-of :keyword [:vector :some]]]]]]}
  ([]
   {:ins {::episode "One payload-free episode signal from the mailbox."}
    :outs {}
    :workload :io
    :ping-map-fn (fn [state]
                   (select-keys state [:seon.cluster.run/id]))})
  ([args]
   args)
  ([state transition]
   (when (= ::flow/stop transition)
     (async/offer!
      (:seon.cluster.agent/turn-stopped
       (:seon.cluster.loop/cluster state))
      ::stopped))
   state)
  ([state _input _message]
   (let [cluster (:seon.cluster.loop/cluster state)
         completion (:seon.cluster.loop/completion cluster)]
     (if-some [_ready (async/<!! completion)]
       (try
         (let [agent-id (:seon.cluster.agent/id state)
               connection (:seon.db/connection cluster)
               process (:seon.cluster.run/process cluster)
               now (Date.)
               ;; SETTLE BEFORE DERIVING, scoped to this agent: an orphaned
               ;; run keeps its agent busy, and per-agent graphs settle their
               ;; own orphan (conservation §5)
               _ (when-let [orphan (work/interruption @connection agent-id)]
                   (cluster.loop/settle-interruption!
                    cluster (:seon.cluster.run/id orphan) now))
               request {:seon.cluster.agent/id agent-id
                        :seon.cluster.run/process process}
               ;; ONE database value for the derivation
               next (work/next-agent-work @connection request)]
           (if (nil? next)
             [(dissoc state :seon.cluster.run/id)
              nil]
             (let [report (cluster.loop/turn
                           {:seon.cluster.loop/cluster cluster
                            :seon.cluster.work/next next}
                           now)]
               ;; self-rewake into this agent's OWN mailbox, coalescing on
               ;; its (sliding-buffer 1): it cannot recurse, because the pass
               ;; is only re-entered after this transform returns
               (when (work/more-agent-work? @connection request)
                 (async/offer! (:seon.cluster.wake/channel cluster) ::wake))
               [(let [run-id (held-run-id @connection agent-id process)]
                  (cond-> (dissoc state :seon.cluster.run/id)
                    run-id (assoc :seon.cluster.run/id run-id)))
                ;; flow's own report channel: observation, never a dependency
                {::flow/report [report]}])))
         (finally
           (when-not (async/offer! completion ::ready)
             (throw
              (ex-info
               "The agent turn could not publish its terminal completion."
               {:seon.error/kind ::turn-completion-undeliverable
                :seon.cluster.agent/id
                (:seon.cluster.agent/id state)})))))
       [state nil]))))

;;; ---------------------------------------------------------------------------
;;; The ONE blueprint
;;; ---------------------------------------------------------------------------

(defn graph-definition
  "The ONE blueprint: (agent-id, handle) → a `create-flow` definition.
  Pure data — `create-flow` allocates no threads, so a stamped
  definition costs nothing until started. The handle already carries
  this agent's own mailbox and schedule channels plus its armed-ready
  completion permit (`arm!` puts them there), so the definition is a
  projection of its request. The one
  conn rides `(sliding-buffer 1)`: a wake says only \"look\", the turn
  pass derives ALL of this agent's work from one fresh database value,
  so coalescing is free by the same argument that made the central
  pass's wake safe."
  {:malli/schema [:=> [:cat :seon.cluster.agent/blueprint-request] :map]}
  [{handle :seon.cluster.loop/cluster agent-id :seon.cluster.agent/id}]
  ;; Every proc in this agent's graph carries the cluster's environment
  ;; SCOPED to this agent, so work leaving a proc on any thread still names
  ;; which cluster and which agent it belongs to.
  (let [environment (env/scope (env/of handle)
                               {:seon.cluster.agent/id agent-id})]
    (cond->
     {:procs
      {::mailbox
       {:proc (seon.flow/var-process
               #'mailbox-step :io
               (env/carry {:seon.cluster.wake/channel
                           (:seon.cluster.wake/channel handle)}
                          environment))}
       ::turn
       {:proc (seon.flow/var-process
               #'turn-step :io
               (env/carry {:seon.cluster.loop/cluster handle
                           :seon.cluster.agent/id agent-id}
                          environment))
        :chan-opts {::episode {:buf-or-n (async/sliding-buffer 1)}}}
       ::schedule
       {:proc (seon.flow/var-process
               #'schedule/schedule-step :io
               (env/carry {:seon.cluster.loop/cluster handle
                           :seon.cluster.agent/id agent-id
                           :seon.schedule/channel
                           (:seon.schedule/channel handle)}
                          environment))}}
      :conns [[[::mailbox ::episode] [::turn ::episode]]]}
      (:seon.flow/executor handle)
      (assoc :io-exec (:seon.flow/executor handle)))))

;;; ---------------------------------------------------------------------------
;;; The routing entry and the lifecycle
;;; ---------------------------------------------------------------------------

(defn routing
  "A fresh routing entry: the process-local map atom arming rebuilds.
  `::armed` by agent id (the management view); `::channels` by
  recipient ENTITY id (the wake handler's one-lookup delivery);
  `::fault-channel` set once when the cluster's fan-out stands, read by
  every later arm."
  {:malli/schema [:=> [:cat] :seon.cluster.agent/routing]}
  []
  (atom {::armed {} ::channels {}}))

(defn armed
  "The armed entry for `agent-id`, or nil."
  {:malli/schema [:=> [:cat :seon.cluster.agent/routing
                       :seon.cluster.agent/id]
                  [:maybe :seon.cluster.agent/armed]]}
  [routing agent-id]
  (get-in @routing [::armed agent-id]))

(defn fenced?
  "True when this agent is QUARANTINED: armed, routed, mailbox closed.

  DERIVED from the two process-local artifacts that already exist —
  presence of the routing entry and the channel's own `closed?` — so
  there is no quarantine flag, no fenced-id set, and nothing to keep in
  sync. The state is real and is the one `seon.cluster.loop`'s terminal
  settlement fence creates: the agent must take no further pass over
  its still-running receipt until boot recovery marks that receipt
  interrupted, so its mailbox is closed IN PLACE while its entry stays,
  and `arm!`'s idempotence then leaves it alone until the next boot.

  It is a total answer for the three states an agent can be in:
  unarmed (no entry) is `false`, live is `false`, fenced is `true`. An
  ordinarily disarmed agent never reads `true` because `disarm!` drops
  the entry BEFORE closing the channel — that ordering is what lets
  `wake/delivery` read a closed reachable route as the fence rather
  than as a teardown race."
  {:malli/schema [:=> [:cat :seon.cluster.agent/routing
                       :seon.cluster.agent/id]
                  :boolean]}
  [routing agent-id]
  (boolean
   (some-> (armed routing agent-id)
           :seon.cluster.wake/channel
           async.protocols/closed?)))

(defn channels
  "The current entity-id → mailbox-channel map, for `wake/route!`."
  {:malli/schema [:=> [:cat :seon.cluster.agent/routing]
                  [:map-of :int :seon.flow/channel]]}
  [routing]
  (::channels @routing))

(defn fenced-route?
  "True when `channel` is the CURRENT route for `agent-eid` and closed.

  This is the delivery-side quarantine predicate. Identity matters: a
  stale channel retained by a caller after re-arm is not the agent's
  fence, and a closed non-agent route is not represented here at all."
  {:malli/schema [:=> [:cat :seon.cluster.agent/routing
                       :int
                       :seon.flow/channel]
                  :boolean]}
  [routing agent-eid channel]
  (boolean
   (and (identical? channel (get (::channels @routing) agent-eid))
        (async.protocols/closed? channel))))

(defn arm!
  "Arm one agent's graph: stamp → start → resume → route → prime.
  Idempotent per agent (an armed agent is left alone — the armer's
  derive-all pass makes repeats ordinary). The graph's error channel
  joins the cluster's ONE fault channel tagged with the agent
  (`close? false`, so this agent's stop never closes the committer's
  inbox). The routing entry is registered BEFORE the prime, and the
  prime is last: its pass derives from facts, so a message committed
  before this graph existed is answered by construction. Orphans of a
  previously dead process are settled by that same first pass — step 1
  of the turn transform — so the wedge cannot return through the
  arming gap. Returns the armed entry.

  ARMING IS SERIALIZED BY CONSTRUCTION: the armer proc is the ONE arm
  site (boot primes the armer; the listener offers to the armer), so
  two concurrent arms of one agent are unrepresentable without a
  second caller someone would have to write. Refuses an agent id with
  no committed entity — the armer derives its set from facts, so a
  missing entity is a caller bug, never a nil routing key."
  {:malli/schema [:=> [:cat :seon.cluster.agent/arm-request]
                  :seon.cluster.agent/armed]}
  [{handle :seon.cluster.loop/cluster
    agent-id :seon.cluster.agent/id
    routing :seon.cluster.agent/routing}]
  (or (armed routing agent-id)
      (let [connection (:seon.db/connection handle)
            eid (db/q '[:find ?agent .
                       :in $ ?id
                       :where [?agent :seon.cluster.agent/id ?id]]
                     @connection agent-id)
            _ (when (nil? eid)
                (throw (ex-info "arm! refused: no such agent in facts."
                                {:seon.error/kind ::no-such-agent
                                 :seon.cluster.agent/id agent-id})))
            wake-channel (async/chan (async/sliding-buffer 1))
            schedule-channel (async/chan (async/sliding-buffer 1))
            completion (async/chan 1)
            turn-stopped (async/promise-chan)
            _ (async/>!! completion ::ready)
            agent-handle (assoc handle
                                :seon.cluster.wake/channel wake-channel
                                :seon.schedule/channel schedule-channel
                                :seon.cluster.loop/completion completion
                                :seon.cluster.agent/turn-stopped turn-stopped)
            graph (flow/create-flow
                   (graph-definition
                    {:seon.cluster.loop/cluster agent-handle
                     :seon.cluster.agent/id agent-id}))
            started (flow/start graph)
            entry {:seon.cluster.agent/id agent-id
                   :seon.cluster.agent/eid eid
                   :seon.cluster.loop/cluster handle
                   :seon.flow/graph graph
                   :seon.cluster.wake/channel wake-channel
                   :seon.schedule/channel schedule-channel
                   :seon.cluster.loop/completion completion
                   :seon.cluster.agent/turn-stopped turn-stopped}]
        (seon.flow/join-error-fanout!
         {:seon.flow/started started
          :seon.flow/fault-channel (::fault-channel @routing)
          :seon.flow/tag {:seon.cluster.agent/id agent-id}})
        (flow/resume graph)
        (swap! routing
               (fn [current]
                 (-> current
                     (assoc-in [::armed agent-id] entry)
                     (assoc-in [::channels eid] wake-channel))))
        ;; the arm prime — every mailbox arm primes exactly once
        (async/offer! wake-channel ::wake)
        entry)))

(defn- turn-completion-backstop-ms
  [db cluster-name
   agent-id]
  (let [settings (ai/settings (config/effective db cluster-name)
                              (ai/agent-overlay db agent-id))
        targets (ai/targets settings)
        primary-timeout (get-in targets [:seon.ai/primary
                                         :seon.ai/timeout-ms])
        backup-timeout (get-in targets [:seon.ai/backup
                                        :seon.ai/timeout-ms])
        maximum-retries
        (:seon.ai.retry/maximum-retries (ai/retry-strategy settings))
        provider-budget
        (if backup-timeout
          (+ primary-timeout backup-timeout)
          (* primary-timeout (inc maximum-retries)))
        retry-budget
        (if backup-timeout
          0
          (:seon.ai.retry/maximum-total-delay-ms
           (ai/retry-strategy settings)))]
    (+ provider-budget retry-budget)))

(defn- provider-call-capture-basis
  [db agent-id]
  (db/q '[:find ?basis-t .
         :in $ ?agent-id
         :where
         [?agent :seon.cluster.agent/id ?agent-id]
         [?agent :seon.cluster.agent/run ?run]
         [?run :seon.cluster.run/process _]
         [?capture :seon.context.capture/run ?run]
         [?capture :seon.context.capture/basis-t ?basis-t]]
       db agent-id))

(defn- await-turn-completion!
  [routing entry]
  (let [completion (:seon.cluster.loop/completion entry)
        turn-stopped (:seon.cluster.agent/turn-stopped entry)
        {connection :seon.db/connection
         cluster-name :seon.cluster/name}
        (:seon.cluster.loop/cluster entry)
        database-event (async/chan (async/sliding-buffer 1))
        listener-key (random-uuid)]
    (if-some [terminal (or (async/poll! completion)
                           (async/poll! turn-stopped))]
      terminal
      (try
        (d/listen connection listener-key
                  (fn [_transaction-report]
                    (async/offer! database-event ::committed)))
        (let [agent-id (:seon.cluster.agent/id entry)
              provider-db
              (loop []
                (let [db @connection]
                  (if-let [basis-t
                           (provider-call-capture-basis db agent-id)]
                    (db/as-of db basis-t)
                    (let [[value selected]
                          (async/alts!! [completion turn-stopped database-event]
                                        :priority true)]
                      (if (or (= selected completion)
                              (= selected turn-stopped))
                        value
                        (recur))))))]
          (if-not (map? provider-db)
            provider-db
            (let [timeout-ms
                  (turn-completion-backstop-ms
                   provider-db cluster-name agent-id)
                  backstop (async/timeout timeout-ms)
                  [value selected]
                  (async/alts!! [completion turn-stopped backstop]
                                :priority true)]
              (if (or (= selected completion)
                      (= selected turn-stopped))
                value
                (let [failure
                      (ex-info
                       "Agent turn completion exceeded its provider-derived backstop."
                       {:seon.error/kind ::turn-completion-backstop
                        :seon.cluster.agent/id agent-id
                        :seon.ai/timeout-ms timeout-ms})
                      fault
                      {::flow/pid ::turn
                       ::flow/status :stopping
                       ::flow/op ::turn-completion-backstop
                       ::flow/ex failure
                       :seon.cluster.agent/id agent-id}]
                  (async/offer! (::fault-channel @routing) fault)
                  (binding [*out* *err*]
                    (println "SEON CORE FAULT (agent stop backstop):"
                             (ex-message failure)
                             (pr-str (ex-data failure)))
                    (flush))
                  (throw failure))))))
        (finally
          (d/unlisten connection listener-key)
          (async/close! database-event))))))

(defn disarm!
  "Orderly stop of one agent's graph, idempotent.
  Arm publishes one completion permit before scheduling the turn proc.
  An active transform holds it and republishes it from `finally`; an idle
  graph leaves it ready. Request stop, consume that event, then drop the
  routing entry before closing its channels. Thus disarm waits through a
  seconds-long active model call, while an accepted-but-never-started proc
  cannot strand teardown. If the loud backstop fires, the entry remains so
  disarm can be retried after the turn settles. Stop drops conn contents —
  safe by the transport law; triggers are rows and survive.

  THE ORDER OF THE LAST STEPS IS LOAD-BEARING, not stylistic: completion is
  observed before destructive cleanup, then the entry is dropped BEFORE its
  channel is closed, so `wake/route!` can never reach a channel this function
  closed. That is what makes a
  closed-but-reachable route mean exactly one thing — the terminal
  settlement fence — and lets `fenced-route?` make the exact route
  recognizable to `wake/delivery` without a flag. Closing first would
  put an ordinary teardown into the same state and the recognition
  would become a guess."
  {:malli/schema [:=> [:cat :seon.cluster.agent/disarm-request] :nil]}
  [{agent-id :seon.cluster.agent/id
    routing :seon.cluster.agent/routing}]
  (when-let [entry (armed routing agent-id)]
    (flow/stop (:seon.flow/graph entry))
    (await-turn-completion! routing entry)
    (swap! routing
           (fn [current]
             (-> current
                 (update ::armed dissoc agent-id)
                 (update ::channels dissoc
                         (:seon.cluster.agent/eid entry)))))
    (async/close! (:seon.cluster.wake/channel entry))
    (async/close! (:seon.cluster.loop/completion entry))
    (async/close! (:seon.cluster.agent/turn-stopped entry)))
  nil)

;;; ---------------------------------------------------------------------------
;;; The armer — hosted in the cluster's own graph (R7)
;;; ---------------------------------------------------------------------------

(defn armer-step
  "The armer transform, in Flow's four arities.
  Derive-all under a payload-free wake: (agents in facts) − (armed
  set), arm each, sorted for determinism. The wake set grows by
  `:seon.cluster.agent/id` — a committed agent creation IS an arm wake
  — and the listener also offers here when it sees a `to`-ref with no
  routing entry (the created-and-messaged-in-one-commit belt).
  Coalescing on its sliding-1 in-port is safe by the standard argument.
  L8 holds by construction: arming writes nothing, and the prime is an
  `offer!`. A quiescence request acknowledges that every earlier arm wake
  has settled before cluster teardown disarms agent graphs. The stop
  transition publishes the cluster graph's completion."
  {:malli/schema [:function
                  [:=> [:cat] [:map]]
                  [:=> [:cat :map] :map]
                  [:=> [:cat :map :keyword] :map]
                  [:=> [:cat :map :keyword :any]
                   [:tuple :map [:maybe [:map-of :keyword [:vector :some]]]]]]}
  ([]
   {:ins {}
    :outs {}
    :workload :io
    :ping-map-fn (fn [state]
                   (assoc {}
                          ::armed-count
                          (count (::armed @(:seon.cluster.agent/routing
                                            state)))))})
  ([args]
   (assoc args
          ::flow/in-ports {::arm (:seon.cluster.wake/channel
                                  (:seon.cluster.loop/cluster args))}))
  ([state transition]
   (when (= ::flow/stop transition)
     (async/put! (:seon.cluster.loop/completion
                  (:seon.cluster.loop/cluster state))
                 ::stopped))
   state)
  ([state _input message]
   (cond
     (nil? message)
     [state nil]

     (::quiesce message)
     (do
       (async/put! (::quiesce message) ::quiesced)
       [state nil])

     :else
     (let [handle (:seon.cluster.loop/cluster state)
           routing (:seon.cluster.agent/routing state)
           db @(:seon.db/connection handle)
           agents (db/q '[:find [?id ...]
                         :where [_ :seon.cluster.agent/id ?id]]
                       db)
           unarmed (remove #(contains? (::armed @routing) %) agents)]
       (doseq [agent-id (sort unarmed)]
         (arm! {:seon.cluster.loop/cluster handle
                :seon.cluster.agent/id agent-id
                :seon.cluster.agent/routing routing}))
       [state nil]))))
