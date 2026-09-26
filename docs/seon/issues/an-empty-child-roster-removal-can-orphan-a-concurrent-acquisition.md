---
type: issue
status: open
severity: cleanup
created: 2026-09-26
tags: [issue, agent, custody, isolation]
---

# Removing an empty child roster after a failed acquisition can orphan a concurrent one

## Evidence (source, 30e6e8f75)

- `seon.cluster.agent/acquire-context!` takes a branch other than the handle's live branch into the child roster that
  `child-roster!` records under the parent's `:seon.agent/children`.
- When that acquisition throws, the outer catch removes the child record if the child's `:seon.agent/contexts` is
  empty and the record is still that child (`identical?`).
- The emptiness check and the removal are two steps. If a second acquisition of the same branch reserves its key in
  the child between them, it proceeds in a roster the parent no longer records. Its handle, graphs and release then
  fall outside every parent-side lookup: the MCP branch scan and the branch-owner search.

## Why it is low severity

Only a first acquisition of a branch can fail this way, and an isolated branch name is fresh (`agent-<id>`), so a
concurrent same-branch acquisition needs a named branch that is racing its own first failure.

## Owner and acceptance

The owner is `acquire-context!` with `child-roster!`.

Acceptance: decide the removal at the parent roster's own swap. Remove the child only if its value, read inside that
swap, still has no contexts, or let the next `child-roster!` replace a child that holds nothing. One regression: a
failing first acquisition and a concurrent second one; the surviving handle's roster is the one the parent records.
