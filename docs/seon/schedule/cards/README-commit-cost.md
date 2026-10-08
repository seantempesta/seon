---
title: "Commit cost cards (CC)"
type: reference
status: active
created: 2026-10-09
---

# Commit cost cards (CC)

What exists: every eval request writes its recording as one durable Datahike commit (`seon.turn.batch/settle` →
`seon.db/transact-call`); the test step adds its own commits; each commit is `datahike.writing/commit!` writing the
dirty index nodes (new immutable keys, deferred sync), the fused-root commit record (immutable, deferred) and the same
full record again at the branch key (mutable, so konserve's `:barriered` plan: data sync, full directory barrier,
rename, full barrier). The approach, in the order of the research
([commit-cost-astra](../../../research/agent-platform/2026-10-09/commit-cost/commit-cost-astra.md) §6): (1) delete Seon
commits that need not exist, (2) shrink the head so a commit encodes and writes its root record once, (3) share the
two barriers across branch writers that are ready together. Nothing here weakens the contract: the recording
transaction, and every commit with consequences, is durable before its answer.

## Measured parents (2026-10-09)

- Raw durable commit on default's store config: empty 12-13 ms, 17 datoms 19-25 ms; the same `d/with` 0.12 ms; one
  branch-head konserve write 8-10 ms; 20 new immutable keys 5.6 ms total (lane settle-cost). On default a small commit
  writes 30-41 index-node files plus an ~88 KB fused root, twice (record and head), with two full barriers.
- Throwaway store, insert-only, 128/32 with history: 17 assertions 12.0 ms p50, 4 new files; no-sync diagnostic arm
  2.5 ms. One append + `F_FULLFSYNC` on this machine 3.75-4.07 ms; the two-barrier floor is ~8 ms (astra §3.1-3.2).
- Independent branches do not share barriers: 8 concurrent branch commits 30.4 ms p50 (8 commits), 32 → 156 ms (32
  commits), against one connection's 22.4 / 24.5 ms (2 commits) (astra §3.6).

## Commits per eval request today (agent `commit-cards`, default PID 64926, 2026-10-09)

Counted as head advances of every branch (`datahike.versioning/branch-as-db`, then
`datahike.versioning/parent-commit-ids` back to the previous head), beside the reply's `:seon.cost/transactions`:

| Eval request | Durable commits | Reply `:transactions` | What each commit is |
|---|---:|---:|---|
| `:ping` (SCI, warm) | 1 | 1 | recording (`seon.db/absent-call`) |
| `defn`, no reaching test | 1 | 1 | recording |
| `deftest`, or `defn` with one reaching test re-executed | 3 | 4 | recording (263 datoms on the `defn`, 169 of them `:seon.profile.measurement/*`); test admission (`seon.db/transact!`, 18 datoms); member completion (`seon.test.runner/record-tx`, 11 datoms) |
| root, JVM mode | 1 | 0 | recording on `:cluster-default` |

With task proofs held, the step runs the proofs request and the reaching request separately, each admitting and
recording (EN-6: "a test request commits twice"), so a `defn` with proofs and reaching tests is up to 5 commits.

Which rows remove which commit:

| Commit | Kept or removed by |
|---|---|
| recording | kept: the one durable recording transaction. S5-02 (stored measurements deleted) and TX-8 shrink it; TX-0/TX-4 add 2 datoms |
| test admission, nothing executes | CO-2 (answered from its record: no admission, 0 commits) |
| test admission, members execute | CC-2 (rides the recording transaction), owner question CC-Q1 |
| member completion | kept (EN-6); one per request, never per member |
| proofs request + reaching request | owner question CC-Q2 (one request: one admission, one completion) |
| home/cluster administration | TX-1, TX-2, TX-3, TX-5, TX-6, TX-7 (not per eval request) |

## Cards, by measured saving per commit

| Card | Step | Saves per commit (expected from the work) | Owner | Gitlink |
|---|---|---|---|---|
| [CC-0](CC-0.md) | proof | none: the measured-cost test every card uses | `seon.cluster.store` test | no |
| [CC-1](CC-1.md) | proof | none: the reply counts the commits the request made | `seon.profile/sink-counts` and its callers | no |
| [CC-2](CC-2.md) | 1 | one whole commit per eval request whose tests execute: 19-25 ms and two barriers | `seon.turn` test step, `seon.test/admit-run` | no |
| [CC-3](CC-3.md) | 2 | none (readers first) | Datahike fork readers of the branch key | yes |
| [CC-4](CC-4.md) | 2 | one encode + write of the fused record: 2-8 ms CPU and ~88 KB per commit | `datahike.writing/commit!` | yes |
| [CC-5](CC-5.md) | 2 | measurement: what unfused roots save on a recording-shaped commit | throwaway store probe | no |
| [CC-6](CC-6.md) | 3 | none: the barrier trace test | konserve fork `konserve.impl.defaults/update-blob` tests | yes (test only) |
| [CC-7](CC-7.md) | 3 | with N branch writers ready together, ~8·(1−1/N) ms barrier time per commit; one writer unchanged | konserve fork `konserve.impl.defaults/update-blob` (`:barriered`) | yes |
| [CC-8](CC-8.md) | 3 | proof: Datahike branches share the group | Datahike fork writer integration test | yes (test only) |

Order: CC-0 and CC-1 first (both proofs); CC-2 after CO-2 (same owner path, `seon.test`); CC-3 → CC-4; CC-6 → CC-7 →
CC-8. CC-3/CC-4 and CC-6/CC-7 touch different forks and run in parallel. Root batches the Datahike and konserve
gitlink bumps with one restart.

## Owner questions

- **CC-Q1.** OD-Q5 keeps writer admission. Does admission riding the recording transaction (still a
  `[:db.fn/call seon.test/admit-run …]` checked at the writer) satisfy it, or must admission be its own commit?
- **CC-Q2.** May the test step's proofs and reaching tests be one `seon.test/run` request (one admission, one
  completion), with the proofs ordered first inside its bound, instead of two requests?
- **CC-Q3.** The datom write-ahead log (below): when to open its design.

## Step 4, not carded: a datom write-ahead log

Placeholder for the owner's decision. After steps 1-3 the floor is still two barriers per group plus node and record
writes (~10 ms serial for one writer). The path to the measured ~4-6 ms serial floor (one append + one full flush) is
to persist each commit's resolved datom delta once in a durable log in the Datahike fork, acknowledge after that log's
flush, and checkpoint indexes in the background, keeping commit identity, branch lineage, history and GC. It is a
multi-day fork design (astra §6 option 3) and is not carded until the owner selects it.
