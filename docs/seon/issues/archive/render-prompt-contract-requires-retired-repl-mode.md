---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, schema, runtime]
---

# Align the prompt result contract with explicit reply policy

## Problem

The live prompt renderer returns the explicit reply-policy projections, while
`seon.agent.turn/render-prompt` still requires the retired
`:seon.config/repl-mode` projection in its output schema. Runtime
instrumentation therefore converts a valid prompt render into a core fault
before the first turn can open.

## Evidence

The default-cluster live drive created agent `tall-turkeys-turn` and run
`q8mrne0fdwvo` through `POST /agents` at 2026-07-24T04:55:41Z. The run claimed
successfully at epoch 1, but no turn, model-attempt, or eval receipt was ever
committed.

`logs/operator/pod/b21b042c-fb81-439d-9f03-dc39e243adc4.log:434` records:

```text
SEON-CORE-FAULT :malli.core/invalid-output
:seon.error.malli/fn-sym seon.agent.turn/render-prompt
:seon.error.malli/path [:seon.config/repl-mode]
:seon.error.malli/leaf-type :malli.core/missing-key

```

The writer retained the same evidence twice as core-fault entities:

- entity 7004, transaction 536871708,
  `:seon.error/kind :seon.error.kind/malli-instrument-output`;
- entity 7010, transaction 536871709, with the same kind, function, and missing
  path.

Both fault rows name basis transaction 536871707. The cluster config entity at
the later writer value still carries `:seon.config/repl-mode :batch`, so this
is not absent configuration.

The source mismatch is direct:

- `src/seon/agent/ctx/driver.cljs:360-377` merges
  `seon.ai/reply-policy-from-rows` into the rendered prompt;
- `src/seon/agent/turn.cljs:344-353` still requires
  `:seon.config/repl-mode` in `::rendered-prompt`.

The run remained open with the dead pod run-holding process, no current turn, and no
receipts at writer basis transaction 536871709.

The dependency boundary is Malli 0.20.0, pinned in `deps.edn`; its maintained
source is `reference-code/malli` at `80138076960e`.
`malli.core/-ref-schema` resolves registered keyword references through the
active registry, and `seon.schema/valid-candidate-value?` validates against
Seon's candidate registry. The existing R36 schema owners are
`src/seon/config/resolve.cljc` for `:seon.ai/wire-stream?` and
`:seon.ai/reply-evaluation`; `src/seon/ai/core.cljc` produces their resolved
map through `reply-policy-from-rows`.

Commit `a88e11505` replaces the retired required output field with references
to those two registered axis schemas. The regression invokes the real
`seon.agent.ctx.driver/render-prompt!` producer through the instrumented
`seon.agent.turn/render-prompt` consumer for all four legal combinations,
asserts each result satisfies `:seon.agent.turn/prompt-result`, and proves the
legacy projection is absent. The focused `seon.agent.turn-test` gate passed 17
tests and 51 assertions with zero failures or errors; the transcript is
`tmp/orchestrator/replmode-gate.log`.

A source scan confirms `src/seon/agent/turn.cljs` has no remaining
`:seon.config/repl-mode` contract field. Its remaining occurrences are
historical explanatory comments. Per the live-proof source-freeze boundary,
this lane did not start a cluster; the orchestrator owns the rebuild, restart,
and fresh agent re-drive.

## Owner

`seon.agent.turn` owns the public prompt-result contract. It must describe the
one value produced by `seon.agent.ctx.driver/render-prompt!`; do not restore a
second legacy-mode projection beside the explicit reply-evaluation and
wire-stream policy.

## Acceptance

- The instrumented real prompt path accepts all four explicit reply-policy
  combinations without requiring `:seon.config/repl-mode` in its result.
- A fresh live agent opens a turn, records a DeepSeek attempt receipt, and
  advances through `:published`.
- A contract regression compares the actual driver result against
  `::prompt-result`, so producer and consumer cannot drift independently.

The source contract and recurring regression are resolved by `a88e11505`. The
fresh live re-drive remains the orchestrator's integration proof after its
coordinated rebuild and restart; it is not a remaining source change in this
issue.
