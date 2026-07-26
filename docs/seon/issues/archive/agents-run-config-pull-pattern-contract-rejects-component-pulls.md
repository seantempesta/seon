---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, web, schema]
---

Terminology: this note records evidence from before the rename; the process holding a run is now `:seon.agent.run/process`.

# Accept component pulls in the model config pull-pattern contract

## Problem

`seon.ai/config-pull-pattern` declares an output schema of
`[:vector :keyword]`, but its maintained return value deliberately includes
two Datahike component-pull maps for provider descriptors and model variants.
Runtime instrumentation therefore rejects the real value whenever an
instrumented consumer calls it. The `/agents/run` final-evidence path turns a
settled live run into HTTP 500.

## Evidence

The 2026-07-24 default-cluster redrive created agent `real-mails-fix` and drove
run `xn9l2q67n1cz`. The run and turn settled in the database, after which
`seon.web.serve/final-agent-task-result` called the instrumented function and
returned:

```clojure
{:seon.error.malli/fn-sym seon.ai/config-pull-pattern
 :seon.error.malli/schema [:=> :cat [:vector :keyword]]
 :seon.error.malli/errors
 [{:in [8] :value {:seon.config/provider-descriptors [*]}}
  {:in [9] :value {:seon.config/model-variants [*]}}]}

```

The pod log records the failure at 2026-07-24T05:31:15.736Z. The HTTP response
was 500. The complete live transcript and database evidence are in
`tmp/orchestrator/redrive2-gate.log`.

This is the same class as the already-fixed prompt reply-policy mismatch:
the function's real producer result is correct and its output contract is
stale. `src/seon/ai/core.cljc` owns both the function and its schema.

## Owner

`seon.ai.core/config-pull-pattern` owns the typed Datahike pull pattern.
Consumers must continue to use that one pattern; do not copy or weaken it at
`seon.web.serve`.

## Resolution

Commit `de1458b24` registers the closed component selector shape once and
declares the output as a vector whose members are either qualified attributes
or one-key descriptor/model-variant component pulls. Its focused regression
instruments the real function, invokes it, and validates both component
members. The later claimant2 drive acquired the resulting provider/model
configuration and persisted a DeepSeek status-200 receipt, independently
confirming the real producer value crosses the model boundary.

The final `/agents/run` graduation remains downstream of the separate
execution-planning issue; it no longer fails at this contract.

## Acceptance

- The function contract accepts the exact closed union of pull-pattern
  members it returns: keyword attributes and component-pull maps.
- A regression invokes the instrumented real function and validates both
  descriptor component members.
- A real `POST /agents/run` reaches final evidence and returns HTTP 200 after a
  settled turn.
