---
type: research
status: active
tags: [research, prd, database, flow, agent]
---

# Datahike resource lifetime research — 2026-07-15

## Scope and decision posture

This report executes tasks 4, 5, 7, 8, and 9 from
[[source-grounded-research-tasks-2026-07-15]]. It records mechanisms, races,
options, and decision briefs. It does not select the final architecture. Sean
remains the decision owner after the cache-identity and transport lanes provide
their evidence.

Selected source revisions:

- Datahike `9ada755087228e10cfb179fa5779ce227a6ed220`;
- Konserve `df6818d43ea3363a808cd051c0d68917f1b987a9`; and
- Seon at the containing checkout revision, including the existing coordinate,
  registry, protocol, writer, and replica mechanisms cited below.

## Existing lifetime vocabulary

Use the dependency's nouns directly:

- a Datahike **connection reference** is one acquisition counted in
  `datahike.connections/*connections*`;
- a Datahike **connection** is the shared connection object for one
  `connection-id`;
- a Seon **attachment** is `{database-id, branch}`;
- a Seon **coordinate** is `{database-id, branch, commit-id, t}`;
- a pod **db-id** is an opaque, process-local key retaining one immutable
  database value; and
- a Datahike **listener key** is the opaque key owning one callback in the
  connection's listener atom.

There is no evidence for introducing a parallel `lease`, `snapshot`, or
`subscription` entity.

## Task 4 — connection demand and references

### What Datahike already guarantees

`reserve-connection-opening!` performs one atomic transition over the global
connection registry. A matching live connection increments `:count`; matching
concurrent openers become waiters; the first caller reserves opening ownership;
and mismatched acquisition or physical-store keys do not join the entry
(`connections.cljc:37-88`). Publication converts the reservation to a shared
connection whose count is the opening owner plus its waiters
(`connections.cljc:90-106`). Failed opening removes only the matching
reservation (`connections.cljc:108-117`).

`release-connection-reference!` decrements a positive count atomically. The
transition from one to zero records a completion channel and identifies exactly
one final releaser. Later releases observe `:in-progress`; other live references
observe `:retained` (`connections.cljc:11-35`). A new connect against the
zero-count entry observes `:releasing`, and `connect` rejects it rather than
opening over resources being closed (`connections.cljc:75-88`,
`connector.cljc:297-307`). Only after final cleanup does
`delete-connection!` mark the shared connection `:released` and remove the
registry entry (`connections.cljc:119-122`, `connector.cljc:504-507`).

Final release already has the essential dependency order:

1. writer shutdown closes admission and drains accepted writes;
2. secondary indexes close after the writer can no longer touch them;
3. the Konserve store releases after the writer and indexes; and
4. the connection registry entry is deleted last.

The implementation collects writer, secondary-index, and store errors, records
completion, and throws the aggregate (`connector.cljc:467-507`). Opening failure
also closes an opened writer, restored secondary indexes, and store before
removing its reservation (`connector.cljc:383-408`).

### Executable state-machine probe

The following read-only process-local probe was run from
`reference-code/datahike`; it bound `*connections*` to a fresh atom and opened
no database or lifecycle service:

```clojure
(let [registry (atom {})
      c1 (clojure.core.async/promise-chan)
      c2 (clojure.core.async/promise-chan)
      done (clojure.core.async/promise-chan)
      k [:probe]]
  (binding [datahike.connections/*connections* registry]
    (reserve-connection-opening! k c1 :cfg :store)
    (reserve-connection-opening! k c2 :cfg :store)
    (complete-connection-opening! k c1 :conn)
    (release-connection-reference! k false done)
    (release-connection-reference! k false done)
    (reserve-connection-opening! k c2 :cfg :store)))
```

Observed states were `:owner`, `:opening`, a published `:count` of `2`, then
`:retained`, `:last`, and `:releasing`. This directly falsifies the need for a
second counter merely to prevent open/release overlap.

### What remains unproved

- No retained test currently exercises session crash cleanup or reconnect after
  the completion is delivered and the registry entry deleted.
- A query takes an immutable database value, not a connection reference. Final
  release does not wait for arbitrary caller-owned query Futures because none
  are currently registered with the connection state machine.
- Listener callbacks and protocol response encoding are synchronous work outside
  the connection count; neither is drained by `d/release` unless its owner does
  so first.
- Branches have distinct connection IDs but may share `:physical-store-key` and
  the write-hook atom (`connections.cljc:59-72`). Connection count is therefore
  branch-scoped while some store-facing coordination is physical-store-scoped.

### Options for Sean

**A. One connection reference per remote session.** This reuses Datahike's
existing count and makes an abnormal disconnect equivalent to releasing that
session's acquisition. It gives exact demand, but large agent counts create
large reference counts and every session must prove exactly-once cleanup.

**B. One connection reference per active attachment, with sessions owned by the
authority registry.** This keeps Datahike lifecycle at its natural shared-owner
boundary and lets many agent sessions share it. It needs a small authority-side
session ownership map for routing and cancellation, but that map is not a second
database reference count if the attachment is admitted independently of client
count.

**C. Hybrid admission reference plus temporary operation references.** This can
protect long queries during idle shutdown, but risks duplicating Datahike's
count and adding atomic traffic. Consider it only if the in-flight lane proves
that stop-admission plus bounded drain cannot protect accepted operations.

All three are reversible behind the protocol because connection references are
process-local and never cross the wire.

**Decision brief 4:** decide whether an attachment stays warm independently of
client presence. If no, option A is the closest existing primitive. If yes,
option B expresses that policy without pretending every socket is a physical
database owner. Do not choose C without a demonstrated release/query race.

## Task 5 — coordinate versus pod db-id

Seon's coordinate is durable identity. `coordinate/resolved` derives physical
database UUID, branch, commit UUID, and max transaction from a committed raw
database value (`src/seon/db/coordinate.cljc:47-65`). `coordinate/at` validates
a temporal cut against its containing commit and attachment
(`coordinate.cljc:86-107`). The protocol already uses attachment- and
head-fenced release requests (`src/seon/db/protocol.cljc:273-279,550-555`).

The pod db-id solves a different problem. Every `db`, `as-of`, `since`,
`history`, or `db-with` result is inserted into a process-local `dbs` atom with
its parent connection ID (`codegen/pod.clj:241-247,265-275`).
`generate-db-id` deliberately creates a fresh commit UUID even for an equivalent
value so speculative values cannot collide (`codegen/pod.clj:383-388`).
`release-db` only dissociates that retained value (`codegen/pod.clj:394-400`).
The supplied `with-db` macro is structured `try/finally` cleanup
(`codegen/pod.clj:100-111`).

Important lifetime gap: pod connection release removes the `conns` entry, but
does not cascade through `dbs`; every unreleased db-id continues to retain its
database value and parent connection ID (`codegen/pod.clj:249-262,377-400`).
Listeners are intentionally unavailable through the pod because callbacks do
not serialize (`codegen/pod.clj:76-89`).

### Options for Sean

**A. Coordinates only.** Resolve each operation from durable identity. This is
stateless, reconnect-friendly, and has no retained-handle leak, but repeated
resolution of historical or speculative values may repeat work.

**B. Coordinates at the public boundary plus optional db-ids within one
session.** A successful resolution returns an opaque handle tied to the session;
subsequent queries avoid reconstruction. Disconnect releases all session-owned
handles. The coordinate remains the only reconnectable identity.

**C. Public db-ids.** This minimizes request size but makes reconnect,
capability transfer, expiry, and stale-handle behavior part of the public
protocol. It duplicates the role of coordinates and is hardest to replace with
a non-JVM authority.

**Decision brief 5:** retain the coordinate as the protocol identity. Admit B
only after measuring repeated coordinate resolution and historical-value
retention. If selected, call the value a `db-id` to match Datahike, scope it to
one session and attachment, make release idempotent, and never present it as a
second durable coordinate. C has no source-grounded advantage beyond avoiding
resolution and should remain rejected unless measurement is decisive.

## Task 7 — listener and session ownership

Datahike stores callbacks in the shared connection's metadata under opaque
keys. Registering the same key replaces the callback; `unlisten!` dissociates
that key (`core.cljc:206-224`). The existing test proves that two committed
reports arrive before unlisten and the later transaction does not
(`test/listen_test.cljc:11-41`). Thus “subscribed” can be derived from ownership
of a listener key; no active flag is needed.

The callback path is unsafe for network work. After the writer returns a report,
`transact!` iterates every listener and calls it directly before delivering the
transaction result (`writer.cljc:345-368`; merge repeats the pattern at
`writer.cljc:381-395`). One slow or throwing callback can delay result delivery
and the remaining listeners. Datahike provides no per-listener queue,
backpressure, error isolation, or asynchronous dispatch here.

Seon's current replica already demonstrates one higher-level pattern: one feed
socket buffers live events under a fixed bound during replay, then dispatches
them into the connection's one native listener bus
(`src/seon/db/replica.cljs:761-781,1195-1245`). That is evidence for separating
Datahike publication from network delivery, not evidence that the current
cluster-wide feed should survive unchanged.

### Options for Sean

**A. One Datahike listener per attachment.** Its callback performs only a
nonblocking offer of a compact committed-event descriptor to an
attachment-owned bounded dispatcher. Session interests, queues, and cancellation
live after that boundary. This minimizes writer callback work and listener
cardinality.

**B. One Datahike listener per session.** The listener key naturally proves
ownership and unlisten is direct, but callback count and writer-thread work grow
with agents. A slow callback remains hazardous unless every callback only
offers to an independent queue, multiplying queues and allocations.

**C. No listener for query-only sessions.** Return transaction coordinates to
writers and let readers explicitly query by coordinate. Add attachment listener
demand only for sessions that request committed events.

Options A and C compose and remain protocol-reversible. B is easy to prototype
but costly to remove if callback identity leaks into durable data.

**Decision brief 7:** start the final design discussion with A+C. Require a
measured bound and overflow policy before any remote notification capability is
accepted. The Datahike callback must never encode, write a socket, block, or run
a client predicate. A session is subscribed precisely when the dispatcher owns
its interest key; disconnect removes that key. The attachment listener exists
iff at least one capability needs committed events.

## Task 8 — scoped eviction and final release

The query-result cache is one JVM-global weighted LRU. Its database-value bucket
key is currently `[hash max-tx max-eid]`; limits are global by snapshot count and
shallow result weight (`query.cljc:2376-2427`). The only public maintenance
operations reset the entire cache (`query.cljc:2429-2455`). There is no
database-, attachment-, connection-, or session-scoped eviction operation.
Parent snapshot buckets intentionally remain after propagation because callers
may still query old immutable values (`query.cljc:2587-2615`).

Therefore final `d/release` does **not** evict query results. Those buckets age
out only through global LRU pressure or a global reset. Adding scoped eviction
before task 1 settles bucket identity would risk evicting another physical
database or failing to find all branches and temporal values.

A safe candidate release sequence, subject to tasks 1–3 and executor evidence,
is:

1. atomically stop admitting new attachment operations and mark the generation;
2. remove the one attachment listener so no new events enter dispatch;
3. close session admission and finish or cancel already accepted queries and
   encodes according to their documented semantics;
4. evict only completed and encoded results proven to belong to that attachment
   and generation;
5. call the existing final `d/release`, which drains writer, closes secondary
   indexes, releases Konserve, and deletes the connection; and
6. publish completion only after cleanup, allowing reconnect to open a new
   generation.

The generation fence is necessary above Datahike: its registry rejects reconnect
while release is in progress, but a stale authority callback could otherwise
evict results created after a completed release and reconnect. A compare-and-
remove against the exact attachment owner/generation is sufficient; a durable
lease entity is not.

**Decision brief 8:** do not add scoped query eviction until task 1 provides a
physical database/branch-aware cache identity. Decide whether an idle attachment
should release immediately or after a bounded grace interval using reconnect
latency and retained RSS. Either policy uses the same generation-fenced state
machine, so the timing choice is reversible.

## Task 9 — distinct cache and resource layers

These layers have different keys, owners, sharing, and release behavior:

| Layer | Current owner and bound | Sharing scope | Release today |
|---|---|---|---|
| Parsed queries | Datahike global volatile LRU, 100 entries | JVM | LRU only |
| Query plans | Datahike global volatile LRU, 100 entries | JVM and compatible schema key | LRU only |
| Query results | Datahike global weighted LRU, 64 database-value buckets and 1,000,000 shallow units by default | Current db cache key, not connection-scoped | LRU or global reset only |
| Immutable index nodes | Database value and persistent-set structures backed by Konserve | Values may structurally share parent nodes | Becomes collectible when no db/cache/handle retains it; durable nodes remain stored |
| Konserve EDN values | Store-local `:cache`, default 32 top-level keys | One decorated store object | Cache atom becomes unreachable with the released store; file backend has no explicit close |
| Secondary indexes | Handles restored into one connection database value | Connection/branch, with durable backing as configured | Explicitly closed after writer drain |
| Pod database values | Pod-global `dbs` atom by fresh db-id | One pod process; each ID retains its value | Explicit `release-db`; no connection cascade |
| Listener callbacks | Shared connection metadata atom by listener key | One connection | Explicit unlisten or connection collection |
| Encoded responses | No shared authority cache yet | None | Request-local |
| In-flight queries/encodes | No common owner yet | Caller/executor | Caller-specific |

Evidence: query cache defaults and ownership are at
`query.cljc:2358-2427`; Konserve installs a store-local default LRU of 32 and
performs locked read-through (`konserve/cache.cljc:19-39`); writes evict or
refresh only their top-level store key (`cache.cljc:84-190`). Konserve release is
backend-specific: memory and file are no-ops, while tiered stores recursively
release frontend and backend (`konserve/store.cljc:249-253,296-299,421-432`).
Deleting a store is separate from releasing it and must never be used as idle
cleanup (`store.cljc:175-202,242-253,290-299`).

No retained measurement yet attributes RSS or allocation to these layers. The
next measurement must report each independently rather than infer memory from a
single JVM RSS number:

- query-result bucket count, entry count, shallow weight, hits, and misses;
- parsed-query and plan-cache occupancy;
- Konserve cache occupancy per store and backend reads;
- retained raw, historical, speculative, and pod db values;
- secondary-index native/heap ownership;
- encoded byte-cache weight, if introduced; and
- in-flight count, queued work, cancellation state, and retained request bytes.

**Decision brief 9:** set no per-database memory budgets from the current
defaults. First establish which query-result buckets belong to which physical
database and measure a 1/2/4/8-database workload. Keep global plan/query parsing
caches global unless schema isolation or contention disproves that choice. Keep
Konserve and secondary-index metrics separate because release and sharing differ.

## Race and lifetime model to test

```mermaid
stateDiagram-v2
    [*] --> Opening: first matching acquire
    Opening --> Active: connection published
    Opening --> [*]: open failed and cleanup finished
    Active --> Active: another acquire or nonfinal release
    Active --> Quiescing: final demand requests release
    Quiescing --> Releasing: admission closed; listeners and in-flight work drained
    Releasing --> [*]: Datahike final release completed
    [*] --> Opening: reconnect creates new generation
```

Required falsifiers before a PRD decision:

- disconnect and duplicate disconnect release exactly one session owner;
- connect racing open joins the one opener; connect racing release receives a
  retryable releasing error and succeeds only after completion;
- a query accepted before quiescing completes or returns a documented
  cancellation value before store release;
- listener callback work remains constant as session count rises and a blocked
  client cannot delay transaction completion;
- old db-ids and listener keys become unresolvable after session disconnect;
- stale release work cannot remove a reconnected generation's listener, result,
  or connection; and
- releasing one attachment does not change query/cache/listener evidence for a
  sibling branch or unrelated physical database.

## Decisions to review with Sean

1. Is an attachment warm only while a client exists, or may the authority retain
   it for a bounded idle grace period?
2. Should database demand map one-to-one to session connection references, or
   should one admitted attachment reference serve many sessions?
3. Are session-scoped pod-style db-ids worth their retained-memory and cleanup
   surface after coordinate-resolution benchmarks?
4. Which clients need committed-event interest at all? Query-only sessions need
   no listener.
5. What is the bounded overflow contract for a slow interested client: drop and
   replay, disconnect, or coalesce to the newest coordinate?
6. Should final release evict every completed historical result for an
   attachment, or may globally bounded LRU entries outlive the connection for a
   measured reconnect benefit?

These decisions remain intentionally open. Tasks 1–3 must settle cache identity
and single-flight ownership, and the Bun transport lane must quantify connection
and encoding costs, before the final PRD freezes them.

## Dependency seam — changes we can make in Datahike

Seon owns the maintained Datahike fork, so the final design need not wrap a weak
dependency API. The placement rule is: put a capability in Datahike when it is
generic to embedded database-value/resource ownership and can be correct without
knowing sockets, agents, or clusters. Put policy in Seon when it depends on
remote sessions, capabilities, transport, admission, fairness, or product
semantics.

### Generic Datahike candidates

**Inspectable connection state.** Expose a read-only data view rather than
requiring callers to inspect `*connections*`:

```clojure
(d/connection-status config)
;; => {:datahike.connection/state :active
;;     :datahike.connection/reference-count 3
;;     :datahike.connection/branch :db
;;     :datahike.connection/physical-store-key ...}
```

This belongs in Datahike because the opening/releasing states and acquisition
count already live there. It improves proof and observability without exposing
the connection atom or completion channel. Compatibility cost is additive and
low; the internal map remains private.

**Structured final-release result.** `release` currently returns nil or throws,
while its implementation already distinguishes absent, retained, in-progress,
and final cleanup. A generic data result could expose whether physical resources
were retained or closed and summarize cleanup errors:

```clojure
(d/release conn)
;; => {:datahike.connection/release :retained
;;     :datahike.connection/reference-count 2}
```

Changing the existing return is source-compatible for callers that ignore nil
but could break callers asserting nil. A safer migration is an opt-in detailed
option followed by deliberate replacement in Seon. Do not add a Seon wrapper
that independently reconstructs these states.

**Scoped query-cache identity and eviction.** Once task 1 proves the identity
law, Datahike should own the database-value bucket descriptor and operations
over it:

```clojure
(dq/query-cache-stats)
(dq/evict-query-cache! {:datahike.db/physical-id id})
(dq/evict-query-cache! {:datahike.db/physical-id id
                        :datahike.db/branch branch})
```

The selector shape is illustrative, not settled. The essential change is that
Datahike, which creates keys and propagation edges, also tracks enough generic
provenance to evict one physical database or branch safely. Seon should request
eviction at an idle boundary but never duplicate the bucket index. Compatibility
cost is internal cache-key migration and cache invalidation at upgrade; cached
values are disposable, so rollback is straightforward.

**Owned database-value handles.** The pod's `dbs` atom demonstrates the utility
but not a complete lifecycle. If benchmarks justify handles, move the generic
mechanism out of pod code into a Datahike namespace with explicit owner and
cascade release:

```clojure
(d/retain-db {:datahike.db/value db
              :datahike.db/owner session-key})
;; => {:datahike.db/id opaque-id}

(d/release-dbs {:datahike.db/owner session-key})
;; => {:datahike.db/released-count n}
```

The handle ID must remain process-local. Datahike can own retention, lookup,
owner-scoped release, and metrics; Seon owns which protocol session is the
owner and whether clients may receive handles. This would delete the pod-only
parallel registry and let pod bindings consume the same mechanism. Compatibility
requires retaining pod `release-db` as a thin generated call during migration.

**Listener dispatch safety.** Keyed registration is sound, but synchronous
callback execution on the transaction delivery path is a generic database
hazard. Datahike could expose one explicit delivery choice rather than making
every host build a defensive callback:

```clojure
(d/listen conn listener-key callback
          {:datahike.listener/delivery :bounded-dispatch
           :datahike.listener/capacity 256
           :datahike.listener/overflow :disconnect})
```

This interface is only a sketch. The optimal primitive may instead be a
connection-level committed-report source with one nonblocking offer and no
per-listener executor. Datahike must define ordering, exception isolation,
overflow, and shutdown. Seon still owns filtering, replay, socket queues, and
slow-client policy. Replacing synchronous listener behavior globally would be a
compatibility break; retain `:caller` delivery initially and make the safer
mode explicit until all callers migrate.

**Unified resource report.** Datahike can report its own layers without knowing
protocol sessions:

```clojure
(d/resource-stats conn)
;; namespaced data for connection refs, retained db values, query buckets,
;; Konserve cache occupancy, secondary indexes, listeners, and writer state
```

Konserve may need a generic `cache-stats` protocol so Datahike does not reach
into backend records. Counts and structural weights are suitable; JVM objects,
callbacks, cache entries, and socket state are not. This is additive and should
replace ad hoc private-var inspection in benchmarks.

### Seon policy that should not enter Datahike

- mapping Bun socket/session identities to attachment demand;
- authentication, capabilities, database names, and cluster admission;
- transport framing, encoding, request IDs, cancellation envelopes, and retry;
- whether an attachment stays warm and for how long;
- fairness between databases and agents;
- notification interest predicates, replay cursors, and client overflow policy;
- durable coordinates and protocol conformance for non-Datahike authorities;
  and
- generation fencing across disconnect, reconnect, and network ownership.

Datahike may expose the primitives that make these policies cheap, but embedding
them there would couple the reusable database to Seon's topology.

### Deletion and simplification consequences

If owned handles land generically in Datahike, delete the pod's private `dbs`
registry and generate pod operations against the common owner. If scoped cache
identity and eviction land in Datahike, Seon needs no cache registry or bucket
index. If Datahike exposes resource and connection status, Seon should publish
those namespaced values rather than mirror counters. If committed-report
dispatch becomes safely bounded in Datahike, delete any Seon shim whose only job
is protecting the writer from callback latency; retain only protocol filtering,
replay, and delivery.

**Decision brief 13:** the strongest dependency-level package to evaluate is
scoped query-cache ownership + resource stats first, owned db handles only if
resolution measurements justify them, and safe committed-report dispatch after
listener latency is measured. Connection/session mapping and idle policy remain
Seon decisions. This ordering yields observability before behavior changes and
keeps each change independently reversible.
