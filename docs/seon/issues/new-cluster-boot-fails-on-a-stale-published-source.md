---
type: issue
status: open
severity: blocker
tags: [issue, cluster, boot, operator, database]
---

# Refuse a cluster fork whose source lacks the rows population will name

## Problem

`bin/seon start <new-cluster>` dies before readiness on a checkout whose
published `current-src` predates the `:getting-started` instruction row.
Cluster population names that row as a lookup ref, Datahike refuses the
transaction, and the operator prints a 40-line Datahike writer stack trace
plus `The cluster population transaction was refused.`

Worse, the failed boot has already created the branch. Every subsequent
`bin/seon start` reuses it and fails identically — including after
`bin/seon init` republishes `current-src` with the missing row, because a
cluster snapshots the published commit only when its branch is ABSENT. The
only escape is `bin/seon init <cluster> --force`, which nothing in the error
output names.

## Evidence

Observed 2026-07-31 on `ef8cc6f77` while booting a scratch cluster:

```text
:error datahike.db.utils Nothing found for entity id
  [:seon.cluster.instruction/id :getting-started]
Execution error at seon.cluster/refused! (cluster.clj:83).
The cluster population transaction was refused.
```

Sequence: `bin/seon start visual-qa` → refused; `bin/seon init` (republished
`current-src`, 46 s) → `bin/seon start visual-qa` → refused identically;
`bin/seon init visual-qa --force` → reforked → `bin/seon start visual-qa`
→ ready.

`src/seon/cluster.clj:829-848` builds `:seon.cluster/instructions` from
`instruction/instruction-ids` as lookup refs; `populate-source!`
(`src/seon/cluster.clj:410-431`) is the only writer of those rows and runs on
`current-src`, not on the cluster branch.

## Owner

`seon.cluster` boot — the fork/population seam.

## Acceptance

Booting a cluster against a `current-src` that cannot satisfy population
refuses with a steering message naming the missing rows and the exact repair
command, and leaves no half-populated branch behind: a repeat `bin/seon start`
either succeeds or repeats the same steering message, never a Datahike stack
trace.

## Triage 2026-08-02

**Still real; destination: cluster population prerequisite wave.** The later
coherence gate catches absent namespace/function populations, but it does not
prove that every lookup ref the current population will name exists.
`src/seon/cluster.clj:504-525` accepts any single recorded digest with at least
one namespace and function. `ensure-cluster-entity!` then names every current
`instruction/instruction-ids` lookup ref at lines 830-849. A complete older
program graph that predates one of those instruction rows therefore still
passes the first gate and reaches Datahike's missing-entity refusal. The
current boot test proves partial-program steering, not this transitive
population prerequisite.
