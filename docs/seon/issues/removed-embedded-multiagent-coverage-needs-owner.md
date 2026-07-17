---
type: issue
status: open
severity: friction
tags: [issue, agent]
---

# Restore focused agent edge-case coverage

## Problem

The authority migration correctly deleted the embedded-Datahike
`seon.agent.multiagent-test` harness, but several edge-case assertions do not
yet have exact replacements in the focused authority-backed owners. Restoring
the old local database harness would recreate the removed runtime architecture;
the missing cases belong in the existing run, lifecycle, derive, and loop test
namespaces.

## Evidence

Commit `b950603e` replaces the embedded multiagent system with focused facade
proof for database-value acquisition, spawn depth, atomic delegation, and
purpose authorization. The retained focused suites cover ordinary completion,
run closing, scheduling, crash handling, and breaker behavior, but the review
identified these exact variants without direct replacements:

- blank completion writes no result or message;
- spawn-depth cycle and configured-cap boundaries;
- crash-notice parent/root deduplication;
- stale-watchdog boundary, missing-beat, and late-beat fencing;
- breaker threshold and window boundaries.

## Owner

The existing authority-backed `seon.agent.lifecycle`, `seon.agent.run`,
`seon.derive`, and `seon.agent.loop` focused test owners. There is no new test
harness or local Datahike connection.

## Acceptance

- Each listed behavior has one focused test beside its production owner.
- Tests use ordinary database values and the `seon.db` facade or pure
  transformations; none opens an embedded Datahike connection.
- No compatibility fixture, second runner, or versioned test namespace is
  introduced.
- The focused suites and full maintained CLJS gate pass.
