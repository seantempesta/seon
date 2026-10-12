---
title: "Pre-briefs for hybrid phases M5, M6 and M7 (derived lifecycle, merge keyed by the assignment, the gate)"
type: research
status: prebrief, 2026-10-12. Read-only research; no source, test or resource was edited. Each phase lists what the implementer must know before the first form.
basis: HEAD 373ad0ac8 (M3 done at 767551c7b; M1, M2 and M3 done; M4 running). Uncommitted worktree edits by other lanes are noted where they touch a phase's files. Symbols are fully qualified; no line numbers appear here. Stored runs were read on default (cluster `default`, jvm mode, root), and default was faulted at read time (7 failed tests, 4 open faults, 16 agent-error signatures).
plan: docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md §6 (rows M5, M6, M7)
---

# Pre-briefs: M5, M6, M7

Every claim below was checked at the code by `rg` and by reading the defns. "Callers" means every reference in `src`, `test`, `script` and `resources` (tasks, schemas, docs named in the phase's file list). Words are Clojure, Datahike and git's.

## Cross-phase facts that change all three

1. **Two merge engines, not one.** `seon.cluster.squash/merge!` (M3's `merge-tx`, squash of cluster facts: tasks, attempts, `:seon.task/merge-base`) is what `seon.cluster.agent/land-ends!` calls today. `seon.cluster.source/accept-merge!` (declarations: prepare on a scratch branch, gate, `declarations-held-tx`, `:parents`, write-back, git commit) is what `seon.cluster.source/land!` calls for merge requests. The hybrid says M6's `seon.merge/merge!` "folds `land!`/`land-held!`" and never says which engine supplies the cluster-facts squash after M5 deletes `land-ends!`. Root must rule this before M6 is claimed.
2. **The rename touches 17 holders.** `seon.cluster.squash` appears in `src/seon/task.clj`, `src/seon/cluster/squash.clj`, `src/seon/cluster/agent.clj`, `script/astra_cost_handoff.clj` (`squash-merge`), `docs/seon/architecture/vocabulary.md`, the task files `seon.task`, `seon.cluster.agent`, `seon.flow`, `seon.fault` and `seon.cluster.squash` (nine `:needs` and intent references inside it), `resources/seon/schemas/seon.task.edn`, and the tests `squash_test` (16), `registry_test` (1) and `db_flow_test`. Task ids derive from the namespace, so every `:needs` naming `seon.cluster.squash/…` moves with the rename.
3. **Lane collisions now.** The ledger shows lane `root-drift` holding `src/seon/turn.clj`, `src/seon/test.clj`, `test/seon/turn_test.clj`, `resources/seon/tasks/seon.turn.edn`, `test/seon/request_cost_test.clj` and `resources/seon/schemas/seon.turn.loop.edn`. Both `src/seon/turn.clj` and `src/seon/test.clj` carry uncommitted edits in the worktree (`request-changes` in `seon.test` and a turn change). M5 needs a patch to `seon.turn` (`land-ended!` loses its caller); M7 edits `seon.test` (`change-reds`). Neither can be claimed until `root-drift` releases.
4. **Stored evidence is thin.** A jvm query over the stored member rows (`:seon.test.member/symbol` with its pass, fail and error counts) matched the merge, squash, merge-request and ended-attempt names: one symbol had a recorded error (`seon.cluster.squash-test/a-branch-born-entity-keeps-its-eid-on-main`), and no failing member for any target. The M3 lane's "23 reds shared with HEAD" (m3-notes.md) are not in the stored runs; treat the notes as the only record of those reds. Stored absence of a member is "unknown", not green.

---

## M5 — the lifecycle is derived; delete the marker, the end copy and the catches (absorbs P7)

### 1. Symbols changed or deleted, with all callers

| Symbol (fully qualified) | Callers and mentions (non-doc) |
|---|---|
| `seon.cluster.agent/land-ends!` (the end copy: squash-merge for `:done`/`:sent-back`, record-error-end! for errors, stop, settle) | `seon.cluster.agent/db<->flow` (the one pass, calls it right after the start loop); `seon.cluster.agent` docstrings only; `seon.turn` docstrings (two, naming it as the landing); `test/seon/turn_continue_test` (a string literal in an assertion message, 345) |
| `seon.cluster.agent/unlanded-ends` | `seon.cluster.agent/db<->flow` only |
| `seon.cluster.agent/branch-end` | `seon.cluster.agent/unlanded-ends` only |
| `seon.cluster.agent/settle-kept-branches!` | `seon.cluster.agent/db<->flow` only; its docstring names `successor-waits?` |
| `seon.cluster.agent/record-error-end!` | `seon.cluster.agent/land-ends!` only |
| `seon.cluster.agent/ended-problem!` | `seon.cluster.agent/record-error-end!` only; its problem-task text tells the reader to call `seon.cluster.squash/merge!` and `my.agent/retire-branch!` (both stale after M3/M5) |
| `seon.cluster.agent/detached!` | `seon.cluster.agent/land-ends!` (the `:done` branch); `test/seon/cluster/db_flow_test` 807 calls `#'agent/detached!` directly |
| `seon.cluster.agent/open-merge-request?` | `seon.cluster.agent/settle-kept-branches!`, `seon.cluster.agent/land-ends!` |
| `seon.cluster.agent/successor-waits?` | `seon.cluster.agent/settle-kept-branches!`, `seon.cluster.agent/land-ends!` |
| `seon.cluster.agent/apply-plan!` (per-action catch; the name itself may change to `apply-actions!`) | `seon.cluster.agent/db<->flow`; `test/seon/test_test` 501 calls it directly (`same-test-symbol-on-two-branches-runs-each-bodys-content`) |
| The `:seon.squash/attempt` tx-meta marker | written by `seon.cluster.agent/record-error-end!` and `land-ends!`; read by `unlanded-ends` (`not [_ :seon.squash/attempt ?a]`), `branch-end`'s callers, and `landed` in `settle-kept-branches!`; tests `db_flow_test` 506 (a helper query around the same lines), 509 and 551 (the attempt markers) |
| `resources/seon/schemas/seon.squash.edn` entries `:attempt`, `:copy`, `:datoms`, `:conflicts` | `:copy` returned by `seon.cluster.squash/merge!` (its `:malli/schema` output); `:conflicts` in `seon.cluster.squash/merged`'s schema; `:datoms` in `merge!`'s body and in `test/seon/cluster/db_flow_test` 579 and 581, `test/seon/cluster/squash_test` 476 and `test/seon/cluster/registry_test` 832 |
| The three catches | (a) `seon.cluster.agent/apply-plan!` per action; (b) `seon.cluster.agent/db<->flow`'s start loop; (c) `seon.cluster.agent/db<->flow`'s try around `land-ends!`/`settle-kept-branches!`. (a) and (b) are P7's; (c) dies with `land-ends!` |

Not in M5's list but must move with it: `seon.cluster.agent/plan-facts` (reads `:seon.task.attempt/end :done` as the basis for the next attempt; the hybrid says it is alive until M8, so keep it) and `seon.turn/land-ended!` (its only caller is `detached!`; after M5 it is dead code that M8 deletes, so M5 must not leave it wired).

### 2. Tests that reach these symbols, and red state

- `test/seon/cluster/db_flow_test`: the tests tagged `seon.flow/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted` (two deftests, around 508 and 551); `an-ended-attempts-closed-merge-releases-its-connection-and-branch` (tagged `seon.turn/a-closed-attempt-releases-its-connection-before-branch-deletion`, around 326; it depends on land-ends!); the M3 deftests reading `:seon.squash/datoms` (around 579–581); `detached-work-without-a-launcher-refuses-and-never-runs-inline` (around 798–809, tagged `seon.cluster.agent/two-plan-passes-start-one-attempt`), which calls `#'agent/detached!` directly. **This test has no row in the hybrid: M5 must retire or rewrite it or it stays red by name.**
- `test/seon/cluster/registry_test` 797 (tagged `seon.flow/an-ended-attempt-…`) and 832 (`:seon.squash/datoms`).
- `test/seon/cluster/squash_test`: `a-repeated-merge-adds-nothing` (476), `a-root-edit-after-capture-keeps-roots-value` (493) and the `:seon.squash/datoms` and `:seon.squash/conflicts` reads (442, 476, 493). These are M3's and reach the `:copy` schema M5 deletes.
- `test/seon/test_test` 501 calls `apply-plan!`.
- `test/seon/turn_continue_test` 345 names `land-ends!` in an assertion message only (reword, no behavior).
- Proof names in the hybrid that **do not exist as deftests**: `seon.cluster.db-flow-test/a-merged-assignment-whose-graph-survived-a-crash-is-stopped-and-deleted` (the M5 red proof; the narrow check's text said `a-merged-request-…`, so the name has drifted between the phase row and the check), and `seon.cluster.db-flow-test/a-failed-start-ends-its-attempt-and-the-next-plans` (P7; the task entry exists in `resources/seon/tasks/seon.cluster.agent.edn`, no deftest). Both are red by absence on HEAD.
- Stored runs on default: none of the M5 proof names has a member row. The existing reach-tests have no recorded failing member on default, so the red state for M5 is "unknown on default, absent for the two proofs".

### 3. Filed tasks and proofs

- `resources/seon/tasks/seon.flow.edn` → `seon.flow/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted` (kind `:function`; `:needs` `seon.cluster.squash/a-conflict-keeps-its-three-values-on-the-merge-branch`, i.e. M4). Red proof `seon.cluster.db-flow-test/a-merged-assignment-whose-graph-survived-a-crash-is-stopped-and-deleted` (not written).
- `resources/seon/tasks/seon.cluster.agent.edn` → `seon.cluster.agent/a-failed-start-ends-its-attempt-and-the-next-plans` (P7; `:needs` the same M4 proof). Red proof `seon.cluster.db-flow-test/a-failed-start-ends-its-attempt-and-the-next-plans` (not written).

### 4. Prior attempts, gotchas, and chain friction

- **The auto-land path disappears before its replacement exists (the biggest risk).** `land-ends!` is the only code that copies a `:done` or `:sent-back` end to main today. The hybrid puts the `:merge` action (A1) into `apply-actions!` only at M8. M5 as written deletes `land-ends!` and says `next-actions` replaces "the marker, the end copy and the catches" without saying the end copy is re-expressed. Between M5 and M8 no path merges a done end, so chains stop. Either M5 emits `:merge` (calling the M3 merge path) or M5 and M8 are reordered. Chain experiment 2 and 3 both show root having to run a hand pass for exactly this (G8).
- **P7's catch removal turns one failed start into a failed pass.** Removing `apply-plan!`'s per-action catch and `db<->flow`'s start catch lets an exception from one action abort the remaining actions of that pass. P7's fix (the Flow proc boundary writes the attempt's `:error` end) must land in the same commit, or the pass must keep "each action fails alone" as a value. The hybrid says the entrant boundary (the Flow proc) takes thrown maps; confirm in `seon.flow` that a start failure reaches it.
- **The `:delete-branch` predicate reads facts that do not exist yet.** M5 defines `:delete-branch` as "outcome present, graph exited, head = base or not done, nothing unmerged, no publication due, `:seon.config.agent/keep-ended-branches?` false". `:seon.task/outcome` is written by `seon.task/close!` only at M6, and "no publication due" needs M9's `:git-commit`. Before M6 and M9, the implementer must use a fact that exists (the end on main plus `:seon.task/merge-base` equal to the head) and say so in the landing.
- **The schemas M5 deletes are used by `merge!`.** `seon.squash.edn` `:copy`, `:datoms` and `:conflicts` are the output schema of `seon.cluster.squash/merge!` and of its helper `merged`, and the shape of the value `test/seon/cluster/squash_test` and `registry_test` read. M5's file list does not include `src/seon/cluster/squash.clj` or `test/seon/cluster/squash_test.clj`. Either move the schema deletion to M6 (where `merge!` changes its name and its return) or add the two files to M5. The first is smaller.
- **The marker is also the signal for `open-merge-request?`'s set of attempts.** `seon.cluster.agent/unlanded-ends` skips any attempt holding the marker; deleting the marker makes every ended attempt re-selected each pass, so `:delete-branch` must be idempotent by identity (the hybrid says so) and the stop must not re-run a closed graph (`seon.flow` `stop-flow!`).
- **Tx-meta `:seon.squash/source-branch` and `:seon.squash/source-commit` stay.** `merge!` writes them and `db_flow_test` 535 reads them. Only `:seon.squash/attempt` goes; the hybrid lists it, but the two provenance attributes must not be swept with it.
- **`apply-plan!` has a docstring contract** ("Each action's failure is recorded as db<->flow's fault and the rest still apply"). Rewrite it with the change, not after.
- **Chain friction mapping.** G7 (a successor forks from a deleted predecessor branch, `seon.cluster.agent/fork-attempt!`) is M10's root with M5's `:delete-branch` as its trigger; chain-experiment-3 says "merge G7's fix (M10, M5) before the next run". G8 (an end sits on its branch until a hand pass) is M5's root: `unlanded-ends` only sees ends whose branch the store still holds, and nothing wakes it. G6 (capped attempt has no relaunch) is M12, not M5.
- Cost bound in the hybrid: one pass over ten assignments under 50 ms. `settle-kept-branches!` runs `successor-waits?` once per landed attempt, a query per attempt; measure before the derived version.

### 5. File collisions

- `src/seon/cluster/agent.clj` is edited by M3 (done), M5, M6, M8, M10, M12, M13 and M16. M5 is the largest edit in it (about 380 lines of defns).
- `src/seon/turn.clj` (patch for `land-ended!`'s last caller) is held by `root-drift` and dirty.
- `test/seon/cluster/db_flow_test.clj` is edited by M5, M10, M12, M16 and the P7 proof.
- `resources/seon/schemas/seon.squash.edn` has no other lane today; its name goes at M18 (`squash` → `merge`).
- `resources/seon/tasks/seon.cluster.agent.edn` and `seon.flow.edn` are the M5 task files; `seon.flow.edn` is held by none.

---

## M6 — the merge keyed by the assignment (`seon.cluster.squash` becomes `seon.merge`)

### 1. Symbols changed or deleted, with all callers

| Symbol | Callers and mentions (non-doc) |
|---|---|
| `seon.cluster.squash` (namespace) → `seon.merge` | requirers `seon.cluster.agent` (`squash` alias), `seon.task` (`requiring-resolve` in `close!`'s docstring and a comment), `script/astra_cost_handoff.clj` (`squash-merge`), `test/seon/cluster/squash_test`, `test/seon/cluster/registry_test`, `test/seon/cluster/db_flow_test` |
| `seon.cluster.squash/merge!` → `seon.merge/merge!` | `seon.cluster.agent/land-ends!` (dies at M5); `seon.cluster.source/land-held!`-side callers do not use it |
| `seon.cluster.squash/merge-tx` | `seon.cluster.squash/merge!` only; M3's writer function, stays |
| `seon.cluster.source/land!` and `land-held!` | `my.root/merge!` (through `source/land!`); `seon.turn` `source-land!` delay (`requiring-resolve` at turn.clj 75; the one-line caller M6 keeps until M8); test `seon.cluster.merge-test` (several, 315–561 by the narrow check) |
| `seon.cluster.source/prepare-merge!` and `accept-merge!` (take the assignment and its C) | `seon.cluster.source/land-held!`; `test/seon/cluster/merge_test` (about 40 calls, direct) |
| `seon.task/close!` (writes `:outcome` on the assignment) | `seon.cluster.source/land-held!` (via requiring-resolve), `my.root/close!` (`src/my/root.clj`), `seon.task` docstring |
| `my.root/merge!`, `merge-requests`, `close!` (key by `:seon.task/assignment`) | `seon.context` (the offered operation at its merge branch, 398–420 and 460 by the narrow check), `seon.cluster.message` (the offered operations at its 239, 605, 643 and 862), `test/my/root_test` (three deftests; fixture `with-merge-request` uses `task/request-merge!`, which M8 deletes) |
| `seon.task/merge-request-view`, `:seon.task/merge-request`, `:seon.task/merge` keys | `resources/seon/schemas/seon.task.edn`, `src/my/root.clj`, `src/seon/task.clj`, `src/seon/cluster/source.clj`, `src/my/program.clj` (`request-merge!` returns the view; M8 deletes it) |

### 2. Tests that reach these symbols, and red state

- The M6 proof `my.root-test/a-merge-is-keyed-by-the-assignment` **does not exist**; `test/my/root_test.clj` has three deftests (`merge-requests-lists-the-open-request-with-its-merge-and-close-offered`, `close-records-closed-with-its-reason-and-the-request-leaves-the-open-list`, `merging-a-task-with-no-open-request-answers-the-refusal-naming-it`), all keyed by the merge handle. Red by absence.
- `test/seon/cluster/merge_test` (the gate tests for prepare and accept, incl. `:seon.source/red-on-target-error` at its 228–230 and 536–537) reaches `source/land!`. The hybrid names `test/seon/cluster/merge` for the same file. Decide whether the rename creates a second test namespace `seon.merge-test` before writing.
- `test/seon/cluster/squash_test` (21 squash references) and `registry_test` (`:seon.squash/datoms` at 832) are renamed in M6 (`:seon/tasks` tags name `seon.cluster.squash/…`).
- Stored runs on default: no member row for `my.root-test/a-merge-is-keyed-by-the-assignment`.

### 3. Filed tasks and proofs

- `resources/seon/tasks/my.root.edn` → `my.root/a-merge-is-keyed-by-the-assignment`; `:needs` `seon.flow/an-ended-attempt-…` (M5) and a second `:needs`. Red proof `my.root-test/a-merge-is-keyed-by-the-assignment` (not written).
- The rename's task file: `resources/seon/tasks/seon.cluster.squash.edn` becomes `seon.merge.edn`; its entries keep their names under the new namespace (`a-merge-whose-reached-definition-moved-writes-nothing`, `a-conflict-keeps-its-three-values-on-the-merge-branch`, `an-accepted-merge-is-published-by-the-next-pass`, `a-sibling-merge-that-breaks-a-proof-undoes-done`, and the rest). Note: `seon.cluster.squash/an-attempts-cluster-facts-copy-onto-main-by-identity` is the task tagging `squash_test` 442; retire or convert it.

### 4. Prior attempts, gotchas, and chain friction

- **M4 blocks M5 and M6 is blocked by M4's placement.** M5's `:needs` names M4's proof `a-conflict-keeps-its-three-values-on-the-merge-branch`. `m4-notes.md` says M4 must follow M6 (the merge branch does not exist until M6), recommending "reorder: M4 after M6". If the reorder is accepted, M5 waits for M6, and M6 waits for M5 (its `:needs` names `an-ended-attempt-…`). That cycle must be broken in the task files before anyone claims M5.
- **Two engines (see Cross-phase fact 1).** M6 says `seon.merge/merge!` folds `land!` and `land-held!`. Those are the declarations engine. The cluster-facts squash (M3's `merge-tx`) is the engine M5 leaves without a caller. The fold must say which engine's body is `merge!`, or the squash must become a second function under `seon.merge`. The narrow check did not flag this; it is the largest design question in M6.
- **`seon.cluster.source/land!` must stay until M8.** `seon.turn`'s `source-land!` delay resolves it by name (`requiring-resolve`), and `seon.turn` is dirty in the worktree. Keep it as a one-line caller of `seon.merge/merge!`, as the hybrid says, and do not rename the delay in this commit.
- **The rename is a sweep, not a phase.** Run it as one scripted pass (the hybrid's "scripted word sweep" rule) across the 17 holders in cross-phase fact 2, then lint. Dropping the namespace `seon.cluster.squash` without converting the `:needs` symbols in `seon.cluster.agent.edn` (two) and `seon.task.edn` (one) leaves tasks whose `:needs` resolve to nothing; ingestion reports that as a problem on the task.
- **`close!` changes its key.** `seon.task/close!` takes the merge handle today and `my.root/close!` offers it. Changing the key to the assignment changes every merged-request row's identity; `:outcome` is written on the assignment, which is the value M5's `:delete-branch` predicate needs later.
- **`my.root` fixture uses the verb M8 deletes.** `test/my/root_test` builds its open request with `task/request-merge!`. M6 can keep the fixture until M8 but must not add a second one.
- **Chain friction.** The hybrid's own note: "merge G7's fix (M10, M5) before the next run". G1–G5 and G9 are other phases; none of chain-experiment-2 or -3's merge-request frictions is an M6 root cause, other than the duplicate-merge path (a repeated `my.root/merge!` on a closed request must answer as a refusal, not write, which `merge-tx`'s `[]` already gives on the squash side).
- Cost: `my.root/merge!` < 1 s with reused gate results (the hybrid's bound). The gate is reused only if M7's digests are in place; before M7, measure the full `prepare-merge!` plus `accept-merge!` on a one-function change and record it.

### 5. File collisions

- `src/seon/cluster/source.clj`: M6 (`land!` fold), M7 (gate, `declarations-held-tx`, `:parents`), M9 (publication), M11 (`update!` in `seon.merge`), M17 (`merge-base`, `advance-base!`). The busiest file in the merge path.
- `src/my/root.clj`: M6, M12 (`relaunch!`), M16 (`root-offers-a-sent-message-once`). Not claimed by any lane today.
- `src/seon/task.clj`: M6 (`close!` key), M8 (request verb and the ready rule), M10, M12. `root-drift` holds none of it.
- `src/seon/context.clj` lines 398–420 and 460 (the offered operation): M6 and M14 (`current-state`'s changes) and M7 (`designed-red?` public). Sequence M6 before M7 in this file.
- `src/seon/cluster/message.clj`: M6 offered operations, M16.
- `resources/seon/schemas/seon.task.edn` and `seon.task.attempt.edn`: M6, M8, M10 and M17 touch them; the schema `seon.task` is the subject of two untracked patches in `docs/research/agent-platform/2026-10-12/` (`merge-tasks-patches.md`), held by `cn-renames-root`.

---

## M7 — the gate is the one rule; designed reds are data

### 1. Symbols changed or deleted, with all callers

| Symbol | Callers and mentions (non-doc) |
|---|---|
| `seon.test/change-reds` | `seon.cluster.source/accept-merge!` (one call); `test/seon/test_test` (the `change-reds-…` deftest around 362–386, its `sut/change-reds` assertion at 385); `test/seon/cluster/merge_test` (comments at 67 and 205 only); `resources/seon/schemas/seon.test.member.edn` (description mention); `resources/seon/tasks/seon.turn.edn`, `seon.test.edn`, `seon.context.edn`, `seon.cluster.squash.edn` (task references); `docs/seon/plan.md`. `seon.test/fork-point-red` is its helper and is also called by `seon.turn` (four references) and `seon.test` (three): keep it until M1's rule is the only reader. |
| `seon.cluster.source/red-test-refusal` and `red-test-verdicts` | `seon.cluster.source/accept-merge!` only |
| `seon.cluster.source/declarations-held-tx` | `seon.cluster.source/accept-merge!` (inside its transaction) |
| `:parents` in the transaction of `accept-merge!` | `seon.cluster.source/accept-merge!` writes `(conj merged e-id)` through `db/transact!` (routes to `merge-db!` in `seon.db`); `seon.cluster.source/accept-merge!` result `:seon.source/base-commit-id` is read by `write-back!` and the commit refusal (`:seon.source/base-commit-id` in the offending value, around the write-back refusal) and by `test/seon/turn/batch_test` (fixture value) and `test/seon/cluster/merge_test` (line 80, a fixture value) |
| `seon.context/step-status` (the one rule; not changed by M7, but now called from the gate) | `seon.turn` (`context-step-status` delay; prove and step turns), `seon.context/task-finished?`-style reader, tests `seon.context-test` (310) and `seon.test-failure-facts-test` (185) |
| `seon.context/designed-red?` (made public) | `seon.context/step-status` only; the hybrid's `:seon/tasks` naming a task with no `:merged` assignment is the new coverage rule |

### 2. Tests that reach these symbols, and red state

- `test/seon/cluster/merge_test`: the red and broken cases at 53–122 (prepare, accept on a red test, `:seon.source/merge` layer) and 186–230 (`red-test-error` with the `:seon.source/red` vector; `red-on-target-error` for a test already red on main), and 315–340 and 536–537. These encode the **root decision** path that M7 must replace (see gotchas).
- `test/seon/test_test` 362–386: the `change-reds` case, which the hybrid retires (`seon.test/change-reds-judges-…`, filed; `resources/seon/tasks/seon.test.edn`).
- `test/seon/context_test` 310 and `test/seon/test_failure_facts_test` 185: `step-status` cases that must still pass after the gate reads it.
- The M7 proofs **do not exist**: `a-merge-whose-reached-definition-moved-writes-nothing` and `a-prove-steps-designed-reds-pass-the-gate` (both named in the hybrid for `test/seon/cluster/merge_test.clj`; the filed tasks are `seon.cluster.squash/…`). Red by absence.
- Stored runs on default: no member row for either M7 proof.

### 3. Filed tasks and proofs

- `resources/seon/tasks/seon.cluster.squash.edn` → `seon.cluster.squash/a-merge-whose-reached-definition-moved-writes-nothing` and `seon.cluster.squash/a-prove-steps-designed-reds-pass-the-gate` (the latter names `seon.context/designed-red?` in its intent). The gate task's `:must-read` lists `seon.test/change-reds` and `seon.cluster.source/declarations-held-tx`, which must be retired with the change.
- `resources/seon/tasks/seon.test.edn` → `seon.test/change-reds-judges-a-test-main-does-not-hold-as-not-red-there` (retired by M7 per the hybrid, with its behavior re-proven as `broken` at B by a second deftest). The task stays filed until that deftest exists.

### 4. Prior attempts, gotchas, and chain friction

- **`step-status` judges a task step, not a merge.** `seon.context/step-status` branches on `:implement` and `:prove`; every other step kind is `:working`. A merge gate has no step kind. Using `step-status` on the merge branch needs either a merge step kind (a new enum value in `seon.context.edn`) or a constructed observation whose kind is one of the two. The hybrid does not say which; choose before writing. The `:seon.context/step-observation` schema requires `head` and `fork-point`, so the gate must build both from the runs on the merge branch and at B (`seon.test.runner/result-at-reach`, M1).
- **Designed reds versus the root decision.** The current gate sends a red that is already red on main to root (`:seon.source/red-on-target-error`). `step-status` answers `:working` for a red that is not broken by the change, which would refuse with the untyped `:seon.source/red-test-error`, not stop for root. The owner rule in AGENTS (a test already red on main stops it for root) has no M7 row. Decide before M7: a `:red-on-target` verdict must remain a root stop, or the phase changes the rule.
- **`:parents` removal changes the base commit.** `accept-merge!` returns `:seon.source/base-commit-id` by reading the parent of the merge commit that is not a merged parent. `write-back!` and `commit!` read it for their refusals. Without `:parents` the base must come from the assignment's `:seon.task/merge-base` (M3) or the branch's base ref (`seon.cluster.registry/base-commit-id`, deleted in M17). Name the source before deleting the transaction option.
- **`declarations-held-tx` is replaced by a CAS in `merge-tx`, but `merge-tx` is the cluster-facts squash.** The declarations path (`accept-merge!`) does not call `merge-tx`. M7's replacement sits on a different path from the one it names, unless M6 has already merged the two engines (cross-phase fact 1). Sequence M6 before M7.
- **`red-test-verdicts` depends on `seon.test.runner/red-result?`, which reads the runner's `ex-data` type.** The narrow check confirmed the typed-versus-untyped red split matches the runner; keep the `:seon.error/unimplemented` type as the only designed-red marker.
- **The cost row.** Gate reuse on an unchanged result under 100 ms. `seon.test.runner/latest-results` is what `change-reds` reads today; the replacement must not re-run a proof that the step already holds.
- **M1 and M7 both change `step-status`'s inputs.** M1 added `:satisfied` and `green-before`. M7 must not re-decide `:satisfied`; the gate treats `:satisfied` (all green at B) as an admitted proof, not as a reason to skip the gate.
- Chain friction: none of chain-experiment-2 or -3's frictions is a merge-gate defect; the designed-red path (a prove step whose stub is red by `:seon.error/unimplemented`) is what M7's second proof protects, and chain-experiment-3 met it when the Design step stopped at a held step rather than a merge.

### 5. File collisions

- `src/seon/test.clj`: **held by `root-drift` and dirty in the worktree** (a new `request-changes`, uncommitted). M7 deletes `change-reds` in this file, so it cannot be claimed until `root-drift` commits or releases. This is the hardest collision in the three phases.
- `src/seon/cluster/source.clj`: M6, M7, M9, M11, M17 (see M6 §5).
- `src/seon/context.clj`: `designed-red?` (M7), `current-state` (M14), offered operation (M6). Sequence M6, then M7, then M14.
- `test/seon/test_test.clj` (held by no lane today; the `change-reds` case is M7's). `test/seon/cluster/merge_test.clj` (M6 rename and M7 root-decision cases; M4's conflict test lives in `squash_test`).
- `resources/seon/schemas/seon.test.member.edn` and `resources/seon/schemas/seon.test.edn`: description-only mentions of `change-reds`; include them in M7's sweep.

---

## Biggest risk per phase (for the reply)

- **M5:** removing `land-ends!` before `apply-actions!` emits `:merge` stops every done end from reaching main until M8. Reorder or re-express the copy in M5.
- **M6:** two merge engines (declarations `accept-merge!` and cluster-facts squash `merge-tx`) and no ruling on which one `seon.merge/merge!` is; M4 and M5 needs form a cycle with M6 if M4 is reordered after it.
- **M7:** `seon.context/step-status` has no merge step kind, and the root-decision path for a red already on main has no replacement; `seon.test.clj` is held by `root-drift`.
