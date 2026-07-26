---
type: research
status: active
tags: [research, database, runtime]
---

# Writer throughput at hundreds-to-thousands of concurrent drivers — 2026-07-23

Research lane paired with the JVM-concurrency lane (the anchor's scoping
hold names writer contention at 1k callers as limit #2). Question: can the
per-cluster single Datahike writer sustain hundreds→thousands of concurrent
agent drivers when every driver blocks on writer round-trips for
transactions and head reads? Grounded in a direct read of
`src/seon/db/` (writer, executor, server, transport, host, session), the
vendored fork (`reference-code/datahike`, `reference-code/konserve`,
`reference-code/persistent-sorted-set`), and the accepted claim/receipt
designs (`research/loop-cljc-sci-design-2026-07-23.md`,
`research/llm-http-io-design-2026-07-23.md`). No cluster was started; the
one machine-local measurement is a filesystem fsync micro-probe (§3c).
Builds on and does not repeat
`research/audit-db-parallelism-isolation-2026-07-21.md` (isolation
verdicts); this document owns throughput.

## 0. Executive verdict

- **The structural ceiling is NOT Datahike.** Datahike's LocalWriter is
  built to pipeline: one processing thread drains a 120k-deep transaction
  queue and a separate commit thread batches every queued report into one
  storage flush (`reference-code/datahike/src/datahike/writer.cljc:78,206-241`).
- **The ceiling is Seon's own admission policy.** The executor classes
  `:mutation` as a serialized class — at most ONE running mutation per
  database (`src/seon/db/executor.clj:117`, eligibility at
  `executor.clj:231-238`) — and the mutation job stays "running" until the
  Datahike **commit callback** resolves (`executor.clj:420-440,344-357`;
  `writer.clj:2236-2249,1564-1584`). Per database, transactions are
  therefore strictly sequential END-TO-END: prepare → apply → **disk
  commit** → respond → next. Datahike's commit batching never engages,
  because Seon never lets a second transaction into the queue.
- **Estimated per-database ceiling as built: ~60–200 tx/s** (one commit
  flush per transaction, §3). At the design's ~12 writes per turn (§2)
  that supports roughly **150–500 simultaneously active runs per
  database**; 1,000 active runs (~400 tx/s) is 2–6× over; 10,000 (~4,000
  tx/s) is 20–60× over.
- **First escape needs no new mechanism:** admit >1 mutation per database
  into the LocalWriter queue (its single processing thread is the
  serializer; the commit thread amortizes the flush across the batch),
  plus within-turn multi-op coalescing the claim design already permits.
  Estimated 5–20× (§5). Second escape: databases are parallel mutation
  lanes (up to `min(4,(procs+1)/2)` concurrent per writer process,
  `executor.clj:126`); separate operator processes
  (`script/seon/dev/process.clj:594-610`) share nothing.
- **Head reads and queries never contend with the writer** — they run on
  immutable database values on `processors-1` CPU workers
  (`executor.clj:141-143`; `:read` is not a serialized class), so the
  "every driver blocks on head reads" half of the question is a latency
  (round-trip) concern, not a throughput one.

## 1. The writer path, end to end (what serializes where)

One transaction from a pod driver traverses:

1. **Pod session** — ONE multiplexed UDS session per pod process
   (`src/seon/db/session.cljs:415-416`); requests are correlated by
   `request-id` (`session.cljs:147-176`). All drivers in one pod share
   this socket. Transit-JSON codec, 4 MiB max frame
   (`src/seon/db/protocol.cljc:115`; encode/decode
   `src/seon/db/transport/uds.cljc:210-224`).
2. **Transport server** — default 256 connections, 256 authority /
   64 per-session response slots, configurable codec worker pool
   (`uds.cljc:181-188`; config keys `src/seon/db/server.clj:71-91`).
3. **Executor admission** — transact requests submit as work-class
   `:mutation` (`writer.clj:3962-3979`), bounded by
   `maximum-queued-by-database = max(64, 16×mutation-workers)` per
   database (`executor.clj:131,150-152`); overflow is an ordinary
   rejection value ("The database work queue is full, fenced, or
   stopped.", `executor.clj:548-550`).
4. **Per-database serialization** — `:mutation` (and `:delivery`) are
   serialized classes: a database is eligible only when it has ZERO
   running jobs of that class (`executor.clj:117,231-238`). Mutation work
   dispatches onto a virtual thread (`executor.clj:445-447,474`).
5. **Prepare** — under `(locking connection ...)`: durable-idempotency
   recovery query (one indexed lookup of
   `[?tx :seon.db.protocol/request-id ?rid]` per transact,
   `writer.clj:1342-1347,1440-1445,1467-1470`), expected-db assertion,
   schema derivation from the transaction data, per-attribute value
   coercion, tempid receipts, and meta stamping (request-id +
   request-hash + protocol version on the tx entity,
   `writer.clj:1457-1514,1492-1496`).
6. **Datahike apply** — `d/transact!` enqueues onto the LocalWriter's
   bounded transaction queue (`datahike/writer.cljc:46-56`, queue depth
   120,000 at `:78`); ONE processing thread applies the tx against the
   in-memory chained db (`writer.cljc:96-185`), including `:db.fn/cas`
   resolution (`datahike/db/transaction.cljc:963-985,1138-1140`).
7. **Commit** — a separate commit thread drains EVERYTHING queued
   (`(into [tx] (take-while some?) (repeatedly #(poll! commit-queue)))`,
   `writer.cljc:206`), persists ONLY the newest db (`:211`), then
   delivers every batched callback and offers each report to the
   committed-report feed (`:233-241`). `commit-wait-time` defaults to
   0 ms (`:83`). Commit = flush dirty persistent-sorted-set nodes for all
   six indexes (`keep-history? true` doubles eavt/aevt/avet,
   `src/seon/db/backend.clj:120-125`;
   `datahike/writing.cljc:49-110`) + commit record + branch head
   (`writing.cljc:423-560`), each a konserve key write.
8. **Respond** — `finish-transaction!` builds the transaction report
   (db-before/after descriptors, public datoms, tempids,
   `writer.clj:1527-1550,690-712`), the executor releases the database's
   mutation slot (`executor.clj:396-418,344-357`), and the response is
   transit-encoded back over the UDS session.
9. **Interest delivery** (after commit, off the transact path) — the
   delivery class polls committed-report batches (capacity 256, batch 32,
   `writer.clj:2366-2367,2853-2862`), selects interests by CHANGED
   ATTRIBUTE (attribute-indexed tables, `writer.clj:2780-2795`), and
   sends one full transaction-report event per matching interest
   (`writer.clj:2797-2812`). A gapped (slow) consumer gets a
   resynchronization event instead (`writer.clj:2814-2851`). Delivery is
   serialized per database with per-database queue depth 1
   (`executor.clj:117,153-155`).

**Head read vs transact.** A head read resolves the current committed
value (a deref of the connection the commit thread `reset!`s,
`datahike/writer.cljc:233`) and executes on the `:read` class —
`processors-1` concurrent CPU workers, per-database queue up to
`max(16, 4×cpu-workers)`, never serialized (`executor.clj:141-143`).
Datahike databases are immutable values; a reader holds a snapshot and
never blocks the writer. A pod head read therefore costs one UDS
round-trip + transit codec + scheduling — order 0.5–2 ms unloaded — and
scales with CPU workers, not with the mutation lane. The JVM sci host's
db leaf is a retained pool of `processors-1` sessions with a 1 s lease
wait (`src/seon/db/host.clj:14-17,33-46`), so host-side callers are
additionally bounded to `processors-1` concurrent round-trips per
process.

## 2. The write-load model (writes per turn, honestly counted)

From the claim design (`loop-cljc-sci-design-2026-07-23.md` §2–§4) and
the LLM I/O design (`llm-http-io-design-2026-07-23.md` §1c), a driven
turn commits, as separate fenced transactions unless stated:

| # | Write | Cadence | Source |
|---|---|---|---|
| 1 | Claim acquire/reacquire (run-holding process CAS + epoch CAS + beat) | once per run open / steal, amortized ≪1 per turn | loop design §2a |
| 2 | `open-turn!` allocation (turn row + pointer+epoch fence) | 1 per turn | turn.cljs:535-559; loop design §2b |
| 3 | Phase `:rendered` (+ prompt blob ref + rendered-tx) | 1 | loop design §3 |
| 4 | LLM attempt OPEN receipt | 1 per attempt (≥1) | llm-http-io §1c `attempt-open-tx` |
| 5 | LLM attempt TERMINAL (CAS `:open→outcome` + reply-blob link, same tx) + `:reply-ready` advance | 1–2 per attempt | llm-http-io §1c `attempt-terminal-tx` |
| 6 | Eval receipts: `:running` BEFORE each form + terminal CAS after | 2 per form (typ. 1–3 forms → 2–6) | host/eval.clj receipt-before-run; loop design §0 |
| 7 | Agent's own effect txs (messages, `my.*` facts, plan updates) | 0–5, workload-dependent | domain |
| 8 | `:evaled` + `:published` advances | 1–2 (composable with 6/7 tails) | loop design §3 |
| 9 | `close-turn!` (telemetry merge, final pull pin) | 1 | turn.cljs:569-637 |
| 10 | Beat (heartbeat/lease renewal, fence-led) | 1 per turn + idle cadence | run.cljs:37,777-789 |

**Total: ~9–20 tx per turn, median ≈ 12.** Each tx also carries ~4–6
meta/receipt datoms beyond the domain datoms (request-id, request-hash,
version, provenance user/process, tempid receipts —
`writer.clj:1489-1496`), so a 1-datom beat is a ~5–8 datom transaction.

**Idle claimed runs beat.** Today the beat is per turn and the ticker
runs every 30 s (`loop.cljs:1215-1219`); the watchdog threshold is 20 min
(`config/system.edn:499`). An idle claimed run at ticker cadence is
0.033 tx/s; the design-headroom scenario in this mission ("beats every
few seconds") is 0.2–0.5 tx/s per run.

**Expected transact load** (turn duration LLM-dominated, ~15–60 s per
turn while active; 12 tx/turn → 0.2–0.8 tx/s per active run):

| Scenario | tx/s (median est.) |
|---|---|
| 100 active runs | ~40 (range 20–80) |
| 1,000 active runs | ~400 (200–800) |
| 10,000 active runs | ~4,000 (2,000–8,000) |
| 1,000 idle claimed, 30 s beats | ~33 |
| 1,000 idle claimed, 3 s beats | ~333 |
| 10,000 idle claimed, 30 s beats | ~333 |

Read load rides alongside (every turn renders context = many
execute-many reads, plus head reads per acquire) but occupies the
parallel `:read` lane and is excluded from the ceiling below.

## 3. The ceiling estimate (stated assumptions)

### 3a. Per-transaction latency budget, as built

Because the mutation lane is one-at-a-time per database (§1.4), the
per-database throughput is `1 / end-to-end-tx-latency`:

| Stage | Estimate | Basis |
|---|---|---|
| UDS + transit decode/encode | 0.1–0.5 ms | small frames (beat ≈ hundreds of bytes), JSON transit, codec pool |
| Executor admission + vthread dispatch | <0.1 ms | in-memory queues (`executor.clj:503-571`) |
| Prepare (recover query + coercion + schema derivation) | 0.3–1 ms | one indexed lookup + per-datom walks (`writer.clj:1457-1514`) |
| LocalWriter apply (6 PSS inserts, bf 512) | 0.2–1 ms | in-memory B-tree, branching factor 512 (`datahike/index/persistent_set.cljc:471`) |
| **Commit flush** | **3–15 ms** | ~12–20 dirty nodes (2–3 per index × 6 + commit record + branch head), each fressian-serialized + written with per-key fsync (§3c) |
| Report build + respond | 0.2–1 ms | `writer.clj:690-712` |
| **Total** | **~4–18 ms** | → **~60–250 tx/s per database** |

Stated assumptions: warm JVM; database of 10⁵–10⁷ datoms (tree depth
2–3 at branching factor 512); no embedding writes (`SEON_EMBED` off);
Apple-silicon Mac local APFS.

### 3b. What saturates, in order

1. **The per-database serialized mutation lane** (~60–200 tx/s): the
   commit flush is paid PER TRANSACTION because Seon admits one mutation
   per database at a time — the batching machinery datahike ships is
   idle. This binds at roughly **150–500 active runs per database**.
2. **The per-database mutation queue (64)** — beyond ~64 pending writes
   the writer answers with rejections (errors-as-values); at 1,000
   drivers bursting a beat tick simultaneously this triggers well before
   the lane saturates in steady state (`executor.clj:131,544-547`).
3. **Interest-delivery fanout** — one transit-encoded full
   transaction-report event per matching interest per commit
   (`writer.clj:2797-2812`). Attribute-indexed candidate selection keeps
   the match cheap, but 1k per-agent wake interests over hot attributes
   (`run` beats hit `:seon.agent.run/last-beat-at`) mean up to
   N×tx/s event encodes; slow consumers degrade to resynchronization
   (correct, but each resync is a fresh head read storm). Delivery
   per-database depth 1 (`executor.clj:153-155`) means it lags rather
   than amplifies.
4. **The one pod UDS session** — 64 in-flight response slots per session
   (`uds.cljc:185`) and single-socket framing. At thousands of drivers
   in ONE pod this bounds concurrency before the transport's 256
   connections do. (An all-JVM driver fleet using the host pool is
   instead bounded at `processors-1` concurrent calls per process,
   `host.clj:16`.)
5. **NOT saturating:** Datahike's transaction queue (120k), fsync itself
   (§3c), the read lane (parallel), CAS conflict rates (claims are
   per-run; disjoint runs never contend on a datom — writer-level
   serialization is the only cross-run coupling).

### 3c. Machine measurement: fsync is cheap here; per-key overhead is not the disk

Konserve's filestore defaults to `sync-blob? true, in-place? false`
(temp write + fsync + atomic rename per key,
`reference-code/konserve/src/konserve/filestore.clj:692-694`), and
Seon's backend passes no overriding `:config`
(`src/seon/db/backend.clj:120-154`;
`reference-code/konserve/src/konserve/store.cljc:272-293`). Measured on
this machine (probe in scratchpad, 4 KiB payloads, APFS):

- temp-write + `fsync` + rename: **0.105 ms/key** (Java
  `FileChannel.force` maps to plain fsync);
- plain write: 0.053 ms/key;
- `F_FULLFSYNC` (true platter durability, NOT what the JVM issues):
  4.07 ms/key.

So a 14–20-key commit costs ~1.5–2 ms of filesystem overhead plus node
serialization; the 3–15 ms commit estimate is dominated by fressian
serialization of 512-entry nodes and konserve bookkeeping, not the disk.
Corollary: macOS durability is already soft (fsync ≠ platter); batching
commits does not weaken a guarantee Seon actually has.

## 4. Cross-cluster sharding (the stated escape) — confirmed with one caveat

- Databases inside one writer process share NOTHING at the Datahike
  level: each has its own connection, LocalWriter thread pair, store
  directory, and committed-report scopes. The executor runs mutations
  for DIFFERENT databases concurrently up to
  `min(4, (processors+1)/2)` (`executor.clj:126,150-152`), so one
  writer process scales to ~4 parallel mutation lanes.
- Separate operators are separate JVM writer processes with their own
  socket, heap, and store (`script/seon/dev/process.clj:594-610` —
  `--db-name <cluster>`, `--path <cluster-dir>/db`,
  `--req-sock <per-operator socket>`): nothing serializes across them.
- **Caveat (already flagged by the parallelism audit):** all clusters
  under ONE `bin/seon` supervisor share ONE writer JVM
  (`audit-db-parallelism-isolation-2026-07-21.md` §shared-writer).
  Sharding to more than ~4 mutation lanes, or isolating crash blast
  radius, requires more operator processes, not more database names.

## 5. Coalescing opportunities WITHOUT a new mechanism

1. **Let the LocalWriter pipeline (biggest single win, est. 5–20×).**
   Admit >1 in-flight mutation per database. Datahike is structurally
   ready: the single processing thread is the correctness serializer,
   `:datahike/expected-basis-t` rejects stale optimistic writes at apply
   (`writer.cljc:120-131`; `writer.clj:1516-1525`), and the commit
   thread amortizes ONE flush over every queued report
   (`writer.cljc:206-241`). The cost drops from ~(apply+commit) to
   ~apply per tx. Honest blockers to engineer through, in place:
   - `recover-current` reads the COMMITTED value
     (`writer.clj:1440-1445`); with pipelining, a duplicate op-id racing
     its own in-flight original is not detected until commit. Needs an
     in-flight request-id set alongside the durable receipt (process
     memory as cache, receipts stay the authority — same posture as the
     claim design's atoms-as-caches ruling).
   - Generated-id candidate allocation and tempid derivation read
     `d/db` pre-commit (`writer.clj:1485-1491`); allocation writers
     already assert exclusivity (`writer.clj:2228-2229`), so allocation
     txs can stay serialized while ordinary txs pipeline.
   - Commit batching means multiple tx-ids share one commit-id — already
     an accepted constraint (loop design §1 "sharp edge"; no
     branch-from-arbitrary-t).
2. **Within-run multi-op coalescing (free today).** Datahike composes
   multiple CAS ops + asserts in one tx (`transaction.cljc:963-985`);
   the claim design already mandates fence + phase advance + phase facts
   in ONE tx (loop design §3). Adjacent cursor advances that today are
   separate txs (`:evaled` then `:published` then close) can ride the
   tail tx they annotate, trimming ~2–4 tx/turn.
3. **Cross-run beat batching — design tension, flag not fix.** N runs'
   beats in one multi-op tx would collapse idle load by N, and the one
   ticker is a natural batching point — but every beat leads with a
   per-run fence CAS and a CAS MISMATCH ABORTS THE WHOLE TRANSACTION
   (`transaction.cljc:963-985` raise). One displaced run would poison
   every co-batched beat. Batching therefore requires either
   retry-with-exclusion at the ticker (no schema change, but a loop) or
   dropping the fence from pure beats and letting the staleness scan
   tolerate a late beat from a displaced run-holding process (semantics change —
   owner call). Do NOT silently choose.
4. **Not a coalescing target:** eval receipt pairs (receipt-before-run
   is the durability point — merging open+terminal would delete the
   crash evidence the design exists for); attempt open/terminal
   (same reason).

## 6. Probe plan (runnable later; not run here)

Three probes, smallest-first, all against existing surfaces — no new
runner, no gym. Bench code lives beside the existing
`bench/db_scale.clj` precedent; correctness assertions stay out (this is
measurement, not a 4th test surface).

### Probe A — in-process writer ceiling (no transport)

Shape: `bench/writer_throughput.clj` patterned exactly on
`test/seon/db/writer_mutation_concurrency_test.clj` (writer runtime via
`seon.db.writer-test-support`, requests through `writer/handle-request`,
`writer.clj:4246-4253`) but with backend `:file` under `tmp/` (the
mutation-concurrency test uses `:memory`; the ceiling is commit-bound so
`:file` is the honest case).

Workload shapes (realistic write shapes from §2):

- **beat**: 1 CAS + 1 assert on a pre-created run entity;
- **turn-tail**: the 12-tx turn script (open, phase advances, attempt
  open/terminal, 2 receipt pairs, close) against per-driver run/turn
  entities;
- **receipt-pair**: `:running` assert then terminal CAS.

Measure, per shape: sustained tx/s over ≥30 s and p50/p95/p99 latency,
at offered concurrency 1 / 16 / 64 / 256 synthetic callers (virtual
threads issuing `handle-request` back-to-back). Expected result: tx/s
flat at the serialized ceiling regardless of concurrency, queue
rejections appearing at depth 64 — confirming §3b(1) and (2). Collect
`executor/evidence` counters (`executor.clj:761-781`) and datahike's
`:datahike/commit-time` trace log (`writer.cljc:225-226`) alongside.

### Probe A′ — the pipelining headroom (datahike-only)

Same file backend, bypass the Seon executor: call `d/transact!` directly
on the connection with K uncompleted calls in flight (K = 1 / 8 / 64),
measuring committed tx/s and per-tx latency. This isolates exactly what
§5.1 would buy (commit amortization across the LocalWriter batch) and
gives the before/after number for the escape decision without changing
any Seon code.

### Probe B — full-path round trip from the pod

Live named probe cluster (own `bin/seon` operator instance and store;
never the default cluster). Through cluster-qualified `eval_cljs`:
launch N `^:async` writers (N = 16 / 64 / 256) each looping
`db/transact!` beat-shaped txs against pre-created run entities;
measure per-call round-trip percentiles and aggregate tx/s from the
basis-t delta over the wall clock. Separately, the **beat-heavy idle
case**: one scheduler loop issuing 1,000 runs' beats at 3 s cadence
(333 tx/s offered) — watch for queue-full rejection values surfacing at
the client, delivery lag on an installed listen interest, and session
response-slot pressure (`uds.cljc:520-529` send statuses).

### Probe C — sharding confirmation

Same writer process, 4 databases (registry `ensure-database` per name),
Probe A's beat workload against all four concurrently: expect ≈4× the
single-database ceiling (mutation `maximum-active` = 4 on ≥7 cores,
`executor.clj:126`), confirming the parallel-lane claim and measuring
cross-database interference (shared commit disks, shared codec pool).

Acceptance mapping: 1k active runs needs a measured ≥400 tx/s on one
database (Probe A′ pipelined, then §5.1 implemented) OR an explicit
sharding layout (Probe C × operator processes); 10k active additionally
requires the beat-cadence decision (§5.3) because idle load alone
approaches the as-built ceiling.

## 7. Open questions routed to owners

- §5.1 (pipelined mutations) touches `seon.db.executor` +
  `seon.db.writer` admission semantics — a spine-adjacent unit, not a
  drive-by; it needs the in-flight request-id cache and an allocation-tx
  carve-out. Queue behind the all-JVM design pass verdict.
- §5.3 (beat batching vs fence semantics) is an owner semantics call;
  record with the claim-native driver unit.
- Delivery fanout at 1k interests (§3b.3) should be measured in Probe B
  before any design motion — the attribute-indexed tables may make it a
  non-issue.
