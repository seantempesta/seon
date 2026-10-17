---
title: "Task model: desired state, observed state, one reconciler"
type: plan
status: "design plan by lane task-model-plan (Opus), 2026-10-17, revised in place by lane task-model-rev (Sonnet) to fold in transitions.md's completed transition table, its 15 gaps and owner decisions 6 to 8, from the owner's directions of 2026-10-17 (tasks carry branch and environment; the task graph is the scheduler; desired vs observed state; merge is an ordinary task, no merge attribute), folding in every ruling of merge-rulings.md \"The task model (owner, 2026-10-17, later)\", which binds. The owner decisions of 2026-10-17 (last section before the narrow check) bind and are applied in place; the narrow check (lane task-model-check, last section) corrected the claims and phases it names. Read-only probes on default (JVM mode). Not yet amended into TASK-CHAIN: phase T0 does that."
---

# Task model: desired state, observed state, one reconciler (2026-10-17)

**Phase status** (each implementing commit updates this line): T0 done (TASK-CHAIN amended in place to the final plan and `task-t0-task-chain.patch` deleted, lane task-t0-finish) · T1 pending · T2 pending · T3a pending (the exhaustive transition table, §5) · T3a-agent pending ·
T3b pending · T3c pending (no norm; updates `seon.mine`) · T4 pending (clean restart) · P10 (merge-hybrid's phase, with
§6.3's conflicts section) done 8f39ed642

## 1. The design in a few lines

The owner asked what a chain was ever doing. It held a branch, a first task, a config and a merge decision for a group
of tasks. Each of those belongs on a task. Once they do, the chain has nothing left to hold.

1. **Desired state is task facts on main.** One task schema, nothing merge-specific. A task names:
   - its prerequisites, `:seon.task/needs`;
   - its environment: its branch, its target and its config and model;
   - whether it should run, `:seon.task/execution` (`:held`, `:running` or `:cancelled`). Every written task is `:held`: nothing autoruns until the owner
     flips it on, by root writing `:running` (`my.root/run!`).

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
4. **Scheduling and merge are not mechanisms of their own. They are rows of the reconciler's transition table (§5), checked for exhaustiveness.**
   - A task is ready when it is `:running`, every task it needs is done where that task's attempts live, and its latest attempt is absent or `:closed`.
   - The minimal action for a ready task is "build its environment, then start". The build rule is one rule, P4a's
     prepare generalized: a task continues its need's branch, or gets a new branch holding main's head plus the
     unmerged change of each need on another branch.
   - Ready tasks start in parallel, oldest first, while the open attempts are fewer than
     `:seon.config.agent/max-running` (the cluster's configuration, a dial sized for a 16 GB machine; shipped 4 and
     read at every pass), one running task per branch, as git checks a branch out in one worktree.
   - A task is ready only when it is `:running`. Written tasks are `:held`, so a write starts nothing; root releases a
     task by writing `:running`.
   - **A merge is an ordinary task** (`seon.step.merge/template`) after the work it merges. Its environment's branch
     targets main, so its done step writes main through P4a's accept. A task green at its start ends with no model
     call (the existing rule), so a merge with no conflict and green members is done with no agent. A conflict or a
     red leaves work, and an agent starts with the merge template's instructions. Its block lists each conflict with
     both versions and the form that resolves it, live (§6.3).
   - Environment is desired state on the task. An agent starts only once its task's desired environment is complete;
     a running agent whose environment differs is stopped and a fresh one started on the new environment (§6.1, a
     clean restart). One mechanism, no special paths.
5. **One path.** A single piece of work is a task with no needs, followed by a merge task when it should reach main.
   The chain fact and the per-assignment launch path (attempt rows on main, `attempt-<key>` branches,
   `seon.task/ready`) both go.

Grounding (AGENTS.md "Which proven system already solved this? Take its model whole"):
- **GitHub Actions jobs.** Jobs run in parallel unless they declare `needs:`, and each runs on its own runner
  (`runs-on`, its environment). A workflow is only the file that groups them: here it is one write of tasks.
- **Kubernetes controllers.** A spec is desired, a status is observed, and a reconcile loop does the minimum to close
  the difference. Seon's own `seon.reconcile` (config) already uses the word this way.

## 2. What a chain was doing, and where each job goes

Measured on HEAD 2ee1db4a5 (re-counted by the narrow check, `exact-token` over `src test script resources bin
.agents docs/seon`): 191 `:seon.chain/branch` uses, 35 `first`, 23 `config`, 15 `merge?`, 13 `attempts`, 12 `state`,
9 `tasks`, 7 `kind`, 10 `from` (282 `:seon.chain/` uses in all), in 13 source and resource files, 16 test files and
the docs and skills the phases name. Live on default: 4 chain facts (`chain-42e0e4c46b71`, `chain-91a8e6815ef2`,
`chain-ce9d664c1be0`, `chain-fd3ba8395135`), each holding only `branch` and `first` (no `merge?`, `config` or `from` is
stored on any); 15 store branches, 5 of them kept and listed by `seon.mine/kept-branches` (four named only by the chain
facts, one by main's one attempt row); 972 task entities on main, 962 of them with an execution (961 `:held`, 1
`:stopped`) and 13 with `:seon.task/needs` (14 links); one attempt row on main; 10 tasks with `:seon.task/children`.

| # | Job the chain does today | Readers (functions) | Where it goes |
|---|---|---|---|
| 1 | Discovery: root finds work through one fact on main that wakes it (`:seon.wake/starts`) | `chain-facts`, `chain-ends`, `chain-finishes`, `finish-chains!`, `route-chains!`, `my.root/chain-requests` | The tasks on main. `:seon.task/execution` and `:seon.task/needs` are already `:seon.wake/starts`. |
| 2 | Names the branch the work is on | 47 functions read `:seon.chain/branch` (table in §9) | `:seon.task/branch` on every task, resolved by the writer (§4) |
| 3 | Keeps the branch through boot's retirement | `seon.cluster.registry/cluster-claims` (its chain clause and `task-branch-claims`) | The clause `[_ :seon.task/branch ?b]`, which `cluster-claims` already has |
| 4 | Identity, and an idempotent create (the first task's handle) | `seon.task/chain!`, `chain-branch` | Each task's own identity (`seon.task/task-handle`). `seon.task/create-all!` is idempotent per task. |
| 5 | Order, along `next` from `first` | `chain-read`, `chain-handles`, `chain-ends`, `chain-finishes`, `chain-view` | The `needs` graph |
| 6 | Fork point (`:seon.chain/from`) | `chain!`, `bail-chain!` (its problem text) | Deleted as a separate meaning. To continue another branch's work, a task names that branch, or needs a task on it: the build rule then carries its change (§5). |
| 7 | Config overlay (`:seon.chain/config`) | `chain!`, `chain-refusal`, `config-tx`, `desired-config` | `:seon.task/config`, converged onto the branch when the task starts (§5, N4) |
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
| task identity, `plan`, `instructions`, `proofs`, `model` | task writer (`seon.task/create-all!`, `my.task/update!`); ingestion for filed tasks | what the work is. Agents change the content members on their own branch, and the one merge carries the change. |
| `:seon.task/needs` | task writer | the prerequisites. Absent: none. |
| `:seon.task/branch` | task writer, resolved at the write (§4) | the branch the task's attempts check out |
| `:seon.task/target` | task writer (the merge template's builder) | the branch the task's done step writes. Absent: the done step writes only its own branch. Environment, not merge logic (§6). |
| `:seon.task/config` | task writer | the task's config decisions over main's (the old `:seon.chain/config` value) |
| `:seon.task/execution` | task writer (`:held` at create and at ingestion); root (`:running` to release, `:held` at a bail, `:cancelled` at abandon) | whether it should run. Nothing autoruns. `:paused` and `:stopped` are folded into `:held` (owner decision 7). |
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
| `:seon.db/transaction-refused-error` (main moved) | Datahike's writer | nothing written | the gap stays: merge main forward (§5, A3, O4), or rebuild an idle branch (N3) |
| test results, keyed by reach digest | the runner | the branch tested | at each run |
| `:seon.task/git-commit`, `:seon.task/publication-refusal` | `seon.merge/publish!` | main | after publication |

**What changes about "running".** Today `:seon.flow.actions/started` is read from the in-memory registry each pass
(`registry-facts`). `:seon.agent/graph-process` makes it a fact. This is AGENTS.md's "admission and retirement are
writer-checked facts" for a live host object.

The registry still holds the graph object and the in-flight transitions of the reactive plan (acquire, start, stop,
merge-main). A crash empties the registry. Boot then retracts the stale `graph-process` values, so the next pass reads
"not running" from the data and never from silence.

**Where a task's progress lives** (owner decision 6). A task's progress, its done included, is observed state on the
task's own branch, because agents write only their branch. The reconciler reads desired state from main and observed
state from each task's branch (`seon.db/branch-database`, cached by commit, one read per task), so nothing is copied
to main to be read there. Branches are never deleted by default (`:seon.config.agent/keep-ended-branches?` ships true
and stays the default): an ended branch stays as history and as the record of its task. Compaction is an explicit act,
built when first wanted, and the dial's `false` arm is not the way to compact. Before the branch goes, it records on main what the reconciler still needs from it for that task (the
outcome its dependents' "done where its attempts live" reads), and then deletes. This resolves gap 11 (deleting a
branch erased a no-target task's only `:done`, so the task would run again) without moving data. Until compaction is
built, no branch is deleted and the state cannot arise; the compacted task's representation (a branch `:absent` with
an outcome) is a new constraint the compaction phase adds to §5.1's table with its row, not something T3a decides.

The bail is the one writer of two databases, and its order is fixed (§5.2): `:held` on main first, then `:closed` on the
branch.

## 4. Attributes

| Attribute | Status | Type | Replaces | Rule |
|---|---|---|---|---|
| `:seon.task/needs` | exists (13 tasks on main, `create!` refuses a cycle; `ready-rules`, `blocked`). TASK-CHAIN §1 lists it among the attributes it deletes, and S3e deletes it; T0 amends both. | set of task refs, `:seon.wake/starts` | `:seon.task/next` (cardinality one, forward), `:seon.chain/first` | The one dependency edge, written on the dependent: a new task names what it waits for and changes no existing task (accretion). Fan-in and fan-out both work. T0 un-retires it in TASK-CHAIN §1 and S3e and records it in vocabulary.md, grounded in GitHub Actions `jobs.<id>.needs`. |
| `:seon.task/branch` | exists (optional; a merge step names its merge branch) | branch keyword, **required after the writer** | `:seon.chain/branch` | Resolved once, at the write, over the vector written (resolve a name when it is written): a value given wins. A task with no target that needs exactly one task of the same write, and is that task's only dependent without a target in the write, shares its branch (a linear run). A task with a target never counts as a dependent, so a merge task that also needs the run's earlier tasks leaves the run on one branch. Every other task, every task with a target included, gets a new branch `task-<handle>`, built at readiness by §5's rule. The needs of a task with a target resolve to their branches and are de-duplicated before the build. |
| `:seon.task/target` | new (git's word: a merge request's target branch) | branch keyword | `:seon.chain/merge?`, `chain-finishes`' "the last task merges", the merge step's append | The branch the task's done step writes, and that its own branch is built against. Only the merge template's builder sets it, to main's branch. Why a datum is needed at all is in §6. |
| `:seon.task/config` | new as a stored attribute (today the name of the runnable view's decided key) | `[:map-of :qualified-keyword :seon.schema/value]` | `:seon.chain/config` | The task's decisions. The runnable view's decided value keeps its schema name `:seon.task/desired-config` as its key. The turn cap is a dial in it: no separate attribute. |
| `:seon.task/model` | exists | | | unchanged. `:outside` is how root repairs at the REPL (§6). |
| `:seon.task/execution` | exists | enum `:held :running :cancelled` after owner decision 7 (`:paused` and `:stopped` leave the enum in T3c, after T3b converts their readers) | `:seon.chain/state` (derived), `my.root/run!` as a starter | The writer (`create-all!`) and ingestion both write `:held` (owner decision 2). Root releases a task by writing `:running`; nothing else starts work. |
| `:seon.agent/graph-process` | new | ref to `:seon.db.process/process` | `:seon.flow.actions/started` read from the registry | §3.2 |
| `:seon.agent/environment` | new | digest of the decided config the agent's turn loop read | — | observed environment, closes the restart (G5, O3) |
| `:seon.task/next` | **deleted in T3c** (T3b converts every reader but the chains page's, which T3c deletes with the attribute) | | | replaced by `needs` |
| `:seon.chain/*` (13 keys and the views) | **deleted** | | | §2 |

Words recorded in `docs/seon/architecture/vocabulary.md` before code uses them (T0):
- **desired state** and **observed state** (Kubernetes spec and status);
- **reconcile** (`seon.cluster.agent/reconcile`);
- **needs** (un-retired; GitHub Actions);
- **target** (git's target branch, as a task's environment member);
- **environment**: a task's branch, target and config;
- **graph process**;
- **restart** (stopping an agent whose environment differs from its task's and starting a fresh one on the desired
  environment; the owner's clean restart);
- **release** (root writing `:running` on a `:held` task: the only start signal).

The vocabulary rows for "chain" and "chain fact" are retired. The word "chain" survives only as plain prose for "tasks
written together and linked".

## 5. The reconciler

`seon.cluster.agent/observe` reads desired and observed state into one value:
- main's tasks and their needs (one query, measured 1.2–1.5 ms on default over 962 tasks: `seon.task/ready` 1.33 ms
  warm, the needs query 1.16 ms);
- **each task's progress from the task's own branch** (owner decision 6): its attempt rows, their ends, the outcome and
  the step results, read once per task with `seon.db/branch-database` and cached by the branch's commit, so a branch
  that has not moved costs a lookup (0.45–0.7 ms per branch uncached, TASK-CHAIN §2.2's measure). Only a task whose
  branch exists is read; a held task with no branch costs nothing;
- the graph processes on main, and the store's branches.

`seon.cluster.agent/reconcile` is `[:=> [:cat :seon.flow.actions/observed :seon.config.agent/max-running]
:seon.flow.actions/actions]`. It is pure: the plan is data and each action's actor executes it (Terraform's plan and
apply). `apply-actions!` hands each action to its actor. No pass re-derives inside itself: an action's closing commit
wakes the next pass through the route. The pass is the one `db<->flow` already runs, and Flow stays the runtime
(`transitions-web.md` §7, `transitions.md` §7.1):

```
wake (seon.cluster.wake/route!) → observe → task-state per task → transitions → admit → actions → owners
```

### 5.1 The state of one task, as Malli enums

Desired and observed state per task, after `observe`, is one value of `:seon.flow.actions/task-state`: twelve
dimensions, each a registered Malli enum. Three reuse a schema that exists (`:seon.task/execution`,
`:seon.task/outcome`, `:seon.task.attempt/end`). The execution enum is `:held :running :cancelled` after owner decision
7 (below); `transitions.md` proved `:stopped` behaves exactly as `:held`, and `:paused` is folded by the owner.

| Dimension | Values | Source fact (§3) |
|---|---|---|
| `execution` | `:held :running :cancelled` | `:seon.task/execution` on main |
| `needs` | `:none :pending :done` | each need done where its attempts live (readiness below) |
| `target` | `:none :main` | `:seon.task/target` |
| `environment` | `:complete :incomplete` | `:seon.task/environment` validates the desired environment (§6.1) |
| `branch` | `:absent :current :stale` | the branch exists; its recorded base H is, or is not, the target's head |
| `config` | `:none :converged :differs` | the branch's config row against `:seon.task/desired-config` |
| `attempt` | `:none :unended` and the seven ends | the latest attempt row and `:seon.task.attempt/end`, on the task's branch |
| `outcome` | `:none :done :merged :closed` | `:seon.task/outcome` of the latest attempt, on the task's branch |
| `step` | `:none :unjudged :empty :work-left` | the latest `done-refusals` judgment at the head |
| `agent-env` | `:none :same :differs` | `:seon.agent/environment` and `:seon.agent/branch` against the desired environment |
| `graph` | `:absent :running` | `:seon.agent/graph-process` (T2) |
| `publication` | `:none :due :done` | `:seon.task/git-commit` or `publication-refusal` after `:merged` |

Rows may also read two values derived from these, never stored:
- `latest`: `:none :open :awaiting :closed :settled`. It folds `attempt` and `outcome` into the attempt's phase:
  `:awaiting` is ended with no outcome, `:settled` is `:done` or `:merged`.
- `wanted`: true when `execution` is `:running` and `needs` is not `:pending`.

Three things are deliberately not dimensions, because no decision depends on them:
- unmerged entries (`seon.cluster.source/done-refusals` includes `unmerged-refusal`, so they are `step :work-left`);
- proofs green, red or unknown (they are `step`);
- the cluster's intent, `:seon.flow.actions/cluster`: `launch-facts` hardcodes it to `:running`, a dead dial deleted in
  T3b/T3c (§9). On and off is Flow's pause of `db<->flow` (§7).

**The impossible combinations are data**, each with its reason: `{:id :why :when {dimension #{values}}}`, held in one
registered named predicate. `transitions.md` §1 names 17: 16 by construction and one invariant.
`config-without-branch` and `branch-without-config` (the branch holds the config row); `stale-without-target`;
`attempt-without-branch`; `outcome-without-attempt`, `step-without-attempt`, `attempt-without-step` (the start step
writes the attempt row, unjudged until it judges); `attempt-without-agent`, `graph-without-agent`,
`agent-without-attempt` (the start step acquires the context, which records its environment, before any graph);
`merged-without-target`, `done-with-target`; `settled-not-green`; `incomplete-but-same`;
`publication-without-merge`, `merge-without-publication-state`; and the invariant `graph-after-outcome` (every outcome
writer requires the graph absent: the closure check proves the table keeps it).

Counts, over the folded enums (re-run by this lane with `transitions.md`'s prototype, enums reduced, in a throwaway
JVM; the unfolded figures of `transitions.md` are 29,760 of 2,099,520):

| | States |
|---|---|
| Product of the twelve enums | 839,808 |
| Impossible by construction | 819,792 |
| Impossible by the invariant | 6,048 |
| Possible | 13,968 |

Enumeration backtracks over the dimensions and prunes a partial state as soon as a constraint whose dimensions are all
assigned matches it (54 ms).

### 5.2 The transition table

`seon.cluster.agent/transitions` is a vector of `{:row :when :then}` maps, a Var, so `source` shows it to agents.
`:when` maps a dimension (or `latest`, `wanted`) to a value or a set; `:then` is a vector of action ops, or `[]` with
`:steady "name"`; `:start? true` marks the start group. Rows are **disjoint**: no priority order, no `:else`, no default
row. A steady state is a row with no actions, so an unmatched state is a red test and never a silent no-op. This
replaces the old gap table row for row (the old numbers are in the last column).

| Row | When (besides the group's condition) | Then | Replaces |
|---|---|---|---|
| **A hosted graph** (only an open or awaiting attempt has one) | | | |
| G1 | graph running, latest `:awaiting` | `:stop` | 6 |
| G2 | graph running, open, execution `:held` or `:cancelled` | `:stop` | 6 |
| G5 | graph running, open, `:running`, agent-env `:differs` | `:stop` (restart, first half) | 13 |
| G6 | graph running, open, `:running`, agent-env `:same`, branch `:current` | steady "working" | — |
| G7 | graph running, open, `:running`, agent-env `:same`, branch `:stale` | `:merge-main` | 1b, open half |
| **No graph, an open attempt** | | | |
| O1 | execution `:cancelled` | `:close-cancelled` | 11 |
| O2 | execution `:held` | steady "held with its attempt open" | — |
| O3 | `:running`, agent-env `:differs` | `:close-environment` (restart, second half) | 13 |
| O4 | `:running`, agent-env `:none` or `:same`, branch `:stale` | `:merge-main` | 1b, open half |
| O5 | as O4, branch `:current`, step `:unjudged` | `:judge` | none (a crash between the attempt row and its judgment) |
| O6 | as O5, step `:empty` | `:done-step` | 3a |
| O7 | as O5, step `:work-left` | `:start-graph` | 3b and 5, one row |
| **No graph, the attempt ended, no outcome** | | | |
| A1 | execution `:cancelled` | `:close-cancelled` | none (a bail would write `:held` over `:cancelled`) |
| A2 | execution `:held` or `:running`, end other than `:done` | `:bail` | 8 |
| A3 | not cancelled, end `:done`, branch `:stale` | `:merge-main` | none (1b would rebuild over the work) |
| A4 | not cancelled, end `:done`, `:current`, step `:unjudged` | `:judge` | 7 |
| A5 | … step `:empty` | `:done-step` | 7 |
| A6 | … step `:work-left` | `:bail` | 7, refused |
| **Settled** (outcome `:done` or `:merged`) | | | |
| S1 | publication `:none` or `:done` | steady "done" | — |
| S2 | publication `:due` | `:publish` | 9 |
| **Nothing open** (latest `:none` or `:closed`): the start group, admitted by the fold below | | | |
| N1 | not wanted (not `:running`, or a need pending) | steady "not released, or waiting on needs" | — |
| N2 | wanted, branch `:absent` | `:build` (start group) | 1 |
| N3 | wanted, branch `:stale` | `:rebuild` (start group) | 1b, idle half |
| N4 | wanted, `:current`, config `:differs` | `:converge-config` (start group) | 2 |
| N5 | wanted, `:current`, converged, environment `:incomplete` | `:problem-hold` | 3c |
| N6 | wanted, `:current`, converged, environment `:complete` | `:start-step` (start group) | 3 |

The branch-level table (a second table of four booleans: active, route held, finished, ended; 16 combinations, 8
possible) replaces old rows 10 and 12:

| Row | When | Then | Replaces |
|---|---|---|---|
| B1 | active, route absent | `:listen` | 12 |
| B2 | active, route held | steady | — |
| B3 | not active, route held | `:release-route` | today's `release-chain-route!` |
| B4 | not active, route absent, finished, not ended | `:end-branch` (`seon.task/end-branch!`: graphs joined, routes released, the branch kept by default) | 10 |
| B5 | not active, route absent, finished, ended | steady (kept) | — |
| B6 | not active, route absent, unfinished | steady (held work waits) | — |

**Actions are validated data.** `:seon.flow.actions/op` is a `:multi` on `:op`, each variant declaring its members
(task, branch or agent): `:build :rebuild :converge-config :start-step :problem-hold :judge :done-step :start-graph
:stop :close-cancelled :close-environment :bail :merge-main :publish`, and the branch ops `:listen :release-route
:end-branch`. An unknown op is refused by the `::m/default` variant, never run. Each effect owner applies its op and
writes the fact that closes its row.

**The scheduler is a fold, not rows.** The coupling between tasks is not a fact about one task, so
`seon.cluster.agent/admit` folds the start-group answers: oldest first, one per branch (git checks a branch out in one
worktree), while the open attempts are fewer than `:seon.config.agent/max-running`, read from the cluster's
configuration at every pass (`seon.config/effective`; shipped 4, live 4). Open attempts are counted on facts, as
`launch-actions` counts them today, so the cap holds across a restart. The prototype answers `["t0"]` for five ready
tasks, `max-running` 2 and one open attempt, and `["a"]` for two ready tasks on one branch. A cap of 0 admits nothing
(owner decision 8). Only the start group is capped: stopping, judging, merging forward, bailing and publishing always
run.

**Readiness** is the `wanted` rows N2 to N6, and its pieces are:
- `execution(T) = :running`, and no need is `:pending`: every N in `needs(T)` is **done where N's attempts live**, which
  is N's branch head for a task with no target, and main for a task whose done step wrote main, because its accept
  transaction carries N's attempt;
- the **latest attempt is absent or `:closed`** (gap 1). An open attempt is not ready (G, O rows), and an ended
  attempt awaiting its judgment is not ready either (A rows), so a task is never started again while its previous
  attempt is judged or bailed. `:closed` is the only ended state that restarts, and only because the restart
  (row O3, G5) leaves `execution :running` on purpose;
- the branch has no open attempt of another task (one checkout per branch, in `admit`).

A task becomes wanted only by root writing `:running`: every written task is `:held`.

The ruling's "and its own proofs are not yet green" is honored by the start step (N6), not by a precondition. The
proofs' state exists only on the task's built branch (do the work when its data exists). A task whose proofs are green
there is done at once (O6), with no graph and no model call. So a ready task never gets an agent while its proofs are
green, and the reconciler never computes a test result.

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

**A stale branch is never rebuilt over work** (gap 2). Stale means the recorded base H is no longer the target's head.
- Idle and wanted (N3): nothing is on the branch to lose, so rebuild: retire the stale branch and build again from the
  new head. A `:held` task is never rebuilt.
- With a graph, an open attempt or an ended attempt awaiting its judgment (G7, O4, A3): the branch holds the work,
  including a merge agent's conflict resolutions, so main is merged forward into it (`seon.merge/merge-main!`, the
  reactive plan's R-P3), conflicts as unmerged entries the agent's next block shows. The attempt's end commit moves, and
  the next judgment is made on the merged head.

**The start rows check execution and the observed environment** (gaps 3 and 4). `:start-graph` has exactly one row
(O7), and it requires execution `:running`, agent-env `:none` or `:same`, the branch `:current` and `step :work-left`.
A held, cancelled or restarting task therefore never starts a graph, and after the restart's stop (G5) the old agent
is closed first (O3) and never started again on its old environment.

**The done step** (O6 and A5) is one function of the environment:
- When the task's branch was built against a target (the registry's recorded target, observed), it is P4a's accept:
  one transaction on the target with parents #{the branch's source commits, the branch's judged commit} under
  Datahike's basis fence on H, writing the attempt `:merged` and the carried tasks.
- Otherwise it writes outcome `:done` on the task's own branch.
- Main moved (`:seon.db/transaction-refused-error`): nothing is written. The branch is now `:stale` with an open or
  awaiting attempt, so the next pass merges main forward (O4, A3) and judges again. This is a new environment, not a
  retry of failed work, and it never rebuilds over the attempt.

**No automatic re-runs.** The bail (A2, A6) writes `execution :held`, and readiness requires `:running`, so nothing in
the table restarts a bailed task. Only root's write of `:running` does.

**A bail never un-cancels** (gap 5). A cancelled task whose attempt ended is closed as cancelled (A1), with no problem
task and no `:held`; the bail rows exclude `:cancelled`.

**The bail writes two databases, in a fixed order** (gap 12): `:held` on main first, then `:closed` on the task's
branch. A crash between them leaves (`:held`, latest `:awaiting`), which A2 bails again idempotently. The opposite
order would leave (`:running`, latest `:closed`), a start row, and the task would run again. The bail's applier is the
one writer that must keep this order (T3b's proof, §10).

**A crash between the start step's attempt row and its judgment** (gap 7) is a row: an open attempt with no graph and
`step :unjudged` is O5, which judges.

**Livelock under a moving main needs no guard** (gap 13). With the done step's "main moved" answer included, the
closure has 42 cycles, each passing through the done step's refused answer (A3, A4, A5 or O4, O5, O6: merge forward, judge, refused by the fence, again).
They are livelock only under continuous writes to main; progress is bounded by main's write rate, which is
Kubernetes-style convergence. It is not a table defect, and no retry count, backoff or lock is added (AGENTS.md "NO
GUARDS OR FENCES").

**Idempotence.** Every action writes the fact its gap is absent from:
- the branch and its base;
- the attempt row and its step results;
- `graph-process`;
- the outcome, or the accept.

A second reconcile over the same state therefore plans nothing.

### 5.3 The proof: one exhaustiveness deftest

The completeness is checked, never assumed. The decision table over Malli enums beats the alternatives in
`transitions.md` §2 and `transitions-web.md` §2: core.match has no compile-time exhaustiveness (a missing clause is a
run-time `IllegalArgumentException`, and an `:else` silences even that, which is a fence), core.logic needs negation
over finite domains, and statecharts discard an unmatched event by SCXML specification, hold a second copy of observed
state and settle overlaps by document order. No library beyond Malli is added.

T3a's one red proof, `seon.cluster.agent-test/the-transition-table-is-exhaustive-deterministic-and-convergent`,
enumerates the 13,968 possible states and asserts four properties, reporting any failure as the offending states
(data):
1. **Exactly one row.** Every possible state matches exactly one row: no gap, no overlap (0.1 s).
2. **Successors are possible.** Every action leads only to possible states: each op's `effects` names the facts its
   actor writes and every answer the world can give (a judgment of `:empty` or `:work-left`; a refused basis fence),
   and none violates a constraint.
3. **Convergence.** Every state reaches a steady row without a cycle, with no external event (0.2 s).
4. **Plan, apply, re-plan.** Applying a plan's closing facts to the observed value and planning again answers `[]`
   (idempotence), through the whole-value `reconcile`, with the test-local `closed` helper.

An impossible observed state is refused by `reconcile`'s input contract, which names the constraint: data in the
one error map delivered at db<->flow's boundary, not a guard. The whole check runs in about 0.7 s; matching costs
1.6 ms per 1,000 tasks, naive and warm.

`transitions.md` §5 encoded the old gap table as written: 10,424 of 29,760 states matched one row, 9,960 matched none
(6,240 unnamed steady states and 3,720 needing an action), 9,376 matched two or more, 7,650 led to an impossible state
and 72 cycled. The completed table matches 29,760 of 29,760 once (13,968 of 13,968 after the fold), with 0 invalid
successors and 0 cycles. The fifteen gaps it found are resolved in the owning sections, indexed here:

| Gap | Resolved in |
|---|---|
| 1 readiness admits an ended attempt | readiness above: latest absent or `:closed` |
| 2 rebuild destroys work | a stale branch above: merge main forward over work (G7, O4, A3), rebuild only idle (N3) |
| 3, 4 the start rows ignore execution and the observed environment | one row O7; O3 closes first |
| 5 a bail un-cancels | A1; the bail rows exclude `:cancelled` |
| 6 `:paused`, `:stopped` have no rows | owner decision 7: folded into `:held` |
| 7 the crash between attempt row and judgment | O5 |
| 8 rows 6a, 6b, 6c overlap | G1, G2, G5 |
| 9 unnamed steady states | the steady rows |
| 10 routes never released | B3 |
| 11 deleting a branch erases a `:done` | owner decision 6: progress is observed state on the task's branch; branches are not deleted by default (§3.2) |
| 12 the two-database bail | the bail's order above |
| 13 livelock under a moving main | noted above, no guard |
| 14 `:sent-back` changes meaning | §9 and T3b: `end-kinds` goes in the same commit |
| 15 the dead `cluster` dial | §9 and T3b/T3c: deleted |

### 5.4 Processes are flow procs: no new lifecycle types

Owner, 2026-10-17: flows have explicit start, stop and run states, so no process invents its own lifecycle types.
Root verified in `clojure.core.async.flow`: a proc has an init (the `(args)` arity) and the transitions
`::flow/resume`, `::flow/pause` and `::flow/stop`, with statuses `:paused`, `:running` and `:stopped`.
1. **Every long-lived process is a flow proc** that maps its own states onto flow's: the reconciler (`db<->flow`),
   each agent's graph, render and the schedules. A state a process needs beyond flow's three is a dimension of its
   decision table (§5.1 for the task, T3a-agent for the agent), never a new lifecycle type.
2. **A proc's listeners are registered at init or start, used while it runs, and unlistened at stop.** `db<->flow`
   already does this: it registers its listener at init, runs passes while running, and unlistens at stop (the prime at
   `::flow/resume` is the only thing the transition adds).
3. **The reconciler holds a listener on main plus one per watched task branch.** Datahike listeners live on each
   connection's meta (`datahike.writer/notify-listeners!`); there is no global tx-log, and a branch is its own
   connection, so a proc listens to N branches by holding N listeners, as `db<->flow` already holds a main route plus
   chain routes keyed per branch. Adding or releasing a branch listener is a reconciler action derived from the task
   facts: `:listen` (B1) when a branch is active, `:release-route` (B3) when it is not (gap 10). The listener set is
   never kept as separate state: the registry holds the live listener objects in its one registry (AGENTS.md), keyed by
   branch, and each pass re-derives which should exist from the facts.
4. The reconciler's own on and off is Flow's pause of the proc (§7); the table has no state for it.

**Today's pieces and what they shrink to** (function spans on HEAD 2ee1db4a5):

| Today | Lines | After |
|---|---|---|
| `launch-facts` 36, `chain-read` 50, `chain-facts` 26, `chain-ends` 28, `chain-finishes` 35, `chain-bails` 26, `lifecycle-facts` 58, `taskless-agents` 27, `branch-admission-end` 16, `basis-valid?` 18, `registry-facts` 10, `seon.task/ready` + `ready-rules` + `done?` + `execution` 62 | 392 | `observe` ≈ 70 |
| `launch-actions` 45, `follow` 17, `assignment-actions` 37, `taskless-actions` 11, `next-actions` 11, `unfinished-intent` 12, `publication-due?` 10 | 143 | `reconcile` ≈ 90 plus the rows as data (the transition table; the taskless-agent rule kept as two rows) |
| `advance-chains!` 25, `merge-chains!` 56, `finish-chains!` 37, `bail-chains!` 10, `bail-chain!` 30, `done-judged` 16, `close!` 22, `apply-lifecycle!` 33, `fork-attempt!` 14, `seed-attempt!` 18, `start-attempt!` 14, `write-attempt!` 16, `write-chain-attempt!` 22, `start-chain-attempt!` 29, `failed-pass!` 27, `merge-step` 18, `refusal-section` 19 | 406 | appliers ≈ 160: build and rebuild (N2, N3) 30, start step (N6) 35, done step (O6, A5) 30, bail (A2, A6) 25, publish and end (S2, B4) 25, close (O1, O3, A1) 15 |
| `run-pass!` 50, its thrice re-derivation of the lifecycle included | 50 | ≈ 15: observe, reconcile, apply |
| **total** | **991** | **≈ 335**: about 655 lines go from `seon.cluster.agent` |

`merge-step` and `refusal-section` go: there is no append. The merge task's start step shows its refusal (red members,
unmerged entries, lint) in its block as any task's step does. `route-chain!` is renamed to the branch route.
`agents-to-start` stays (root and branch agents with work, not tasks).

### What disappears, by the owner's list

- **The chain entity and its state.** The schema `seon.chain.edn` (13 keys and three views) and §9's functions.
- **`run!` as a branch-forking starter.** Writing a task starts nothing (every task is `:held`). `my.root/run!` survives
  as the one release, a start or a resume after a bail alike: one write of `:running` on main, no branch transaction.
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
  - `chain-finishes`' "main does not hold the attempt yet" check. The accept on main is the observation;
  - `seon.cluster.agent/follow`, the `:paused` and `:stopped` arms of `launch-actions`, and the dead
    `:seon.flow.actions/cluster` dial: the table has `:stop` and no `:pause` (owner decision 7).

## 6. Environment, merge and done

### 6.1 Environment is desired state; a difference restarts the agent

A task's **environment** is its branch, its target and its config: `:seon.task/branch`, `:seon.task/target`,
`:seon.task/config` (every dial the launch and the turn loop read, the turn cap and the provider dials included) and
`:seon.task/model`. It is desired state on the task entity. Root writes it on main and may write it on any branch.

**Where it is read.** A task's environment is read from the database its attempt checks out: main's task before an
attempt, and the task as its branch holds it while an attempt is open. That is the agent's checkout, as a git
working tree reads its own config. Root changing a running task writes the task on that branch; one merge later
carries the change to main's task. Nothing syncs the two. `my.task/update!` stays closed to `config` and `model`: an
agent that needs a different environment calls `my.task/send-back!`.

**Complete before start** (owner decision 3). An agent is never started until its task's desired environment is
complete. Start is a reconciler action (N6) whose precondition is that `:seon.task/environment`, a closed schema
over the members above, validates the environment as the built branch holds it (the branch is built by N2, the
config converged by N4; the decided config has every dial the launch and the turn loop read). An incomplete
environment is not a start: it is row N5, reported once as a problem task naming the missing members and the task
held. Nothing starts with a default standing in for a missing member.

**The observed environment** is recorded on the agent row when its context is acquired:
- the branch its context reads (`:seon.agent/branch`, exists);
- the config digest its turn loop read (`:seon.agent/environment`, new: `seon.config/effective`'s decided map, by
  digest).

**The restart** (G5, then O3). desired differs from observed while `graph-process` is present: the reconciler stops
the graph and joins it (G5), closes the attempt `:closed` with the reason "environment changed", and releases the
context. The task stays `:running`, so the next pass starts a fresh attempt, with a new agent identity, a new graph
and a new SCI context, on the desired environment (N2 to N6). Private SCI state is lost; the owner accepts that for
reliability ("a clean restart is more reliable even if it loses data"). The cross-branch in-place advance is dropped,
and with it the risk it carried.

One mechanism covers:
- root editing a held task's config: no graph, so the next start reads it;
- root editing a running task's config or model: N4 converges the branch's config row, and G5 then O3 restart onto it;
- root moving a running task to another branch: G5 then O3 restart onto the new branch, whose attempt row starts there;
- a task whose environment is incomplete: nothing starts (N5).

A **moved base is not an environment change.** A merge task whose main moved is stale: N3 rebuilds it before the start
step while it is idle; while an agent works or its attempt awaits judgment, `seon.merge/merge-main!` (the reactive plan's R-P3, rows G7, O4, A3) merges main forward into its branch and
the agent's next eval request reacquires the new head. Conflicts arrive as unmerged entries in its block (§6.3). The
graph is not restarted.

There are no special paths.

### 6.2 The merge task reuses P4a

**A merge is an ordinary task.** `seon.task/from-filed` and any builder write `[design prove implement merge]` at
once, linked by `needs`. The first three share one branch b. The merge task, from `seon.step.merge/template`,
carries:
- the template's instructions, model and config;
- `:seon.task/target`, main's branch, set by the builder, which holds main's database.

There is no merge attribute, no merge function and no chain entity. A bench run writes no merge task.

**Its environment is P4a's merge branch S** (row N2, built when the task is ready). S holds:
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
- **Main moved:** before the start step, while S is idle, N3 rebuilds S from the new head. While an agent works or the
  attempt awaits judgment, `:merge-main` (G7, O4, A3) merges main forward into S, and the agent's next eval request sees
  it; S is never rebuilt over work. Unchanged reach is a lookup.

**What of P4a is reused, and how** (`seon.cluster.source`, `seon.merge` on HEAD 2ee1db4a5):

| Function | Role in the merge task | Status |
|---|---|---|
| `seon.cluster.source/merge-base`, `merge-base-refusals`, `merge-base-database`, `branch-merge-base` | O for the build | unchanged |
| `compare-branch`, `compare-commit`, `seon.program/three-way`, `seon.program/own-changed-identities` | Δ and its conflicts for the build | unchanged |
| `unmerged-tx`, `entry-identity`, `unmerged`, `with-unmerged`, `unmerged-sets`, `unmerged-count`, `unmerged-refusal` | the conflicts on S, the done rule's refusal | unchanged |
| `versions` | each entry's base, ours and theirs as resolved code references | unchanged; made public for §6.3 |
| `replacement` | Δ's replacement transaction on S | unchanged |
| `merge-members`, its fold, `contract-gaps`, `uncovered-functions`, `publication`, `lint-refusal`, `done-refusals` | S's members and the one judgment (start step N6, judgment O5, A4) | unchanged |
| `prepare-merge!` | split along what it already does: the build (fork H, one write of Δ + unmerged entries + the facts squash) becomes the applier of N2 and N3; its judgment (`judged-merge!`) is the start step's ordinary `done-refusals` over `judged-input` on S | body kept, ≈ 40 lines move to the start step |
| `accept-merge!` | the done step for a task with a target: one `seon.db/transact!` with `:parents` and `:datahike/expected-basis-t` H | unchanged body; called with the task's S |
| `seon.merge/merge-tx`, `squash-merge`, `collision-remap`, `member-merge`, `cluster-entity?`, `attribute-tx` | the facts squash in the build | unchanged, minus the `:running` → `:stopped` rule (§5) |
| `seon.merge/merge-main!` | G7, O4 and A3: while the merge agent works or awaits judgment | unchanged |
| `seon.merge/publish!`, `indexed!`, the write-back (`proven-files`, `write-back!`, `commit!`) | S2 | unchanged (P7 makes one proof per content) |
| `my.program/resolve!` | the agent's (or root's) resolve verb | unchanged |
| `seon.merge/merge!`, `merge-onto`, `assignment-row`, `assigned-task`, `chain-row`, `acceptance` | the entrance by attempt key: the chain path and the per-assignment path | **deleted** in T3b; the reconciler calls the build, the start step and `accept-merge!` directly |
| `seon.cluster.agent/merge-chains!`, `advance-chains!`, `chain-finishes`, `chain-ends`, `merge-step`, `refusal-section`, `finish-chains!` | the chain merge path | **deleted** in T3b (§5) |
| `lifecycle-facts`, `assignment-actions`'s `:merge`, `apply-lifecycle!`'s `:merge`, `fork-attempt!`, `seed-attempt!`, `publication-due?` | the per-assignment merge path | **deleted** in T3b |

The merge logic itself does not change. What goes is every entrance to it but one, and every piece of state that
records where a merge stood: the merge step append, the chain fact, the assignment rows on main and the second
transaction (P4b).

**Root's repair at the REPL** (AGENTS.md "Merge is a root task": "conflicts stay unmerged on the merge branch, which
root repairs at the ordinary REPL holding both sides") is root working the merge task as an outside agent:
1. Root writes `:seon.task/model :outside` on the task. That is an environment change, so the restart applies (§6.1).
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

- `done-refusals` empty means done (rows O6 and A5).
- A task with no target writes `:done` on its branch and nothing to main.
- There is no manual approval today, and none is added.

**Approval, if the owner wants it, is a task.**
- Its model is `:outside` (no model turns).
- Its proof is a deftest that reads a person-written approval fact on the supplied task (`seon.test/task`).
- The tasks after it list it in `needs`.

It adds no flag and no reconciler row; one attribute when first wanted (owner decision 5).

### 6.5 Dependents of parallel work

C needs A and B on separate branches.
- If A and B are followed by merge tasks MA and MB, C needs those. They are done on main, and C's branch (built from
  main's head) holds both.
- If C needs A and B directly, C's branch is built as main's head plus both unmerged changes (the build rule), with
  any conflict between them as unmerged entries in C's conflicts section. A merge task after C merges all three.

Either way main stays the one integration point, and every write to main is a judged done step under the basis fence.

## 7. Scheduling

The task graph is the scheduler:
- Writing tasks never starts work: every written task is `:held`, and the owner flips the system on by root writing
  `:running` (`my.root/run!`). `:seon.task/execution` and `:seon.task/needs` wake root.
- Readiness is §5's rule (rows N1 to N6).
- Parallelism is `admit` (§5.2): every ready task, oldest first, one per branch, while open attempts are fewer than
  `:seon.config.agent/max-running` (the cluster configuration; the live cluster answers 4). The reconciler never
  starts a task past the cap.
- Dependency order is the needs graph.

Config before agent (the reactive start ruling of 2026-10-15) is rows N2 and N4 before N6. Nothing is ordered by call
sequence.

**Three controls, each at its own scope** (owner decision 8):

| Control | Scope | Durable? |
|---|---|---|
| Flow's pause of the `db<->flow` proc | everything, at once: no starts, stops, judging, merging or publishing; agent graphs are separate graphs and keep working, their ends wait as `:awaiting` | no (boot resumes the graph) |
| `:seon.config.agent/max-running` | the cap on new starts while on | yes, cluster facts |
| `:held` per task | one task | yes |

`(flow/pause-proc cluster-graph :seon.flow/db<->flow)` is the instant off. Flow starts every proc paused, and a paused
proc blocks on its control channel and reads nothing; the listener's wakes coalesce in the sliding-1 in-port, and
`::flow/resume` primes one pass that reads every fact, so catching up is free because the reconciler is level-triggered
(`transitions.md` §7.2, read from `clojure.core.async.flow.impl`). The proc's own lifecycle (paused, resuming, running)
stays out of the table: Flow owns it.

`max-running` may be 0 (its schema becomes `:min 0` in T3a): running work finishes and nothing new starts, and that
holds across restarts because the open attempts are counted on facts and the cap is a cluster fact. `admit`'s
`(max 0 (- max-running open))` already admits nothing.

## 8. Root's tools on task identity

| Tool | Becomes |
|---|---|
| `my.root/run!` | Release `{:seon.task/assigned h}` (or a vector of handles): validate the runnable view, write `:running` on main. It is the one start signal and a resume after a bail alike, and no branch transaction (today `seon.task/run-held!`, 29 lines, becomes about 8). Nothing else writes `:running`. |
| `my.root/abandon!` | `{:seon.task/assigned h}` or `{:seon.task/branch b}`: write `:cancelled` on the named tasks and every task that needs them, transitively. The reconciler stops, closes and ends the branch (G2, O1, A1, B4). 30 + 26 lines become about 20. |
| `my.root/merge-requests` | The tasks with a target, their latest attempts read on their branches |
| `ended`, `close!` | Read attempts through each task's `:seon.task/branch` |
| `merge-steps`, `chain-requests` | Deleted |
| `seon.task/chain!` | Deleted. `seon.task/create-all!` (new; `seon.task/create!`, one task from a request map with an agent id, has 73 callers in 27 files and is unchanged) takes a vector of task maps and writes them on main in ONE transaction through the one task writer. It resolves each `:seon.task/branch` (§4), fills defaults (`:held`), and validates each task's runnable view and that every needs edge resolves within the write or to an existing task. It is idempotent per task identity. No fork at the write: the build is row N2, when the task is ready, so a dependent builds after its needs are done. |
| `seon.task/from-filed` | Answers `[design prove implement merge]` with `needs` links, the entry's identity on the first map, and the merge task's target. |

## 8a. The error law, as touched

AGENTS.md's error law applies, ratcheted by P13's census (`seon.guard-census-test/a-changed-function-holds-no-guard`):
- Every function a T phase writes or keeps (`observe`, `reconcile`, the appliers, `create-all!`, the build, the conflicts
  section, the restart) follows the law:
  - a reader returns its declared variants;
  - an applier throws the one error map to the entrant boundary, here db<->flow's one catch, `failed-pass!`,
    rewritten to a bail (A2, A6);
  - nothing downstream re-checks what contracts or the writer guarantee.
- The deleted functions (§9) are deleted, never converted. Untouched code waits for its toucher.
- The reconciler adds no guard. A case it cannot plan is a desired state that does not validate, refused at the
  write by `:seon.task/runnable`. A failed effect is its actor's error, and the attempt it names bails.

## 9. Deletion table (function spans on HEAD 2ee1db4a5)

| Symbol | Lines | Phase | Replaced by |
|---|---|---|---|
| `seon.cluster.agent`: §5's 991 lines of pieces (`merge-step` and `refusal-section` included) | 991 → ≈ 335 | T3b | `observe`, `reconcile`, the appliers |
| `seon.task/chain!` 66, `chain-entities` 22, `chain-handles` 8, `chain-attempts` 31, `chain-branch` 14, `chain-branches` 8, `chain-refusal` 16, `hold!` 20, `end-chain!` 16, `abandon!` 26 | 221 | T3b, except what the chains page still calls (`chain-handles`, `chain-attempts`, `chain-branches`, checked by `rg` at the phase): those go with the page in T3c | `create-all!` (≈ 25), `end-branch!` (exists), abandon as a desired write (≈ 12) |
| `seon.task/run-held!` 29 | 29 → 8 | T3b | resume |
| `seon.task/advance-tx` 24, `next-tx`, `next-refusal` | ≈ 45 | T3b | the flow at the start step (N6); no merge-step append |
| `seon.task/ready` 22 and the readiness rules `blocked` and `needs*` | ≈ 30 | T3b | readiness inside `reconcile`. The `ready-rules` var stays with `execution`, `under`, `self-or-under`, `leaf-done` and `open-attempt` for `done?` 14, `resolution`, `effective` and `seon.cluster.message/undeliverable` until S3e deletes the task tree. Their readers outside T3b's first list (message.clj, `seon.cluster.agent` lines 1876 and 1879) convert in T3b. |
| `seon.task/effective` 23, `goal-owner` 15, `children` | 38 | **not in the task model** (S3e) | live readers: `seon.context`, `ref_test`, `task_test`, `script/seon/rename_task_intent.clj`, four filed task files, and 10 `:seon.task/children` datoms on default. T3b does not delete them. |
| `seon.task` chains page: `chain-view` 27, `chain-views` 15, `chain-state` 12, `chain-html` 16, `chain-task-html` 18, `attempt-view` 7, `attempt-html` 27, `bailed?` 10, `finished?` 8 | 140 → ≈ 70 | T3c | one task-graph view grouped by branch |
| `seon.render.web/chain-fragment`, `chains-page-result`, `chain-database`, `chain-state-rank`, `agent-branch` and `missing-agent` chain reads | ≈ 74 → ≈ 45 | T3c | the task-graph page |
| `seon.merge/merge!`'s assignment entrance, `assigned-task`, `chain-row` 22, `assignment-row`'s chain fallback and first-parent walk | ≈ 60 | T3b | the done step reads the task's built branch (its base H and target, recorded by the registry); the facts' base is the branch's recorded base |
| `seon.merge/merge-tx`'s `:running` → `:stopped` rule and its `:stopped` write on a merged task (`seon.merge` lines 193 and 196) | ≈ 8 | T3b | control members never written on a branch; a merged task reads as `:settled` (S1) and needs no execution change |
| `seon.cluster.registry/cluster-claims`' chain clause, `task-branch-claims` | ≈ 12 | T3c | the existing `:seon.task/branch` clause |
| `seon.mine/kept-branches`' chain query | 3 | T3c | `:seon.task/branch` rows |
| `:paused` and `:stopped` (owner decision 7): the `:paused` and `:stopped` arms of `seon.cluster.agent/follow` and `launch-actions` (lines 1659 to 1696); `merge-tx`'s `:stopped` write (above); fixtures that write `:stopped` (`test/seon/task_test.clj` line 235, `test/seon/merge_test.clj` lines 911 and 913, `test/seon/cluster/db_flow_test.clj` line 1789, `test/seon/cluster/write_back_test.clj` line 722); the enum values in `seon.task.edn`'s `:seon.task/execution` and its description; `resources/seon/tasks/seon.cluster.agent.edn`, `seon.cluster.boot.edn` and `seon.turn.edn` (checked by `rg` at the phase) | ≈ 30 + tests | readers T3b (`follow`, `launch-actions`, `merge-tx` and the fixtures); the enum values T3c, after T3b converted the last reader. The one live `:stopped` task on default reads as `:held`. | `:held`; the table's `:stop` and no `:pause`. `transitions.md` §4.3: swapping `:stopped` for `:held` changes 0 answers. |
| `:seon.flow.actions/cluster` (the dead dial: `launch-facts` hardcodes `:running`, line 1896) | schema member + 3 sites | reader `launch-facts` T3b, the schema member and `test/seon/cluster/db_flow_test.clj` lines 46 and 143 T3c | on and off is Flow's pause (§7) |
| `seon.task/end-kinds` (line 1551) and `end-kind` (`:sent-back` maps to `:merge`) | ≈ 15 | T3b, in the same commit as the bail rows (A2, A6) | a send-back bails to root (CLAUDE.md "`my.task/send-back!` ... bails a broken or impossible task to root"); the table replaces `end-kinds`' mapping. A line 1564 caller (`remove (comp #{:merge} end-kind)`) converts in the same commit. |
| `seon.context/judged-input`'s four chain reads | ≈ 8 | T3b | a task's needs-dependents on its branch give `:seon.source/next-proofs`; a task with a target is judged as the last task |
| `my.root/run!` 20, `abandon!` 30, `chain-requests` 13, `merge-steps` 36, `merge-main!` 20 (chain reads) | 119 → ≈ 45 | T3b | §8 |
| `resources/seon/schemas/seon.chain.edn` (all), `:seon.task/chain-options`, `:seon.task/chain`, `:seon.task/chain-context`, `:seon.task/refusal` and `:seon.task/refused-tests` on tasks (the merge step's), `:seon.flow.actions/chains`, `/branches`, `/startable`, `/invalid`, the `:seon.chain/branch` members of `seon.flow.actions.edn` and `my.root.edn` | schema | T3c | `:seon.flow.actions/observed`; `:seon.task/runnable` reads `:seon.task/branch` and `:seon.task/target` |
| `:seon.task/next` (schema; readers in `seon.context`, `seon.task`, `seon.cluster.agent`, `seon.test.runner`, `seon.step.prove`, `my.program`, nine test files, five resource files, four docs) | — | readers T3b, schema T3c (the chains page reads it until T3c) | `:seon.task/needs` (owner decision 1); a task's next proofs are the proofs of the tasks that need it and share its branch |
| Readers of `:seon.chain/branch` and `:seon.task/next` the first list missed: `seon.task/branch-attempts`, `runnable` (adds `:seon.chain/branch` to the view), `next-entity`, `task-rows`, `identified`, `resolution`'s `next` read; `seon.context/chain`; `seon.test.runner/task-identity`; `seon.step.prove/handoff`; `my.program/task-source`; `my.root/laws` (help text) and the `:seon.chain/branch` request keys of `abandon!` and `merge-main!`; `seon.render.web/agent-branch`, `missing-agent` | ≈ 40 | T3b (T3c for the page's: `agent-branch`, `missing-agent`) | `:seon.task/branch` and `needs`; `runnable` reads `:seon.task/branch` and `:seon.task/target` |
| `seon.cluster.boot`: `cluster.agent/release-chain-routes!` (line 597), the `:seon.agent/chain-routes` log (563), `task/chain-branches` for the operator's `--chains` (770) | ≈ 6 | T3b (route), T3c (`chain-branches` becomes the branches of tasks) | the branch route; the operator flag keeps its name |
| `seon.cluster.message/undeliverable`: `(open-attempt ?a)` and `(execution ?t ?intent)` over `task/ready-rules` | 3 | T3b | the agent's task `:seon.task/execution`, and `:seon.agent/graph-process` for whether it is running |
| Filed task files naming a deleted symbol: 14 of `resources/seon/tasks/*.edn` (38 references), and schema descriptions in 7 `resources/seon/schemas` files | — | T3b (tasks), T3c (schemas) | converted to the surviving symbol, or the task retired; an unresolved reference is a problem on the task at ingestion |
| `docs/seon/schedule/cards/MERGE-STEP.md` S6 B–E | card | T0 | the merge task (§6) |
| The tests `seon.cluster.merge-test/a-requested-green-end-merges-and-publishes-with-no-hand-act`, `seon.cluster.merge-test/a-done-branch-merges-with-no-merge-request-written-by-the-agent` and the per-assignment twin `seon.cluster.db-flow-test/a-requested-end-whose-files-no-checkout-holds-merges-nothing` (db_flow_test.clj:1612). Their filed tasks: `seon.merge/a-requested-green-end-merges-and-publishes-is-red-on-head`, `seon.merge/a-done-branch-merges-with-no-merge-request-is-red-on-head`, `seon.cluster.agent/a-requested-green-end-merges-and-publishes-with-no-hand-act`. | tests | T3b | One test of the one path, `seon.cluster.db-flow-test/a-task-and-its-merge-task-end-merged-and-published-with-no-hand-act`. It carries their assertions: main holds the definition by digest; `:merged-declarations` 1; one git commit whose parent is the old HEAD. All three were red on HEAD 2ce235df8 in the narrow check (`bin/seon check --at HEAD`: 8 executed, 3 red) and tested the per-assignment path, which is deleted; they are retired with it, not repaired. |

Totals: about **1,050 lines deleted** from `src`:

| Namespace | Lines deleted |
|---|---|
| `seon.cluster.agent` | ≈ 655 |
| `seon.task` | ≈ 330 |
| `my.root` | ≈ 75 |
| `seon.merge` | ≈ 60 |
| `seon.render.web` | ≈ 30 |

About 270 lines are added (`observe`, `reconcile`, the build rule's generalization of prepare to several needs,
`create-all!`, the task-graph view). About 230 test lines use `:seon.chain` or `chain!` (16 files;
`db_flow_test` 89, `task_test` 46, `competency` 22) and convert in T3b–T3c.

## 10. Phases

Every phase amends TASK-CHAIN in place, with its red deftest (the task-chain design is locked), and boots HEAD after
it. Every phase but T0 edits a schema resource or boot-critical code, so each commits through `bin/lane commit --test`
(platform class), not `--branch-evidence`; T3b and T3c land in one restart batch. A function is deleted in the phase that
converts its last caller (the narrow check's deletion table lists them); T3b therefore keeps the chains page, its
helpers and the `:seon.task/next` attribute, which T3c deletes together.

| Phase | Files | Change | Red proof (red on HEAD, green after) |
|---|---|---|---|
| **T0** Words and the cards (docs, 20 min) | `docs/seon/architecture/vocabulary.md`, `docs/seon/schedule/cards/TASK-CHAIN.md`, `docs/seon/schedule/cards/MERGE-STEP.md` | Record the words of §4, with a note that `:seon.task/target` (a branch, this plan) is not `:seon.task.attempt/target` (the task a send-back names). TASK-CHAIN §1 and §2 rewritten in place to this model: `needs` leaves §1's deleted list, S3e narrows to `children`, `product` and `effective`, and the 17 filed entries carrying `needs` keep them; §6 and §7 get these phases. MERGE-STEP retired into §6. The transition table of §5 and the words transition table, row and steady row enter TASK-CHAIN §2, done by lane task-t0-finish. | none (docs). Each later phase's proof is the card's. |
| **T1** The writer (45 min, Sonnet) | `src/seon/task.clj` (`create-all!` new, `from-filed`), `resources/seon/schemas/seon.task.edn` (`:seon.task/target`, `:seon.task/config`), `test/seon/task_test.clj` | `seon.task/create-all!` over a vector of task maps: one transaction on main, every task `:held`, branches resolved, `:seon.task/target` and `:seon.task/config` stored, the runnable view and the needs edges validated. `seon.task/create!` is not changed (73 callers). Not yet called by the launch: additive. | `seon.task-test/a-task-write-resolves-every-tasks-branch`: in one write of `[a b c d m]` (b needs a; c and d need b; m, with a target, needs c and d), every task is `:held`, a and b share a branch, and c, d and m each get their own; a given branch wins; a repeat write commits nothing. `seon.task-test/a-filed-task-becomes-four-linked-tasks-ending-in-a-merge-task` (`from-filed`: the last map needs the implement and design tasks, carries main's branch as its target and no proofs; design, prove and implement share one branch; all four `:held`). Red on HEAD because `create-all!` does not exist. |
| **T2** Graph process as a fact (45 min, Opus: boot-critical) | `src/seon/cluster/agent.clj` (`start-flow!`, `stop-flow!`), `src/seon/cluster.clj` (`recover-runs!`), `resources/seon/schemas/seon.agent.edn`, `test/seon/cluster/agent_test.clj` | The registry asserts and retracts `:seon.agent/graph-process`. Boot retracts values naming another process. | `seon.cluster.agent-test/a-started-graph-is-observed-until-it-exits`: after `start-flow!` main holds the agent's `graph-process` naming this process; after `stop-flow!` returns with every thread exited it is absent; a value naming another process id is absent after boot's recovery. Red on HEAD because the attribute does not exist. |
| **T3a** The pure reconciler and its exhaustive table (90 min, Opus) | `src/seon/cluster/agent.clj` (`transitions`, `impossible`, `effects`, `task-state`, `row`, `admit`, `reconcile`: new, not wired), `resources/seon/schemas/seon.flow.actions.edn` (the twelve dimension enums, `:seon.flow.actions/task-state` with its constraints as one registered named predicate, `/observed`, `/actions`, the `:seon.flow.actions/op` `:multi`), `resources/seon/schemas/seon.task.edn` (`:seon.task/environment`), `resources/seon/schemas/seon.config.agent.edn` (`:max-running` `:min 0` and its description, owner decision 8), `docs/seon/architecture/vocabulary.md` (transition table, row, steady row, recorded first), `test/seon/cluster/agent_test.clj` | `reconcile` over hand-written observed values (data, no JVM effects): each task's `task-state`, its one row of `transitions` (§5.2), then `admit`'s fold over the start group. The pass is the one `db<->flow` already runs; Flow stays the runtime and no library beyond Malli is added. Additive: `observe` and the wiring are T3b's. T3b's `observe` reads `:paused` and `:stopped` as `:held` while those values stay in the enum (T3b to T3c); that is the owner's fold (decision 7), not a fallback row. | **One exhaustiveness deftest**, `seon.cluster.agent-test/the-transition-table-is-exhaustive-deterministic-and-convergent`, over the 13,968 possible task states (§5.3), four assertions, each failing as the offending states in data: (1) every state matches exactly one row (no gap, no overlap); (2) every row's actions, with each answer the world can give (`effects`), lead only to possible states; (3) every state reaches a steady row without a cycle; (4) plan, apply (the test-local `closed` helper applies each action's closing fact to the observed value), re-plan answers `[]`. The branch table (16 combinations, 8 possible, 6 rows) is checked by the same deftest. Supporting examples, not the proof, in the existing table test `seon.cluster.agent-test/reconcile-closes-each-gap-once`: **parallel start** (two ready tasks, `max-running` 4 → two builds); **cap** (five ready, `max-running` 2, one open → one start; `max-running` 0 → nothing, running work untouched); **one branch** (two ready tasks on one branch → one start); **held** (`:held` → nothing); **dependency wait** (C needs A and B: nothing for C while either is not done where its attempts live); **no re-run** (an ended attempt awaiting its judgment starts no second attempt; a bailed task `:held` with a `:closed` attempt → nothing); **stale with work** (a stale branch with an ended `:done` attempt merges main forward, never rebuilds); **cancel after end** (closes as cancelled, no `:held`); **crash** (an open, unjudged attempt with no graph judges); **restart** (G5 `:stop`, then O3 `:close-environment`, then N2 to N6 fresh); and `seon.cluster.agent-test/an-impossible-task-state-is-refused-naming-its-constraint` (`reconcile` given a branch-less state with a config returns the input refusal naming `config-without-branch`). Red on HEAD because `transitions` and `reconcile` do not exist. |
| **T3a-agent** The agent's phase table (60 min, Opus; after T3a, beside T3b) | the agent's turn proc in `src/seon/turn.clj` and its schema `resources/seon/schemas/seon.turn.work.edn` (the one existing phase enum is `:seon.turn.work/situation`: `:resume :call :open :close :prove`); the narrow check names the final file list | The agent's attempt phases as a phase × event table in its Flow proc's state map, `{:fsm/phase …}` (AGENTS.md "An agent's state lives in its Flow proc and SCI context"; the cluster level holds no machine state, `transitions.md` §7.4). Its transitions are edge-triggered (a model reply, an eval done, tests reported, a wake, and the proc's `::flow/pause`, `::flow/resume`, `::flow/stop`), so the dimensions are phase and event as Malli enums, and each row is `{:when {:phase … :event …} :then [ops] :next phase}`. `transform` looks the row up; a missing pair is the typed error map, never a default. The proc's own `::flow/pause`, `::flow/resume` and `::flow/stop` are flow's states, mapped onto events of the same table, and its listeners are registered at start and unlistened at stop (§5.4); no lifecycle type is added. `:ping-map-fn` returns the phase, so `flow/ping` is how the reconciler's observed "graph running" can be read. The same enumeration check as T3a: every (phase, event) pair has exactly one row, and every row's `:next` is a phase. The phases and events are read from the turn loop at the start of the phase, not guessed here. | `seon.turn-test/every-phase-and-event-has-exactly-one-row` (names to be confirmed by the phase): red on HEAD because the table does not exist. |
| **T3b** One path (120 min, Opus; restart batch; after P4b and P7) | `src/seon/cluster/agent.clj`, `src/seon/cluster/message.clj` (`undeliverable` reads the agent's task execution directly, no `ready-rules`), `src/seon/cluster/boot.clj` (the branch route's release), `src/seon/task.clj`, `src/seon/merge.clj`, `src/seon/cluster/source.clj` (prepare over several needs; the target judged as the last task), `src/seon/context.clj`, `src/my/root.clj`, `src/seon/step/prove.clj`, `src/seon/step/design.clj` (docstring), `src/seon/test/runner.clj` (line 1398 reads next proofs; collides with P6 and P11, so a patch if they hold it), `src/my/program.clj` (its `next` reads), `script/seon/agent_cost.clj`, `script/seon/vocabulary.edn` (the `seon.task/ready` row), `resources/seon/tasks/seon.step.merge.edn` and the 13 other filed task files naming a deleted symbol (38 references, found by `rg` over the deleted names; a reference to a deleted symbol is converted or the task retired in the same commit), `test/seon/cluster/db_flow_test.clj`, `test/seon/cluster/merge_test.clj`, `test/seon/merge_test.clj`, `test/seon/task_test.clj`, `test/my/root_test.clj`, `test/my/task_test.clj`, `test/seon/context_test.clj`, `test/seon/merge_forward_test.clj`, `test/seon/cluster/merge_scenarios_test.clj`, `test/seon/cluster/write_back_test.clj`, `test/seon/proof_reuse_test.clj`, `test/seon/render/agent_world_test.clj`, `test/seon/test_support.clj`, `test/seon/step_test.clj`, `test/seon/step/prove_test.clj` | `run-pass!` = observe, reconcile, apply. `chain!` → `create-all!`. Every reader of `next` converts to `needs`: a task's next proofs are the proofs of the tasks that need it and share its branch. The build rule and the done step. The per-assignment path, the chain functions, `ready` and the merge-step append are deleted (§9). Kept until T3c: the chains page and its helpers, the `next` attribute. Kept until S3e: `children`, `effective`, the `execution` rule. | `seon.cluster.db-flow-test/a-merge-task-with-no-conflict-is-done-at-start-with-no-model-call` (the merge task's attempt is `:merged` on main; main holds the change by digest; exactly one transaction was written on main for it; zero model calls; no `graph-process` was ever asserted for it); `seon.cluster.db-flow-test/a-conflicting-merge-task-starts-an-agent-on-its-merge-branch` (main and the work both changed one function: the merge branch holds main's version of it by digest and one unmerged entry naming it; exactly one graph starts there with the merge template's instructions; main is unchanged); `seon.cluster.db-flow-test/a-task-with-no-target-is-done-on-green-proofs-and-writes-nothing-to-main` (outcome `:done` on its branch; main gains no declaration and no merge commit); `seon.cluster.db-flow-test/a-held-task-starts-nothing-until-root-releases-it` (a task written by `create-all!` is `:held` and a pass starts nothing; `my.root/run!` then one pass opens its attempt); `seon.cluster.db-flow-test/the-scheduler-never-starts-more-than-max-running` (three released independent tasks, `max-running` 2 applied through config: two attempts open after the pass, the third after one ends); `seon.cluster.db-flow-test/two-independent-tasks-start-in-one-pass` (two tasks released together; one pass: two attempts on two branches); `seon.cluster.db-flow-test/a-task-needing-two-parallel-tasks-starts-after-both-and-sees-their-work` (C's attempt exists only after A and B are done; C's branch head holds A's and B's definitions by digest); `seon.cluster.db-flow-test/a-task-and-its-merge-task-end-merged-and-published-with-no-hand-act` (replaces the three red tests). The existing `a-three-task-chain-ends-merged-with-no-hand-act` (green on HEAD) is rewritten over `needs` with a fourth, merge task. Every T3b test releases its tasks with `my.root/run!`, since nothing autoruns. Red on HEAD because none of these tasks, `create-all!` or the held semantics exist. **Also in T3b (the transition table's gaps):** the bail commits `:held` on main before it closes the branch (gap 12); `:sent-back` bails to root and `seon.task/end-kinds` goes in the same commit (gap 14); `launch-facts` and its `:seon.flow.actions/cluster` reader go (gap 15); `follow`, `launch-actions`' `:paused` and `:stopped` arms, `merge-tx`'s `:stopped` writes and the `:stopped` fixtures convert to `:held` (owner decision 7); `observe` reads progress from each task's branch (decision 6). Further red proofs: `seon.cluster.db-flow-test/a-bail-commits-held-on-main-before-closing-the-branch` (the closing commit is absent and the state reconciles to a second bail, never to a start); `seon.cluster.db-flow-test/a-sent-back-attempt-bails-to-root-and-never-merges`; `seon.cluster.db-flow-test/a-stale-branch-with-an-ended-attempt-merges-main-forward-and-keeps-its-work`. |
| **T3c** Views, claims, schema, miner (60 min, Sonnet; same restart batch) | `src/seon/task.clj` (views, `next` schema users), `src/seon/render/web.clj`, `src/seon/cluster/registry.clj`, `src/seon/cluster/boot.clj` (`task/chain-branches` for the operator's `--chains`, now the branches of tasks), `src/seon/mine.clj`, `resources/seon/schemas/seon.chain.edn` (deleted), `seon.task.edn` (`next` deleted), `seon.flow.actions.edn`, `my.root.edn`, and the schema descriptions naming deleted symbols (`seon.context.edn`, `seon.merge.edn`, `seon.source.edn`, `seon.squash.edn`, `seon.config.agent.edn`), `docs/seon/plan.md`, `docs/seon/specs/task-steps.md`, `docs/seon/specs/agent-loop.md`, `docs/seon/specs/merge.md`, `docs/seon/schedule/cards/KEEP-BRANCHES.md`, `.agents/skills/repl/SKILL.md`, `.agents/skills/seon-context-config/SKILL.md`, `test/seon/render/web_test.clj`, `test/seon/cluster/registry_test.clj`, `test/seon/mine_test.clj`, `test/seon/competency.clj`, `test/seon/competency_grade_test.clj` | The task-graph page; the claims clause; the schema deleted; **no norm** (owner decision 4); `seon.mine` reads the task model: `kept-branches` lists the branches of tasks (`:seon.task/branch` on main's tasks) and of attempt rows, its docstrings say task branch, and its long test runs a task graph instead of a chain; after the restart root re-mines whatever branches exist (`seon.mine/kept-situations`). | `seon.cluster.registry-test/a-task-branch-survives-retirement` (renamed from `a-chain-fact-keeps-its-branch-and-base-through-retirement`: a branch named only by a held task's `:seon.task/branch` survives `retire-unclaimed!`; green on HEAD as the chain-fact test, so the rename's proof is the changed fixture); `seon.mine-test/kept-branches-lists-a-held-tasks-branch-with-no-chain-fact` (main holds a task naming branch b and no chain fact, the store holds b, `kept-branches` lists b; red on HEAD because `kept-branches` reads only `:seon.chain/branch` and attempt rows). **Also in T3c:** `:paused` and `:stopped` leave the `:seon.task/execution` enum in `seon.task.edn` and its description (owner decision 7), and the `:seon.flow.actions/cluster` member leaves `seon.flow.actions.edn` with its two test sites (gap 15); the `seon.config.agent.edn` `max-running` description drops "or paused". |
| **T4** Environment restart (60 min, Opus; after T3b) | `src/seon/cluster/agent.clj` (the restart applier, the completeness gap), `resources/seon/schemas/seon.agent.edn` (`:seon.agent/environment`), `resources/seon/schemas/seon.task.edn` (`:seon.task/environment`, if T3a left it), `test/seon/cluster/db_flow_test.clj` | Rows N5, G5 and O3: an agent starts only when its task's desired environment is complete; an environment difference stops the graph, closes the attempt and starts a fresh agent. The agent row records its observed environment at acquisition. `my.task/update!` stays closed to `config` and `model`. | `seon.cluster.db-flow-test/an-environment-change-on-a-running-task-restarts-its-agent-on-the-new-environment`: a running task's agent on branch b; root writes the task's branch b2; one pass later `graph-process` for the old agent is absent, attempt 1 is `:closed` "environment changed", and a second pass opens attempt 2 under a new agent id on b2 with the agent row naming b2; a second row: a config change (the turn cap) restarts the same way and the new agent reads it. `seon.cluster.db-flow-test/a-task-with-an-incomplete-environment-starts-no-agent-and-reports-the-gap` (a released task whose decided config lacks a dial the turn loop reads: no attempt, no context, no graph; a problem task names the missing member and the task is `:held`). Red on HEAD because neither the restart nor the gap row exists. |

Census: P13's `seon.guard-census-test/a-changed-function-holds-no-guard` applies to every T phase's kept code.

### Kept chain branches: no norm (owner decision 4)

The four live chains hold their tasks and attempts on their branches, not main, and only their chain facts name them.
Nothing converts them. When T3c deletes the chain clause, the next boot's `retire-unclaimed!` deletes those four
branches, and default is reset at the cutover anyway. The one kept branch main's attempt row names
(`chain-fddd9dcbfe66`) stays claimed. The miner's corpus is not preserved: `seon.mine` is updated in T3c to read the
task model (`:seon.task/branch` on tasks, attempt rows on a task's branch), and root re-mines what exists after the
restart. `seon.mine/situations` already reads one branch's attempt rows, so it needs no change. Root may mine the four
chain branches with HEAD's miner before the restart if their situations are wanted.


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
| 3 | **T0** words and cards | task model | the owner has answered (owner decisions) | docs |
| 4 | **P5** red is red | kept | Required by the merge task: "green at start" must mean every member green on S, with no red-at-base excuse. | `source.clj`, `context.clj`, `turn.clj`, `test.clj` |
| 5 | **P6** one test request per step | **changed** | Its one request is also the start step (N6): `test-step` with no evaluated form, run by root before any graph. T3b calls it from the reconciler's applier. | `turn.clj` |
| 6 | **T1** the writer | task model | beside P5 and P6 (`task.clj`, task schema) | `task.clj` |
| 7 | **T2** graph process as a fact | task model | beside (`start-flow!`, `stop-flow!`, boot recovery) | `agent.clj` (flow functions only), `cluster.clj` |
| 8 | **T3a** the pure reconciler and its exhaustive table | task model | additive, beside | `agent.clj` (new functions), `seon.flow.actions.edn`, `seon.config.agent.edn`, `agent_test.clj` |
| 8a | **T3a-agent** the agent's phase table | task model | after T3a, beside T3b; its files are named by its narrow check | `turn.clj`, `seon.turn.work.edn` |
| 9 | **P7** one file proof, consumed thrice | kept | Before T3b, which moves `prepare-merge!`'s build and calls `accept-merge!` directly (`source.clj`, `merge.clj` collide). | `source.clj`, `merge.clj` |
| 10 | **T3b** one path | task model; **absorbs** TASK-CHAIN R-P3–R-P6, S3c-2 (with T2), S3e's `children` deletion, and P4a's `seon.merge/merge!` entrance | restart batch with T3c | §10 |
| 11 | **T3c** views, claims, schema, miner | task model | same restart batch; updates `seon.mine`, no norm (owner decision 4) | §10 |
| 12 | **T4** environment restart | task model | after T3b | `agent.clj`, schemas |
| 13 | **P8** no automatic JVM boots | **changed** | A host-bound change or a member the one JVM must not run is work left at the start step, or a done-rule refusal at A4 to A6. The task bails to root by A2 or A6. There is no `:seon.source/deferred` branch in any path. After T3c, because `agent.clj` collides. | `turn.clj`, `cluster.clj`, `source.clj`, `context.clj`, `agent.clj`, `test.clj`, `test/platform.clj` |
| 14 | **P9a** convert the callers of the retired verbs | kept | | `bin/seon-hook`, tests, scripts |
| 15 | **P9b** one verb, help, skill, offers | kept | help also gains §6.3's section and §6.1's restart in one sentence each | `my/test.clj`, help, skill |
| 16 | **P11a–d** runner | kept | independent of the task model | `seon.test`, `seon.test.runner` |
| 17 | **P12** `merge-changes`, `agent-owned` | kept | after its producer task, independent | `source.clj`, `context.clj` |
| — | **P13** the error law per phase | kept, and applied to every T phase (§8a) | the census already landed with P4a | every phase's files |
| — | **MERGE-STEP S6 B–E** | **moot** | The merge task is written with the work, and its conflict case is its own agent (§6.2). There is no append. | — |
| — | merge-hybrid §3.1 rows naming `seon.cluster.agent/merge-chains!`, `refusals-verdict`, `merge-step` as "stays" | **moot** | deleted by T3b (§5, §9) | — |
| — | the merge test list that "stays green through all phases" | **changed** | Their chain fixtures convert to `create-all!` with a merge task in T3b. `a-refused-final-merge-keeps-its-merge-branch-and-holds-a-merge-step`, `a-done-merge-step-merges-its-branch-onto-main` and `a-merge-step-green-at-its-start-ends-done-with-no-model-call` become T3b's two merge-task proofs. `a-requested-green-end-merges-and-publishes-with-no-hand-act` is retired (§9). | tests |

Parallel lanes, by file collisions:
- P10 runs now.
- T1, T2 and T3a run beside P5 and P6. `context.clj` belongs to P10 then P5. `turn.clj` belongs to P6.
- P7 runs before T3b.
- T3b and T3c land in one restart batch, then T4, then P8.
- P9a, P9b, P11 and P12 follow as `merge-hybrid.md` orders them.

## 12. Open questions, answered

The owner answered these (the last section before the narrow check), and the answers are applied in place above.

| # | Question | Answer |
|---|---|---|
| 1 | The edge | `:seon.task/needs`, a set, replaces `:seon.task/next` |
| 2 | Filed tasks autorun? | No. Nothing autoruns: written and ingested tasks are `:held`; `max-running` from config caps the scheduler |
| 3 | Tasks on main before their work merges | Accepted |
| 4 | `:seon.task/target` | Accepted (T0 notes it is not `:seon.task.attempt/target`) |
| 5 | Kept chain branches | No norm; default may reset; `seon.mine` re-mines under the new model |
| 6 | Abandon | Cancels the named tasks and every task that needs them |
| 7 | Approval | Waits until the owner wants it |
| 8 | Plan flow | Along `needs` |
| 9 | Resume verb | `my.root/run!` is the one release (start and resume) |
| 10 | Swap boundary | A clean restart: stop the graph, close the attempt, start a fresh agent once the desired environment is complete |


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

### Later the same day (2026-10-17), after `transitions.md`

6. **A task's progress, its done included, is observed state on the task's own branch,** because agents write only
   their branch. The reconciler reads desired state from main and observed state from each task's branch
   (`seon.db/branch-database`, cached by commit, one read per task). Branches are never deleted by default; compaction is
   an explicit act that records what the reconciler still needs before the branch goes. This resolves gap 11 without
   moving data (§3.2).
7. **`:paused` and `:stopped` fold into `:held`.** Their rows and enum values are removed in the phase that converts
   their readers (readers T3b, enum values T3c; §9). `transitions.md` §4.3 proved `:stopped` answers exactly as `:held`;
   `:paused` is the owner's fold, so a flow-paused graph under `:held` is stopped (G2) and graph state is binary.
   This resolves gap 6.
8. **`:seon.config.agent/max-running` may be 0:** running work finishes and nothing new starts, and that holds across
   restarts. Flow's pause of `db<->flow` stays the instant off (§7).
9. **Every long-lived process is a flow proc, with no new lifecycle types.** Listeners are registered at init or start
   and unlistened at stop; the reconciler holds one on main plus one per watched task branch, derived from the task
   facts (§5.4).


## Narrow check (lane task-model-check, 2026-10-17)

Method. Live claims are read-only forms in JVM mode on default (`seon.cluster.boot/connection "default"`, state
`:faulted` with the faults it had before this lane; the commit it runs was not checked and may trail HEAD), so counts of datoms are live and
counts of code are from HEAD's files (2ce235df8 at the start; other lanes committed beside it). No SCI request was made
as root. Line spans are top-level-form spans by an awk script over `^(` boundaries; reader lists are the top-level form
containing the token. Proofs: `bin/seon check --at HEAD --include-long` over eight named tests. Owner decisions 1 to 5
are applied in place above: `needs` replaces `next`; every task is `:held` and `max-running` caps the scheduler; T4 is a
clean restart that starts an agent only once its desired environment is complete; no norm and `seon.mine` is updated.

### Claims

| # | Claim | Form, value, time | Verdict |
|---|---|---|---|
| 1 | 4 chain facts, none with `merge? false` | `(d/q [:find ?e :where [?e :seon.chain/branch]])` 4; `:seon.chain/first` 4; `:seon.chain/merge?` values `#{}`; the four hold only `branch` and `first`; 8.6 ms (request 13 ms) | reproduced; "none with `merge? false`" is vacuous, none stores `merge?`, `config`, `from` or `state` |
| 2 | 962 tasks on main, 961 `:held`, 1 `:stopped`, 13 with `needs` | `:seon.task/ns` 972 entities; `:seon.task/execution` 962 (`{:held 961 :stopped 1}`); `:seon.task/needs` 13 entities, 14 links | partial, fixed in §2: 972 task entities, 962 with an execution; 10 have `children` |
| 3 | 15 store branches, 5 kept, all "named by main's attempt rows" | `datahike.api/branches` 15 at the first read (17 names later, agent branches created meanwhile); `seon.mine/kept-branches` `[:chain-42e0e4c46b71 :chain-91a8e6815ef2 :chain-ce9d664c1be0 :chain-fd3ba8395135 :chain-fddd9dcbfe66]`; main's attempt rows name `[:chain-fddd9dcbfe66]` only; `:seon.task/branch` on tasks: none | **refuted**: four of five are named only by chain facts, so retracting them deletes those four at boot; fixed in "Kept chain branches" (owner decision 4: no norm) |
| 4 | `:seon.chain/*` use counts (205 `branch`, 34 `first`, ...) | exact-token count: 191 `branch`, 35 `first`, 23 `config`, 15 `merge?`, 13 `attempts`, 12 `state`, 9 `tasks`, 7 `kind`, 10 `from`; 282 in all, 31 files | partial (within 7%), fixed in §2 |
| 5 | 47 functions read `:seon.chain/branch` | 50 top-level forms in `src` contain it (59 for any `:seon.chain/` key); the page, `chain!`, `chain-*`, `my.root` and `seon.cluster.agent` families; plus `runnable`, `branch-attempts`, `converge-config!`, `judged-input`, `cluster-claims`, `kept-branches` | partial: three more, none a new family |
| 6 | Readers of `:seon.chain/config` | `config-tx`, `chain!`, `chain-refusal`, `desired-config` (all `seon.task`) | reproduced |
| 7 | Readers of `:seon.chain/merge?`, `from`, `attempts`, `state`, `tasks`, `kind` | `merge?`: `chain!`, `chain-finishes`; `from`: `chain!`, `bail-chain!`; the other four only the chains page (`chain-view`, `chain-html`, `chain-task-html`, `chain-state`, `finished?`, `bailed?`, `chain-fragment`, `chains-page-result`) | reproduced |
| 8 | Readers of `:seon.task/next` | `seon.context/judged-input`, `/chain`; `seon.task/chain-branch`, `chain-view`, `chain-entities`, `chain!`, `next-entity`, `task-rows`, `chain-attempts`, `chain-handles`, `identified`, `advance-tx`, `next-tx`, `resolution`; `seon.cluster.agent/chain-read`, `chain-finishes`, `chain-ends`; **`seon.test.runner/task-identity`**, **`seon.step.prove/handoff`**, **`my.program/task-source`** (`runner.clj` was in no phase's file list); `:seon.task/next` datoms on main: 0 | partial: fixed in §9 and T3b's file list |
| 9 | `launch-facts` 36, `chain-read` 50, `chain-facts` 26, `chain-ends` 28, `chain-finishes` 35, `chain-bails` 26, `lifecycle-facts` 58, `taskless-agents` 27, `branch-admission-end` 16, `basis-valid?` 18, `registry-facts` 10, `ready`+rules 62 = 392 | spans: identical; `seon.task` group 18+22+14+8 = 62 | reproduced |
| 10 | second group 143, appliers 406, `run-pass!` 50 | 143 (45, 17, 37, 11, 11, 12, 10); appliers 404 (`start-chain-attempt!` 27, not 29); `run-pass!` 50; total 989 against 991 | reproduced (±2) |
| 11 | `seon.task/chain!` 66, `chain-entities` (22 in the table's sum), `hold!` 20, `end-chain!` 16, `abandon!` 26, `run-held!` 29, `advance-tx` 24, `ready` 22, chain views 27/15/12/17/18/7/27/10/8 | spans identical except `chain-html` 17 (plan 16) | reproduced |
| 12 | `seon.task/ready-rules` exists | yes: rules `open-attempt`, `execution` (own, else an ancestor's through `children`), `leaf-done` (a `:merged` attempt with no later send-back), `under`, `self-or-under`, `needs*` (also climbs `children`), `blocked`. Readers beyond `ready`: `done?`, `execution`, `resolution`, `seon.cluster.agent/launch-facts` (lines 1876 to 1879), **`seon.cluster.message/undeliverable` (line 884)** | reproduced; the extra readers and the `children` dependency fixed in §9 and T3b |
| 13 | `seon.task/ready` warm 1.33 ms; the needs query 1.16 ms; `branch-database` 0.45 to 0.7 ms | `(seon.task/ready db)` 0.74 ms mean of 20, answers `#{}`; needs query 0.002 ms (cached); `seon.db/branch-database` on a chain branch 0.58 ms | reproduced (cheaper) |
| 14 | `:seon.config.agent/max-running` bounds launches today | `(:seon.config.agent/max-running (seon.config/effective db "default"))` = 4; `launch-actions` takes `(- max-running counted)` fresh attempts, where `counted` is the open attempts whose execution is `:running`, `:paused` or `:held`; `run-pass!` reads it from `config/effective` each pass | reproduced; §5 now states the same formula |
| 15 | Unmerged entries reach the block only inside the conflict refusal | `seon.context` never names `unmerged` or `conflict` (no hit); `step-section` shows `:seon.context/refusals` = `shown-refusal` of `done-refusals`, which includes `seon.cluster.source/unmerged-refusal` carrying `:seon.unmerged/entries`, each with `versions` (base, ours, theirs); `shown-refusal` drops only `:seon.source/red-tests`; the message names `my.program/resolve!` in prose | reproduced; the entries and versions are present today as one opaque map, with no per-entry resolve form, so §6.3 stands |
| 16 | `seon.task/create!` is the existing idempotent writer and takes a vector | `create!` exists, takes one request map `{:seon.db/connection :seon.agent/id :seon.task/ns :seon.task/name ...}`, refuses `:needs-cycle`, and has 73 callers in 27 files | **refuted** as "`create!` over a vector": the vector writer is a new function, `seon.task/create-all!` (fixed in §8, §10 T1) |
| 17 | `:seon.task/runnable`, `desired-config`, `converge-config!`, `end-branch!`, `cluster-claims`' `:seon.task/branch` clause exist | all read; `runnable-view` adds `:seon.chain/branch`; `:seon.task/config`, `:seon.task/target`, `:seon.agent/graph-process`, `:seon.agent/environment` have no datoms (new) | reproduced |
| 18 | `registry/fork!` records `:seon.branch/target` | `seon.cluster` writes `:seon.branch/target` at fork (line 2787); `seon.task` at 1195; `seon.cluster.source/merge-base` reads it (line 790) | reproduced |
| 19 | `needs` "is retired in vocabulary.md" | `vocabulary.md` does not list it; TASK-CHAIN §1 lists `needs` among the deleted attributes and S3e deletes it | **refuted** location; fixed in §4 and T0 |
| 20 | `seon.step.merge/template` | no namespace file: a task named `template` in `resources/seon/tasks/seon.step.merge.edn`, read by `seon.task/template` | reproduced as a task symbol; its text still cites `:seon.task/refusal` and `refused-tests`, so T3b rewrites it |
| 21 | The two "never green" tests and the per-assignment twin in `db_flow_test` | `bin/seon check --at HEAD --include-long`, run 634a55f41dd7, 8 executed, 3 red, 19.9 s: red are `seon.cluster.merge-test/a-requested-green-end-merges-and-publishes-with-no-hand-act` (error at merge_test.clj:148, main never records `:merged`), `.../a-done-branch-merges-with-no-merge-request-written-by-the-agent` (merge_test.clj:531 to 533) and `seon.cluster.db-flow-test/a-requested-end-whose-files-no-checkout-holds-merges-nothing` (db_flow_test.clj:1632) | reproduced; the twin's name was wrong (the first attempt named `.../a-requested-green-end-merges-and-publishes-with-no-hand-act`, which has no definition), fixed in §9 |
| 22 | Tests that stay green | same run, green: `db-flow-test/a-three-task-chain-ends-merged-with-no-hand-act`, `registry-test/a-chain-fact-keeps-its-branch-and-base-through-retirement`, `guard-census-test/a-changed-function-holds-no-guard`, `db-flow-test/a-merge-step-green-at-its-start-ends-done-with-no-model-call`, `.../a-done-merge-step-merges-its-branch-onto-main` (red in merge-hybrid §8.1 at an older HEAD, green now) | reproduced |

### Deletion order: callers outside the converting phase, and the fix

| Symbol | Live caller outside its phase | Fix applied |
|---|---|---|
| `:seon.task/next` attribute (T3b) | the chains page reads it (`chain-view`, `chain-views`, `chain-handles`, `chain-attempts`) until T3c deletes the page; also `seon.test.runner`, `seon.step.prove`, `my.program`, nine tests, five resource files, four docs | T3b converts every reader but the page's; the attribute is deleted in T3c with the page; runner, prove, program, `test_support`, `step_test`, `prove_test`, `my/task_test` added to T3b |
| `ready`, `blocked`, `needs*` (T3b) | `done?`, `resolution`, `execution` and `seon.cluster.message/undeliverable` use `ready-rules`; `children` and `effective` have live readers (10 `children` datoms) | T3b deletes `ready`, `blocked`, `needs*` only and converts `undeliverable`; the `ready-rules` var, `done?`, `resolution`, `effective`, `goal-owner` stay for S3e, which the task model does not absorb |
| `chain-handles`, `chain-attempts`, `chain-branches` (T3b) | the chains page and `seon.cluster.boot` (line 770) | move to T3c with the page; boot converted in T3c |
| `release-chain-routes!`, `route-chain(s)!` (T3b) | `seon.cluster.boot` (lines 563 and 597) | `boot.clj` added to T3b |
| `seon.task/chain-entities` (T3b) | `script/seon/agent_cost.clj`, `test/seon/proof_reuse_test.clj`, `test/seon/context_test.clj`, `test/seon/cluster/write_back_test.clj` | added to T3b; they build tasks with `create-all!` |
| `seon.merge/merge!` entrance, `assignment-row`, `chain-row` (T3b) | `test/seon/merge_test.clj`, `test/seon/cluster/registry_test.clj`, `write_back_test.clj`, `agent_world_test.clj`, `my.root`, `seon.task`, `seon.cluster.source` (docstring), `.agents/skills/repl/SKILL.md`, ten filed task files and four schema descriptions | added to T3b (tests, tasks) and T3c (schemas, docs, skills) |
| `seon.task/chain!` (T3b) | 17 files in `test` (db_flow 27 uses, task_test 15, competency 5, merge_forward 5), `seon.step.design` (docstring), `seon.cluster.agent`, `seon.context`, `seon.render.web`, `my.root` | all in T3b or T3c's lists; `competency*`, `web_test` in T3c |
| `:seon.chain/*` schema (T3c) | `seon.task.edn` (`runnable`), `seon.flow.actions.edn`, `my.root.edn`; the request key `:seon.chain/branch` of `my.root/abandon!`, `merge-main!` | T3b renames the verb keys to `:seon.task/branch`; T3c deletes the schema |
| `seon.mine/kept-branches`' chain query (T3c) | `test/seon/mine_test.clj` long test (a chain run and `release-chain!`), `competency_grade_test` | in T3c with the miner update |
| `seon.cluster.registry/cluster-claims`' chain clause (T3c) | only the four live chain branches | owner decision 4: default may reset; no norm |
| `seon.task/ready` row in `script/seon/vocabulary.edn` (CN-27) | the script's `:use` text names the deleted function | added to T3b |

### Proof status per phase

| Phase | Named proof | Status on HEAD |
|---|---|---|
| T0 | none (docs) | docs only; the two cards and vocabulary exist |
| T1 | `seon.task-test/a-task-write-resolves-every-tasks-branch`, `.../a-filed-task-becomes-four-linked-tasks-ending-in-a-merge-task` | new; red because `create-all!` is absent. The branch rule is clear for a linear run, a fan-out and a merge task (fix: a task with a target never counts as a dependent, so design, prove and implement keep one branch when the merge task needs two of them) |
| T2 | `seon.cluster.agent-test/a-started-graph-is-observed-until-it-exits` | new; red because `:seon.agent/graph-process` is absent; `start-flow!` (agent.clj:1299), `stop-flow!` (1555) and `recover-runs!` (cluster.clj:3065) exist |
| T3a | `seon.cluster.agent-test/the-transition-table-is-exhaustive-deterministic-and-convergent` (supersedes `reconcile-closes-each-gap-once` as the proof; that table test stays as examples) | new; red because `transitions` and `reconcile` are absent. The completed table matches 13,968 of 13,968 folded states once (29,760 of 29,760 unfolded) with 0 invalid successors and 0 cycles in the prototype |
| T3b | `db-flow-test/a-merge-task-with-no-conflict-is-done-at-start-with-no-model-call`, `.../a-conflicting-merge-task-starts-an-agent-on-its-merge-branch`, `.../a-task-with-no-target-is-done-on-green-proofs-and-writes-nothing-to-main`, `.../a-held-task-starts-nothing-until-root-releases-it` (new), `.../the-scheduler-never-starts-more-than-max-running` (new), `.../two-independent-tasks-start-in-one-pass`, `.../a-task-needing-two-parallel-tasks-starts-after-both-and-sees-their-work`, `.../a-task-and-its-merge-task-end-merged-and-published-with-no-hand-act`; `a-three-task-chain-ends-merged-with-no-hand-act` rewritten | new except the last; red because `create-all!`, held semantics and the task-keyed path are absent. The old three reds (claim 21) are retired, not repaired. `a-three-task-chain...` is green on HEAD, so its proof is the rewritten fixture |
| T3c | `registry-test/a-task-branch-survives-retirement` (renamed), `mine-test/kept-branches-lists-a-held-tasks-branch-with-no-chain-fact` (new, replaces the dropped norm test) | the rename is green on HEAD as the chain-fact test, so the changed fixture is its red; the miner test is red because `kept-branches` reads only the chain fact and attempt rows |
| T3a-agent | `seon.turn-test/every-phase-and-event-has-exactly-one-row` | new; red because the table is absent |
| T4 | `db-flow-test/an-environment-change-on-a-running-task-restarts-its-agent-on-the-new-environment`, `.../a-task-with-an-incomplete-environment-starts-no-agent-and-reports-the-gap` | new; red because neither the restart nor the gap row exists |
| P10 | `seon.context-test/a-merge-branchs-unmerged-entry-shows-in-the-block-until-resolved` | new; red because `seon.context` never names `unmerged` (claim 15) |

### Fixes applied in the sections above

- **Owner decisions.** §1, §3.1, §4, §5, §7, §8 and §12 now say tasks are written `:held`, `my.root/run!` is the one
  release, and `max-running` caps starts; §6.1, rows N5, N6, G5 and O3, and T4 describe the clean restart with the
  completeness precondition; T3c drops the norm and updates `seon.mine`; §11 rows follow.
- **T1.** The vector writer is `seon.task/create-all!`; `create!` (73 callers) is untouched; the branch rule ignores
  targeted dependents.
- **T3a.** Rows added for the cap, held, incomplete environment and restart, plus the `closed` helper.
- **T3b.** Keeps the chains page, `next` and the `ready-rules` var; adds `message.clj`, `boot.clj`, `runner.clj`
  (collides with P6 and P11: a patch if they hold it), `agent_cost.clj`, `vocabulary.edn`, 14 task files, and nine more
  tests; adds the held and cap proofs; defines next proofs over same-branch dependents.
- **T3c.** No norm; `seon.mine` reads task branches; schemas, docs and skills swept.
- **T4.** Restart, not swap; `my.task/update!` stays closed to `config` and `model`.
- **S3e.** The task model does not absorb `children`, `effective` or `goal-owner`; TASK-CHAIN §1 and S3e are amended in
  T0 so that `needs` is kept.

### Findings outside this lane

- A contract refusal on a mistyped database argument cost 852 ms and 1.36 GB (§13, already recorded). This lane saw
  the same producer again: a JVM-mode query over a held Datahike value costs 1.4 GB of allocation in a 258 ms request
  (`(d/q ... [?x :seon.chain/branch ?b] [?x :seon.chain/first ?f] [?x :seon.chain/id ?c])` and an unbounded
  `[?x ?a _]` attribute scan); this was a probe of mine, not product code, and is noted only as a cost to avoid.
- Default is `:faulted` (five open faults, `seon.render.web/feed` await-bound among them) before this lane; root files it.
