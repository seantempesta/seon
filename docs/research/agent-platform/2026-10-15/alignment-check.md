---
title: "Alignment check: the hybrid's narrow check on default"
type: evidence
status: lane align-check (Sonnet); read-only forms on default, JVM mode, cluster default, pid 25531 on e4b5bc410, datahike 96000df8, konserve a2a24792
created: 2026-10-15
inputs: alignment-hybrid.md (31c9bdf8e) section 5 and phases H0-H7
---

# Alignment check

Summary: 7 of 12 items hold (1, 2, 4, 7, 8, 9, 10), 2 hold in part (3 with other numbers, 11 unrun), 1 does not reproduce (5, already fixed by H0), 1 FAILS (6, the diff primitive), 1 is unconfirmed (12). One finding changes every diff phase (H2, H3, H4): `datahike.api/datoms-diff` throws on default for many
real inputs. Nothing was defined, transacted or evicted. Item 2 ran once.

## 1. The 12 items

Form typos fixed, each said where it applies: item 2's `branches` takes the store (`(:seon.store/store (get @seon.operator.runtime/running-instances "default"))`), not a database; main's branch is `:cluster-default`; item 3's `program-revisions` returns a tuple (attributes are `(keys (last ...))`) and `own-changed-identities` takes `(merge-base-database db branch)`, the branch value and `seon.cluster.source/merge-bound`; item 6 needs the fork point's target `:cluster-default`.

| # | Claim | Value, ms, alloc | Verdict |
|---|---|---|---|
| 1 | fork pinned and loaded | `datoms-diff` is a Var; main commit `6ad08bb2`; 13 branches, 4 chain-named | holds |
| 2 | 1-arity `program-context` of a chain keys on the branch, not L | chain `:chain-f1f6dc8e6652`: 1,715 ms / 2,318 MB (Opus: 3,404 ms / 2,641 MB). Main: 3.07 ms / 3.3 MB first, 0.11 ms / 0.15 MB second | holds (smaller ms; the claim that main hits under 1 ms holds on the second call) |
| 3 | revisions are not a share key | 25 program attributes (hybrid said 170), 25 of 25 differ between main and the chain; `own-changed-identities` names 32 (hybrid said about 24); the probe cost 725 ms / 1,148 MB, mostly `merge-base-database` + the walk | holds in direction; numbers are 25 / 25 / 32 |
| 4 | L found three ways | `loaded-identity` public; `loaded-source` and `world-base` private (resolve true); L = `6ad08ae6`, equals main's cluster entity, chain's record is `6ad046be` | holds |
| 5 | the pass reopens every admitted branch | the store now holds 13 branches, not 125: ea12725bf retired 122 stale scratch admissions. `lifecycle-facts` on default: 2.6 ms / 2.4 MB cold, 0.58 ms / 0.77 MB warm; 4 `branch-admission-end` calls for 13 branches / 10 assignments | does NOT reproduce as stated; the mechanism was real and H0 (199750825) already fixed it, so the before-cost is moot |
| 6 | request-path read cheaper than diff | `entities-changed-after` on the chain from its fork point: 2.1-2.2 ms / 1.2 MB (7.8 ms / 3.2 MB on `analyzed-source-digest`), 1,038 entities, so not an unchanged branch. `datoms-diff fd v :aevt [:seon.program/definition-digest]` THROWS | FAILS for the diff side |
| 7 | `answered-t` absent | `:seon.agent/answered-t` false; `answered-commit` false; `:seon.test.run/commit` false; `:seon.branch/base-t` true | holds |
| 8 | fork connect reference-counted | `datahike.connections` keeps `{:conn :count}` per connection id (`reserve-connection-opening!` increments, `release-connection-reference!` decrements). `store/acquire-branch!` already says it relies on that count. `on-branch` twice: 18.0 ms / 1.7 MB then 1.5 ms / 1.7 MB, no refusal; live counts `[1 1 1 1]`. `store/open-branch!` is the guard beside it: a `branch-open-monitor` lock plus the `::branch-already-open` refusal for what Datahike counts | holds: E3 is a fence. Its callers: `open-branch!` x8 (`test.clj:2012`, `cluster.clj:1493`, `:2854`, `boot.clj:233,268`, `agent.clj:1178`, `source.clj:453,634`) |
| 9 | no cross-branch reader of error `basis-t` | writers `error.clj:687,1572`, `turn.clj:2723`, `fault.clj:196`, `eval.clj:2409` all take `(db/basis-t db)`; the only reader, `turn/fault-window`, takes `since` from the same `database` it passes to `error/occurred-since` | holds |
| 10 | a654ad995 keys the as-of | `as-of-key` = `[:seon.db/as-of 6ad08c20... 536873479]`; `seon.test/host` on an as-of value: 943 ms / 1,255 MB first, 0.04 ms / 7 KB second | holds |
| 11 | reach-maps patch applies to HEAD | `git apply --check` on a `git archive` of HEAD: applies; one new deftest (`a-new-branchs-first-attribute-map-costs-what-it-changed`). Not run through `bin/seon check` (outside this lane's no-JVM rule) | partly: applies, unrun |
| 12 | equal programs share one program context | deftest exists (`eval_test.clj:2880`); `seon.test.runner/latest-results` returns `[]` on default's head for it and the 4 other existing tests I looked up, so there is no stored run to cite | unconfirmed: exists, no readable stored run |

### The finding behind item 6: `datoms-diff` throws

`datahike.persistent_set/slot-datoms` calls `(aget (.slots branch) i)`, and `(.slots branch)` is null for a branch with no buffered slots. Reproduced on default with real values:

| call | result |
|---|---|
| `(datoms-diff fd v :aevt [:seon.program/definition-digest])`, fork point vs chain head | NPE `Cannot load from object array because "xs" is null` |
| same with `:analyzed-source-digest`, with `:aevt []`, with `:eavt []`, main vs chain `:eavt`, `with`-overlay vs its parent | NPE |
| fork point vs main head, `:aevt [:seon.program/definition-digest]` | works: 36 ms / 34 MB, `[778 1068]` |
| main vs chain, `:avet []` | works: 738 ms / 1,353 MB, `[24971 19990]` |
| `:eavt [1207986]` fork vs chain | works: 0.7 ms |
| a value against itself | `[0 0]` in 0.8 ms |

So the primitive both plans and the hybrid call "pinned and usable" does not work on all inputs at the pin (771a0c4b code is unchanged at 96000df8). It is data-dependent, so a unit test on one pair would pass. The producer fix is in the fork's `slot-datoms` (`(when-let [slots (.slots branch)] ...)` or equivalent) plus a regression in the fork; it is a fork bump, which AGENTS allows only inside root's scheduled restart batch. H2, H3, H4 and H7 depend on it. This replaces the hybrid's statement "no phase waits on a bump".

## 2. What landed that changes a phase

- H0 done: 199750825 made `branch-admission-end`, `branch-end` and `unmerged-count` caches keyed by head commit (and main's); proof is `seon.cluster.host-test/a-lifecycle-pass-after-one-branch-wrote-reads-only-that-branch`, not the hybrid's `db-flow-test/a-pass-reopens-only-the-woken-branch` (which does not exist and should not be filed). ea12725bf cleaned default to 13 branches. Residuals named in the commit: one head-record read per held branch (~8 ms for 128) and a recompare of each ended agent holding unmerged work (~20 ms each).
- Merge-step fixes (7e84ba763, a78d96713, 7d79cb7ce, e4b5bc410) all touched `src/seon/cluster/agent.clj`, `source.clj`, `registry.clj`, `merge.clj`, `task.clj`. `bin/lane status` shows no live claims, so the hybrid's "agent.clj is held by step-merge, send patches" is stale; `git diff HEAD` shows an unclaimed 4-line change in `src/seon/cluster/agent.clj` that must be committed or released by its owner before H1 claims it.
- b39d918a5: `analyzed-source-digest` is per declaration, so H3's attribute list and the 25-attribute revision set (item 3) already reflect it; `:seon.program/analyzed-source-digest` is the attribute that changes with a file edit, no longer the whole file. No phase text changes except that reach-maps.patch was written before this and its deftest must be re-run on it.
- datahike 96000df8 (node cache weighing) and konserve a2a24792 (LZ4 write path) are pinned and loaded; neither fixes the `slot-datoms` NPE, and node-cache weighing may move the cold numbers in H1 to H3.

## 3. Per phase: (b) deletions and callers, (c) red proof

| Phase | (b) Deletion callers at HEAD vs the phase's file list | (c) Red proof |
|---|---|---|
| H0 | Done (199750825). Its deletion (per-agent opens in `taskless-agents`) is converted. | Landed with `host-test/a-lifecycle-pass-after-one-branch-wrote-reads-only-that-branch` (exists, green). The named db-flow-test does not exist: drop it. |
| H1 | Covered: `adopt-cluster-declarations!` 1 caller (`agent.clj:1202`); `loaded-source` 5 uses in `sci/eval.clj` (hybrid said 23); `world-base` in `test.clj:2046,2238`; `loaded-source-drift` in `test.clj` (6 sites). NOT covered: `::loaded-connection` has 10+ uses in `sci/eval.clj` AND `cluster/boot.clj:76` AND 5 in `test/seon/sci/branch_execution_test.clj`; `:seon.test/loaded-source[-drift]` in `test/seon/test/one_request_test.clj` (4 sites); `registry.clj:777` docstring and `registry_test.clj:462` cite `world-base`. | New `eval-test/a-branch-program-context-is-built-on-the-loaded-commit-not-its-record`: would be new (unresolvable until written). Converted `test-test/a-branch-test-world-runs-the-jvm-loaded-program-not-its-fork-point-record`: exists. |
| H2 | Path error: `src/seon/program.clj` is `src/seon/program.cljc` (`own-changed-identities`, `changed-attributes`). `:seon.branch/base-t` has readers the list omits: `src/seon/test.clj:2130` (`branch-delta`), `registry.clj:366,386,390`, `resources/seon/schemas/seon.branch.edn`, `test/seon/db_test.clj:57`, `registry_test.clj:839`. `commit-at-t` has 1 caller, `src/seon/merge.clj:553`, not in the file list (hybrid said 5 uses). | `merge-forward-test/after-a-forward-merge-...` exists; the extension (equal-t siblings, retract/re-add row) is new rows; the `seon.profile/measure` on `source/merge-accepted` is new. Blocked by the diff NPE. |
| H3 | Covered: `reach-owner`, `reach-changed-entities`, `retire-replaced-revision!` all in `test/runner.clj` (868-932); `held-base` is `agent.clj:1204-1220` (a local, in `agent.clj`, which H3's list omits; it was H1's file). | `reach_test/a-new-branchs-first-attribute-map-costs-what-it-changed` is new, supplied by reach-maps.patch (applies on HEAD, unrun); `eval-test/an-overlay-plan-reads-...` exists, the measure row is new. Blocked by the diff NPE. |
| H4 | `unchanged-through-t`: 2 uses (`test.clj:1432,2965`; hybrid said 4). `carried-results`: `test.clj:2613,2862`. `changed-attributes` t-arity is also read in `test/runner.clj:1571` and `fn.clj:3133` (`fn.clj` is not in the file list). A schema attribute `:seon.test.run/commit` is absent (item 7). | `test-test/test-evidence-follows-content-not-transaction-ids`: new. Blocked by the diff NPE. |
| H5 | `branch-scope`: `render.clj` (3 sites) and `test/seon/render_cache_test.clj` (5 uses, must convert; the file list names only the new test). `call-reach-key`: `render.clj:845,879` and `test/seon/source_census_test.clj:28` (not listed). `seon.turn`/`seon.context` as-of conversion not verified here. | `render-cache-test/equal-inputs-render-once-across-branches`: new. `turn-test/recording-an-answer-does-not-answer-a-later-wake`: new. `:seon.agent/answered-t` absent, so a schema addition plus TASK-CHAIN amendment (owner Q4 first). |
| H6 | `base-branch` exists (`registry.clj:398`, used `:580,:638`, `registry_test.clj:756`, `test_test.clj:387`, `program_test.clj` name only). `base-commit-id` and `advance-base!` are NOT functions at HEAD (`base-commit-id` is an unrelated merge attribute; `advance-base!` appears only in task prose): the deletion list names things that do not exist. `base-t`: see H2. | `registry-test/an-idle-branch-keeps-the-commit-it-forked-from-through-collection` exists; the `retained-before` measure row is new and the 8.8 s figure is unremeasured. |
| H7 | `carry-world-projection!`: 4 uses, all `test.clj` (1999, 2014, 2295, 3209), covered. `program-overlay-tx` 2 callers in `test.clj`; `digest-differences` 3 uses, all `test.clj`. All covered. | `test-test/two-requests-at-equal-content-build-one-world-overlay`: new. Needs measuring first (hybrid says so). Blocked by the diff NPE. |

## 4. Verdict

**Phases ready to file as tasks (no correction needed beyond a file-list addition):**
- H1: add `src/seon/cluster/boot.clj`, `test/seon/sci/branch_execution_test.clj`, `test/seon/test/one_request_test.clj`, `src/seon/cluster/registry.clj` (docstring) to the files; replace "23 + 13 uses" with the real counts (5 `loaded-source` sites, 10+ `::loaded-connection` sites). Red proof is new but well defined; no diff needed.
- H5 (render half): `branch-scope` and `call-reach-key` conversions; add `test/seon/render_cache_test.clj` and `test/seon/source_census_test.clj` to the files. The answered-basis half waits for owner Q4.

**Phases that need a correction first:**
- H0: mark done; replace its red proof with host-test's; drop the db-flow-test name. Residual (head-record read per held branch, recompare of ended-with-unmerged) stays as a follow-up only if measured at the 125-branch scale, which default no longer holds.
- H2, H3, H4, H7: blocked on a fork fix. Correction: file a fork task first (root cause: `slot-datoms` reads `(.slots branch)` without a null check; regression: `datoms-diff` of a branch with null slots; reproduction above), a fork bump in root's next restart batch, then these phases. Also: H2 file path `program.cljc`, add `merge.clj` and `test.clj:2130`, `commit-at-t` has 1 caller; H4 add `fn.clj`, `unchanged-through-t` has 2 uses; H3 add `agent.clj`.
- H6: remove `base-commit-id` and `advance-base!` from the deletion list (not present at HEAD), list the real `:seon.branch/base-t` readers, remeasure `retained-before` before filing.
- Hybrid section 5 item 5 and the "125 branches" figure: restate as measured on default now (13 branches, 2.6 ms / 2.4 MB); item 3's "170 / 24" as 25 / 32.
- Item 8 verdict adds a deletion: the `open-branch!` monitor and its 8 callers move to `acquire-branch!`; file as a fence task (root cause: two holders of one branch; Datahike's count already provides one writer).
