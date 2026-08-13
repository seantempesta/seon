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
