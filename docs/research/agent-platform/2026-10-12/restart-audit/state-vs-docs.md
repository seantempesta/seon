---
title: "Restart audit: phase state at the code versus the planning docs"
type: research
status: read-only audit at HEAD 015673f47 (working tree has no src changes; only tmp/ and untracked docs)
---

# State versus docs (HEAD 015673f47)

Method: each phase checked by symbol and attribute in `src/`, `resources/seon/schemas`, `test/`, and by `git log`. Doc claims were not trusted. Nothing was run on default.

## Counts

- `resources/seon/tasks/*.edn`: 94 files, **783 tasks** (plan §0 still says 705).
- Tasks with at least one deftest tagged `:seon/tasks` naming them: **155** (180 distinct task symbols are tagged in `test/`; **25 tags name no task**, i.e. stale or retired).
- So **628 tasks have no tagged proof** (red or not written). Of the 27 filed phase tasks (U0-U7, P1-P10, E2-E7 and the old hybrid-agent-world P-phases), proofs exist for P1, P2, P3, P4, P5, P6 and E2, plus E3 (tagged to the old two-fork test, see below). No proof exists for U0-U5, U7, P7, P8, P9, P10, P-final, E4, E5, E6, E7.
- Phase numbering collides: `hybrid-agent-world.md` P0-P7 and `hybrid-step-semantics.md` P1-P10 both appear as "Phase Pn" in task intents (for example `seon.task/a-refinement-on-a-branch-reaches-main-only-at-the-end` is the old P4; `seon.context/reached-reds-are-invisible-to-the-agent` is the new P4). Task intents must say which document.

## Phase status

### Step semantics, P1-P10 and P-final

| Phase | Status | Evidence |
|---|---|---|
| P1 (F1) | **DONE** | b09c02b29. `seon.test.runner/latest-results` has no `:seon.test.run/branch` filter (docstring: "reach digest decides"); `first-proof-run-t` is keyed by the attempt. Tests `seon.test.runner-test`, `seon.turn-test` tagged. |
| P2 | **DONE** | 9d25711e7. `:seon.task/step` in `seon.task.edn`; `:error` in the attempt end enum; `:seon.task.attempt/relaunch` and `/relaunch-fork`; `:seon.error/unimplemented`; `seon.task/obligations`. Tagged `seon.task-test/obligations-...`. |
| P3 | **PARTIAL (mostly done)** | 7ddbb2c1e. `seon.context/step-status`, `seon.turn/record-broken!` judged at `fork-commit`, no main run in the test step. But `seon.test/change-reds` still exists, with live callers in `seon.cluster.source` (the merge gate, line ~1047) and a test; the doc's "deleted" list names `change-reds`' main acquisition. `seon.turn/task-proofs` also still exists (now a proofs reader, not the old shape). `test-step` still has a try/catch that folds a throw into the pass values. |
| P4 | **DONE** | 031125438, be32e7501. `seon.context-test/broken-tests-show-with-their-failures`. |
| P5 | **DONE** | 015673f47. `seon.cluster.agent` validity goes through `seon.task/obligations` at the fork point; `db-flow-test/a-successor-whose-proof-its-predecessor-wrote-starts` is tagged (two deftests). |
| P6 | **DONE** | 015673f47. `seon.turn/end-task!` records a merge request only when `(nil? needed?)`. |
| P7 | **NOT STARTED** | `seon.cluster.agent/apply-plan!` still has its per-action `(catch Exception failure` (about line 1979); no test `a-failed-start-ends-its-attempt-and-the-next-plans` exists. The `:error` end value is only in the schema. |
| P8 | **NOT STARTED** | `follow!` still writes the `:cancelled` end on main (about line 1951) with no branch deletion; no derived lifecycle. |
| P9 | **NOT STARTED** | no `my.root/relaunch!` (`my.root` has only instructions, merge-requests, merge!, close!, ended); `relaunch` appears only in the schema; `seon.task/frontier` unchanged. The test the doc names, `db-flow-test/a-capped-attempt-relaunches-only-by-roots-fact`, does not exist; the filed task is `seon.cluster.agent/a-capped-attempt-has-no-relaunch` (name mismatch). |
| P10 | **NOT STARTED** | `seon.cluster.agent/agents-to-start` and its start loop and catch in `db<->flow` exist (test `lazy_agents_test.clj` still calls it); `seon.cluster.host` still calls `agent/start-branch-flow!`; no ROOT or AGENT declaration in `seon.mcp`. There are 3 starters (`plan`, `agents-to-start`, `host/start!`), not one. |
| P-final | **NOT STARTED** | Depends on P7-P10 and the U phases. |

### Merge requests and auto-merge, U0-U7: **none started**

Absent: `:seon.task.attempt/merged`, `:seon.error.occurrence/attempts`, `:max` as a `:seon.cluster/conflict` value (only `:add`, `:ours`, and so on), `seon.cluster.squash/auto-merge-tx`, `:datahike.gc/roots`, `:seon.message/undeliverable` as an attribute (it is only a value built by `seon.cluster.message/undeliverable`), `seon.task/ready`. All the to-be-deleted pieces still exist.

| Phase | Status | Evidence |
|---|---|---|
| U0 | NOT STARTED | `:seon.squash/attempt` (used in `seon.cluster.agent`), `:seon.squash/copy` and `/conflict` (`seon.squash.edn`, `seon.cluster.squash`) all exist. `seon.program-test/cluster-attributes-are-the-declared-cluster-entities` still asserts `:seon.message/to` is excluded (line 1343). Task `seon.program/merged-and-attempts-are-cluster-attributes` has no tagged deftest. |
| U1 | NOT STARTED | `seon.cluster.squash` still defines `squash-merge`, `squash-merge!`, `copy!`, `conflict-task!`, `add-count`, `collision-remap` (with its `when-not` base condition); no `auto-merge-tx`. |
| U2 | NOT STARTED | `db<->flow` has no merge-request auto-merge; it calls `land-ends!` and `settle-kept-branches!` inside a try/catch (around line 2313). |
| U3 | NOT STARTED | `seon.cluster.agent/land-ends!` still calls `seon.cluster.squash/copy!` (line 2165); `unlanded-ends`, `record-error-end!`, `detached!`, `settle-kept-branches!` all exist. The task is filed under `seon.flow/...`, tagged with a pre-hybrid proof. |
| U4 | NOT STARTED | no `:datahike.gc/roots` in `reference-code/datahike`. |
| U5 | NOT STARTED (and gated by P10) | `:my.message/keep?` still exists (task `a-send-writes-nothing-unless-it-keeps` is tagged and passing). |
| U6 | NOT STARTED | `seon.task/frontier` and `frontier-rules` unchanged (they are also referenced in `seon.flow.plan.edn` and `seon.cluster.agent`). |
| U7 | NOT STARTED | `seon.error.occurrence/attempts` absent. |

Related landed work, outside U: b69403e7b (P7 of the old hybrid-agent-world: the recipient's mailbox writes a received message on its own branch), c1e0c97c9 (inbox offer, sender copies only by request), 4718638bd and 9e7958e30 (old P4 slices: attempt row and task writes in the branch's own world; `seon.task/main` is gone), b7cc27520 (attempts fork the predecessor's end commit, recorded as `:seon.task.attempt/fork`), 564cd6d50 (retired cluster identity admission rule).

### Immutable program context, E1-E7

| Phase | Status | Evidence |
|---|---|---|
| E1 | **DONE** | f424382cd bumped `reference-code/sci` to a5ba6af3 ("A frozen context is a value: sci.core/freeze"); `sci.core/freeze` exists. Its task was removed from `seon.sci.eval.edn`. |
| E2 | **DONE** | f424382cd. `seon.sci.eval/program-context` plus `derive-program-context`; `seon.sci.eval-test/no-agent-work-writes-a-program-context` is tagged. |
| E3 | **NOT STARTED** | `fork-for-agent` absent. `copy-base-ctx`, `fork-cluster-ctx` (used by `seon.test`, `seon.cluster.boot`), `branch-ctx`, `fork-for-turn` (used by `seon.turn` and `seon.cluster.agent`) all remain. Tagged test `a-fresh-agents-environment-costs-one-fork` exists at `agent_test.clj` line 2132, presumably red. |
| E4 | **NOT STARTED** | `base-bindings` still defined; no task proof. |
| E5 | **NOT STARTED** | `transfer-evaluated-roots!` and `acquire!` remain. |
| E6 | **PARTIAL** | b7cc27520 forks the predecessor's end commit and records it on the attempt, as `:seon.task.attempt/fork`, not `fork-point`. `write-attempt!` still takes `(first (sort fork-point))` of a join, so the octopus fork is absent. The red proof `a-successor-starts-from-its-predecessors-program-context` does not exist. |
| E7 | **PARTIAL** | 35e906c38 ("an overlay weighs what it adds") touches `context-weight`. `script/seon/program_context_weight.clj` does not exist and the task has no tagged proof. |

## False or stale claims at the code

1. **plan.md §0 is stale throughout.** It is dated 2026-10-11 afternoon and never mentions hybrid-push, step-semantics or immutable-env. It says "705 tasks" (now 783), "agents cannot edit their task at the REPL yet" and "the chain ... has never run" (chain experiment 1 ran 2026-10-12), and its item 6 builds on `seon.task/frontier` (U6 renames it).
2. `hybrid-step-semantics.md` §5, P3: lists `seon.test/change-reds`' main acquisition as deleted. The function still exists, used by the merge gate (`seon.cluster.source`). The gate's main acquisition remains a P3 gap or needs a task.
3. `hybrid-step-semantics.md` P9 names test `db-flow-test/a-capped-attempt-relaunches-only-by-roots-fact`. The filed task is `seon.cluster.agent/a-capped-attempt-has-no-relaunch`, and neither the name in the doc nor a test exists.
4. `hybrid-step-semantics.md` says `follow!` "already passes :seon.agent/finish? true" and fork-attempt "(E6 landed)". The first is true; the second overstates: only the end-commit fork landed, with a different attribute name (`:fork`, not `:fork-point`), and `hybrid-immutable-env.md` E6 still says `fork-point`.
5. `hybrid-push.md` U1 task text allows messages owned by `:seon.message/from` **or** `/to`; §3 of the same doc says `from` only (also flagged by mr-u0-u1).
6. `hybrid-push.md` U0 row says "delete `:seon.squash/attempt`, `/copy`, `/conflict`" while §5 lists these deletions without assigning a commit. The U0 row and the U1 row (delete `copy!`) cannot hold at HEAD because their callers survive (see below). The `basis:` line cites HEAD 4dbd4aaf2, but the doc's "Deleted" symbols are all still present, which is consistent.
7. `hybrid-push.md` header says "phases U0-U7 filed as tasks". They are filed, but U3's task is `seon.flow/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted`, tagged with a pre-hybrid proof that asserts the old copy-back design, so "tagged" there is not a U3 proof.
8. `mr-u0-u1-landing.md` is correct on every symbol checked: `land-ends!`, `record-error-end!`, `unlanded-ends` and `settle-kept-branches!` use `:seon.squash/attempt`; `test/seon/cluster/registry_test.clj` line 820 area calls `copy!`. One addition: `seon.task` (line ~538) and `seon.cluster.agent` docstrings cite `copy!`, so deletion also converts docstrings.
9. 25 `:seon/tasks` tags in tests name tasks that no longer exist (stale proof tags).
10. Working tree: untracked `docs/research/.../mr-u0-u1-landing.md`, `src-clm/`, `docs/prds/next-turn/`, and a stray file named with a literal newline in `docs/research/agent-platform/landing/`.

## The mr-u0-u1 design question

U0 and U1 as written delete `:seon.squash/*` schemas and `copy!`, `squash-merge!` and `conflict-task!` before U2/U3 replace their consumers (`land-ends!`, `record-error-end!`, `unlanded-ends`, `settle-kept-branches!`, `registry_test`). That breaks HEAD (contracts reference missing schemas; callers reference missing functions).

**Option: add first, delete each old piece in the commit converting its last consumer.** It is consistent with:
- CLAUDE.md: "A moved, renamed or deleted function converts every caller in the same commit ... and retires the old path"; "HEAD must boot"; "Grow by accretion ... the old name keeps its promise until its last consumer has moved"; "every addition names what it replaces". Each commit loadable, with deletion in the commit that moves the last caller.
- hybrid-push.md §5: it lists what is deleted without binding deletions to U0/U1, and §6's order places the consumer conversions in U2/U3. Only the U0 and U1 table rows (and the filed task intents) literally say "delete" and need editing: U0 adds the new attributes, `:max`, and `last-at` rule and deletes nothing; U1 adds `auto-merge-tx` and its proofs; the `:seon.squash/*` schemas, `copy!`, `squash-merge!`, `conflict-task!`, `add-count`, `selected-coordinates` and the registry_test caller go in U3 (with `land-ends!`). It also requires giving `test/seon/program_test.clj` to U0 and fixing the `from`/`to` text.
- No fence or duplicate-path problem: the interim coexistence is two writers for a few commits, and it is retired by U3, which CLAUDE.md allows as long as the deletion is planned and the old path is named.

Residual risk: the doc's first sentence promises the merge request replaces the copy; between U1 and U3 both exist, so do not call a phase "done" in the plan until the deletion lands. Options 2 (combine U0-U3) and 3 (hold) are compatible but larger or idle.
