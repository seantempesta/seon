---
type: issue
status: resolved
severity: friction
tags: [issue, ai, config, test, class/n12, wave/ai-retry-evidence]
---

# Remove the deleted run lease from the AI retry proof

## Problem

The shipped AI retry commentary and its regression still justify the retry
budget against a 60-second run lease. Run leases were deleted on 2026-07-28,
and the current model-call deadline is 180 seconds. The test therefore teaches
a deleted custody mechanism and can remain green while proving no current
runtime bound.

## Evidence

- `test/seon/ai_test.clj:187-193` says the shipped retry budget is bounded by
  the "RUN LEASE" and proves only that the total delay is below 60,000 ms.
- `config/default.edn:232-240` correctly says leases are deleted, then claims
  that a model call may take "the full 60 s deadline." The same config sets
  `:seon.config.ai/timeout-ms` to 180,000 at line 183.
- `docs/seon/issues/archive/a-turns-model-work-can-outlive-its-own-run-lease.md`
  records the resolving custody revision: `:seon.cluster.run/lease-until`,
  claim epochs, heartbeat transitions, and the lease refusal arms were
  deleted. `:seon.cluster.run/process` presence now owns custody.
- `src/seon/ai.cljc:184-194,211-241` derives retry delays only from the retry
  strategy. No run-lease reader or 60-second runtime bound remains there.

The current shipped retry values may still be reasonable. What has rotted is
their authority: a deleted clock is still the named reason and the asserted
bound.

## Owner

The AI retry strategy defaults and their proof. The retry wait budget must be
grounded in a current responsiveness or provider constraint, independently of
run custody.

## Acceptance

- No live config commentary or test cites a run lease or a 60-second model
  deadline.
- The maximum total delay has current evidence, or is derived from a current
  owner whose relationship is stated explicitly.
- The regression proves the exact shipped schedule and its current bound; it
  cannot pass merely because one constant is below a deleted constant.
- A repository search finds run-lease language only in historical/quarry
  material that is marked as such.

## Re-grounded evidence — 2026-08-13

**STILL-REAL at `06e654c76`.** The deleted `.cljc` anchor was only a rename;
the stale authority remains in current production inputs and its regression:

- `config/default.edn:407-416` still explains the total retry wait using the
  deleted 60-second lease and a 60-second model deadline.
- `test/seon/ai_test.clj:392-398` still says the shipped strategy is bounded by
  the "RUN LEASE" and asserts only that the delay is below `60000`.
- `src/seon/ai.clj:434-449` derives the strategy from the current dials; it
  supplies no independent 60-second custody bound that could make either claim
  current.

## Resolution — 2026-08-13

Resolved by `ecce9e0c5` at the existing shipped-defaults proof seam.

- A live JVM REPL call to `seon.config/defaults`, `seon.ai/retry-strategy`,
  and `seon.ai/delays` returned the current `180000` ms call deadline, the
  exact shipped retry strategy, midpoint delays `[500 1000]`, and `1500` ms
  total waiting. This reproduced that the old `< 60000` assertion named no
  current owner.
- `config/default.edn:407-413` now grounds the cumulative wait budget against
  the sibling `timeout-ms` dial and contains no live lease or 60-second model
  deadline claim.
- `test/seon/ai_test.clj:392-412` derives the shipped strategy and deterministic
  midpoint schedule, asserts the exact `[500 1000]` delays fit the cumulative
  retry budget, and relates that budget to the current shipped call deadline.
- Focused `bin/test seon.ai-test` completed this regression successfully. Its
  later confirmation phase reported 21 ruled foreign W2 kind-to-class fallout
  assertions at `test/seon/message_test.clj:138`; those consumer/test migrations
  are owned by W3-W5 and are not part of this class fix.
