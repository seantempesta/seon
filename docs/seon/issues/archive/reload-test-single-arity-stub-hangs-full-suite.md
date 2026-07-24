---
type: issue
status: resolved
severity: friction
tags: [issue, cljs, testing]
---

# Reload test's single-arity stub hangs the full suite

## Problem

The full `bin/test-cljs` run reached
`seon.client-initialization-test`, printed `INITPAGE_10X_MEASUREMENT`, and
timed out after 1,802 JavaScript seconds without a final `cljs.test` summary.
The namespace reproduced the timeout in isolation.

## Evidence

Exact-var bisection isolated
`completed-reload-ensures-before-publication-and-rehosting`. The reusable
boot-projection change made `seon.runtime.admission/publish-committed!`
multi-arity, but this test still replaced it with a single-arity anonymous
function. The compiled zero-arity call looked for the missing generated
`cljs$core$IFn$_invoke$arity$0` property and threw a `TypeError`.

`shadow-build-notify!` correctly converted that background publication failure
to `admission/mark-unavailable!`. The test recorded `:unavailable` but resolved
its Promise only from the success-only `lifecycle/resume!` stub, so its
`done` continuation was unreachable.

## Owner

`test/seon/client_initialization_test.cljs` owns the reload lifecycle fixture.

## Acceptance

- The publication stub preserves both production arities.
- The async fixture settles and reports both rehost and unavailable outcomes.
- The exact reload test and complete namespace emit honest final summaries.
- The full `bin/test-cljs` checkpoint emits its final summary within the
  existing cap.

## Resolution

The fixture now routes both publication arities through one local stub and
resolves one reload latch from both the rehost and unavailable rails. The exact
regression passes with one test and five assertions; a future publication
failure becomes an immediate assertion with the captured failure data instead
of a suite hang.

The complete namespace then emitted its final summary in 34 JavaScript seconds
(21 tests, 75 assertions). The checkpoint's one full suite completed all 153
namespaces in 139 JavaScript seconds and emitted honest totals (1,590 tests,
7,853 assertions, 64 failures, 2 errors) instead of timing out. The broader
checkpoint's unrelated landed regressions remain visible in
`tmp/orchestrator/cljshang-gate.log`.
