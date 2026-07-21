---
type: research
status: active
tags: [research, database, architecture]
---

# Audit — database read/write parallelism, robustness, cluster isolation (2026-07-21)

Read-only source audit of `src/seon/db/` (writer, executor, server, protocol,
transport), `src/seon/host/context.clj`, pod-side `src/seon/db.cljs` +
`src/seon/db/transport/uds.cljs`, and the operator launch path
(`src/seon/launch.cljc`, `script/seon/dev/{cluster,process,config}.clj`).
Every claim cites file:line in this checkout
(branch `codex/runtime-reliability-refactor`).

## Summary verdicts

### (a) "Clusters are independent; one cluster cannot take another down"

**PARTIAL — logical isolation is STRONG, process isolation is shared within
one operator.**

- Each cluster is its own database (own Datahike connection, own on-disk
  path `data/clusters/<name>/db` — `script/seon/dev/cluster.clj:48-52`) and
  its own pod process (`script/seon/dev/cluster.clj:84,114`). A pod crash is
  fully isolated.
- But `launch/shared-writer-cluster-descriptor`
  (`src/seon/launch.cljc:410-434`, used at
  `script/seon/dev/cluster.clj:48`) deliberately reuses the source
  operator's writer: all clusters under one `bin/seon` supervisor share ONE
  writer JVM (one process launched per operator,
  `script/seon/dev/process.clj:549-568`; clusters that don't own the writer
  start pod-only, `process.clj:272,512`). A writer-process death (crash,
  OOM at the default `-Xmx512m`, `script/seon/dev/config.clj:44`) takes
  down database service for EVERY cluster on that writer simultaneously.
- Scheduling-level cross-database isolation inside the shared writer is
  genuinely strong (see below). Separate operators (`bin/seon` vs
  `bin/acme`) run separate writer processes on separate sockets and are
  fully independent.

### (b) "Reads/writes are parallelized wherever possible and robust"

**STRONG on the writer and the pod client. WEAK on the JVM sci-host
context path (`seon.host.context`), which is an acknowledged C1-probe-era
serialized boundary.**

- Writer: a bounded multi-class fair dispatcher runs reads in parallel on
  `processors-1` CPU workers while serializing mutations per database only
  (`src/seon/db/executor.clj:116-158,232-234,483-493`). Mutations and
  provider work run on virtual threads so a slow commit never occupies a
  CPU read worker (`executor.clj:445-447,474`).
- Pod: one multiplexed UDS session, up to 256 concurrent in-flight
  requests, per-request deadlines, bounded queues everywhere, idempotent
  transact recovery, session reconnect with interest re-registration.
- JVM host contexts: ONE retained `SocketChannel` under `locking
  call-lock` (`src/seon/host/context.clj:197-198,213-229`) — every agent
  context's read is head-of-line serialized, and the underlying
  `uds/call!` has no deadline (`src/seon/db/transport/uds.cljc:270-287`),
  so a stalled writer response blocks the whole host indefinitely.

## 1. Write path

**Queue and serialization.** Every transact is admitted to the executor as
work-class `:mutation` (`src/seon/db/writer.clj:3802-3825`).
`:mutation` is a serialized class: at most one running mutation per
`[class database]` (`executor.clj:117` `serialized-classes`,
`executor.clj:230-234` eligibility check), while up to
`min(4, (processors+1)/2)` mutations for DIFFERENT databases run
concurrently (`executor.clj:126,150-152`). Inside the database,
`handle-transact`/`prepare-transaction!` also take `locking connection`
(`writer.clj:2095,1336`); the actual Datahike commit is asynchronous
(`transact-once-async!`, `writer.clj:1433-1453`) and executes on a virtual
thread (`executor.clj:445-447`).

**Head-of-line blocking.** Within one cluster (= one database): yes, by
design — single write order per database means a slow commit delays every
other write to that database. Across databases: no — the ready queue is
round-robin over databases (`executor.clj:187-207`) and each database has
its own mutation lane.

**Size caps and rejection.** A transaction is capped at the 4 MiB wire
frame (`src/seon/db/protocol.cljc:102`; enforced both directions,
`uds.cljc:221-224,253-256`, pod side `uds.cljs:19,154-159`). The executor
additionally enforces per-class queue caps, per-database queue caps, and an
authority-wide queued-bytes budget of 8–32 MiB scaled by processors
(`executor.clj:136-158`); overflow is an immediate rejection error to the
caller, never unbounded growth (`executor.clj:544-550,496-501`). There is
NO server-side wall-clock timeout on a commit; the pod client attaches a
120 s deadline (`src/seon/db.cljs:863`) which requests cancellation but —
correctly — keeps the physical callback because the durable receipt, not
the acknowledgement, is authoritative (`uds.cljs:434-453`).

**Cross-cluster.** One writer process per operator, many databases
(`writer.clj` registry handles N databases; `launch.cljc:434` requires a
distinct database name per shared-writer cluster). Verdict: **STRONG**
mechanics, one shared-fate caveat (gap 2/3 below).

## 2. Read path

**Pod.** There is NO pod-local replica. Reads are UDS round-trips carrying
an explicit immutable database value; the session caches only the latest
database *descriptor* (`db.cljs:284,343`), and
`docs/seon/architecture/architecture.md:42` states "no Bun process retains
a Datahike replica" (again at `architecture.md:531`). The root
`CLAUDE.md`/`AGENTS.md` sentence "the pod reads its local immutable
replica ... forwards writes through `seon.db.replica`" does not correspond
to any source namespace (no `src/seon/db/replica.*` exists) — documentation
drift, gap 4.

The pod session is fully multiplexed: request correlation by request-id,
up to 256 pending requests (`uds.cljs:20`), per-request deadlines checked
on a 250 ms tick (`uds.cljs:25,419-453`), and every `seon.db` operation
passes an explicit deadline (15 s head/schema `db.cljs:724,1086`; 30 s
`db.cljs:961,1167`; 60 s `db.cljs:1127`; 120 s transact `db.cljs:863`).
Reads on deadline are detached, rejected, and a cancel op is enqueued
(`uds.cljs:407-418,445-453`).

**Writer-side read concurrency.** Reads execute on `cpu-workers =
max(1, processors-1)` dedicated threads (`executor.clj:122,141-143,
483-488`); `:read` is NOT a serialized class, so N reads for the same or
different databases run concurrently up to the worker count, against
immutable database values (`writer.clj:1025-1058,966-1000`). Long queries
are natively cancellable (`d/run-q!` at `writer.clj:1028`,
`d/cancel-query!` at `writer.clj:2951-2957`). N=100 agents hammering
queries → bounded read queues (`read-queue = max(16, 8*cpu-workers)`,
per-database cap `max(16, 4*cpu-workers)`, `executor.clj:128-143`);
overflow rejects rather than queues.

**JVM host contexts (`seon.host.context`).** Confirmed exactly as the
roadmap says: ONE retained physical connection shared by all agent
contexts, serialized under `locking call-lock` with a single blind
reconnect-and-retry (`context.clj:181-229`); ~2 ms per call measured
(docstring `context.clj:187`; research
`c1-jvm-host-scale-2026-07-20.md` gate results — 100 contexts, one-turn
wave 164 ms). So yes: **that is one serialized socket with head-of-line
blocking on reads.** It could be parallelized — the writer already serves
concurrent reads, and either multiple channels or the pod-style
multiplexed session would remove the lock — but today every host context
read waits behind every other, and `uds/call!` blocks forever if the
writer stalls (no deadline, no cancel: `uds.cljc:270-287`,
`context.clj:213-229`). Reads are NOT served from a local immutable value
on the host either; each `db-query`/`db-pull` does `resolve-head!` plus
the read round-trip (`context.clj:287-306,316-329`) — two serialized
round-trips per read. Classification: **PoC (deliberate C1-stage
boundary), the weakest link in the read story.**

## 3. Robustness

**Connection death / reconnect.**
- Pod: any socket failure terminates the session once, rejects ALL pending
  requests with a terminal error, clears bounded queues
  (`uds.cljs:310-337`), and the next `seon.db` operation lazily reopens the
  session from the retained selection and re-registers every interest
  handler, replaying the current head as a synthetic event
  (`db.cljs:640-655` `active-or-reconnect!`; interest re-registration
  `db.cljs:533-560`).
- Writer: a closed transport connection removes its interests, cancels its
  active requests, waits for in-flight work, and releases its database
  acquisitions with logged proof (`writer.clj:3881-3946`).
- Host: reconnect-once-and-retry (`context.clj:222-229`) — no retry
  budget, no backoff, and a stalled (not closed) writer is never detected.

**Writer restart mid-request (idempotency).** Verified in `writer.clj`:
the request-id and a logical transaction hash are stamped into tx-meta of
every commit (`writer.clj:1361-1365`); on any retry or delivery failure the
writer first queries for a committed transaction bearing that request-id
(`writer.clj:1218-1223,1309-1314`) and reconstructs the full receipt —
datoms, tempids, generated entity ids — from durable data
(`writer.clj:1262-1292`), marking it `::protocol/recovered?`
(`writer.clj:1213`). Hash mismatch on a reused id is an explicit
request-conflict error (`writer.clj:1294-1307`). A commit that wins while
its acknowledgement is lost resolves to the durable receipt
(`writer.clj:1402-1410`). **STRONG.**

**Backpressure.** No unbounded queue was found on either side:
- inbound: authority-wide 32 MiB input reservation with read-pausing
  (`uds.cljc:169,341-358`), 4 MiB frame cap, 256-connection cap
  (`uds.cljc:174`);
- executor: per-class/per-database queue caps plus the queued-bytes budget
  with rejection (`executor.clj:544-550`);
- outbound: 256 authority / 64 per-session response slots and
  256 MiB / 128 MiB output byte caps (`uds.cljc:170-173`); interest events
  coalesce per interest, degrading repetition to a single
  resynchronization event instead of queueing (`writer.clj:2495-2508`,
  pod mirror `uds.cljs:199-227` with hard event-overflow limits);
  a terminal delivery failure closes the offending connection
  (`writer.clj:2482-2489`).

**B8 area.** The two original writer-gate intermittents
(`writer-integration` release path, `query-admission` injected release)
remain OPEN as order-dependent single sightings that did not recur in a 6x
gate loop; the distinct TERM-before-shutdown-hook race they exposed is
CLOSED at `b34548b0` — the hook is now registered before `start!` and
awaits the started promise (`src/seon/db/server.clj:454-491`; ledger
`docs/prds/source-cleanup/roadmap.md:60-61`).

## 4. Isolation of runaway work

**Can cluster A starve cluster B on the shared writer?** Queued work: no —
per-database queue caps and database round-robin (`executor.clj:128-158,
187-207`) guarantee B's admission and turn. Running work: partially — a
runaway query occupies a CPU worker until it completes, hits a resource
ceiling, or is cancelled; A can hold at most `cpu-workers` running reads,
which is also the global read pool, so **all read workers can be
transiently monopolized by one database's already-running queries** until
client deadlines (15–30 s) fire cancellation. Mutation, knn, hnsw, and
delivery lanes are unaffected (separate classes; hnsw is globally 1/1,
`executor.clj:156-158`).

**Per-query deadlines/bounds server-side.** There is no server-imposed
wall-clock deadline; cancellation is client-deadline-driven
(`uds.cljs:419-453` → `writer.clj:3850-3879` → `d/cancel-query!`
`writer.clj:2953`) plus connection-close cancellation
(`writer.clj:3915-3930`). Resource ceilings (`max-work`, `max-results`,
`max-result-weight`) are forwarded only if present on the request
(`writer.clj:818-827`); the writer itself applies **no default**.

**Can an agent OOM the writer?** Pod-originated reads: effectively no —
`seon.db` attaches the configured safety ceilings to EVERY query and pull
(`db.cljs:765-776`; ceilings `config/system.edn:91-103`: query max-work
1e8, max-results 1e6, max-result-weight 3e6), results are validated as
ordinary data (`writer.clj:738-746`), and responses die at the 4 MiB frame.
Host-context or other raw protocol reads: yes in principle — no ceilings
are attached (`context.clj:287-306`), so a pathological cross-product
query materializes unbounded intermediate/result state inside a 512 MiB
heap; the blast radius is the whole writer, i.e., every cluster on it
(gap 2).

**Is bounding general or per-call-site?** General. The one mechanism is
the config-database ceiling triple applied by `seon.db` to every read
plus the protocol's `datahike.resource/*` options and `index-page`
limit/cursor paging (`writer.clj:921-955`); Stage 1.5's bounded transcript
event selection (`docs/prds/runtime-reliability/roadmap.md:1537,1293`) is
call-site pagination *on top of* that general mechanism, not a parallel
one-off bounding system.

## 5. The "replica" and staying current

There is no replica to keep current. The pod stays current through:
1. the latest database value cached from any acquisition, accepted
   transaction, or event (`db.cljs:284,343`; `architecture.md:43-46`);
2. selective committed-report interests — per-scope bounded sources
   (capacity 256, batch 32, `writer.clj:2233-2234`) delivered through the
   `:delivery` class (serialized per database, `executor.clj:117,153-155`);
3. **gap/replay robustness:** if a bounded source overflows (slow
   consumer), the writer detects the gapped status, closes and replaces the
   source, and sends each interested session a `resynchronization` event
   carrying the CURRENT head (`writer.clj:2681-2729`); the client then
   re-derives from current truth rather than replaying a transaction log
   (`architecture.md:405-409`). Lag therefore degrades to one coalesced
   resync + fresh reads, never an unbounded backlog or a divergent
   replica. **STRONG design; it eliminates the gap/replay problem class
   instead of solving it.**

## Classification table

| Area | Mechanism | Class |
|---|---|---|
| Write serialization per database | `:mutation` serialized class + `locking connection` (`executor.clj:117,232-234`; `writer.clj:2095`) | STRONG |
| Cross-database write parallelism | up to 4 mutation lanes, virtual threads (`executor.clj:150-152,445-447`) | STRONG |
| Write size/queue caps | 4 MiB frame + queued-bytes budget + rejection (`protocol.cljc:102`; `executor.clj:136-158,544-550`) | STRONG |
| Transact idempotency across restart | durable request-id/hash receipts + recovery (`writer.clj:1216-1314,1396-1410`) | STRONG |
| Pod read path | multiplexed session, deadlines, cancel, parallel server execution (`uds.cljs:20-25`; `db.cljs:657-666`; `executor.clj:141-143`) | STRONG |
| Read resource ceilings (pod) | config triple on every query/pull (`db.cljs:765-776`; `system.edn:91-103`) | STRONG |
| Read resource ceilings (server default) | none unless the caller sends them (`writer.clj:818-827`) | WEAK |
| JVM host-context reads | one locked channel, no deadline, 2 round-trips/read (`context.clj:197-229,287-306`) | PoC (C1 stage) |
| Backpressure (both directions) | input pausing, slot/byte caps, admission rejection, event coalescing (`uds.cljc:169-174,341-358`; `uds.cljs:199-247`) | STRONG |
| Interest feed lag/gap | bounded source → replace + resync-to-head (`writer.clj:2681-2729`) | STRONG |
| Cluster scheduling isolation | per-database queues, round-robin, fencing, scoped cancel (`executor.clj:128-207,581-658`) | STRONG |
| Cluster process isolation | shared writer JVM per operator, 512 MiB heap (`launch.cljc:410-434`; `config.clj:44`) | WEAK |
| Pod process isolation | one pod process per cluster (`cluster.clj:84,114`) | STRONG |

## Ranked gaps

1. **Host-context serialized reads without deadline** —
   `src/seon/host/context.clj:213-229` + `uds.cljc:270-287`. All agent
   contexts on the JVM host share one locked synchronous channel; a
   stalled writer response blocks every context forever (reconnect-once
   only fires on a thrown failure). Fix direction: reuse the multiplexed
   session pattern (or N channels) plus a socket read deadline; the writer
   already serves concurrent reads.
2. **No server-side default read ceilings** — `writer.clj:818-827` forwards
   caller options only. Any client that omits them (host context does; any
   raw protocol client can) may run an unbounded materialization inside
   the 512 MiB writer heap; writer OOM is shared fate for every cluster on
   that writer. Fix direction: writer-side defaults from the same config
   singleton (caller may lower, not raise).
3. **Shared writer JVM across clusters of one operator** —
   `launch.cljc:410-434`. Intentional (shared indexed values, one heavy
   boundary), but the owner's isolation question should be answered
   honestly: scheduling isolation yes, process-fate isolation no. Worth an
   explicit decision record if per-cluster writer processes are ever the
   requirement.
4. **Documentation drift: "local immutable replica" / `seon.db.replica`**
   — root `CLAUDE.md`/`AGENTS.md` ("Current runtime and boundary" section)
   names a pod replica and a `seon.db.replica` namespace; neither exists,
   and `architecture.md:42,531` states the opposite (no Bun process
   retains a replica). Reconcile the root authority text.
5. **Transient read-worker monopolization** — one database's
   already-running queries can occupy all `cpu-workers` for up to a client
   deadline period (15–30 s) before cancellation lands; no per-database
   cap on RUNNING (as opposed to queued) reads (`executor.clj:209-216`
   caps by class only).
6. **B8 residue** — the two original order-dependent writer-gate
   intermittents remain open single sightings
   (`docs/prds/source-cleanup/roadmap.md:61`); no recurrence in 6x loops,
   sibling TERM race closed at `b34548b0`.
