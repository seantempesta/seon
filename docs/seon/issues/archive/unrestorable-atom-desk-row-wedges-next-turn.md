---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, sci, agent, live-drive]
---

# Skip unrestorable atom rows for the agent's defs before blob rehydration

## Problem

An agent turn can settle an atom-shaped row for the agent's defs with an honest
`:seon.def/unrestorable-reason` and neither `:seon.def/value-edn` nor
`:seon.def/blob`. The next turn's `seon.sci.eval/fork-for-turn` dispatches on
`:seon.def/atom?` before the unrestorable reason, calls `def-value`, and then
calls `seon.blob/get` with a nil digest. The core fault is committed, but the
run stays open with custody and no eval receipt.

This is a turn wedge, not a missing-blob storage failure: the database row
already says why it cannot be restored.

## Live evidence — default cluster, 2026-08-06

Run eid `23675`, id `f9a0547f-761a-427a-84e1-d81f2764aff7`, settled eval
receipt eid `23682` at `2026-08-06T17:26:19Z`. Its terminal transaction
`536871007` committed these agent-scoped rows for the agent's defs:

| eid | `:seon.def/key` | stored state |
|---:|---|---|
| 23683 | `["root" "seon.operator.runtime/held-flocks"]` | `:seon.def/atom? true`, unrestorable, no value/blob |
| 23684 | `["root" "seon.operator.runtime/running-instances"]` | `:seon.def/atom? true`, unrestorable, no value/blob |
| 23685 | `["root" "seon.operator.runtime/root-store-holder"]` | `:seon.def/atom? true`, unrestorable, no value/blob |

All three carry the reason `The atom's settled value is not store-faithful.`

The next run, eid `23687`, id
`f56667dc-a2ec-4f92-af47-e37cdb06535c`, opened at
`2026-08-06T17:26:19Z` with process identity
`52509-1786036914863`. At `2026-08-06T17:27:50Z`, error fact eid `23700`
recorded:

```text
seon.blob/get violated its contract (invalid-input):
[nil [{:value nil, :message "should match regex"}]]
```

At `2026-08-06T17:38:47Z` the run still had custody, no close fact, and zero
eval receipts. The database and log were otherwise quiet for more than four
minutes.

The source order is visible at `src/seon/sci/eval.clj:1329-1359`: the `atom?`
arm invokes `def-value` before the later `reason` arm can emit its notice.

This cause refines, without replacing, the independent symptoms recorded in
[Keep newly loaded system Vars out of the agent's defs](agent-desk-captures-newly-loaded-system-vars.md)
and
[Settle or refuse a frozen plan's first form](run-freezes-before-first-receipt-after-plan-freeze.md).

## Owner

The single restoration dispatch for the agent's defs is `seon.sci.eval/fork-for-turn`. Blob
storage is not the owner: no blob identity exists or should be read for this
row.

## Acceptance

- An atom row carrying `:seon.def/unrestorable-reason` and no value/blob emits
  the existing honest agent defs notice without calling `seon.blob/get`.
- A restorable atom with a value or blob still rehydrates its snapshot state.
- A turn following an unrestorable atom row reaches and settles its first eval
  receipt; no core-fault fact is committed.
- The live-drive reproduction closes its run rather than retaining custody
  indefinitely.

## Resolution

Resolved by `11ddaba1a`. `fork-for-turn` now handles an explicit
`:seon.def/unrestorable-reason` before atom restoration, never calls
`seon.blob/get` without both a connection and digest, and contains malformed
source/value rows as one honest `could not restore …` REPL notice. Rows with no
restorable representation are not pre-interned into the fork.

`preexisting-bad-def-rows-do-not-block-the-next-turn` commits the two
unrestorable runtime atom shapes and one malformed atom shape before building
a fresh cluster context. The fork returns three deterministic notices, its
first form evaluates to `3`, and the real receipt settlement commits the exact
evaluation result at ordinal zero. The cross-JVM proof of the agent's defs also passed after
its writer adopted the production per-turn fork boundary.

The complete agent defs gate passed 6 tests / 24 assertions; the adjacent evaluator
gate passed 52 tests / 245 assertions. Per the lane's explicit safety scope,
the already-live `default` cluster and its wedged run were not mutated or
re-driven.
