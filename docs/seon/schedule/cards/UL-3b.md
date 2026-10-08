---
title: "UL-3b: Tests arm only their own Vars; the instrumentation restore is deleted"
type: reference
status: ready
created: 2026-10-08
---

# UL-3b: Tests arm only their own Vars; the instrumentation restore is deleted

Ledger row UL-3b (group F/T). Source: `reinvention/reinvention-opus.md` item 2. Law: CLAUDE.md "No JVM-global test
state … or restoring reloaded roots; preserve instrumentation (a test arms only its own Vars, never the whole JVM);
never `alter-var-root`, `with-redefs`, `load-file` or `intern` over any Var." Model: Sonnet for UL-3b1 to UL-3b3,
Opus for UL-3b4 (it deletes instrumentation code the test runner's drift check reads).

## What exists (verified at `1fcdbf47a`)

- `seon.instrument/state` (`src/seon/instrument.clj:1192-1206`), `replaced-definitions` (`:1208-1232`), `restore!`
  (`:1234-1290`) and the private `function-schemas*` reach into Malli's atom (`:1187-1190`); `restore!` is the only
  writer of Malli's `-function-schemas*` in `src` (it `reset!`s it).
- `seon.test-support/preserving-instrumentation-state` (`test/seon/test_support.clj:1142-1156`): capture `state`, run
  the body, `restore!` in `finally`.
- The safety net that stays: the test runner's drift check, `seon.test.runner/ambient-snapshot`
  (`src/seon/test/runner.clj:630-690`), compared before and after every run (`:404`, `:418-424`, "Worker-global
  state changed"). It counts a wrapper whose contract is **not** its Var's own `:malli/schema` as a test's change, the
  **removal** of a Var's own wrapper (or of its supplied-default plan) as drift, and any change to
  `(m/function-schemas)`. A wrapper a test adds over its own Var's own `:malli/schema` is the program's arming and is
  not drift. Arming (`arm-var!`, `:981`) never writes Malli's function-schema registry.

So a test that arms only Vars it owns (gensym'd in its own namespace, carrying `:malli/schema`) and never unarms or
unmaps them leaves no drift and needs no restore. A test that re-arms or unarms a production Var, boots a cluster or
reloads a namespace is global and moves to the platform tier (`^{:seon.test/platform "reason"}`, run in the platform
tier's isolated JVM, see `seon.sci.eval-test/the-evaluator-remains-live-after-its-namespace-reloads`), or is
rewritten to use its own Vars.

**The ledger's list of eight sites is short.** `rg "preserving-instrumentation-state|instrument/state|instrument/restore!|replaced-definitions" test`
finds 17 sites in 13 files. This card replaces the ledger's list.

| Site | What the body changes globally | Conversion | Slice |
|---|---|---|---|
| `test/seon/instrument_test.clj:62-70` (`scoped-instrumentation`, every test's `:each` fixture) | each test arms its own suite Vars (`arm-suite!` `:96`, `arm-with!` `:81`) | keep `with-database`, drop the restore; a test that unarms its own Var (`alter-var-root … -f->original`) instead asserts on a fresh gensym Var it leaves armed | 3b1 |
| `test/seon/instrument_test.clj:825-860` (`acquisition-carries-one-effective-policy-to-every-wrapper`) | interns gensym Vars and arms them | drop the wrapper (its own Vars) | 3b1 |
| `test/seon/instrument_test.clj:~1460-1500` (the `replaced-definitions`/`restore!` test) | tests the restore itself | deleted with the functions | 3b4 |
| `test/seon/error_test.clj:511` | arms only `seon.error-test/wide-return-narrow-violation` | drop the wrapper | 3b1 |
| `test/seon/sci/documentation_test.clj:231` | nothing (its comment: "No self-arming") | drop the wrapper | 3b1 |
| `test/seon/sci/eval_test.clj:1599` | nothing (re-arms nothing; the outer assertion already checks the wrapper set) | drop the wrapper | 3b1 |
| `test/seon/sci/eval_test.clj:463-487` (platform tier already) | `require :reload` of `seon.sci.eval` in its isolated JVM | drop `state`/`restore!` and the two assertions on `replaced`; the isolated JVM is disposable | 3b1 |
| `test/seon/error/refusal_test.clj:34` | re-arms production refusal readers with `policy {}` through private `arm-var!` | do not re-arm: boot already armed each entry; assert `(contains? (instrument/instrumented) entry)` and call `@entry` as today | 3b3 |
| `test/seon/test_reaching_test.clj:294` (`in-process-results-detect-worker-global-drift`) | unarms `#'seon.id/digest`, a production Var, to provoke the drift check | provoke drift with the test's own Var instead: intern a gensym Var with `:malli/schema` in `seon.test-reaching-test`, arm it through `instrument/apply!` with `:seon.instrument/changed-identities` (as `arm-suite!` does), and let the member body unarm **that** Var; assert the same error count and message | 3b3 |
| `test/seon/cluster/publication_delta_test.clj:110` (`reloading-one-file-preserves-unrelated-wrappers`) | re-arms every contracted `my.turn` Var | arm a fixture namespace the test creates (as `instrument_test.clj:~1460` builds its probe namespace) instead of `my.turn`; the assertion (every other wrapper identical) is unchanged | 3b3 |
| `test/seon/render/web_adoption_test.clj:9` | `with-redefs` over `seon.render.web/handler` | delete the test: Var redefinition is forbidden, and the behavior is a direct Var call (`src/seon/render/web.clj:3716`, `(fn [request] ((handler service) request))`), which every `seon.render.web-test` request already exercises | 3b3 |
| `test/seon/cluster/cohost_boot_test.clj:61` (`:each` fixture) | boots a second cluster (boot arms the JVM) | `^{:seon.test/platform "boots a second cluster, which arms the JVM"}` on its deftests; delete the fixture | 3b2 |
| `test/seon/cluster/publication_host_test.clj:30` | boots a cluster | platform tier; drop the wrapper (its `with-redefs` at `:~48` is a separate defect, see below) | 3b2 |
| `test/seon/sci/eval_instrumentation_test.clj:26` | boots a development cluster | platform tier; drop the wrapper | 3b2 |
| `test/seon/cluster/publication_adoption_test.clj:24` (`failed-loaded-definition-scope-restores-arming`) | `load-file` of `script/seon/dev/docstring.clj` to test the restore | deleted (it tests the restore and uses `load-file`) | 3b4 |
| `test/seon/cluster/publication_adoption_test.clj:47` (`freshly-forked-host-already-has-its-published-source`) | `refresh-source!` and a boot | platform tier; drop the wrapper | 3b2 |
| `test/seon/test_support_test.clj:211` (`an-instrumentation-test-restores-the-entering-contracts-on-failure`) | tests the helper itself | deleted with the helper | 3b4 |

## UL-3b1: drop the restore where the body owns its Vars (Sonnet, ordinary)

- Files: `test/seon/instrument_test.clj`, `test/seon/error_test.clj`, `test/seon/sci/documentation_test.clj`,
  `test/seon/sci/eval_test.clj`.
- No function is added; each listed site loses its `preserving-instrumentation-state` call (or `state`/`restore!`).
  A test in `instrument_test.clj` that unarms a Var it armed (search the file for `-f->original` under
  `alter-var-root`) now leaves the Var armed: unarming its own Var is a removal the drift check reports. Read each such
  test; if its subject is unarming, it asserts on the unarmed wrapper `unarmed-wrapper` (`:73`) builds without
  installing it.
- REPL-first: ordinary. Evaluate each converted deftest on the lane's branch (`eval_clj` mode sci, agent = lane),
  run it there, read the run's drift result, then write it back. Commit through the throwaway check until RF-2.
- Proof: the converted tests green **with no drift report** (each run result has no "Worker-global state changed").
- `--test seon.instrument-test --test seon.error-test --test seon.sci.documentation-test --test seon.sci.eval-test`
- Commit: `bin/lane commit <lane> -m "UL-3b1: tests that arm only their own Vars drop the instrumentation restore" --test seon.instrument-test --test seon.error-test --test seon.sci.documentation-test --test seon.sci.eval-test test/seon/instrument_test.clj test/seon/error_test.clj test/seon/sci/documentation_test.clj test/seon/sci/eval_test.clj`

## UL-3b2: tests that boot move to the platform tier (Sonnet, host-bound)

- Files: `test/seon/cluster/cohost_boot_test.clj`, `test/seon/cluster/publication_host_test.clj`,
  `test/seon/sci/eval_instrumentation_test.clj`, `test/seon/cluster/publication_adoption_test.clj` (the `:47` test
  only; the `:24` test goes in 3b4).
- Each deftest gains `:seon.test/platform` with a one-line reason naming the boot; the wrapper or fixture is deleted.
  Read how the runner selects the platform tier (`src/seon/test.clj:610`, `:795`, `:904`) and confirm each test then
  runs in the isolated JVM, not the shared one.
- Throwaway check (boots a cluster).
- Proof: each test green in the platform tier; the shared-JVM run no longer selects them.
- `--include-long --test seon.cluster.cohost-boot-test --test seon.cluster.publication-host-test --test seon.sci.eval-instrumentation-test --test seon.cluster.publication-adoption-test`
- Commit: `bin/lane commit <lane> -m "UL-3b2: tests that boot a cluster run in the platform tier, without the instrumentation restore" --include-long --test seon.cluster.cohost-boot-test --test seon.cluster.publication-host-test --test seon.sci.eval-instrumentation-test --test seon.cluster.publication-adoption-test test/seon/cluster/cohost_boot_test.clj test/seon/cluster/publication_host_test.clj test/seon/sci/eval_instrumentation_test.clj test/seon/cluster/publication_adoption_test.clj`

## UL-3b3: rewrite the four tests that change production Vars (Sonnet, ordinary)

- Files: `test/seon/error/refusal_test.clj`, `test/seon/test_reaching_test.clj`,
  `test/seon/cluster/publication_delta_test.clj`, `test/seon/render/web_adoption_test.clj` (deleted).
- Conversions per the table. The drift-provoking test keeps its subject (the runner reports a worker-global change)
  with a Var it owns: its own Var's wrapper is removed inside the member, which the drift check counts
  (`runner.clj:647-649`, "only its removal … counts").
- REPL-first: ordinary; evaluate the rewritten deftests on the lane's branch first.
- Proof: `seon.error.refusal-test/armed-refusal-returns-every-declared-family-without-refusing`,
  `seon.test-reaching-test/in-process-results-detect-worker-global-drift` and
  `seon.cluster.publication-delta-test/reloading-one-file-preserves-unrelated-wrappers` green, each without a drift
  report of its own; D: `rg "with-redefs" test/seon/render/web_adoption_test.clj` finds no file.
- `--test seon.error.refusal-test --test seon.test-reaching-test --test seon.cluster.publication-delta-test --test seon.render.web-test`
- Commit: `bin/lane commit <lane> -m "UL-3b3: tests provoke drift and arm with their own Vars; the handler redefinition test is deleted" --test seon.error.refusal-test --test seon.test-reaching-test --test seon.cluster.publication-delta-test --test seon.render.web-test test/seon/error/refusal_test.clj test/seon/test_reaching_test.clj test/seon/cluster/publication_delta_test.clj test/seon/render/web_adoption_test.clj`

## UL-3b4: delete the restore (Opus, host-bound)

- Files: `src/seon/instrument.clj`, `test/seon/test_support.clj`, `test/seon/test_support_test.clj`,
  `test/seon/instrument_test.clj`, `test/seon/cluster/publication_adoption_test.clj`,
  `resources/seon/tasks/seon.instrument.edn` (any intent text naming the four).
- Delete `seon.instrument/state`, `replaced-definitions`, `restore!`, the private `function-schemas*`;
  `seon.test-support/preserving-instrumentation-state`; the tests that test them
  (`seon.test-support-test/an-instrumentation-test-restores-the-entering-contracts-on-failure`, the
  `replaced-definitions` test in `instrument_test.clj`,
  `seon.cluster.publication-adoption-test/failed-loaded-definition-scope-restores-arming`). Fix the
  `preserving-schema-registry` docstring (`test_support.clj:1161`) that names the deleted helper.
- First: `rg "preserving-instrumentation-state|instrument/state|instrument/restore!|instrument/replaced-definitions" src test script resources`
  lists only the sites this slice deletes; anything else stops the slice.
- Proof: D: zero callers of the four (the same `rg`); `(ns-resolve 'seon.instrument 'restore!)` is nil;
  `seon.instrument-test` and `seon.test-support-test` green with no drift reports.
- `--test seon.instrument-test --test seon.test-support-test --test seon.cluster.publication-adoption-test`
- Commit: `bin/lane commit <lane> -m "UL-3b4: delete the instrumentation restore; tests arm only their own Vars" --test seon.instrument-test --test seon.test-support-test --test seon.cluster.publication-adoption-test src/seon/instrument.clj test/seon/test_support.clj test/seon/test_support_test.clj test/seon/instrument_test.clj test/seon/cluster/publication_adoption_test.clj resources/seon/tasks/seon.instrument.edn`

## Cost

D. Runtime: none (test-only machinery). Each test without the restore saves one walk of every armed Var (`state`
derefs every `instrumented` root, about 2,250 on default) plus the restore's `alter-var-root` per armed Var.

## Deleted

About 110 source lines (`instrument.clj:1183-1290`), 15 in `test_support.clj`, and three tests of the deleted
machinery plus one forbidden `with-redefs` test.

## Out-of-scope findings

`test/seon/cluster/publication_host_test.clj:~48` and `test/seon/sci/eval_instrumentation_test.clj:~42` use
`with-redefs` (on `schema/projection-from-database` and `ai/complete`), and `instrument_test.clj:827` uses `intern`;
CLAUDE.md forbids both. Each needs its own conversion (an injected value at the world, as `ai/complete`'s provider
already is a world input); recorded for root as a ledger note on UL-3b, not done here.

## Dependencies

UL-3a (same `instrument.clj`, `instrument_test.clj`). UL-3b4 after UL-3b1..3. RED-2 (its evaluator-reload red is this
class) is re-read after UL-3b1. UL-12 edits `instrument.clj` after UL-3b4.
