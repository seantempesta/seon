---
type: issue
status: open
severity: friction
tags: [issue, flow, database, wave/general]
---

# Flow error proc attribution lacks a declared function relation

## Problem

The error occurrence design requires a function ref derived from the Flow
proc keyword. The fault recorder receives the keyword but no declared
relation to the step Var that constructed that proc. Inferring a function
from the spelling would violate the program-fact authority.

## Evidence

Read on 2026-09-16 during error-graph, baseline
`3c35a62127424075c2c7f585fb88e04e10c652c7`:

- `src/seon/flow.clj:126` receives the step Var in var-process and passes it
  to core.async.flow/process at `:166`.
- `src/seon/cluster/agent.clj:422` constructs the graph definition.
- `src/seon/error.clj:561` copies `::flow/pid` to `:seon.error/proc`.
- `resources/seon/schemas/seon.flow.edn:139` declares an in-memory step-var
  predicate; `:194` declares a keyword proc-id. Neither declares a durable
  relation between a graph's proc keyword and its function entity.
- Searches for proc-fn and proc/step relations in source and schema did not
  find a durable mapping usable by error/prepare. This establishes the
  missing declared relation, not that the live Flow implementation could
  never expose its step through an additional inspection protocol.

## Owner

The existing graph/proc construction seam, which knows both the graph's
proc key and its step Var. The recorder should consume that evidence.

## Acceptance

Derive `:seon.error.occurrence/proc-fn` from declared construction evidence,
including two graphs whose proc keywords coincide but whose step Vars
differ. Preserve the exact proc keyword as evidence; do not infer the
function from its spelling. Verify through the canonical fixture and a
real Flow error report. Until this exists, leave the optional ref absent
and retain this explicit residual.
