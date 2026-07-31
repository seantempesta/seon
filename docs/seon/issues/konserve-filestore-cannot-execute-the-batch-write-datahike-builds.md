---
type: issue
status: open
severity: friction
tags: [issue, dependency, database, architecture]
---

# Make the filestore able to execute the batch write Datahike builds

## Problem

Our Datahike fork carefully assembles a single ordered batch for every
commit — children before parents, branch head last — and hands it to
`k/multi-assoc`. On Seon's backend that batch **cannot execute**. The
konserve filestore implements neither backing protocol that batching
requires, so the capability probe returns false and every commit silently
takes the per-key fallback.

This is a feature Seon authored upstream and cannot run.

## Evidence

`reference-code/datahike/src/datahike/writing.cljc:517-529` builds the
ordered batch and calls `(k/multi-assoc store writes metas {:sync? sync?})`;
the fallback path immediately below (`:530-541`) writes key by key.

`reference-code/konserve/src/konserve/impl/defaults.cljc:632-635` gates the
capability on `(satisfies? PMultiWriteBackingStore backing)`.
`reference-code/konserve/src/konserve/filestore.clj` implements **neither**
`PMultiWriteBackingStore` nor `PMultiReadBackingStore` — verified by grep;
the only implementor in the tree is `konserve/indexeddb.cljs:420`.

The same dead path is taken by `versioning.cljc:402-403` and by GC's
`multi-dissoc` at `online_gc.cljc:120,124`.

What Datahike actually needs from the batch is **ordering**, not atomicity
(`konserve/core.cljc:443-460`), and ordering is trivially expressible over a
directory — so the two protocols are implementable for the filestore.

Full analysis:
`docs/prds/sci-execution-runtime/research/upstream-delta-sweep-2026-07-31.md`.

## Owner

`reference-code/konserve/src/konserve/filestore.clj` — the backing
implementation. Related but distinct from
`file-store-commits-pay-five-times-the-fsyncs-they-need.md`, which owns the
Datahike-side create-time options; this note owns the missing backing
capability underneath them.

Taken 2026-07-31 by the konserve filestore batch lane. Design, crash proof,
and measurements are recorded in
`docs/prds/sci-execution-runtime/research/konserve-multi-assoc-notes-2026-07-31.md`.

Local implementation commit: `reference-code/konserve` `737697d`. The full
fork suite and real-Datahike four-stage forced-kill proof are green. The issue
stays open through fork publication and because the unfused 18-object exact
probe regressed from `93.732 ms` to `156.093 ms`; the combined fusion path
improved from `20.019 ms` to `18.012 ms` and clears the `<=25 ms` gate.

## Acceptance

- `(konserve.utils/multi-key-capable? store)` is true for a `:file` store.
- A small commit against a file-backed store takes the `multi-assoc` branch,
  proven by a probe that observes the batch rather than the per-key writes.
- The ordering guarantee is preserved and tested: children land before
  parents and the branch head lands last, including under a mid-batch
  failure, so the crash model (`writing.cljc:502-511`) is untouched.
- Measured against the same harness as the fsync issue, so the two changes'
  effects can be told apart.
