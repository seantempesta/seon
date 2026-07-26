---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, database]
---

# Give agent birth a surviving owner after the pod's cluster apply lost it

## Problem

Pod-cut group 4 (`9ebd05588`) deleted `seon.agent`, including
`ensure-initial-agent!`. The pod's cluster-apply path was its only caller,
so a fresh cluster apply/reset no longer creates the root agent or the
initial agent. The apply result now reports `root-created? false`
unconditionally.

## Evidence

- `src/seon/client.cljs` cluster-apply block (the cut site, commented).
- `git show 9ebd05588 --stat` — the deletion.
- The falsifier fixture had to allocate its own sender agent because no
  root-birth path ran on the current cluster generation.

## Owner

The cluster JVM (step 6's merge). Agent birth is committed facts —
identity, home namespace rows, context seed-copy — through the one
writer; the surviving owner is the JVM boot/apply path, not a restored
pod function. `seon.agent.core`/`seon.agent.ctx` (surviving `.cljc`)
hold the birth transaction builders.

## Acceptance

- `bin/seon cluster reset default` produces a cluster whose root agent
  exists with its seeded context blocks, with zero pod involvement.
- The apply result's `root-created?` reports truth again.
- The reset-boundary live proof (step 6 falsifier) covers it.
