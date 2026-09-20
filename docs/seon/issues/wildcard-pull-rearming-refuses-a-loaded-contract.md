---
type: issue
status: open
severity: friction
created: 2026-09-21
tags: [issue, database, instrumentation, tests]
---

# Wildcard-pull rearming refuses a loaded contract

After call-preparation's marker-only snapshot refusals were converted, fast
run `a27611812ade` executed 64 `seon.db-test` tests: zero assertion failures,
one error. The error is
`instrumented-wildcard-pull-keeps-unparsed-database-fields-ordinary`, at its
`instrument/apply!` call (`test/seon/db_test.clj:476` in that snapshot):

```text
clojure.lang.ExceptionInfo: The loaded function contract cannot compile.
seon.instrument/apply! (instrument.clj:1019)
seon.test-support/preserving-instrumentation-state (test_support.clj:1130)
```

The test has not reached its wildcard-pull assertion. This evidence does not
identify which loaded contract failed; the ordinary test report omitted the
registration error's ex-data. Do not attribute this to call preparation or
missing resources without extracting that diagnostic member.

The snapshot used HEAD plus sci-program's paths and excluded foreign dirty
`src/seon/error.clj`, `src/seon/sci/eval.clj`, and `test/seon/fn_test.clj`.
The held database/test and instrumentation owners must diagnose this boundary.
The raw iteration log is `tmp/sci-program-fast-facets.log`; the durable run
identity is above. The same run passed the previously blocked diff and
arity/component cases and all 10 program protocol tests.

Reproduce in the shared canonical harness with the orchestrator's cold gate
(or a lane's fast `--paths` run), selecting `seon.call-preparation-test`,
`seon.db-test`, `my.program-test`, `my.program-mutation-test`, and
`my.program-query-test` in that order. Preserve instrumentation around any
probe; never remove the test's arming to get past the failure.
