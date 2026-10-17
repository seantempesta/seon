---
title: "Transitions: the reconciler's states and actions as one checked table"
type: research
status: "research and REPL prototype by lane transitions-research (Opus), 2026-10-17, for the owner's question of 2026-10-17 on accounting for every db<->flow transition. Input to task-model.md's T0 and T3a. Changes no source. The prototype ran in a standalone throwaway JVM (malli at reference-code/malli dd13ff81) and touched neither default nor the store."
---

# Transitions: the reconciler's states and actions as one checked table (2026-10-17)

**The owner's question:** how do we account for every transition `db<->flow` must handle, so that every combination is
covered and an uncovered one is loud?

**Answer.** Use a plain decision table:
- Each dimension of the task's desired and observed state is a Malli enum.
- Each impossible combination is a named constraint with its reason.
- Each row is a map from dimensions to allowed values, with its actions. A steady state is also a row, with no actions.

One test enumerates every possible state. It checks three things:
- exactly one row matches each state;
- applying each row's actions leads only to possible states;
- every state reaches a steady row without cycling.

The whole check runs in about 0.7 s on the real dimensions. Matching costs 1.6 ms per 1,000 tasks. No library beyond
Malli is needed:
- core.match, core.logic and statecharts all lack an exhaustiveness check;
- they would each add a dependency that does less than this.

**What it found in task-model.md §5's gap table**, encoded as written:

| Count | What |
|---|---|
| 29,760 | possible states (out of a product of 2,099,520) |
| 10,424 | states matched by exactly one row |
| 9,960 | states matched by no row: 6,240 unnamed steady states and 3,720 states that need an action |
| 9,376 | states matched by two or more rows |
| 72 | cycles (stop and start alternating without end) |
| 7,650 | states whose actions lead to an impossible state |

The completed table (§5) has 29 rows. It matches 29,760 of 29,760 states once each, with 0 invalid successors and 0
cycles.

Fifteen gaps are named in §6. The ones that matter most:
- **An ended attempt counts as "ready".** Starting a new attempt while the ended one is still being judged is an
  automatic re-run.
- **Rebuild destroys work.** It can delete a stale branch out from under an ended attempt's work or a running graph.
- **Execution `:held` makes rows 5 and 6 flap.**
- **`:paused` and `:stopped` have no rows.** The table also proves `:stopped` behaves exactly like `:held`.
- **A bail can un-cancel a cancelled task.**
- **Deleting a branch can erase a `:done`.** For a task with no target, it erases the only `:done` there is, so the
  task re-runs.

## 1. The state space, as data

Desired and observed state per task, after `seon.cluster.agent/observe` has read them. Each dimension is one Malli enum.
Three of them reuse a named schema that already exists:

| Dimension | Values | Source fact (task-model.md §3) | Existing schema |
|---|---|---|---|
| `execution` | `:held :running :paused :stopped :cancelled` | `:seon.task/execution` on main | `:seon.task/execution` |
| `needs` | `:none :pending :done` | each need done where its attempts live (§5 readiness) | new |
| `target` | `:none :main` | `:seon.task/target` | new attribute (T1) |
| `environment` | `:complete :incomplete` | `:seon.task/environment` validates the desired environment | new (T3a) |
| `branch` | `:absent :current :stale` | the branch exists; its recorded base H is (not) the target's head | new |
| `config` | `:none :converged :differs` | branch config row vs `:seon.task/desired-config` | new |
| `attempt` | `:none :unended` + the 7 ends | latest attempt row and `:seon.task.attempt/end` | `:seon.task.attempt/end` |
| `outcome` | `:none :done :merged :closed` | `:seon.task/outcome` of the latest attempt | `:seon.task/outcome` |
| `step` | `:none :unjudged :empty :work-left` | the latest `done-refusals` judgment at the head | new |
| `agent-env` | `:none :same :differs` | `:seon.agent/environment` (and branch) vs the desired environment | new (T4) |
| `graph` | `:absent :running :paused` | `:seon.agent/graph-process` (T2), plus Flow's own status for a paused graph | new |
| `publication` | `:none :due :done` | `:seon.task/git-commit` or `publication-refusal` after `:merged` | new |

Rows may also read two values derived from the base state, which are never stored:
- `latest` is one of `:none :open :awaiting :closed :settled`. It folds `attempt` and `outcome` into the attempt's
  phase: `:awaiting` means ended with no outcome, and `:settled` means `:done` or `:merged`.
- `wanted` is true when `execution` is `:running` and `needs` is not `:pending`.

**Counts.**

| | States | Note |
|---|---|---|
| Product of the twelve enums | 2,099,520 | |
| Impossible by construction | 2,049,600 | 16 named constraints |
| Impossible by an invariant | 20,160 | the table must keep it true: every outcome writer requires the graph absent |
| Possible | 29,760 | |

Enumeration backtracks over the dimensions. It prunes a partial state as soon as a constraint whose dimensions are all
assigned matches it, and visits the 2.1 M product in 95 ms.

**The impossible combinations** (constraints are data: `{:id :why :when {dim #{values}}}`):

| id | why |
|---|---|
| `config-without-branch`, `branch-without-config` | the branch holds the config row |
| `stale-without-target` | stale is measured against the target's head |
| `attempt-without-branch` | attempt rows live on the task's branch |
| `outcome-without-attempt`, `step-without-attempt`, `attempt-without-step` | the start step writes the attempt row, unjudged until it judges |
| `attempt-without-agent`, `graph-without-agent` | the start step acquires the attempt's context, which records its observed environment before any graph |
| `merged-without-target`, `done-with-target` | the done step writes `:merged` against a target, else `:done` |
| `settled-not-green` | the done step runs only on an empty judgment |
| `agent-without-attempt` | an agent row exists only for an attempt |
| `incomplete-but-same` | an agent starts only on a complete environment, so "same" implies complete |
| `publication-without-merge`, `merge-without-publication-state` | publication follows a merge |
| `graph-after-outcome` (**invariant**) | every outcome writer requires the graph absent; the closure check proves the table keeps it |

Three dimensions are deliberately absent, because the decision does not depend on them:
- **Unmerged entries present.** `seon.cluster.source/done-refusals` includes `unmerged-refusal`, so unmerged entries
  are already part of `step :work-left`, and present-and-`:empty` is impossible.
- **Proofs green, red or unknown.** These are `step`.
- **The cluster's intent, `:seon.flow.actions/cluster`.** `seon.cluster.agent/launch-facts` hardcodes it to `:running`,
  so it is a dead dial. On/off is Flow's pause (§7).

Branch-level state (gap rows 10 and 12) is a second table of four booleans:

| | States |
|---|---|
| Product | 16 |
| Possible | 8 |
| Matched by task-model's rows | 3 |
| Matched by no row | 5 (2 need an action) |
| Rows in the completed table | 6, 0 gaps |

## 2. Ways to express transitions, compared

| Shape | Exhaustiveness | Determinism (exactly one rule) | Readable as data | Cost per reconcile | Dependency | On our classpath |
|---|---|---|---|---|---|---|
| **core.match** 1.1.0 | **none at compile time.** `clojure.core.match/emit-matrix` appends a default clause throwing `IllegalArgumentException "No matching clause: …"` at run time (a TODO there says it should throw when `:else` is missing). `clojure.core.match/warn` is defined but never called. | first match wins: overlaps are silently decided by order | no: patterns live in a macro body | compiled decision tree, fast | small | **no** (a 1.1.0 jar is in `~/.m2`, not in `deps.edn`) |
| **core.logic** | could compute uncovered states as a relation, but "no row matches" is negation, which needs `nafc` (non-relational) or explicit enumeration over finite domains | must be written as a separate query | goals are code | the search engine on every pass, or the same enumeration we do anyway | medium: a second evaluation model for agents to read | **no** (not in `~/.m2`) |
| **Statecharts** (`fulcrologic/statecharts`, SCXML semantics) | **none.** SCXML: "If no transition matches in any state, the event is discarded", a silent no-op by spec | document order and an "optimal transition set" pick among conflicts, silently | yes: states and transitions are data | an event loop holding a current configuration | medium (core.async event loop) | **no** |
| `clj-statecharts` | not read; the same event-driven model | — | — | — | — | no |
| **Malli `:multi` alone** | over one dispatch value only: `m/explain` reports an invalid dispatch value | one branch per dispatch value | yes | negligible | none | **yes** |
| **Decision table + Malli enums** (recommended) | **exact.** The enumeration test lists every uncovered state | the same test lists every state with more than one row | yes: rows are EDN maps that can be rendered, queried and diffed | 1.6 ms per 1,000 tasks, naive and warm | none | **yes** (Malli) |

Two notes on the statecharts row:
- Statecharts model **edge-triggered** events against a configuration held in memory.
- The reconciler is **level-triggered** (the Kubernetes model the task model adopts). Every pass re-derives the state
  from facts, so a missed wake costs nothing.

Holding a statechart's configuration would be a second copy of observed state. The task model deletes exactly that.

## 3. Recommendation

**A decision table over Malli enums, checked by enumeration.** It has four parts:

1. **States are schemas.** Each dimension is a registered Malli enum (`:seon.flow.actions/needs`, …), reusing
   `:seon.task/execution`, `:seon.task/outcome` and `:seon.task.attempt/end`. The per-task state is one registered map
   schema, `:seon.flow.actions/task-state`. Its constraints are a registered named predicate, never an anonymous
   function. `observe` produces values of it, and `reconcile`'s contract validates them on entry. An impossible
   observed state is therefore refused by the contract, naming the constraint: data, not a guard.
2. **Rows are data.** `seon.cluster.agent/transitions` is a vector of `{:row :when :then}` maps.
   - `:when` maps a dimension to a value or a set of values.
   - `:then` is a vector of action ops, or `[]` with `:steady "name"`.
   - `:start? true` marks the start group: build, rebuild, converge, start step.
   - Rows are disjoint. There is no priority order, so a row's meaning never depends on its position.
3. **Actions are validated data.** One `:multi` on `:seon.flow.actions/op` declares each op's members: task, branch or
   agent. The prototype's `:multi` refuses `:launch` with an invalid dispatch value. Each effect owner applies its op.
4. **Global coupling is a fold, not rows.** These are not facts about one task:
   - the `max-running` cap;
   - oldest first;
   - one open attempt per branch.

   `seon.cluster.agent/admit` folds the start-group answers. The prototype returns `["t0"]` for five ready tasks with
   `max-running` 2 and one open attempt, and `["a"]` for two ready tasks on one branch. Both match T3a's rows.

**Multi-step paths are edges, not authored states.** Intermediate phases fall out of the table's closure:
- build → converge → start step → graph;
- stop → close "environment changed" → start step;
- stale → merge main forward → judge.

The closure check walks them. Each action's `effects` names the facts its actor writes and every answer the world
can give (a judgment of `:empty` or `:work-left`; a refused basis fence). It then proves two things:
- every successor is a possible state;
- with no external event, every path ends at a steady row.

An unmatched combination is loud at both times:
- **At test time**, the enumeration deftest is red and lists the states.
- **At run time**, it cannot happen for a valid state, because the test proved exactly one row matches each. An
  impossible observed state is refused by `reconcile`'s input contract, which names the constraint. It reaches
  db<->flow's one boundary as the one error map and is recorded. There is no fallback row, no `:else` and no `cond`
  default.

## 4. The prototype

It ran in a standalone throwaway JVM:
`clojure -Sdeps '{:deps {metosin/malli {:local/root "reference-code/malli"}}}' -M -i transitions.clj -m transitions`.
The full source is in the appendix.

| Measure | Value |
|---|---|
| Possible states | 29,760 |
| Enumerate | 95 ms |
| Check v1 and v2 | 172 ms |
| Closure of both | 383 ms |
| Matching, warm | 1.6 ms per 1,000 tasks |
| Process total | 1.5 s wall, JVM start included |

### 4.1 The completed table (v2): 29 rows, 0 gaps, 0 overlaps, closure clean

| Row | When (besides the group's condition) | Then | Replaces |
|---|---|---|---|
| **A hosted graph** | | | |
| G1 | graph running or paused, latest `:awaiting` | `:stop` | 6 |
| G2 | graph running or paused, open, execution `:held`, `:stopped` or `:cancelled` | `:stop` | 6 |
| G3 | graph running, open, execution `:paused` | `:pause` | today's `follow` |
| G4 | graph paused, open, execution `:paused` | steady "paused, hosted" | — |
| G5 | graph running or paused, open, `:running`, agent-env `:differs` | `:stop` (restart, first half) | 13 |
| G6 | graph running, open, `:running`, agent-env `:same`, branch `:current` | steady "working" | — |
| G7 | graph running, open, `:running`, agent-env `:same`, branch `:stale` | `:merge-main` | 1b, open half |
| G8 | graph paused, open, `:running`, agent-env `:same` | `:resume` | today's `follow` |
| **No graph, an open attempt** | | | |
| O1 | execution `:cancelled` | `:close-cancelled` | 11 |
| O2 | execution `:held`, `:stopped` or `:paused` | steady "held with its attempt open" | — |
| O3 | `:running`, agent-env `:differs` | `:close-environment` (restart, second half) | 13 |
| O4 | `:running`, agent-env none or same, branch `:stale` | `:merge-main` | 1b, open half |
| O5 | as O4, branch `:current`, step `:unjudged` | `:judge` | — (a crash between the attempt row and its judgment) |
| O6 | as O5, step `:empty` | `:done-step` | 3a |
| O7 | as O5, step `:work-left` | `:start-graph` | 3b and 5 |
| **No graph, ended, no outcome** | | | |
| A1 | execution `:cancelled` | `:close-cancelled` | — |
| A2 | not cancelled, end other than `:done` | `:bail` | 8 |
| A3 | not cancelled, end `:done`, branch `:stale` | `:merge-main` | — (1b would rebuild over the work) |
| A4 | not cancelled, end `:done`, `:current`, step `:unjudged` | `:judge` | 7 |
| A5 | … step `:empty` | `:done-step` | 7 |
| A6 | … step `:work-left` | `:bail` | 7, refused |
| **Settled** | | | |
| S1 | publication none or done | steady "done" | — |
| S2 | publication `:due` | `:publish` | 9 |
| **Nothing open** (latest `:none` or `:closed`) | | | |
| N1 | not wanted (not `:running`, or a need pending) | steady "not released, or waiting on needs" | — |
| N2 | wanted, branch `:absent` | `:build` (start group) | 1 |
| N3 | wanted, branch `:stale` | `:rebuild` (start group) | 1b, idle half |
| N4 | wanted, `:current`, config `:differs` | `:converge-config` (start group) | 2 |
| N5 | wanted, `:current`, converged, environment `:incomplete` | `:problem-hold` | 3c |
| N6 | wanted, `:current`, converged, environment `:complete` | `:start-step` (start group) | 3 |

Branch table (v2): six rows, all eight possible states covered, 0 overlaps:

| Row | When | Then | Replaces |
|---|---|---|---|
| B1 | active, route absent | `:listen` | 12 |
| B2 | active, route held | steady | — |
| B3 | not active, route held | `:release-route` | today's `release-chain-route!` |
| B4 | not active, route absent, finished, not ended | `:end-branch` | 10 |
| B5 | not active, route absent, finished, ended | steady (kept) | — |
| B6 | not active, route absent, unfinished | steady (held work waits) | — |

### 4.2 Closure

Without external events, v2 reaches a steady row from every possible state: 0 cycles and 0 invalid successors.

With the "main moved" answer of the done step's basis fence included, there are 78 cycles. All of them pass through
that answer:
- A3 → A4 → A5;
- O4 → O5 → O6.

These are livelock only under continuous writes to main, which is Kubernetes-style convergence. Progress is bounded by
main's write rate. It is not a table defect, and it needs no guard.

### 4.3 What the table proves about execution values

The prototype swaps one execution value for another and compares the table's answers:

| Swap | States that answer differently |
|---|---|
| `:stopped` → `:held` | **0**. They are the same state to the reconciler. |
| `:paused` → `:held` | 324, all of them "keep a flow-paused graph hosted" (G3, G4) |
| `:cancelled` → `:held` | 1,296, all of them close-cancelled instead of hold, judge or bail |

## 5. task-model.md §5 encoded as written (v1)

The rows are as written. Readiness is "execution `:running`, not done, no attempt open, needs done", which admits an
ended attempt with no outcome. Row 6 has three rows, one per disjunct. Rows 10 and 12 are in the branch table.

| v1 result | States |
|---|---|
| Matched by exactly one row | 10,424 |
| Matched by no row | 9,960 |
| Matched by more than one row | 9,376 |
| Closure: invalid successors | 7,650 |
| Closure: cycles | 72 |

The no-row states, classified by v2's answer for the same state:

| v2 answer | States | Kind |
|---|---|---|
| N1 not released or waiting on needs | 3,900 | unnamed steady |
| S1 done | 2,160 | unnamed steady |
| G4 paused, hosted | 108 | unnamed steady |
| O2 held with its attempt open | 36 | unnamed steady |
| G6 working | 36 | unnamed steady |
| G1 `:stop` (a paused graph whose agent ended) | 3,276 | **missing action** |
| G2 `:stop` (a paused graph, undesired) | 324 | **missing action** |
| G5 `:stop` (a paused graph, environment changed) | 72 | **missing action** |
| G8 `:resume` | 36 | **missing action** |
| O5 `:judge` | 12 | **missing action** |

The largest overlap classes:

| Rows | States |
|---|---|
| 6a + 6b + 6c (and pairs) | 3,000+ |
| 1b + 8 | 1,512 |
| 1b + 6* | 1,890 |
| 1b + 2 | 274 |
| 2 + 8 | 216 |
| 3 + 8 | 144 |
| 3b + 5 + 13 | 96 |
| 3a + 13 | 96 |
| 3c + 8 | 72 |

## 6. Gaps found in task-model.md

Each gap gives its evidence (prototype counts), what goes wrong, and the v2 fix. The fixes are for T0 to amend into
TASK-CHAIN together with the table.

1. **Readiness admits an ended attempt awaiting its judgment.** §5 defines "no attempt of T is open" against an
   attempt with no end and no outcome (`seon.task/open-attempt?`). An ended attempt with no outcome yet is therefore
   "not open, not done", so the task is ready.
   - Evidence: rows 1, 2, 3 and 3c fire beside 7, 7r or 8 (3 + 8: 144 states; 2 + 8: 216; 3c + 8: 72; 3 + 7: 8; and
     more).
   - What goes wrong: a second attempt starts while the first is judged or bailed. That is the automatic re-run §5
     forbids.
   - Fix: readiness requires the latest attempt to be absent or `:closed` (latest ∈ `#{:none :closed}`, rows N*).
2. **Row 1b rebuilds over work.** "No open attempt: rebuild" includes an ended attempt and a still-hosted graph.
   - Evidence: 1b + 7/7r/8 (1,890 states), 1b + 6* (1,890), and 7,560 invalid successors (a graph left without its
     agent row).
   - What goes wrong: rebuilding retires the branch that holds the ended attempt's work, a merge agent's conflict
     resolutions included, and pulls the branch out from under a running graph. 1b also rebuilds tasks that are
     `:held`, which is wasted work.
   - Fix:
     - rebuild only when the task is wanted and idle (N3);
     - an open or ended attempt on a stale branch merges main forward instead (G7, O4, A3, `seon.merge/merge-main!`).
3. **Rows 3b and 5 are one row, and neither reads execution.**
   - Evidence: every state 3b matches, 5 matches too (48 states with no other row, 270 in all), plus 72 cycles, all of them {3b or 5, 6b}.
   - What goes wrong: with execution `:held` (or paused, stopped or cancelled), row 6b stops the graph and row 5
     starts it again.
   - Fix: one row, O7, requiring `:running`.
4. **3a, 3b and 5 ignore the agent's observed environment.**
   - Evidence: they overlap row 13 (3a + 13: 96 states; 3b + 5 + 13: 96), and 90 invalid successors, where a graph
     starts on an incomplete environment.
   - What goes wrong: after the restart's stop, the old agent is restarted on its old environment.
   - Fix: O3 closes first, and O5–O7 require agent-env `:none` or `:same`.
5. **A bail un-cancels.** Row 8 writes `execution :held` on a cancelled task whose attempt ended, and row 11 covers only
   open attempts.
   - Fix: A1 closes it as cancelled, with no problem task and no `:held`.
6. **`:paused` and `:stopped` have no rows.** §3.1 says root writes `:paused`, and today `seon.cluster.agent/follow`
   flow-pauses a graph for it. A paused graph has no observation in T2's binary `graph-process`.
   - Evidence: 3,708 states need an action, all of them involving a flow-paused graph.
   - The table proves `:stopped` ≡ `:held` (§4.3).
   - Owner decision:
     - delete `:stopped`;
     - either delete `:paused` too, which makes graph binary and drops G3, G4 and G8 (the clean restart argues for
       this), or keep it and observe the paused status through `clojure.core.async.flow/ping`.
7. **A crash between the start step's attempt row and its judgment** leaves an open, unjudged attempt with no graph.
   - Evidence: no row matches (12 states).
   - Fix: O5 judges.
8. **Rows 6a, 6b and 6c overlap each other.** The action is the same, but three derivations of one decision drift.
   - Fix: one row per graph cell (G1, G2, G5).
9. **Unnamed steady states.** 6,240 states fall through to "no action" implicitly.
   - Fix: as rows, they make an unmatched state a red test instead of a silent no-op.
10. **Branch routes are never released.** Row 12 only listens. A route held on a branch with no ready or open task has
    no row.
    - Fix: B3 (today `release-chain-route!`).
11. **Deleting a branch erases a `:done`** (outside the enumeration: an observation is lost). A task with no target has
    its `:done` only on its branch (§3.2). Row 10 deletes that branch when `keep-ended-branches?` is false.
    - What goes wrong: the task then observes exactly as never run (latest `:none`), so N2 builds it again. Its
      dependents' "done where its attempts live" can never hold.
    - Owner decision: either write a no-target done step's outcome on main too, or let a branch end only after its
      outcomes are on main.
    - Today the dial ships true, so this is latent.
12. **The bail writes two databases, in an order the table fixes.** The bail writes `:held` on main and `:closed` on
    the branch.
    - What goes wrong: if `:closed` commits and `:held` is lost, the state (`:running`, latest `:closed`) is a start row
      (N2–N6), so the task re-runs.
    - Fix: the bail actor must commit `:held` on main first. A crash then leaves (`:held`, `:awaiting`), and A2 bails
      again, idempotently.
13. **Livelock under continuous writes to main** (§4.2). This is expected convergence, not a defect, and needs no guard.
14. **`:sent-back` changes meaning.** `seon.task/end-kinds` maps `:sent-back` to `:merge`. The gap table's row 7 covers
    only `:done`, so a send-back bails (row 8, A2).
    - CLAUDE.md agrees that a send-back "bails a broken or impossible task to root".
    - T3b must change `end-kinds` in the same commit, or delete it, since the table replaces it.
15. **`:seon.flow.actions/cluster` is a dead dial.** It is hardcoded to `:running` in `seon.cluster.agent/launch-facts`.
    - Fix: delete it; on/off is Flow's pause (§7).

## 7. How T3a adopts it

### 7.1 Inside db<->flow

The reconciler is the pass inside the existing `db<->flow` proc. It adds no new runtime. The pass is:

```
wake (seon.cluster.wake/route!) → observe → task-state per task → transitions → admit → actions → owners
```

The proc stays as it is:
- `#'seon.cluster.agent/db<->flow`, `:io`, in the cluster graph;
- the one Datahike listener, `seon.cluster.wake/route!`;
- the sliding-1 in-port;
- the prime at `::flow/resume`.

`run-pass!` becomes `observe`, then `reconcile`, then apply. `reconcile` maps each task's state to its one row, then
folds the start group with `admit`.

### 7.2 On/off

On/off is Flow's own pause and resume of the db<->flow proc. Read from
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj` (dc35f3e):
- **Paused reads nothing.** `clojure.core.async.flow.impl/proc` starts every proc `:paused`. While paused, its loop
  blocks on the control channel alone (`(async/<!! control)`), so the in-port is not read.
- **Wakes coalesce.** The listener's wakes still `offer!` into the sliding-1 in-port, which keeps at most one wake.
  Nothing is lost, because the wake carries no payload.
- **Resume primes a pass.** `flow/resume-proc` (or `flow/resume` on the cluster graph) moves the status to `:running`.
  `handle-transition` then calls the step-fn with `::flow/resume`, and `seon.cluster.agent/db<->flow` offers its prime.
  The next pass reads every fact, so it catches up: the reconciler is level-triggered.
- **The off switch is `(flow/pause-proc cluster-graph :seon.flow/db<->flow)`.** Paused means nothing reconciles:
  - no starts;
  - no stops;
  - no judging, merging or publishing.

  Agent graphs are separate graphs, so running agents keep working. Their ends wait as `:awaiting` until resume.
- **It does not survive a restart.** Boot resumes the graph, so a restart turns reconciliation back on. Durable
  "off" is already data: every task is `:held` until root releases it.

The three controls:

| Control | Scope | Durable? |
|---|---|---|
| Flow pause of db<->flow | everything, at once | no (a restart resumes) |
| `:seon.config.agent/max-running` | the cap on new starts while on | yes |
| `:held` per task | one task | yes |

`max-running` has `:min 1`, so it cannot express "finish what is open but start nothing". If the owner wants that, it
is a schema change to `:min 0`, not a new mechanism.

### 7.3 The proc's own lifecycle stays out of the table

The proc's own lifecycle (paused, resuming, running) should **not** be in the table:
- Flow owns it: `:paused`, `:running` and `:exit` in `handle-command` and `handle-transition`.
- "Resuming" is not a state. It is the `::flow/resume` transition, whose only Seon decision is the prime that already
  exists.
- Paused means the step-fn is not called, so there is nothing to decide.

Encoding it would be a second derivation of a state Flow already holds. The table does cover agent graphs (`graph`
`:absent`, `:running`, `:paused`), because the reconciler decides those.

### 7.4 The FSM in the proc's state map

The owner suggested an FSM in the proc's state map, with a library describing it and the step-fn evaluating it. That
shape fits, with one rule at each level: **the state lives where its owner is.**
- **The cluster level (db<->flow over many tasks)** holds no machine state in the proc. The per-task state is derived
  from facts on every pass (`latest` and `wanted` are derived; no configuration is kept). A statechart configuration in
  the step-fn's state would be a second, crash-volatile copy of what facts already say: the copy the task model
  deletes. The step-fn's state stays the routing handle; the table is the description it evaluates.
- **The agent level (its graph over one attempt)** legitimately keeps its phase in its Flow proc state. AGENTS.md says
  "An agent's state lives in its Flow proc and SCI context", and today `:seon.turn.work/situation` is one such enum.
  Its transitions are edge-triggered: a model reply, an eval done, tests reported, a wake. The same format applies:
  - the dimensions are phase × event, as Malli enums;
  - each row is `{:when {:phase … :event …} :then [ops] :next phase}`;
  - the same enumeration proves every (phase, event) pair has exactly one row.
- **The completeness check is stronger in the decision table** than in statecharts. SCXML discards an unmatched
  event by specification, and settles conflicts by document order. Statecharts' hierarchy and history states buy
  nothing here, because our states are flat products of enums.
- **Recommendation:** one format at both levels, the decision table over Malli enums. Flow stays the runtime. Malli
  supplies the vocabulary and validation, and the enumeration test supplies the completeness. The agent level is a
  follow-on to T3a, not prototyped here.

### 7.5 Changes to T3a (task-model.md §10)

- **Schemas** (`resources/seon/schemas/seon.flow.actions.edn`):
  - the dimension enums;
  - `:seon.flow.actions/task-state`, with its constraints as a registered named predicate;
  - the `:op` `:multi` gaining `:build`, `:rebuild`, `:converge-config`, `:start-step`, `:problem-hold`, `:judge`,
    `:done-step`, `:close-cancelled`, `:close-environment`, `:bail` and `:merge-main`.

  The words "transition table", "row" and "steady row" go into vocabulary.md in T0.
- **Code** (`src/seon/cluster/agent.clj`, additive):
  - `transitions` (the rows, a Var, so `source` shows it to agents);
  - `impossible` (the constraints);
  - `task-state` (one task's state from observed);
  - `row` (the one matching row);
  - `admit`;
  - `reconcile`.

  `reconcile`'s contract is unchanged from task-model.md §5.
- **Red proofs** (`test/seon/cluster/agent_test.clj`). These replace and extend the single
  `reconcile-closes-each-gap-once`:
  - `seon.cluster.agent-test/every-possible-task-state-has-exactly-one-row`: enumerates the possible states and asserts
    that the uncovered and overlapping sets are empty. On failure it reports the states as data (29,760 states,
    ≈ 0.3 s).
  - `seon.cluster.agent-test/every-task-state-reaches-a-steady-row`: the closure, with no external answers. It asserts
    no invalid successor and no cycle (≈ 0.4 s).
  - `seon.cluster.agent-test/the-branch-table-covers-every-branch-state`.
  - `seon.cluster.agent-test/reconcile-closes-each-gap-once`. It is kept as a table test of examples:
    - its rows run through `reconcile`, with idempotence by the `closed` helper;
    - its cap and one-per-branch rows prove `admit`;
    - add rows for gaps 1, 2, 3, 5 and 7: an ended attempt starts nothing new, a stale branch with an ended `:done`
      attempt merges forward instead of rebuilding, `:held` stops and does not restart, cancel after end closes
      without `:held`, and an unjudged open attempt judges.
  - `seon.cluster.agent-test/an-impossible-task-state-is-refused-naming-its-constraint`: `reconcile` given an observed
    state with config but no branch returns the input refusal, whose data names `config-without-branch`.

  All of these are red on HEAD, because `transitions` and `reconcile` do not exist.
- **T3b** applies gaps 12 (the bail commits `:held` first) and 14 (`end-kinds`), and deletes the dead `cluster` dial
  (gap 15).
- **Owner decisions:** gap 6 (whether `:paused` survives) and gap 11 (where a no-target `:done` lives).

## Appendix: the prototype (`transitions.clj`)

This is a disposable probe, reproduced here because `tmp/` is not durable. Run it as §4 shows. The branch table, the
execution-equivalence check and the cost loop are three short scripts over the same namespace. Their forms and outputs
are quoted in §1, §4.3 and §4.

```clojure
(ns transitions
  "Prototype: the reconciler's transition table as data, its exhaustiveness and determinism check, and its closure
  (every state reaches a steady row by the table's own actions)."
  (:require [malli.core :as m]
            [malli.error :as me]))

;;; ---------------------------------------------------------------------------
;;; Dimensions: one enum each, desired then observed (per task, after observe)
;;; ---------------------------------------------------------------------------

(def dimensions
  "Ordered: constraints are checked as soon as their last dimension is assigned."
  [[:execution   [:enum :held :running :paused :stopped :cancelled]] ; :seon.task/execution, existing enum
   [:needs       [:enum :none :pending :done]]                       ; every need done where its attempts live?
   [:target      [:enum :none :main]]                                ; :seon.task/target
   [:environment [:enum :complete :incomplete]]                      ; :seon.task/environment validates?
   [:branch      [:enum :absent :current :stale]]                    ; stale: base H is no longer the target's head
   [:config      [:enum :none :converged :differs]]                  ; branch config vs desired-config
   [:attempt     [:enum :none :unended :done :given-back :capped :stalled :sent-back :cancelled :error]]
   [:outcome     [:enum :none :done :merged :closed]]                ; :seon.task/outcome of the latest attempt
   [:step        [:enum :none :unjudged :empty :work-left]]          ; done-refusals at the latest judged head
   [:agent-env   [:enum :none :same :differs]]                       ; :seon.agent/environment vs desired
   [:graph       [:enum :absent :running :paused]]                   ; :seon.agent/graph-process + flow status
   [:publication [:enum :none :due :done]]])                         ; git commit or refusal after :merged

(def dimension-order (mapv first dimensions))

(def ended (set [:done :given-back :capped :stalled :sent-back :cancelled :error]))

(defn derived
  "The derived dimensions rows may read: functions of the base state, never stored."
  [{:keys [execution needs attempt outcome] :as s}]
  (let [latest (cond (= :none attempt) :none
                     (#{:done :merged} outcome) :settled
                     (= :closed outcome) :closed
                     (= :unended attempt) :open
                     :else :awaiting)]
    (assoc s :latest latest
             :wanted (and (= :running execution) (not= :pending needs)))))

;;; ---------------------------------------------------------------------------
;;; Impossible combinations, each with its reason
;;; ---------------------------------------------------------------------------

(def impossible
  [{:id :config-without-branch :why "the branch holds the config row" :when {:branch #{:absent} :config #{:converged :differs}}}
   {:id :branch-without-config :why "a built branch has a decided config" :when {:branch #{:current :stale} :config #{:none}}}
   {:id :stale-without-target :why "stale is measured against the target's head" :when {:target #{:none} :branch #{:stale}}}
   {:id :attempt-without-branch :why "attempt rows live on the task's branch" :when {:branch #{:absent} :attempt (disj (set (m/children (m/schema (second (dimensions 6))))) :none)}}
   {:id :outcome-without-attempt :why "an outcome is written on an attempt" :when {:attempt #{:none} :outcome #{:done :merged :closed}}}
   {:id :step-without-attempt :why "the start step writes the attempt row first" :when {:attempt #{:none} :step #{:unjudged :empty :work-left}}}
   {:id :attempt-without-step :why "an attempt row carries its step's state, unjudged until judged" :when {:attempt (conj ended :unended) :step #{:none}}}
   {:id :merged-without-target :why "only a done step against a target writes :merged" :when {:target #{:none} :outcome #{:merged}}}
   {:id :done-with-target :why "a done step against a target writes :merged, never :done" :when {:target #{:main} :outcome #{:done}}}
   {:id :settled-not-green :why "the done step runs only on an empty judgment" :when {:outcome #{:done :merged} :step #{:unjudged :work-left}}}
   {:id :agent-without-attempt :why "an agent row exists only for an attempt" :when {:attempt #{:none} :agent-env #{:same :differs}}}
   {:id :attempt-without-agent :why "the start step acquires the attempt's context, which records its environment" :when {:attempt (conj ended :unended) :agent-env #{:none}}}
   {:id :graph-without-agent :why "the context (and its observed environment) is acquired before the graph starts" :when {:agent-env #{:none} :graph #{:running :paused}}}
   {:id :graph-after-outcome :why "invariant: every outcome writer requires the graph absent" :kind :invariant :when {:outcome #{:done :merged :closed} :graph #{:running :paused}}}
   {:id :incomplete-but-same :why "an agent starts only on a complete environment, so same means complete" :when {:environment #{:incomplete} :agent-env #{:same}}}
   {:id :publication-without-merge :why "publication follows a merge" :when {:outcome #{:none :done :closed} :publication #{:due :done}}}
   {:id :merge-without-publication-state :why "a merge is either due for publication or published" :when {:outcome #{:merged} :publication #{:none}}}])

(defn matches? [state conditions]
  (every? (fn [[dim allowed]] (if (set? allowed) (contains? allowed (get state dim)) (= allowed (get state dim)))) conditions))

(defn violated [state]
  (filterv #(matches? state (:when %)) impossible))

(defn enumerate
  "Every possible state: backtracking over the dimensions, pruning a partial state once a constraint whose
  dimensions are all assigned matches it."
  ([] (enumerate impossible))
  ([impossible]
  (let [last-dim (fn [c] (apply max-key #(.indexOf ^java.util.List dimension-order %) (keys (:when c))))
        by-last (group-by last-dim impossible)]
    (loop [states [{}] [[dim schema] & more] dimensions]
      (if-not dim
        states
        (recur (into [] (for [s states v (m/children (m/schema schema))
                              :let [s' (assoc s dim v)]
                              :when (not-any? #(matches? s' (:when %)) (by-last dim))]
                          s'))
               more))))))

;;; ---------------------------------------------------------------------------
;;; v1: task-model.md §5's gap table, encoded as written
;;; ---------------------------------------------------------------------------

(def not-running #{:held :paused :stopped :cancelled})

(def v1
  (let [ready {:execution :running :needs #{:none :done} :latest #{:none :awaiting :closed}}] ; "not done, no open attempt"
    [{:row "1"   :when (merge ready {:branch #{:absent}}) :then [:build]}
     {:row "1b"  :when {:target :main :latest #{:none :awaiting :closed} :branch :stale} :then [:rebuild]}
     {:row "1b'" :when {:target :main :latest :open :branch :stale} :then [:merge-main]}
     {:row "2"   :when (merge ready {:branch #{:current :stale} :config :differs}) :then [:converge-config]}
     {:row "3"   :when (merge ready {:branch :current :config :converged :environment :complete}) :then [:start-step]}
     {:row "3c"  :when (merge ready {:branch :current :config :converged :environment :incomplete}) :then [:problem-hold]}
     {:row "3a"  :when {:latest :open :step :empty :graph :absent} :then [:done-step]}
     {:row "3b"  :when {:latest :open :step :work-left :graph :absent} :then [:start-graph]}
     {:row "5"   :when {:latest :open :step :work-left :graph :absent} :then [:start-graph]}
     {:row "6a"  :when {:graph :running :attempt ended} :then [:stop]}
     {:row "6b"  :when {:graph :running :execution not-running} :then [:stop]}
     {:row "6c"  :when {:graph :running :agent-env :differs} :then [:stop]}
     {:row "7"   :when {:attempt :done :outcome :none :graph :absent :step :empty} :then [:done-step]}
     {:row "7r"  :when {:attempt :done :outcome :none :graph :absent :step #{:work-left :unjudged}} :then [:bail]}
     {:row "8"   :when {:attempt (disj ended :done) :outcome :none :graph :absent} :then [:bail]}
     {:row "9"   :when {:outcome :merged :publication :due} :then [:publish]}
     {:row "11"  :when {:execution :cancelled :latest :open :graph :absent} :then [:close-cancelled]}
     {:row "13"  :when {:graph :absent :latest :open :agent-env :differs} :then [:close-environment]}]))

;;; ---------------------------------------------------------------------------
;;; v2: the completed table. Rows are disjoint; a steady state is a row with :then [].
;;; ---------------------------------------------------------------------------

(def v2
  (let [idle #{:none :closed}
        wanted {:wanted true}
        unwanted {:wanted false}]
    [;; -- a hosted graph (running or flow-paused): only an open or awaiting attempt has one
     {:row "G1" :when {:graph #{:running :paused} :latest :awaiting} :then [:stop] :why "the agent ended: stop and join before judging"}
     {:row "G2" :when {:graph #{:running :paused} :latest :open :execution #{:held :stopped :cancelled}} :then [:stop]}
     {:row "G3" :when {:graph :running :latest :open :execution :paused} :then [:pause]}
     {:row "G4" :when {:graph :paused :latest :open :execution :paused} :then [] :steady "paused, hosted"}
     {:row "G5" :when {:graph #{:running :paused} :latest :open :execution :running :agent-env :differs} :then [:stop] :why "restart, first half"}
     {:row "G6" :when {:graph :running :latest :open :execution :running :agent-env :same :branch :current} :then [] :steady "working"}
     {:row "G7" :when {:graph :running :latest :open :execution :running :agent-env :same :branch :stale} :then [:merge-main]}
     {:row "G8" :when {:graph :paused :latest :open :execution :running :agent-env :same} :then [:resume]}
     ;; -- no graph, an open attempt
     {:row "O1" :when {:graph :absent :latest :open :execution :cancelled} :then [:close-cancelled]}
     {:row "O2" :when {:graph :absent :latest :open :execution #{:held :stopped :paused}} :then [] :steady "held with its attempt open"}
     {:row "O3" :when {:graph :absent :latest :open :execution :running :agent-env :differs} :then [:close-environment] :why "restart, second half"}
     {:row "O4" :when {:graph :absent :latest :open :execution :running :agent-env #{:none :same} :branch :stale} :then [:merge-main]}
     {:row "O5" :when {:graph :absent :latest :open :execution :running :agent-env #{:none :same} :branch :current :step :unjudged} :then [:judge] :why "a crash between the attempt row and its judgment"}
     {:row "O6" :when {:graph :absent :latest :open :execution :running :agent-env #{:none :same} :branch :current :step :empty} :then [:done-step] :why "3a"}
     {:row "O7" :when {:graph :absent :latest :open :execution :running :agent-env #{:none :same} :branch :current :step :work-left} :then [:start-graph] :why "3b and 5, one row"}
     ;; -- no graph, the attempt ended with no outcome yet
     {:row "A1" :when {:graph :absent :latest :awaiting :execution :cancelled} :then [:close-cancelled] :why "a bail would write :held over :cancelled"}
     {:row "A2" :when {:graph :absent :latest :awaiting :execution #{:held :running :paused :stopped} :attempt (disj ended :done)} :then [:bail] :why "8"}
     {:row "A3" :when {:graph :absent :latest :awaiting :execution #{:held :running :paused :stopped} :attempt :done :branch :stale} :then [:merge-main] :why "never rebuild over an ended attempt's work"}
     {:row "A4" :when {:graph :absent :latest :awaiting :execution #{:held :running :paused :stopped} :attempt :done :branch :current :step :unjudged} :then [:judge]}
     {:row "A5" :when {:graph :absent :latest :awaiting :execution #{:held :running :paused :stopped} :attempt :done :branch :current :step :empty} :then [:done-step] :why "7"}
     {:row "A6" :when {:graph :absent :latest :awaiting :execution #{:held :running :paused :stopped} :attempt :done :branch :current :step :work-left} :then [:bail] :why "7, refused"}
     ;; -- settled
     {:row "S1" :when {:latest :settled :publication #{:none :done}} :then [] :steady "done"}
     {:row "S2" :when {:latest :settled :publication :due} :then [:publish] :why "9"}
     ;; -- nothing open: the start group, admitted by the scheduler fold
     {:row "N1" :when (merge unwanted {:latest idle}) :then [] :steady "not released, or waiting on needs"}
     {:row "N2" :when (merge wanted {:latest idle :branch :absent}) :then [:build] :start? true :why "1"}
     {:row "N3" :when (merge wanted {:latest idle :branch :stale}) :then [:rebuild] :start? true :why "1b, only when ready"}
     {:row "N4" :when (merge wanted {:latest idle :branch :current :config :differs}) :then [:converge-config] :start? true :why "2"}
     {:row "N5" :when (merge wanted {:latest idle :branch :current :config :converged :environment :incomplete}) :then [:problem-hold] :why "3c"}
     {:row "N6" :when (merge wanted {:latest idle :branch :current :config :converged :environment :complete}) :then [:start-step] :start? true :why "3"}]))

;;; ---------------------------------------------------------------------------
;;; The check
;;; ---------------------------------------------------------------------------

(defn rows-for [table state] (filterv #(matches? state (:when %)) table))

(defn check
  "Every possible state against `table`: the gaps (no row) and the overlaps (more than one row)."
  [table states]
  (reduce (fn [acc s]
            (let [rows (rows-for table (derived s))]
              (case (count rows)
                0 (update acc :gaps conj s)
                1 (update acc :covered inc)
                (update acc :overlaps conj [s (mapv :row rows)]))))
          {:gaps [] :overlaps [] :covered 0}
          states))

;;; ---------------------------------------------------------------------------
;;; Closure: each action's closing observation, possibly several (the world answers)
;;; ---------------------------------------------------------------------------

(def ^:dynamic *external*
  "Whether effects include answers that need an external event (another writer moving main)."
  true)

(defn effects
  "The states `op` can leave `s` in: the fact its actor writes, and each answer the world can give."
  [op {:keys [target] :as s}]
  (let [judged (fn [s] (for [v [:empty :work-left]] (assoc s :step v)))
        configs (fn [s] (for [v [:converged :differs]] (assoc s :config v)))]
    (case op
      :build (configs (assoc s :branch :current))
      :rebuild (configs (assoc s :branch :current :attempt :none :outcome :none :step :none :agent-env :none))
      :converge-config [(assoc s :config :converged)]
      :start-step (judged (assoc s :attempt :unended :outcome :none :agent-env :same))
      :problem-hold [(assoc s :execution :held)]
      :judge (judged s)
      :done-step (if (= :main target)
                   (concat (for [p [:due :done]] (assoc s :outcome :merged :publication p))
                           (when *external* [(assoc s :branch :stale)])) ; main moved: the basis fence refuses, nothing written
                   [(assoc s :outcome :done)])
      :start-graph [(assoc s :graph :running :agent-env :same)]
      :stop [(assoc s :graph :absent)]
      :pause [(assoc s :graph :paused)]
      :resume [(assoc s :graph :running)]
      :close-cancelled [(assoc s :outcome :closed)]
      :close-environment [(assoc s :outcome :closed)]
      :bail [(assoc s :execution :held :outcome :closed)]
      :merge-main (judged (assoc s :branch :current))
      :publish [(assoc s :publication :done)])))

(defn successors [table s]
  (into #{} (for [row (rows-for table (derived s)) op (:then row) s' (effects op s)] s')))

(defn closure-check
  "Over every possible state: successors that violate a constraint, and cycles (a path that revisits a state with no
  external event). Memoized depth-first search; :steady states end a path."
  [table states]
  (let [bad (volatile! []) cycles (volatile! []) done (java.util.HashMap.)]
    (letfn [(walk [s path on-path]
              (cond (.containsKey done s) nil
                    (contains? on-path s) (vswap! cycles conj (conj path s))
                    :else (do (doseq [s' (successors table s)]
                                (if (seq (violated s'))
                                  (vswap! bad conj [s s' (mapv :id (violated s'))])
                                  (walk s' (conj path s) (conj on-path s))))
                              (.put done s true))))]
      (doseq [s states] (walk s [] #{}))
      {:invalid-successors @bad :cycles @cycles})))

;;; ---------------------------------------------------------------------------
;;; Actions are validated data
;;; ---------------------------------------------------------------------------

(def action
  (m/schema
   [:multi {:dispatch :op}
    [:build [:map [:op [:= :build]] [:task :string] [:branch :keyword]]]
    [:rebuild [:map [:op [:= :rebuild]] [:task :string] [:branch :keyword]]]
    [:converge-config [:map [:op [:= :converge-config]] [:task :string] [:branch :keyword]]]
    [:start-step [:map [:op [:= :start-step]] [:task :string] [:branch :keyword]]]
    [:problem-hold [:map [:op [:= :problem-hold]] [:task :string] [:missing [:set :keyword]]]]
    [:judge [:map [:op [:= :judge]] [:task :string] [:branch :keyword]]]
    [:done-step [:map [:op [:= :done-step]] [:task :string] [:branch :keyword]]]
    [:start-graph [:map [:op [:= :start-graph]] [:task :string] [:agent :string]]]
    [:stop [:map [:op [:= :stop]] [:agent :string]]]
    [:pause [:map [:op [:= :pause]] [:agent :string]]]
    [:resume [:map [:op [:= :resume]] [:agent :string]]]
    [:close-cancelled [:map [:op [:= :close-cancelled]] [:task :string]]]
    [:close-environment [:map [:op [:= :close-environment]] [:task :string]]]
    [:bail [:map [:op [:= :bail]] [:task :string]]]
    [:merge-main [:map [:op [:= :merge-main]] [:task :string] [:branch :keyword]]]
    [:publish [:map [:op [:= :publish]] [:task :string]]]
    [::m/default [:map [:op [:= ::unknown-op]]]]]))

(defn admit
  "The scheduler fold over the tasks' answers: start-group actions, oldest first, one per branch, while the open
  attempts are fewer than `max-running`."
  [answers open max-running]
  (let [starts (->> answers (filter :start?) (sort-by :created))
        admitted (->> starts (reduce (fn [[seen out] a] (if (seen (:branch a)) [seen out] [(conj seen (:branch a)) (conj out a)]))
                                     [#{} []])
                      second (take (max 0 (- max-running open))))]
    (into (vec (remove :start? answers)) admitted)))

(defn -main [& _]
  (let [t0 (System/nanoTime)
        product (reduce * (map #(count (m/children (m/schema (second %)))) dimensions))
        states (enumerate)
        t1 (System/nanoTime)
        ;; impossible by construction vs invariant: count states violating only invariants
        invariant-only (- (count (enumerate (remove (comp #{:invariant} :kind) impossible))) (count states))
        r1 (check v1 states)
        r2 (check v2 states)
        t2 (System/nanoTime)]
    (prn {:product product :possible (count states) :impossible (- product (count states))
          :invariant-only invariant-only
          :enumerate-ms (/ (- t1 t0) 1e6) :check-both-ms (/ (- t2 t1) 1e6)})
    (prn :v1 {:covered (:covered r1) :gaps (count (:gaps r1)) :overlaps (count (:overlaps r1))})
    (prn :v2 {:covered (:covered r2) :gaps (count (:gaps r2)) :overlaps (count (:overlaps r2))})
    ;; v1's overlaps grouped by the rows that overlap
    (prn :v1-overlaps (frequencies (map second (:overlaps r1))))
    ;; v1's gaps classified by v2's answer for the same state
    (prn :v1-gaps-by-v2-row (frequencies (map #(let [[r] (rows-for v2 (derived %))] [(:row r) (or (:steady r) (:then r))]) (:gaps r1))))
    (let [t3 (System/nanoTime)
          [c1 c2] (binding [*external* false] [(closure-check v1 states) (closure-check v2 states)])
          c2e (closure-check v2 states)]
      (prn :v2-closure-with-main-moving {:invalid (count (:invalid-successors c2e)) :cycles (count (:cycles c2e))
                                         :cycle-rows (frequencies (map (fn [path] (into (sorted-set) (mapcat #(map :row (rows-for v2 (derived %)))) path)) (:cycles c2e)))})
      (prn :closure-ms (/ (- (System/nanoTime) t3) 1e6))
      (prn :v1-closure {:invalid (count (:invalid-successors c1)) :cycles (count (:cycles c1))})
      (prn :v1-invalid-kinds (frequencies (map (fn [[s _ ids]] [(mapv :row (rows-for v1 (derived s))) ids]) (:invalid-successors c1))))
      (prn :v1-cycle-example (some-> (first (:cycles c1)) (->> (mapv #(select-keys (derived %) [:execution :latest :graph :step :agent-env :branch])))))
      (prn :v1-cycle-rows (frequencies (map (fn [path] (into (sorted-set) (mapcat #(map :row (rows-for v1 (derived %)))) path)) (:cycles c1))))
      (prn :v2-closure {:invalid (count (:invalid-successors c2)) :cycles (count (:cycles c2))})
      (doseq [x (take 5 (:invalid-successors c2))] (prn :v2-invalid x))
      (doseq [x (take 3 (:cycles c2))] (prn :v2-cycle x)))
    ;; actions as validated data
    (prn :action-valid (m/validate action {:op :build :task "t1" :branch :task-t1})
         :action-unknown (me/humanize (m/explain action {:op :launch :task "t1"})))
    ;; the scheduler fold: five ready tasks, cap 2, one open
    (prn :admit (mapv :task (admit (for [i (range 5)] {:op :build :task (str "t" i) :branch (keyword (str "b" i)) :created i :start? true}) 1 2))
         :admit-same-branch (mapv :task (admit [{:op :start-step :task "a" :branch :b :created 0 :start? true}
                                                {:op :start-step :task "b" :branch :b :created 1 :start? true}] 0 4)))))
```
