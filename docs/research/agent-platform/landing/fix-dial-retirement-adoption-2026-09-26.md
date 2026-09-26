---
type: landing
status: committed, unadopted and untested (default is down; root runs the list below)
lane: fix-dial-retirement-adoption-opus
created: 2026-09-26
authority: tmp/orchestrator/investigation-preamble-2026-09-26.md
---

# Landing: fix-dial-retirement-adoption-opus (2026-09-26)

What exists: boot compiles the config rows from the checkout's declarations and
reconciles them onto the cluster branch. It does this before `move-to-head!`
adopts the publication (`init --dev` → `adopt-rows!`). The writer validates
every final report through Datahike's `validate-report` hook.
Approach: each owner decides for the value it owns. Config does not remove an
attribute it does not declare. The writer judges a report by the declarations
that report leaves.

## Broken first

- **Default is DOWN, and it is not the old program.** The only JVM for this root is pid 15369,
  started 18:39:33Z from HEAD 7c40396b3. Its REPL is up. Its cluster stopped
  after `:seon.source/commit-id`: there is no ctx, no web and no flow.
  The previous program was **not** resumed. `move-to-head!`
  (`script/seon/operator.clj:1356-1366`) catches only `ExceptionInfo`. This
  failure reached the client as a `java.util.concurrent.ExecutionException`
  (an edamame parse error in the reply), so rollback, termination and resume
  were all skipped. **RESET NEEDED:** root runs `bin/seon down && bin/seon start --head`
  after this commit. Owner of the operator catch: `script/seon/operator.clj` (not mine).
- **Unreadable diagnostic: the site, now fixed at its owner (root's follow-up).**
  `seon.db/transact!` wraps every refusal through `write-observation`, which
  copied the submitted transaction verbatim into
  `[:seon.error/data :seon.db.write.attempt/transaction]`. Config apply
  submitted `[[:db.fn/call #'reconcile-call projection desired request]]`, so
  the error carried the whole schema projection. Measured with `pr-str`:
  **8,110,274 chars**, at path `[:seon.error/data :seon.db.write.attempt/transaction :tx-data 0 2]`.
  The copy includes `:seon.schema.projection/compiled`, whose core.cache is
  keyed by `[::write-validator <compiled schema>]`. Compiled schemas print as
  their forms, so reading the text back fails with duplicate keys.
  Fixed in two places:
  1. The producer: `seon.config/reconcile-call` derives the projection inside
     the writer.
  2. The class: `seon.db/attempted-transaction` keeps every submitted operation
     but names each `[:db.fn/call f & args]` as `[:db.fn/call f]`, dropping
     the arguments. It is used by `write-observation` and by the write-timeout
     evidence.

  The same refusal is now its own 20,641 chars plus the named call. The
  recorder's bounded `:seon.db.write.attempt/operations` (error.clj `prepare`)
  is unchanged, and error.clj is not edited.
  Not changed: the `:seon.db.write.attempt/transaction` description in
  `resources/seon/schemas/seon.db.write.attempt.edn` still says "Actual
  submitted transaction". That is not my file. It should read "submitted
  operations; transaction functions named without their arguments".
  Regression: `seon.db-error-test/a-refused-write-names-its-transaction-function-never-its-arguments`.
- **Tests not run.** The cluster is not booted, so `bin/test-check` has no
  target. The two regressions and the static pre-commit checks are owed to
  root after the restart.
- **Load not proven.** Loading edited `seon.db`/`seon.config` into default's
  JVM would redefine default's Vars, so I did not. `clj-kondo` reports 0 errors
  over the 5 touched Clojure files, and the three new or changed contracts
  compile against the packaged projection at the REPL.
- Remaining sibling: `seon.cluster/accrete-schema-population!` writes
  declarations and schema rows in two transactions. It would still refuse a
  handed projection that retires a required key. This is not on the start/adopt
  path. cluster.clj is held.

## Defect: HEAD cannot boot after deleting a required dial

Reproduced on the live `:cluster-default` value (commit 6ab8115c, basis t 536871009), read-only:

| probe | result |
|---|---|
| `reconcile/plan` of the checkout's compiled config | `[[:db.fn/retractAttribute [:seon.config/cluster "default"] :seon.config/on-core-error]]` |
| that plan through the writer's validator (`prepared-write` + `d/with`) | refused: required key `:seon.config/on-core-error` missing, entity `#:seon.config{:cluster "default"}` (the logged refusal) |
| adoption tx (`declaration-changes` 2 ops + `schema-row-changes` 21 rows), old validator | **refused** (same message) |
| same adoption tx, validator on `carried-projection` of `:db-after` | **admitted**, 47 datoms, 63 ms |
| config desired rows with `hold-retired-attributes` | plan `[]` (converged); hold costs 5.8 ms per apply (17 rows) |
| plain one-datom write, old vs new validator | 4.4-4.7 ms vs 4.7-5.0 ms (noise); `carried-projection` of the after-value is `identical?` to the before projection (0.03 ms) |

Two causes: config wrote the checkout's rows onto an unadopted branch, and the
writer validated against the pre-write declarations. Details are in
`docs/seon/issues/deleting-a-required-dial-refuses-boot-and-adoption-on-an-existing-store.md`.
The fix is general, so the next dial deletion needs no two-step retirement.

## Changes

- `src/seon/db.clj`: `attempted-transaction` (above). `write-report-validator` is replaced by
  `validate-write-report`, which judges by the final value's declarations and
  falls back to the construction projection when that value has none. Deleted:
  the per-projection validator cache, its `declare`, and the priming call in
  `carry-connection-projection-state!`.
- `src/seon/config.clj`: `hold-retired-attributes` (new). `reconcile-call`
  derives the projection from the writer's database and no longer takes one as
  a tx argument.
- Callers converted: `test/seon/turn_work_test.clj` (validator),
  `test/seon/config_test.clj` (`reconcile-call` arity in the redef).
- Skills: datahike SKILL (the validator's name and its final-declaration rule),
  seon-context-config SKILL (deleting a dial needs no two-step retirement).
- Regressions: `seon.config-test/a-dial-the-checkout-no-longer-declares-is-left-for-adoption`,
  `seon.schema-in-place-test/a-required-key-retires-with-its-entity-schema-in-one-transaction`.
- Sizes: src +83/−30 (config +36/−7, db +47/−23); tests +84/−3.

## What root must run

1. `bin/seon down && bin/seon start --head`: expect readiness, then adoption
   retires `:seon.config/on-core-error` with its data.
2. The static pre-commit checks (contracts-compile, schema edn).
3. `--test seon.config-test/a-dial-the-checkout-no-longer-declares-is-left-for-adoption`,
   `--test seon.schema-in-place-test/a-required-key-retires-with-its-entity-schema-in-one-transaction`,
   `--ns seon.config-test`, `--ns seon.schema-in-place-test`, `--ns seon.turn-work-test`,
   `--ns seon.db-test`, `--ns seon.db-error-test`, `--ns seon.error-test`, `--test seon.operator-test/operator-reads-refusals-that-carry-vars-and-objects`.
