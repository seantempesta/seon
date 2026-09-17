---
type: research
status: landed-or-open
created: 2026-09-18
tags: [plan, fixtures, refused-read]
---

# `my.plan` after the reset

## Scope and authorities

This bounded lane read `AGENTS.md` sections 0–5,
`.agents/skills/clojure-testing/SKILL.md`,
`.agents/skills/data-modeling/SKILL.md`,
`.agents/skills/datahike/SKILL.md`, the
[critical-findings triage row 7](critical-findings-triage-2026-09-17.md),
and program-facts rulings 1o/1q end to end for the requested scope. The
Datahike transaction-data reference grammar is the authority for the fixture:
an int, tempid, lookup ref, ident, or nested entity map enters a transaction;
stored datoms contain the entity id; pulls return maps.

No new plan/read error facet was declared. The additive manifest at
`error-declaration-manifest-slice1` declares `:my.plan/error`, but no distinct
plan/read facet. These functions therefore enumerate the already-declared
`:seon.db/invalid-read-error` and
`:seon.schema/missing-projection-error` where the read result is narrow, and
the public pass-through reads retain `:seon.error/value`. A constructor-group
follow-up may replace the generic spelling only when that group lands the
facet and its constructors together. Boundary errors remain values; this lane
added no durable error row.

## Root causes and fixes

1. **Retained-agent fixture — fixture defect.**
   `the-plan-component-holds-objective-tree-and-current-step` retracted the
   agent to prove component cascade. Agent identities are retained after the
   reset, so the writer correctly refused the fixture. The regression now
   retracts the plan component itself, proves its steps cascade, and separately
   proves the agent identity survives
   ([test/my/plan_test.clj:597](../../../../test/my/plan_test.clj)). Commit:
   `dcb1959d5` (5 added lines, 1 removed line).

2. **Juniper component fixture — fixture defect.**
   The nested plan omitted its required `:my.plan/agent` relation. The fixture
   now supplies the lookup ref `[:seon.agent/id "juniper"]`; its same-transaction
   current-step and needs links remain tempids, which is the transaction-data
   grammar rather than a pulled-ref shape
   ([test/my/plan_test.clj:546](../../../../test/my/plan_test.clj),
   [my.plan.edn:9](../../../../resources/seon/schemas/my.plan.edn)). Commit:
   `049121590` (2 added lines, 1 removed line). The declaration was correct and
   was not changed.

3. **Ready subjects and two-agent rendering — production symbol defect.**
   `subject-eid` converted a qualified symbol to a string before querying the
   now-symbol-valued `:seon.fn/sym`. The lookup therefore returned no row and
   `resolve-subject!` fed its refusal downstream. It now queries with the symbol
   itself, and `ready-subjects` returns a refused read verbatim. The same repair
   restores the ready rows and string result in the second-agent SCI regression
   ([src/seon/plan.clj:157](../../../../src/seon/plan.clj),
   [test/my/plan_test.clj:411](../../../../test/my/plan_test.clj)).

4. **Ownership and eid reads — production fail-open defect (triage row 7).**
   The four eid readers could return a truthy error map. `owned-step-eid!`
   interpreted one as ownership, `next-position` coerced one with `long`, and
   writer compilation could place one where an entity id belonged. The readers
   now explicitly enumerate ordinary ids/nil plus the two read-error shapes;
   `ref-eid` preserves an error returned by `db/entity`; and `read-result!`
   uses `seon.error/error?` to refuse verbatim before ownership, arithmetic, or
   transaction-data construction
   ([src/seon/plan.clj:112](../../../../src/seon/plan.clj),
   [src/seon/plan.clj:531](../../../../src/seon/plan.clj),
   [src/seon/plan.clj:551](../../../../src/seon/plan.clj)). The canonical
   regression obtains a real invalid-read value from an uninstalled attribute
   and proves both ownership and position refuse with that exact value
   ([test/my/plan_test.clj:426](../../../../test/my/plan_test.clj)). Commit:
   `4bcb6acc9` (146 added lines, 64 removed lines across two files).

## Verification

Final owned files measure 67,678 bytes for `src/seon/plan.clj` and 29,380
bytes for `test/my/plan_test.clj` (97,058 bytes total). `git diff --check`
passed before the commits.

The final isolated iteration was:

```text
bin/test-fast --paths src/seon/plan.clj src/my/plan.clj test/my/plan_test.clj resources/seon/schemas/my.plan.edn -- my.plan-test
Ran 22 tests containing 125 assertions.
0 failures, 0 errors.
```

The requested `seon.plan-test` namespace does not exist; the first invocation
proved that boundary with `Could not locate seon/plan_test`, so subsequent
runs dropped it as instructed. The green snapshot armed 1,202 contracts. This
is fast iteration evidence only; the orchestrator still owes the cold gate and
platform proof.

The Seon runtime MCP tools were unavailable in this lane, so no live JVM/SCI
probe or adoption claim is made. The default cluster was observed alive and
was not operated. Foreign edits in `src/seon/test.clj`,
`src/seon/test/runner.clj`, `test/seon/cluster/source_test.clj`,
`test/seon/test/selection_test.clj`, and
`resources/seon/schemas/seon.test.selection.edn` were excluded by the
HEAD-plus-owned-path snapshot and preserved.
