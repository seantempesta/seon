---
type: issue
status: open
severity: blocker
tags: [issue, runtime, flow, agent, wave/flow-join]
---

# Orderly-stop completion joins have no bound

## Problem

Cluster teardown correctly waits on component-owned completion events, but
several joins still omit the loud bounded half. One missing armer, proc, or
launcher publication can make `cluster/stop!` park forever after the per-agent
disarm repair has succeeded.

## Evidence

- `src/seon/cluster.clj:2363-2386` blocks on armer admission and quiescence.
- `src/seon/cluster.clj:2391-2402` blocks on cluster-loop, render, and search
  stop completions.
- `src/seon/flow.clj:696-720` blocks on launcher drain and proc stop.
- `src/seon/flow.clj:1129-1139` blocks on fault-committer proc completion.

## Acceptance

One declared orderly-stop bound is carried with the cluster/flow handle and
races each exact completion event. Firing is a typed core-fault diagnostic
naming cluster, component/proc, lifecycle operation, and bound; it is never
reported as successful teardown. Regressions replace each completion publisher
with a never-settling event and prove `cluster/stop!` returns or fails within
the declared bound.

## Disposition (2026-08-14)

`3f0cdf200` converted the two free work-launcher joins through
`seon.await/await!`. The launcher carries
`:seon.config.agent/turn-completion-backstop-ms`; expiry distinguishes the
launcher-drained event from the work-launcher-proc-stopped event and reports
the active-work and submission counts. `seon.flow-test` passed 20 tests / 258
assertions.

The class remains open. These members were held rather than edited:

- `src/seon/cluster.clj:2437-2472`: armer admission/quiescence and the cluster
  loop, render, and search completion joins. This file is protected by the
  owner task.
- `src/seon/flow.clj:1159-1170`: `stop-error-fanout!` still takes its proc
  completion without a bound. Its production handle is constructed in the
  protected cluster owner and does not carry the declared orderly-stop bound,
  so converting only this free function would invent a constant or a second
  config-read seam.

The program-graph census therefore records only the completed owner crossings;
it does not misreport these protected members as resolved.
