---
title: "Pre-brief for hybrid phase M4 (conflicts as seon.merge/unmerged, the merge branch, conflict-task! deleted)"
type: research
status: prebrief, 2026-10-12. Read-only research; no source, test or resource was edited. Basis HEAD bc347d583 (M6 done: 7c3077b9e, 3da1f6449, 59a1ffe1f). Default runs b14dc2b3f; nothing here ran on default, and no test was run. Test status below is from m6-notes.md and reading, not from a run.
basis: hybrid-done-and-merge.md §6 row M4 and §2 (the `:seon.merge/unmerged` entity), m6-notes.md "For M8 and M4", m4-notes.md (the earlier stop), prebrief-m5-m7.md (format)
plan: docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md §6 (M4)
---

# Pre-brief for M4

## Where M4 starts, at HEAD

- `seon.merge/merge!` (`src/seon/merge.clj`) runs `seon.merge/merge-tx` only inside main's writer, as the
  `:db.fn/call` of one transaction that also moves `:seon.task/merge-base` to C and closes the assignment. The
  facts three-way, `seon.merge/squash-merge`, runs there on main's own database value. Its `:root` conflicts keep
  main's value and come back in `:seon.merge/conflicts` of the merge's answer.
- `seon.merge/conflict-task!` (private, called twice in `merge!`, once per branch of its cond) writes root's prose
  problem task `squash-conflict-<id>` on main after the transaction, one task per conflict, named by
  `seon.id/id [:squash-conflict entity attribute]`. This is the shape M4 replaces.
- The merge branch exists only on the requested path: `seon.cluster.source/prepare-merge!` forks a candidate
  branch S from the head (`registry/branch!`) to gate declarations, then `accept-merge!` carries `merge-tx` in its
  own transaction and `retire-branch!` deletes S. The unrequested path (`(nil? request)` in `merge!`) has no
  branch at all: it transacts `merge-call` on `connection` directly.
- So at HEAD there is no merge branch for facts. M6's note says so and asks M4 to put the facts three-way on one
  (see Risk 1). The hybrid's M4 row names only `seon.merge/conflict-task!` as the deletion.

## 1. Every symbol, schema key and task M4 changes or deletes, with its callers

| Name (fully qualified) | Where it lives | Change in M4 | Callers and references (grep at HEAD) |
|---|---|---|---|
| `seon.merge/conflict-task!` | `src/seon/merge.clj` (private, `defn-`) | **delete** | `seon.merge/merge!` (two calls, lines 280 and 310 of the file); its docstring names `seon.merge/squash-merge` (no caller). Task `seon.merge/a-conflict-keeps-its-three-values-on-the-merge-branch` lists it in `:must-read`. |
| `seon.merge/merge-tx` | `src/seon/merge.clj` | change: writes `:seon.merge/unmerged` for each `:root` conflict in the same transaction (or, per Risk 1, the conflicts move elsewhere) | `seon.merge/merge!`; `src/my/root.clj` docstring (line 58); `src/seon/task.clj` docstring (line 844); `src/seon/cluster/agent.clj` comment (line 1916); tests `test/seon/merge_test.clj` 475 and 545 (`a-repeated-merge-adds-nothing`, `a-merge-stays-within-its-cost-bound`). |
| `seon.merge/merge!` | `src/seon/merge.clj` | change: its answer's `:seon.merge/conflicts` is replaced by the unmerged entities (the answer keeps the assignment, base, merge commit, outcome) | `src/my/root.clj` (line 105, `my.root/merge!`); `src/seon/cluster/source.clj` (line 1716, `land!` one-line caller, `requiring-resolve`); `src/seon/cluster/agent.clj` (line 2192, `merge/merge!` in the db<->flow `:merge` action); `src/seon/task.clj` (line 545 docstring); tests `test/seon/merge_test.clj` 446 (`merged!`), `test/seon/cluster/db_flow_test.clj` 594, 633 (and comment 716), `test/seon/cluster/registry_test.clj` 830 (and comment 800). |
| `seon.merge/squash-merge` | `src/seon/merge.clj` | unchanged in M4 (its `:conflicts` shape is the input). M2 and M7 do not touch it. Note: `:seon.squash/conflict` appears inline in its malli `:conflicts` vector. | `seon.merge/merge-tx`; `test/seon/merge_test.clj` lines 85–88, 109, 123, 139, 154–155, 189, 205, 224, 259, 279, 347, 370; `script/astra_cost_handoff.clj` 33. |
| `seon.merge/add-count`, `member-merge` | `src/seon/merge.clj` | unchanged | `squash-merge` only (add-count also `merge-tx` callers via the counter). |
| `:seon.merge/conflicts` (schema key) | `resources/seon/schemas/seon.merge.edn` (line 9 and the `:conflicts` entry at line 4) | **replaced** by `:seon.merge/unmerged` (entity, members `attribute`, `entity`, `base`, `ours`, `theirs`, `source-commit`, identity `seon.id/id [assignment attribute entity-identity]`) | `seon.merge/merge-tx` and `squash-merge` malli; `seon.merge/merge!` answer (malli `:seon.merge/merged`, `:merged` key in the same file); `test/seon/merge_test.clj` 497 (reads `(:seon.merge/conflicts merged)`); `docs/seon/specs/merge.md` line 105 (names the key in an example answer). |
| `:seon.squash/conflict` (schema) | `resources/seon/schemas/seon.squash.edn` (line 8, `:conflict`) | **delete** (the hybrid retires it; `:seon.merge/unmerged` replaces it). M5 does not list it; it is M4's. | `src/seon/merge.clj` 132 (`conflict-task!`'s malli, deleted with it); `resources/seon/schemas/seon.merge.edn` line 4 (`:conflicts` vector); `resources/seon/schemas/seon.squash.edn` line 8 itself. |
| `:seon.merge/unmerged` (schema key, NEW) | `resources/seon/schemas/seon.merge.edn` (file exists, see below) | **new**, declared at its producer (`seon.merge/merge-tx`'s writer or the branch writer, whichever Risk 1 picks) | none yet. The task file's `:seon.task/intent` says "no `:seon.merge/` attribute exists in the registry"; that is still true for attributes, since the file only holds the map schemas `:execution`, `:conflicts`, `:merged`. |
| `:seon.cluster/conflict` enum, value `:root` | `resources/seon/schemas/seon.cluster.edn` line 1 | **unchanged in M4** (renamed `:unmerged` in M18, by the word sweep). Its description still says "root reports while keeping main" and M4 must not contradict it. | `seon.program/cluster-attributes` (`src/seon/program.cljc` 103) and `squash-merge`'s `:root` branch (`src/seon/merge.clj` 122). |
| `:seon.merge/execution`, `:seon.merge/merged` | `resources/seon/schemas/seon.merge.edn` | `:merged` changes its `:seon.merge/conflicts` member | `seon.merge/merge!`'s malli; `src/seon/cluster/source.clj` (`:seon.source/merging` comment 1011). |
| Task `seon.merge/a-conflict-keeps-its-three-values-on-the-merge-branch` | `resources/seon/tasks/seon.merge.edn` (entry 14–17) | the task is M4's; its `:must-read` names `seon.merge/conflict-task!` (deleted) and `seon.merge/squash-merge`, `seon.program/three-way`, `seon.id/id`. Its `:intent` names `resources/seon/schemas/seon.merge.edn` as new (already present). Its red proof, `seon.merge-test/a-conflict-keeps-its-three-values-on-the-merge-branch`, **does not exist** (see §2). | `:seon/tasks` tags in `test/seon/merge_test.clj` name other tasks only. |
| Task `seon.cluster.squash/conflict-task` problem tasks, named `squash-conflict-<id>` | written by `conflict-task!` | the test reading them, `test/seon/cluster/db_flow_test.clj` 767–787 (`problems` counts tasks whose sym text includes `squash-conflict`), is converted to the unmerged entity | `test/seon/merge_test.clj` 101 (fixture `task/task-handle 'fixture.tasks "squash-conflict"` is only a name, not the deleted task). |
| `my.root/merge-requests` / `my.root/merge!` display | `src/my/root.clj` | the hybrid wants `my.root/merge-requests` to show each unmerged coordinate with its three values (hybrid §2 and the Q-a note). Not in M4's file list; decide with the lane whether M4 reads `:seon.merge/unmerged` here or leaves it to M8. | `src/my/root.clj` 58, 87, 105. |
| Docs | `docs/seon/specs/merge.md` line 105; `docs/seon/plan.md` (M-table row names M4); `docs/seon/architecture/vocabulary.md` (conflict entries, M18) | update the example key in `specs/merge.md` in M4; plan row status | — |

The `:seon.merge.edn` file exists already (created by M6 and earlier phases, holding `:execution`, `:conflicts`, `:merged`). The hybrid's "(new)" in the M4 row is out of date; M4 edits the file, it does not create it.

## 2. Tests reaching these, and the red proof

**The named red proof does not exist.** `seon.merge-test/a-conflict-keeps-its-three-values-on-the-merge-branch` has no `deftest` anywhere under `test/` (grep at HEAD). The task file tags nothing. M4 must write it (or tag an existing test whose assertion it changes). Per `clojure-testing` and the lane rule (`bin/lane commit --test` refuses any red), the red test and its implementation land in one commit, or the test is written as a failing test in the same commit as the code that makes it green. A test committed red alone is refused.

Tests reaching the names in §1 (by grep at HEAD):

- `test/seon/merge_test.clj` (the whole namespace reaches `squash-merge`/`merge-tx`/`merge!`):
  - `a-value-changed-on-both-sides-resolves-by-the-declared-rule` (line 98): asserts `:conflicts` counts and `:root` conflict shape `{:entity :attribute :base :ours :theirs}`; `squash-merge` is unchanged, so it should stay green; it is the place to add the three-values-on-unmerged assertion only if the shape moves (M4 keeps `squash-merge`'s shape).
  - `a-root-edit-after-capture-keeps-roots-value` (line 483): reads `(:seon.merge/conflicts merged)` at 497 — M4 changes this key; must convert.
  - `a-repeated-merge-adds-nothing` (462), `a-reverted-branch-value-reverts-on-main` (500), `an-occurrence-counted-on-the-branch-adds-once-on-main` (514), `a-merge-stays-within-its-cost-bound` (534; `merge-tx` writer < 20 ms, its bound M4 must keep).
  - `test/seon/merge_test.clj` 67–382 deftests tagged `seon.merge/an-attempts-cluster-facts-copy-onto-main-by-identity` (old copy-era tests; reach `squash-merge`; they keep green as the three-way is unchanged).
- `test/seon/cluster/db_flow_test.clj`:
  - `a-refinement-on-a-branch-reaches-main-only-at-the-end` (second deftest at line 750): lines 767–787 assert exactly one `squash-conflict` problem task. M4 converts this to one `:seon.merge/unmerged` coordinate, the agent's refinement and root's concurrent edit kept as the three values. This is the `db_flow_test 680` of the hybrid, now at 787.
  - lifecycle tests around 594 and 633 (`merge!` through `db<->flow`).
- `test/seon/cluster/registry_test.clj` 830 (`merge!` with a deleted attempt): reach-only; m6-notes lists `seon.cluster.registry-test/a-deleted-attempts-storage-is-reclaimed-by-the-next-collection` as red on HEAD (not M4's).
- `test/seon/cluster/merge_test.clj` (`seon.cluster.merge-test`): reaches `merge!` through `my.root/merge!`; the code-merge tests are green after M6 (`a-green-request-lands-on-main-and-disk-in-one-call`, `a-function-and-its-schema-land-as-one-unit`). Still red on HEAD per m6-notes (not M4's): `a-require-in-the-agents-user-namespace-lands-with-its-change`, `an-end-message-naming-a-merge-task-is-not-a-request`.
- `test/seon/cluster/host_test.clj` `a-merge-request-waits-for-its-assigned-tasks-proofs-then-reaches-root` (red on HEAD per m6-notes; unrelated to conflicts).
- `test/my/root_test.clj` (`a-merge-is-keyed-by-the-assignment`, green since M6).
- `test/seon/turn_continue_test.clj` `a-green-end-lands-its-merge-without-root` (red on HEAD per m6-notes; the dead `land-end!` path, M8).

Stored evidence: none was read for these tests in this pre-brief. The red list above is m6-notes.md's claim at HEAD 3da1f6449 / 59a1ffe1f, not a run at bc347d583. Before the first M4 commit the lane must run the named tests on its own branch in the main JVM (`seon.test`, reach digests), not on default.

## 3. Prior attempts and frictions

- **m4-notes.md (lane m4, stopped before editing).** The hybrid's row says "on the merge branch only", but at HEAD no merge branch exists for facts. The lane offered three options: (1) M4 after M6 (recommended, and M6 has now merged, so this option is spent); (2) write `:seon.merge/unmerged` on main beside the merge, through the connection `merge!` already holds (simplest; contradicts "merge branch only"; `my.root/merge-requests` would see it on main); (3) a throwaway merge branch per merge (true to the data design; costs a branch create/open per conflict, unmeasured; M6 rewrites its fork point). It also noted: `merge!` holds a connection, not a store, and forking needs the store (`registry/branch!`, `store/open-branch!`), and `agent.clj`'s `land-ends!` (now the db<->flow `:merge`) is the only caller that has the store.
- **m6-notes.md "For M8 and M4".** M6 chose "the merge branch exists in the requested path" and asked M4 to run the facts three-way on the merge branch too, so the unmerged entities are written by the transaction that finds them, replacing `:seon.merge/conflicts`. That is option (3) of m4-notes, now with M6's engine.
- **m3-notes.md (line 31–33).** "Conflict tasks are written after the transaction (M4 replaces them)." `merge-tx` runs in the writer and re-derives conflicts only by calling `merged` a second time on `:db-before` (the `(merged before a commit projection base)` at line 271): the answer's conflicts are a second three-way, not the writer's. That is a cost and a correctness friction: M4 should compute the three-way once.
- **Git history.** `git log --all --grep` for unmerged/conflict-task/merge branch finds only the hybrid, the `copy!`-era commits (fb1fb9e60 `land!`), 70d8c6c74 (a direct `land!` refused by the gate) and 824ad4afd (hybrid). No earlier attempt at a `:seon.merge/unmerged` entity exists in history.
- **Root-edit race (P4 in the hybrid).** Root edits main between capture and transaction. The three-way runs in the writer on its own value, so root's edit is ours, and a shared coordinate is a conflict. The unmerged entity must record `ours` as main's value at the writer, not the value captured earlier (`(shown-state target attribute o)` in `squash-merge` is that value).

## 4. File collisions

`bin/lane status` at 2026-10-12 lists four live claims, each holding one path, all docs (no source or test):

- `root-report`: `docs/research/agent-platform/2026-10-08/morning-report.md`
- `web-check`: `docs/research/agent-platform/2026-10-12/chain-experiment-4.md`
- `astra-one-system`: `docs/research/agent-platform/2026-10-10/astra-one-system.md`
- `astra-review-merge-base`: `docs/research/agent-platform/2026-10-10/review-merge-base.md`

No live claim holds a file M4 needs. M4's files, which the lane must claim with `bin/lane claim` before editing:

- `src/seon/merge.clj` (the owner; M6 and M7 also touch it, both done or later: M7 is next after M4 in the order)
- `resources/seon/schemas/seon.merge.edn`, `resources/seon/schemas/seon.squash.edn` (line 8 deleted)
- `resources/seon/tasks/seon.merge.edn` (the task's `:must-read`, intent)
- `test/seon/merge_test.clj`, `test/seon/cluster/db_flow_test.clj`
- `docs/seon/specs/merge.md` (line 105)
- `src/my/root.clj` only if `my.root/merge-requests` is to read the unmerged entities (Risk 2; likely deferred to M8)

Not needed by M4 and must not be touched: `src/seon/cluster/agent.clj` (M5 deleted the catches; M8 and M10 recur in it), `src/seon/turn.clj` (held by lane program-context in the hybrid's words, M1, M8, M10, M13, M17), `src/seon/cluster/source.clj` (M7 and M9 touch it).

## 5. The riskiest step, and how HEAD keeps booting

**The riskiest step is where the unmerged entity is written.** The facts three-way runs only in main's writer, so a conflict is found inside a transaction on main. Writing `:seon.merge/unmerged` on the merge branch (the hybrid's wording) means either (a) a branch created for every facts merge, or (b) the three-way computed on a value (`db/commit-database` at the base or at main's head, both values, no branch) and a branch forked only when a conflict is found, with the writer's compare-and-swap of main's head still deciding whether the merge applies. Either choice changes `merge-tx`'s shape and the base-advance rule: if the base advances while the conflict stays on a branch, root's merge answer must say the coordinate is still open. Measure before choosing: the fork is unmeasured (m4-notes); M6 measured a facts merge at 6.4 ms warm with no branch.

**HEAD boots after each commit** with this order, each commit a platform slice (`bin/lane commit LANE -m MSG --test NS... PATHS`, which runs `bin/seon check`: publish and reload on disposable branches, then a throwaway JVM runs the named tests):

1. Commit A (additive, nothing deleted): add `:seon.merge/unmerged` to `seon.merge.edn`; write the new red test `seon.merge-test/a-conflict-keeps-its-three-values-on-the-merge-branch` AND its implementation in `merge-tx`/`merge!` beside `conflict-task!`, so both conflict records exist and the test is green. Tag the test `:seon/tasks #{'seon.merge/a-conflict-keeps-its-three-values-on-the-merge-branch}`. HEAD boots: `conflict-task!` still runs, no caller removed.
2. Commit B (deletion with its last caller): delete `conflict-task!`, both calls in `merge!`, `:seon.squash/conflict` (seon.squash.edn line 8), its uses in `seon.merge.edn` line 4 and `merge!`'s answer key `:seon.merge/conflicts` (renamed), convert `db_flow_test` 750–787 (problem count → unmerged entity), `merge_test` 497, and `docs/seon/specs/merge.md` line 105. Named tests: `seon.merge-test`, `seon.cluster.db-flow-test`.
3. Commit C (optional, if Risk 1's branch path is chosen): the fork-on-conflict branch, with its cost measured by `seon.profile/measure` on the conflict case, before and after, on the same probe (perf rule: a measured regression is not committed).

Each commit runs the lint (`clj-kondo --lint src script test` with `--cache false`) and the named tests through the platform gate. Root's batched `start --head` restores HEAD if a new JVM cannot boot; M4 must not leave a `seon.squash/conflict` reference in any file HEAD loads (a deleted schema referenced by a live malli form fails the publish).

## 6. Three risks, ranked

1. **The merge branch for facts does not exist, and the hybrid's wording requires one** (Risk 1 above). A wrong choice here either leaves the three values on main (losing the "merge branch only" rule and changing what `my.root/merge-requests` shows) or adds a fork cost to every conflicting merge. Decide before the lane starts; m4-notes gave the owner three options; M6 chose the third (per m6-notes). Recommendation for the lane: compute the three-way once on commit values, fork a merge branch only on conflict, and measure the fork on the conflict case.
2. **Lost or stale conflict entities.** The unmerged coordinate must hold main's value as `ours` at the writer (P4: root's edit between capture and transaction), and a write to the coordinate on the merge branch must retract it (hybrid §2, "git's index stage"). Neither retraction nor the writer-time `ours` is implemented or tested today. If M4 writes the entity without the retraction rule, a later merge replays a resolved conflict. The proof must cover resolution, not just creation, or it will pass while the entity never clears.
3. **The proof does not exist, and the test surface is wide.** The named red test is absent; the conversions in `db_flow_test` 750 and `merge_test` 497 change assertions that the hybrid did not list; the `--test` gate refuses any red among the named tests, and reaching tests include red-on-HEAD items (m6-notes: `a-merge-request-waits...`, `a-green-end-lands...`) that must not be pulled into M4's gate. Name only the tests that reach the change; leave the m6-notes reds to their own phases.
