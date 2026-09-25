---
type: landing
status: 8 code commits plus an issue, code only under the load rule; nothing run. DRIFT deferred by root; N3 needs no source fix here
lane: fix-harness-fallout-opus
created: 2026-09-25
authority: docs/research/agent-platform/red-baseline-census-2026-09-25d.md (R0, N1, N3, N4, N5, N6, DRIFT; root's CFG and private-definition follow-ups and rulings)
---

# Lane fix-harness-fallout (2026-09-25)

## Broken or unproven first

- **Nothing here has run** (load rule). clj-kondo reports 0 errors on every touched file. Every probe was a read-only
  JVM form in the throwaway namespace `fix-harness-scratch` on default (pid 35446). Root's runs below are the first
  proof.
- **CFG touches boot.** `cluster/source-base!` now takes the booting cluster's name. `boot.clj` passes it at
  :198 and :643, and passes `cluster-name` to `projection-state` at :237 and :250. A boot or `init` regression would
  name `projection-state` or `source-base!`. Root proves the platform and boot path.
- **CFG leaves a two-arity `projection-state`, deliberately.** Ten callers I do not hold still use it:
  `cluster/source_test.clj` ×2, `error_test.clj` ×1 and `sci/branch_execution_test.clj` ×7. It no longer invents a
  name. It takes the cluster the world names (`carried-cluster-name`):
  1. the environment the connection or database value carries;
  2. else the database's one cluster row;
  3. else, for the published `:current-src` branch, which holds no cluster, that branch's name.

  Anything else refuses by name. A caller whose raw `@connection` world holds two or more clusters and no carried
  environment now refuses, where before it silently got `"current-src"`. That is the intended loud failure. If one
  of those ten goes red on it, the fix is to pass the name.
- **N4 test conversion may meet the analyzer.** `non-evaluable-agent-source…` now analyzes
  `(defn valid? [length id] (s3.missing/function length id))` through `program-fn-row`. If the analyzer refuses an
  unresolved namespace, that test needs a resolvable non-evaluable form instead.
- **N5 cost is high.** The armed profile since 14:24Z records `seon.test/attempt-request-context` at 117 calls,
  31,585 ms total, a mean of **270 ms** and a max of 363 ms. The two tx builders cost microseconds (`creation-tx`
  9 µs, `open-tx` 4 µs), so the time is the two checked writes plus `config/effective`.
  - Across the 465 tests that reach `effect/request*`, that is about **126 s per full pass**.
  - It is about 21% of `bounded-result`'s mean of 1,270 ms (427 calls).
  - It is under one second per member but it is whole-reach work, so it needs an owner decision: narrow the reach
    (refusal paths) or batch the two writes into one transaction. Root rules. No issue has been filed.
- **DRIFT deferred (root).** The guard `ambient-snapshot`/`ambient-drift` (runner.clj:604–690) compares process-wide
  state before and after a member. Another thread's first load and arming (`seon.contracts-compile-test/check`
  appeared during an error-test member) therefore reads as this member's leak. It needs per-member ownership of
  arming before it can tell the two apart. Unchanged.
- **N3: no source fix is owed here.** By reading, the world overlay (test.clj:1544–1760) uses only database values;
  test.clj has no `packaged-forms` or `io/resource` call.
  - contracts-compile ×2 read the checkout by design (`packaged-projection`, contracts_compile_test.clj:244), so the
    verdict is RERUN on a clean tree.
  - turn-test ×2 and edit-test ×2 go through the authored-resource fallbacks `seon.program/shapes`/`authored-shapes`
    (program.cljc:276–304), `identity-attributes` (:25) and `seon.fn/declaration-forms` (fn.clj:1490–1502). That is
    the open issue `docs/seon/issues/live-resources-outrun-the-loaded-program-identity-list.md`.
- **N1 attribution corrected.** S4a did not cause the three members with surviving text. Run `000b6410d752` records
  the same refusal at 2026-09-24T20:28:31Z; S4a (db7f1c919) landed at 02:15:43Z. Those members nest two
  `with-database` calls and write the outer connection inside the inner custody. `my.catalog/reads-canned-label`'s
  text was lost with its fixture branch.
- **N4 attribution corrected.** The tests' overrides kept the canonical row's stored digest. Probe: the admitted row
  digests to `c29f0e66…`, equal to the stored one; recomputed, it is `302120263…`. Every digest comparison read
  "unchanged". `base-context-bindings…` was already red at 00:59Z, before S4b (02:53Z). The writer defect is filed:
  `docs/seon/issues/the-writer-admits-a-definition-digest-its-row-does-not-produce.md`. It is in db.clj, not held,
  so root routes it.
- RESET NEEDED: none.

## Changes

| commit | files | + / − | what |
|---|---|---:|---|
| 9ebdc9cd4 | `src/seon/test.clj` | +17 / −6 | **R0.** `arm-first-loads!` compares against, and arms with, the program `sci.eval/loaded-identity` names now (`source/database` of that commit, from the ctx's store), never the ctx snapshot's `::loaded-database`, which stays behind a later adoption. An identity naming no commit is the booted program; an unknown one answers `:seon.test/not-runnable-error`, now in the contracts of `arm-first-loads!` and `run`. |
| 829d6909d | `test/seon/test/clean_world_test.clj` | +19 / −4 | **N5.** The member reaches `request*` through `boot/connection`'s refusal path. Its world holds exactly its attempt agent and turn, named from `effect/*request-context*`; their two transactions are excluded from the added-entities check. |
| 50b0e82bc | `test/seon/test/seed_preparation_test.clj` | +7 / −6 | **N6.** Seeds write the installed, identity-less `:seon.test/fixture-observation`. `:seon.test/skip-reason` is a map member and is installed nowhere. |
| 9f82663e1 | `src/seon/sci/eval.clj` | +7 / −2 | **Private-definition ruling.** `install-function-from-database!` binds an unchanged row's compiled root only when the ctx's SCI namespace holds no entry for the symbol. Probe: 0 of 1,306 uninstalled program symbols have an entry and 3,549 of 3,549 installed ones do, so a present entry is the ctx's own definition. |
| 4ebea3977 | `test/seon/test_support.clj`, `test/seon/config_test.clj` | +14 / −16 | **N1 (root's approved option).** `transacted!`, `apply-config!` and `seed-cluster!` write the connection they name under that connection's own custody. `two-clusters…` uses `apply-config!`. |
| 6db5b2451 | eval.clj, cluster/agent.clj, cluster/boot.clj, cluster.clj, test.clj, instrument_test, db_test, test_support | +64 / −32 | **CFG.** `projection-state [db projection cluster-name]`; callers pass their cluster: the handle's (agent.clj ×2), boot's `cluster-name` (×2), `source-base!`'s new `cluster-name` argument, `open-world!`'s new `cluster` argument, and the fixtures' named clusters. `cluster-ctx*`, the `fork-cluster-ctx` 3-arity and the `fork-for-turn` fallback take the world's own cluster (`carried-cluster-name`, above). No `"current-src"` literal remains. |
| d8b80b1c3 | `test/seon/test/first_load_arming_test.clj`, `test/seon/sci/eval_test.clj` | +49 / −7 | **Regressions.** `a-stale-acquisition-snapshot-does-not-decide-a-first-load` (R0) and `a-context-definition-survives-its-symbols-first-install` (ruling). The N4 overrides in `database-provenance…` and `non-evaluable…` now come through `program-fn-row database sym source`, so each carries its own digest. |
| 1c8938497 | `docs/seon/issues/the-writer-admits-a-definition-digest-its-row-does-not-produce.md` | +45 | The N4 writer defect, with its probe and the wanted class regression. |

Net code and tests: +177 / −73 across 16 files. The largest single change is CFG, at +64 / −32; +24 of that is
`carried-cluster-name` and the two-arity with contracts.

## Evidence (read-only probes, default pid 35446)

- **R0 staleness, live:**
  - The ctx snapshot's `::loaded-database` is commit `6ab5ec54`.
  - `loaded-identity`, the adoption record and `loaded-program(loaded-source)` are all `6ab687a9`.
  - The new read (`source/database` plus `carried-projection`) takes 44.7 ms for 2,092 contracts, and only when a
    request first loads a test namespace.
- **N5 reach:** among the clean-world test's calls, only `seon.cluster.boot/connection` is in
  `(seon.fn/reverse-closure {:seon.fn/seeds #{'seon.effect/request*}})`. The gate set has 465 tests and took 54.6 ms.
- **CFG:**
  - Default's handle environment already names `"default"` (boot overrides the name).
  - `(db/db connection)` values carry the projection state in their metadata; raw `@connection` values do not.
- **N4:** see the probe above and the issue.

## Root runs (serially, after adopting every commit above)

    bin/test-check default --ns seon.test.first-load-arming-test
    bin/test-check default --ns seon.problems-test
    bin/test-check default --ns seon.render.web-debug-test
    bin/test-check default --ns seon.schema-usage-guard-test
    bin/test-check default --ns seon.test-failure-facts-test
    bin/test-check default --ns seon.render-simplification-test
    bin/test-check default --ns seon.render.retained-test
    bin/test-check default --ns seon.test.clean-world-test
    bin/test-check default --ns seon.test.seed-preparation-test
    bin/test-check default --ns seon.sci.eval-test
    bin/test-check default --test seon.config-test/two-clusters-on-one-jvm-have-no-config-bleed
    bin/test-check default --test seon.test.effect-attempt-test/an-agent-capability-stub-settles-a-receipt-only-in-an-effect-reaching-attempt --include-long
    bin/test-check default --ns seon.instrument-test
    bin/test-check default --ns seon.db-test
    bin/test-check default --ns seon.cluster.source-test --ns seon.error-test --ns seon.sci.branch-execution-test
    bin/test-check default --ns seon.contracts-compile-test          ; N3 rerun on a clean tree
    bin/test-check default --changed seon.test/run
    bin/test-check default --changed seon.sci.eval/projection-state
    bin/test-check default --changed seon.sci.eval/install-function-from-database!
    # platform, root-owned: boot and init with source-base!'s new argument
    bin/test --platform

Regressions from this lane would name one of:
- `arm-first-loads!`, `:seon.test/not-runnable` or `source/database`;
- `carried-cluster-name` ("A cluster context needs its caller's cluster name");
- `projection-state` or `source-base!` arity;
- custody ("This write names branch") inside a fixture helper.

Released paths: every file in the table, plus this note. `src/seon/test/runner.clj` is released unchanged.
