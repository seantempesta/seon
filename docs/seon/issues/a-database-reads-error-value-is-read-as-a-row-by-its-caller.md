---
type: issue
status: open
severity: blocker
tags: [db, pull, error-model, class-kill, class/absence-as-health]
created: 2026-09-17
---

# A database read's error value is read as a row by its caller

## The class (three instances in one night, 2026-09-17)

`seon.db/pull` (and `q`) return a flat `:seon.error` VALUE when the read
fails; that value is a map, and every caller that binds the result as
"the row" (or takes `(:db/id …)` of it) reads the refusal as data:

1. `seon.config/effective-in` — read the error as the config row; reported
   68 "missing" facts (resolved in `761408a17`).
2. `seon.config/population-transaction-data` — read it as "no such entity"
   and minted a tempid for an existing identity → conflicting upsert
   (resolved in `761408a17`).
3. `seon.test.runner/record-tx` — read it as a previous run with different
   provenance → `:seon.test.run/immutable` refused every cold gate's
   recording (resolved in `e58a27c86`).

The trigger each time was one intermediate hot-reload (`total-pull-arguments`
returning a seq) that made every pull fail; the class is that the failure
was invisible at every reader.

## Why patching callers one at a time is the defect

`seon.db/pull`'s contract is `[:or :nil :map :seon.error/value]`; the map
and the error arms are indistinguishable to a caller without an explicit
`error-value?` check, and nothing forces the check. Every new caller
repeats the mistake.

## Options for the owner (design gate; the orchestrator recommends 1)

1. **Internal reads throw, boundary reads return values.** `seon.db/pull`
   and `q` throw the flat error (as `ex-info` carrying the same value) when
   called from system code, and the agent-facing boundary (`my.*`, SCI
   evaluation, the render seams) is where a thrown read becomes the flat
   value the agent sees — one conversion at the boundary, none in the
   middle. Guarantee: a failed read can never be mistaken for a row.
   Cost: one sweep of internal callers that already check `error-value?`
   (they become simpler); the SCI/`my.*` boundary gets one catch.
2. **Keep values; add a checker.** A lint/program-graph check refuses any
   caller that destructures a `seon.db/pull` result without an
   `error-value?` branch. Guarantee: drift is caught at publication. Cost:
   the checker, and every caller still carries the branch.
3. **Keep values; a `pull-row` arity that throws.** Callers choose. Cost:
   two paths for one read (the thing 2.5 forbids); the trap remains.

## Regression (whichever option)

A pull made to fail (an error value handed as the database) reaching
`seon.config/effective`, `population-transaction-data` and `record-tx`
produces the read's error at each, never a "missing facts", a tempid, or an
"immutable" verdict — one class regression, three seams.
