---
title: "R1: db<->flow, the process linking Datahike and Flow"
type: research
status: design for review (root and owner), revised with the owner's answers of 2026-10-11; no code lands before it is accepted
basis: HEAD a14542f08 read by symbol (S2 frontier 4e8ce1164, held-default 60fec55d2, R4 bdf7411b5, W1 a74fd3031); JVM probes on default, whose loaded commit predates S1, R4 and W1 (`:seon.task/execution` uninstalled there, `seon.turn.world/world` unloaded)
---

# R1: db<->flow

**What exists.** Today an agent's graph is started through three pieces:

- `seon.cluster.agent/armer-step` derives unarmed agents with work and calls `arm!`.
- `seon.cluster.host/host!` admits an agent on a branch and starts a second, per-branch armer graph.
- `seon.task/frontier` answers the `:running` leaves whose needs are done.

**Principal approach.** One Flow process, `seon.flow/db<->flow`, sits in the cluster graph. It is the state machine that
links the database and Flow in both directions:

- **Database to Flow.** Intents and attempts become Flow verbs: `flow/create-flow`, `flow/start`, `flow/resume`,
  `flow/pause` and `flow/stop`.
- **Flow to database.** What Flow observes is written back as facts.

Its decision is one pure function, `plan` (facts and roster in, actions out). Each action has one small effect. Nothing
else starts a graph.

## 1. The pass: data in, actions out

```clojure
(seon.flow/plan facts roster max-running) ; pure → [action ...]

facts   {:startable #{handle}                                 ; frontier ∩ valid (§3)
         :invalid   #{handle}                                 ; :running leaves that fail validation → problem tasks
         :open      [{:task h :attempt k :agent id :branch b :intent i}] ; attempts with no end, with their task's intent
         :ended     {h n}                                     ; ended attempts per task: the next attempt is n+1
         :created   {h t}                                     ; tx of the task's identity datom, for order
         :basis     {h #{commit-id}}                          ; end commits of the leaf's done needs (§6 joins)
         :cluster   :running | :paused}
roster  {:started #{branch}}                                  ; graphs in the cluster roster (one graph per agent, §2)
actions [{:op :attempt  :task h :attempt k :agent id :branch b :fork-point #{commit}|:main}
         {:op :start-flow :task h :branch b}                 ; create + start + resume, recorded in the roster
         {:op :problem :class :invalid-task :task h}
         {:op :pause|:stop|:cancel :task h :branch b}]       ; R2's verbs
```

| Fact × roster | Action |
|---|---|
| a startable leaf with no open attempt, while open `:running`/`:paused` attempts < `max-running`, oldest first | `:attempt`, then `:start-flow` in the same pass |
| an open attempt, intent `:running`, no graph (a restart, or a crash between the write and the start) | `:start-flow` |
| an open attempt with a graph | nothing |
| a `:running` leaf that does not validate | `:problem`, by identity `seon.id/id [:invalid-task handle]`, so a repeat writes nothing |
| `:held` | nothing: the frontier never holds it |
| cluster `:paused`, or intent `:paused` / `:stopped` / `:cancelled` | `:pause` / pause, join, `flow/stop`, release (P3, no interrupt) / interrupt and end `:cancelled` (R2, D3) |

**Identity, with no flag and no lock.** Attempt `n` = 1 + the task's ended attempts. Its key is
`k = seon.id/id [handle n]`, its agent is `"attempt/<k>"` and its branch is `:attempt-<k>`. A second pass re-derives the
same `k`, sees the open attempt and writes nothing. A repeated `fork!` answers `created? false`, and a repeated
`start-flow!` leaves a started graph alone.

**Write first, then make Flow follow.** The attempt row is the desired state. It is committed before anything runs. A
crash at any later point leaves an open attempt with no graph, and the next pass (the prime at `::flow/resume` after a
restart) starts it. Launch and restart are one path.

**max-running is counted on facts** (open attempts on main), so it holds across a crash and needs no counter.

**Prototype.** A 25-line plan was run at default's JVM REPL:

- Fresh `#{A}` gives one `:attempt`.
- Planning again with an empty roster gives `:start-flow`, the restart case.
- Once A is started, the plan is `[]`.
- After A's end, B's `:fork-point` is A's commit.
- Ten ready leaves with a bound of 4 give four actions.
- 50 leaves and 200 open attempts plan in 0.5 ms and 0.35 MB.

## 2. Functions, contracts, and one graph per agent

| Function | Contract | Notes |
|---|---|---|
| `seon.flow/db<->flow` | Flow's four arities, `:io` | in the cluster graph; woken by the router on the start attributes, primed at `::flow/resume`; transform = `facts → plan → run! effect` |
| `seon.flow/plan-facts` | `[:=> [:cat :seon.db/database-value] [:or ::facts :seon.db/invalid-read-error]]` | four reads on main: `seon.task/frontier`, open attempts and intents, ended counts, need commits; validity from §3 |
| `seon.flow/plan` | `[:=> [:cat ::facts ::roster :int] [:vector ::action]]` | pure; R1's tests are a table over it |
| `seon.flow/start-flow!` | `[:=> [:cat :seon.agent/start-request] :seon.agent/started]` | create + start + resume one agent's graph on its branch execution, recorded in the roster (today's `arm!` + `arm-graph!`) |
| `seon.cluster.host/assemble!` | card R1's request → `[:or ::assembled ::refused-error]` | the `:attempt` effect: one main transaction, `fork!`, the environment (R4), the world (W1), then `start-flow!` |
| dial `:seon.config.agent/max-running` | `[:int {:min 1}]`, default 4 | `seon.config.agent.edn` + defaults |

**The per-branch armer graph goes** (owner). `db<->flow` starts every agent graph directly, which leaves one graph and
one platform thread fewer per agent:

- `host!`'s `start!` stops starting a graph.
- The branch's `wake/route!` listener is registered by `start-flow!` on the agent's branch execution, as the branch
  armer's init does today.
- An agent that appears later on a branch is never started there: only `db<->flow` starts graphs.

**The task drives the context through its attempt row.** `assemble!` renders nothing. The first turn pins a value and
renders `seon.context/current-state` from that row, and its cost is the per-turn path's (§5).

**The contract S3 must honor:**

- `seon.task/attempt-tx [db handle agent-id branch]` returns tx-data that `assemble!` concatenates with the agent's
  creation. It includes `:seon.task/attempt-of`.
- `:seon.task.attempt/end` and `:seon.task/needs` declare the start-wake property (§7's rename of `:seon.wake/arms`),
  so that A's end wakes `db<->flow` and B starts.

### Words R1 renames (one scripted sweep in R1's commit; uses counted in `src` / `test` / `resources+docs/seon+.agents`)

| Today | Becomes | Uses |
|---|---|---|
| `seon.cluster.agent/armer-step`, "armer", "the armer's pass" | `seon.flow/db<->flow` | 7/9/11; "armer" 62/105/71 |
| `seon.cluster.agent/arm!`, private `arm-graph!` | `seon.flow/start-flow!` (one function) | 39/68/53; 4/0/1 |
| `seon.cluster.agent/agents-to-arm` | deleted: `plan` | 2/3/0 |
| `::arming` claim, `:seon.agent/armed` roster key, `armed` | `::starting`, `:seon.agent/started`, `started` | 10/0/0; 17/17/1; 18/41/2 |
| `:seon.agent/armed-count` (ping) | `:seon.agent/started-count` | 1/10/1 |
| `:seon.agent/arm-request`, `:seon.agent/armed` schemas | `:seon.agent/start-request`, `:seon.agent/started` | 27/3/3 |
| `:seon.wake/arms`, `seon.cluster.wake/arming-attributes` | `:seon.wake/starts`, `starting-attributes` | 6/4/7; 5/2/4 |
| `:seon.agent/arm`, `:seon.agent/arm-prime`, `:seon.cluster.wake/armer-channel` | `:seon.flow/plan`, `:seon.flow/plan-prime`, `:seon.flow/plan-channel` | 1, 1, 16/19/2 |
| `disarm!`, `seon.cluster/disarm-agents!`, `arm-agents!` | `stop-flow!`, `stop-flows!`, `start-flows!` | 11/26/24; 9/1/20; 12/4/26 |

The vocabulary rows (`db<->flow`, `plan`, `start-flow!`) go into
[vocabulary](../../../seon/architecture/vocabulary.md) first. "Arm" in the contract sense (an armed contract,
`seon.instrument`) is a different word and stays.

## 3. Intent is a field; validity is part of the plan

- **Intent** is `:seon.task/execution` on the task, and nothing else.
- **One rule:** the task's own field decides, with no inheritance. Ingestion and `create!` write `:held` when absent
  (60fec55d2).
- A chain's creator writes each step with the intent it wants. The usual case is every step `:running`, and the steps
  wait on `needs`, which the frontier already enforces.
- Pausing a chain is the cluster intent, or one transaction setting each open leaf's intent (a `my.task` function).
- Inheritance would add a recursive rule to the frontier and a second place to look, and it saves only that one
  transaction.
- **Valid** means the task validates against its entity schema (`:seon.task/task`), and has instructions (or an intent
  text), an intent field and at least one proof (`seon.task/task-edges`).
- Validity is derived in `plan-facts` from the task's content: a `seon.cache` kind keyed by `seon.task/task-basis`'s
  digest, so an unchanged task is a lookup.
- A `:running` task that fails validation is not started. It becomes a problem task for root (runtime-follows-db §5,
  class `task-quality`) whose instructions carry the failed checks as data.

## 4. Cost: measured, with R4, and the lower bound

All rows are measured on default with `seon.profile/measure` (thread allocation) unless marked. "Lower bound" is what
the work inherently is: one main commit, one branch pointer, the branch's fork entity, a cached environment, three procs
on an executor, and a cached context.

| Leg | Measured now | With R4 (bdf7411b5) | Lower bound, and how |
|---|---|---|---|
| frontier + plan | 6–8 ms (S2's check JVM) + 0.5 ms | same | same: proportional to task rows; every pass follows a new commit, so a cache cannot hit |
| attempt + agent rows on main | `host!`'s two `home-call` txs 44 ms + the attempt (one combined creation + assignment tx measured 32 ms, 0.45 MB) | same | **one main transaction, about 30 ms**: attempt row + agent creation together, the writer deciding admission |
| fork | 47 ms, 5.7 MB (`branch!` 17 ms + fork-entity tx 27 ms) | same | **about 45 ms**: the branch pointer + one branch transaction carrying the fork entity *and* the agent's branch row (forking after the main tx from `:main` inherits it for free) |
| host | 76 ms, 45 MB (two `home-call` txs, acquisition, branch armer graph) | — | **0**: folded into the rows above and `start-flow!` |
| SCI environment (acquisition) | unmoved branch 1 ms; a moved branch cold up to 388 ms, 399 MB (R4's measurement) | warm 1.28 ms, 0.83 MB; successor fork 1.10 ms, 0.80 MB (R4) | **about 1 ms**: R4's cached fork source |
| start-flow (`arm!` today) | 14 ms, 15 MB | same | **under 10 ms**: `start-graph!` of two procs once D2 deletes the schedule proc (to measure in R1) |
| world (W1 `seon.turn.world/world`) | not loaded on default | — | under 5 ms (card target; measured in R1) |
| **launch total** | **about 190 ms, about 70 MB** (up to 580 ms and 470 MB on a moved branch) | **about 160 ms, 70 MB** | **about 90 ms, under 15 MB** |
| first context: `seon.context/current-state` | 94–177 ms, 148–354 MB on uncommitted (`d/with`) values, which always miss; 80 ms and 139 MB on the first read of a freshly forked committed branch; 2 ms and 2 MB on a hit | same | **2–5 ms**: a fresh fork of an unchanged program hits main's entry (§5, card CACHE-1) |
| **start to first context** | **about 270 ms, 210 MB** | **about 240 ms, 210 MB** | **about 95 ms, under 20 MB** |

The lightness proof `starting-one-agent-on-a-task-costs-under-a-second-and-300-mb` measures start to first context. It
is green under budget today. It records the measured numbers as profile evidence so that a slower run is red. The lower
bound is the target, and the gap closes through two changes:

- the one-transaction launch (R1 itself);
- CACHE-1.

## 5. Caching: every derivation on the two paths

Owner: whatever runs frequently is cached seamlessly. `seon.cache/lookup` is that read-through: a miss derives and
fills, a hit is a read, and eviction is the LRU under the byte budget. Keys are content, so nothing is invalidated.

| Derivation | Path | Cached today? key | Miss | Hit | Card |
|---|---|---|---|---|---|
| frontier (`seon.task/frontier`) | start | no (each pass follows a new commit) | 6–8 ms | — | none |
| task validity (§3) | start | new kind, key `task-basis` digest | about 1 ms (edges 0.45 + pull + validate) | lookup | in R1 |
| task basis (`seon.task/task-basis`) | start, turn | yes, `::task-basis [forms content names]` | — | lookup | — |
| program identity (`seon.sci.eval/program-identity`) | start, turn | yes, by commit id | 0.03 ms | 0.008 ms | — |
| base context (`seon.sci.eval/base-ctx`) | start, turn | yes, `::base-context [store commit]` | 24 ms | 7.6 ms | — |
| overlay fork source (R4) | start, turn | yes, `::overlay-context [base-key, program basis]` | 388 ms, 399 MB | 1.28 ms, 0.83 MB | landed |
| schema projection | every read and write | yes, `seon.db` projection caches by commit and content | — | lookup | — |
| world (W1) | start, turn, test member | a construction over held values, not a derivation | to measure | — | none |
| held task + edges (`seon.context` `held-task`, `task-edges`) | turn | no | 0.9 + 0.45 ms | — | none (under 2 ms) |
| **proof reach digests** (`seon.test.runner/reach-digests`) | turn, test selection | yes, `::reach-graph [content-revisions tests]` and `::reach-attribute` | main: 29 ms, 39 MB; **a fresh fork with an unchanged program: 70–80 ms, 127–139 MB** | 1.2–2 ms | **CACHE-1**: the miss on a fresh fork is the start's dominant cost. Key the reach by program content (definition digests), so a branch whose program equals main's hits main's entry. Also: `d/with` values have no committed context and always miss, which makes probes and tests that use `d/with` overstate the cost |
| contract references (`seon.schema/contract-references`) | inside reach misses | no | 98 ms in 1,072 calls on one `d/with` miss | — | **CACHE-2**: kind keyed by the contract form's digest (proportional to changed contracts, not all reached ones) |
| latest results (`seon.test.runner/latest-results`) | turn | no | 0.06 ms | — | none |
| branch cost (`seon.context` `branch-cost`) | turn | no | 0.18 ms | — | none |
| whole `current-state` | turn | no kind of its own | 80 ms (cold parts) | 4.5 ms, 9 MB (parts hit) | none once CACHE-1 lands: its parts hit; a kind keyed by read evidence would save about 4 ms per turn and is not worth a second key |
| what is due (`seon.turn/unanswered-wakes`) | turn | no | 3.2 ms (§4 of runtime-follows-db) | — | none |
| doc and reference renders (`seon.ref` `::document`, `seon.render` `::call-reach`) | turn (render) | yes, by text digest and content | not measured here | — | measure in Q3's render work |
| effective config + agent overlay (`config/effective`, `ai/agent-overlay`) | start-flow | not measured | inside start-flow's 14 ms | — | measure in R1 |

## 6. Tests and joins

- **Tests (W2).** A test run is a task. Its members run in a fork of the agent's environment at the agent's current
  commit, and their results come back as data. The agent's own environment and branch never move. Results are stored in
  Datahike keyed by (test, reach digest), as `seon.test.member/reach-digest` is today, so any branch whose reach digest
  matches reuses them.
- **Parallel members (W2, owner's question).** Today `seon.test` gives each member its own custody, branch, world and
  platform thread, but walks the members sequentially in a reduce.
  - **Measured** on default's 81 recorded members:

    | | p50 | p90 | max |
    |---|---|---|---|
    | wall time per member | 394 ms | 1.54 s | 5.56 s |
    | allocation per member | 285 MB | 2.43 GB | 6.2 GB |

  - **Expected wall-clock gain at 4,** for the four largest recorded runs:

    | Members | Serial | With 4 at a time |
    |---|---|---|
    | 24 | 7.8 s | about 1.9 s |
    | 19 | 11.8 s | 5.0 s (bounded by one 5.0 s member) |
    | 10 | 10.2 s | about 2.6 s |
    | 8 | 2.0 s | about 0.5 s |

    The gain is about 3–4× unless one long member dominates.
  - **Design.** Members run on a bounded pool:
    - The pool size is the dial `:seon.config.test/member-parallelism`, default 4 for a 16 GB machine, never derived
      from host RAM.
    - Each member is still awaited to its actual exit.
    - Host-bound and destructive tests keep their isolated JVMs.
    - Members return results as data. The run (a task) writes them in one transaction keyed by reach digest.
  - **Ordering prerequisite.** Per-member cost drops first: CACHE-1 and CACHE-2, then each member's reach and context
    leg. Running 4 members at once at p90 would allocate about 10 GB in seconds against a 4 GB heap, so parallelism
    lands after the per-member allocation falls to tens of MB.
- **Joins.**
  - A leaf with **one** done need forks that need's end commit (`:seon.task.attempt/commit`, by commit id, P2).
  - A leaf with **several** forks a fresh join branch from the first need's commit, then `datahike.api/merge!` of the
    others. `merge!` records the parents but takes the caller's tx-data, so the tx-data is the
    `seon.cluster.source/compare-branch` diff of each further need against the merge base.
  - A conflict (the same entity changed on two sides) writes a problem task (class `stuck chain`, the conflict as data)
    and starts nothing.
  - The join's fork point is the merge commit.
  - A need on a parent counts its leaves' end commits.
  - With no need, the leaf forks main's current value.

## 7. Where each component lives

| Store | Holds |
|---|---|
| Datahike | tasks, intents, attempts and their ends, agents, firings, problem tasks, test results keyed by reach digest, commits (fork points by commit id) |
| konserve blobs | large immutable values by digest (shown text past the threshold, stored evidence), through `seon.blob/inline-or-stage` |
| `seon.cache` | every derivation, keyed by content: base and overlay contexts, program identity, projections, reach, validity, contract references |
| Flow | live processes only: `db<->flow` in the cluster graph, one graph per running agent; channels carry wakes, never state |

## 8. Tests for R1 (red first)

- A table over `plan`:
  - `two-passes-start-one-attempt`
  - `a-held-leaf-is-never-started`
  - `an-invalid-running-leaf-is-a-problem-task`
  - `max-running` (10 ready, 4 started; 4 open, 0 started)
  - restart (an open attempt with no graph gives `:start-flow`)
  - `a-successor-forks-from-its-predecessors-end-commit`
  - a join (two needs give a merge fork point)
- One integration test on a disposable branch of the live store: the two-leaf chain.
  1. Setting A and B `:running` starts A only.
  2. A's end with a commit starts B, forked from that commit.
  3. Dropping the roster and priming restarts the open attempt.
- `starting-one-agent-on-a-task-costs-under-a-second-and-300-mb` through `seon.profile/measure`, start to first context,
  with the measured legs as profile evidence.

**RESET NEEDED** before any live proof: default runs a commit from before S1, R4 and W1.

## 9. Open questions

1. **Joins:** merge the further needs into a join branch (§6), or require root's merge request to have landed each need
   on main first, which turns every join into a fork of main? The second is simpler but serializes on root. Recommended:
   the merge, since it keeps chains parallel without root.
2. **An open attempt whose task becomes `:held`.** Recommended: treat it as `:paused`. The graph stays and nothing runs.
3. **Observing `:paused`.** The roster does not say whether a graph is paused. R2 records it in the roster entry when
   `db<->flow` issues the verb, and the heartbeat's `ping` checks it.
4. **Agent ids** derive from the attempt only (`"attempt/<k>"`). A task rename then never re-derives a different agent.
