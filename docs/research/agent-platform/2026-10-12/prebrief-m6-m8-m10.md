---
title: "Pre-briefs for hybrid phases M6, M8 and M10 (merge keyed by the assignment, done without a request, fork main)"
type: research
status: prebrief, 2026-10-12. Read-only research; no source, test or resource was edited. Lane prebrief-m6.
basis: HEAD 1b09d8043 (M1, M2, M3 done; M5 slice 1 (P7) landed; M4 not started; M7 not started). Live claims read with `bin/lane status`. Stored red state read on default (jvm query as root, read-only). Symbols are fully qualified; no line numbers appear here.
plan: docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md §6 (rows M6, M8, M10); supersedes the M5 and M6 parts of prebrief-m5-m7.md where HEAD has moved.
---

# Pre-briefs: M6, M8, M10

Order in force: M5 → M6 → M4 → M7 → M8 (M4 after M6, per m4-notes.md option 1). M10 waits for M8. Every claim below was checked by `grep` over `src`, `test`, `script`, `resources`, `config`, `.agents`, `AGENTS.md` and `docs/seon`.

## 0. What changed since prebrief-m5-m7.md (read first)

1. **M5 is mostly landed.** At HEAD the lifecycle is `seon.cluster.agent/next-actions` over `seon.cluster.agent/lifecycle-facts`, applied by `seon.cluster.agent/apply-plan!`, with `seon.cluster.agent/failed-pass!` as db<->flow's one catch. The following no longer exist as defns: `land-ends!`, `unlanded-ends`, `branch-end`'s callers in the old form, `settle-kept-branches!`, `record-error-end!`, `ended-problem!`'s old caller, `detached!`, `open-merge-request?`, `successor-waits?`. Remaining M5 residue: `seon.flow.edn` still names `successor-waits?` in a task intent; `seon.squash/attempt` survives only in `seon.flow.edn`'s intent text. The `:seon.squash.edn` schema entries are still present. Lane `m5` holds all of these; M5 is not finished, and this brief does not claim it.
2. **The P7 proof is untagged.** `test/seon/cluster/db_flow_test.clj`, deftest `a-failed-start-ends-its-attempt-and-the-next-plans`, has no `:seon/tasks` metadata, so the filed task `seon.cluster.agent/a-failed-start-ends-its-attempt-and-the-next-plans` has no success test. Fix the tag in M5, not here.
3. **The auto-merge action exists, and it merges cluster facts only.** `apply-lifecycle!` runs `:merge` as `seon.cluster.squash/merge!` on the branch's cluster facts. It never calls `seon.cluster.source/land!`. So a done assignment's declarations (functions, tests) reach main only through the agent-written merge request and root's `my.root/merge!`. This is the central M8 risk (§3.1).
4. **`land-ended!` is dead code.** Its only chain is `land-ended!` → `land-end!` → the `source-land!` delay (`seon.cluster.source/land!` by `requiring-resolve`). No caller of `land-ended!` exists in `src`, `test` or `script`. `:seon.config.root/land?` is read only by `land-end!`, set true only in `test/seon/turn_continue_test.clj` (around 587), and false in `config/default.edn`. M8 can delete this chain with no caller change except that one test.

## 1. Stored red state on default (jvm query as root, read-only)

Members with a recorded failure or error whose symbol names the reach of M6, M8 or M10 (`db-flow`, `cluster.merge`, `my.root`, `cluster.host`, `registry-test`, `task-test`, `lazy`):

| Member (symbol) | Recorded (fail, error) | Phase |
|---|---|---|
| `seon.cluster.db-flow-test/a-prove-step-admitted-by-its-successor-opens-a-model-turn` | (2, 1) and (0, 1) in two stored runs | M10 reaches it (successor forks); already red, owner M1 `a-task-satisfied-at-the-base-opens-no-model-turn` |
| `seon.cluster.db-flow-test/a-send-back-reason-reaches-the-targets-next-attempt` | (1, 0) | M10 (attempt rows) |
| `seon.cluster.registry-test/a-deleted-attempts-storage-is-reclaimed-by-the-next-collection` | (0, 1) | M10 (`attempt-rows-tx`), already red (red-triage #5) |
| `my.task-test/current-answers-the-held-steps-instructions`, `…-the-held-task-with-its-proofs`, `a-send-back-with-positional-arguments-is-answered-with-its-example` | (0,1), (1,0) | M8 touches `my.task`'s held-step reads only through `task/current`; not a merge test; out of scope here |

No stored member row exists for `seon.cluster.merge-test/*`, `my.root-test/*` or `seon.task-test/the-ready-leaves-…` with a failure. **Stored absence is unknown, not green.** For those files the notes are the record: `central-round.md` lines near 169, 278–279 and 303 name `cluster.merge-test` reds (`a-function-and-its-schema-land-as-one-unit`, `a-green-proposal-lands-on-main-and-disk-in-one-call`, `an-end-message-naming-a-merge-task-is-not-a-proposal`, `a-test-never-run-on-the-target-is-not-blamed-on-the-merge`). Treat those four as red on the notes, unverified on stored runs.

## 2. M6 — the merge keyed by the assignment

### 2.1 Symbols changed, with every caller

| Symbol | Callers, tests, quoted symbols, schemas, tasks (non-doc unless noted) |
|---|---|
| `seon.cluster.squash` (namespace) → `seon.merge` | requirers `seon.cluster.agent`, `seon.task` (a `requiring-resolve` in `close!`'s docstring, and a comment), `script/astra_cost_handoff.clj` (`squash-merge`); tests `test/seon/cluster/squash_test.clj`, `test/seon/cluster/registry_test.clj`, `test/seon/cluster/db_flow_test.clj`; task files `seon.fault`, `seon.program`, `seon.cluster.agent`, `seon.cluster.source`, `seon.flow`, `seon.turn.world`, `seon.task`, `seon.cluster.squash` (its own file renames to `seon.merge.edn`); `docs/seon/architecture/vocabulary.md` |
| `seon.cluster.squash/merge!` (cluster-facts squash) | `seon.cluster.agent/apply-lifecycle!` (the `:merge` case, live at HEAD). Not `land!`. |
| `seon.cluster.squash/merge-tx`, `merged`, `squash-merge` | `merge-tx` ← `merge!` (the transaction function); `merged` ← `merge-tx` and `merge!`; `squash-merge` ← `merged`, `script/astra_cost_handoff.clj`, the squash tests. Keep the three-way; only the namespace moves. |
| `seon.cluster.source/land!` | `seon.turn` (`source-land!` delay, used only by the dead `land-end!`), `my.root/merge!`, `test/seon/cluster/merge_test.clj` (several calls). Stays a one-line caller until M8. |
| `seon.cluster.source/land-held!` | `seon.cluster.source/land!` only. |
| `seon.cluster.source/prepare-merge!`, `accept-merge!` | `land-held!`; `merge_test.clj` (about 40 direct calls). Their signatures take the merge request's `:seon.task/branch`, `proved`, `root`; M6 changes them to take the assignment and its `C` (the source commit). |
| `seon.task/close!` | `seon.cluster.source/land-held!` (through `requiring-resolve`), `my.root/close!`, `seon.task` docstrings. Its key changes from the merge handle to the assignment. |
| `my.root/merge!`, `my.root/close!`, `my.root/merge-requests`, `open-merge-request` | `my.root` callers: `seon.context` (offered operation), `seon.cluster.message` (offered operations), `test/my/root_test.clj` (three deftests, fixture `with-merge-request` uses `task/request-merge!`, which M8 deletes). |
| `seon.context` offered operation (around the merge branch) | `seon.context` only |
| `seon.cluster.message` offered operations | `seon.cluster.message` only |
| `seon.task/merge-request-view`, `:seon.task/merge-request`, `:seon.task/merge` | `resources/seon/schemas/seon.task.edn`, `resources/seon/schemas/my.root.edn`, `src/my/root.clj`, `src/seon/task.clj`, `src/seon/cluster/source.clj`, `src/my/program.clj`. M6 adds the assignment key beside them; M8 deletes them. |

### 2.2 Tests, red state, and proofs

- **M6 proof `my.root-test/a-merge-is-keyed-by-the-assignment` does not exist.** `test/my/root_test.clj` has three deftests (`merge-requests-lists-the-open-request-with-its-merge-and-close-offered`, `close-records-closed-with-its-reason-and-the-request-leaves-the-open-list`, `merging-a-task-with-no-open-request-answers-the-refusal-naming-it`), all keyed by the merge handle. Red by absence.
- The hybrid's `test/seon/cluster/merge` names a file that exists as `test/seon/cluster/merge_test.clj`. Use that path, not a namespace, in every task.
- `test/seon/cluster/squash_test.clj` has 21 squash references; `registry_test` 832 reads `:seon.squash/datoms`. These move in the rename commit.
- Stored: no member row for `my.root-test/*` (§1). Notes: none.

### 2.3 Tasks and proofs

- `resources/seon/tasks/my.root.edn` holds `my.root/a-merge-is-keyed-by-the-assignment` (the M6 task; its `:needs` names M5's `seon.flow/an-ended-attempt-…`). Red proof `my.root-test/a-merge-is-keyed-by-the-assignment`: not written.
- `resources/seon/tasks/seon.cluster.squash.edn` renames to `seon.merge.edn`. Its entries keep their names. `seon.cluster.squash/an-attempts-cluster-facts-copy-onto-main-by-identity` (tagging `squash_test` 442) is retired or converted here.

### 2.4 Gotchas

- **Two engines; the fold must say which body is `seon.merge/merge!`.** The cluster-facts squash (`seon.cluster.squash/merge!`, live at HEAD via `apply-lifecycle!`'s `:merge`) and the declarations engine (`seon.cluster.source/land!`, live via `my.root/merge!`) are different. M6's row says `seon.merge/merge!` "folds `land!`/`land-held!`", which leaves the cluster-facts squash without a home. **Root must rule before M6 is claimed.** The narrow reading: `seon.merge/merge!` = the declarations gate and write-back (`land-held!`'s body), and the cluster-facts squash becomes `seon.merge/squash!`, called by the same `:merge` action. Two functions under one namespace, not one.
- **M5 ↔ M6 need cycle.** M5's `:needs` names M4's proof; M4 waits for M6; M6's `my.root.edn` task needs M5's `an-ended-attempt-…`. Break the cycle in the task files before any lane is claimed: M6's `:needs` should name only M5's merged-attempt proof (already green), not the M4 proof.
- **`:seon.squash/*` provenance attributes stay.** `merge!` writes `:seon.squash/source-branch` and `:seon.squash/source-commit` as tx-meta; `db_flow_test` reads them (around 535). Rename only `:seon.squash/datoms`/`conflicts` when M4 and M6 land.
- **The rename is one scripted sweep.** The 17 holders listed in prebrief-m5-m7.md (cross-phase fact 2) are unchanged at HEAD. Dropping `seon.cluster.squash` without converting the `:needs` symbols in `seon.cluster.agent.edn` and `seon.task.edn` leaves tasks that ingestion reports as problems.
- **`close!` changes its key.** `seon.task/close!` takes the merge handle today. Changing it to the assignment changes every merge-request row's identity; `:outcome` moves to the assignment, which is what M5's `delete-branch` predicate needs.
- **The `:merge` action merges cluster facts while `land!` merges code.** Until M6 folds the declarations engine into the `:merge` action, a done branch's functions never reach main by the auto path. Keep `my.root/merge!` as the only declarations path until M8.
- Cost: `my.root/merge!` under 1 s with reused gate results. Before M7's digests exist, measure the full `prepare-merge!` plus `accept-merge!` on a one-function change and record it in the landing.

### 2.5 File collisions (lane `m5` holds these now)

- `src/seon/cluster/source.clj`: no live lane. M6, M7, M9, M11, M17.
- `src/my/root.clj`: no live lane. M6, M12, M16.
- `src/seon/task.clj`: **held by m5.** M6 (`close!` key), M8, M10, M12.
- `src/seon/context.clj` (398–420 offered operation): no live lane. M6 before M7 in this file.
- `src/seon/cluster/message.clj`: no live lane.
- `src/seon/cluster/agent.clj`: **held by m5.**
- `resources/seon/schemas/seon.task.edn`, `seon.task.attempt.edn`: no live lane; `merge-tasks-patches.md` (held by cn-renames-root, a patch file) carries them.
- `resources/seon/tasks/my.root.edn`, `seon.cluster.squash.edn`: **held by m5.**

## 3. M8 — delete the agent's verb and the request; done has no fact

### 3.1 Symbols deleted, with every caller

| Symbol or key | Callers and mentions | Deletion condition |
|---|---|---|
| `seon.task/request-merge!` | `my.program/request-merge!` (its `task/request-merge!` call at its body); `test/my/root_test.clj` (fixture); `test/seon/cluster/merge_test.clj` (the agent eval `(my.program/request-merge! {})`, around 287); `test/seon/cluster/host_test.clj` (around 428–460, three deftests asserting the request's refusal and view); `test/seon/turn_continue_test.clj` (around 532); task file `seon.task.edn`; `.agents/skills/repl/SKILL.md`, `AGENTS.md`, `docs/seon/plan.md`, `docs/seon/specs/merge.md` (prose) | Delete with `my.program/request-merge!`; convert every caller to the done-branch rule (§3.4). |
| `my.program/request-merge!` | `seon.turn/end-task!` (`program-request-merge!` delay, around 2338), `seon.turn` docstring, `test/seon/turn_continue_test.clj` 532, `host_test` 435–460, `merge_test` 287, `seon.task`/`seon.render.var`/`my.program`/`turn` task entries | Same as above. |
| `seon.task/red-first` (private) | `seon.task/request-merge!` only; `test/seon/db_test.clj`, `test/seon/custody_stability_test.clj` (names in their task references); schema `seon.task.edn` `:red-first`, `:seon.task/red-first` in `:merge-request-row` | Delete with the request. M7's designed-red rule replaces the red-first check. |
| `seon.turn/end-merge-request` | `seon.turn/end-task!` (once, to reuse a recorded request), `seon.turn/land-ended!` (dead) | Delete with `end-task!`'s merge branch. |
| `seon.turn/land-end!`, `land-ended!`, `landing-tx`, `root-execution`, `source-land!` delay | `land-ended!` has no caller (§0.4); `landing-tx` and `root-execution` only inside `land-end!` | Delete together, now (no caller change, one test to convert: `turn_continue_test` 587 sets `:seon.config.root/land?` true). |
| `:seon.config.root/land?` | `seon.turn` (`land-end!`), `config/default.edn` (line 177), `resources/seon/schemas/seon.config.root.edn`, `test/seon/turn_continue_test.clj` 587, `docs/seon/plan.md`, `docs/seon/schedule/cards/TASK-CHAIN.md`, `seon.task.edn` task entry | Delete the dial in `config/default.edn`, its schema, and the test's override. |
| `:seon.task/merge-request-row`, `:merge-request-view`, the `:merge` kind of `:seon.task/kind` | `seon.task.edn` (schema, `:kind` enum description), `my.root.edn` schema (`:merge-request`, `:merge-requests`), `src/my/root.clj` (`offered`, `open-merge-request`), `src/seon/cluster/source.clj` (`merge-request-view` in `land-held!`) | Delete after M6 has moved every reader to the assignment. |
| `seon.task/leaf-done` (in `ready-rules`) and `done?` | `ready`, `done?`, `seon.cluster.agent` (`ready-rules` use in `kept?`, which M10 deletes), `seon.context` (`done?` callers) | Rewrite to read "latest assignment `:merged`" (§3.3). |
| `:done` in the end enum (`:seon.task.attempt/end`) | `seon.turn/task-end`, `end-task-call`, `seon.cluster.agent` `merged-ends` (`#{:done :sent-back}`), `next-actions`, `seon.task.attempt.edn` | `end-task!` writes only non-done ends (§3.2). |

### 3.2 `end-task!` today and after M8

Today `seon.turn/end-task!` requests the merge when the end is `:done` and no task needs it (`needed?`), via `my.program/request-merge!`. The C1 "no needer" rule is already in `end-task!`.

After M8: `end-task!` writes the end (`end-task-call`) and no merge request. A `:done` end becomes no fact at all: the assignment's branch is merged by `apply-lifecycle!`'s `:merge` action, which is A1 in the hybrid. The `:merge` action must carry the declarations engine too (§2.4), or M8 leaves done code unmerged.

### 3.3 Ready and done

`seon.task/ready-rules` `leaf-done` reads `:done` ends; `seon.task/done?` reads the same; `ready` filters by `leaf-done`. After M8 both read "latest assignment with outcome `:merged`". `seon.context/step-status` (the M1 `:satisfied` rule) continues to answer the step; `seon.task/done?` is then a fact about merge, not about the end.

### 3.4 Converted callers in tests

- `test/seon/cluster/merge_test.clj`: the agent-eval request at around 287.
- `test/seon/cluster/host_test.clj`: the three deftests at 428–460 assert refusal, a request view and a view's offers. They become assertions that a done branch merges with no request.
- `test/seon/turn_continue_test.clj`: 532 (the request), 587 (the land dial, delete the override).
- `test/my/root_test.clj`: the fixture `with-merge-request`.
- `test/seon/cluster/db_flow_test.clj`: any `:done`-end fixture still wanting a request.

### 3.5 Tasks and proofs

- Proof `seon.task/a-done-branch-merges-with-no-merge-request-written-by-the-agent`: **no deftest exists.** Its task entries are in `seon.task.edn`, `seon.cluster.agent.edn` and `seon.cluster.squash.edn`. Red by absence.
- Proof `seon.task/a-satisfied-task-closes-with-no-model-call`: **no deftest exists**; its task entry is in `seon.task.edn`. This overlaps M1's `seon.context-test/each-step-kind-is-done-by-its-one-rule` (M1 is done); check M1's deftest before writing a new one.
- `seon.task/the-ready-leaves-are-those-whose-needs-are-done`: a deftest exists in `test/seon/task_test.clj`, tagged or not (check before assigning; the hybrid says entries are in `merge-tasks-patches.md`, held by cn-renames).

### 3.6 Gotchas

- **The auto path must merge code before the request goes.** Deleting `request-merge!` while `:merge` merges cluster facts only leaves done functions unmerged. M6's fold is a prerequisite, not a parallel slice.
- **`my.root/merge-requests` lists nothing after M8.** Root's review (the laws in `my.root/instructions`: "Merge with my.root/merge!") describes a verb that no longer names a request. Update the laws text in the same commit.
- **`:seon.config.root/land?` is also a dial in `docs/seon/plan.md` and `TASK-CHAIN.md`.** Prose naming a retired dial is a stale claim; convert in the same commit (AGENTS.md rule: correct false current claims in the same commit).
- **Dead chain first.** The `land-ended!` chain (§0.4) can go in its own slice before M6 lands, with one test converted. That shrinks M8's diff and removes the `source-land!` delay that M6 otherwise has to keep as a one-line caller.
- Cost: detection under 5 ms per changed branch (the hybrid's bound). `done?`'s current cost is unmeasured here; measure it on default before and after.

### 3.7 File collisions

- `src/seon/turn.clj`: **held by m5.** M8's patch goes to m5 or waits.
- `src/seon/task.clj`: **held by m5.**
- `src/my/program.clj`: no live lane.
- `src/my/root.clj`: no live lane.
- `src/seon/cluster/agent.clj`: **held by m5.**
- `config/default.edn`, `AGENTS.md`, `.agents/skills/repl/SKILL.md`, `docs/seon/plan.md`: `docs/seon/plan.md` is held by m5; the others have no live lane.
- `resources/seon/schemas/seon.task.edn`, `seon.task.attempt.edn`, `seon.config.root.edn`: no live lane (`seon.config.root.edn` and `seon.task.edn` are named by `merge-tasks-patches.md`).

## 4. M10 — successors fork main after their needs merge

### 4.1 Symbols deleted or changed, with every caller

| Symbol or attribute | Callers and mentions | Deletion condition |
|---|---|---|
| `seon.cluster.agent/fork-attempt!` predecessor clause | `start-attempt!` only. Reads `:seon.task.attempt/fork` and `registry/branches` for the predecessor branch and throws `The attempt's predecessor branch is gone…` (chain-3 G7's trigger). | Delete the clause; forks always from main's head after the needs merge. |
| `seon.cluster.agent/seed-attempt!` | `start-attempt!` only; writes the agent and row on a branch whose fork point predates them. | Delete (the fork is main; the row is already there). |
| `seon.task/attempt-rows-tx` | `seon.cluster.agent/seed-attempt!` and `seon.task/assign!` (`attempt-rows-tx` call, around the `own` binding). `assign!` writes the row on the agent's branch under main's ids; with a fork from main that is still needed for the branch's own world, so keep the `assign!` use and delete only the predecessor-fork use. Check before deleting. | Delete `seed-attempt!`'s use; decide `assign!`'s use with M10's test in hand. |
| `:seon.task.attempt/fork` (schema `seon.task.attempt.edn`, `seon.task.edn` `:seon.task/assignment-row`) | `seon.cluster.agent/write-attempt!` (`fork` from `:seon.flow.plan/fork-point`), `fork-attempt!`, `seon.turn/fork-commit`, `seon.task/attempt-rows-tx` (its selector), `seon.cluster.agent/kept?` (`forked?`), `test/seon/cluster/db_flow_test.clj` (around 809, reads `:seon.task.attempt/fork`), tasks `seon.turn.edn`, `seon.cluster.registry.edn`, `seon.cluster.agent.edn`, `seon.task.edn` | Delete attribute and every reader; convert the test to the main fork. |
| `:seon.task.attempt/relaunch-fork` | `seon.task.attempt.edn` (M12 reuses it for P9) | **Not M10.** Leave it; M12 owns it. Note the name overlap in the commit. |
| `seon.cluster.agent/plan`'s `:seon.flow.plan/fork-point` and the `(first (sort fork-point))` in `write-attempt!` | `plan` (launch's `:seon.flow.plan/fork-point`), `write-attempt!` | Delete both; `:seon.flow.plan/fork-point` leaves `seon.flow.plan.edn`'s attempt action. |
| `plan-facts`' basis query (`:seon.flow.plan/basis`, the `:done` ends' commits) and `basis-valid?` | `plan-facts`, `plan` (`basis` for fork-point), `basis-valid?` (also the `:prove` successor lookup) | Replace with "a need's merge is main's `:merged` assignment" (`seon.task/ready` already gates on needs). `basis-valid?` is needed for admission (an `:implement` step with proofs); keep it, delete the `commit` argument if it only served the basis. |
| `seon.turn/fork-commit` | `seon.turn` (`record-broken!`, around 5530), `seon.test` and `seon.context` (names in docstrings only; check before editing) | Rewrite to read `:seon.task/merge-base` on the open assignment (set by M3's `merge-tx`; M5 writes it through `:merge-base`). Keep the `base-commit-id` fallback until M17. |
| `seon.cluster.agent/kept?`'s `waiting`/`forked?` clauses (the held-successor pin) | `apply-lifecycle!`'s `:delete-branch` via `next-actions`' `kept?` | Delete the pin; `kept?` keeps its merge-request and keep-branches clauses until M5's delete-branch predicate is final. |
| "the octopus" (several predecessors' end commits fork the first, per `write-attempt!`'s docstring) | `write-attempt!` docstring only (no code path forks several commits; `(first (sort fork-point))` is the whole behavior) | Delete with `fork-point`. |
| `seon.cluster.agent/successor-waits?` | `seon.flow.edn` task intent only (no code caller). | Remove the name from the task intent; M5 already removed the function. |

### 4.2 Tests, red state, and proofs

- `test/seon/cluster/db_flow_test.clj`: deftests tagged `seon.cluster.agent/a-successor-whose-proof-its-predecessor-wrote-starts` (around 943 and 1009) fork a successor from its predecessor's end; they must move to the main fork. Around 809 reads `:seon.task.attempt/fork` (tagged `seon.cluster.agent/two-plan-passes-start-one-attempt`).
- `test/seon/cluster/registry_test.clj`: `a-deleted-attempts-storage-is-reclaimed-by-the-next-collection` is red on stored runs (§1); M10's `attempt-rows-tx` removal may change its fixture. Do not assume it turns green.
- `test/seon/cluster/lazy_agents_test.clj`: `agents-to-start` names only the start rule; M13 owns the deletion. Check its fixture for a fork.
- `test/seon/cluster/turn_test.clj`, `test/seon/turn_test.clj`: check for a fork fixture before editing (lane m5 holds both).
- Proof `seon.cluster.agent/a-join-forks-main-after-both-needs-merge`: **no deftest exists**; the name appears only in the `kept?` docstring and in `seon.cluster.agent.edn`. Red by absence.
- Proof `seon.cluster.agent/a-prove-steps-red-product-merges-into-main`: **no deftest exists**; the entry is in `seon.cluster.agent.edn` alone. Red by absence.
- Stored red on default for the successor test: `db-flow` `a-prove-step-admitted-by-its-successor-opens-a-model-turn` (§1). Its red is the M1 base-result rule's concern, not M10's; a green there after M10 is not M10's proof.

### 4.3 Gotchas

- **G7 is the trigger M10 must remove.** Chain-3 saw a successor fork from a deleted predecessor branch; the throw in `fork-attempt!` is that failure made loud. Delete the predecessor clause and the throw together, so the failure cannot be reached.
- **`:seon.task.attempt/fork` leaves before `fork-commit` reads main.** `seon.turn/fork-commit` falls back to `registry/base-commit-id` (M17 removes that). M10 must not leave `fork-commit` reading a fork attribute that `write-attempt!` no longer writes; rewrite it to `:seon.task/merge-base` first, then delete the attribute, in one commit.
- **The fork must read main after the needs merge.** A successor forked from main before its need merged gets an old program. `seon.task/ready` already gates on needs being done (`leaf-done`); after M8 done is `:merged`. M10 depends on M8: the `merged` test is the gate, not the end.
- **Cost bound.** Fork under 20 ms (the hybrid's bound). `fork-attempt!` forks main, which is one `registry/fork!` call; measure it before and after on default.
- **`:seon.task.attempt/relaunch-fork`** (M12) is the only remaining writer of a non-main fork; M10 must not delete the schema entry for it.

### 4.4 File collisions

- `src/seon/cluster/agent.clj`: **held by m5.** M10 is one of its largest edits (`fork-attempt!`, `seed-attempt!`, `write-attempt!`, `plan-facts`, `kept?`, `plan`).
- `src/seon/task.clj`: **held by m5.** `attempt-rows-tx` (around 626) and `assign!`'s use (around 682).
- `src/seon/turn.clj`: **held by m5.** `fork-commit` (around 5496).
- `resources/seon/schemas/seon.task.attempt.edn`, `seon.task.edn`: no live lane; `seon.flow.plan.edn` is held by m5.
- `test/seon/cluster/db_flow_test.clj`, `test/seon/cluster/registry_test.clj`: **held by m5.**

## 5. Riskiest step and HEAD

The riskiest step is **M8's deletion of `request-merge!` together with the done-branch merge**. Today a done end reaches main through two paths: `end-task!` writes a merge request that root's `my.root/merge!` lands (declarations, through `land!`), and `apply-lifecycle!`'s `:merge` squashes cluster facts. Deleting the request before M6 folds the declarations engine into `:merge` leaves HEAD booting but silently unmerged: no error, no red proof, and every function written by an agent stays on its branch. HEAD keeps working only if (a) M6's fold lands first with a test that `:merge` on a done branch changes a function's digest on main (not only the assignment's outcome), and (b) M8 deletes the request in the same commit that converts `my.root/merge-requests` to list open assignments. Do not split M8's request deletion from M6's fold.

M10's riskiest step is removing `:seon.task.attempt/fork` before `seon.turn/fork-commit` reads `:seon.task/merge-base`. Keep both changes in one commit, so the record is never read without its writer.

## 6. Three biggest risks

1. Two merge engines with no ruled home for the cluster-facts squash (`seon.cluster.squash/merge!` versus `seon.cluster.source/land!`). M6's fold cannot be claimed until root rules it.
2. M8 deletes the only declarations path for done work before the `:merge` action carries declarations; done code silently stays on branches. Proof must check the digest on main, not only the outcome.
3. M10's fork change depends on M8's `:merged` gate and on `fork-commit` reading `merge-base` in the same commit as the attribute's removal; the chain-3 G7 trigger goes only when the predecessor clause and its throw go together.

## 7. Filed tasks and proofs to write (not written here)

| Phase | Proof (deftest name) | Filed task | Status |
|---|---|---|---|
| M6 | `my.root-test/a-merge-is-keyed-by-the-assignment` | `my.root/a-merge-is-keyed-by-the-assignment` | no deftest |
| M6 | (fold proof) `merge-test/a-done-branch-merge-moves-its-declarations-to-main` | none filed | to write; blocks M8 |
| M8 | `seon.task/a-done-branch-merges-with-no-merge-request-written-by-the-agent` | `seon.task/a-done-branch-merges-with-no-merge-request-written-by-the-agent` | no deftest |
| M8 | `seon.task/a-satisfied-task-closes-with-no-model-call` | `seon.task/a-satisfied-task-closes-with-no-model-call` | no deftest; check M1's `each-step-kind-is-done-by-its-one-rule` first |
| M10 | `seon.cluster.agent/a-join-forks-main-after-both-needs-merge` | `seon.cluster.agent/a-join-forks-main-after-both-needs-merge` | no deftest |
| M10 | `seon.cluster.agent/a-prove-steps-red-product-merges-into-main` | `seon.cluster.agent/a-prove-steps-red-product-merges-into-main` | no deftest |

Not filed, found while reading (for root): the P7 deftest `a-failed-start-ends-its-attempt-and-the-next-plans` lacks its `:seon/tasks` tag (M5 slice fix); `:seon.config.root/land?` and `land-ended!`'s chain have no caller (M8 slice 0, before M6).
