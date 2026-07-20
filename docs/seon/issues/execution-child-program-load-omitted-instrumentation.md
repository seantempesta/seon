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

Related live observation (2026-07-20, warnings-block re-enable lane, default
cluster after `bin/seon restart` at f85e68da's parent): root's derived
`instrumentation-gaps-block` rendered "621 specced fns without a live
wrapper" on the POD side, and `core-faults-block` showed 13 `:core` faults
since the last user message (at least two were that lane's own REPL probes:
an unallocated-generated-identity `create!` refusal and a
`:malli.core/invalid-input`, both recorded `:core-bug`). The 621-gap census
was observed, not diagnosed — it may be this issue's pod-side sibling or a
separate publication fault after restart; whoever picks this up should read
the census via `seon.instrument/coverage-gaps` on the live pod first.

## Owner

`seon.eval/load-authored-program!` owns reconstruction of one complete program
inside an execution child. It activates the schema projection for cljs.js
analysis, loads the requested authored vars, then reconciles the complete
wrapper generation. `eval-batch!` also re-establishes its explicit agent scope
at the self-host callback boundary instead of relying on host-specific ALS
retention.

## Status 2026-07-20 (clean-signals lane)

The reconciliation gap is fixed in source: `seon.eval/load-authored-program!`
activates the projection, loads the targets, then calls
`seon.instrument/reconcile-projection!` and throws a `:core-bug` value on an
incomplete generation (`src/seon/eval.cljs:954-962`, landed with bef42a75
"Restore execution child program contracts"). Focused loader tests in
`test/seon/execution_test.cljs` pass inside the green full suite
(2026-07-20: 1293 tests, 0 failures).

The related pod-side "621 specced fns without a live wrapper" census is NOT
reproducible: live default pod 2026-07-20 18:4x, `coverage-gaps` over all
726 `:seon.fn/spec` rows returned 0 gaps. Treat that observation as a
transient post-restart publication state unless it recurs.

Remaining before close: the live acceptance drive — a real agent observing
its ID and calling `my.plan/step!` without an explicit `:seon.agent/id`
through an execution child.

## Acceptance

- Focused program-loading and eval-batch tests pass with complete wrapper
  reconciliation.
- A real agent can observe its ID and omit `:seon.agent/id` from
  `my.plan/step!`, receiving a successful plan write.
- The same agent resumes its existing plan and performs the namespace work.
