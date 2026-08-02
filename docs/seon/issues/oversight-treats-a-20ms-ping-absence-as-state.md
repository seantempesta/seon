---
type: issue
status: open
severity: friction
tags: [issue, flow, render, clocks]
---

# Derive fleet state from events, not a 20 ms ping absence

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
