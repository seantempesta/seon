---
type: issue
status: superseded
severity: blocker
created: 2026-09-16
tags: [issue, database, agent, operator]
---

# Reconcile issue-family guarantees with implementation ownership

## Verdict — 2026-09-16

Superseded by the owner's explicit authorization of the publication,
source-digest and adoption hunks and approval of index-and-inspect plus
worker creation. Schema slice: `6a491f0b3`. The remaining writer invariant
and settlement are deliberately deferred and tracked in
[their owning issue](issue-test-preservation-and-settlement-need-writer-integration.md).
This closes the scope decision, not those deferred guarantees. Live worker
creation and its canonical in-process regression are recorded in the
[landing note](../../prds/steward-platform/research/issue-family-2026-09-16.md).

## Problem

The approved issue-family spec requires automatic publication, preservation
of a started issue's tests, and verified settlement. Its owned paths omit
the publication and database writer owners and explicitly prohibit the
cluster and turn owners. Implementing only the new issue API cannot enforce
these guarantees for existing database callers.

## Evidence

`src/seon/cluster.clj:1383`, `:1449` and `:1935` own source population,
digest inputs and development adoption. Live default at basis 536872692
reported source roots `["src" "test" "config/default.edn"]` and no issue
schema. `src/seon/db.clj:2810` only preflights maps and additions; a pure
live call returned nil for a retraction of an existing agent attribute.
`src/seon/plan.clj:554` writes step completion, while the protected turn
owner calls settlement at lines 2204, 3423, 3462 and 4642.

## Owner

The steward-platform design owner must reconcile scope with
`docs/prds/steward-platform/plan/issue-family-spec-2026-09-16.md` section 5.
Exact seams, reproducible forms, limitations and three priced options are
recorded in
`docs/prds/steward-platform/research/issue-family-2026-09-16.md`.

## Acceptance

Either authorize the owning seams and prove note-only publication,
non-removal through database writes, and verified automatic settlement on
the canonical harness; or explicitly narrow the guarantee and acceptance
criteria. A test exercising only additive `my.issue/tests!` does not prove
that database callers cannot remove a success criterion.
