---
type: issue
status: open
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
