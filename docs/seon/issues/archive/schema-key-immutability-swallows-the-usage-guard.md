---
type: issue
status: resolved
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
2026-08-08 on `codex/runtime-reliability-refactor` at `5e716e371`:

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
(`concurrent-divergent-schema-declarations-falsely-both-succeed.md`)
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

## Resolution

`::schema-key-immutable` is DELETED. Both declaration families now enter one
decision path in `row-tx`, in the order the two questions actually differ:

1. **Did the installed row diverge from the basis this run opened on?** This
   is the concurrency question and it is family-independent, so
   `definition-diverged-since-open?`/`definition-written-by-run?` generalized
   into `declaration-diverged-since-open?`/`declaration-written-by-run?`,
   parameterized by the identity attribute. `:seon.schema/key` now asks it
   exactly as `:seon.fn/sym` does, and a losing concurrent declaration
   refuses with `::program-row-changed-after-open` — the face that names
   divergence, not immutability. `declaration-written-by-run?` asks the
   question of the terminal TRANSACTION rather than of one family's content
   attribute, so there is no per-family attribute to forget.
2. **Does current data depend on the affected attributes?** The usage guard
   (`assert-schema-data-unused!`) answers, and its typed refusal —
   `:seon.schema/current-data-blocks-change` with
   `:seon.schema/data-attributes` — reaches the caller. A legal change again
   builds its Datahike diff through `schema-attribute-change-tx`.

This is the 1009d5889 pattern applied one arm over: an unmeasured claim
("there might be a consumer") replaced by a measurement ("these attributes
carry current data").

## Acceptance evidence

- `bin/test seon.schema-usage-guard-test` — 12 tests, 69 assertions, 0
  failures, run three times consecutively (2026-08-08).
- The class regression is
  `seon.schema-usage-guard-test/one-decision-path-answers-every-schema-form-change`:
  one run walks all three answers — the guard's typed refusal with its named
  attributes, the same change succeeding after retraction, and a form another
  writer changed since the run opened refusing as
  `:seon.cluster.run/program-row-changed-after-open`. The coarse rule cannot
  be reintroduced without failing it.
- `bin/test seon.cluster.run-test seon.program-test` — 32 tests, 182
  assertions, 0 failures; 1009d5889's
  `opening-basis-divergence-is-only-claimed-when-it-is-measurable` still
  passes against the generalized functions.
- `bin/test --platform` — 69 tests, 369 assertions, 0 failures.
- `bin/test seon.cluster.turn-test` — 16 failures / 3 errors across five
  tests, IDENTICAL to the same run with these two files restored to
  `5e716e371` (measured both ways, 2026-08-08). Those five are foreign
  breakage, not this change.

## Residual owner question

One case genuinely changed behavior, and it needs an owner ruling rather
than a lane's judgment:
[within-run-schema-key-refinement-needs-an-owner-ruling](../within-run-schema-key-refinement-needs-an-owner-ruling.md).
