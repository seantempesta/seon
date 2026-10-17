---
title: "Task model: desired state, observed state, one reconciler"
type: plan
status: "design plan by lane task-model-plan (Opus), 2026-10-17, from the owner's directions of 2026-10-17 (tasks carry branch and environment; the task graph is the scheduler; desired vs observed state; merge is an ordinary task, no merge attribute), folding in every ruling of merge-rulings.md \"The task model (owner, 2026-10-17, later)\", which binds. Read-only probes on default (JVM mode). Not yet amended into TASK-CHAIN: phase T0 does that, after the owner answers section 12."
---

# Task model: desired state, observed state, one reconciler (2026-10-17)

**Phase status** (each implementing commit updates this line): T0 pending · T1 pending · T2 pending · T3a pending ·
T3b pending · T3c pending · T4 pending · P10 (merge-hybrid's phase, with §6.3's conflicts section) pending

## 1. The design in a few lines

The owner asked what a chain was ever doing. It held a branch, a first task, a config and a merge decision for a group
of tasks. Each of those belongs on a task. Once they do, the chain has nothing left to hold.

1. **Desired state is task facts on main.** One task schema, nothing merge-specific. A task names:
   - its prerequisites, `:seon.task/needs`;
   - its environment: its branch, its target and its config and model;
   - whether it should run, `:seon.task/execution`.

   The tasks on main are the schedule. Root, the task writer and config apply are the only writers.
2. **Observed state is facts written by their observer when the event happens.** These are:
   - a graph running, written by the registry;
   - an attempt and its end, written by root and the agent;
   - a judged done and a merge, written by root;
   - test results, written by the runner;
   - branch heads, written by Datahike.

   Nothing is inferred from history.
3. **One reconciler.** `seon.cluster.agent/reconcile` is a pure function of (desired, observed) that answers the
   minimal actions. Each action's actor writes the observed fact that closes its gap, and that commit wakes the next
   pass. Reconciling the same state twice answers nothing the second time.
4. **Scheduling and merge are not mechanisms of their own. They are rows of the reconciler's gap table (§5).**
   - A task is ready when every task it needs is done where that task's attempts live.
   - The minimal action for a ready task is "build its environment, then start". The build rule is one rule, P4a's
     prepare generalized: a task continues its need's branch, or gets a new branch holding main's head plus the
     unmerged change of each need on another branch.
   - Ready tasks start in parallel up to `:seon.config.agent/max-running`, one running task per branch, as git checks
     a branch out in one worktree.
   - **A merge is an ordinary task** (`seon.step.merge/template`) after the work it merges. Its environment's branch
     targets main, so its done step writes main through P4a's accept. A task green at its start ends with no model
     call (the existing rule), so a merge with no conflict and green members is done with no agent. A conflict or a
     red leaves work, and an agent starts with the merge template's instructions. Its block lists each conflict with
     both versions and the form that resolves it, live (§6.3).
   - Environment is desired state on the task; a running agent whose environment differs is swapped onto it at its
     next idle boundary (§6.1). One mechanism, no special paths.
5. **One path.** A single piece of work is a task with no needs, followed by a merge task when it should reach main.
   The chain fact and the per-assignment launch path (attempt rows on main, `attempt-<key>` branches,
   `seon.task/ready`) both go.

Grounding (AGENTS.md "Which proven system already solved this? Take its model whole"):
- **GitHub Actions jobs.** Jobs run in parallel unless they declare `needs:`, and each runs on its own runner
  (`runs-on`, its environment). A workflow is only the file that groups them: here it is one write of tasks.
- **Kubernetes controllers.** A spec is desired, a status is observed, and a reconcile loop does the minimum to close
  the difference. Seon's own `seon.reconcile` (config) already uses the word this way.

## 2. What a chain was doing, and where each job goes

Measured on HEAD 2ee1db4a5: 205 `:seon.chain/branch` uses, 34 `first`, 24 `config`, 14 `merge?`, 14 `attempts`,
13 `state`, 10 `tasks`, 8 `kind`, 7 `from`, 14 view keys, in 13 source and resource files and 16 test files. Live on
default: 4 chain facts (`chain-42e0e4c46b71`, `chain-91a8e6815ef2`, `chain-ce9d664c1be0`, `chain-fd3ba8395135`), none
with `merge? false`; 15 store branches, 5 of them kept and readable by `seon.mine/kept-branches`; 962 tasks on main
(961 `:held`, 1 `:stopped`), 13 of them with `:seon.task/needs`; one attempt row on main.

| # | Job the chain does today | Readers (functions) | Where it goes |
|---|---|---|---|
| 1 | Discovery: root finds work through one fact on main that wakes it (`:seon.wake/starts`) | `chain-facts`, `chain-ends`, `chain-finishes`, `finish-chains!`, `route-chains!`, `my.root/chain-requests` | The tasks on main. `:seon.task/execution` and `:seon.task/needs` are already `:seon.wake/starts`. |
| 2 | Names the branch the work is on | 47 functions read `:seon.chain/branch` (table in §9) | `:seon.task/branch` on every task, resolved by the writer (§4) |
| 3 | Keeps the branch through boot's retirement | `seon.cluster.registry/cluster-claims` (its chain clause and `task-branch-claims`) | The clause `[_ :seon.task/branch ?b]`, which `cluster-claims` already has |
| 4 | Identity, and an idempotent create (the first task's handle) | `seon.task/chain!`, `chain-branch` | Each task's own identity (`seon.task/task-handle`). `seon.task/create!` is idempotent per task. |
| 5 | Order, along `next` from `first` | `chain-read`, `chain-handles`, `chain-ends`, `chain-finishes`, `chain-view` | The `needs` graph |
| 6 | Fork point (`:seon.chain/from`) | `chain!`, `bail-chain!` (its problem text) | Deleted as a separate meaning. To continue another branch's work, a task names that branch, or needs a task on it: the build rule then carries its change (§5). |
| 7 | Config overlay (`:seon.chain/config`) | `chain!`, `chain-refusal`, `config-tx`, `desired-config` | `:seon.task/config`, converged onto the branch when the task starts (§5, row 2) |
| 8 | Whether the work merges (`:seon.chain/merge?`) | `chain!`, `chain-finishes` | Whether a merge task follows the work. A bench run writes no merge task. |
| 9 | Scoping: the branch inherits main's tasks, and the chain's work is only its `next` path | `chain-read`, `chain-ends` | Nothing to scope. The schedule is main's tasks, and a branch holds only its attempts and its agents' edits. |
| 10 | A wake route per chain branch | `route-chain!`, `route-chains!`, `release-chain-route(s)!` | A route per branch with a ready task or an open attempt, derived each pass |
| 11 | The merge unit: one merge per chain | `merge-chains!`, `finish-chains!` | A merge task (§6): an ordinary task whose environment targets main. Any number of tasks may share the branch whose change it carries. |
| 12 | The abandon unit | `seon.task/abandon!`, `my.root/abandon!` | Root writes `:cancelled` on tasks, and the reconciler stops, closes and ends what follows (§8) |
| 13 | The state shown on `/chains` (`:seon.chain/state`, the views) | `chain-view`, `chain-views`, `chain-state`, the HTML pairs, `seon.render.web/chain-fragment` | A task-graph view per branch, derived from desired and observed facts |

## 3. Desired and observed state

### 3.1 Desired-state facts (on main, except where noted)

| Fact | Writer | Meaning |
|---|---|---|
| task identity, `plan`, `instructions`, `proofs`, `model` | task writer (`seon.task/create!`, `my.task/update!`); ingestion for filed tasks | what the work is. Agents change the content members on their own branch, and the one merge carries the change. |
| `:seon.task/needs` | task writer | the prerequisites. Absent: none. |
| `:seon.task/branch` | task writer, resolved at the write (§4) | the branch the task's attempts check out |
| `:seon.task/target` | task writer (the merge template's builder) | the branch the task's done step writes. Absent: the done step writes only its own branch. Environment, not merge logic (§6). |
| `:seon.task/config` | task writer | the task's config decisions over main's (the old `:seon.chain/config` value) |
| `:seon.task/execution` | task writer (`:running` at create); root (`:held` at a bail, `:running` at resume, `:cancelled` at abandon, `:paused`) | whether it should run |
| config dials | `bin/seon config apply`, root | the cluster's desired config. `:seon.config.agent/max-running` bounds parallel attempts. |

### 3.2 Observed-state facts (each written only by its observer)

| Fact | Observer and writer | Where | When |
|---|---|---|---|
| the branch exists, its head, and how it was built (its base on main H, and its target: `seon.cluster.registry/fork!`'s `:seon.branch/target`, today always main) | Datahike and the registry (`registry/fork!`, P4a's prepare, every commit) | store | at the build and at each commit |
| unmerged entries on the branch (a conflict) | P4a's prepare (`seon.cluster.source/unmerged-tx`) | the branch | when the branch is built |
| `:seon.agent/graph-process` (new): the process whose registry runs this agent's graph | the routing registry | main | asserted when `start-flow!` has a started entry, retracted when `stop-flow!` observes every proc thread exited. Boot retracts every value naming a process other than its own: boot is the observer of the previous process's end. |
| `:seon.agent/branch` and `:seon.agent/environment` (new: the decided config's digest) | the agent's context acquisition | main | at each acquisition (§6.1) |
| the attempt row (`:seon.task/assignment`, its number, its agent) | root, when it starts the attempt | the task's branch | at the start |
| `:seon.task.attempt/end`, `/commit`, `/reason` | the agent's turn (`seon.turn/task-end`) | the task's branch | when the agent ends |
| `:seon.task/outcome :done` | root's done judgment (`seon.cluster.source/done-refusals` empty at the end commit) | the task's branch | after the end |
| `:seon.task/outcome :closed`, `:seon.task/reason` | root, at a bail or abandon | the task's branch | after the end |
| the merge (`:seon.task/outcome :merged`, `:seon.squash/source-commit`, `:seon.task/merged-declarations`) | the done step of a task whose branch targets main: P4a's accept, one transaction with the basis fence (P4b) | main | when main accepts |
| `:seon.db/transaction-refused-error` (main moved) | Datahike's writer | nothing written | the gap stays: rebuild the branch (§5, row 1b) |
| test results, keyed by reach digest | the runner | the branch tested | at each run |
| `:seon.task/git-commit`, `:seon.task/publication-refusal` | `seon.merge/publish!` | main | after publication |

**What changes about "running".** Today `:seon.flow.actions/started` is read from the in-memory registry each pass
(`registry-facts`). `:seon.agent/graph-process` makes it a fact. This is AGENTS.md's "admission and retirement are
writer-checked facts" for a live host object.

The registry still holds the graph object and the in-flight transitions of the reactive plan (acquire, start, stop,
merge-main). A crash empties the registry. Boot then retracts the stale `graph-process` values, so the next pass reads
"not running" from the data and never from silence.

## 4. Attributes

| Attribute | Status | Type | Replaces | Rule |
|---|---|---|---|---|
| `:seon.task/needs` | exists (13 tasks on main; `ready-rules`, `blocked`). Its word is retired in vocabulary.md. | set of task refs, `:seon.wake/starts` | `:seon.task/next` (cardinality one, forward), `:seon.chain/first` | The one dependency edge, written on the dependent: a new task names what it waits for and changes no existing task (accretion). Fan-in and fan-out both work. vocabulary.md un-retires it, grounded in GitHub Actions `jobs.<id>.needs`. |
| `:seon.task/branch` | exists (optional; a merge step names its merge branch) | branch keyword, **required after the writer** | `:seon.chain/branch` | Resolved once, at the write, over the vector written (resolve a name when it is written): a value given wins. A task with no target that needs exactly one task of the same write, and is that task's only dependent in the write, shares its branch (a linear run). Every other task, every task with a target included, gets a new branch `task-<handle>`, built at readiness by §5's rule. |
| `:seon.task/target` | new (git's word: a merge request's target branch) | branch keyword | `:seon.chain/merge?`, `chain-finishes`' "the last task merges", the merge step's append | The branch the task's done step writes, and that its own branch is built against. Only the merge template's builder sets it, to main's branch. Why a datum is needed at all is in §6. |
| `:seon.task/config` | new as a stored attribute (today the name of the runnable view's decided key) | `[:map-of :qualified-keyword :seon.schema/value]` | `:seon.chain/config` | The task's decisions. The runnable view's decided value keeps its schema name `:seon.task/desired-config` as its key. The turn cap is a dial in it: no separate attribute. |
| `:seon.task/model` | exists | | | unchanged. `:outside` is how root repairs at the REPL (§6). |
| `:seon.task/execution` | exists | enum | `:seon.chain/state` (derived), `my.root/run!` as a starter | `create!` writes `:running` by default (the owner's "everything autoruns"). Ingestion of filed tasks keeps `:held` (open question 2). |
| `:seon.agent/graph-process` | new | ref to `:seon.db.process/process` | `:seon.flow.actions/started` read from the registry | §3.2 |
| `:seon.agent/environment` | new | digest of the decided config the agent's turn loop read | — | observed environment, closes gap row 13 |
| `:seon.task/next` | **deleted** | | | replaced by `needs` |
| `:seon.chain/*` (13 keys and the views) | **deleted** | | | §2 |

Words recorded in `docs/seon/architecture/vocabulary.md` before code uses them (T0):
- **desired state** and **observed state** (Kubernetes spec and status);
- **reconcile** (`seon.cluster.agent/reconcile`);
- **needs** (un-retired; GitHub Actions);
- **target** (git's target branch, as a task's environment member);
- **environment**: a task's branch, target and config;
- **graph process**;
- **idle boundary** (the point between two eval requests where a context is advanced in place, already AGENTS.md's phrase);
- **swap** (moving a running agent onto its desired environment at the idle boundary).

The vocabulary rows for "chain" and "chain fact" are retired. The word "chain" survives only as plain prose for "tasks
written together and linked".

## 5. The reconciler

`seon.cluster.agent/observe` reads desired and observed state into one value:
- main's tasks and their needs (one query, measured 1.2–1.5 ms on default over 962 tasks: `seon.task/ready` 1.33 ms
  warm, the needs query 1.16 ms);
- the attempt rows of each branch with a ready task or an open attempt, its head read once with
  `seon.db/branch-database` and released (0.45–0.7 ms per branch, TASK-CHAIN §2.2's measure);
- the graph processes on main, and the store's branches.

`seon.cluster.agent/reconcile` is `[:=> [:cat :seon.flow.actions/observed :seon.config.agent/max-running]
:seon.flow.actions/actions]`. It is pure. `apply-actions!` hands each action to its actor. No pass re-derives inside
itself: an action's closing commit wakes the next pass through the route.

**Readiness.** T is ready when all of these hold:
- `execution(T) = :running`;
- T is not done (no latest attempt with outcome `:done` or `:merged`);
- no attempt of T is open;
- every N in `needs(T)` is **done where N's attempts live**:
  - N's branch head for a task with no target;
  - main for a task whose done step wrote main, because its accept transaction carries N's attempt;
- no other task on `branch(T)` has an open attempt (one checkout per branch).

Ready tasks start oldest first, up to `max-running` minus the open attempts.

The ruling's "and its own proofs are not yet green" is honored by the start step (row 3), not by a precondition. The
proofs' state exists only on the task's built branch (do the work when its data exists). A task whose proofs are green
there is done at once, with no graph and no model call (row 3a). So a ready task never gets an agent while its proofs
are green, and the reconciler never computes a test result.

**The environment build rule** (one rule, P4a's prepare generalized from one source to the needs):
- T's branch exists: continue it. This is a linear run sharing a branch.
- T's branch is absent: build it from H = the head of T's target, else main's head. The new branch holds H plus, for
  each N in `needs(T)` on another branch, N's branch change since its merge base with H:
  - `seon.program/own-changed-identities` and `seon.program/three-way`;
  - unmerged entries for conflicts (`seon.cluster.source/unmerged-tx`);
  - the facts squash (`seon.merge/merge-tx`).

  With no such need it is a plain fork of H. The registry records the built branch's base H and its target.

Two consequences:
- A merge task's branch is exactly P4a's merge branch S: main's head plus its predecessor's change.
- A task needing two parallel unmerged branches starts on main's head plus both changes. That is git's merge of both
  into a new branch from main. Conflicts are unmerged entries its agent sees.

No separate fork-point attribute is needed.

**Gap table.** Each row is: desired, observed, the gap, the action, its actor, and the fact that closes the gap.

| # | Gap | Action | Actor | Closing observation |
|---|---|---|---|---|
| 1 | T ready, `branch(T)` absent | build the environment by the rule above | `registry/fork!` + one transaction (P4a's prepare, generalized) | the branch, its base H and target recorded; unmerged entries if any |
| 1b | T has a target, is not done, and its branch's base H is no longer the target's head | no open attempt: rebuild (retire the stale branch, build again from the new head); an open attempt: merge main forward into its branch (`seon.merge/merge-main!`, R-P3), conflicts as unmerged entries the agent's next block shows | as row 1; root | a branch based on the current head |
| 2 | T ready, branch config differs from `desired-config(T)` | converge config (`seon.task/converge-config!`) | root, one transaction with its basis | the config row |
| 3 | T ready, environment built, no open attempt | **start step**: write the attempt row; acquire a context on the branch; run the step's stale members by reach digest (`seon.test/run`); judge `done-refusals` over `seon.context/judged-input` | root (no graph, no model) | the attempt row and the step's recorded results |
| 3a | the start step judged empty | the **done step** (row 7's write) at once: no graph starts, no model is called | root | outcome `:done`, or main written |
| 3b | the start step left work (a red, an unmerged entry, a contract gap) | start the graph: the agent works with T's instructions | registry | `graph-process` asserted |
| 5 | open attempt, no end, no `graph-process`, and work left | acquire the context, start the graph (after a crash, the same) | registry | `graph-process` asserted |
| 6 | `graph-process` present and (attempt ended, or execution not `:running`) | stop the graph and join it; release the context | registry | `graph-process` retracted |
| 7 | attempt ended `:done`, no outcome, no graph | judge `done-refusals` at the end commit. Empty: the **done step**. Refused: row 8. | root | the done step's write, or row 8's facts |
| 8 | attempt ended any other way (or refused at 7), no outcome, no graph | **bail**: a problem task on main (identity from the attempt), `execution :held` on main, outcome `:closed` with its reason on the branch | root | `:held`, and `:closed` |
| 9 | merged, `git-commit` and `publication-refusal` absent, declarations moved | `seon.merge/publish!` | root | `git-commit` or a refusal |
| 10 | a branch none of whose tasks is runnable or open, its change on main or never meant to merge | end it (`seon.task/end-branch!`): kept or deleted by `keeps-ended-branches?` | root | the branch gone, or kept and named by main's rows |
| 11 | execution `:cancelled`, an open attempt | after row 6: outcome `:closed` "cancelled" | root | `:closed` |
| 12 | a branch with a ready task or an open attempt, no route | listen | registry | route held (in memory, idempotent by key) |
| 13 | `graph-process` present, and the task's environment as its checkout holds it differs from the agent row's observed one (`:seon.agent/branch`, `:seon.agent/environment`) | swap at the next idle boundary (§6.1): config or model by row 2 and the next turn; a branch by the next acquisition on the new branch, same agent and attempt | root, then the agent's next eval request | the agent row's branch and environment digest |

**The done step** (rows 3a and 7) is one function of the environment:
- When the task's branch was built against a target (the registry's recorded target, observed), it is P4a's accept:
  one transaction on the target with parents #{the branch's source commits, the branch's judged commit} under
  Datahike's basis fence on H, writing the attempt `:merged` and the carried tasks.
- Otherwise it writes outcome `:done` on the task's own branch.
- Main moved (`:seon.db/transaction-refused-error`): nothing is written, and row 1b rebuilds the environment. This is a
  new environment, not a retry of failed work.

**No automatic re-runs.** Row 8 sets the desired state to `:held`, and readiness requires `:running`, so nothing in the
table restarts a bailed task. Only root's write of `:running` does. Row 1b rebuilds an environment that went stale. It
never reruns an attempt that ended.

**Idempotence.** Every action writes the fact its gap is absent from:
- the branch and its base;
- the attempt row and its step results;
- `graph-process`;
- the outcome, or the accept.

A second reconcile over the same state therefore plans nothing.

**Today's pieces and what they shrink to** (function spans on HEAD 2ee1db4a5):

| Today | Lines | After |
|---|---|---|
| `launch-facts` 36, `chain-read` 50, `chain-facts` 26, `chain-ends` 28, `chain-finishes` 35, `chain-bails` 26, `lifecycle-facts` 58, `taskless-agents` 27, `branch-admission-end` 16, `basis-valid?` 18, `registry-facts` 10, `seon.task/ready` + `ready-rules` + `done?` + `execution` 62 | 392 | `observe` ≈ 70 |
| `launch-actions` 45, `follow` 17, `assignment-actions` 37, `taskless-actions` 11, `next-actions` 11, `unfinished-intent` 12, `publication-due?` 10 | 143 | `reconcile` ≈ 90 (the gap table; the taskless-agent rule kept as two rows) |
| `advance-chains!` 25, `merge-chains!` 56, `finish-chains!` 37, `bail-chains!` 10, `bail-chain!` 30, `done-judged` 16, `close!` 22, `apply-lifecycle!` 33, `fork-attempt!` 14, `seed-attempt!` 18, `start-attempt!` 14, `write-attempt!` 16, `write-chain-attempt!` 22, `start-chain-attempt!` 29, `failed-pass!` 27, `merge-step` 18, `refusal-section` 19 | 406 | appliers ≈ 160: build (1, 1b) 30, start step (3) 35, done step (3a, 7) 30, bail (8) 25, publish and end (9, 10) 25, close (11) 15 |
| `run-pass!` 50, its thrice re-derivation of the lifecycle included | 50 | ≈ 15: observe, reconcile, apply |
| **total** | **991** | **≈ 335**: about 655 lines go from `seon.cluster.agent` |

`merge-step` and `refusal-section` go: there is no append. The merge task's start step shows its refusal (red members,
unmerged entries, lint) in its block as any task's step does. `route-chain!` is renamed to the branch route.
`agents-to-start` stays (root and branch agents with work, not tasks).

### What disappears, by the owner's list

- **The chain entity and its state.** The schema `seon.chain.edn` (13 keys and three views) and §9's functions.
- **`run!` as a starter.** Writing a task with `:running` is the start. `my.root/run!` survives only as resume after
  a bail: one write of `:running` on main, no branch transaction (open question 4).
- **The per-assignment path.** Attempt rows on main, `attempt-<key>` branches, `fork-attempt!`, `seed-attempt!` (its
  per-attempt merge-base write), `seon.task/ready` and `ready-rules`, `basis-valid?` and its commit-keyed cache, the
  `:invalid` launch problem, and `lifecycle-facts`' main-or-branch end lookup.
- **Relaunch.** Already deleted (63aac4169). `blocking-ends` and `leaf-done`'s `:sent-back` clause, which read
  history to keep a task from relaunching, go with `ready`.
- **The merge as a separate mechanism.** `merge-chains!`, the merge-step append (`merge-step`, `next-tx`,
  `next-refusal`), and `seon.merge/merge!`'s assignment-key entrance. A merge is a task, and the done step reads its
  environment.
- **Code that keeps two copies in sync:**
  - `seon.task/attempt-view`'s main row "overlaid by the branch's";
  - `seon.merge/chain-row`, which scans every chain branch for an attempt key; the task's `:seon.task/branch` names
    where to read;
  - `merge-tx`'s forcing of `:running` tasks to `:stopped` on main. Control members are written only on main and
    never on a branch, so a three-way merge of facts keeps main's value with no rule;
  - `close!`'s copy of a branch's end facts onto main.
- **Pass logic that remembers history:**
  - `run-pass!`'s "derive again after a stop, again after a merge";
  - `basis-valid?`'s commit-keyed cache;
  - `blocked`, which negates over recursive needs of children;
  - `chain-finishes`' "main does not hold the attempt yet" check. The accept on main is the observation.

## 6. Environment, merge and done

### 6.1 Environment is desired state, swapped at the idle boundary

A task's **environment** is its branch, its target and its config: `:seon.task/branch`, `:seon.task/target`,
`:seon.task/config` (every dial the launch and the turn loop read, the turn cap and the provider dials included) and
`:seon.task/model`. It is desired state on the task entity. Root writes it on main. An agent writes its own task's
`config` and `model` through `my.task/update!` (which T4 opens to those two members) on the branch it checks out.

**Where it is read.** A task's environment is read from the database its attempt checks out: main's task before an
attempt, and the task as its branch holds it while an attempt is open. That is the agent's checkout, as a git
working tree reads its own config. Root changing a running task writes the task on that branch; root may write any
branch. One merge later carries the change to main's task. Nothing syncs the two.

**The graph's actual environment is observed state**, recorded on the agent row when its context is acquired:
- the branch its context reads (`:seon.agent/branch`, exists);
- the config digest its turn loop read (`:seon.agent/environment`, new: `seon.config/effective`'s decided map, by
  digest).

**The swap** (gap row 13): desired ≠ observed while `graph-process` is present → swap at the agent's **next idle
boundary**. That is the point between two eval requests where AGENTS.md already advances an SCI context in place:
"each eval request reacquires from the branch head" (`seon.sci.eval`'s acquisition, `advance-context-projection!`).
- **A config or model difference:** row 2 converges the branch's config row, and the next turn reads it. No stop.
  The agent row's digest closes the gap.
- **A branch difference:** the in-flight eval request finishes on the old branch. The next acquisition reads the new
  branch's head and advances the same SCI context in place onto that program, so private defs survive as at any
  advance. The branch-bound pieces (the connection, the route) are reacquired, under the same agent id and the same
  open attempt. The agent row's `:seon.agent/branch` closes the gap.
  - Risk to prove in T4: that the in-place advance holds across branches as it does across commits of one branch.
  - If it does not, the fallback is rows 6 then 5: stop with `finish?` and start on the new branch. The attempt stays
    open, and private SCI state is lost.

One mechanism covers:
- root editing a held task's config: no graph, so the next start reads it;
- root or the agent editing a running task's config or model: converge, then the next turn;
- root moving a running task to another branch;
- a merge task whose main moved: row 1b merges main forward into its branch (`seon.merge/merge-main!`, the reactive
  plan's R-P3). The agent's next eval request reacquires the new head. Conflicts arrive as unmerged entries in its
  block (§6.3).

There are no special paths.

### 6.2 The merge task reuses P4a

**A merge is an ordinary task.** `seon.task/from-filed` and any builder write `[design prove implement merge]` at
once, linked by `needs`. The first three share one branch b. The merge task, from `seon.step.merge/template`,
carries:
- the template's instructions, model and config;
- `:seon.task/target`, main's branch, set by the builder, which holds main's database.

There is no merge attribute, no merge function and no chain entity. A bench run writes no merge task.

**Its environment is P4a's merge branch S** (gap row 1, built when the task is ready). S holds:
- main's head H;
- whatever of b's change since the merge base merged cleanly;
- for each declaration both sides changed, main's version on S plus an unmerged entry recording base, ours and
  theirs (main wins every conflict, MERGE-STEP 6);
- the facts squash.

**What in data makes a done step write main.** The environment: S was built against a target. The desire is
`:seon.task/target`. The observation is the target and base H the registry recorded when it built S. The done step
reads the observation.

A datum is truly needed, and here is why. The build rule makes "the next step on a fresh branch carrying its need's
change" and "a merge branch" the same construction. The two differ only in where the done step writes. That is what
git's merge request names: a source (here, derived from `needs`) and a target. No other existing fact carries it:
- the template's identity would be a hand rule;
- "the last task writes main" would make every single task and every bench run merge.

`:seon.task/target` is an environment member, like the branch. The merge itself is P4a's accept, run by the one done
step.

**Its proofs are derived, never hand-listed.** The task's start step and every later step judge S with
`done-refusals` over `seon.context/judged-input` on S, exactly as P4a judges S:
- its members are `seon.cluster.source/merge-members` of S's change since H: the tests reaching it, the tests it
  defines, and the proofs of the tasks whose change it carries (the needs closure on b, read from the facts the build
  carried);
- it has no next proofs, so the last-task coverage clause applies.

`:seon.task/proofs` stays absent on the template. T3b amends §2.4 so that for a task with a target, "neither proofs
nor next proofs never finishes" reads the derived members.

**The run, by the existing rule** ("all green at the start ends the task with no model call", TASK-CHAIN §2.3):
- **No conflict, every member green** (each looked up by reach digest; only digests new on S run): the start step
  judges empty. The done step accepts onto main at once in ONE fenced transaction. **Zero model calls, no graph,
  main written once.**
- **A conflict or a red member:** the start step leaves work, and the graph starts. The merge agent works on S with
  main's versions in place and its conflicts section in the block (§6.3). It either fixes the code there, or, when
  too much has changed, calls `my.task/send-back!` with its reason. That bail tells root to restart the process: root
  writes a new set of tasks, or resumes the work task.
- **Main moved:** before the start step, row 1b rebuilds S from the new head. While an agent works, row 1b merges main
  forward into S, and the agent's next eval request sees it. Unchanged reach is a lookup.

**What of P4a is reused, and how** (`seon.cluster.source`, `seon.merge` on HEAD 2ee1db4a5):

| Function | Role in the merge task | Status |
|---|---|---|
| `seon.cluster.source/merge-base`, `merge-base-refusals`, `merge-base-database`, `branch-merge-base` | O for the build | unchanged |
| `compare-branch`, `compare-commit`, `seon.program/three-way`, `seon.program/own-changed-identities` | Δ and its conflicts for the build | unchanged |
| `unmerged-tx`, `entry-identity`, `unmerged`, `with-unmerged`, `unmerged-sets`, `unmerged-count`, `unmerged-refusal` | the conflicts on S, the done rule's refusal | unchanged |
| `versions` | each entry's base, ours and theirs as resolved code references | unchanged; made public for §6.3 |
| `replacement` | Δ's replacement transaction on S | unchanged |
| `merge-members`, its fold, `contract-gaps`, `uncovered-functions`, `publication`, `lint-refusal`, `done-refusals` | S's members and the one judgment (start step, row 7) | unchanged |
| `prepare-merge!` | split along what it already does: the build (fork H, one write of Δ + unmerged entries + the facts squash) becomes row 1's applier; its judgment (`judged-merge!`) is the start step's ordinary `done-refusals` over `judged-input` on S | body kept, ≈ 40 lines move to the start step |
| `accept-merge!` | the done step for a task with a target: one `seon.db/transact!` with `:parents` and `:datahike/expected-basis-t` H | unchanged body; called with the task's S |
| `seon.merge/merge-tx`, `squash-merge`, `collision-remap`, `member-merge`, `cluster-entity?`, `attribute-tx` | the facts squash in the build | unchanged, minus the `:running` → `:stopped` rule (§5) |
| `seon.merge/merge-main!` | row 1b while the merge agent works | unchanged |
| `seon.merge/publish!`, `indexed!`, the write-back (`proven-files`, `write-back!`, `commit!`) | row 9 | unchanged (P7 makes one proof per content) |
| `my.program/resolve!` | the agent's (or root's) resolve verb | unchanged |
| `seon.merge/merge!`, `merge-onto`, `assignment-row`, `assigned-task`, `chain-row`, `acceptance` | the entrance by attempt key: the chain path and the per-assignment path | **deleted** in T3b; the reconciler calls the build, the start step and `accept-merge!` directly |
| `seon.cluster.agent/merge-chains!`, `advance-chains!`, `chain-finishes`, `chain-ends`, `merge-step`, `refusal-section`, `finish-chains!` | the chain merge path | **deleted** in T3b (§5) |
| `lifecycle-facts`, `assignment-actions`'s `:merge`, `apply-lifecycle!`'s `:merge`, `fork-attempt!`, `seed-attempt!`, `publication-due?` | the per-assignment merge path | **deleted** in T3b |

The merge logic itself does not change. What goes is every entrance to it but one, and every piece of state that
records where a merge stood: the merge step append, the chain fact, the assignment rows on main and the second
transaction (P4b).

**Root's repair at the REPL** (AGENTS.md "Merge is a root task": "conflicts stay unmerged on the merge branch, which
root repairs at the ordinary REPL holding both sides") is root working the merge task as an outside agent:
1. Root writes `:seon.task/model :outside` on the task. That is an environment change, so the swap applies.
2. Root enters through MCP `eval_clj` with the attempt's agent id on S. Ours is main's head, and theirs is b,
   readable by name.
3. Root resolves at the ordinary REPL.
4. The same done step accepts.

No second repair path.

### 6.3 Conflicts in the agent's block, live

**Today** (by reading HEAD 2ee1db4a5, not observed live):
- `seon.context/branch-changes` shows what will merge, with read and `ns-unmap!` forms.
- The step section shows red tests with their failures.
- Unmerged entries reach the block only indirectly. `seon.cluster.source/done-refusals` appends one
  `:seon.source/conflict-error` (`unmerged-refusal`) carrying `:seon.unmerged/entries`, each with its three
  `versions`. The step section shows that refusal (`seon.context/shown-refusal`) as one opaque error map, so:
  - it says nothing per entry about how to resolve it, only the message's prose;
  - it shows only when the step has judged;
  - `seon.context` itself never names `unmerged` or `conflict`.
- The merge template describes conflicts in prose.

So the owner's reading holds in substance: no section lets the agent see and act on each conflict.

**Change: a `:seon.context/conflicts` section in `seon.context/current-state`,** derived on every request from the
branch value the request reads. Nothing is stored or copied, so a resolved entry is gone from the next block. The step
section stops repeating the entries: `shown-refusal` drops `:seon.unmerged/entries` from the conflict refusal, as it
already drops `:seon.source/red-tests`, which the proofs show once. Each entry holds:
- `:seon.unmerged/name`, the declaration;
- `:seon.unmerged/versions`: ours (main's, on S) and theirs (the source branch's), and base when present. Each is a
  reference through the one resolver (`seon.ref/references`) at code level, so its source shows. This is exactly
  `seon.cluster.source/versions`, made public;
- `:seon.context/resolve`, the forms that resolve it, as data, never run by the render:
  - `(my.program/resolve! 'ns/name)` keeps ours;
  - an `in-ns` + `defn` of theirs or a combination resolves by writing the declaration.

Cost, proportional to the number of conflicts (ruling: "computed at most once per turn"):
- `unmerged` is one `:aevt` scan of `:seon.unmerged/id` plus `held-digests` over the entries. An ordinary branch has
  no entries, so the work is zero.
- `versions` reads three references per entry.
- The whole section is one `seon.cache` kind keyed by the branch value's commit id, so a turn's repeated block
  renders compute it once, and a resolving write (a new commit) recomputes it.
- Each version reference is also cached by content (`seon.render/render-call`).

**"What will merge" carries test status (P10).** P10 stays a phase, and it absorbs the conflicts section: both are
`seon.context` block sections derived from the same Δ and the same judged head entries on the same branch, in the same
file. P10 can run now (it needs only P4a, which is done) and need not wait for T3b. The conflicts section works on
today's MERGE-STEP merge branches too.

**The merge template's instructions point at the section.** `resources/seon/tasks/seon.step.merge.edn`'s Work section
becomes: "Your block's `:seon.context/conflicts` lists each declaration main and your source both changed, with both
versions and the forms that resolve it, and its step lists the red members. Resolve each; everything in
`:seon.context/changes` merges." The prose description of conflicts and red tests is deleted.

### 6.4 Done is automatic

- `done-refusals` empty means done (rows 3a and 7).
- A task with no target writes `:done` on its branch and nothing to main.
- There is no manual approval today, and none is added.

**Approval, if the owner wants it, is a task.**
- Its model is `:outside` (no model turns).
- Its proof is a deftest that reads a person-written approval fact on the supplied task (`seon.test/task`).
- The tasks after it list it in `needs`.

It adds no flag and no reconciler row; one attribute when first wanted (open question 7).

### 6.5 Dependents of parallel work

C needs A and B on separate branches.
- If A and B are followed by merge tasks MA and MB, C needs those. They are done on main, and C's branch (built from
  main's head) holds both.
- If C needs A and B directly, C's branch is built as main's head plus both unmerged changes (the build rule), with
  any conflict between them as unmerged entries in C's conflicts section. A merge task after C merges all three.

Either way main stays the one integration point, and every write to main is a judged done step under the basis fence.

## 7. Scheduling

The task graph is the scheduler:
- Writing tasks is the only start signal. `:seon.task/execution` and `:seon.task/needs` wake root.
- Readiness is §5's rule.
- Parallelism is "every ready task, one per branch, up to `max-running`".
- Dependency order is the needs graph.

Config before agent (the reactive start ruling of 2026-10-15) is rows 1 and 2 before row 3. Nothing is ordered by call
sequence.

## 8. Root's tools on task identity

| Tool | Becomes |
|---|---|
| `my.root/run!` | Resume `{:seon.task/assigned h}`: validate the runnable view, write `:running` on main. It no longer writes a branch transaction (today `seon.task/run-held!`, 29 lines, becomes about 8). It is not a starter. |
| `my.root/abandon!` | `{:seon.task/assigned h}` or `{:seon.task/branch b}`: write `:cancelled` on the named tasks and every task that needs them, transitively. The reconciler stops, closes and ends the branch (rows 6, 11, 10). 30 + 26 lines become about 20. |
| `my.root/merge-requests` | The tasks with a target, their latest attempts read on their branches |
| `ended`, `close!` | Read attempts through each task's `:seon.task/branch` |
| `merge-steps`, `chain-requests` | Deleted |
| `seon.task/chain!` | Deleted. `seon.task/create!` takes a vector of task maps and writes them on main in ONE transaction through the one task writer. It resolves each `:seon.task/branch` (§4), fills defaults, and validates each task's runnable view and that every needs edge resolves within the write or to an existing task. It is idempotent per task identity. No fork at the write: the build is gap row 1, when the task is ready, so a dependent builds after its needs are done. |
| `seon.task/from-filed` | Answers `[design prove implement merge]` with `needs` links, the entry's identity on the first map, and the merge task's target. |

## 8a. The error law, as touched

AGENTS.md's error law applies, ratcheted by P13's census (`seon.guard-census-test/a-changed-function-holds-no-guard`):
- Every function a T phase writes or keeps (`observe`, `reconcile`, the appliers, `create!`, the build, the conflicts
  section, the swap) follows the law:
  - a reader returns its declared variants;
  - an applier throws the one error map to the entrant boundary, here db<->flow's one catch, `failed-pass!`,
    rewritten to a bail (row 8);
  - nothing downstream re-checks what contracts or the writer guarantee.
- The deleted functions (§9) are deleted, never converted. Untouched code waits for its toucher.
- The reconciler adds no guard. A case it cannot plan is a desired state that does not validate, refused at the
  write by `:seon.task/runnable`. A failed effect is its actor's error, and the attempt it names bails.

## 9. Deletion table (function spans on HEAD 2ee1db4a5)

| Symbol | Lines | Phase | Replaced by |
|---|---|---|---|
| `seon.cluster.agent`: §5's 991 lines of pieces (`merge-step` and `refusal-section` included) | 991 → ≈ 335 | T3b | `observe`, `reconcile`, the appliers |
| `seon.task/chain!` 66, `chain-entities`, `chain-handles` 8, `chain-attempts` 31, `chain-branch` 14, `chain-branches` 8, `chain-refusal` 16, `hold!` 20, `end-chain!` 16, `abandon!` 26 | 221 | T3b | `create!`'s vector arity (≈ 25), `end-branch!` (exists), abandon as a desired write (≈ 12) |
| `seon.task/run-held!` 29 | 29 → 8 | T3b | resume |
| `seon.task/advance-tx` 24, `next-tx`, `next-refusal` | ≈ 45 | T3b | the flow at the start step (gap row 3); no merge-step append |
| `seon.task/ready-rules` 18, `ready` 22, `done?` 14, `execution` 8 | 62 | T3b | readiness inside `reconcile` |
| `seon.task/effective` 23, `goal-owner` 15, `children` | 38 | already TASK-CHAIN S3e; lands with T3b if still present | — |
| `seon.task` chains page: `chain-view` 27, `chain-views` 15, `chain-state` 12, `chain-html` 16, `chain-task-html` 18, `attempt-view` 7, `attempt-html` 27, `bailed?` 10, `finished?` 8 | 140 → ≈ 70 | T3c | one task-graph view grouped by branch |
| `seon.render.web/chain-fragment`, `chains-page-result`, `chain-database`, `chain-state-rank`, `agent-branch` and `missing-agent` chain reads | ≈ 74 → ≈ 45 | T3c | the task-graph page |
| `seon.merge/merge!`'s assignment entrance, `assigned-task`, `chain-row` 22, `assignment-row`'s chain fallback and first-parent walk | ≈ 60 | T3b | the done step reads the task's built branch (its base H and target, recorded by the registry); the facts' base is the branch's recorded base |
| `seon.merge/merge-tx`'s `:running` → `:stopped` rule | ≈ 8 | T3b | control members never written on a branch |
| `seon.cluster.registry/cluster-claims`' chain clause, `task-branch-claims` | ≈ 12 | T3c | the existing `:seon.task/branch` clause |
| `seon.mine/kept-branches`' chain query | 3 | T3c | `:seon.task/branch` rows |
| `seon.context/judged-input`'s four chain reads | ≈ 8 | T3b | a task's needs-dependents on its branch give `:seon.source/next-proofs`; a task with a target is judged as the last task |
| `my.root/run!` 20, `abandon!` 30, `chain-requests` 13, `merge-steps` 36, `merge-main!` 20 (chain reads) | 119 → ≈ 45 | T3b | §8 |
| `resources/seon/schemas/seon.chain.edn` (all), `:seon.task/chain-options`, `:seon.task/chain`, `:seon.task/chain-context`, `:seon.task/refusal` and `:seon.task/refused-tests` on tasks (the merge step's), `:seon.flow.actions/chains`, `/branches`, `/startable`, `/invalid`, the `:seon.chain/branch` members of `seon.flow.actions.edn` and `my.root.edn` | schema | T3c | `:seon.flow.actions/observed`; `:seon.task/runnable` reads `:seon.task/branch` and `:seon.task/target` |
| `:seon.task/next` (schema, 40 source and test files' uses) | — | T3b | `:seon.task/needs` (open question 1) |
| `docs/seon/schedule/cards/MERGE-STEP.md` S6 B–E | card | T0 | the merge task (§6) |
| The tests `seon.cluster.merge-test/a-requested-green-end-merges-and-publishes-with-no-hand-act` and `a-done-branch-merges-with-no-merge-request-written-by-the-agent`, and the per-assignment twin in `test/seon/cluster/db_flow_test.clj`. Their filed tasks: `seon.merge/a-requested-green-end-merges-and-publishes-is-red-on-head`, `seon.merge/a-done-branch-merges-with-no-merge-request-is-red-on-head`, `seon.cluster.agent/a-requested-green-end-merges-and-publishes-with-no-hand-act`. | tests | T3b | One test of the one path, `seon.cluster.db-flow-test/a-task-and-its-merge-task-end-merged-and-published-with-no-hand-act`. It carries their assertions: main holds the definition by digest; `:merged-declarations` 1; one git commit whose parent is the old HEAD. They tested the per-assignment path, which is deleted, and were never green. They are retired with it, not repaired. |

Totals: about **1,050 lines deleted** from `src`:

| Namespace | Lines deleted |
|---|---|
| `seon.cluster.agent` | ≈ 655 |
| `seon.task` | ≈ 330 |
| `my.root` | ≈ 75 |
| `seon.merge` | ≈ 60 |
| `seon.render.web` | ≈ 30 |

About 270 lines are added (`observe`, `reconcile`, the build rule's generalization of prepare to several needs,
`create!`'s vector arity, the task-graph view). About 230 test lines use `:seon.chain` or `chain!` (16 files;
`db_flow_test` 89, `task_test` 46, `competency` 22) and convert in T3b–T3c.

## 10. Phases

Every phase amends TASK-CHAIN in place, with its red deftest (the task-chain design is locked), and boots HEAD after
it. T3b and T3c change schema resources and boot-critical code: they commit through `bin/lane commit --test` in one
restart batch.

| Phase | Files | Change | Red proof (red on HEAD, green after) |
|---|---|---|---|
| **T0** Words and the cards (docs, 20 min, after the owner answers §12) | `docs/seon/architecture/vocabulary.md`, `docs/seon/schedule/cards/TASK-CHAIN.md`, `docs/seon/schedule/cards/MERGE-STEP.md` | Record the words of §4. TASK-CHAIN §1 and §2 rewritten in place to this model; §6 and §7 get these phases. MERGE-STEP retired into §6. | none (docs). Each later phase's proof is the card's. |
| **T1** The writer (45 min, Sonnet) | `src/seon/task.clj` (`create!`), `resources/seon/schemas/seon.task.edn`, `test/seon/task_test.clj` | `create!` over a vector: one transaction on main, branches resolved, `:seon.task/target` and `:seon.task/config` stored, the runnable view and the needs edges validated. Not yet called by the launch: additive. | `seon.task-test/a-task-write-resolves-every-tasks-branch`: in one write of `[a b c d m]` (b needs a; c and d need b; m, with a target, needs c and d), a and b share a branch, and c, d and m each get their own; a given branch wins; a repeat write commits nothing. `seon.task-test/a-filed-task-becomes-four-linked-tasks-ending-in-a-merge-task` (`from-filed`: the last map carries main's branch as its target and no proofs). |
| **T2** Graph process as a fact (45 min, Opus: boot-critical) | `src/seon/cluster/agent.clj` (`start-flow!`, `stop-flow!`), `src/seon/cluster.clj` (`recover-runs!`), `resources/seon/schemas/seon.agent.edn`, `test/seon/cluster/agent_test.clj` | The registry asserts and retracts `:seon.agent/graph-process`. Boot retracts values naming another process. | `seon.cluster.agent-test/a-started-graph-is-observed-until-it-exits`: after `start-flow!` main holds the agent's `graph-process` naming this process; after `stop-flow!` returns with every thread exited it is absent; a value naming another process id is absent after boot's recovery. |
| **T3a** The pure reconciler (60 min, Opus) | `src/seon/cluster/agent.clj` (`reconcile` new, not wired), `resources/seon/schemas/seon.flow.actions.edn`, `test/seon/cluster/agent_test.clj` | `reconcile` over hand-written observed values (data, no JVM effects). Additive. | One table deftest, `seon.cluster.agent-test/reconcile-closes-each-gap-once`. Its rows: **idempotence** (apply each answered action's closing fact, reconcile again → `[]`); **parallel start** (two ready tasks with no needs, `max-running` 4 → two builds and two start steps in one answer); **dependency wait** (C needs A and B: no action for C while either is not done where its attempts live; both done → C's build and start step); **done step on a target** (a start step judged empty on a branch built against main → one accept, no graph start); **work left** (a start step with an unmerged entry → one graph start); **stale environment** (a target task whose base is no longer main's head → one rebuild); **stop on undesired** (`graph-process` present, execution `:held` → `:stop`); **no re-run** (a bailed task `:held` with a `:closed` attempt → nothing); one branch, two ready tasks → one start. |
| **T3b** One path (120 min, Opus; restart batch; after P4b and P7) | `src/seon/cluster/agent.clj`, `src/seon/task.clj`, `src/seon/merge.clj`, `src/seon/cluster/source.clj` (prepare over several needs; the target judged as the last task), `src/seon/context.clj`, `src/my/root.clj`, `src/seon/step/prove.clj`, `src/my/program.clj` (its `next` reads), `resources/seon/tasks/seon.step.merge.edn`, the template task files, `test/seon/cluster/db_flow_test.clj`, `test/seon/cluster/merge_test.clj`, `test/seon/task_test.clj`, `test/my/root_test.clj`, `test/seon/context_test.clj`, `test/seon/merge_forward_test.clj`, `test/seon/cluster/merge_scenarios_test.clj` | `run-pass!` = observe, reconcile, apply. `chain!` → `create!`. `next` → `needs`. The build rule and the done step. The per-assignment path, the chain functions, `ready` and the merge-step append are deleted (§9). | `seon.cluster.db-flow-test/a-merge-task-with-no-conflict-is-done-at-start-with-no-model-call` (the merge task's attempt is `:merged` on main; main holds the change by digest; exactly one transaction was written on main for it; zero model calls; no `graph-process` was ever asserted for it); `seon.cluster.db-flow-test/a-conflicting-merge-task-starts-an-agent-on-its-merge-branch` (main and the work both changed one function: the merge branch holds main's version of it by digest and one unmerged entry naming it; exactly one graph starts there with the merge template's instructions; main is unchanged); `seon.cluster.db-flow-test/a-task-with-no-target-is-done-on-green-proofs-and-writes-nothing-to-main` (outcome `:done` on its branch; main gains no declaration and no merge commit); `seon.cluster.db-flow-test/two-independent-tasks-start-in-one-pass` (two tasks written in one `create!`; one pass: two attempts on two branches); `seon.cluster.db-flow-test/a-task-needing-two-parallel-tasks-starts-after-both-and-sees-their-work` (C's attempt exists only after A and B are done; C's branch head holds A's and B's definitions by digest); `seon.cluster.db-flow-test/a-task-and-its-merge-task-end-merged-and-published-with-no-hand-act` (replaces the two never-green tests). The existing `a-three-task-chain-ends-merged-with-no-hand-act` is rewritten over `needs` with a fourth, merge task. |
| **T3c** Views, claims, schema, kept history (60 min, Sonnet; same restart batch) | `src/seon/task.clj` (views), `src/seon/render/web.clj`, `src/seon/cluster/registry.clj`, `src/seon/mine.clj`, `resources/seon/schemas/seon.chain.edn` (deleted), `seon.flow.actions.edn`, `my.root.edn`, the norm file, `test/seon/render/web_test.clj`, `test/seon/cluster/registry_test.clj`, `test/seon/mine_test.clj`, `test/seon/competency*.clj` | The task-graph page; the claims clause; the schema deleted; the norm (open question 5). | `seon.cluster.registry-test/a-task-branch-survives-retirement` (renamed from `a-chain-fact-keeps-its-branch-and-base-through-retirement`: a branch named only by a held task's `:seon.task/branch` survives `retire-unclaimed!`); `seon.task-test/the-chain-norm-moves-each-chains-tasks-to-main` (a store holding a chain fact and its branch: after the norm main holds the chain's tasks with `:seon.task/branch` and `needs` from `next`, no `:seon.chain/*` datom, and `seon.mine/kept-branches` still lists the branch). |
| **T4** Environment swap (60 min, Opus; after T3b) | `src/seon/cluster/agent.clj` (the swap applier), `src/seon/sci/eval.clj` (only if the cross-branch advance needs it), `src/my/task.clj` (`update!` opens `config` and `model`), `resources/seon/schemas/seon.agent.edn`, `test/seon/cluster/db_flow_test.clj` | Gap row 13; the agent row records its observed environment at acquisition. | `seon.cluster.db-flow-test/an-environment-change-on-a-running-task-swaps-its-agents-branch-at-the-next-idle-boundary`: a running task's agent defines a private `def` on branch b; root writes the task's branch b2; the eval request in flight records on b; the next records on b2 under the same agent id and the same open attempt, the private `def` still resolves, and the agent row names b2. A second row: a config change (the turn cap) is read by the next turn with no graph stop. |

Census: P13's `seon.guard-census-test/a-changed-function-holds-no-guard` applies to every T phase's kept code.

### Kept chain branches: a norm, not a reset

The four live chains hold their tasks and attempts on their branches, not main. Without a conversion, retracting the
chain facts leaves those branches unclaimed. Boot's `retire-unclaimed!` would delete them, and the miner's corpus would
lose them.

The five kept branches `seon.mine/kept-branches` lists are already named by main's attempt rows (`:seon.task/branch`),
so they need nothing.

A norm of about 30 lines writes each chain's tasks onto main in the target shape:
- `:seon.task/branch` the chain branch;
- `needs` reversed from `next`;
- execution as the branch holds it;
- then it retracts the chain fact.

It writes no merge task. Root appends one when it wants a converted chain merged. That is cheaper than deciding per
branch, and it is the shape every later task has. Default can still be reset. The norm exists only so that the corpus
survives a non-reset restart (open question 5).

## 11. One ordered list: merge-hybrid's remaining phases and the task-model phases

Each remaining phase of `merge-hybrid.md` maps to one disposition:
- **kept** as is;
- **changed** (kept, with a different scope stated here);
- **absorbed** into a task-model phase;
- **moot**.

The status line of each plan stays in its own file. Root works from this list.

| # | Phase | Disposition | Why, and what changes | Files that order it |
|---|---|---|---|---|
| 1 | **P4b** one transaction | kept | The accept writes main once and `:seon.task/merged-declarations` itself; `:seon.task/merge-commit` is derived. That is the done step's write. Its conversions of `publication-due?` and `lifecycle-facts` are short-lived (T3b deletes both), but they keep HEAD booting until then. | `merge.clj`, `task.clj`, `agent.clj` |
| 2 | **P10** what will merge | **changed** | Absorbs §6.3: the live conflicts section derived from the stored unmerged entries, cached per branch value; `shown-refusal` stops repeating entries; the merge template points at the section. Its own proof stays, plus `seon.context-test/a-merge-branchs-unmerged-entry-shows-in-the-block-until-resolved`. Moved earlier: agents need it for today's merge steps. | `context.clj`, `seon.step.merge.edn`, `context_test.clj` |
| 3 | **T0** words and cards | task model | after the owner answers §12 | docs |
| 4 | **P5** red is red | kept | Required by the merge task: "green at start" must mean every member green on S, with no red-at-base excuse. | `source.clj`, `context.clj`, `turn.clj`, `test.clj` |
| 5 | **P6** one test request per step | **changed** | Its one request is also the start step (row 3): `test-step` with no evaluated form, run by root before any graph. T3b calls it from the reconciler's applier. | `turn.clj` |
| 6 | **T1** the writer | task model | beside P5 and P6 (`task.clj`, task schema) | `task.clj` |
| 7 | **T2** graph process as a fact | task model | beside (`start-flow!`, `stop-flow!`, boot recovery) | `agent.clj` (flow functions only), `cluster.clj` |
| 8 | **T3a** the pure reconciler | task model | additive, beside | `agent.clj` (new function), `agent_test.clj` |
| 9 | **P7** one file proof, consumed thrice | kept | Before T3b, which moves `prepare-merge!`'s build and calls `accept-merge!` directly (`source.clj`, `merge.clj` collide). | `source.clj`, `merge.clj` |
| 10 | **T3b** one path | task model; **absorbs** TASK-CHAIN R-P3–R-P6, S3c-2 (with T2), S3e's `children` deletion, and P4a's `seon.merge/merge!` entrance | restart batch with T3c | §10 |
| 11 | **T3c** views, claims, schema, norm | task model | same restart batch | §10 |
| 12 | **T4** environment swap | task model | after T3b | `agent.clj`, `my/task.clj`, maybe `sci/eval.clj` |
| 13 | **P8** no automatic JVM boots | **changed** | A host-bound change or a member the one JVM must not run is work left at the start step, or a done-rule refusal at row 7. The task bails to root by row 8. There is no `:seon.source/deferred` branch in any path. After T3c, because `agent.clj` collides. | `turn.clj`, `cluster.clj`, `source.clj`, `context.clj`, `agent.clj`, `test.clj`, `test/platform.clj` |
| 14 | **P9a** convert the callers of the retired verbs | kept | | `bin/seon-hook`, tests, scripts |
| 15 | **P9b** one verb, help, skill, offers | kept | help also gains §6.3's section and §6.1's swap in one sentence each | `my/test.clj`, help, skill |
| 16 | **P11a–d** runner | kept | independent of the task model | `seon.test`, `seon.test.runner` |
| 17 | **P12** `merge-changes`, `agent-owned` | kept | after its producer task, independent | `source.clj`, `context.clj` |
| — | **P13** the error law per phase | kept, and applied to every T phase (§8a) | the census already landed with P4a | every phase's files |
| — | **MERGE-STEP S6 B–E** | **moot** | The merge task is written with the work, and its conflict case is its own agent (§6.2). There is no append. | — |
| — | merge-hybrid §3.1 rows naming `seon.cluster.agent/merge-chains!`, `refusals-verdict`, `merge-step` as "stays" | **moot** | deleted by T3b (§5, §9) | — |
| — | the merge test list that "stays green through all phases" | **changed** | Their chain fixtures convert to `create!` with a merge task in T3b. `a-refused-final-merge-keeps-its-merge-branch-and-holds-a-merge-step`, `a-done-merge-step-merges-its-branch-onto-main` and `a-merge-step-green-at-its-start-ends-done-with-no-model-call` become T3b's two merge-task proofs. `a-requested-green-end-merges-and-publishes-with-no-hand-act` is retired (§9). | tests |

Parallel lanes, by file collisions:
- P10 runs now.
- T1, T2 and T3a run beside P5 and P6. `context.clj` belongs to P10 then P5. `turn.clj` belongs to P6.
- P7 runs before T3b.
- T3b and T3c land in one restart batch, then T4, then P8.
- P9a, P9b, P11 and P12 follow as `merge-hybrid.md` orders them.

## 12. Open questions for the owner

1. **The edge.** The owner said "stick with the next connecting it". `next` (one successor, written on the
   predecessor) expresses a line. It cannot express "this task depends on these two parallel tasks", which the owner
   also asked for.
   - Recommended: `:seon.task/needs` (a set of prerequisites, written on the dependent: GitHub Actions' word), with
     `next` deleted. The writer still accepts a vector and links it in order, so writing a line is as easy as today.
   - Alternative: keep `next` for lines and add `needs` only for fan-in. That is two edges for one concept, which
     AGENTS.md's "one name per concept" refuses.
2. **Filed tasks autorun?** `create!` writes `:running` (everything autoruns). The 961 filed backlog tasks ingest
   `:held`, so a restart does not launch the backlog. Recommended: keep ingestion `:held`. Root releases backlog tasks
   by writing `:running`, and `from-filed` writes the four running tasks.
3. **Tasks on main before their work merges.** The scheduler must see the whole graph in one database, so tasks live on
   main from their write. Their agents' content edits stay on the branch until the merge task carries them. This
   supersedes TASK-CHAIN's "main stays clean until the chain succeeds" for task entities, not for program entities.
   Accept?
4. **`:seon.task/target`.** The one environment datum §6 argues is needed, so that a merge task's done step writes main
   while an identically built work branch does not. Accept the git word, or name it otherwise?
5. **Kept chain branches.** Convert the four live chains with a norm (about 30 lines, recommended: the miner keeps its
   corpus), or reset default at the cutover and lose them?
6. **Abandon.** Cancel the named tasks and every task that needs them transitively (recommended: their needs can never
   be done), or only the named ones?
7. **Approval.** Wanted now? If so, it is one attribute (`:seon.task/approved-by`, written by a person through root)
   and one template with an `:outside` model and one proof. No reconciler change.
8. **Plan flow.** The plan flows along a same-branch need at the dependent's start step. A task on a built branch
   takes its needs' plans as the build carries their task facts; with several needs, its block cites each. Accept?
9. **Resume verb.** Keep `my.root/run!` as resume only (one write of `:running`), or have root write execution through
   the task writer with no verb? Recommended: keep the name as resume.
10. **Swap boundary.** Swap at the next idle boundary (between eval requests), keeping the agent id, the attempt and
   its SCI context advanced in place (recommended), or stop and restart the graph (loses private SCI state)? T4
   proves the first; if the cross-branch advance cannot hold, the second is the fallback.

## 13. Findings outside this lane

- **A contract refusal on a mistyped argument cost 852 ms and 1.36 GB.** On default, in JVM mode,
  `(seon.task/chain-views db (count ...))`, a wrong arity and type by this lane, refused at input. The explanation
  walked a Datahike database value field by field (`:eavt`, `:aevt`, …, 21 problems, each with a `HistoricalDB`
  example). Result `result/e5674c7754db6`, cost `result/cc944c2d9846f`.

  An input refusal should be proportional to the schema, not to the database. The producer is Malli's explain over a
  `:seon.db/database-value` that sits inside a map schema in `seon.task/chain-views`' contract. Root files it: this
  lane commits only this file.
- **Tests that never went green.** `seon.cluster.merge-test/a-requested-green-end-merges-and-publishes-with-no-hand-act`
  and `a-done-branch-merges-with-no-merge-request-written-by-the-agent` were never green. T3b retires them with the
  per-assignment path (§9) and does not repair them.

## Owner decisions (2026-10-17)

These answer §12, and they bind the narrow check and every T phase.

1. **`:seon.task/needs` replaces `:seon.task/next`.** It is a set of prerequisites, so a merge task needs both the coding task and the design task. The owner: "we can do more than one dependency (refs are sets right?) Yeah that does seem better."
2. **Nothing autoruns until the owner flips it on.** Filed and ingested tasks are `:held`. In the owner's words: "I want to keep testing agents until they can solve real problems before we just flip the system on."
   - The scheduler reads concurrency from config. `:seon.config.agent/max-running` is the cap, and the reconciler never starts more than that many tasks at once.
   - The configuration is cluster facts, sized for a 16 GB machine, and the scheduler is built on them.
3. **The environment swap is a clean restart, not an in-place advance.** The owner said a clean restart is more reliable even if it loses data. When the environment differs, stop the agent's graph and start a fresh one.
   - An agent is never started until its configuration, and every other fact it needs, is written on its task. Start is a reconciler action whose precondition is a complete desired environment; an incomplete one is a gap the reconciler reports, not a start.
   - T4 is rewritten this way, and the cross-branch in-place advance it depended on is dropped.
4. **No norm for the old chains.** Default may reset. The four kept chain branches are not converted; the miner (`seon.mine`) is updated to read the new task model and re-mines what exists. Delete the T3c norm.
5. **Root takes §12's recommendations** on the rest, unless the owner objects:
   - tasks live on main before their work merges;
   - the name `:seon.task/target`;
   - abandon cancels dependents;
   - approval waits until the owner wants it;
   - a plan flows along `needs`;
   - `my.root/run!` only resumes a held task.
