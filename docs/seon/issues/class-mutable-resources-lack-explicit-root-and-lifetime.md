---
type: issue
status: open
severity: blocker
tags: [issue, operator, runtime, test, class/n4, class-kill, wave/class-kill-queue]
---

# Make mutable resources carry their root and lifetime

## Problem

Mutable files, connections, children, executors, and operator roots can be
created outside the scope that settles and releases them. Some operations
reopen borrowed custody; others share an installation or repository path even
after selecting an isolated root. Cleanup and contention are therefore
remembered conventions instead of consequences of construction.

## Evidence

Current open members carry `class/n4` and are derived with
`bin/issues-index --class class/n4`.

## Owner

The root/resource constructors and their operation-specific completion values.

## Acceptance

- A constructor returns one ownership value carrying the selected root,
  resource, every owned child completion, and release operation.
- Mutable paths derive only from that root; only immutable inputs may be
  shared across roots or workers.
- Borrowers receive custody and have no reopen operation; cleanup is reachable
  only after all owned completions settle.
- Cross-root and interrupted-operation properties prove no contention, leak,
  early deletion, or second acquisition can be constructed.

## Re-verified at HEAD (2026-09-15)

UNVERIFIABLE-WITHOUT-GATE (`seon.cluster.store-test`, `seon.operator-test`, `seon.dev.fresh-operator-test`). Audited HEAD `7e35df213:src/seon/cluster/store.clj:117-134` derives canonical root/lock paths; `src/seon/operator.clj:928-960` explicitly distinguishes borrowed versus acquired stores and releases acquired custody in finally. Process executors remain explicitly owned by `resources/seon/operator/runtime.clj:11-30`. These owners still exist, with substantial corrections; they do not prove absence of every cross-root leak. Current member claims include `deletable-directories-have-no-claim-or-size-facts.md` and `dependency-cache-lock-wait-has-no-deadline.md`. Need the root-isolation/interruption tests; launching them is prohibited by the owner correction. No resource leak was reproduced in this triage. Retain blocker pending verification of the class's resource-lifetime members.

surface: store-process
