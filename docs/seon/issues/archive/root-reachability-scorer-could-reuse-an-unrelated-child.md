---
type: issue
status: archived
severity: high
tags: [issue, agent, capability]
---

# Root reachability scorer could reuse an unrelated child

## Problem

The root reachability scorer accepted evidence from any earlier child agent.
It did not require exactly one `start!`, extract that transaction's created
child identity, and join every later prompt/query/result/report assertion to
that exact child. A run could therefore pass by reusing a sibling or stale
agent.

## Resolution

The scorer now requires one start operation, resolves the child identity from
that exact transaction, and constrains every downstream evidence join to it.
Missing, repeated, sibling, and unrelated child evidence fail closed. The task
fixture and solver tests use the common admitted Inspect path.

## Evidence

The focused reachability and solver gate passes 81 tests. The retained rejected
root run remains rejected; scorer hardening does not rewrite its historical
result.
