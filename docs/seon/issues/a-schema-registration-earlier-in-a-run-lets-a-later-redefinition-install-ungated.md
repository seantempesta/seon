---
type: issue
status: open
severity: blocker
created: 2026-09-26
tags: [issue, gate, test, schema, dogfood]
---

# A schema registration earlier in a run lets a later redefinition install without its gate

Found by dogfood lane dogfood-author-opus through MCP only (branch `dogfood-author-opus-2`, default pid 27723,
HEAD 6a8e46ec6).

## Observed

The test `notes-index-test/add-note-then-tagged-finds-it` was defined in an **earlier** run, and
`(my.program/tests-reaching {:seon.program/subject 'notes-index/tagged})` answered
`:seon.program/gating [notes-index-test/add-note-then-tagged-finds-it]`. The test calls
`(notes-index/tagged :probe)`.

1. A run with only `(in-ns 'notes-index)` and a `defn tagged` that changes the argument from a keyword to a
   request map was **refused**:
   `Gate: 1 tests · 0 passed · 1 failed · auto-check 25/25 · install refused … notes-index/tagged refused
   argument count …`. Correct.
2. A run with `(seon.schema/register! :notes-index/tag :keyword)`, then `(in-ns 'notes-index)`, then the same
   kind of breaking `defn tagged` (request map `[:map [:seon.db/db …] [:notes-index/tag …]]`) answered
   `#'notes-index/tagged`. There was no gate report and no advisory. In the next run
   `(notes-index/tagged :probe)` refused with "expected a map … got a keyword :probe", so the gating test was
   broken.
3. Re-evaluating the same `defn` (docstring changed) in a run **without** `register!` was refused by the same
   test (`Gate: 1 tests · 0 passed · 1 failed`).

Steps 2 and 3 differ only in the `register!` form, so it discriminates.

A likely earlier sighting of the same class: on default pid 19859, after the JVM restart, a bad redefinition
of `normalize-tags` (dropping lower-casing) installed silently although two tests gated it. That run had no
`register!`, but the branch's test worlds were failing at the time (see
[my-test-check-fails-every-test-once-a-branch-contract-names-an-agent-schema](my-test-check-fails-every-test-once-a-branch-contract-names-an-agent-schema.md)).
A gate that cannot build its world may be admitting instead of refusing.

## Expected

Every redefinition runs its gating tests, whatever else the run contains. A gate that cannot run reports so
and refuses. It never looks like a silent pass. A passing gate and a skipped gate currently print the same
thing (`#'ns/name`), so the agent cannot tell them apart.

## Model that is wrong

The registration delta a `register!` opens changes which path, or which world, the following `defn` is
admitted through, and that path does not gate. Related class:
[a-redefinition-in-the-same-run-as-its-test-installs-ungated](a-redefinition-in-the-same-run-as-its-test-installs-ungated.md)
(earlier forms of a run change what the gate sees).
