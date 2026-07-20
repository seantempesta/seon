---
type: issue
status: open
tags: [agent, cljs, issue]
severity: friction
---

# Execution child program load omitted instrumentation

## Problem

The execution child activated the committed schema/function-contract
projection and loaded authored source, but never reconciled Malli wrappers for
that generation. Agent-facing toolkit calls therefore bypassed the one
instrumentation boundary, including its declared `:seon.agent/id` injection.

## Evidence

After namespace setup was repaired, agent `plain-chefs-do` completed sixteen
turns without a child crash. Direct `(seon.db/current-agent-id)` returned
`"plain-chefs-do"`, yet every ordinary `my.plan/step!` call without an
explicit ID returned “no `:seon.agent/id` resolved.” This disproved an ALS-loss
theory: the runtime scope existed, but `my.plan/step!` was not wrapped.

`seon.eval/load-authored-program!` activated the projection and loaded source;
unlike `seon.runtime.admission`, it did not call
`seon.instrument/reconcile-projection!`.

## Owner

`seon.eval/load-authored-program!` owns reconstruction of one complete program
inside an execution child. It activates the schema projection for cljs.js
analysis, loads the requested authored vars, then reconciles the complete
wrapper generation. `eval-batch!` also re-establishes its explicit agent scope
at the self-host callback boundary instead of relying on host-specific ALS
retention.

## Acceptance

- Focused program-loading and eval-batch tests pass with complete wrapper
  reconciliation.
- A real agent can observe its ID and omit `:seon.agent/id` from
  `my.plan/step!`, receiving a successful plan write.
- The same agent resumes its existing plan and performs the namespace work.
