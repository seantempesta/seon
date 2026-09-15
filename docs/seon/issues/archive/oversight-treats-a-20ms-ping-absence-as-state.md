---
type: issue
status: resolved
severity: friction
tags: [issue, flow, render, class/p2, wave/render-oversight-event]
---

# Derive fleet state from events, not a 20 ms ping absence

## Resolution verified — 2026-09-15

The original classifier was dissolved in `b5665971d`, verified by history and
current `src/seon/oversight.clj:94,221`. A missing pong is explicitly unknown;
an open turn fact supplies mid-turn, and a pong without an open turn supplies
parked. The 20 ms value is a declared config fact, not evidence that work is
running. No replacement production mechanism was needed in this lane.

MCP JVM probes on default returned
`{:seon.oversight/proc :probe/absent :seon.oversight/ping :unknown}`.
The three-worker `run.0j9ayQ` gate passed the absence matrix and a freshly
booted real-cluster/page proof (`a-booted-cluster-tells-its-live-fleet-story`,
40,182 ms), as part of 36 tests / 327 assertions with no failures/errors.

Unknown deliberately does not diagnose why a proc failed to answer. Named
graph-transition observations and removal of test polling remain tracked by
`observable-graph-transitions-are-polled-in-tests.md`; no comprehensive
busy-versus-scheduler diagnosis is claimed. See the
[landing note](../../../prds/context-generation/research/fixtures-events-2026-09-15.md).

## Problem

Fleet oversight uses a private 20 ms deadline as the primary classifier for
whether a proc is parked or mid-turn. Scheduling delay and active work are
therefore indistinguishable, and absence of a response is rendered as state.

## Evidence

`src/seon/oversight.clj:34-39` justifies and hard-codes
`ping-timeout-ms` from a past microbenchmark rather than an observable
transition. `agent-story` at lines 87-120 pings with that deadline; a missing
turn pong combined with a current run becomes the mid-turn story.
`plumbing-story` at lines 122-142 repeats the same deadline and emits a proc
row without pass evidence when no pong arrives.

The namespace docstring at lines 16-21 explicitly defines missing reply as
mid-turn. A loaded JVM under scheduler pressure can therefore report the same
shape as genuine work without any durable or Flow transition proving it.

The 2026-08-02 frozen-gate REPL probe falsified the executor-change suspicion:
mailbox, turn, and plumbing pings all responded, and `seon.oversight/unit` plus
`block-html` produced the expected fleet story. The actual gate regression was
the missing `seon.render.web/page-of` caller, fixed in `feb1c30d9`; it does not
dissolve this independent clock-law defect.

## Owner

The existing Flow lifecycle/report channel for each armed agent and the
oversight render's query over those observable facts.

## Acceptance

- Armed, active, parked, stopped, and faulted observations come from named
  Flow reports or durable run facts, not response timing.
- A ping deadline may remain only as a loud diagnostic backstop whose firing
  is itself reported as uncertainty/fault, never translated into mid-turn.
- Oversight distinguishes a busy transform, scheduler delay, stopped graph,
  and unavailable owner with evidence for each.
- A stress regression delays scheduling beyond 20 ms and proves no false
  mid-turn state.

## 2026-09-07 evening — seen again

The `one-eval-point` lane observed `seon.render.web/render` answering
`unknown` to the oversight ping on `juniper-context` while serving every
page and feed request in milliseconds. Same class; no new mechanism.
