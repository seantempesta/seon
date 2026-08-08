---
type: issue
status: open
severity: blocker
tags: [issue, schema, database, runtime, tests]
---

# Schema-key immutability refuses before the usage guard can answer

## Evidence

`c55879b73` ("Refuse divergent schema key declarations at settlement") added
`::schema-key-immutable` to `seon.cluster.run/row-tx`
(`src/seon/cluster/run.clj`): any incoming `:seon.schema/key` row whose
`:seon.schema/form` differs from the installed one refuses, unconditionally
and before the schema usage guard runs.

The usage guard is the older, finer mechanism: it refuses a schema change
only when CURRENT DATA depends on the attribute, and it names which
attributes blocked the change
(`:seon.schema/current-data-blocks-change` plus
`:seon.schema/data-attributes`). Its whole contract is that a change becomes
legal again once the depending data is retracted.

Both rules now claim the same decision, and the coarse one wins. Measured
2026-08-08 on `codex/runtime-reliability-refactor` at `2db8a4be4`:

```text
bin/test seon.schema-usage-guard-test
Ran 11 tests containing 62 assertions.
10 failures, 0 errors.
```

The failures are one class in two shapes:

- the guard's typed refusal never arrives —
  `expected :seon.schema/current-data-blocks-change, actual nil` — because
  the transaction aborted earlier with
  `{:seon.cluster.run/rule :seon.cluster.run/schema-key-immutable}`;
- a change the guard is supposed to ALLOW is refused —
  `retracted-current-data-allows-change-and-retains-history` and
  `entity-lifecycle-preserves-surviving-global-leaf-attributes` both fail
  with the same `schema-key-immutable` rule after the depending data is
  gone.

This is independent of the concurrent-definition repair committed alongside
this note: the same 10 failures reproduce with those changes stashed.

## Owner

`seon.cluster.run/row-tx`'s `:seon.schema/key` arm and the schema usage
guard in `seon.schema`. One of the two owns "may this schema form change",
not both. The archived note `c55879b73` closed
(`docs/seon/issues/archive/concurrent-divergent-schema-declarations-falsely-both-succeed.md`)
describes the concurrency hazard the new rule was meant to kill; the fix for
it should compose with the usage guard rather than pre-empt it — the
concurrency question is "did this row diverge from what the run opened on",
which is a different question from "does current data depend on this
attribute". The function-declaration arm already separates those two
questions (`definition-diverged-since-open?`); the schema arm does not.

## Acceptance

- `bin/test seon.schema-usage-guard-test` is green.
- A divergent schema form still refuses when another run changed it after
  this run's opening basis, with a face naming that divergence.
- A divergent schema form with no current data depending on it, declared by
  the run that opened on it, succeeds.
- Exactly one rule decides schema-form change legality; the other is
  deleted rather than layered.
