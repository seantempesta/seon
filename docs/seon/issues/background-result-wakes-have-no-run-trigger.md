---
type: issue
status: open
severity: major
tags: [issue, agent, runtime, data-model]
---

# Give every wake an explicit run trigger

## Problem

A terminal background result wakes its target agent, but the run opened for
that wake records only `:seon.cluster.run/background-results`. It has no
`:seon.cluster.run/trigger`, unlike message and schedule wakes. Therefore the
live situation cannot answer “why am I awake?” through the one trigger member
for this path.

## Evidence

`src/seon/cluster/work.clj` derives `:open` from
`unanswered-background-result?`, without a message identity. The open
transition attaches the result refs, and
`test/seon/background_test.clj` explicitly asserts that the opened run's
`:seon.cluster.run/trigger` is nil.

## Owner

The run trigger model and background-result open transition in
`src/seon/cluster/loop.clj` and `src/seon/cluster/run.clj`.

## Acceptance

- Every message, schedule, error, and background-result wake gives its opened
  run one queryable cause through a declared run connection.
- `(help)` reports that cause without branching on wake family.
- The background-result regression no longer expects a cause-free run.
