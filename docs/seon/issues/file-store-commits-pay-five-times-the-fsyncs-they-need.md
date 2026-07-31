---
type: issue
status: open
severity: friction
tags: [issue, datahike, architecture, evidence]
---

# A file-store commit writes 18 objects where 1 would do

## Problem

One small Datahike commit on Seon's file store costs **~123 ms**, and ~99 % of
that is konserve fsyncing one object at a time: a commit rewrites the
root-to-leaf path of every index (times the temporal indices), plus the
schema-meta record, the commit record and the branch head — **18 to 24 objects
at ~5–8 ms of APFS metadata fsync each**. The konserve filestore is not
`multi-key-capable?`, so Datahike takes the sequential branch and pays every
fsync serially (`datahike/writing.cljc:528-552`,
`konserve/impl/defaults.cljc:104-117`).

This is not a regression — every serial file-store measurement in this
repository agrees (45 ms in 2026-07, 125 ms today at 161k datoms), and the
"thousands of tx/s" numbers are the writer's concurrent coalescing, which still
works (1,477 tx/s at 1,024 callers, measured today). The defect is that Seon's
store is created **without any of the three write-amplification options the
maintained Datahike fork already ships**, and without the writer's one batching
dial.

## Evidence

`docs/prds/sci-execution-runtime/research/transact-throughput-regression-2026-07-31.md`,
probes at `research/scripts/transact-throughput-2026-07-31/`. Freshly built
~21,000-datom stores, 5-datom commits, one serial caller, n=25:

| configuration | objects | median | gain |
|---|---:|---:|---:|
| today's default | 18 | 99.9 ms | 1× |
| `:fuse-index-roots? true` + `:index-config {:diff-buf-size 256}` | 1 | **19.0 ms** | **5.3×** |

Separately, `commit-wait-time 5` on the writer costs 15 % of serial latency
(45.2 → 51.9 ms) and takes 64 concurrent callers from 247 to **564 tx/s**.

Neither change trades any durability: every object is still fsynced, the branch
head still lands last, and the crash model (nothing re-executes; facts survive)
is untouched.

## Owner

`seon.cluster.store/datahike-configuration`
(`src/seon/cluster/store.clj:155-174`) — it hard-codes a three-key `:store` map
and an empty `{:backend :self}` writer map, so neither the index options nor
`commit-wait-time` can be expressed today.

## Acceptance criteria

- `datahike-configuration` emits `:fuse-index-roots? true`,
  `:index-config {:diff-buf-size 256}` and a writer `:commit-wait-time`, with
  the wait exposed as a config fact rather than a literal.
- A store created with those options shows a serial small-commit median at or
  below **25 ms** at ~20k datoms, reproduced with
  `research/scripts/transact-throughput-2026-07-31/options-admissible.clj`.
- `bin/test` green, and one live proof on a freshly forked cluster that reads
  back a fact committed through `store/transact!` — the representation changed,
  so a fixture-only proof is not sufficient.

## Constraints a fix must respect

- **`:commit-graph? false` is inadmissible.** It is the biggest single win
  (9.9×) and it gives up branching from a bare commit id — which is exactly how
  a new cluster forks the published `current-src` commit.
- **`:diff-buf-size` and `:fuse-index-roots?` are fixed at database creation**
  and adopted from the store on reconnect. Existing stores cannot adopt them;
  landing this means creating the store with them (republish + refork via
  `bin/seon init`), not editing config against live data.
- `:diff-buf-size` needs persistent-sorted-set ≥ 0.4.137 for correct concurrent
  reads. The pin is already 0.4.137 (`deps.edn:196`).
- **`:sync-blob? false` (20×) must not become the default.** It moves the crash
  window into the page cache and can lose commits out of order, leaving a branch
  head pointing at nodes that were never written — the precise failure
  `writing.cljc:502-511` orders its writes to prevent.
