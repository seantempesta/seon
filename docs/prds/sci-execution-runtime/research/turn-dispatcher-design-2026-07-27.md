---
type: research
status: active
tags: [prd, research]
---

# Turn dispatcher design, 2026-07-27

## Decision

The end-state run loop is one **dispatcher proc**, not one turn runner. One
pass pins one database value, derives at most one instruction for every
admissible agent from that value, submits every instruction to the bounded
`:io` class, and returns. Task completion removes the agent from the
dispatcher's disposable active set and offers one coalescing wake. A serial
runtime is this same mechanism with concurrency `1`.

The shipped default is `1`. The dial is
`:seon.config.flow.io/concurrency`, paired with
`:seon.config.flow.io/queue-depth`. That name follows the existing
workload-owner convention
`:seon.config.flow.compute/{concurrency,queue-depth}` rather than inventing
`:seon.config.loop/turn-concurrency`: the bounded resource is the launcher's
Flow `:io` class, and turns are its first submitted workload. The existing
names and their acquired-default rule are at
`src/seon/schema/flow.edn:1-2`,
`src/seon/flow.clj:291-317`, and `src/seon/config.cljc:120-130`.

This is safe only with two distinct fences:

- The **durable per-agent fence** is
  `:seon.cluster.agent/run`. `open-call` reads it inside the transaction and
  refuses `::agent-already-running`; the same transaction asserts the run and
  pointer from one resolved agent ref
  (`src/seon/cluster/run.cljc:212-243`).
- The **process-local execution fence** is the dispatcher's set of active
  agent ids. It prevents a second task for an agent whose first submitted
  task has not completed. It is disposable scheduling state, not durable
  truth: after process death the database run, process, claim epoch, lease,
  forms, and receipts determine what survives.

The first fence proves that an agent cannot acquire two open runs. It does
**not** by itself prove that two tasks cannot interleave on the same already
open run. In particular, duplicate tasks can both derive `:call` for the same
run and process before either freezes a plan; both could make the paid model
call before `plan-call` refuses the loser. The active-agent set is therefore a
required strengthening, not an optimization.

No durable `busy?`, queue row, or scheduler entity is added. The database
remains the intermediary for all durable races; the active set only prevents
one live dispatcher from submitting redundant work that the database would
later refuse.

## Dependency ledger

| mechanism | selected source | contract used here |
|---|---|---|
| core.async Flow | `reference-code/core.async` at `dc35f3e0d7bc2eef502e77982f48641f025c8051`, tag `v1.10.874-alpha3` | ordinary proc state is sequential; custom launchers own non-ordinary threads and lifecycle |
| Datahike | `reference-code/datahike` at `357ffc87c8009f342b239145802e1385d4a18ca9` | one local writer serializes transaction functions over its current immutable database value |
| Seon Flow launcher | `src/seon/flow.clj` | fixed-buffer submission plus active-count bounded execution, currently compute-only |
| Seon run transitions | `src/seon/cluster/run.cljc` | open, custody, lease, epoch, plan, receipt, release, and close fences |
| Seon work derivation | `src/seon/cluster/work.cljc` | pure next instruction and interruption derivation |
| Seon loop | `src/seon/cluster/loop.cljc` | current inline pass and the already named bounded-`:io` extension point |

Datahike's local writer accepts calls through one transaction queue and threads
each successful invocation's `:db-after` into the next invocation
(`reference-code/datahike/src/datahike/writer.cljc:85-117,154-188`).
`transact!` applies against that current writer value
(`reference-code/datahike/src/datahike/writing.cljc:862-879`).
Within one transaction, `:db.fn/call` receives the transaction's current
database value and returns more transaction data
(`reference-code/datahike/src/datahike/db/transaction.cljc:1138-1144`);
that returned data is processed before later transaction entities
(`reference-code/datahike/src/datahike/db/transaction.cljc:1223-1297`).
These are the facts behind “the database is the intermediary,” not an
assumption that two callers happened to read different bases.

Flow defines `:io` as allowed to block but not perform extended computation,
and `:compute` as forbidden to block
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:194-203`).
The default JVM `:io` executor uses virtual threads when available
(`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:71-96`).
The turn's model call is blocking I/O, while its SCI eval already crosses to
the bounded compute launcher (`src/seon/cluster/loop.cljc:21-26,312-320`).

## Baseline being replaced

The present proc does exactly one inline instruction per wake:

- `step` is an ordinary `flow/process` transform with workload `:io`
  (`src/seon/cluster/loop.cljc:189-218`).
- It settles every interruption, dereferences one post-settlement database
  value, calls `work/next-work`, and runs `turn` before returning
  (`src/seon/cluster/loop.cljc:237-259`).
- If another instruction remains, it offers a wake into its own
  `(sliding-buffer 1)` in-port
  (`src/seon/cluster/loop.cljc:260-266`).
- Its namespace contract deliberately names submitting turns to a bounded
  `:io` class as the extension point
  (`src/seon/cluster/loop.cljc:28-38`).

`next-work` is pure, but singular. It sorts the agents with an open run or
trigger, returns the first instruction, and prioritizes held work over a new
trigger for each agent (`src/seon/cluster/work.cljc:93-102,152-192`).
Interruptions are a separate plural derivation over all agents
(`src/seon/cluster/work.cljc:203-244`).

The current sealed wake remains correctly minimal. The listener offers only a
payload-free “look” signal, never parks, and uses a `(sliding-buffer 1)`
because facts—not channel contents—hold the work
(`src/seon/cluster/wake.cljc:6-29,99-134`). The dispatcher does not add wake
consumers: its pass remains the only consumer.

## Dispatcher pass contract

### Input and derivation

A wake invokes one single-threaded dispatcher transform.

1. Acquire `now` once.
2. Settle interruptions first, as today. An unclaimed, unplanned run keeps its
   agent busy, so deriving before settlement can strand it
   (`src/seon/cluster/loop.cljc:244-251`).
3. Dereference the connection exactly once after settlement. Call the new
   pure `work/all-work` with that database value and process request.
4. `all-work` returns a vector ordered by agent id, with at most one
   instruction per agent. It is the plural of the existing per-agent
   derivation, not repeated calls to `next-work` against moving database
   values.
5. Remove instructions whose agent id is already in the dispatcher state's
   active-agent set.

Every admitted task therefore carries:

- the exact derived instruction;
- its agent id and optional run id;
- the derivation basis transaction, for observation only;
- the pass instant;
- a unique submission id; and
- the cluster handle.

The basis transaction is evidence, not a write precondition. Eligibility is
rechecked inside run transaction functions against the writer's current
database value. Using the dispatcher's stale basis as a full-head CAS would
incorrectly make unrelated agents contend.

### Submission and return

“Admissible” includes capacity. The dispatcher derives every agent but selects
no more inactive agents than the launcher's acquired active-plus-queued
capacity can hold. For each selected instruction it injects one task and adds
the accepted `(agent-id, submission-id)` to its next immutable state. Work
beyond capacity remains database facts; a completion re-wakes the dispatcher
and gives it another admission opportunity.

This bounded batch is load-bearing. Flow's `inject` completes only after its
messages have been put
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:157-163`).
Blindly injecting every derived agent could park the dispatcher behind a full
queue while completed tasks fill the completion input that only the
dispatcher can drain. The launcher must therefore expose an atomic
accepted/full/closed admission result. “Full” is ordinary backpressure and
leaves the instruction unsubmitted; “closed” is a core fault.

The pass returns after every capacity-selected task is accepted. It does not
wait for a task to start or finish. The launcher's active count, not the
channel buffer, enforces `:seon.config.flow.io/concurrency`; queue depth bounds
accepted tasks waiting for an active slot. The completion input is fixed at
active-plus-queued capacity, so every accepted task has one reserved
completion position and completion delivery never depends on an unbounded
buffer.

A completion message is data containing submission id, agent id, report or
normalized fault, and completion instant. The dispatcher consumes it on its
own input set, removes the agent id from its active set, publishes the
observation report, and `offer!`s one wake. Completion is the readiness event;
there is no polling and no tuned delay.

The active id is removed only on a matching completion id. A stale or unknown
completion is a core fault; it cannot clear a newer task for the same agent.
The state therefore needs `agent-id -> submission-id`, not merely a set,
although the derived ping projection may expose the keys as a set.

### Serial is the same mechanism

At `:seon.config.flow.io/concurrency 1`, the launcher admits one active turn
task. The dispatcher may queue other agents, but their task bodies start
serially. No branch chooses “inline mode,” and no separate serial loop remains.

The queue depth is independently configurable because it bounds admitted but
not started tasks; concurrency bounds started tasks. The shipped queue depth
should initially equal the shipped concurrency (`1`) so the safe version does
not hide an unmeasured backlog. A later larger default requires measured queue
wait and memory, not intuition.

## Race audit

### Races the existing transitions already kill

| race | existing winner/refusal |
|---|---|
| Two tasks open different runs for the same agent from one stale basis | The first `open-call` asserts the agent pointer; the second reads that pointer inside its serialized transaction and refuses `::agent-already-running` (`src/seon/cluster/run.cljc:226-243`). |
| Two tasks open the same run id | The second reads the existing run and refuses `::run-exists` (`src/seon/cluster/run.cljc:226-232`). |
| Two tasks open runs for different agents | Both may commit. Their distinct agent pointers are independent; writer serialization supplies order without making either ineligible. |
| Two processes claim an unheld run | The first asserts process, incremented epoch, and lease; the second sees a live claim and refuses (`src/seon/cluster/run.cljc:256-287`). |
| A stale holder writes after takeover | `held-run` requires exact process, epoch, and live lease; mismatch or expiry refuses (`src/seon/cluster/run.cljc:173-195`). |
| Two replies freeze different plans | The first writes `::plan-digest`; the second reads it inside `plan-call` and refuses `::plan-frozen` (`src/seon/cluster/run.cljc:399-436`). |
| Two tasks start the same form attempt at one epoch | Receipt identity derives from run, ordinal, and epoch; duplicate start refuses `::receipt-exists` (`src/seon/cluster/run.cljc:448-502`). |
| Two tasks settle one receipt | Only a running receipt settles, so the first terminal outcome wins; the run suite asserts duplicate refusal and preservation (`test/seon/cluster/run_test.clj:262-338`). |
| A stale task closes or releases after displacement | Both transitions pass through `held-run`; close additionally refuses a broken agent pointer (`src/seon/cluster/run.cljc:318-384`). |

The same-agent open claim was also probed directly on 2026-07-27 with two
futures released by one latch against a fresh in-memory database. Observed:
one `::committed`, one `::agent-already-running` refusal, the pointer named the
winner, and the set of run ids contained only the winner. This is the exact
concurrent falsifier that must become a discovered test; the current
state-machine property is sequential
(`test/seon/cluster/run_test.clj:664-707`).

### Races introduced by dispatch

| new race | required answer |
|---|---|
| A second wake arrives while an agent's task is queued or active | Dispatcher state excludes that agent until matching completion. Coalescing does not provide this exclusion. |
| Duplicate `:call` tasks for one held, unplanned run | Prevented by the active-agent map. The plan fence alone is too late because the provider call is already paid. |
| Duplicate `:resume` tasks | Prevented by the active-agent map; receipt-start remains the durable last line of defense before eval. |
| One task completes while another agent's trigger commits | Completion and listener both offer the same payload-free wake. One coalesced wake is sufficient because the next pass scans all agents. |
| Queue admission races with task completion | The accepted admission reserves one completion position; the dispatcher records the mapping in the same transform before it can consume that completion. Completion carries the exact submission id. |
| Graph stop races with active submitted tasks | The launcher owns a `FutureTask` for every started task, stops admission, interrupts them, and publishes its stopped event only after all owned tasks exit. A replacement graph must await that event. |
| An interrupt-insensitive foreign call survives graph stop | Record a core fault and keep loop activity halted; do not start a replacement dispatcher beside the survivor. The JVM, REPL, database, and web UI stay up. |

The current `turn` also asks global `next-work` for only an ordinal while
continuing one agent's fold
(`src/seon/cluster/loop.cljc:472-487`). That is not admissible in the plural
world: another agent can be first globally. The revision must either complete
one form per submitted task and re-wake, or use a new
`work/next-agent-work` constrained to the task's agent. Recommendation:
retain one submitted task per whole fold so one SCI `ctx` spans its forms
(`src/seon/cluster/loop.cljc:412-427`), but replace the global lookup with
`next-agent-work`.

## Why wake coalescing still works

The `(sliding-buffer 1)` argument depended on one consumer and payload-free
signals. Both remain true.

- There is still one dispatcher transform reading the wake in-port.
- Task bodies do not consume wakes.
- A pass scans every agent at one database value instead of selecting one
  agent.
- Active agents are deliberately skipped; each completion emits another wake
  after making that agent admissible again.
- If a trigger wake and several completions collapse to one signal, the next
  pass observes all committed triggers and the current active map.

Thus wake multiplicity still carries no information. The only prohibited
ordering is removing an active mapping without issuing the completion wake;
the sealed suite must kill that class.

## Submitted-task options

### Option 1 — dynamic Flow child procs

Reject. A Flow graph declares its procs and channels in configuration
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:76-105`).
Creating one proc per turn means rebuilding topology for ordinary work.
A fixed worker-proc pool merely reimplements the bounded launcher as N
long-lived procs and makes the configured count structural rather than a
launcher dial.

### Option 2 — executor tasks owned by one custom launcher

Recommend. Extend the existing work launcher, which already has a submission
channel, completion channel, active count, and resolved executor
(`src/seon/flow.clj:215-289`), from compute-only to the two real classes.

For `:io`, the launcher uses `spi/get-exec resolver :io` and submits retained
`FutureTask`s. It starts tasks only while active count is below the acquired
class concurrency, reports faults on `::flow/error`, and sends completions on
the internal completion channel. The active task table and futures are
process-local observability and lifecycle state.

This requires a custom launcher because ordinary `flow/process` is
single-step-state sequential. Its implementation calls transform, receives
the next state, sends outputs, and only then reads another input
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:271-322`).
If a transform hands work to an executor and returns, Flow knows only the
returned state. It does not adopt the spawned task, merge later state, catch
its exception, or stop it.

The SPI explicitly exists for processes impossible with ordinary
`flow/process` and requires control-priority channel operations, cleanup of
all threads on stop, and error reporting
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:11-58`).
The launcher must satisfy that contract, including an explicit stopped event;
Flow's public `stop` sends control and clears graph channels but is not itself
a join (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:164-183`).

### Option 3 — `core.async/go`

Reject. A turn blocks on provider and database I/O. Go dispatch explicitly
detects blocking calls on its dispatch threads and throws when checking is
enabled
(`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:38-61`).
Putting blocking calls in `go` either monopolizes dispatch threads or smuggles
the same executor-task design behind callbacks, outside Flow lifecycle.

## Configuration and sealed-contract revisions

The implementation wave must revise every owner below together. There is no
parallel “dispatcher v2.”

1. `src/seon/schema/flow.edn`
   - add `:seon.config.flow.io/concurrency` and
     `:seon.config.flow.io/queue-depth`, both positive integers;
   - extend `:seon.flow/launcher-configuration` with both required facts;
   - extend `:seon.flow/workload` from `[:enum :compute]` to
     `[:enum :compute :io]`;
   - split the work-submission schemas where compute's time-limit backstop is
     not a truthful required field for a turn task;
   - add completion and stopped-event shapes.
2. `src/seon/schema/config.edn`, `config/default.edn`, and `src/seon/config.cljc`
   - include both `:io` facts in manifest, effective map, and singleton
     entity;
   - ship concurrency `1` and queue depth `1`;
   - keep the existing machine-derived compute default unchanged.
3. `src/seon/flow.clj`
   - generalize the one launcher to per-class channels, active counts,
     acquired queue depths, and acquired concurrency;
   - retain started `FutureTask`s, implement control-priority stop, and
     publish a stopped completion only after owned tasks exit;
   - add non-awaiting capacity-reserving `try-submit!` for `:io`, returning
     accepted/full/closed; retain awaiting `submit!!` for the compute eval
     boundary;
   - project per-class queued, active, completed, and oldest-wait metrics.
4. `src/seon/cluster/work.cljc` and `src/seon/schema/work.edn`
   - factor the existing agent-local decision into `next-agent-work`;
   - replace the singular scan owner with `all-work`, one ordered vector from
     one database value;
   - keep interruption derivation separate.
5. `src/seon/cluster/loop.cljc` and `src/seon/schema/loop.edn`
   - change `step` from inline turn execution to derive/filter/submit/return;
   - add the active `agent-id -> submission-id` state and completion in-port;
   - make ping report dispatches, queue admissions, active agents, completions,
     refusals, and oldest active age;
   - make completion the only removal path and always self-rewake;
   - replace the fold's global `next-work` lookup with agent-local derivation.
6. The cluster graph/boot owner
   - acquire both new facts from the config singleton;
   - configure the launcher's `:io` channel before the dispatcher resumes;
   - inject the one boot wake only after listener, launcher, and completion
     path are ready;
   - await the launcher's stopped event before topology replacement.

The sealed suites require these exact revisions:

- `test/seon/cluster/run_test.clj`: add the latch-controlled two-opener,
  same-agent falsifier and assert commit/refusal, one pointer, and no loser
  run.
- `test/seon/cluster/work_test.clj`: prove `all-work` returns every eligible
  agent once in deterministic order from one basis, while two triggers for one
  busy agent still yield one instruction.
- `test/seon/cluster/loop_test.clj`: seal that a pass submits all admissible
  agents without running a turn inline, excludes active agents, ignores stale
  completions, removes only a matching completion, and completion re-wakes.
- `test/seon/cluster/turn_test.clj`: drive two agents and prove one agent's
  fold never consumes the other's ordinal; preserve one SCI `ctx` per run.
- `test/seon/cluster/wake_test.clj`: retain the one-listener,
  nonparking, computed-disjointness properties and add the composition case
  where several completions plus a trigger coalesce yet all work is found.
- `test/seon/flow_test.clj`: seal queue bound versus concurrency separately
  for `:io`, completion delivery at saturation, pause/resume, task fault
  continuation, stop interruption, and stopped-event join.
- `test/seon/config_test.clj` and `test/seon/reconcile_test.clj`: require the
  two new facts, default `1`, validation refusal below `1`, and converged
  zero-write apply.
- The cluster boot/live suite: prove the listener and completion input are
  ready before the boot wake, and prove a graph rebuild never overlaps old
  and replacement turn tasks.

## Sealed falsifier sketch

The class-killer is one discovered test with latches, not sleeps:

1. Build a fresh in-memory database through the boot-derived schema path.
2. Create agents A and B, each with one trigger.
3. Configure `:io` concurrency `2`.
4. Hold both task bodies immediately after admission. Assert the dispatcher
   returned, both agent ids are active, and both tasks started.
5. Commit a second trigger for A and inject several wakes. Assert no second A
   task starts while B may proceed.
6. Release A and B together. Their matching completions clear both active
   entries and coalesce to one wake.
7. Hold the newly admitted A task and race two raw `open-tx` operations for a
   separate agent C. Assert exactly one commit, the loser rule is
   `::agent-already-running`, C has one pointer, and only the winning run
   exists.
8. Stop the graph while one task is held. Assert stop interrupts it, the
   stopped event does not publish before the task exits, and a replacement
   graph never overlaps it.

The recurring invariants after every event are:

- at most one active submission per agent;
- at most one open run pointer per agent;
- every active mapping names a launcher task or a completion waiting to be
  consumed;
- every successful task mutation satisfies process, epoch, and live lease;
- no task starts a paid call after losing open admission;
- no wake count is used as a work count; and
- all durable work remains derivable after discarding dispatcher state.

At concurrency `1`, replay the same event sequence and assert the same durable
facts and reports modulo timing and submission ids. This is the proof that
serial is configuration, not another implementation.

## Measurement gate before landing

Run two configurations against identical fixed work: concurrency `1` and each
candidate above it (`2`, then `4`; stop when a measured resource regresses).
Record conditions: machine, core count, model/provider, prompt and response
sizes, Datahike backend, queue depths, agent count, cluster count, and exact
commit.

### Two-cluster live proof

Run one real agent in each of two clusters concurrently, then wedge one
cluster's provider call within its real external deadline. Measure:

- per-cluster trigger-commit to task-start latency;
- per-cluster full turn latency, p50/p95/max;
- writer committed transactions per second and transact p50/p95/max;
- queue admission wait and active/queued depth by class;
- wake starvation: trigger-commit or completion instant to the next dispatcher
  pass that observes it, p50/p95/max;
- whether the healthy cluster's latency or writer throughput changes while
  the other cluster is blocked; and
- stop/rebuild settlement latency with no overlapping old task.

Acceptance is isolation with an explained numeric ceiling, not merely both
turns eventually completing.

### Same-cluster two-agent drive

Run two real agents in one cluster with synchronized triggers and provider
responses long enough to overlap. Measure:

- launch spread between the two task starts;
- each agent's trigger-to-start, provider, eval, commit, and full-turn
  latency;
- aggregate writer throughput and transact latency under their commit bursts;
- dispatcher queue wait, active depth, and completion-to-next-pass latency;
- maximum interval during which a queued eligible agent receives no pass;
- model-call overlap and compute-eval overlap separately; and
- durable safety: one open run per agent, one terminal receipt per admitted
  form and epoch, no duplicate provider call for one run, no stale successful
  write, and no unanswered trigger after quiescence.

The safe version lands only if concurrency `1` is behaviorally equivalent to
the sealed serial contract and concurrency `2` improves overlap without
material writer-throughput collapse or wake starvation. Any higher default is
a later measured decision.

## Failure policy: fail loud is not fall down

- A task returns an agent/model error value through the ordinary turn
  contract. It completes, clears its active mapping, and re-wakes.
- An unexpected task throwable is reported on Flow's error channel, normalized
  and committed as a core fault, and still produces a completion so one agent
  cannot remain process-locally wedged.
- Under development `:panic`, the affected loop activity halts after the fault
  is committed. It does not kill the JVM, REPL, database, or web UI.
- Under production `:record`, other agents continue when custody invariants
  remain trustworthy. There is no silent retry of a paid call.
- A full submission buffer refuses this pass's admission and exposes
  saturation; the work remains in database facts and completion re-wakes. It
  never drops a turn or parks the one dispatcher behind its own completion
  input.
- A completion channel overflow, unknown completion id, active-map mismatch,
  or task surviving stop is a core fault. None is repaired by deleting state
  or guessing that the task ended.
- A refused database transition is an expected losing race and a reportable
  task outcome, not a process crash. Repeated refusal for an active-map
  invariant violation is nevertheless a core design fault, because the local
  fence should have prevented the redundant submission.

This keeps the simplification test honest: one dispatcher, one submitted-task
mechanism, one database transition authority, and serial execution as the
number `1`.
