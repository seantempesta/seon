---
type: landing
lane: fix-digest-fixtures-opus
status: landed-unverified
date: 2026-09-25
authority: docs/prds/agent-platform/landing/lane-fix-writer-digest-2026-09-25.md (79b6d9abd)
evidence: tmp/orchestrator/verify-6ab6920a-results.txt(.d/)
---

# Lane fix-digest-fixtures (2026-09-25)

The test row helpers now build each program row from its FINAL value. `seon.test-support/revised-row` merges the
overrides into the row. It then digests the result through `program/definition-digest`, with the resolver context of
the row's namespace as stored in the database. `seon.fn` reads that context the same way, and so does the writer
(`seon.db/write-digest-error`). `program-row` and `program-fn-row` accept `overrides` as a last argument.
`revision` edits a stored row in the database. It reads the row the way the writer reads it
(`seon.db/declaration-value`), merges the overrides (sets are unioned, as an upsert accretes them) and returns the
partial edit with the digest of the final value. The writer was not changed.

## Broken or unproven first

- **Nothing here has run.** Under the load rule, the only checks were clj-kondo (0 new errors in every touched file)
  and read-only probes in the throwaway namespace `fix-digest-fixtures-scratch` on default (pid 35446). The probes used
  `d/with` values and called the writer's own `write-digest-error`. Nothing was transacted into default, and
  test_support.clj was not reloaded there. Root's runs below are the first proof.
- **Three production defects remain. They are not fixture defects, and converting their fixtures would hide them:**
  - **P1: result attributes are digested.** A result write on a test declaration is refused. Read-only probe:
    `[:db/add [:seon.test/sym seon.id-test/data-shape-and-explicit-length-determine-identity] :seon.test/pass-count 7]`
    refuses. `seon.test.runner/record-tx` writes `:seon.test/failures`, `:seon.test/reach` and the counts onto test
    declaration entities, so recording is refused too.
    - Tests that stay red: most of `seon.test-failure-facts-test` (`record-tx` at :462), the `:seon.test/pass-count`
      write in `seon.test.admission-digest-test` (the test asserts "a result write is no program write"), and
      `seon.test.reach-test/result-only-writes-do-not-read-the-reachable-program-again`.
    - Owner: `definition-digest-excluded-attributes` in `src/seon/program.cljc` (or the partition that entity schemas
      declare), or the writer check.
  - **P2: runtime digests use a resolver context other than the stored one.**
    - `seon.fn/analyze-form` digests with the analysis `ns` context, widened by referenced namespaces
      (`src/seon/fn.clj:823`).
    - SCI `definition-rows` digests with the SCI context's binding rows (`src/seon/sci/eval.clj:496`, `:3822`).
    - The writer uses the stored namespace, so rows that agents settle are refused.
    - Tests that stay red: `seon.fn-test/fixture-observations-survive-static-and-runtime-admission` (the SCI rows),
      `seon.test.one-request-test` (3, through `define-agent-test!` on `sci.eval/evaluate`) and `seon.turn-test`
      (`batch-settlement-…`, `unresolved-test-subjects-…` and `settlement-refuses-a-divergent-…`, all through
      `turn/receipt-settle-*`).
  - **P3: indexer seed components.**
    `seon.fn.seed-admission-test/seed-entries-of-all-three-forms-are-admitted-and-stored-in-order` writes
    `seon.fn/rows` output through `reconcile-tx` and is refused. This suggests the indexer digests `:seon.test/seed`
    components in a different shape from the one the writer reconstructs. Not probed.
  - No issue paths are owned here. Root files one issue per class.
- **Files held by render-dispatch-opus were not edited.** The exact changes are listed at the end.
- **`seon.fn/stored-namespace-context` and `seon.db/declaration-value` are private.** test_support reads them through
  their Vars (`@#'…`), as the writer does with the first. Making both public is their owners' call.
- RESET NEEDED: none.

## Evidence (read-only, default pid 35446, `d/with` + `#'seon.db/write-digest-error`)

| case | old helper or fixture | new helper |
|---|---|---|
| synthetic `seon.effect-test/demo-owner` (stored ns: 21 requires, 19 aliases) | refused | nil |
| `(assoc (program-fn-row 'seon.db/transact!) :seon.fn/doc "changed")` | refused | `revised-row` nil |
| `{:seon.fn/sym seon.db/transact! :seon.fn/doc "x"}` datom edit | refused | `revision` nil |
| `:db/add … :seon.test/reach seon.turn/open?` | refused | `revision` nil |
| new ns `sample.digestlane`: plain, spec override, deftest | | nil, nil, nil |
| `call-preparation-test/synthetic-population` (merged contract facts) | refused | nil |
| `shape-row-in`: doc revision, then retract doc + original digest | | nil, nil (digest moved) |

- **The stored-context read reproduces the indexer's digests.** For `seon.effect-test/test-handler`,
  `seon.db/transact!`, `seon.cluster/serve!`, `seon.id/id` and `seon.render.hiccup/escape`, the indexed digest equals
  `definition-digest` with the pulled context.
- **Timings.** Each probe returned in 3–197 ms of evaluation, the largest being `synthetic-population` under
  `call-with-projection`. `revised-row` adds one query, one pull and one `definition-digest` per row.

## Classes converted

- Synthetic rows in an existing namespace. These are fixed in the helper, with no call-site change: effect-test
  owners, instrument-test, sci eval authored rows, wake-test, publication-delta, reset-edges, and fn-test
  retired-row.
- `assoc` or `merge` onto a helper row now passes overrides in: call-preparation, db-test arity and seed,
  schema-usage-guard, fn-test (8 `analyzed-test-row` sites, output, reach, mismatch, readable-edges, prepared),
  program-mutation and sci eval.
- Stored-row datom edits (`:seon.fn/doc`, `:seon.test/reach`) now go through `revision`: db-test (7), my.program-test,
  admission-digest (2), reach-test (2) and instrument `valid?`.
- Forged or random digests become real digested changes (a doc revision): db-test `restamp!` and retained-branch,
  sci branch-execution and cluster agent-test.
- Hand-built rows with no digest now go through the helpers with overrides: bootstrap-test (3 blocks) and
  test-failure-facts (2 fixtures).
- In fn-test keyword-usage, the stub `seon.error` file resolves the alias only. Its rows no longer overwrite the
  installed namespace.

## Changes (net +6 lines)

| commit | files | + / − |
|---|---|---:|
| 210a443bb | test/seon/test_support.clj | +41 / −13 |
| e8d37c038 | test_support.clj (`revision`), test/my/program_test.clj, test/my/program_mutation_test.clj, test/seon/bootstrap_test.clj | +63 / −64 |
| 68ff27ce5 | test/seon/call_preparation_test.clj, test/seon/db_test.clj | +26 / −38 |
| 0435413f9 | test/seon/fn_test.clj | +35 / −35 |
| d3edec1cf | test/seon/cluster/agent_test.clj, test/seon/instrument_test.clj, test/seon/reset_edges_test.clj, test/seon/schema_usage_guard_test.clj, test/seon/sci/branch_execution_test.clj, test/seon/sci/eval_test.clj, test/seon/test/admission_digest_test.clj, test/seon/test/reach_test.clj, test/seon/test_failure_facts_test.clj | +34 / −43 |

Released after landing: every path above, plus this note.

## Root runs (serially, after adopting d3edec1cf)

```
bin/test-check default --ns seon.effect-test
bin/test-check default --ns seon.call-preparation-test
bin/test-check default --ns seon.db-test
bin/test-check default --ns seon.fn-test
bin/test-check default --ns seon.bootstrap-test
bin/test-check default --ns my.program-test
bin/test-check default --ns my.program-mutation-test
bin/test-check default --ns seon.instrument-test
bin/test-check default --ns seon.sci.eval-test
bin/test-check default --ns seon.sci.branch-execution-test
bin/test-check default --ns seon.reset-edges-test
bin/test-check default --ns seon.schema-usage-guard-test
bin/test-check default --ns seon.cluster.agent-test
bin/test-check default --ns seon.cluster.wake-test
bin/test-check default --ns seon.cluster.publication-delta-test
bin/test-check default --ns seon.test.admission-digest-test
bin/test-check default --ns seon.test.reach-test
bin/test-check default --ns seon.test-failure-facts-test
```

Expected: the digest class is gone except for P1, P2 and P3 above.

## Exact changes for held files (render-dispatch-opus; harness-s2b-c not released)

- **`test/seon/program_test.clj`.**
  - At :463, :742, :750 and :844, change `(merge (test-support/program-fn-row DB SYM SRC) M …)` to
    `(test-support/program-fn-row DB SYM SRC (merge M …))`. At :742/:750, `parsed-contract` joins the overrides map.
  - The remaining reds (`sample/redefined`, `sample/unmeasured`, `my.agent-test/held-branch`,
    `my.agents.registration-test/same-name`) are either existing-namespace synthetic rows, which the helper fixes, or
    datom edits. Convert those edits to `(test-support/revision DB [:seon.fn/sym SYM] {ATTR V})`.
- **`test/seon/cluster_test.clj`.** At about :415, `restamp!` writes a random digest. Change it to
  `(test-support/revision (db/db connection) [:seon.fn/sym sym] {:seon.fn/doc (str "Restamped " (random-uuid))})`,
  as db_test now does.
- **`test/seon/render/entity_pairs_test.clj`.**
  - At :26, change `(assoc (support/program-row DB [:seon.test/sym …] SRC) :seon.fn/calls #{…})` to
    `(support/program-row DB [:seon.test/sym …] SRC {:seon.fn/calls #{…}})`.
  - The viewer rows are synthetic rows in an agent namespace, which the helper fixes.
- **`test/seon/render/retained_test.clj`.** At :38, change the `:db/add … namespace-ai` datom to
  `(test-support/revision DB [:seon.fn/sym 'seon.render-simplification.fixture-a/namespace-ai] {ATTR V})`.
- **`test/seon/test/seed_preparation_test.clj`.** At :71, `source-rows` is given a hand namespace row without the
  stored requires. Pass `(mapv #(support/revised-row database % {}) rows)` to `reconcile-tx`, so the harness's
  by-name seed refusal is reached instead of the writer's.
