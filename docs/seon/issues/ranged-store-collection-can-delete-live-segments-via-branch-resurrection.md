---
type: issue
status: open
severity: data-loss
tags: [issue, database, datahike, konserve, gc]
---

# Ranged store collection can delete live segments via branch resurrection

## Problem

Datahike's GC safe point (`gc_guard.cljc`) spares objects a sequence
**writes** while its pointer is unpublished. It does not — and cannot — cover
objects a sequence makes **reachable again**. `versioning/branch!` reads an old
commit record and republishes it as a branch head, so a branch created between
a collection's MARK and its SWEEP resurrects commits the mark judged garbage,
and the sweep then deletes the index nodes that head now names.

Two things make this urgent rather than theoretical for Seon:

- The hole is **created by the cutoff**. With Datahike's default
  `remove-before` of epoch, all ancestry stays marked and resurrection is safe.
  The head-only cutoff Seon needs to reclaim 357 GiB
  (`docs/prds/sci-execution-runtime/research/disk-burn-forensics-2026-08-04.md:195-198`)
  is exactly what lets resurrection reach deleted objects.
- Branching from a commit id is **Seon's routine cluster start**, not an exotic
  operation: `seon.cluster.registry/ensure-cluster!`
  (`src/seon/cluster/registry.clj:200-222`) and `reset-cluster!` (`:224-251`)
  both fork from `:seon.source/commit-id`.

A second, independent instance of the same class: `seon.blob/put!` and
`put-binary!` write a blob (`src/seon/blob.clj:109`, `:148`) whose referencing
datom is transacted **later by the caller**, and neither takes
`gc-guard/writing!`. `collect!` also computes its blob whitelist *before* the
pass (`src/seon/cluster/registry.clj:343`), widening the window further.

## Evidence

Falsified live on a scratch store; reproducer retained at
`docs/prds/sci-execution-runtime/research/scripts/gc-resurrection-race-2026-08-05.clj`
(injects a real `d/branch!` at the `konserve.gc/sweep!` seam that
`gc.cljc:146` calls, so the branch provably lands between mark and sweep):

```text
{:source-commit-datoms-before-gc 400, :swept 23,
 :branch-during-mark-sweep-window :ok,      ; branch! reported success
 :branches #{:db :resurrected},
 :resurrected-readable? 400,                ; healthy IN THIS PROCESS
 :main-head-readable? 400}
:after-reconnect ExceptionInfo / Node not found in storage.
```

The corruption is **masked by the persistent-set node cache** and surfaces only
after reconnect — i.e. a `bin/seon start` succeeds, the cluster works, and a
later boot fails. Control run
(`scripts/gc-head-only-sweep-2026-08-05.clj`): outside the window the API
refuses honestly with `:type :commit-not-found`, so the mark→sweep interleaving
is the only unsafe path.

Source: safe point `reference-code/datahike/src/datahike/gc_guard.cljc:1-41,
85-102`; cutoff `gc.cljc:122-129`; roster read `gc.cljc:136`; sweep
`gc.cljc:146`; parent-walk range `gc.cljc:41-42, 72`; `branch!` read/write/publish
`reference-code/datahike/src/datahike/versioning.cljc:250-251, 268, 287-289`.

## Not a CAS problem

Recording heads at mark and verifying at sweep does not help: a head advance is
already correct by construction (values-then-pointer ordering
`writing.cljc:497-552` plus the safe point), and a branch **creation** is not a
head advance, so there is no prior value to compare. Konserve has no
compare-and-set — `force-branch!`'s `:expected-current-commit` disclaims exactly
this (`versioning.cljc:331-335`).

## Owner

`reference-code/datahike/src/datahike/gc_guard.cljc` (the mechanism),
`seon.cluster.registry` (the one GC call site), `seon.blob` (the unguarded
values-then-pointer writes).

## Acceptance

- `gc-guard`'s atom becomes a two-sided latch: `branch!`/`force-branch!` and the
  sweep are mutually exclusive (one `swap!` each, no check/hold window);
  ordinary commits stay unexcluded under the existing safe point.
- `gc-storage!` refuses with a flat value (never a throw) when a
  reachability-extending sequence is open, and holds the latch across mark AND
  sweep.
- `seon.blob` writes and their referencing transactions form one
  `with-unreferenced-writes` sequence; the blob whitelist is derived inside the
  guarded mark.
- A recurring test in `bin/test` collects head-only while a writer commits, a
  branch is forked from an old commit, and a blob is written — and after
  RECONNECT every head, every branch (including the forked one), and every blob
  is intact; a second pass sweeps zero. The negative control asserts the
  uninterleaved run actually sweeps a non-zero count.
- The `gc_guard` docstring stops claiming the safe point covers everything
  written before it; it covers written objects, not resurrected ones.

## Related

Design, full source analysis, and the falsifier specification:
[gc-correctness-cas-opus-2026-08-05.md](../../prds/sci-execution-runtime/research/gc-correctness-cas-opus-2026-08-05.md).
The cutoff itself is owned by
[storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md](storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md),
whose 2026-08-02 disposition ("no cutoff should land by guess") this answers.
