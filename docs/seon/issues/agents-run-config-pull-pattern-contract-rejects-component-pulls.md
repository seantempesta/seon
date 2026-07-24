---
type: issue
status: open
severity: blocker
tags: [issue, agent, web, schema]
---

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

## Acceptance

- The function contract accepts the exact closed union of pull-pattern
  members it returns: keyword attributes and component-pull maps.
- A regression invokes the instrumented real function and validates both
  descriptor component members.
- A real `POST /agents/run` reaches final evidence and returns HTTP 200 after a
  settled turn.
