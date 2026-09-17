---
type: issue
status: open
severity: blocker
tags: [operator, reset, store, lock, class/absence-as-health]
created: 2026-09-17
---

# Reset's refork refuses "held elsewhere" without naming the holder, after its own republish

## Problem

`bin/seon reset --force` on 2026-09-17 04:40Z ran preflight (312 ms), down
(962 ms), destroy (9.9 s), republish (145.3 s), then **refork refused**:

```
the store at /Users/sean/src/seon/data/store is held by another live process
:seon.cluster.store/refused :seon.cluster.store/held-elsewhere
```

Log: `data/operator/operations/reset-refork-60487.log`. The refusal names
the directory and the lock file and NO holder: no pid, no liveness, no
process record. `lsof data/store.lock` immediately after the reset returned
nothing, and `bin/seon status` reported 0/0 clusters. The only candidate
holder is the reset's own republish JVM releasing its `flock` after its
phase reported complete — the refork opened the store before the previous
phase's process had exited.

The pipeline then stopped with no cluster: the reset's promise (ends in a
started, adopted cluster) was not kept, and the orchestrator finished it by
hand (`init default --force; start; init --dev default`).

## Two defects

1. **The refusal is evidence-incomplete.** `held-elsewhere` must name the
   holder: the pid from the lock (or the process record), whether it is
   alive, and which operator phase owns it. A refusal that cannot name what
   holds the lock is the typed unknown and must say so.
2. **A phase boundary is not a process boundary.** The reset advances to
   refork when republish *reports* complete, not when the republish JVM has
   *exited and released the store*. The phase's completion must be its
   child's exit (bounded, loud), or the next phase must wait on the lock with
   the holder named — the reset-is-total lane's bounded lock wait exists for
   the preflight; it did not cover the pipeline's own children.

## Regression

A reset on a scratch root whose republish child is made to exit slowly
(a planted delay after publication) must still end in a started adopted
cluster, and a genuine foreign holder must be refused naming its pid and
liveness.
