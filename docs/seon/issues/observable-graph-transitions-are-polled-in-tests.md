---
type: issue
status: open
severity: friction
tags: [issue, flow, test, class/p2, wave/core]
---

# Publish graph transitions instead of polling them in tests

## Problem

Fresh integration tests repeatedly sample database or Flow state, and one
uses a fixed sleep, to infer transitions the running graph can observe and
publish directly.

## Evidence

`test/seon/cluster/agent_test.clj:153-160` defines a 25 ms polling loop with 12
remaining call sites. The hard core observes idle-pass counts and armer/routing
settlement. `seon.cluster.agent/arm!` receives Flow's started map, including
the report channel, but omits it from the armed handle
(`src/seon/cluster/agent.clj:376-390`). The open
`:seon.cluster.agent/armed` schema likewise has no report channel
(`resources/seon/schema.edn:106`). Tests therefore cannot await those named
graph events through the public handle.
Render and turn fixtures also loop on `flow/ping`.

These are not foreign-process or remote-I/O backstops. Core.async Flow already
publishes reports and Datahike publishes transaction reports. The production
armed/render handles discard or hide the precise lifecycle reports, forcing
tests to infer readiness, pause, idle, and settlement from later effects.
`docs/prds/sci-execution-runtime/research/test-smell-audit-2026-07-29.md`
independently identifies the same root cause; no issue note previously owned
it.

The `Long/MAX_VALUE` child-process sleeps in `flow/kill_child.clj` and
`store_child.clj` are not part of this defect: each child has published
readiness and must remain alive until the parent deliberately sends SIGKILL.

## Progress — 2026-08-02

Commit `53996daa3` removed the pause test's provider-entry polling, wall-clock
duration assertion, fixed 300 ms negative sleep, repeated pause-status ping,
plan poll, and resumed-quiescence poll. They now use latches, one ordered
pause-then-ping acknowledgement, and Datahike transaction events. The exact
test passed 50/50 repetitions; the focused namespace passed 12 tests / 93
assertions.

Commits `8259098f2`, `e5d0e4544`, and `1e320cf41` removed the parent-side
foreign-child file polls through filesystem/pipe readiness plus
`Process.onExit`. Those changes preserve a loud foreign-process backstop and
do not change the intentional child wait-to-be-killed sleeps.

The issue remains open because the 12 agent polling call sites cannot all be
removed within test-only ownership. Closing them requires the armed handle to
retain the existing Flow report channel and its open schema to admit it; a
hidden test-only channel would recreate the interface defect rather than fix
it.

## Continuation — 2026-09-15

Commit `e4f8bbe07` retains Flow's started value on newly armed
handle and publishes completed idle passes through its existing report
channel. The seven remaining `await-until` calls in the agent namespace are
replaced with named idle or closed-turn reports. The canonical fast run
verified park/wake and hot reload after removing the helper. The render-test
ping helper now requests one proc reply with the shared event bound instead
of retrying Flow's default one-second window; the long-derivation regression
uses an explicit hold/release event instead of a 1,500 ms coalescing setting.

The same run exposed disarm retaining a cancelled completion observer's
never-closed channel. The candidate closes that existing channel on
cancellation and re-derives the bounded stop wait. A dedicated canonical
regression is included. The focused agent gate passed 22 tests / 146
assertions. The final additive declaration preserves existing live handles;
default's loaded pass-report contract and readable root handle were verified
by MCP JVM probe. Final combined/platform gates are requested from the
orchestrator under the 21:05Z rule.

This note remains open: the concurrently edited `src/seon/render/web.clj`
publishes no completed-pass report when no package changes. Remaining stream
and runtime-event tests still poll that proc's ping projection. The needed
change is to publish that existing projection through Flow's report channel;
the lane does not alter the protected owner or substitute an unrelated
interest-port settlement for those events.

## Owner

The armed-agent and render graph handles: retain and expose named lifecycle
reports through the existing Flow report mechanism.

## Acceptance

Tests await named armed, paused/resumed, idle/episode-settled, and render-state
reports or Datahike transaction reports. The named polling helpers and fixed
300 ms sleep disappear; clocks remain only as loud bounds around genuinely
external state.
