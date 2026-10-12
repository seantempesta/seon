---
title: "Narrow check: hybrid-done-and-merge.md"
type: research
status: narrow check of hybrid-done-and-merge.md (824ad4afd, 6a7c7ded0) under AGENTS.md "Design by independent plans and a hybrid"; three questions only, no design changes. Source is unchanged between the hybrid's basis d7a9cc5b1 and HEAD 69c49b6b0 (`git diff d7a9cc5b1 HEAD -- src` empty); `src/seon/turn.clj` has uncommitted edits from lane program-context, none touching the symbols read here.
basis: default (archive 015673f47), agent `narrow-check` (SCI on its own branch, retired), jvm mode as root read-only with `seon.db/with`; symbols read in the working tree; Datahike read at the submodule pin ec907fb5 (the hybrid cites 42309184, an ancestor of the pin; every seam it cites is the same there).
---

# Narrow check: done is derived from proofs; root merges from main

Result: Q1 40 claims: 36 hold, 3 false (assign! `:no-proofs`; `prove-turn` records the fork point; `step-observation` carries the base result), 1 count drifted (branches 24/10 now 26/11). Q2 16 phases (M1..M15, M-final): 10 clean, 6 fail (M2, M3, M5, M6, M9, M14). Q3 16 phases: 11 sound, 5 with a defect in the named proof (M1, M3, M12, M14, M15). Failures first.

## Failures

1. **False live claim (§7 "Code read", edge 29).** "`assign!` names `:no-proofs` and does not check it." `seon.task/assignment-read`, called first by `seon.task/assign!`, refuses `:no-proofs` when the step's obligations are empty (`(empty? (get edges …))`). What `assign!` lacks is a refusal when every proof is green.
2. **M1's input is not built today.** §1 says the observation is "already built today": `fork point = results at the merge base, which seon.turn/prove-turn records`. `seon.turn/prove-turn` only runs the proofs (`run-proofs`) and records nothing about the base. `seon.turn/record-broken!` calls `seon.test/fork-point-red` only for the reds at the head (`reds`), so a proof that is green at the head has no base result anywhere, and `seon.context/step-observation` rebuilds the fork point as `(not (broken s))`, which is `true` for a proof red at the base and for one green at the base. M1 owns only `src/seon/context.clj` and its test, so the rule it tests cannot be fed on the live path (producer: `seon.turn/record-broken!`, `seon.context/step-observation`).
3. **M6 deletes what M7 still calls.** M6 deletes `:seon.task/merge-request-row` with `:merge-request`, `:merge`, `:of-assignment`, `:proved`, `:run-id`, `:program` and (necessarily) `:seon.task/merge-request-view`; `seon.cluster.source/land!` and `land-held!` carry `:seon.task/merge-request-view` in their `:malli/schema` and read `:seon.task/merge`, `:of-assignment` (source.clj 1627-1783) until M7; `seon.context` (398-420) and `seon.cluster.message` (239, 605, 643, 862) carry `:seon.task/merge` and offer `my.root/merge!`, and neither file is in M6's list. HEAD does not load after M6.
4. **M2 leaves the occurrence count's readers.** "Occurrence count derived" removes the stored `:seon.error.occurrence/count`, read in `seon.fault` (123, 132), `seon.error` (1271-1386, 1579, 1672, 2198-2226), `seon.problems` (143), `seon.render.transcript` (290) and six tests; none is in M2's files. If the int stays, it is a scalar both sides edit and merges as unmerged on every concurrent increment. The `:add` member of `:seon.cluster/conflict` is declared in `resources/seon/schemas/seon.cluster.edn`, which M2 does not list.
5. **M9 and M14 leave `seon.turn/fork-commit`.** It reads `:seon.task.attempt/fork` and `seon.cluster.registry/base-commit-id` (the `:base/` ref); M9 deletes the first and M14 the second, and `src/seon/turn.clj` is in neither file list (also `seon.cluster.source/merge-base` reads `base-commit-id`, source.clj 761).
6. **M3 leaves `squash/copy!` in `test/seon/cluster/registry_test.clj` (820)**, not in M3's files.
7. **M3's red proof needs M4.** `a-root-edit-after-capture-is-unmerged-not-overwritten` asserts "one unmerged", an entity M4 introduces.
8. **M15's red proof is not the stated one.** `seon.vocabulary-test/living-prose-names-no-retired-word` asserts the whole living prose clean of every CN entry (today `seon.vocabulary` reports remaining words under CN-1..CN-27); M15 cannot make it green alone, and the table has no `attempt`, `squash` or `:root` entry yet, so it does not test M15's renames.
9. **Phases that overlap filed tasks without naming them.** M3 supersedes `seon.cluster.squash/a-repeated-auto-merge-adds-nothing` (hybrid-push U1, which deletes the same `copy!`, `add-count`, `conflict-task!`, `squash-merge!`, `selected-coordinates`); M14 supersedes `seon.cluster.registry/a-merged-base-older-than-the-window-survives-collection` (U4, the same `:datahike.gc/roots`; M14's deftest is `a-merge-base-older-than-the-window-survives-collection`); M6 and M7 delete symbols named in the must-read of `seon.turn/a-merge-request-comes-only-from-a-step-no-task-needs` and `seon.test/change-reds-judges-a-test-main-does-not-hold-as-not-red-there`. Retirement of these tasks is in no phase.

## Q1. Live claims

| Claim | Verdict | Evidence |
|---|---|---|
| Symbols named in `basis` (squash, agent, turn, task, context, source, test, registry, `my.root`) | holds | each `(defn …)` found: e.g. `seon.cluster.squash/squash-merge` squash.clj:68, `seon.cluster.agent/db<->flow` :2204, `seon.turn/end-task!` :2293, `seon.task/frontier` :885, `seon.cluster.registry/advance-base!` :366. `seon.cluster.agent/plan` :1659 |
| §5 body line counts (257/73/100/105/84/20) | holds | counted per defn: unlanded-ends 20, land-ends! 40, record-error-end! 23, ended-problem! 14, settle-kept-branches! 22, successor-waits? 18, detached! 28, branch-end 10, open-merge-request? 8, agents-to-start 15, seed-attempt! 17; copy! 30, add-count 6, conflict-task! 14, squash-merge! 11, selected-coordinates 12; land-end! 24, land-ended! 13, end-merge-request 20, landing-tx 14, root-execution 9; request-merge! 24+41, red-first 20, attempt-rows-tx 20; land! 22, land-held! 41; change-reds 15; advance-base! 5 |
| Schema keys exist: `:seon.squash/attempt`, `/copy`, `/datoms`, `/conflicts`, `/conflict`, `:seon.task/merge-request-row`, `:of-assignment`, `:proved`, `:red-first`, `:run-id`, `:program`, `:seon.task.attempt/fork`, `/commit`, `/end`, `/target`, `/relaunch`, `:seon.config.root/land?`, `:seon.config.agent/keep-ended-branches?`, `:seon.config.db/snapshot-window-ms`, `:seon.program/partition`, `:seon.cluster/conflict`, `:seon.task/assignment`, `/outcome`, `/reason`, `/landed`, `/landed-git`, `/kind` | holds | rg over resources/seon/schemas and src, all nonzero |
| New names do not exist yet (`:seon.task/merge-base`, `:seon.merge/unmerged`, `:seon.config.root/auto-merge?`, `:seon.context/green-before`, `:seon.task/merge-commit`, `/git-commit`) | holds | 0 hits in src, resources, test |
| `:datahike.gc/roots` absent from the pinned reference | holds | 0 hits in `reference-code/datahike/src`; only a filed task names it |
| §7 P1 `step-status` answers `:done` for fork point `{x/p false}`, `{x/p true}`, `{}` | holds | `(seon.context/step-status {…})` on `narrow-check` → three `{:seon.task/status :done :seon.context/broken #{} :seon.context/new-red #{}}` |
| §7 P1 `step-status` 0.07 ms | holds (smaller) | jvm loop of 2000: 0.0013 ms |
| §7 P2 `:seon.config.root/land?` false, `keep-ended-branches?` false, window 3600000 | holds | `{:land #{[false]} :keep #{[false]} :window #{[3600000]}}` |
| §7 P2 0 merge requests, 0 assignment entities, 0 `:seon.squash/attempt` markers on main | holds | `{:mr 0 :assign 0 :marker 0}` |
| §7 P2 24 branches of which 10 `:base/` | not reproducible as a count | live now 26 branches, 11 `:base/` refs (drift: an agent branch was created); shape holds |
| §7 P2 `:seon.task/status` enum `[:working :requested :done :bailed :capped]` | holds | seon.task.edn:95 |
| §7 P3 disjoint additions to `:seon.task/evidence` conflict today | holds | task 52281, theirs +`docs/y.md`, ours +`docs/z.md`: `(squash-merge t b o proj nil)` → `{:tx-data [] :conflicts [{:attribute :seon.task/evidence …ours #{p z} :theirs #{p y}}]}` |
| §7 P4 fixed base loses the revert; advanced base gives the delta; repeat writes nothing | holds | `(squash-merge t2 b0 m1)` → `[]`; `(squash-merge t2 t1 m1)` → retract "b", add original; `(squash-merge t1 t1 m1)` → `{:tx-data [] :conflicts []}` |
| §7 P4 a root edit after capture becomes a conflict inside the writer, never an overwrite | holds | `seon.db/with` of `[:db.fn/call (fn [db] (:tx-data (squash-merge t2 t1 db proj nil)))]` over a db whose intent is "root edited": intent stays "root edited"; three-way reports `[{:ours "root edited" :theirs <original>}]` |
| §7 P4 `squash-merge` 0.24 ms | holds (smaller) | 0.108 ms per call, 200 calls |
| `copy!` computes the three-way before the writer, then one `db/transact!` | holds | squash.clj 132-160 |
| `:seon.squash/attempt` is the completion marker read by `unlanded-ends` and `settle-kept-branches!` | holds | agent.clj 2006, 2133 |
| `follow!` writes the `:cancelled` end on main (edge 9) | holds | agent.clj 1949-1952 |
| `apply-plan!`, `land-ends!` and `db<->flow` each catch and continue (edge 6) | holds | agent.clj 1970-1978, 2181-2185, 2306-2318 |
| `successor-waits?` counts every leaf needing the task, held or not (edge 22) | holds | agent.clj 2105-2122 (`(not [?t :seon.task/children _])` over `needs*`) |
| `accept-merge!` calls `seon.test/change-reds` | holds | source.clj 1047 |
| `seon.test/green-members` requires completed and terminated | holds | test.clj 789-801 |
| `basis-valid?` reads obligations at the base | holds | agent.clj 1705-1724 |
| `seon.db/transact!` routes `:parents` to `merge-db!` and carries `:datahike/expected-basis-t` | holds | db.clj 6096, 6342 |
| `merge-writer!` is `transact!` plus `:datahike/merge-parents` metadata | holds | datahike writing.cljc 1060-1068 |
| `force-branch!` takes `:expected-current-commit` and demands exclusive write access | holds | versioning.cljc 327-331 |
| `reachable-in-branch` follows parents only `in-range?` | holds | gc.cljc 48, 80 |
| `flow/stop` "shuts down the flow"; `stop-flow!` joins | holds | flow.clj 123; agent.clj 1540 docstring |
| Filed tasks `a-failed-start-ends-its-attempt-and-the-next-plans`, `a-capped-attempt-has-no-relaunch`, `each-intent-names-one-roster-state`, `the-real-chain-runs-with-no-nudges` in `seon.cluster.agent.edn`; their deftests absent | holds | resources/seon/tasks/seon.cluster.agent.edn 30, 70, 75, 80; 0 hits in test/ |
| `assign!` names `:no-proofs` and does not check it (edge 29) | **false** | task.clj 585 inside `assignment-read`, called at 672 |
| Fork point "is recorded by `prove-turn` before any model call" and read by `fork-point-red` for a red | **false as stated** | `prove-turn` (turn.clj 5308) only calls `run-proofs`; `record-broken!` (5470) calls `fork-point-red` for head reds only |
| `step-observation` is "already built today" with the base result per proof | **false as stated** | context.clj 204-222 rebuilds `fork-point` from `broken`/`new-red`: `[s (not (broken s))]` |
| `:seon.task/status` of an `:implement` step with all proofs green is `:done` at any fork point | holds | same P1 |
| `my.program/request-merge!` writes a merge task plus a request keyed `[assignment commit]` | holds | task.clj 766-790, `id/id [key commit]` |
| `seon.id/id [task number]` keys the assignment | holds | task.clj 600-618 |
| `plan` is the one starter among three (`plan`, `agents-to-start`, `host/start!`) | holds | agent.clj 1659, 2188, 2304; the P10 task states the same |
| M1 premise "a task may tag an existing regression test beside its new red one" is the project law | holds | CLAUDE.md "prefer tagging an existing failing test…" |
| Retired/renamed words listed in §1 exist in code (`land?`, `settle-kept-branches!`, `frontier`) | holds | rg |

## Q2. Deletions with their last caller

| Phase | Deleted | Remaining callers at that point | Verdict |
|---|---|---|---|
| M1 | nothing | none | clean |
| M2 | `add-count`, the `:add` rule, stored `:seon.error.occurrence/count` ("derived") | `add-count`: only `squash-merge` (squash.clj 109), converted. `:seon.error.occurrence/count`: `seon.fault` 123/132, `seon.error` 1277-1386/1579/1672/2198-2226, `seon.problems` 143, `seon.render.transcript` 290, tests `fault_storage_test`, `fault_message_test`, `listener_failure_test`, `turn_loop_test`, `schedule_test`, `program_test`, `schema/datahike_parity.edn`; `:add` in `resources/seon/schemas/seon.cluster.edn` enum, `test/seon/cluster/squash_test.clj` 34-48 | **fails** (not in M2's files) |
| M3 | `copy!`, `squash-merge!`, `selected-coordinates` | `copy!`: agent.clj 2165 (converted, "one call site"), `test/seon/cluster/registry_test.clj` 820 (not listed); `squash-merge!`: `db_flow_test` 551/554 (listed), prose in `ended-problem!` (agent.clj 2027, deleted M5); `selected-coordinates`: only `squash-merge`; docstring refs `seon.task` 538 and `agent` 2149 name `copy!`; `:seon.squash/copy`/`/datoms`/`/conflicts` returns still read in `registry_test` 823, `db_flow_test` 552/554 | **fails** (registry_test) |
| M4 | `conflict-task!` | its only caller `copy!` left in M3; `db_flow_test` 680 and `squash_test` 101 read the "squash-conflict" task (tests listed) | clean |
| M5 | `:seon.squash/attempt`, `unlanded-ends`, `branch-end`, `settle-kept-branches!`, `record-error-end!`, `ended-problem!`, `detached!`, `open-merge-request?`, the catches; `land-ends!` (not listed anywhere, but `detached!` and `turn/land-ended!` callers die with it) | `:seon.squash/attempt`: `db_flow_test` 480/509 (listed); its schema `resources/seon/schemas/seon.squash.edn` is not in M5's files; `detached!`: `db_flow_test` 780 (listed); `successor-waits?` becomes an unused private var until M9 (lint finding) because `next-actions` in §1 has no successor clause; agent.clj 1747 (`plan-facts` reads `:done` ends) is converted in no phase | **fails** (schema file, unused var, `:done` reader unowned) |
| M6 | both `request-merge!`, `red-first`, `end-merge-request`, `land-end!`, `land-ended!`, `landing-tx`, `root-execution`, `:seon.task/merge-request-row` (+ view and members), `:merge` kind, `land?`, `:done` from the end enum | `source/land!`, `land-held!` (contracts and reads of the deleted attributes, until M7); `seon.context` 398-420, `seon.cluster.message` 239/605/643/862 (`:seon.task/merge`); `task.clj` 875 `leaf-done` and agent.clj 1747 read `:done`; `.agents/skills/repl/SKILL.md` 146 and AGENTS.md name `my.program/request-merge!`; tests `merge_test` 287, `host_test` 428-455, `root_test` 23, `turn_continue_test` 449-689 (tests listed generically) | **fails** (HEAD does not load) |
| M7 | `change-reds`, `land!`, `land-held!`, `declarations-held-tx`, `accept-merge!`'s `:parents` | `change-reds`: source.clj 1047 (listed), `test_test` 362-386 (tests); `land!`: `my.root` 103 (listed), `merge_test` 315-561 (tests), turn.clj 74 (gone in M6); `script/astra_cost_handoff.clj` requires `seon.cluster.squash` (file moved here; script not listed); filed task `seon.test/change-reds-judges-…` not retired | clean on callers; script and task retirement unowned |
| M8 | nothing | none | clean |
| M9 | `:seon.task.attempt/fork`, `/commit` as fork basis, `successor-waits?`, `attempt-rows-tx`, `seed-attempt!`, predecessor clause, `(first (sort fork-point))` | `seon.turn/fork-commit` (turn.clj 5467) reads `:seon.task.attempt/fork`, not in M9's files; `task.clj` 629, 682 and agent.clj converted (listed) | **fails** (turn.clj) |
| M10 | nothing | none | clean |
| M11 | `follow!`'s `:cancelled` end write (implied) | none beyond agent.clj (listed) | clean |
| M12 | `agents-to-start`, `host/start!` graph start, held clause | `lazy_agents_test` 24-34 (tests); the P10 task `seon.turn/a-held-task-opens-no-model-turn` is the owner | clean |
| M13 | nothing named | none | clean |
| M14 | `:base/<branch>` ref, `advance-base!` | `advance-base!`: source.clj 1094 (gone in M7), `squash_test` 331 (tests); `base-commit-id`: turn.clj 5468, source.clj 761, registry.clj 407, `squash_test` 263/360; turn.clj and source.clj not in M14's files | **fails** (turn.clj, source.clj) |
| M15 | retired words, `seon.cluster.squash` → `seon.merge` | M7 already moves the file, so the rename sits in two phases; `script/astra_cost_handoff.clj` | clean once M7's script is owned |
| M-final | nothing | none | clean |

## Q3. Red proofs

| Phase | Named proof | Resolves | Tests data the phase changes | Red today | Verdict |
|---|---|---|---|---|---|
| M1 | `seon.context-test/a-proof-green-before-the-work-is-not-done` | file exists (`each-step-kind-is-done-by-its-one-rule` at 302) | yes, but its fork-point values are `true` = red at base; the live producer builds `true` for "not broken" (failure 2) | yes: `{x/p false}` and `{}` answer `:done` today (P1) | defect in the input meaning |
| M2 | `seon.cluster.squash-test/disjoint-set-additions-merge-as-their-union` | file exists | yes (`:tx-data`, evidence set) | yes (P3 conflict) | sound |
| M3 | `a-repeated-merge-adds-nothing`, `a-root-edit-after-capture-is-unmerged-not-overwritten`, `a-reverted-branch-value-reverts-on-main` | `squash_test`, `db_flow_test` exist | yes | yes (no `merge-tx`, no `:seon.task/merge-base`; revert lost, P4) | second asserts an entity from M4 |
| M4 | `a-conflict-keeps-its-three-values-on-the-merge-branch` | new test, files named | yes | yes (no `seon.merge.edn`) | sound |
| M5 | `a-merged-request-whose-graph-survived-a-crash-is-stopped-and-deleted`; filed `seon.cluster.agent/a-failed-start-ends-its-attempt-and-the-next-plans` | task exists (agent.edn 70); deftest absent | yes | yes (no deftest; the plan still replans the first action) | sound |
| M6 | `a-done-branch-merges-with-no-merge-request-written-by-the-agent`, `a-satisfied-task-closes-with-no-model-call` | new, `db_flow_test` | yes | yes | sound as proof; phase breaks HEAD |
| M7 | `a-merge-whose-reached-definition-moved-writes-nothing`, `a-prove-steps-designed-reds-pass-the-gate` | new | yes | yes | sound |
| M8 | `an-accepted-merge-is-published-by-the-next-pass` | new | yes | yes | sound |
| M9 | `a-join-forks-main-after-both-needs-merge`, `a-prove-steps-red-product-merges-into-main` | new | yes | yes | sound (blocked by turn.clj) |
| M10 | `a-sibling-merge-that-breaks-a-proof-undoes-done` | new | yes | yes | sound |
| M11 | `a-cancelled-assignment-closes-without-a-task-for-root`; filed `seon.cluster.agent/a-capped-attempt-has-no-relaunch` (agent.edn 75) and `seon.cluster.agent/each-intent-names-one-roster-state` (agent.edn 30) | both tasks exist; deftests absent | yes | yes | sound |
| M12 | `seon.turn-test/a-held-task-opens-no-model-turn` | task is `seon.turn/a-held-task-opens-no-model-turn` (seon.turn.edn 255); `test/seon/turn_test.clj` exists and is held by lane program-context | yes | yes (no deftest) | names the deftest, not the task by ns/name |
| M13 | `root-offers-a-sent-message-once-and-merges-its-record`, `an-undeliverable-message-is-answered-as-data` | new, `message_test` exists | yes | yes | sound |
| M14 | `a-merge-base-older-than-the-window-survives-collection` | new; nearly the filed `seon.cluster.registry/a-merged-base-older-than-the-window-survives-collection` | yes | yes (no deftest of either name in test/) | name duplicates a filed task |
| M15 | `seon.vocabulary-test/living-prose-names-no-retired-word` (hybrid says "finds no retired word on the path") | exists, untracked at HEAD | asserts all living prose, not the path | yes (report nonempty) | stays red after M15; no entry for the renamed words |
| M-final | `seon.cluster.agent/the-real-chain-runs-with-no-nudges` | task exists (agent.edn 80); deftest absent | yes | yes | sound |

The probe branch of agent `narrow-check` was retired (`my.agent/retire-branch!`, retiring now, unlinks when its run exits). Nothing was written to main; no source was loaded.

## Second check (f46d4e524), 2026-10-12

The revised hybrid (M1..M18 + M-final, owner answers in §4) was read in full; every symbol and file it names was looked up, and behavior was re-run on default (agent `narrow-check`, retired; jvm mode read-only). Counts: Q1 18 claims, 14 hold, 3 false, 1 not reproducible. Q2 19 phases, 16 clean, 3 fail (M6, M8, M10). Q3 19 phases, 17 sound, 2 with a defect (M1, M18). Five of the nine first-check failures are closed; the other four (2, 3, 8 and the deletion callers of 5) are closed except for the items below.

### Remaining failures

1. **M1: the test step cannot open zero model turns.** The second M1 deftest asserts `step-verdict` `:working` and "the loop opened zero model turns" for a branch whose proofs are all green at the base. `seon.turn/proved-unanswered?` (turn.clj 5350s) and `next-agent-work` (2360, 2441) open a model turn for any held `:running` task whose `seon.context/task-done?` is false, and with M1's rule that task is `:working`. M1 names no change to either, so the assertion contradicts the design until M8's `satisfied` close (the first draft's "stops calling the model" has the same gap).
2. **M1: the base result is only the newest result.** §1 says `fork-point-red` reads the head's own result at the base program's reach digest from `seon.test.runner/latest-results`. `latest-results` answers one execution per test, the newest, so the base-program result survives only until the proof reruns on changed code (reproduced by reading the function: one `reduce` keeping the last run per symbol); after that the lookup falls back to opening the fork commit, which the cost bound ("≤ 2 ms per proof, one indexed read") does not describe.
3. **M6: the rename leaves unconverted references.** `seon.cluster.squash` is named in seven task files (`seon.fault`, `seon.program`, `seon.cluster.agent`, `seon.cluster.source`, `seon.flow`, `seon.turn.world`, `seon.cluster.squash`) and in `docs/seon/architecture/vocabulary.md`; the `:seon/tasks` tags in `squash_test` are `seon.cluster.squash/…`. None is in M6's files, so the filed tasks U1/U3/U7 and the old squash task lose their identities (task ids derive from the namespace). `land!`/`land-held!` are "folded" in M6 but `seon.turn`'s `source-land!` delay (turn.clj 74) still resolves `seon.cluster.source/land!` until M8 (nil at call, not a load failure).
4. **M8 names the wrong schema file.** `:seon.config.root/land?` is defined in `resources/seon/schemas/seon.config.root.edn`; M8 lists `seon.config.edn` (no `config.root` entry) and `config/default.edn` (which holds the key). The dial's schema stays after its readers are deleted.
5. **M10 omits `resources/seon/schemas/seon.task.edn`.** `:seon.task.attempt/fork` is defined in `seon.task.attempt.edn` (listed) but the `:seon.task/assignment-row` map in `seon.task.edn` names it too (line 148 area), so deleting the attribute leaves a dangling schema reference.
6. **M18's red proof does not test M18.** `seon.vocabulary/report` takes only `roots` (no entry selection); a test must filter `:totals` by CN id. The entries for attempt, copy and `:root` do not exist until M18 adds them, so for those the filtered test is green before the phase; only land (CN-26) and settle (CN-7) can be red today (CN-26: 11 remaining occurrences).

### Q1. Live claims

| Claim | Verdict | Evidence |
|---|---|---|
| `assign!`/`assignment-read` refuses `:no-proofs`, no all-green refusal (corrected) | holds | task.clj 569-586 |
| `prove-turn` only runs proofs; `record-broken!` asks `fork-point-red` for head reds only; `step-observation` builds `[s (not (broken s))]` | holds | turn.clj 5308, 5470-5495; context.clj 204-222 |
| The runner records `:seon.error/type` since be32e7501 and `proof-failures` carries it | holds | `git cat-file -t be32e7501` commit; runner.clj 188-208; context.clj `failure-keys` |
| `seon.context/designed-red?` "exists" | holds, private | `defn-` at context.clj 118; M7 makes it public (listed) |
| `fork-point-red` can read the head's own base-program result via `latest-results` keyed by reach digest | **false as stated** | `latest-results` keeps the newest run per test (runner.clj 2195-2240) |
| M1 test step yields zero model turns for proofs green at the base | **false** | `proved-unanswered?` / `next-agent-work` (failure 1) |
| `my.program/diff`, `declaration-diff`, `history`, `seon.program/exact-replacement-tx`, `changed-identities`, `seon.context/current-state` exist; `restore!`, `:seon.agent/green-before`, `:seon.context/changes`, `src/seon/merge.clj`, `seon.merge.edn` do not | holds | program.clj 340, 878, 928; program.cljc 853, 1629; context.clj 424; 0 hits for the rest |
| `changed-identities` ≈ 0.8 ms warm; M14 bound ≤ 5 ms per eval request | holds | 0.057 ms for a two-change diff (200-call loop on default) |
| `:seon.error.occurrence/count` readers `seon.fault`, `seon.error`, `seon.problems`, `seon.render.transcript` stay untouched with `:add` kept; hybrid-push recorded base 1, source 2, main 2 → 3 | holds | rg; hybrid-push.md line 38 |
| All 18 filed tasks in §5 exist by ns/name | holds | each found in resources/seon/tasks (U1, U7, U0, U3, P7, U2, four `seon.turn`, change-reds, P8, P9, P10, U5, U4, frontier, P-final) |
| `:seon.config.root/land?` lives in `seon.config.edn` | **false** | `seon.config.root.edn` line 1; failure 4 |
| `seon.vocabulary` report can be restricted to six entries | **false** | `report` signature `[roots]`; failure 6 |
| `seon.cluster.squash` requirers are `agent`, `task`, the script and three tests | **false (incomplete)** | seven task files and vocabulary.md also name it; failure 3 |
| `seon.turn/fork-commit` reads `:seon.task.attempt/fork` and `registry/base-commit-id`; `merge-base` and `fork!` read `base-commit-id` | holds | turn.clj 5467-5468; source.clj 761; registry.clj 403-407, 517, 562 |
| `fork-commit`'s fallback is gone once every agent has an assignment after M13 | not reproducible | design claim; outside agents such as `narrow-check` hold no assignment today |
| `seon.task/create!` docstring is the `copy!` reference in task.clj (M3 calls it `delete-branch!`'s) | holds with a mislabel | task.clj 538 is `create!`; the file is listed |
| P1, P3, P4 as before | holds | reproduced in the first check |
| `:add` exact at an advanced base | holds | P4 plus hybrid-push line 38 |
| Source unchanged since the first check | holds | `git diff d7a9cc5b1 HEAD -- src` empty |

### Q2. Deletions with their last caller (revised order)

| Phase | Deleted | Remaining callers at that point | Verdict |
|---|---|---|---|
| M1, M2, M9, M11, M14, M15, M16, M-final | nothing | none | clean |
| M3 | `copy!`, `squash-merge!`, `selected-coordinates` | `agent.clj` 2165, `registry_test` 820, `db_flow_test` 551-554 and docstrings 538, 2149 all in M3's files; the `:seon.squash/attempt` writer between M3 and M5 is unstated (`unlanded-ends` would re-select ended attempts each pass; idempotent, HEAD boots) | clean |
| M4 | `conflict-task!` | `db_flow_test` 680, `squash_test` 101 listed | clean |
| M5 | `land-ends!`, marker and `seon.squash.edn` entries, `unlanded-ends`, `branch-end`, `settle-kept-branches!`, `record-error-end!`, `ended-problem!`, `detached!`, `open-merge-request?`, `successor-waits?`, the catches | `successor-waits?` callers are `land-ends!` and `settle-kept-branches!`, both gone (no unused var); `plan-facts` `:done` reader stated alive until M8; `turn/land-ended!` loses its only caller here and dies in M8 | clean |
| M6 | none (rename, fold) | seven task files, vocabulary.md, `:seon/tasks` tags (failure 3) | **fails** |
| M7 | `change-reds`, `declarations-held-tx`, `:parents` | `source.clj` 1047 and `test_test` 362-386 listed; task `change-reds-judges-…` retired in §5 | clean |
| M8 | both `request-merge!`, `red-first`, `end-merge-request`, `land-end!`, `land-ended!`, `landing-tx`, `root-execution`, request row and view, `:merge` kind, `land?`, `:done` | `source.clj` readers moved in M6; `seon.context`/`message` moved in M6; `leaf-done`, `plan-facts` and `done?` converted here; AGENTS.md and repl SKILL.md listed; `seon.config.root.edn` unlisted (failure 4) | **fails** |
| M10 | `:seon.task.attempt/fork`, `attempt-rows-tx`, `seed-attempt!`, predecessor clause | `fork-commit` (turn.clj patch), `task.clj` 629/682, agent.clj listed; `seon.task.edn` `assignment-row` unlisted (failure 5) | **fails** |
| M12 | `follow!`'s main write | agent.clj listed | clean |
| M13 | `agents-to-start`, `host/start!` graph start | `lazy_agents_test` 24-34 listed | clean |
| M17 | `:base/` ref, `advance-base!`, `base-commit-id` readers | registry.clj 353-370, 403, 517, 562; `source.clj` 761, `fork-commit` fallback, `squash_test` 263/331/350-365 all listed | clean |
| M18 | retired words | scripted sweep; `:root` default in `program.cljc` 98 and the `:conflict` enum in `seon.cluster.edn` fall in the sweep | clean |

### Q3. Red proofs

| Phase | Proof | Verdict |
|---|---|---|
| M1 | `seon.context-test/a-proof-green-before-the-work-is-not-done` red today (P1: all three shapes `:done`); `seon.turn-test/the-test-step-records-the-proofs-green-at-the-base` red by absence but asserts zero model turns | **defect** (failure 1) |
| M2 | `seon.cluster.squash-test/disjoint-set-additions-merge-as-their-union` red (P3) | sound |
| M3 | four deftests; the U1 and U7 tasks exist, the first deftest carries U1 | sound (the "unmerged" dependency on M4 is gone) |
| M4 | `a-conflict-keeps-its-three-values-on-the-merge-branch` | sound |
| M5 | `a-merged-assignment-whose-graph-survived-a-crash-is-stopped-and-deleted`; P7 exists with no deftest | sound |
| M6 | `my.root-test/a-merge-is-keyed-by-the-assignment` (`test/my/root_test.clj` exists) | sound |
| M7 | `a-merge-whose-reached-definition-moved-writes-nothing`; `a-prove-steps-designed-reds-pass-the-gate` (typed vs untyped red matches the runner, which sets the type only from `ex-data`) | sound |
| M8 | `a-done-branch-merges-with-no-merge-request-written-by-the-agent`, `a-satisfied-task-closes-with-no-model-call` | sound |
| M10 | `a-join-forks-main-after-both-needs-merge`, `a-prove-steps-red-product-merges-into-main` | sound |
| M11 | `a-sibling-merge-that-breaks-a-proof-undoes-done` | sound |
| M12 | `a-cancelled-assignment-closes-without-a-task-for-root`; P8 and P9 tasks exist | sound |
| M13 | the deftest tagged `seon.turn/a-held-task-opens-no-model-turn` (task exists, named by ns/name now) | sound |
| M14 | `seon.context-test/the-current-state-shows-the-branch-diff-since-its-merge-base` | sound |
| M15 | `my.program-test/restore-returns-an-entity-to-its-merge-base-value` | sound |
| M16 | U5 `seon.cluster.message/root-offers-a-sent-message-once-and-merges-its-record` exists | sound |
| M17 | U4 `seon.cluster.registry/a-merged-base-older-than-the-window-survives-collection`, deftest takes the task's name | sound |
| M18 | `seon.vocabulary-test/the-merge-path-names-no-retired-word` | **defect** (failure 6) |
| M-final | `seon.cluster.agent/the-real-chain-runs-with-no-nudges` | sound |

## Third check (2b813c4ec), 2026-10-12

Scope: the six second-check failures and every line `git diff f46d4e524 2b813c4ec` touched (M1, M6, M8, M10, M18, §5 rows, §7 code read). Verified at the code in the working tree (source unchanged since d7a9cc5b1) and by reading the runner and turn loop; nothing loaded, no main write. Counts: Q1 14 claims, 11 hold, 3 false. Q2 19 phases, 18 clean, 1 fails (M8). Q3 19 phases, 17 sound, 2 with a defect (M1, M18). Four failures remain: 1 DESIGN, 3 MECHANICAL.

### The six second-check failures

| # | Failure | Status in 2b813c4ec |
|---|---|---|
| 1 | M1 zero model turns | `:satisfied` status and the `proved-unanswered?`/`next-agent-work` edit are named and land in `turn.clj` lines 2360 and 2441, the only two `(not (task-done? …))` sites; but see remaining failure A: the judgment is never written on that pass |
| 2 | M1 base result lost to `latest-results` | closed: `seon.test.runner/result-at-reach` (new, absent today) reads the retained run by reach digest; `test/seon/test/runner_test.clj` exists for its proof |
| 3 | M6 rename holders | closed: exactly seven task files plus `docs/seon/architecture/vocabulary.md` plus requirers `agent`, `task`, the script, `registry_test`, `db_flow_test`, `squash_test` hold `seon.cluster.squash` (rg over the tree outside docs/research, tmp, reference-code); all are in M6's list; `land!` stays as a one-line caller for `turn.clj` 74 until M8 |
| 4 | M8 wrong schema file | closed: `seon.config.root.edn` (key `:land?`) is named; but see remaining failure C |
| 5 | M10 `seon.task.edn` | closed: `assignment-row` names `:seon.task.attempt/fork` (seon.task.edn 108) and the file is listed |
| 6 | M18 proof independent of `report` | wording present but misplaced; see remaining failure B |

### Remaining failures

A. **M1: nothing writes `:seon.agent/green-before` on the pass that must idle. DESIGN.** The M1 deftest `seon.turn-test/a-task-satisfied-at-the-base-opens-no-model-turn` asserts that after one pass of a branch whose only proof was green before any form, the agent holds `:seon.agent/green-before #{'a/t}` and `step-verdict` is `:satisfied`. That pass is `prove-turn`, which reports `(report :closed 0)` with zero forms run; `step-due?` (turn.clj 5260) is `(pos? forms-run)`, so `test-step` and `record-broken!` (the only writer of `broken`/`new-red`, and M1's writer of `green-before`) never run on it. `step-observation` therefore has no `green-before` and `step-status` cannot answer `:satisfied`. Where the base judgment is made for a pass with no forms (the prove pass, or on read in `step-observation`) is a decision M1 does not state.

B. **M18: the row was not updated; its new text sits in §5. MECHANICAL.** The M18 row in §6 (line 184) still carries the old proof `…/the-merge-path-names-no-retired-word` and "`seon.vocabulary`'s report restricted to those six entries" (false: `report` takes only roots). The new M18 text and the proof `seon.vocabulary-test/the-merge-path-names-no-retired-identifier` were pasted into the `seon.task/the-frontier-is-the-leaves-whose-needs-are-done` row of the §5 filed-task table (line 158), and the P-final row of that table became `| M-final | reuse as filed |` (line 159), dropping `seon.cluster.agent/the-real-chain-runs-with-no-nudges`. Also, the proof says it asserts empty the files under `src`, `test`, `resources/seon` and `script` holding the identifiers; its own file, `test/seon/vocabulary_test.clj` (untracked, lane root-docs), would hold them in its table, so it must build the names or exclude itself.

C. **M8: living docs still name the deleted dial. MECHANICAL.** `docs/seon/plan.md` 22 and 205 (`seon.cluster.source/land!`, `:seon.config.root/land?`), `docs/seon/schedule/cards/TASK-CHAIN.md` 208 (`land-end!`) are prose the vocabulary report scans and would be false after M8; only AGENTS.md and the repl SKILL.md are listed.

### Q1. Claims

| Claim | Verdict | Evidence |
|---|---|---|
| `result-at-reach` is new; `latest-results` keeps the newest run per test | holds | 0 hits for `result-at-reach` in src; runner.clj 2195-2240 |
| `:satisfied` is new in the `:seon.task/status` enum (seon.task.edn 95), file listed | holds | enum is `:working :requested :done :bailed :capped` |
| `proved-unanswered?` and `next-agent-work` are the only turn openers keyed on `task-done?` | holds | turn.clj 2360, 2441 |
| The agent's test step writes the base judgment on the zero-form pass | **false** | turn.clj 5313-5326, 5260, 5782 (failure A) |
| Seven task files + vocabulary.md + requirers are all the `seon.cluster.squash` holders | holds | rg |
| `seon.turn`'s `source-land!` delay resolves `seon.cluster.source/land!` (so M6 keeps `land!`) | holds | turn.clj 73-74, 2272 |
| `:seon.config.root/land?` is declared in `seon.config.root.edn`; readers in `turn.clj`, `agent.clj`, `config/default.edn`, `turn_continue_test` | holds | rg |
| `assignment-row` in `seon.task.edn` names `:seon.task.attempt/fork` | holds | seon.task.edn 108 |
| `seon.vocabulary/report` takes only roots | holds | vocabulary.clj 53-65 |
| `test/seon/test/runner_test.clj` exists | holds | ls |
| `latest-results` measured 148 executions of 14 tests in 3.9 ms | holds | docstring, runner.clj 2203-2207 |
| §5 net lines (−640, +355, net −285) | holds | arithmetic over §5 |
| §5 filed-task table is intact | **false** | lines 158-159 (failure B) |
| M8's list holds every holder of the `land?` dial's prose | **false** | plan.md, TASK-CHAIN.md (failure C) |

### Q2. Deletions with their last caller

All phases as in the second check except: M6 clean (task files, vocabulary.md, tags, `land!` caller kept); M10 clean (`seon.task.edn` listed); M8 fails on failure C only (code callers all listed: `source-land!` delay and `land!` deleted together; `seon.config.root.edn` named; `merge_test` converted in M6 for its `land!` uses). M1, M2, M4, M5, M7, M9, M11-M18, M-final clean.

### Q3. Red proofs

M1 defect (failure A; its first two deftests are sound and red today: `result-at-reach` absent, and `{'a/t false}` answers `:done` today, expected `:satisfied`). M18 defect (failure B). Every other phase sound as in the second check; M6's proof is unchanged and red by absence.

## Fourth check (85b8203a3), 2026-10-12

Scope: M1 as redesigned (derived on read, no `:seon.agent/green-before`), every line `git diff 2b813c4ec 85b8203a3` changed (status, §1, §2, §3, §5, §6 M1/M8/M18, §7 P5), and confirmation that third-check failures B and C (f70c98ee8) are closed. Also read: commit 2ef43687f (`frontier` → `ready`), which landed in `src` between the checks. Reproduced on default with agents `narrow-check` and `narrow-check-4` (SCI on their own branches; jvm mode read-only); nothing loaded into default, no main write. Counts: Q1 17 claims, 12 hold, 5 false. Q2 19 phases, 19 clean on callers. Q3 19 phases, 18 sound, 1 defect (M18). Three failures remain, all MECHANICAL; the third-check DESIGN failure A is closed by the redesign.

### Third-check failures

| # | Status |
|---|---|
| A (DESIGN) no writer on the zero-form prove pass | closed by design: nothing is written; `step-observation` derives the base result on read. The mechanism reproduces (below) |
| B (M18 row, §5 rows, self-referencing proof) | closed in f70c98ee8: §6 M18 row carries `the-merge-path-names-no-retired-identifier` and the build-from-parts wording; §5 frontier and P-final rows restored. A new staleness in the same rows, failure 3 |
| C (M8 living docs) | closed: M8 now lists `docs/seon/plan.md` and `docs/seon/schedule/cards/TASK-CHAIN.md`; their lines (plan.md 22, 205; TASK-CHAIN.md 208) exist |

### Remaining failures

1. **M1: `seon.db/commit-database` is not new. MECHANICAL.** M1 and §1 add "`seon.db/commit-database`, new, over `datahike.api/commit-as-db`". `seon.cluster.source/commit-database [store commit-id]` already exists (source.clj 290, `d/commit-as-db`) and has callers in `cluster.clj`, `boot.clj`, `branch_evidence.clj`, `my.program` and a cost test. M1 must name it and say why a branch-value wrapper (`basis-valid?` already does `d/commit-as-db (db/schema-database database) commit …`) is not it.
2. **M1: the cost numbers are for a toy closure. MECHANICAL.** §7 P5 and the M1 bound give `reach-digests` 0.02-0.08 ms and `step-verdict` ≤ 2 ms for three proofs. Measured on default for two real tests (`seon.context-test/each-step-kind-is-done-by-its-one-rule`, `seon.cluster.squash-test/a-copy-of-an-unchanged-branch-is-empty`): `commit-as-db` of main's head on the branch's database 0.72 ms; `reach-digests` there 1.6 ms first call, 1.0-1.1 ms repeated, also on a fresh value of the same commit; the first computation of those digests took 2.1 s and 4.0 GB. With `result-at-reach` at ≤ 0.5 ms per proof the components for three proofs are about 3.5-5 ms, above the stated bound. The digests themselves match (below), so the mechanism stands; the bound and the 0.02-0.08 ms figure do not.
3. **Stale names after `frontier` → `ready` (2ef43687f). MECHANICAL.** `seon.task/frontier` and `frontier-rules` no longer exist (`ready`, `ready-rules`, task.clj 871-909), and the filed task is now `seon.task/the-ready-leaves-are-those-whose-needs-are-done`. The hybrid still names `frontier-rules` (§1 data row for `:seon.task/outcome`; M8 "`leaf-done` (`frontier-rules`)"), the task `seon.task/the-frontier-is-the-leaves-whose-needs-are-done` (§5 row, line 158), and M18's sweep and proof entries `seon.task/frontier`, `frontier-rules`, so the M18 proof is no longer "red today for every entry"; only `seon.task.attempt/`, `attempt-tx`, `retire-branch!` and `:root` remain red.

### Q1. Claims

| Claim | Verdict | Evidence |
|---|---|---|
| §7 P5: a branch's members for one test sit at two reach digests with different results, while `latest-results` answers only the newest | holds | agent `narrow-check-4`: `f-is-one` members `[… "276c7f" pass 1 fail 0]` and `[… "618050" pass 0 fail 1] ` after `(defn f [] 1)` then `(defn f [] 2)`; each eval request ran a test step (363-496 MB allocated, 238-301 ms) |
| Main holds 0 test runs and 0 members | holds | datalog over `:seon.test.run/id`, `:seon.test.member/symbol` on default: 0 and 0 |
| `seon.test.runner/result-at-reach` is new | holds | 0 hits |
| `seon.db/commit-database` is new | **false** | `seon.cluster.source/commit-database` exists (failure 1); no `seon.db/commit-database` |
| The reach digest in the merge-base value equals the digest the branch recorded for an untouched closure | holds | both tests: `reach-digests` on `commit-as-db` of main's head and on the branch database are equal (`274d6b…`, `8142ba…`) |
| `commit-as-db` of the base 0.5-0.85 ms | holds | 0.72 ms |
| `reach-digests` 0.02-0.08 ms warm | **false for real tests** | 1.0-1.6 ms for two (failure 2) |
| `step-verdict` ≤ 2 ms for three proofs | **false by components** | failure 2 |
| `seon.turn/fork-commit` is private and `seon.context` already requires `seon.turn` | holds | turn.clj 5465 `defn-`; context.clj 20 |
| `prove-turn` runs no form, so `step-due?` is false and no test step runs on it | holds | turn.clj 5260, 5313-5326, 5782 |
| `fork-point-red` records its fork run on a child it then releases; the runner already records `:seon.test.run/tested-branch` | holds | test.clj 3045-3075; runner.clj 1475, 1765 |
| `reach-digests` over a set containing a test absent from the value does not refuse the whole answer | holds | returned a map for `[existing, nc4.scratch/f-is-one]` on main |
| `seon.task/the-frontier-is-the-leaves-whose-needs-are-done` and `seon.task/frontier` exist | **false** | renamed (failure 3) |
| M18 proof is red today for every entry | **false** | same |
| Test files `test/seon/test/runner_test.clj`, `test_test.clj`, `context_test.clj`, `turn_test.clj` exist | holds | ls |
| §5/§6 rows B (frontier, P-final, M18) restored | holds | lines 158-159, 184 |
| M8 lists `plan.md` and `TASK-CHAIN.md` | holds | M8 row |

### Q2. Deletions

No phase deletes something with a remaining caller. M1 deletes nothing (no `green-before` attribute was ever added; `fork-commit` goes public). The stale `frontier` names in M8 and M18 are failure 3, not callers.

### Q3. Red proofs

M1's four deftests are sound: `seon.test.runner-test/a-result-at-the-base-reach-digest-survives-later-runs` (red, `result-at-reach` absent; the mechanism reproduced), `seon.context-test/a-proof-green-before-the-work-is-not-done` (red: `{'a/t false}` answers `:done` today), `seon.turn-test/a-task-satisfied-at-the-base-opens-no-model-turn` (red: `step-verdict` answers `:done` today, P1), `seon.test-test/a-fork-point-run-is-recorded-once-on-the-agents-branch` (red: the fork run is recorded on the released child). M18 has the defect of failure 3; every other phase is sound as before.
