# Lane deletion-caller-edge — 2026-09-23

Defect: `docs/seon/issues/incremental-publication-refuses-a-deletion-whose-unchanged-caller-edge-survives.md`.

## Commits

- `f31074521` Recompute a removed definition's unchanged callers during incremental population
  (`src/seon/fn.clj`, `test/seon/fn/incremental_deletion_test.clj`).
- `4135c518a` Compare the regression's pulled calls as a set (test only).
- this note, the issue update and
  `docs/seon/issues/a-collection-bound-edge-clause-costs-half-a-second-per-read.md`.

## Cause (REPL, default pid 51528, read-only)

- `rg render-diff-ai src resources` → no match; the only remaining mention is
  `test/seon/schema/datahike_parity.edn` (a dated baseline).
- Default stores `seon.render/invoke-selected` with 112 `:seon.fn/calls`, including
  `seon.db/render-diff-ai` (entity 3075 still present; caller entity 4903).
- `render.clj` has no commit after f1e55a824; the edge came from the declared
  invocation `{:seon.fn/invokes #{:seon.render/ai …}}` joined to the schema form
  `{:seon.render/ai seon.db/render-diff-ai}` that f1e55a824 removed from
  `seon.db.diff.edn`. Neither macro nor stale base: an unchanged caller whose edge
  derives from another file.
- Loaded `seon.fn/analyze-rows` of `src/seon/render.clj` alone against default:
  `{:stored 112 :fresh 16}` (96 declared edges lost), 10146 ms with a cold
  disposable kondo cache.

## Fix

`analyzed-files` → `removed-definition-caller-paths` (two AVET reads over
`:seon.fn/calls`/`:seon.fn/references` + a file join, proportional to the removed
definitions' referrers) and re-analysis of those files in the same population;
`index!` → `reconciled-paths` (selected ∪ files whose rows are supplied) for both
removal identities and the post-commit findings pass; `analyzed-artifacts` adds the
invokers' declared targets from the whole declaration population to the
database-known set. The deletion guard (`seon.db/removed-definition-error`) is unchanged.

## Evidence

HEAD `seon.fn` was loaded into default as the disposable namespace
`tmp.deletion-caller-edge-probe` (fn.clj text with the ns renamed; removed afterwards,
`find-ns` → false). Default's own `seon.fn` was NOT reloaded.

| probe | result | ms |
|---|---|---|
| probe `analyze-rows` render.clj alone | `{:stored 112 :fresh 112 :lost [] :gained []}` | 1421 |
| probe `analyze-rows` `db.clj` + `seon.db.diff.edn` + `seon.db.edn` vs default db | files `[db.clj render.clj db_test.clj]`; `invoke-selected` 111 calls, none names `render-diff-ai`; no row names it | 12633 cold, 1609 warm |
| caller read, Datalog `[?caller ?attribute ?symbol]` | `["src/seon/render.clj" "test/seon/db_test.clj"]` | 550.9 |
| caller read, AVET datoms + join (shipped) | same paths | 1.3 |

Branch regression (`tmp/deletion-caller-edge/branch-probe.clj`): each case branches
default (`registry/branch!`), publishes a sample root, deletes `sample.callee/produce`,
publishes `callee.clj` incrementally, then releases and `retire-branch!`es (roster
checked empty afterwards).

| seon.fn | declared-only caller | literal caller |
|---|---|---|
| loaded (old) | REFUSED: surviving referrer `sample.caller/run` → `sample.callee/produce` (3495 ms) | refused (3447 ms) |
| HEAD probe | COMMITTED, 4 operations; produce gone; caller edge gone (3971 ms) | REFUSED: `literal.clj:2:15 :unresolved-var Unresolved var: sample.callee/produce` (1729 ms) |

The literal caller refuses at the analyzer's blocking finding before the guard; it
names the removed definition either way.

## Verification limits

- `bin/test-fast --paths src/seon/fn.clj test/seon/fn/incremental_deletion_test.clj --
  seon.fn.incremental-deletion-test` (run `97154398d99a`, 24.3 s wall): seon.fn loaded
  and armed; the test ERRORED in fixture setup, "An identity-bearing entity schema must
  declare its partition" (`:my.note/note`) — the stale published base this defect
  blocks. The regression has NOT run green in the armed runner.
- `bin/test --prepare-head-base` (once, after `f31074521`, 37.2 s wall): FAILED with
  the ORIGINAL refusal (`invoke-selected` → `render-diff-ai`, db/id 4903). Cause:
  `seon.test.cache/prepare-base!` asks the root's live publisher, i.e. default pid
  51528, which runs its loaded pre-fix `seon.fn` (`resolve
  'seon.fn/removed-definition-caller-paths` → nil). Not a resources/ hunk failure.
- RELOAD NEEDED: default must reload `seon.fn` (orchestrator), then
  `bin/test --prepare-head-base` and the regression run once.

## Timings over 1 s

| operation | wall ms | note |
|---|---|---|
| bin/test-fast focused run | 24260 | snapshot 3 s; fixture refused; defect class of a-focused-test-jvm-spends-twenty-seconds-before-its-first-test.md |
| bin/test --prepare-head-base | 37190 | refused in development reconciliation transaction |
| analyze-rows render.clj, cold disposable kondo cache (old seon.fn) | 10146 | cold cache created by the probe |
| analyze-rows deletion selection, first call | 12633 | warm repeat 1609 |
| analyze-rows db.clj only (warm) | 3207 | `assert-capability-contracts!` 3213 ms, `file-rows` 756 ms: new issue a-collection-bound-edge-clause-costs-half-a-second-per-read.md |
| sample incremental population (both seon.fn versions) | 1729–3971 | same capability walk |

## Follow-up: review §B and schedule #5/#7 (commit `6699de97a`)

Review: `docs/research/agent-platform/review-validator-deletion-oversight-2026-09-23.md` §B.
Paths: `src/seon/fn.clj`, `test/seon/fn/incremental_deletion_test.clj`.

Changes in `seon.fn`:
- `population-targets` (one walk of the declaration population; `{}` when no attribute
  is invoked) and `program-targets` (restricts to the batch's functions plus the ones
  the database holds, one `:seon.fn/sym` query). `analysis-rows-by-file` now takes those
  targets; `analyzed-artifacts`, `build-artifact` and `source-rows` compute them once
  per batch. The earlier `mentioned` widening is gone.
- `analyzed-artifacts` 8-arity `seed`: the caller batch knows the selected batch's
  definitions (P1 replacement).
- `invoker-paths` + `dependent-paths`: callers of removed definitions plus stored
  invokers (`:seon.fn/invokes`) of attributes whose population targets changed (#7).
- `analysis-projection`: one derivation of the analysis declaration world, used by
  `analyzed-files` and `reconciled-paths`.
- `reconciled-paths` derives dependents at `index!` from the previous database, the
  selection and the supplied definitions; a dependent the supplied rows do not cover
  refuses (`:seon.fn/missing-paths`); a supplied file row no longer widens (P1 file row).

Premise check at the REPL (default pid 43581, HEAD `seon.fn` = f31074521 vs the working
file loaded as the disposable namespace `tmp.deletion-caller-edge-probe`, each case on
its own branch of default, retired afterwards; roster and sample roots checked empty;
probe `tmp/deletion-caller-edge/branch-probe-2.clj`, 75.1 s for 10 cases):

| case | HEAD f31074521 | working fix |
|---|---|---|
| declared-only deletion | commits, edge gone | commits, edge gone |
| literal caller | refused, names `<p>.callee/produce` | same |
| replacement retarget (P1) | caller calls `[]`, replacement LOST | `[<p>.callee/replacement]` |
| schema adds target `kept` (#7) | caller `[produce]`, addition LOST | `[produce kept]` |
| supplied file row for other.clj (P1) | `stays` RETRACTED | `stays` kept, 2 operations |
| `source-rows` invoker of `:seon.render/ai` (#7) | 0 calls, 29 ms | 50 calls, 22 ms |
| `analyze-rows` render.clj alone vs default | — | 111/111 edges kept, 2020 ms (shared kondo cache; render ns forgotten = one miss by design) |
| whole-schema target walk (P2), 3326 forms, 8 attributes | 8.4 ms | skipped when nothing is invoked |

First probe attempt found a real hazard, not a defect of the fix: the committed test
file's literal sample symbols are stored `:seon.fn/references` in default's program
(`seon.fn.incremental-deletion-test/delete-callee!` etc.), so deleting a fixed
`sample.callee/produce` in any fixture branched from the published program is
correctly refused by the guard. The tests now name per-run namespaces.

### Verification limits
- The committed regressions have NOT run in the armed runner: `bin/test` exits at
  `bin/_test-slot: No such file or directory`, and `bin/test-fast` is absent — both are
  uncommitted deletions of lane realities-commit-5 (its ledger paths). Run once it lands:
  `bin/test --paths src/seon/fn.clj test/seon/fn/incremental_deletion_test.clj -- seon.fn.incremental-deletion-test`.
- HEAD load: the committed fn.clj text compiled in default (probe namespace load 220 ms);
  no fresh-JVM load. `clj-kondo` 0 errors on both files.
- Default was NOT reloaded by this lane; RELOAD NEEDED for `seon.fn` to publish with the fix.

### Timings over 1 s (follow-up)

| operation | wall ms | breakdown |
|---|---|---|
| probe, 10 branch cases | 75128 | per case 3.0–9.5 s, population 1.7–8.0 s |
| instrumented 5-case run | 42823 | `file-rows` 9455, `reconcile-tx-in` 8857, `report-identities` 4919, `assert-capability-contracts!` 3402, `caller-files` 912, `analyzed-artifacts` 503, `population-targets` 317, `dependent-paths` 257, `reconciled-paths` 88 |
| analyze-rows render.clj | 2020 | |

Every population over 1 s is a DEFECT (publication cost #16), dominated by pre-existing
readers, not by this change (new helpers total 662 ms over 5 cases). `file-identities`
binds `?attribute` from a collection in `[?entity ?attribute ?value]`, the same shape
as the 550 ms clause in `a-collection-bound-edge-clause-costs-half-a-second-per-read.md`.
No cache was added. Kondo: shared cache, forgotten namespaces re-analyzed (misses) only.
