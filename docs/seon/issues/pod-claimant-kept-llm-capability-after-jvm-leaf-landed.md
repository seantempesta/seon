---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime]
---

# Retire model I/O from the pod claimant

## Problem

The portable pod leaf still advertises
`:seon.agent.driver.capability/llm` after the JVM `java.net.http` claimant leaf
landed. The pod can therefore win `:open-attempt` and `:settle-attempt`, keeping
production turns on the superseded Node provider path instead of handing the
durable phase cursor to the JVM claimant.

## Evidence

The three live runs in `tmp/orchestrator/redrive2-gate.log` were attributed to
claimant `35849@2026-07-24T05:21:05.189Z`. `bin/seon status` identified workload
PID `35849` as the pod and PID `35766` as the JVM host. A history query against
the default database confirmed that the failing runs `xn9l2q67n1cz`,
`t14vkircg9gb`, and `n9m1a5qcgr07` all acquired the pod claimant identity.

`src/seon/agent/driver/pod.cljs` advertises the LLM capability and dispatches
both attempt phases through `seon.ai.dispatch/llm-fn`. The maintained JVM leaf
is installed as `seon.ai.http/complete` in `src/seon/host.clj` and advertises
the same capability. The identical persisted prompt succeeded through a fresh
JVM leaf, but the live run never exercised that leaf.

## Owner

`seon.agent.driver.pod/leaf` owns the pod claimant's phase capabilities.
`seon.agent.driver` already releases a held claim when the current leaf cannot
execute the next phase; no new routing mechanism is needed.

## Current state

The pod LLM capability and both attempt dispatch arms are removed in source.
Focused CLJS proof shows the pod leaf advertises exactly render and publish:
2 tests / 4 assertions / 0 failures / 0 errors. The JVM transport/receipt
matrix is also green: 11 tests / 63 assertions.

Live acceptance remains open. The isolated `claimantllm` gate stopped before a
provider call because `cluster open` did not reconcile a target JVM claimant;
the target pod was stopped cleanly. See
[[named-cluster-open-does-not-reconcile-jvm-host]] and
`tmp/orchestrator/claimantllm-gate.log`.

## Acceptance

- The pod claimant advertises only render and publish phases.
- After pod rendering, the durable run is cleanly released and the JVM claimant
  owns the provider attempt.
- A live DeepSeek request returns HTTP 200 through `seon.ai.http`, persists a
  successful attempt receipt with response identity, and advances through eval
  and publication.
- The pod remains ready and can publish the completed JVM-evaluated turn.
