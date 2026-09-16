---
type: issue
status: resolved
severity: friction
tags: [issue, testing, instrumentation, wave/contract-gate]
---

# SCI reload regression leaves worker instrumentation changed

The N7 isolated gate at `8574992e43ebd6e435a89629175b8d61bcbe4d19`
plus its owned paths reported `WORKER-GLOBAL STATE CHANGED` after
`seon.sci.eval-test/the-evaluator-remains-live-after-its-namespace-reloads`.
The entering instrumented set lost 23 wrappers, including `accept-candidate!`,
`acquire!`, `build-base-ctx`, and `bind-result!`. The lane's only change in
this test file was canonical acquisition in a different evaluation-count test.

This is a fixture-restoration defect. The runner detected it; the observation
does not claim that later tests ran unarmed or that it caused their failures.
The earlier gate re-arming repair remains separately documented in
[the closed instrumentation issue](archive/an-armed-contract-test-is-unarmed-by-another-test-in-the-same-worker.md).

The subject is `test/seon/sci/eval_test.clj:243`. The reload test uses the canonical
`seon.test-support/preserving-instrumentation-state` fixture. Superseded roots
must not be restored; newly loaded definitions must be re-armed. Acceptance is the real
armed test completing with no worker-global drift report. The fixture and
instrumentation owners were protected during N7; no changes were made there.
Full gate context is in
[the N7 landing note](../../prds/context-generation/research/n7-query-classification-2026-09-15.md).

## Resolution — 2026-09-16 UTC

Commit `0edd57230` scopes the reload test with the existing
`seon.test-support/preserving-instrumentation-state` fixture. The generated-form
instrumentation test uses the same fixture and deletes its bespoke cleanup.
No second restoration mechanism was added.

A live default JVM probe reproduced 24 removed wrappers before the repair.
After loading the saved definitions and arming with default's carried projection,
`seon.test/run` recorded three passing assertions for each test and no added or
removed instrumented Vars. The complete final replay was 8 tests / 72 assertions,
zero failures/errors and zero instrumentation-set changes. Exact run identities
and before/after evidence are in
[the P1 batch-12b landing record](../../prds/context-generation/research/p1-ambient-state-2026-09-15.md)
and its adjacent `p1-batch12b-final-2026-09-15.edn`. The orchestrator re-gate is
pending; no new full platform-tier result is claimed.

## Recurrence and correction — S1, 2026-09-17

The current restoration owner deliberately excludes replaced definitions.
Reloading the evaluator therefore left 24 new definitions unarmed even inside
the preservation fixture. The existing regression now calls
`seon.instrument/apply!` in `finally`, with its entering handed projection,
to arm the new definitions without restoring superseded callable roots.
The in-process run on default at 2026-09-16T19:01:48Z recorded four passes,
zero failures/errors and no instrumentation drift (basis 536871418).
No restoration owner or test harness was changed; the cold gate awaits review.
