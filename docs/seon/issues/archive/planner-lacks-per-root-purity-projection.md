---
type: issue
status: superseded
severity: friction
tags: [issue, runtime, agent]
---

# Planner lacks a per-root purity projection

## Problem

`seon.host.context/pure-block?` still classifies portable-base source blocks
with a regex. Deleting it requires a planner-owned per-root purity result, but
the current planner returns one aggregate placement and requires a claim
database value.

## Evidence

- `build-base!` loads the portable slice before a claim database value exists;
  its focused tests deliberately construct unconnected writer sessions.
- Planning one source block cannot resolve sibling toolkit definitions because
  synthetic bundles are created only for supplied roots. Planning every block
  together returns one aggregate placement rather than a classification per
  root.
- Reconstructing resolution, effects, and sibling bundles in
  `seon.host.context` would create the second analyzer and acquisition path the
  execution-planning design forbids.
- Ordinary core calls are terminals, while the installer inventory enumerates
  capability leaves only, so common pure source blocks cannot yet be
  classified honestly through the current plan consumer.

## Owner

The execution planner. Expose one batch/per-root purity projection over parsed
forms and retained P1 resolutions. `seon.host.context` must consume that result
without acquiring or reconstructing program edges.

## Acceptance

- Pure helper closures and ordinary core calls classify capability-free per
  root.
- Database, package, async, and unresolved roots fail closed.
- Portable-base load counts and effectful toolkit exclusions remain unchanged.
- `pure-block?` and its regex source scans are deleted.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
