---
type: issue
status: resolved
severity: friction
tags: [issue, agent, component]
---

# Keep AI environment tests isolated from every recognized variable

## Problem

The complete CLJS suite inherited `SEON_AI_EXTRA_BODY` from the operator, but
the `seon.ai-test` snapshot/restore fixture did not include that recognized
variable or `SEON_DG_BACKEND`. Tests that claimed to control every AI setting
therefore observed live operator configuration and failed depending on the
launch environment.

## Evidence

The 2026-07-14 complete checkpoint failed exactly two assertions in
`env-row-reads-and-parses-set-vars` and
`env-row-skips-unparseable-values-loudly`: both actual maps contained the live
`:seon.ai/extra-body-edn` value. A default-pod REPL probe confirmed that
`SEON_AI_EXTRA_BODY` was present and projected by `seon.ai/env-row`, while the
fixture's variable set omitted it. `seon.ai/env-var-specs` also recognizes
`SEON_DG_BACKEND`, which the fixture omitted.

## Owner

The environment isolation fixture in `test/seon/ai_test.cljs`, kept aligned
with the recognized input surface in `src/seon/ai.cljs`.

## Acceptance

- The fixture snapshots, clears, and restores both missing recognized inputs.
- Focused `seon.ai-test` passes with a live `SEON_AI_EXTRA_BODY` setting.
- The complete CLJS checkpoint passes without depending on operator AI config.

## Resolution

The fixture now includes `SEON_AI_EXTRA_BODY` and `SEON_DG_BACKEND`, snapshots
their original values, clears every recognized input before each example, and
restores the exact prior environment afterward. The affected gate passes 9
tests/30 assertions. The complete operator-environment checkpoint passes 1,307
tests/6,182 assertions with zero failures and errors; its retained report is
`tmp/test-cljs-20260714-180208-94101.report.edn`.
