---
type: research
status: active
tags: [research, database]
---

# Probe — writer connection pool viability for host writer-call! (2026-07-21)

## Verdict: POOL VIABLE

A small pool of retained UDS connections with per-call deadlines is safe
against the current writer. Executed evidence (real `writer/start!` over a
scratch `:memory` database, 8+ concurrent connections from one JVM process)
proves independent admission, per-database (not per-connection) idempotency,
clean mid-request connection death, and re-admission after close. The one
writer-side limit is the transport's 256-connection cap.

U1's caution — "the writer scopes database access to physical connections, so
per-call reconnects are wrong" — is correct about *reconnect-per-call* and
irrelevant to a *retained pool*: what is connection-scoped is the registry
acquisition refcount (the database releases when its **last** physical
connection's acquisitions release). N retained pool connections each acquire
on first touching request and hold until closed; the database stays open as
long as any pool member lives. Per-call connect/close would churn
acquire/release and could release shared indexes between calls; a retained
pool never does.

## 1. What the writer scopes to a physical connection (source)

- **Per-connection state** — `src/seon/db/writer.clj:2813-2823`
  (`transport-connection`): each accepted UDS session gets its own
  `::connection-lock`, `::closed?`, `::acquisitions`,
  `::database-advanced-acquisitions`, `::interests`, `::event-state`.
- **Database acquisition** — `writer.clj:1842-1880`
  (`connection-for-request`): every database-touching request calls
  `registry/acquire-database!` under that connection's lock and records the
  `[database-name connection-id]` acquisition on the connection. Admission is
  per-request; there is no session handshake beyond ensure/resolve — a new
  connection is fully served after `ensure-database` (idempotent) or even a
  bare read against an already-open database.
- **Interest registration** (listen/unlisten) is per-connection
  (`writer.clj:2288-2320, 4008-4018`) — a pool must either pin interests to
  one designated member or re-register on member replacement.
- **Request identity/claims are global, not per-connection** —
  `claim-request!` (`writer.clj:2834-2849`) claims request-ids in one
  runtime-wide `::active-requests` map; duplicate ids across connections are
  refused while active (`writer.clj:4026-4033`), so pool members must not
  share in-flight request-ids (they use random UUIDs — fine).
- **Connection close** — `close-transport-connection!`
  (`writer.clj:3881-3946`): marks closed, removes interests, neuters and
  cancels every request owned by that connection, awaits them, then releases
  each recorded acquisition. Other connections' acquisitions are untouched.
- **Execution is a shared bounded executor, not per-connection** —
  `handle-request!` (`writer.clj:3948-4036`) dispatches reads/transactions
  into `seon.db.executor` work classes; `executor.clj:119-158` (`capacity`)
  bounds `:read` at `cpu-workers` concurrent with per-database queues;
  `:mutation`/`:delivery` are serialized per database
  (`executor.clj:117, 231-235`). So server-side concurrency is by capacity
  class, never serialized per client connection.
- **Transport limit** — `uds.cljc:174` `default-maximum-connections 256`;
  `accept-session!` (`uds.cljc:971-976`) closes the accepted channel
  immediately when at the cap. `writer/start!` (`writer.clj:4099-4105`) does
  not override it, so the live writer cap is 256 per server.

## 2. Executed probe

Code: `tmp/writer-pool-probe/src/probe/pool.clj` (run via
`tmp/writer-pool-probe/run.sh`). Real `writer/start!`, `:memory` backend,
`selected-processors 5` → 4 read workers; 1500 seeded entities; slow query =
~1.1M-pair cross join (~0.9-1.0 s server-side); quick query = one
identity-attribute lookup. Each of 8 connections performed the same
ensure-database + resolve-head handshake `seon.host.context` performs, using
the identical `protocol/ensure-database-request` / `resolve-head-request`
builders. All 8 handshakes succeeded: `{[true true] 8}`.

### 2a. No head-of-line blocking across connections

Quick-query latency on conns 2-8 (30 calls each, concurrent):

| condition | median | p95 | max |
|---|---|---|---|
| conn 1 idle (baseline) | 3.07 ms | 11.06 ms | 21.98 ms |
| conn 1 running a 775 ms slow query | 2.15 ms | 7.09 ms | 9.86 ms |
| conns 1-4 running 4 slow queries (~990-1000 ms each, all 4 read workers busy) | 1.57 ms | 5.27 ms | 959.15 ms |

One stalled/slow connection does not degrade the others at all. Saturating
every read worker (4 concurrent ~1 s queries, cache defeated with distinct
arguments) still leaves median/p95 unchanged; only the tail (max 959 ms)
shows a quick call queued until the first worker freed — executor capacity,
not per-connection serialization. Pool sizing conclusion: pool size should
not exceed useful server read parallelism (`cpu-workers`), and per-call
deadlines must exceed worst queued-read time under saturation.

### 2b. Idempotency receipt is per-database, not per-connection

Transact through conn 3 with request-id R (`ok? true, recovered? nil,
t 536870918`); replay the identical request-id + data through conn 5 →
`ok? true, recovered? true`, same `db-after :t` (durable recovery via the
`:seon.db.protocol/request-id` datom, `writer.clj:1218-1314`). Same
request-id with different data through conn 6 → refused,
`:seon.db.protocol.error/request-conflict` (`writer.clj:1294-1307`). Retrying
an in-doubt write through a *different* pool member is safe and exact.

### 2c. Mid-request connection death is isolated; new connections admit

Conn 2 sent a slow query and was closed 100 ms later (client-side deadline
simulation). Conns 1,3-8 immediately after: median 1.96 ms, p95 7.66 ms —
unaffected. A brand-new conn 9 handshook `[true true]` and served quick
queries at median 0.89 ms. After the 2d mass open/close, a fresh connection
still admitted `[true true]` — no writer-side leak.

### 2d. Connection-count limit confirmed at 256

Opening connections until refusal: refusal came with exactly **256
concurrent server-side sessions** (7 surviving handshaked members + 249 new
— the probe counter printed 257 because it counted the already-closed
victim), matching `default-maximum-connections 256`. The refused
connection's `connect` still succeeded (listen backlog) but the server
accept-and-closed it, so its first call failed with EOF — a pool must treat
first-call EOF as "at capacity", not as a dead writer. After closing them
all, a fresh connection admitted fine. A host pool of 4-8 is nowhere near the cap, but
the cap is per-writer-process across ALL clients (pod + hosts + tools);
budget accordingly.

## 3. Deadline sanity: orphaned in-flight work

Client-side deadline + close leaves the writer healthy: after the 2c kill,
executor evidence reached `running 0, queued 0, retained-identities 0,
fenced 0, rejected 0` (the orphaned query had already drained by first
check; `completed 690` counts it). Mechanics from source:

- `close-transport-connection!` cancels each owned request via
  `handle-cancel` (`writer.clj:1075-1110`): queued executor jobs are removed
  (`executor/cancel-queued!`, `executor.clj:690-713`); a **running** job is
  only flagged `::canceled? true` (`executor.clj:680-683`) — it is not
  preempted.
- A flagged running read runs to its next phase boundary: multi-phase query
  jobs re-check `continue-query-job?` (`writer.clj:2911-2919`) and
  `continue-work!` refuses canceled/fenced continuations
  (`executor.clj:377-381`); single-phase work runs to completion and
  `finish-outcome!` discards the result as a canceled outcome
  (`executor.clj:404-418`). Delivery is a no-op because the entry's
  `::complete!` was neutered at close (`writer.clj:3905-3908`).
- Scope fencing (`fence-scope!`/`fence-and-drain!`, `executor.clj:581-658`)
  is a *database-removal* mechanism, not a connection-close mechanism; a
  client deadline never fences a scope, so no scope can be left stuck.

Implication for the pool: a per-call deadline that closes the pooled
connection wastes at most the remainder of the in-flight read (bounded by
the writer's own read-spend limits) and one acquire on the replacement
connection. No writer restart, no stuck state.

## Proven pool recipe

1. Open `uds/connect!`; send `ensure-database` (explicit backend/path) once
   per pool member, then `resolve-head` — the exact `seon.host.context`
   handshake; both are idempotent.
2. Keep members retained; one in-flight `uds/call!` per member; random-UUID
   request-ids (never share an id across members while active).
3. On deadline: close that member only, replace it with a fresh
   connect+ensure; retry idempotent writes with the SAME request-id through
   any member (2b).
4. Pin `listen` interests to one designated member and re-register them if
   that member is replaced.
5. Pool size ≤ writer `cpu-workers`; total client connections per writer
   process budgeted under 256.
