---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Retire model I/O from the pod

## Problem

The portable pod leaf still advertises
`:seon.agent.driver.capability/llm` after the cluster JVM `java.net.http` leaf
landed. The pod can therefore win `:open-attempt` and `:settle-attempt`, keeping
production turns on the superseded Node provider path instead of handing the
durable phase cursor to the cluster JVM.

## Evidence

The three live runs in `tmp/orchestrator/redrive2-gate.log` were attributed to
run-holding process `35849@2026-07-24T05:21:05.189Z`. `bin/seon status` identified workload
PID `35849` as the pod and PID `35766` as the JVM host. A history query against
the default database confirmed that the failing runs `xn9l2q67n1cz`,
`t14vkircg9gb`, and `n9m1a5qcgr07` all acquired the pod `:seon.agent.run/process`.

`src/seon/agent/driver/pod.cljs` advertises the LLM capability and dispatches
both attempt phases through `seon.ai.dispatch/llm-fn`. The maintained JVM leaf
is installed as `seon.ai.http/complete` in `src/seon/host.clj` and advertises
the same capability. The identical persisted prompt succeeded through a fresh
JVM leaf, but the live run never exercised that leaf.

## Owner

`seon.agent.driver.pod/leaf` owns the pod pod driver's phase capabilities.
`seon.agent.driver` already releases a held claim when the current leaf cannot
execute the next phase; no new routing mechanism is needed.

## Resolution

Commit `e21c85417` removes the pod LLM capability and both attempt dispatch
arms.
Focused CLJS proof shows the pod leaf advertises exactly render and publish:
2 tests / 4 assertions / 0 failures / 0 errors. The JVM transport/receipt
matrix is also green: 11 tests / 63 assertions.

The source-frozen claimant2 history proves the tier handoff: pod workload PID
`48530` rendered run `dwvphar4i9yf` at epoch `1`, then JVM host workload PID
`50645` acquired epoch `2`, opened attempt `sj29e811vgsg`, and settled it
`:success` with HTTP 200 and a nonempty reply blob. The later planner refusal
closed and released the run without returning model I/O to the pod.

## Acceptance

- The pod run-holding process advertises only render and publish phases.
- After pod rendering, the durable run is cleanly released and the cluster JVM
  owns the provider attempt.
- A live DeepSeek request returns HTTP 200 through `seon.ai.http`, persists a
  successful attempt receipt with response identity, and advances through eval
  and publication.
- The pod remains ready and can publish the completed JVM-evaluated turn.
