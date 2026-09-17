---
type: issue
status: resolved
severity: friction
created: 2026-09-17
tags: [issue, operator, test]
---

# Operator cleanup bypasses the deletion logger

Batch 122 B at `5dd6ef7cc` reports one failed logging assertion and five
nil-string errors in
`seon.operator-test/a-destructive-root-is-declared-never-inferred-from-the-working-directory`.
The raw log nevertheless contains the admission before deletion. The break
is logging custody, not deletion ordering: `c4d1be3ac` moved cleanup from
`seon.operator` into the BB/JVM state owner and replaced `store/log-deletion!`
with `println`, dropping the caller field and Timbre admission observation.
The adoption liveness commits did not introduce that replacement.

Move the existing logger and caller derivation into `seon.fs`, alongside
deletion admission, and have both store and state delegate there. The BB
state owner supplies its caller explicitly because its JVM frames otherwise
name SCI implementation functions. No cluster program loads for cleanup.
The JVM regression now checks the target paths still exist inside the log
appender, proving admission precedes the actual removal; its symlink sentinel
continues to verify containment.

The standalone BB probe observed a 6-byte target present during admission,
the explicit state caller, and successful removal afterward. Armed fast
verification passed both cleanup regressions in the admitted retry: 68 tests,
519 assertions, one unrelated schema-namespace parity failure, zero errors.
The initial attempt refused before JVM launch for missing HEAD graph; see the [review evidence](../../prds/steward-platform/research/adoption-margin-2026-09-17.md).
