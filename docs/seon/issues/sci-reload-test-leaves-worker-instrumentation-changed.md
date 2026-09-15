---
type: issue
status: open
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

The subject is `test/seon/sci/eval_test.clj:253`. The reload test must use the canonical
`seon.test-support/preserving-instrumentation-state` fixture and restore its
entering callable roots and registry even on failure. Acceptance is the real
armed test completing with no worker-global drift report. The fixture and
instrumentation owners were protected during N7; no changes were made there.
Full gate context is in
[the N7 landing note](../../prds/context-generation/research/n7-query-classification-2026-09-15.md).
