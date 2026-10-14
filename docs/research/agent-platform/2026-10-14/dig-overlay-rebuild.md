---
title: "Dig: incremental rebuild of a branch's program context (overlay)"
type: research
status: dig only; facts with symbols and lines; no source, schema, test or doc changed; no SCI run; no recommendation
date: 2026-10-14
basis: "HEAD 8ad2a751b; src/seon/sci/eval.clj as read; reference-code/sci at eb3290d5c (gitlink)"
---

# Dig: overlay rebuild

Question for the designers: make a branch's program context (SCI overlay) acquisition incremental, so a program
change re-interprets only the changed rows and what reads them, instead of every value row's init from the loaded base.

Verification marks: VERIFIED = read in source or in git in this dig; UNVERIFIED = stated, not confirmed here.

## 1. Acquisition path, end to end

| Step | Symbol (src/seon/sci/eval.clj) | Reads | Returns | Callers |
|---|---|---|---|---|
| Entry | `base-ctx` (~3885) | `program-context` of the database, `loaded-program`, `armed-dials` | a fork with the caller's custody, kernel atoms and acquisition refusals recorded | `seon.cluster.agent/acquire-context!` (src/seon/cluster.clj:2728 `candidate-gate!`; src/seon/turn.clj:5291 `prove-turn`, :5472, :5527; src/seon/test.clj:201, :2417, :3182) VERIFIED |
| Cache front | `program-context` (3844) | `program-revisions`, dial revisions, `program-changes` (the plan), `program-content-key` | the frozen program context value; `seon.cache` kind `::program-context` (weight `overlay-weight` or `context-weight`) and `::program-key` | `base-ctx`, `script/seon/program_context_weight.clj`, tests (section 5) VERIFIED |
| Plan | `program-changes` (3260) → `overlay-plan` (3007) → `derive-overlay-plan` (2964) | authored rows, namespace digests, loaded digests, `function-symbols` | `{::overridden ::added ::namespaces ::tests ::digest}`; `overlay-plan` cached under `::overlay-plan` by `[store-identity basis commit]` | `program-changes`, `program-context` VERIFIED |
| Derive | `derive-program-context` (3827) | `loaded`, projection, `plan` | with a plan: `derive-overlay-ctx` on the loaded base; without: `derive-base-ctx` | `program-context` VERIFIED |
| Base | `derive-base-ctx` (3670) | loaded JVM namespaces (`load-core-namespaces!`), projection | the loaded program's frozen context; cached as `::program-context` under `cluster-base-key` `[store-identity commit]` (3706) | `derive-program-context` VERIFIED |
| Overlay | `derive-overlay-ctx` (3785) | base context (`copy-base-ctx`, 3715: `sci/fork`, new kernel atoms, `::custody` from the connection), plan | `freeze-program` (3659) of the interpreted builder, plus `::acquisition` and `::own-namespace-entries` | `derive-program-context` only VERIFIED |
| Interpret | `overlay!` (3273) → `interpret-overlay!` (3154) | ctx, database, plan | acquisition map | `derive-overlay-ctx`; `interpret-affected!` (3238, with `::overridden`/`::evaluated` set to the changed symbols) VERIFIED |
| Install | `install-selected!` (2579) → `install-row!` (1449) per row, inside a try | namespace rows, function rows, test rows | installed count, load results, refusals (`acquisition-refusal`) | `interpret-overlay!` (3228), `interpret-source-namespaces!` (2206), `acquire-program!` (2850), `reinterpret-reach!` (2924), `promote-member!` (3090) VERIFIED |
| Refusals | `record-acquisition-refusals!` (2383), `record-refusals-once!` (3970) | acquisition, program revisions, connection identity | records once per `[program target]` in `::refusal-recording` (cache size 512) | `base-ctx`, `interpret-overlay!` VERIFIED |

What `interpret-overlay!` does, VERIFIED (3154-3225):
- `already` = the snapshot's `::interpreted` plus the plan's `::evaluated`. A fresh overlay has none, so nothing is skipped.
- `closure` = the plan's `::closure` if present, else `seon.fn/reverse-closure` seeded with `::overridden`.
- `callers` = the closure's affected functions not already interpreted and not overridden.
- `added-rows` = `function-rows-in` of `added` minus `interpreted`. `added` is every function the database holds that the loaded digests lack (`derive-overlay-plan`, line 2990). So every branch-only definition is re-read and re-installed by each derivation.
- `function-rows` = `overridden` (minus `already`) plus macros plus sourced added functions. `install-selected!` runs over these.

`install-row!` (1449-1620) VERIFIED: pulls the committed row by its identity attribute (`program-row-identity`, 718, from `program/identity-attributes`), checks the source against the committed one, then dispatches on identity attribute (`:seon.ns/name`, `:seon.fn/sym`, `:seon.test/sym`, delete identities). A value def is a `:seon.fn/sym` row since de1f48c23 (arglists `"()"`).

## 2. What a row is, its digests, and dependents

| Item | Fact | Symbol / location | Status |
|---|---|---|---|
| Row kinds that install | function (incl. value defs), test, namespace, delete identity | `program/identity-attributes`; `install-row!` dispatch | VERIFIED |
| Definition digest | `seon.program/definition-digest` over the family's canonical row plus the namespace resolver context; stored at `:seon.program/definition-digest` | `seon.db/definition-digest-of` (src/seon/db.clj:5520); read in eval as `row-definition-digest` (eval.clj:1322) and `definition-digest-of` (1352) | VERIFIED |
| Behavior digest | the definition digest without documentation; a docstring-only change does not count as behavior change | `seon.db/behavior-digest` (db.clj:5552); `behavior-changed?` (eval.clj:1369) | VERIFIED |
| Loaded digests | `function-digests` of the loaded commit, cached `::loaded-digests` by `[store commit]` | `loaded-digests` (eval.clj:1358) | VERIFIED |
| Plan digest | `id/digest` over the held definition digests of overridden and added, namespace keys, and test rows | `derive-overlay-plan` `::digest` (eval.clj:3003) | VERIFIED |
| Program context key | `program-content-key` (3814): `[store commit]` + plan digest + `projection-delta` + `armed-dials` when a plan exists; `[store commit]` otherwise | eval.clj:3814-3825 | VERIFIED |
| Program key (cache of the content key) | `[store-identity revisions dial-revisions supplied-loaded-commit]`, weight 1024 | eval.clj:3862-3872 | VERIFIED |
| Call edges | `:seon.fn/calls` set on rows (value defs too, for their initializer) | src/seon/fn.clj:796, :851, :885; value-defs.md:19-20 | VERIFIED |
| Dependents of a changed definition | reverse closure through declared invocation edges; symbol mentions do not count | `seon.fn/reverse-closure` (fn.clj:2025) over `closure-walk` (fn.clj:1971) with `declared-callers`; docstring says "Declared invocation edges count; symbol mentions do not" | VERIFIED |
| Tests reaching a change | `closure-walk` tests; plan carries `::closure-tests` when present | fn.clj:1971; eval.clj:3171 | VERIFIED |
| Read evidence | exists for reads: `seon.db/read-evidence-current?` (db.clj:1359), retained dependency revisions | not called from the overlay path (eval.clj 2850-3290, 3785-3900 contain no call) | VERIFIED for the overlay path; global absence UNVERIFIED |
| Reach digests (tests) | test-side reach digest and `seon.test.cache` | not read in this dig | UNVERIFIED here |

Consequence, VERIFIED from the code above: the overlay key is content-only. Equal content gives an identical value across
branches and commits. Any change to the plan's digest (for example, one unrelated `defn` that adds a branch-only row)
yields a new key, so the whole plan is derived again from the loaded base. No per-row record of what a row read is kept
on the overlay path.

## 3. SCI mechanisms (reference-code/sci, gitlink eb3290d5c)

| Symbol | Behavior, from source | Location |
|---|---|---|
| `sci.core/fork` | a new context with a new `:sci/generation`; new and redefined vars in the fork are not visible to the original; a Var keeps its identity; the fork writes no shared Var in place | reference-code/sci/src/sci/core.cljc:337-350 |
| `sci.core/freeze` | `(update (fork ctx) :env deref)`: the env held as a map, nothing evaluates against it | core.cljc:352 |
| `sci.core/init` | builds a context from options | core.cljc:323 |
| `sci.core/merge-opts` | updates a context with options | core.cljc:332 |
| `fork` on a frozen context | `(if (map? env) env ...)`: a frozen context's env is already a map, so a fork of one is cheap and gets a new generation | core.cljc:337-346 |
| `copy-ns` | compile-time macro that builds a namespace from a publics map; not used at runtime by eval | core.cljc:498-520, 560-660 |
| env / namespace install | Seon calls `sci/install-namespace-bindings!` per namespace row | eval.clj interpret-overlay! (~3206) |

Whether a context can be derived from a previous context: VERIFIED that `copy-base-ctx` (3715) accepts any
`:seon.sci.eval/program-context` value and forks it, and that `derive-overlay-ctx` types its `cluster` argument as one.
UNVERIFIED that a previous overlay can serve as the base: `interpret-overlay!` reads `::interpreted` from the forked
snapshot, so a previous overlay's interpreted set would make `already` non-empty. Whether its changed rows would then
still be re-installed depends on `overridden` covering them; not tested here.

## 4. Counts per request

| Measurement | Value | Source | Status |
|---|---|---|---|
| `install-row!` calls, one request | 37 (result `result/ecf21e7d07946`, first held-task block, scoped profile) | docs/research/agent-platform/2026-10-14/ctx-cost.md "Profile" table: 37 calls, 20.96 ms summed self time; ctx-cost-evidence.json warm sample: 37 calls, 566.6 us warm self | VERIFIED for that request only |
| `install-row!` calls, repeat or trivial request | not recorded in ctx-cost.md | none | UNVERIFIED |
| `install-caller-host-bindings!` in the same request | 4 calls, 18.18 ms summed self | ctx-cost.md profile table | VERIFIED |
| `seon.test/bounded-result` in same request | 2 calls, 139.12 ms summed self, includes waiting for body execution | ctx-cost.md | VERIFIED |
| `vector-query-call` in the same request | 281 calls, 226.35 ms summed self | ctx-cost.md | VERIFIED |
| Overlay derivation (interpret-overlay!) on a program change | not separately timed in ctx-cost.md | none | UNVERIFIED |
| The 8ad2a751b reproduction: unrelated `defn` raises a refusal count by 2 | per install-refusal per consumer (agent acquisition and test step) | docs/research/agent-platform/2026-10-14/install-row-reruns.md | VERIFIED as reported by lane; reproduction not rerun here |

Note: the two `install-row!` timings differ by sampling method (summed self time versus warm sample). Both are recorded
in their sources and are not reconciled here.

## 5. Tests that reach this path

| File | Test or form | What it exercises | Measured? |
|---|---|---|---|
| test/seon/sci/acquisition_cost_test.clj | `a-cold-base-derivation-is-proportional-to-declarations` (68) | `derive-base-ctx` cost by declarations | yes, `profile/measure` |
| same | `a-second-acquisition-of-an-unmoved-branch-interprets-nothing` (99) | warm acquisition calls no `interpret-overlay!` (asserted zero at lines 115, 134, 141) | yes, call counts |
| same | `install-row-is-twice-as-slow-after-the-stale-var-fix` (145) | `install-row!` cost via `profile/measure` (line 153) | yes |
| same | `a-branch-head-read-costs-one-primary-materialization` (171) | head read cost | yes |
| test/seon/sci/eval_test.clj | `an-overlay-plan-digests-only-the-authored-rows` (2764) | `derive-overlay-plan` measured (2773) | yes |
| same, ~2750-2760 | asserts zero `derive-overlay-plan` and `derive-overlay-ctx` calls on one path | call counts | yes |
| same, ~2869-2879 | `a-program-context-weighs-its-own-declarations`: `derive-overlay-ctx` retained bytes | weight | yes |
| same | `branches-without-program-changes-add-no-overlay-entry`, `equal-programs-on-two-branches-share-one-program-context`, `the-program-context-is-a-value` | content sharing | no |
| test/seon/sci/branch_execution_test.clj | interpreted-count assertions (163-172, 256-257, 337, 393, 451, 460) and `overlay-plan` (360, 710) | counts across branches | prints `UNOVERRIDDEN-HOST-BOUND-MS` (528) but asserts no bound |
| test/seon/sci/repl_differential_test.clj | `interpret-affected!` (263) | measured | yes |
| test/seon/instrument_test.clj | base derivations, `derive-base-ctx` once (1103-1120) | cache eviction then derivation | yes |
| test/seon/cluster/acquisition_test.clj | counts `sci/install-row!` by `with-redefs` (133-135) | install count | yes; uses with-redefs |
| test/seon/cluster/source_test.clj, test/seon/test_test.clj, test/seon/sci/eval_test.clj | direct `install-row!` calls | row behavior | no |
| test/seon/cluster/branch_release_retention_test.clj, evaluate_source_test.clj | retained `:seon.sci.eval/program-context` | retention | yes |

No test found by symbol asserts that an unrelated `defn` leaves a refusal count unchanged (the 8ad2a751b task's proof).
This is a search by symbol and name; UNVERIFIED as a global absence.

## 6. Prior attempts and rulings

| Commit or artifact | Date | What it did or ruled | Status |
|---|---|---|---|
| 86430115b | 2026-10-13 | Settlement never writes an execution's base context. The base had held roots analyzed against another context's Vars, so it claimed the head's program while holding a copy no derivation would produce. | VERIFIED (message) |
| ab8f218d4 | 2026-10-13 | A branch's test world runs its candidate's program by content, never the execution's base. The `clean-request-handle` fork of a base that claimed the program ran an old body (signature 7c52d6ce). | VERIFIED (message) |
| de1f48c23 | 2026-10-14 | Every top-level def is a `:seon.fn/sym` row, as in files (owner ruling, option b). Trade-off named in value-defs.md:170: an effectful computed initializer runs again in every new context and test world, as Clojure loading does. | VERIFIED (message, value-defs.md) |
| 172b23e7c | (E4) | An eval request on an unchanged program forks nothing; a moved program forks the program context once and carries only the agent's own namespaces. | VERIFIED (subject) |
| bc347d583 | (E7) | A program context is charged the bytes its eviction frees, measured. | VERIFIED (subject) |
| 874377c5f | | Interpreted test source acquisition made lazy: 236 ms/369 MB to 97 ms/180 MB. | VERIFIED (subject) |
| 7eddc79aa | | A branch's overlay enters every namespace row it changes; an empty merge delta judges no write-back root. | VERIFIED (subject) |
| 588d986c7 | | Plan (Opus): the program context as an immutable value of a commit. | VERIFIED (subject); content not read |
| c537b61f4 | | Sol plan: the environment at any commit as an immutable value. | VERIFIED (subject); content not read |
| 8ad2a751b | 2026-10-14 | Research note: the overlay re-runs every branch value row's init per program change; three options; lane recommends option 1 (incremental overlay, measured). Nothing installed. | VERIFIED |
| "F57", stale compiled body | | `git log -S`/grep for F57 found no commit. The stale-body symptom is the one in ab8f218d4. | UNVERIFIED (name not found) |
| lane base-ctx-content | | No commit or branch named so in `git log --grep`. | UNVERIFIED (not found) |
| seon.call-preparation | | Named only in src/seon/error/refusal.clj:118-126 (`find-ns 'seon.call-preparation`) and a resource schema; no namespace in src. | VERIFIED |

## 7. Evidence files

- docs/research/agent-platform/2026-10-14/install-row-reruns.md (root causes and options; not repeated here)
- docs/research/agent-platform/2026-10-14/ctx-cost.md and ctx-cost-evidence.json (request costs; install counts)
- docs/research/agent-platform/2026-10-14/value-defs.md (value defs as rows; trade-off)
- docs/research/agent-platform/2026-10-14/reflevels-opus.md (the same finding, "Findings during the probe" item 1)
