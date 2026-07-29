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

  TWO PROCS, both pinned `:io`, both var step-fns through the one
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

  Evals are NOT a proc here: they hop to the one bounded `:compute`
  door (`seon.flow/submit!!` under the one `:interrupt-fn`), exactly as
  the loop always did. `:mixed` appears nowhere — `var-process` refuses
  it at construction.

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
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.work :as work]
            [seon.flow :as seon.flow]
            [seon.schema.edn :as schema.edn])
  (:import [java.util Date]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/agent.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Namespace assignment
;;; ---------------------------------------------------------------------------

(defn creation-tx
  "Create one agent already assigned to its namespace.

  Pure transaction data: the namespace identity and agent identity land in
  the same commit, so the formal creation path has no ownerless basis."
  {:malli/schema [:=> [:cat :seon.cluster.agent/creation-request]
                  :seon.cluster.agent/creation-tx]}
  [{agent-id :seon.cluster.agent/id namespace-name :seon.ns/name}]
  (let [namespace-tempid (str "namespace:" namespace-name)]
    [{:db/id namespace-tempid
      :seon.ns/name namespace-name}
     {:seon.cluster.agent/id agent-id
      :seon.cluster.agent/namespace namespace-tempid}]))

(defn owner-of
  "The agent id assigned to `namespace-name`, or nil."
  {:malli/schema [:=> [:cat :any :seon.ns/name]
                  [:maybe :seon.cluster.agent/id]]}
  [db namespace-name]
  (d/q '[:find ?agent-id .
         :in $ ?namespace-name
         :where
         [?namespace :seon.ns/name ?namespace-name]
         [?agent :seon.cluster.agent/namespace ?namespace]
         [?agent :seon.cluster.agent/id ?agent-id]]
       db namespace-name))

;;; ---------------------------------------------------------------------------
;;; The two proc steps
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
  (d/q '[:find ?id .
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
  close, the pass-local custody law, and the pre-provider capture —
  then `offer!` one wake into this agent's OWN mailbox when
  `more-agent-work?`. Coalescing on sliding-1 keeps the rewake
  non-recursive. Failures inside the pass stay VALUES (the existing
  `refused!`/`error-tx` owners); a Throwable that escapes anyway is a
  core fault and rides this graph's error channel into the cluster's
  fault committer, tagged with the agent. The stop transition publishes
  the orderly-stop completion only after the active transform has
  returned, so disarm honestly joins a seconds-long model call."
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
                   (select-keys state [::passes ::turns
                                       :seon.cluster.run/id]))})
  ([args]
   (assoc args ::passes 0 ::turns 0))
  ([state transition]
   (when (= ::flow/stop transition)
     (async/put! (:seon.cluster.loop/completion
                  (:seon.cluster.loop/cluster state))
                 ::stopped))
   state)
  ([state _input _message]
   (let [cluster (:seon.cluster.loop/cluster state)
         agent-id (:seon.cluster.agent/id state)
         connection (:seon.store/branch-connection cluster)
         process (:seon.cluster.run/process cluster)
         now (Date.)
         ;; SETTLE BEFORE DERIVING, scoped to this agent: an orphaned
         ;; run keeps its agent busy, and per-agent graphs settle their
         ;; own orphan (conservation §5)
         _ (when-let [orphan (work/interruption @connection agent-id)]
             (cluster.loop/settle-interruption!
              cluster (:seon.cluster.run/id orphan) now))
         request {:seon.cluster.agent/id agent-id
                  :seon.cluster.run/process process
                  :seon.cluster.work/now now}
         ;; ONE database value for the derivation
         next (work/next-agent-work @connection request)]
     (if (nil? next)
       [(-> state
            (update ::passes inc)
            (dissoc :seon.cluster.run/id))
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
            (cond-> (-> state
                        (update ::passes inc)
                        (update ::turns inc)
                        (dissoc :seon.cluster.run/id))
              run-id (assoc :seon.cluster.run/id run-id)))
          ;; flow's own report channel: observation, never a dependency
          {::flow/report [report]}])))))

;;; ---------------------------------------------------------------------------
;;; The ONE blueprint
;;; ---------------------------------------------------------------------------

(defn graph-definition
  "The ONE blueprint: (agent-id, handle) → a `create-flow` definition.
  Pure data — `create-flow` allocates no threads, so a stamped
  definition costs nothing until started. The handle already carries
  this agent's own mailbox channel and completion (`arm!` puts them
  there), so the definition is a projection of its request. The one
  conn rides `(sliding-buffer 1)`: a wake says only \"look\", the turn
  pass derives ALL of this agent's work from one fresh database value,
  so coalescing is free by the same argument that made the central
  pass's wake safe."
  {:malli/schema [:=> [:cat :seon.cluster.agent/blueprint-request] :map]}
  [{handle :seon.cluster.loop/cluster agent-id :seon.cluster.agent/id}]
  {:procs
   {::mailbox
    {:proc (seon.flow/var-process
            #'mailbox-step :io
            {:seon.cluster.wake/channel
             (:seon.cluster.wake/channel handle)})}
    ::turn
    {:proc (seon.flow/var-process
            #'turn-step :io
            {:seon.cluster.loop/cluster handle
             :seon.cluster.agent/id agent-id})
     :chan-opts {::episode {:buf-or-n (async/sliding-buffer 1)}}}}
   :conns [[[::mailbox ::episode] [::turn ::episode]]]})

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

(defn channels
  "The current entity-id → mailbox-channel map, for `wake/route!`."
  {:malli/schema [:=> [:cat :seon.cluster.agent/routing]
                  [:map-of :int :seon.flow/channel]]}
  [routing]
  (::channels @routing))

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
      (let [connection (:seon.store/branch-connection handle)
            eid (d/q '[:find ?agent .
                       :in $ ?id
                       :where [?agent :seon.cluster.agent/id ?id]]
                     @connection agent-id)
            _ (when (nil? eid)
                (throw (ex-info "arm! refused: no such agent in facts."
                                {:seon.error/kind ::no-such-agent
                                 :seon.cluster.agent/id agent-id})))
            wake-channel (async/chan (async/sliding-buffer 1))
            completion (async/promise-chan)
            agent-handle (assoc handle
                                :seon.cluster.wake/channel wake-channel
                                :seon.cluster.loop/completion completion)
            graph (flow/create-flow
                   (graph-definition
                    {:seon.cluster.loop/cluster agent-handle
                     :seon.cluster.agent/id agent-id}))
            started (flow/start graph)
            entry {:seon.cluster.agent/id agent-id
                   :seon.cluster.agent/eid eid
                   :seon.flow/graph graph
                   :seon.cluster.wake/channel wake-channel
                   :seon.cluster.loop/completion completion}]
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

(defn disarm!
  "Orderly stop of one agent's graph, idempotent.
  Stop, JOIN the turn proc's completion (which flow publishes only
  after the active transform — including a seconds-long model call —
  has returned), then drop the routing entry and close the mailbox.
  Stop drops conn contents — safe by the transport law; triggers are
  rows and survive."
  {:malli/schema [:=> [:cat :seon.cluster.agent/disarm-request] :nil]}
  [{agent-id :seon.cluster.agent/id
    routing :seon.cluster.agent/routing}]
  (when-let [entry (armed routing agent-id)]
    (flow/stop (:seon.flow/graph entry))
    (async/<!! (:seon.cluster.loop/completion entry))
    (swap! routing
           (fn [current]
             (-> current
                 (update ::armed dissoc agent-id)
                 (update ::channels dissoc
                         (:seon.cluster.agent/eid entry)))))
    (async/close! (:seon.cluster.wake/channel entry)))
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
  `offer!`. The stop transition publishes the cluster graph's
  completion."
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
                   (assoc (select-keys state [::passes])
                          ::armed-count
                          (count (::armed @(:seon.cluster.agent/routing
                                            state)))))})
  ([args]
   (assoc args
          ::flow/in-ports {::arm (:seon.cluster.wake/channel
                                  (:seon.cluster.loop/cluster args))}
          ::passes 0))
  ([state transition]
   (when (= ::flow/stop transition)
     (async/put! (:seon.cluster.loop/completion
                  (:seon.cluster.loop/cluster state))
                 ::stopped))
   state)
  ([state _input _message]
   (let [handle (:seon.cluster.loop/cluster state)
         routing (:seon.cluster.agent/routing state)
         db @(:seon.store/branch-connection handle)
         agents (d/q '[:find [?id ...]
                       :where [_ :seon.cluster.agent/id ?id]]
                     db)
         unarmed (remove #(contains? (::armed @routing) %) agents)]
     (doseq [agent-id (sort unarmed)]
       (arm! {:seon.cluster.loop/cluster handle
              :seon.cluster.agent/id agent-id
              :seon.cluster.agent/routing routing}))
     [(update state ::passes inc) nil])))
