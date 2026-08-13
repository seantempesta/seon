---
type: issue
status: open
severity: friction
tags: [issue, database, wave/store-perf]
---

# A file-store commit writes 18 objects where 1 would do

## Problem

The title and original problem describe the pre-`c5c55809d` /
`393198915` path, not a newly created Seon store. Current
`seon.cluster.store/datahike-configuration` enables fused index roots and a
256-entry diff buffer (`src/seon/cluster/store.clj:156-179`), and the selected
Datahike/Konserve revisions execute an ordered multi-key file-store batch
(`reference-code/datahike/src/datahike/writing.cljc:497-528`;
`reference-code/konserve/src/konserve/filestore.clj:324-328`).

Two unsettled facts remain:

- Konserve's file batch is an **ordered durable-prefix operation, not one
  atomic write or one fsync**. It stages, blob-forces, renames, and
  directory-forces every pair in sequence
  (`reference-code/konserve/src/konserve/filestore.clj:121-153`). It preserves
  Datahike's children → schema metadata → immutable commit → mutable branch-head
  order, but does not itself coalesce local filesystem barriers.
- Seon still leaves the writer's `:commit-wait-time` at Datahike's zero default.
  The wait happens after one committed batch and before the commit loop takes
  the next item (`reference-code/datahike/src/datahike/writer.cljc:201-268`),
  allowing queued logical transactions to form a larger following commit at a
  direct serial-latency cost.

## Evidence

The retained reproduction is
`docs/prds/sci-execution-runtime/research/scripts/store-options-before-after-2026-08-02.clj`.
Its 2026-08-02 run used fresh private stores under `tmp/`, 3,623 current datoms,
two warmups, and seven measured single-row replacements:

| configuration | blob forces / commit | median |
|---|---:|---:|
| legacy roots + no diff buffer, forced sequential | 14 | 74.0 ms |
| legacy roots + no diff buffer, ordered file batch | 14 | 117.0 ms |
| fused roots only, ordered file batch | 10 | 83.8 ms |
| current fused roots + diff buffer, ordered file batch | **2** | **18.1 ms** |
| current path + `commit-wait-time 5`, serial | 2 | 23.6 ms |

The ordered file batch alone was slower than the forced sequential fallback on
this APFS run; it is a crash-order/capability result, not the source of the
latency win. Fusion plus diff buffering cut blob forces 14 → 2 and latency
74.0 → 18.1 ms. With three bursts of 24 logical transactions,
`commit-wait-time 5` changed six physical commits to three and median burst
time 36.8 → 27.0 ms (652 → 890 logical tx/s), while adding 5.5 ms to the
serial median. These are local-workstation numbers, not a universal tuning
constant.

Neither change trades any durability: every object is still fsynced, the branch
head still lands last, and the crash model (nothing re-executes; facts survive)
is untouched.

## Owner

`seon.cluster.store/datahike-configuration` for the remaining writer setting;
the two store-fixed index settings are already adopted. A database-backed
config fact cannot simply be read before the database connection that owns
that fact exists: writer configuration is captured by `d/connect`, so its
acquisition/reconnect boundary must be explicit rather than presented as a
live dial.

## Acceptance criteria

- The retained script continues to prove the fused/diff path's blob-force and
  latency advantage against the legacy shapes.
- If a nonzero `:commit-wait-time` lands, its database-fact acquisition and
  reconnect boundary are stated and both serial latency and concurrent
  coalescing are measured; no workstation-specific literal is silently made
  policy.
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

## Current disposition 2026-08-02

**Fusion and diff buffering are proven and already live. Ordered multi-key
writes are live but are a safety/order capability, not a proven filestore
speedup.** `:commit-graph? false` and `:sync-blob? false` remain inadmissible.
The only unadopted performance candidate in this note is writer waiting; its
coalescing benefit and serial cost are proven, while its Seon config acquisition
mechanism remains design work.

## Re-grounded evidence — 2026-08-13

**STILL-REAL at `06e654c76`, only in the narrowed writer-wait sense already
recorded above.** `src/seon/cluster/store.clj:148-165` still constructs the
self writer as `{:backend :self}` with fused index roots and a 256-entry diff
buffer, but supplies no `:commit-wait-time`. The retained
`docs/prds/sci-execution-runtime/research/scripts/store-options-before-after-2026-08-02.clj`
still contains the comparative serial/burst probe.

The title's 18-object/five-fsync premise is stale and must not be scheduled as
the current defect. What remains open is the measured coalescing tradeoff and
its explicit acquisition/reconnect design, not adoption of the already-live
fusion/diff settings.
