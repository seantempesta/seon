---
type: research
lane: health-substrate-research
date: 2026-09-28
status: research only; no src/test/resources edits
evidence: JVM-mode MCP probes on default pid 85484 (started 2026-09-28T17:34:11Z), namespace `health-probe`,
  disposable branches `:health-substrate-70bf486740be*` forked from `:cluster-default`, all retired; datahike
  reference-code at 591c825d6a47
---

# Health recording substrate: what to write where

**Headline.** Armed functions write **nothing** to the database per call. Timing lives in in-memory JDK counters.
Only three per-evaluation summaries reach datoms, and they ride the evaluation's existing settlement commit as
ordinary history datoms, not noHistory ones.

In this store the cost is the **commit**, not the datom. A commit costs at least 1.15 MB retained and about
25 ms, whatever it carries. Every commit stays reachable forever under the plain collection, so noHistory
does not stop growth. It only saves the temporal-index nodes (about 55% of a small commit's index bytes) and keeps
the datom count constant.

So the substrate is: in-memory counters on the hot path, plus facts that **ride commits that already happen**. Riding
100 noHistory upserts on an existing commit measured about 0 extra bytes and 2-3 ms. Nothing gets a commit of its
own.

## Confirmed today

What is **in memory only** (never stored):

- **Lifetime cells.** There is one per installed callable, keyed `[sym digest scope context]`. Each holds calls,
  total ns, max ns and throws in LongAdder and LongAccumulator counters (`src/seon/profile.clj:69-85`), bumped by
  `timed` (`:154-206`).
  - Measured now: 2,564 cells, 1,635 observed, 1,631 of them with a digest.
  - Read by `runtime_status` through `profile/top` and `summary` (`:232`, `:360`).
- **The per-evaluation world sink.** It maps `[sym digest]` to an `AtomicLongArray [calls nanos]`
  (`profile.clj:127-148`, bound per evaluation in `seon.batch`).
- **Test phase timings.** `:seon.test/timings` holds acquire, resolve, run and release ms per member
  (`src/seon/test.clj:2013-2016`). They sit only in the returned run-result value (`seon.test.edn:329-347`). The
  member entity has **no duration attribute** (`resources/seon/schemas/seon.test.member.edn:1-40`).

What is **stored**:

- **The evaluation settle transaction.** `code-evidence` (`src/seon/turn.clj:5254`) and `call-profile-facts`
  (`:5272`) write three attributes as ordinary **history** datoms (`resources/seon/schemas/seon.cluster.eval.edn:10-19`):
  - `:seon.cluster.eval/executed #{[sym digest]}`
  - `:seon.cluster.eval/call-count`
  - `:seon.cluster.eval/repeated-calls #{[sym calls ms]}`, for calls over 100 (`src/seon/batch.clj:95-112`)
- **Test evidence.** Each member stores pass, fail and error counts, `began?`/`ended?`, failures, reach digest and
  tx refs (`seon.test.member.edn`). After the store-write fix these are written in one commit per batch
  (`docs/research/agent-platform/store-write-fixes-2026-09-28.md`).
- **Faults.** `seon.error` plus occurrences. `:seon.error.occurrence/count` and `/last-at` are **noHistory**
  (`seon.error.occurrence.edn:3,5`). They are linked to a function by `:seon.instrument/fn`.

`:db/noHistory` in Seon schemas comes only from `:seon.db/no-history? true` (`src/seon/schema/datahike.clj:193`).
It is declared in 8 schema files, and none of them is profile or test timing:

- AI model gauges: `last-latency-ms`, `last-tokens-per-second`, `last-used-at`. Their writer is
  `src/seon/ai.clj:1017`, the only "noHistory gauge upsert" precedent.
- Fault occurrence count and last-at.
- Evaluation source, output, comment and diagnostics text (task `compaction-erases-no-history-form-text`).
- A few effect, turn and blob attributes.

The owner's belief ("every function is instrumented and writing to no-history datoms, and testing too") is
**refuted** as stated:

- Instrumented: yes.
- Written per call: no.
- Stored as noHistory: no.
- Per-test durations: measured but not stored at all.

## Datahike noHistory (with file:line)

- **What it gates: the temporal (history) indexes only.** `keep-history? = (and (-keep-history? db) (not
  (no-history? db a)))`:
  - On add: `reference-code/datahike/src/datahike/db/transaction.cljc:448`.
  - On upsert: `:547` (with `:db/txInstant` excluded).
  - The current EAVT, AEVT and AVET indexes are always updated (`:460-462`, `:471-474`, `:563-567`).
  - A retraction of a history attribute inserts two temporal datoms per index (`:478-484`). A noHistory one inserts
    none.
- **Reads.** A noHistory attribute's current datoms are merged into history views (`db/utils.cljc:235-267`), so
  `history` and `as-of` show only its current value.
- **The commit is unchanged.** `writing/commit!` (`writing.cljc:436-560`) flushes all six indexes (`:73-84`),
  writing only dirty nodes. It then writes the commit record, and the branch head last.
- **In Seon, the commit record carries the index roots inline.** The store has `:fuse-index-roots? true`
  (`src/seon/cluster/store.clj:195`, `writing.cljc:142-160`), so every commit record is about 300 KB (measured
  below).
- **Retention.** The plain collection's `remove-before` is the epoch (`src/seon/cluster/registry.clj:524-531`). So
  `gc.cljc` `reachable-in-branch` (`:22-60`) marks every commit of every roster branch, together with its fused roots
  and index nodes. **An overwritten noHistory leaf is still reachable from the older commit's root, so it is
  retained.** noHistory keeps the temporal index from growing, but it does not stop per-commit disk growth.
  Only a retention cutoff (`retention-cutoff`) or fewer commits reduces that.

## Measurements (exact forms, numbers)

**Method.** Raw `datahike.api/transact` on a branch opened by `store/open-branch!` and forked with
`registry/branch!` from `:cluster-default`. The branch had three probe attributes:

- `:probe.health/key`: string, unique identity
- `:probe.health/nh`: long, `:db/noHistory true`
- `:probe.health/h`: long, with history

100 gauge entities were pre-created. Each case ran on its own sub-branch forked from that commit.

"New bytes" are index-node files first reached by the case. They were marked with `idx/-mark` over all six indexes
against a shared visited set seeded from the base (2,270 nodes); this is the method of
`tmp/astra-store-fixes-20260928/batch-recording-probe.clj`. "Record" is the commit record's own file
(`defaults/key->store-key cid`). Times are the synchronous transact on the branch, without the Seon writer
(`seon.db/transact!` adds validation on top).

| Case | Commits | Datoms | Index bytes/commit | Record/commit | ms/commit |
|---|---|---|---|---|---|
| (a) new entities, noHistory: 1 / 100 / 1000 | 1 | 2 / 101 / 1001 | 846 K / 850 K / 884 K | 304 K | 28 / 29 / 34 |
| (b) new entities, history: 1 / 100 / 1000 | 1 | 2 / 101 / 1001 | 846 K / 2.01 M / 2.08 M | 304 K | 28 / 29 / 39 |
| (c) upsert 1 entity x10, noHistory | 10 | 20 | 847 K flat | 304 K | 24-29 |
| (c) upsert 1 entity x10, history | 10 | 20 | 1.92-2.01 M | 304 K | 25-29 |
| (c) upsert 100 entities x10, noHistory | 10 | 1010 | 847 K flat | 304 K | 25-28 |
| (c) upsert 100 entities x10, history | 10 | 1010 | 1.25-2.04 M | 304 K | 25-33 |
| settle-like: 8 new history datoms alone | 5 | 45 | 2.007 M | 304 K | 26-32 |
| (piggyback) same + 100 noHistory upserts | 5 | 545 | 2.007 M (+240 B/commit) | 304 K | 29-34 (+~3) |
| (piggyback) same + 100 history upserts | 5 | 545 | 1.29-2.04 M (leaf-split noise) | 304 K | 31-35 (+~4) |
| (d) same + tx-meta `{:probe.health/h k :probe.health/nh k}` | 5 | 55 | 2.01-2.07 M | 304 K | 28 (39 first) |
| separate flush commit after each settle (100 noHistory) | 10 | 550 | +847 K per flush | +304 K per flush | +26 per flush |

What the table shows:

- **Per-commit floor.** Any commit costs about 1.15 MB retained (0.85 MB of nodes plus the 0.30 MB fused-root
  record) and about 25 ms, whatever it holds. This matches the audit's production median of 1.47-1.57 MB per commit
  (`docs/research/agent-platform/store-write-audit-2026-09-28.md` §4).
- **noHistory vs history in a standalone commit.** 0.85 MB against 1.9-2.0 MB of index bytes: noHistory saves the
  temporal-leaf rewrites. Ten gauge refreshes still retain 11.5 MB with noHistory and 22.5 MB with history. Growth
  is linear in the number of commits either way.
- **Riding an existing commit.** It adds about 0 bytes and 2-4 ms. The marginal cost of health data is its datoms
  in leaves that are rewritten anyway.
- **tx-meta** as a carrier costs the same as riding. But each transaction entity is new, so it accumulates current
  datoms forever. It fits "this commit's provenance", not a gauge.

**Snapshot as a blob, JVM mode.** `(profile/cells)` → `observation` for 1,635 observed cells takes 5.9 ms. `pr-str`
takes 4.9 ms and gives 351,606 bytes of EDN (about 88k tokens). `seon.blob/put!` takes 9.8 ms. No commit is made, but
an unreferenced blob is swept by the next collection (`registry.clj:548`, `referenced-blobs`), so a durable
snapshot needs one referencing fact.

## Options with costs

| Option | Commits added | Retained bytes | Durability | Queryable per entity | Fit |
|---|---|---|---|---|---|
| 1. Per-event noHistory writes (owner's literal proposal) | 1 per event | 1.15 MB per event (the audit's 94% test-member pattern again) | yes | yes | **No: this is the thrash** |
| 2. In-memory cells only (today) | 0 | 0 | lost on restart | via `runtime_status` / REPL only, not Datalog | yes for the hot path |
| 3. Periodic coalescing flush of changed aggregates into noHistory rows | 1 per interval, only when something changed | 1.15 MB per flush: 1.6 GB/day at 1/min, 28 MB/day at 1/hour | aggregates | yes | Acceptable only at a long interval; still a commit of its own |
| 4. **Ride existing settlement commits** (evaluation settle; the test batch commit; the fault occurrence write) | 0 | about 0 extra (datoms only) | yes, with the owner's own write | yes | **Best**; already the pattern for `executed`/`call-count` |
| 5. Blob snapshot, referenced by a process/shutdown fact | 0 (or 1 at shutdown) | 0.35 MB per snapshot | whole-JVM aggregates | only by reading the blob | For restart survival of lifetime cells, if wanted |
| 6. seon.cache (Caffeine) for the derived health view | 0 | 0 | derived, rebuilt on demand | yes (a function over db + cells) | The on-demand pull |

## Recommendation

This is the smallest design. It adds no new process or schedule, and no commit of its own.

1. **Hot path unchanged.** Armed calls only bump counters (`profile/timed`). Nothing on the call path touches the
   database. This already holds.
2. **Record only at owners' existing commits, as noHistory aggregates or as facts that are already history:**
   - **Evaluation settle.** Extend the facts that already ride it (`turn.clj:5254-5280`). Per `[sym digest]` this
     batch executed, upsert one `:seon.profile/observation` row keyed by `seon.id/id [sym digest]`, holding noHistory
     calls, total/self ns, max and p99. This is the design in `profiling-and-related-data-2026-09-28.md` §2.4, with
     one change: **declare the members noHistory**, so repeated settles keep one current datom per member and add no
     temporal datoms.
     - Cost: the rows ride the commit (about 60 definitions per settle, under 10 ms by that doc's `d/with` numbers).
     - Only definitions whose counters changed in this batch are written, never all cells.
   - **Test batch commit.** Store each member's `run-ms` (already measured, `test.clj:2015`) on the member. Zero
     commits: the batch already commits once (store-write-fixes). A test's latest duration is then a query.
   - **Faults.** Already a noHistory count and last-at on the occurrence, linked by `:seon.instrument/fn`. Slow
     functions become faults through the same path (profiling doc §2.5).
3. **JVM-host calls outside any evaluation** (Flow procs, MCP JVM mode, boot) stay in the lifetime cells. They are
   observed on demand. They are not persisted unless the owner wants restart survival, in which case use option 5:
   one blob at orderly stop, referenced from the process record.
4. **The health view is on demand.** It is a function over the db value (faults, latest test members, contract and
   analysis facts, stored observations) plus live cells for the current JVM. It is rendered per entity (one hop, the
   same as related data), cached as a `seon.cache` kind keyed by what it read, never materialized as its own facts.
5. **No dedicated flush commits.** If one is ever needed (e.g. a long-idle JVM with only host calls), it is
   coalesced, sent only on change, and made at most once per long interval. It is priced at 1.15 MB each.

Identity (Q4):

- **Function**: `[:seon.fn/sym sym]` plus the definition digest. The cell and sink key is already `[sym digest]`
  (`profile.clj:124`). A new digest starts a new row; the old row keeps what that definition did.
- **Schema**: `:seon.schema/key`.
- **Test**: `:seon.test/sym`. Its latest member comes from `:seon.test.member/symbol` (AVET-indexed).
- **Namespace**: `:seon.ns/*` identity.
- **Agent**: agent id.
- **Proc/graph**: the roster id.
- **Fault**: `:seon.error/signature`.
- **Store/cache**: the store dir and cache kind (runtime-only, observed, not stored).
- **Queries.** Datahike already caches the parse by query form (`reference-code/datahike/src/datahike/query.cljc:3122-3133`,
  `memoized-parse-query`, which `seon.db/q` calls at `src/seon/db.clj:2773`).
  - The stable identity is the **quoted query form** (a constant at each call site; the args vary).
  - Keep per-form `[calls nanos max]` counters in memory, keyed by the form, and digest only when stored or shown
    (`seon.id/id form`).
  - Hashing per call would put print+SHA on a path called 46k times.
  - Attributing a query to its caller needs the caller's `[sym digest]`. Today the world sink is flat (no caller
    edge), but self time (profiling doc §2.2) on the calling definition already names which function's queries are
    expensive.

## Open questions for the owner

1. **Restart survival.** Must profile aggregates survive a JVM restart? If yes, use one blob at orderly stop
   (0.35 MB), or rely on settle rows, which already survive for evaluated code. Host-only lifetime cells are lost
   today.
2. **Retention.** noHistory does not bound disk while every commit is retained (the plain collection keeps all
   history). Should health rows count against a retention cutoff, or is "no extra commits" enough?
3. **The fused-root commit record.** It is about 300 KB per commit, 26% of the per-commit floor, and applies to
   every commit (not just health). It is a store-owner tuning question (`:fuse-index-roots?` plus
   `:branching-factor 4096`). File it as a task?
4. **Per-query counters.** Keyed by the query form: is a bounded map acceptable? Dynamically built forms could be
   unbounded, so it would need its own bound or a `seon.cache` kind. Or is per-caller self time enough?
5. **The history on the settle facts.** `repeated-calls` and `call-count` on the settle are history datoms on a new
   entity per evaluation, so they grow with evaluations, which is fine. Should the per-definition aggregate rows
   (upserted) be noHistory, as recommended, so they lose their past values (history then shows only the current
   value)?
