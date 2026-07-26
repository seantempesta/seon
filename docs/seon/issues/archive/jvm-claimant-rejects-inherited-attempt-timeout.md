---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime, config]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Preserve the inherited LLM attempt timeout on the cluster JVM

## Problem

The cluster JVM refuses every ordinary agent whose
`:seon.ai/agent-attempt-timeout-ms` override is absent. Absence is the
documented inherit case, but `seon.agent.driver.host/resolve-llm-context!`
requires the optional override itself to be a positive integer before it calls
the shared resolver. A rendered turn therefore stops before an attempt receipt
is opened, so the `java.net.http` transport is never called.

## Evidence

The graduation drive created agent `yummy-mirrors-hang` through `POST /agents`
on the ready default cluster. Run `pbnfs9xudihn` opened at
`2026-07-24T06:44:15.451Z`; pod run-holding process
`82416@2026-07-24T06:34:22.798Z` rendered turn `vdwttk9tkndz`, then released it.
cluster JVM `82301@2026-07-24T06:34:12.318333Z` acquired the run at epoch `2`.
The current turn remained `:running` at phase `:rendered`, with a prompt blob
but zero `:seon.ai.attempt/id` rows.

A database pull of the agent returned only
`{:seon.agent/id "yummy-mirrors-hang"}`: the optional
`:seon.ai/agent-attempt-timeout-ms` override is absent. The same query showed
that every other default-cluster agent also inherits rather than stores that
override.

`src/seon/agent/driver/host.clj:68-87` reads the optional agent attribute into
`attempt-timeout-ms`, returns
`"The acquired agent has no positive durable LLM attempt timeout."` when it is
absent, and only then calls `ai.core/resolved-config-from-rows`.
`src/seon/ai/core.cljc:438-441` already owns the correct contract: use the
agent override when present, otherwise use the acquired fallback argument.
The existing pod owner supplies that fallback through
`src/seon/ai.cljs:593-608`; the cluster JVM does not.

No provider request or 402 occurred. Exact live datoms, database values, and
process generations are in `tmp/orchestrator/finaldrive-gate.log`. The
`/agents/run` request eventually returned HTTP 200 only because its explicit
600,000 ms observation bound expired; the response reported
`model_transport_evidence.status = "absent"`, one running turn, zero evals,
an empty reply, and `timed_out = true`.

## Owner

`seon.agent.driver.host/resolve-llm-context!` owns JVM acquisition of the
ordinary LLM configuration rows. It must call the one
`seon.ai.core/resolved-config-from-rows` contract with the same acquired
attempt-timeout fallback used by the active configuration authority.

## Acceptance

- An agent with no `:seon.ai/agent-attempt-timeout-ms` datom resolves the
  configured/default positive attempt timeout instead of returning an error.
- A present per-agent override still wins.
- The cluster JVM reaches the next phase boundary without returning the
  missing-override configuration error.

## Resolution

Commit `094e7a7e6` moves the process fallback into
`seon.config.resolve/llm-attempt-timeout-ms` and makes both the pod accessor and
the cluster JVM pass that same fallback to
`seon.ai.core/resolved-config-from-rows`. The shared resolver still gives a
present per-agent override precedence.

The focused JVM regression proves an absent override resolves to `32100` and a
present override resolves to `65400`. The portable resolver regressions prove
the shipped `120000` default, a valid environment override, and invalid-value
fallback. The isolated `claimantpath` live drive reached the JVM claimant
without the former configuration error. It then exposed the separate
[[../jvm-claimant-id-allocation-future-is-null]] defect, which the phase-error
settlement correctly persisted and released. Evidence is in
`tmp/orchestrator/claimantpath-gate.log`.
