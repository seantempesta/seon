---
type: issue
status: open
severity: friction
tags: [issue, testing, flow]
---

# Publish graph transitions instead of polling them in tests

## Problem

Fresh integration tests repeatedly sample database or Flow state, and one
uses a fixed sleep, to infer transitions the running graph can observe and
publish directly.

## Evidence

`test/seon/cluster/agent_test.clj:139-147` defines a 25 ms polling loop used
throughout the suite; line 416 sleeps 300 ms to assert that a paused graph did
not run. `test/seon/flow_test.clj:930-944` polls an atom with 10 ms sleeps.
Render and turn fixtures also loop on `flow/ping`.

These are not foreign-process or remote-I/O backstops. Core.async Flow already
publishes reports and Datahike publishes transaction reports. The production
armed/render handles discard or hide the precise lifecycle reports, forcing
tests to infer readiness, pause, idle, and settlement from later effects.
`docs/prds/sci-execution-runtime/research/test-smell-audit-2026-07-29.md`
independently identifies the same root cause; no issue note previously owned
it.

The child-process sleeps in `flow/kill_child.clj` and `store_child.clj`, the
bounded foreign-child readiness wait in `store_test.clj`, and deliberate
workload timing in `flow_test.clj:124` are not part of this defect.

## Owner

The armed-agent and render graph handles: retain and expose named lifecycle
reports through the existing Flow report mechanism.

## Acceptance

Tests await named armed, paused/resumed, idle/episode-settled, and render-state
reports or Datahike transaction reports. The named polling helpers and fixed
300 ms sleep disappear; clocks remain only as loud bounds around genuinely
external state.
