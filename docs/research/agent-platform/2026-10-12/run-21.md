---
title: "Run 21: chain 5 (seon.edit/single-form?) on 1d538ec20: STALLED in Prove. Three attempts × 60 calls (180 model calls, every attempt :capped) wrote both declared product tests plus a third, red exactly as the task specifies (the odd-map rows throw). The Prove done rule counts a product test red only 'by failing assertions or an unimplemented stub', so error-red tests never let the step end; the agent saw itself as done and repeated (in-ns 'seon.edit') and prose to the cap. Implement never ran; nothing merged"
type: research
status: complete (lane run21, 2026-10-13); chain STALLED in Prove; relaunches exhausted
created: 2026-10-13
---

# Run 21: the chain `seon.edit/an-unreadable-odd-map-is-not-one-form` on 1d538ec20

**Broken first: the task's red cannot satisfy the Prove done rule (F60).** The gap is that
`seon.edit/single-form?` *throws* on an odd map. So the specified red rows (`["{:a 1 :b}" false]` and three others)
end as clojure.test **errors**, not failures. `seon.context/step-status` (docstring): "A `:prove` step is done
when … every product test is red at the head by failing assertions or an unimplemented stub". On every attempt's
head:

- the table `seon.edit-test/an-odd-map-anywhere-is-not-one-form` ran **10 pass / 0 fail / 4 error**, which is
  error-red only;
- the law `seon.edit-test/every-source-holding-an-odd-map-is-not-one-form` ran **0 / 1 / 1**.

The table is never "red by failing assertions", so the step stays `:working`. The model's replies show that its
context did not say why (F61). From about call 40 of attempt 3 it wrote "The prove step is complete. The
current-state confirms everything…", alternated `(in-ns 'seon.edit)` with prose-only replies ("Your reply had no
form"), and ran to the call cap.

- `db<->flow` relaunched twice ("Ended capped with 3 declarations main lacks: relaunch n of 2 continues from its end
  commit"). Each relaunch inherited the same heads and the same verdict.
- **180 model calls**, 00:22:29.6 → 00:31:16.3, then nothing. No merge, no Implement attempt, no git commit from the
  chain. Nothing shipped: `seon.edit/single-form?` still throws on HEAD.

None of the extra watch items (the `seon.sci.reader` require, ns-form publication, F57 on a fresh branch, lint
after the merge) was reached, because Implement never launched. Lint of `src/seon/edit.clj` and
`test/seon/edit_test.clj` on HEAD after the run: 0 errors, 0 warnings, unchanged (the chain published nothing).

| Measure | Prove (`bbbdd44c7791`) | Implement (`6569d0dc9a1e`) |
|---|---|---|
| Attempts | `2ad107668183` (#1), `ea899f6589e2` (#2), `aad1a740bac8` (#3): each **`:capped`** at 60 calls | none |
| Model calls | **60 + 60 + 60 = 180** (attempt 1: 00:22:29.6 → 00:24:56.7; 2: 00:24:59.5 → 00:28:22.3; 3: 00:28:26.2 → 00:31:16.3) | 0 |
| Product on the last head | the table (13 rows as specified, plus one); the law (`quick-check` 300, seed 20261013, odd-count keyword vectors, three wrappings); and an unrequested third test, `seon.edit-test/a-request-with-an-odd-map-replacement-is-not-valid` (2 errors, through `seon.edit/valid-form-operation?`) | n/a |
| Test results on the head | table 10/0/4, law 0/1/1, extra 0/0/2, all `:interpreted` | n/a |
| `refine!` | attempt 3 called `my.task/refine!` to rewrite Implement's instructions so they name all three reds. It stayed on the branch and never reached main (Implement's instructions on main are unchanged) | n/a |
| `send-back!` | 0 | n/a |
| Attempt-1 errors | 9 of 138 evaluations: 5 prose-only replies, `tc/for-all` (wrong alias), `seon.program/ns`, a string passed to `my.program/source`, `seon.test/identities` as a symbol | n/a |

## Before launching

- Default pid 69901, booted 00:21:44 UTC from `data/source/1d538ec208dd…` (HEAD), and reset. Health `:degraded`
  (warnings). Not stopped or reset by this lane.
- Subject `68d99c8aef55` `:held`; Prove `bbbdd44c7791`; Implement `6569d0dc9a1e`. No attempts, `ready #{}`,
  branches `[:db :current-src :cluster-default]`. The gap was re-verified in the JVM: `"{:a 1 :b}"`, `"{:a}"`,
  `"[{:a}]"` and `"(f {:a 1 :b})"` throw `IllegalArgumentException`; `"(a)"` → true; `"#_x (a)"` → false.
- **Reaching tests, interpreted, on `run21-probe`** (`:agent-842207848d94`): the union of `tests-reaching` for
  `seon.edit/single-form` and `seon.edit/single-form?` is 5 tests. Run `56738c828362`, all `:interpreted`, all
  green: `my.edit-test/form-operation-relation-is-structural-and-open` 5/0/0;
  `seon.edit-test/form-edit-preserves-every-unselected-byte` 5/0/0; `…/form-edit-records-its-span-in-utf8-bytes`
  6/0/0; `…/form-failures-never-fall-back-to-text` 5/0/0;
  `…/selectors-refuse-ambiguity-and-use-parsed-dispatch` 3/0/0. 3,273 ms and 4.6 GB (F27). Launched. The probe was
  retired.
- Baseline lint (clj-kondo, `--cache false`) of `src/seon/edit.clj` and `test/seon/edit_test.clj`: 0 errors, 0
  warnings.
- Config: `apply!` `{no-provider false, max-episode-runs 60}` → 4 operations, `converged? false` (C3). The
  effective values were confirmed, plus `relaunches 2` and model `deepseek-flash`.

## Timeline (UTC, 2026-10-13)

| At | Event |
|---|---|
| 00:22:29.36 | root transacts `:seon.task/execution :running` on the subject. This was the only hand act; no write by this lane until 00:42:5x |
| 00:22:29.6 | Prove attempt 1, call 1 |
| :43 | first run of the table: 9/0/4 (error-red) |
| 00:23:04 | law: 0/1/1 |
| 00:24:56.7 | attempt 1 call 60 → `:capped`; relaunch 1 of 2 |
| 00:24:59.5 → 00:28:22.3 | attempt 2, 60 calls → `:capped`; relaunch 2 of 2 |
| 00:28:26.2 → 00:31:16.3 | attempt 3, 60 calls: adds the third test (00:30:37) and `refine!`. From about 00:30:58 it says "the prove step is complete" over `(in-ns 'seon.edit)` and prose. `:capped` |
| 00:31:16 → 00:42:29 | no attempt, no write, `:open-turns 0`. JVM CPU (`ps`) idle except bursts |
| 00:42:5x | cleanup: config restored, subject `:held` |

Other lanes committed to the shared tree during the window (`c05059e06`, `770733432` Merge-lint, `874377c5f`).
None touched `seon.edit`.

## Seon git commits from this run

None.

## Correctness check

Nothing merged, so there is nothing to grade. The product tests on the last head match the specified rows and the
law. They are red for the right reason, the throw that is the gap itself, but by error and not by failure.

## Grades

- Hand acts after launch: **0**. Wakes: **0**. Relaunches: **2** (automatic). Calls: Prove **180**, Implement **0**.
- Platform: launch → Prove → cap → relaunch → cap → relaunch → cap → stop. The relaunch faithfully continued a step
  that could not end. Cost: 180 calls for a two-test product.
- Product: the tests were right and nothing shipped.

## Frictions

| # | Friction (observed) | Owner | Proposed red proof |
|---|---|---|---|
| **F60 (binding, Prove rule)** | When a gap *is* a throw, the product tests are red by **error**. `seon.context/step-status` requires red "by failing assertions or an unimplemented stub", so the step can never be done. The task data cannot be fixed without weakening the test (catching the throw), which the testing law forbids | `seon.context/step-status` (accept a product test red by an error raised inside the subject under test, as distinct from a broken fixture or an unresolved symbol in the test body) | in `seon.context-test`, a Prove head whose only product test errors with an exception thrown from the function its task names (a stack frame in that var) is `:done`; a test erroring on an unresolved symbol in its own body stays `:working` |
| **F61 (binding, context)** | The agent could not see which clause held it. Its replies quote `:seon.context/new-red` as exactly its product and `:broken #{}`, conclude "the prove step is complete", and then loop prose and `(in-ns …)` to the cap, about 20 calls in attempt 3 alone | the step section of `seon.context/current-state` (name the unmet done clause as data, e.g. `:seon.context/not-done [:error-red table]`) | the current-state of a Prove head whose product is error-red carries the unmet clause naming that test |
| **F62 (unattended, cost)** | Relaunch continued a `:capped` attempt whose head verdict was unchanged since its predecessor's end. Two relaunches spent 120 calls with no new information | `seon.cluster.agent/next-actions` (relaunch only when the predecessor's head made progress, or carry the verdict into the relaunch's context) | a `:capped` attempt whose step verdict at its end equals its predecessor's end is not relaunched; or the relaunch's context names the unchanged verdict |
| F63 (Prove scope, minor) | Prove added an unrequested third test and used `refine!` to rewrite Implement's instructions on its branch. The refinement would have reached main only through the merge | the Prove help (the product is the declared set) | none; note only |
| F27, C3 (again) | 3,273 ms and 4.6 GB to check 5 tests; `apply!` 4 then 3 operations, `converged? false` | as before | as before |

## Residue on default (disposable)

- Subject `68d99c8aef55` is `:held` (verified). Prove has three `:capped` attempts; Implement has none.
- **Branches kept as evidence for F60 and F61**: `:attempt-2ad107668183`, `:attempt-ea899f6589e2` and
  `:attempt-aad1a740bac8`, each with its `:base/…`. `run21-probe` was retired. `:agent-e73845230afd` is not this
  lane's.
- Config restored to `no-provider true` and `max-episode-runs 100` (verified).
- Shared tree: unchanged by the chain. `seon.edit/single-form?` still throws on odd maps.
