---
type: issue
status: resolved
severity: blocker
tags: [issue, agent-runtime, database, error]
---

# Preserve transaction refusal reasons across every run-loop branch

## Problem

Four run-loop transaction sites reduce a preserved refusal value to the single
outcome keyword `:error`. The exact rule or database rejection is discarded,
and the observation-only turn report has no live durable consumer.

## Evidence

The open branch discards the value at `src/seon/cluster/loop.cljc:342-359`;
the plan-freeze branch at `src/seon/cluster/loop.cljc:400-412`; the receipt
start and terminal-settle paths at `src/seon/cluster/loop.cljc:430-440` and
`src/seon/cluster/loop.cljc:453-489`; and the close branch at
`src/seon/cluster/loop.cljc:497-508`. In contrast,
`src/seon/cluster/store.clj:436-447` preserves the transition refusal or
Datahike rejection as a flat value.

The original research line numbers moved in the intervening run-hardening
commits, but the refusal values still collapse to `:error`.

## Owner

`seon.cluster.loop/turn`, through the one durable run/fault error mechanism.

## Acceptance

- Every system-side refusal returned by `store/transact!` retains its exact
  error value through durable fault or run facts.
- No branch relies on the observation-only turn report as the sole destination
  for a refusal reason.
- Tests exercise open, plan freeze, receipt start, terminal settle, and close
  refusals and assert the preserved rule or database rejection.

## Resolved 2026-07-27 — every refusal becomes a durable error fact

`seon.cluster.loop/refused!` records the refusal through
`seon.error/commit-tx` and returns whether it refused, so the five
branches read `(if (refused! …) :error …)` and the recording is not a
second branch to keep in sync. Open, plan-freeze, receipt-start,
terminal-settle and close all go through it; the `:open` case passes
agent-only attribution, because an open that refused has no run to
point at and a lookup ref to a run that does not exist would fail the
very transaction recording the failure.

The turn report is no longer any refusal's destination — and it is no
longer a declared out at all: it rides `::flow/report`, flow's own
observation channel. That change came out of this fix and closed a
second defect (below).

Recording deliberately ignores its own outcome: if the database refuses
the error fact too, the answer is not to record THAT. `store/transact!`
never throws, so the loop keeps its pass and the visible symptom stays
the original refusal.

Note the design decision this fix forced, recorded because it is not
obvious: a refused transition does NOT message the attributed agent. It
becomes a fact, and escalates to the escalation owner on recurrence. An
agent is messaged only when the error was a Throwable that escaped our
code — the case where its run was interrupted and it cannot know
otherwise. Measured: wiring a message to every refusal turned a bounded
test drive into fresh runs opening to discuss refusals.
