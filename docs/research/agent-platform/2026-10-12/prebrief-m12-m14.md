---
title: "Pre-briefs for hybrid phases M12, M13 and M14 (closes and relaunch, one starter, the branch is the proposal)"
type: research
status: prebrief, 2026-10-12. Read-only research; no source, test or resource was edited. Each phase lists what the implementer must know before the first form.
basis: HEAD e70ed838d. Lane m5 (M5) holds uncommitted worktree edits to src/seon/cluster/agent.clj, src/seon/task.clj, test/seon/cluster/db_flow_test.clj and resources/seon/tasks/seon.cluster.agent.edn; root-drift holds src/seon/turn.clj, src/seon/test.clj and test/seon/turn_test.clj. Symbols are fully qualified; no line numbers appear here. Stored runs were read on default (cluster `default`, jvm mode, root).
plan: docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md §6 (rows M12, M13, M14), chain-experiment-3.md (G6, G7, G8).
---

# Pre-briefs: M12, M13, M14

Every claim was checked at the code by `rg` and by reading the defns. "Callers" means every reference in `src`, `test`, `script` and `resources`. Words are Clojure, Datahike and git's.

## Cross-phase facts that change all three

1. **M5 is rewriting the lifecycle right now.** The worktree's `seon.cluster.agent/next-actions` already carries `:stop`, `:merge-base`, `:merge`, `:close` and `:delete-branch`, and `seon.cluster.agent/apply-plan!` routes them through `seon.cluster.agent/apply-lifecycle!`. That is M5's uncommitted work, not HEAD. M12's `:cancel` and close derivations extend that same `next-actions`, so M12 starts only after M5 lands and is read against HEAD plus M5.
2. **Stored evidence is absent.** A jvm query of `:seon.test.member/symbol` for every name in this brief returned one row: `seon.cluster.lazy-agents-test/idle-agents-wait-for-work-but-schedule-owners-need-their-timers`. None of the M12, M13 or M14 proof names has a member row. Absence is "unknown", not green.
3. **Lane collisions.** `src/seon/cluster/agent.clj`, `src/seon/task.clj` and `test/seon/cluster/db_flow_test.clj` are held by m5. `src/seon/turn.clj`, `test/seon/turn_test.clj` and `resources/seon/tasks/seon.turn.edn` are held by root-drift. `src/seon/context.clj`, `src/my/program.clj`, `src/my/root.clj`, `src/seon/cluster/host.clj`, `src/seon/mcp.clj` and the test files for context and program are not claimed and have no worktree edits.

---

## M12 — closes, cancel versus pause, relaunch by root's fact (P8, P9; absorbs G6)

### 1. Symbols changed or deleted, with all callers

| Symbol | Role today | Callers and mentions (non-doc) |
|---|---|---|
| `seon.task/ready` and its `seon.task/ready-rules` | Lists leaves to launch. Its eligibility rule excludes a leaf with any attempt ending `:given-back`, `:capped`, `:stalled`, `:cancelled` or `:error`. Nothing re-admits it. This is the G6 root (see §4). | `seon.cluster.agent/plan-facts` (readers of the startable set); `seon.task/done?` and `seon.task/resolution` use `leaf-done`, not `ready`. Tests: `seon.task-test` (ready cases) and the `db_flow_test` reach tests. |
| `seon.cluster.agent/follow!` | Applies `:pause`, `:resume`, `:cancel` and `:stop` to a started graph. For `:cancel` it stops the graph and writes the assignment `:seon.task.attempt/end :cancelled` with reason "The task's intent is :cancelled." on the branch-side write, so the end is written by follow!. | `seon.cluster.agent/apply-plan!` only. The `:cancel` branch needs P8's change: no end written by follow!, `close-tx` writes `:closed` with end `:cancelled`, then `:delete-branch`. |
| `seon.turn/close-tx` (NOT `seon.cluster.agent/close-tx`; that name does not exist) | Writes the turn's close. P8's `close-tx` wording in the hybrid means the assignment close, which is `seon.cluster.agent/close!` (private, M5's `next-actions` `:close`). | `seon.turn` callers and tests; `test/seon/cluster/evaluate_sources_test` and `test/seon/cluster/turn_test`. Do not reuse the name for the assignment close: rename or reuse `seon.cluster.agent/close!`. |
| `seon.cluster.agent/plan` (`:start-flow` with `:seon.task/execution :running`) | The pure planner. Its `intent` function turns `:held` and `:paused` into `:paused`; `follow` maps the rest. | `seon.cluster.agent/run-pass!`; `test/seon/cluster/agent_test` and `test/seon/cluster/db_flow_test` reach it. |
| `my.root/relaunch!` | **Does not exist.** `src/my/root.clj` defns are `instructions`, `merge-requests`, `merge!`, `close!`, `ended`. | New. M12 adds it; its callers are the `my.root` operation list in `seon.cluster.message` and `seon.context` offered operations (none today). |
| `resources/seon/schemas/seon.task.attempt.edn` `:relaunch` and `:relaunch-fork` | **Exist already** as `:string` with `:seon.wake/starts true` and `:uuid`. So a root fact written there wakes db<->flow. | `resources/seon/schemas/seon.task.edn` assignment row members `:seon.task.attempt/relaunch` and `:seon.task.attempt/relaunch-fork`; `test/seon/task_test` writes both as a shape test. No `src` reader or writer exists. |

Not in M12's list but must move with it: `seon.cluster.agent/fork-attempt!` reads `:seon.task.attempt/relaunch-fork` only after M10 (`:seon.task.attempt/fork` removal). M12 must name the commit that `relaunch-fork` supplies; before M10 it forks the predecessor's end as today.

### 2. Tests reaching these symbols, and red state

- `seon.cluster.db-flow-test/a-capped-attempt-relaunches-only-by-roots-fact` (the M12 red proof named in the task): **does not exist**. Red by absence.
- `seon.cluster.agent-test/each-intent-names-one-roster-state` (the R2 table proof named by `disarm-splits-into-stop-and-cancel`): **does not exist**. The `test/seon/cluster/agent_test.clj` file exists but has no deftest of that name. Red by absence.
- `seon.cluster.db-flow-test/a-cancelled-assignment-closes-without-a-task-for-root` (the hybrid's M12 proof, row M12 in §6): **does not exist and has no task entry** in `resources/seon/tasks/seon.cluster.agent.edn`. The hybrid names it in §6 only. File it as a task before the phase, or the phase has no proof.
- The `seon.task/ready` reach tests: no stored member row on default. Their current red state is unknown.
- `test/seon/task_test` (relaunch shape test): reads `:seon.task.attempt/relaunch` back. It stays green if the attribute keeps its type; M12 must keep its shape.

### 3. Filed tasks and red proofs

- `resources/seon/tasks/seon.cluster.agent.edn` → `seon.cluster.agent/each-intent-names-one-roster-state` (kind `:function`; `:needs` `seon.flow/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted`, the M5 proof). Its intent names `seon.cluster.agent/follow!` and `seon.turn/end-task-call` among its must-reads; `follow!` is the symbol to change.
- `resources/seon/tasks/seon.cluster.agent.edn` → `seon.cluster.agent/a-capped-attempt-has-no-relaunch` (`:needs` the table task above; red proof `seon.cluster.db-flow-test/a-capped-attempt-relaunches-only-by-roots-fact`, not written). Its intent says "Files: `src/seon/cluster/agent.clj`, `src/my/root.clj`, `src/seon/task.clj` (the ready rule)". The prose and the must-read `my.root/merge!` are consistent with the design; the must-read list does not name `my.root/relaunch!`, which is new.
- Proof for the cancel row (`a-cancelled-assignment-closes-without-a-task-for-root`): no task. Add a task entry in `resources/seon/tasks/seon.cluster.agent.edn` with the deftest name before the phase starts.

### 4. Prior attempts, gotchas, and chain friction

- **G6 root, confirmed at the code.** `seon.task/ready` drops any leaf whose attempt ended `:given-back`, `:capped`, `:stalled`, `:cancelled` or `:error`, and no rule re-admits it. So a capped attempt is never relaunched by the pass, and the only way forward in chain experiment 3 was rewriting the end to `:sent-back` (six times). The M12 change is: an ended assignment of a terminal class with a `:seon.task.attempt/relaunch` fact is not terminal for `ready`, and `my.root/relaunch!` writes that fact on the assignment. The fact must be the latest one for the leaf; `ready` must read "terminal end with no later relaunch", not any terminal end.
- **Relaunch numbering.** `seon.cluster.agent/plan` numbers the next assignment as `(inc (get ended handle 0))`. The relaunch path must keep that count so assignment n+1 is written, not a second attempt n. Check `plan-facts`' `ended` count includes the capped assignment (it does today), so the new assignment gets the next number.
- **`:cancel` writes its end from `follow!`.** `follow!` writes `:seon.task.attempt/end :cancelled` in the same branch as the graph stop. P8 says no end is written by `follow!`; the close is `close!` from `next-actions`. Removing the write before `close!` exists leaves a cancelled assignment with no end on main; order: `close!` first, then delete the write.
- **Cancel versus pause.** `follow!`'s `:pause` and `:resume` set `:seon.flow.plan/paused?` on the registry entry. P8 keeps pause as `flow/pause` plus `flow/ping-proc` acknowledgment. Do not change the pause path when splitting `stop-flow!` (that is `disarm-splits-into-stop-and-cancel`, its own task).
- **Cancel mid-merge.** `seon.task/close!` and `merge-tx` each write only when `:seon.task/outcome` is absent. A cancel and a merge racing both reach the writer; whichever commits first wins. Tests must cover the second writer answering `[]`.
- **Ready refusal reasons.** `seon.task/ready`'s docstring says "an :error end, a failed start among them, is terminal too: no pass assigns it again by itself." M5's P7 writes the `:error` end at the Flow proc boundary. The relaunch fact then becomes the only re-admission path for `:error`; the docstring must change with the rule.
- **G8 (end on branch until a hand pass).** See §5 below; it is not an M12 phase concern.
- **Cost bound.** `close-tx` under 10 ms per the hybrid. Measure the new `ready` (one more `not-join`) on a ten-task tree before and after; `ready` is read every pass.

### 5. Where G8 is fixed

G8 is recorded in chain experiment 2 (the `:done` end sits on its branch while `seon.cluster.agent/unlanded-ends` lists it) and chain experiment 3 (every hand-started attempt's end waited 6 min 24 s for a hand pass; every db<->flow-started attempt landed by itself). The mechanism is not reproduced here.

- Known facts: `:seon.task.attempt/end` carries `:seon.wake/starts true` in `resources/seon/schemas/seon.task.attempt.edn`, so an end written on a branch wakes db<->flow **through the branch's route**, which `seon.cluster.agent/start-branch-flow!` registers with `seon.cluster.wake/route!`. An attempt started by `seon.cluster.agent/start-attempt!` gets its route; the notes do not record how the hand-started graphs got theirs.
- **Hypothesis, unmeasured:** a hand pass started graphs whose route was not registered when the end was written, so the wake reached nothing until the next pass. The implementer confirms or refutes this at the REPL before writing the fix: start an attempt by `plan` on a branch, write an end, and count passes without a hand act.
- **Fix location:** M13 (one starter: every graph starts from `plan`, so every branch gets its route at start) and M5 (derived `:close` in `next-actions` on every pass). M12 does not fix G8; M12's relaunch wakes db<->flow by its own `:seon.wake/starts` attribute, so a relaunch cannot depend on a route.
- **Proof:** `seon.flow/an-ended-attempt-reaches-main-in-one-transaction-and-its-branch-is-deleted` (M5) is the end-to-end proof; M13 adds a case where the attempt was started by `plan` and its end lands with no hand pass.

### 6. File collisions and sequence

- `src/seon/cluster/agent.clj`: held by m5. M12's edits (`follow!`, `next-actions`, `apply-plan!`) come after M5 lands.
- `src/seon/task.clj` (the `ready` rule and its docstring): held by m5 (M5's `:delete-branch` predicate). M12 waits for M5.
- `src/my/root.clj`: not claimed; adds `relaunch!`. Also touched by M6 (`merge!` key), M16 (`root-offers-a-sent-message-once`) and M8.
- `test/seon/cluster/db_flow_test.clj`: held by m5; M12 adds two deftests after M5 lands.
- Order: M5 → M12 (the hybrid's "M12 after M5"). M12 also needs M10 for the `relaunch-fork` commit to mean "fork main or this commit" (M10 deletes the predecessor clause in `fork-attempt!`); M12 without M10 forks the predecessor's end.

---

## M13 — one starter (P10)

### 1. Symbols changed or deleted, with all callers

| Symbol | Role today | Callers and mentions (non-doc) |
|---|---|---|
| `seon.cluster.agent/agents-to-start` (private) | Starts unstarted agents with work or schedule owners: `seon.schedule.task/owner` plus `seon.turn/more-agent-work?`. Called by `run-pass!`'s last step. | `seon.cluster.agent/run-pass!` only; `test/seon/cluster/lazy_agents_test` calls `#'agent/agents-to-start` directly (its assertions). |
| `seon.cluster.host/start!` (private; the hybrid says `host/start!`) | Admits the agent on the cluster and branch (`register!`), then calls `seon.cluster.agent/start-branch-flow!`, which is the **second** graph starter (the first is `plan`'s `:start-flow`). | `seon.cluster.host/host!` only. `host!` is called by `seon.mcp` (the MCP entrant, around its `host/host!` at the agent-entrance branch) and by `seon.cluster.host`'s own tests. |
| `seon.cluster.agent/start-branch-flow!` | Registers the branch's wake route, then `start-flow!`. Called by `start-attempt!` (db<->flow) and by `host/start!`. | `seon.cluster.agent/start-attempt!`, `seon.cluster.host/start!`, `seon.cluster.agent/follow!`'s relationship is indirect. |
| `seon.turn/next-agent-work`'s `(string? held)` clause | Opens an `:open` situation for a `:held` task with unanswered wakes (the held clause P10 deletes). | `seon.turn/more-agent-work?` and `seon.turn/next-agent-work`; `test/seon/turn_work_test` (ten or more cases read `more-agent-work?` against `next-agent-work`); `test/seon/cluster/turn_test`; `test/seon/no_provider_test`. |
| `seon.cluster.agent/plan`'s held handling | `intent` turns `:held` into `:paused`; `:held` assignments get no `:start-flow`. | `seon.cluster.agent/run-pass!`; plan tests. |
| `src/seon/mcp.clj` entrant | Calls `host/host!` for an agent entrance, declaring nothing about kind. | `seon.mcp` only. The hybrid's "the MCP entrant declares ROOT or AGENT" is new. |

### 2. Tests reaching these symbols, and red state

- `seon.turn/a-held-task-opens-no-model-turn`: the hybrid's red proof is "this task's deftest in `test/seon/turn_test.clj`". **No deftest of that name exists** (`rg` finds the task entry and nothing else). Red by absence. The test must tag it with `:seon/tasks` per the house rule.
- `test/seon/cluster/lazy_agents_test`: a deftest names `seon.cluster.lazy-agents-test/idle-agents-wait-for-work-but-schedule-owners-need-their-timers` (the stored member row). Its pass or fail is not read here; treat as unknown. It calls `#'agent/agents-to-start` directly, so deleting `agents-to-start` breaks the test by name. It converts, per the task entry.
- `test/seon/turn_work_test` (around the `more-agent-work?` agreement cases): reach `next-agent-work`'s held clause. Red state unknown.

### 3. Filed tasks and red proofs

- `resources/seon/tasks/seon.turn.edn` → `seon.turn/a-held-task-opens-no-model-turn`, `:needs` the M12 table task `seon.cluster.agent/each-intent-names-one-roster-state` (per the hybrid, after M5 and M12). Its intent names the three starters and the mcp declaration. Its must-read names `seon.turn/next-agent-work`, `seon.cluster.agent/agents-to-start`, `seon.cluster.host/start!` and `seon.cluster.agent/plan`.
- `resources/seon/tasks/seon.turn.edn` is held by root-drift (the entry is named "refinement in `merge-tasks-patches.md`"). M13's task edit waits for root-drift's release or uses the patch file.

### 4. Prior attempts, gotchas, and chain friction

- **The entrant graph start has no replacement in the hybrid.** `seon.cluster.host/host!` starts an AGENT's graph via `start-branch-flow!` so the entrant can talk to an agent with no task. P10 deletes that start and says "every graph starts from `plan` over an assignment of a `:running` task." An entrant agent with no task has no assignment and no plan row, so after M13 an MCP-entered agent has no graph. **Decide before M13:** either the MCP entrant writes an assignment for every entered agent (a new kind of task), or the entrant keeps one starter for entrants and the hybrid's "one starter" becomes "one starter for tasks and one for entrants". This is the largest M13 design question and the hybrid does not answer it.
- **`agents-to-start` also serves schedules.** Schedule owners (`seon.schedule.task/owner`) are woken only through `agents-to-start`'s schedule clause. Deleting it removes the schedule timer's starter; the schedule-owner test (`lazy_agents_test`) is the proof. Confirm where the schedule timer's graph starts before deleting.
- **`agents-to-start` also prime-starts agents with messages at resume.** `db<->flow`'s docstring says a committed agent creation is a start wake (`:seon.agent/id`, `:seon.wake/starts`), and `seon.cluster.wake/starting-attributes` is the one declaration. After a restart, `agents-to-start` finds agents with `more-agent-work?`; without it, an agent with an unanswered message and no assignment is never started after a restart. Test it with a restart fixture before deleting.
- **`seon.turn/next-agent-work`'s held clause is a `turn.clj` edit under root-drift.** M13 cannot change `src/seon/turn.clj` until root-drift releases it. The patch file for that edit is named in `merge-tasks-patches.md` (not read here).
- **`plan`'s `:held` handling.** `plan`'s `intent` already maps `:held` to `:paused`; removing the held clause from `next-agent-work` keeps a `:held` task from opening a turn without touching `plan`.
- **G7 link.** Chain experiment 3 says the G7 fix (M10, M5) removes 11 of 23 hand acts. M13 is a precondition for G8's fix (see M12 §5): with one starter, every route is registered by `plan`'s start.

### 5. File collisions and sequence

- `src/seon/cluster/agent.clj`: held by m5 (`agents-to-start` and `run-pass!` are in the file it edits).
- `src/seon/turn.clj`: held by root-drift.
- `src/seon/cluster/host.clj`, `src/seon/mcp.clj`: not claimed; M13 edits both.
- `test/seon/turn_test.clj`: held by root-drift; `test/seon/cluster/lazy_agents_test.clj`: not claimed.
- Order: M13 after M5 and M12 (the hybrid's order; M16 after M13).

---

## M14 — the branch is the proposal (`:seon.context/changes` in the current-state block)

### 1. Symbols changed or added, with all callers

| Symbol | Role today | Callers and mentions (non-doc) |
|---|---|---|
| `seon.context/current-state` | Builds the block: held task, turns, proofs, step, send-backs, unread, branch cost (`branch-cost`), namespace, must-reads. Adds `:seon.context/cost` only when the branch's costs differ from main's. | `seon.context/current-state-ai`, `seon.context/model-context` (through `current-state`), `script/seon/dev/context_corpus` (label-cost ceiling), `script/start_flow_benchmark`, `test/seon/context_test`, `test/seon/test_failure_facts_test` and many reach tests. Adding `:seon.context/changes` is additive; `seon.context/current-state-view` schema (`resources/seon/schemas/seon.context.edn`) must gain the key. |
| `seon.program/changed-identities` | Exists in `src/seon/program.cljc` (declared inside a reader-conditional block, so `rg "^(defn"` misses it). Signature `[base database bounds]` (and a four-arity with attributes). `base` is a basis-t or a database value. | `seon.cluster.source`'s publication path (`seon.cluster.source/changed-identities` is a different function, publication report identities); `test/seon/program_test` (`changed-identities-scope-the-digest-maps-to-one-change` and neighbours). |
| `seon.cluster.source/compare-branch` | The merge's comparison on the merge base: changed identities, base and branch digest maps, and the three-way classification. Materializes the base and branch values; the caller releases them. | `my.program/head-comparison` (private), the merge path (`seon.cluster.source/accept-merge!` callers), `test/seon/cluster/merge_test`. |
| `my.program/diff` | Your branch's program difference from its target branch's head, through `head-comparison` and `compare-branch`. It already compares on the merge base. | `my.program/write-branch!` shares `head-comparison`; `test/my/program_test`; `resources/seon/tasks/my.program.edn`. |
| `my.program/declaration-diff`, `my.program/history` | The dig forms (one declaration's before and after; a definition's history). | `my.program/diff`'s entries carry them as `:dig`; `test/my/program_test` (the declaration-diff deftest). |
| `my.program/restore!` | **Does not exist** (only the task `my.program/…` restore entry and `resources/seon/tasks/my.program.edn`). M15 adds it. | none. |
| `:seon.context/changes` | **Does not exist.** New key. | none. |

Correction to the hybrid: the hybrid says "`my.program/diff` reads the same base". It already does through `compare-branch`, so M14 needs no change to `diff`; it needs the same base inside the current-state block.

### 2. Tests reaching these symbols, and red state

- `seon.context-test/the-current-state-shows-the-branch-diff-since-its-merge-base` (the M14 red proof): **does not exist**. Red by absence.
- `test/my/program_test` (its `declaration-diff`, `history` and `diff` deftests): no stored member row for the M14 proof; their current red state is unknown.
- `test/seon/program_test`'s `changed-identities-scope-the-digest-maps-to-one-change` reaches `seon.program/changed-identities`. Unknown.
- The `current-state` reach tests (`test/seon/context_test`): the new key must not alter their output on a branch with no change.

### 3. Filed tasks and red proofs

- `resources/seon/tasks/seon.context.edn` → `seon.context/the-current-state-shows-the-branch-diff-since-its-merge-base` (intent: "(hybrid-done-and-merge M14) The branch is the proposal…"; `:needs` none beyond what it names).
- `resources/seon/tasks/my.program.edn` → the `my.program/restore!` entry (M15) names the M14 task in `:needs`. Its must-read list already names `seon.context/current-state`.

### 4. Prior attempts, gotchas, and chain friction

- **The base is not visible from the branch's own value (the largest M14 design question).** `seon.context/current-state` reads the agent's branch database. The assignment's `:seon.task/merge-base` is written on main by `seon.cluster.agent/write-merge-base!`, which runs after the branch is forked and before the agent's connection is acquired. The branch's own fork-time value therefore does not contain the merge base. Options: (a) read it from main through the cluster connection (the agent's `current-state` has no main connection by design), or (b) read the branch's retained base ref through `seon.cluster.registry/base-commit-id`, which takes the branch's database value and reads its store (`(:store database)`). Option (b) works today and needs no main read; M17 deletes `base-commit-id`'s readers and the `:base/<branch>` ref, so M14 uses (b) only until M17 and M17 must name M14's reader. Record this before the first form.
- **`seon.program/changed-identities` costs.** The hybrid states 0.8 ms warm on default (hybrid-push §4). It takes a base as basis-t or a database value; pass the base database from `base-commit-id`. The four-arity with attributes is the projection form; use the three-arity.
- **The base may be absent.** An agent with no base ref (a non-task agent, or a branch not forked by db<->flow) has no base commit; the block must omit `:seon.context/changes` rather than report an error. The proof row "an unchanged branch holds no `:seon.context/changes`" covers the no-change case; add a row for the no-base case.
- **Cost.** The hybrid bound is at most 5 ms per eval request on a ten-change branch. Measure `current-state` before and after on a branch with ten changes; `seon.context/current-state`'s docstring already lists its query costs, so add the diff's.
- **Render.** Each entry carries `:seon.render/form` (the dig form) as data. The current-state render is the AI face; the entries are data, not forms executed by the render step (the owner's "data, not comments" rule).
- **Derived, never stored.** The diff is computed in `current-state` each time, not written to a fact. A stored copy would drift from the branch.

### 5. File collisions and sequence

- `src/seon/context.clj`: not claimed. Held in the M6 and M7 plans (the offered operation at its merge branch, and `designed-red?`). Sequence M6 and M7 before M14 in this file, or coordinate with their lanes.
- `src/my/program.clj`: not claimed. M15 adds `restore!` to the same file; M14 must not touch `diff`.
- `resources/seon/schemas/seon.context.edn`: not claimed; M14 adds the key to `seon.context/current-state-view`.
- `test/seon/context_test.clj`, `test/my/program_test.clj`: not claimed.
- Order: M14 after M3 (the base attribute); beside M6–M13 (different files except `context.clj`). M15 after M14.

---

## Three biggest risks

1. **M13 deletes the only starter for entrants and for schedules without a replacement.** `host/start!`'s graph start is the MCP entrant's only start and `agents-to-start` is the schedule timer's and the post-restart prime's. Decide the entrant rule and the schedule starter before M13 or the phase breaks HEAD's ability to run any agent that is not a task.
2. **M12's `ready` rule must read "latest end terminal and no later relaunch".** Reading "any terminal end" re-admits nothing; reading "any relaunch" admits a leaf that was relaunched once and capped again. Both need a red row before the rule changes, and `ready`'s cost must be measured because it is read every pass.
3. **M14's base is unreadable from the branch once M17 deletes `base-commit-id`.** If M14 reads it from the branch's retained base ref and M17 removes that ref without giving the branch a visible base, the block loses its diff silently (the no-base rule would hide the failure). M17 must name M14's reader in the same commit.
