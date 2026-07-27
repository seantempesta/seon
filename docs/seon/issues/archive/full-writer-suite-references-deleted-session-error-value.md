---
type: issue
status: superseded
severity: friction
tags: [issue, test, database, runtime]
---

# Remove the deleted session error constructor from the writer suite

## Problem

The complete `bin/test-writer` runner cannot compile
`seon.host-eval-wire-safety-writer-test` because the test still calls deleted
`seon.db.session/error-value`.

## Evidence

On commit `c03ff91eb`, after the performance-gate owning suites passed,
`bin/test-writer` stopped during namespace compilation:

```text
Syntax error compiling at
(seon/host_eval_wire_safety_writer_test.clj:49:20).
No such var: session/error-value

```

The same stale call also survives in `seon.host.eval` and
`seon.host.invoke`. This is old host/session deletion residue, independent of
the JVM turn-attribution change.

## Owner

The great-deletion host/session slice owns removing the obsolete constructor
and expressing the surviving flat error value directly at its real owner.

## Acceptance

- No source or test namespace references `seon.db.session/error-value`.
- The wire-safety regression preserves its flat error-value assertion against
  the surviving constructor/data shape.
- `bin/test-writer` loads every selected namespace and reaches test execution.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
