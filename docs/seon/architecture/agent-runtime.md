---
type: architecture
status: active
tags: [architecture, agent, runtime]
---

# Agent runtime — per-agent graphs, runs, and recovery

> **Target design** (present tense). Implementation state, gaps, order, and
> evidence live only in [[roadmap]].

Every agent owns one `core.async.flow` graph created from the same blueprint.
Its messages wake that graph; the graph re-reads one immutable database value,
derives the next work, performs only that work, and commits through the one
cluster transaction owner. There is no central dispatcher, scheduler entity,
private work queue, turn entity, or runtime status row.

Each cluster owns one acquired base SCI `ctx`, and each turn evaluates in a
fresh fork of that live base. The fork is turn-private mutable interpreter
state; the database program graph and each agent's defs are the durable
authority. Agents share contracted definitions by committing program facts;
their uncontracted definitions remain in their own desks.

## State is attribute presence

The agent and run store primitives:

- `:seon.cluster.agent/run` present means the agent has an open run; absence
  means idle.
- `:seon.cluster.run/closed-at` absent means the run is open.
- `:seon.cluster.run/process` present means a process holds custody; absence
  means unheld.
- `:seon.cluster.run/plan-digest` present means the form plan is frozen.
- An eval receipt carrying none of `:seon.cluster.eval/result-edn`, `/error`, or
  `/interrupted-at` is running. Presence of one of those facts is terminal.

No stored status, phase, epoch, lease, heartbeat, claimant, pause, termination,
or answered flag restates these facts. Start, pause, resume, and terminate are
not persisted disposition kinds. Flow commands may park or resume an agent's
process-local graph without inventing durable lifecycle state.

## Runs are claimable database state

A message opens one run with `:seon.cluster.run/id`, `/agent`, `/opened-at`,
`/opening-commit-id`, `/starting-ns`, and optional `/trigger`, and asserts the
agent's `/run` pointer in the same transaction. The opening commit fixes the
program generation recorded for the run; the starting namespace is
the agent's assigned namespace. The transition refuses when the run identity
already exists or the agent already points to an open run. The run's own
`/agent` ref is the authority used when closing and retracting that pointer.

A revision run may point through `:seon.cluster.run/supersedes` to the original
run. An original with an adopted revision is absent from the active-run
projection without being deleted, so history and the replacement relationship
remain queryable.

Custody is the one `:seon.cluster.run/process` string:

- absent → a process may claim it;
- present and in the supplied live-process set → the claim is not stealable;
- present but not live → takeover first stamps the run
  `:seon.cluster.run/interrupted-at` and every dangling receipt
  `:seon.cluster.eval/interrupted-at`, then replaces custody in the same
  transaction.

Every transition decision runs inside Datahike as
`[:db.fn/call #'transition request]`. The function reads the mid-transaction
database value, refuses by throwing so the whole transaction aborts, and
returns plain transaction data otherwise. Callers do not pre-read eligibility
or send observed-state fields.

Release requires exact custody and retracts only `/process`. Close requires
exact custody, asserts `/closed-at`, retracts custody, and retracts the owning
agent's `/run` pointer atomically. Plan freeze and receipt settlement use the
same held-run fence. A terminal receipt cannot be overwritten or reopened.

## One graph per agent

An agent's graph contains the mailbox proc, turn proc, and schedule proc. The
process-local routing map connects the agent entity id to its mailbox; it is
disposable live channel state, not a database registry. The blueprint differs
only by agent id and cluster handle.

One pass:

1. receives a coalesced wake meaning only “look”;
2. dereferences the branch connection once;
3. derives work from messages, the agent's current-run ref, run facts, forms,
   receipts, context captures, and provider attempts;
4. acquires or verifies run custody inside the transaction;
5. performs the named pure, model-call, or eval operation on the proc workload
   selected by the graph;
6. commits the resulting facts under the run and receipt fences; and
7. closes, releases, or waits for another wake.

The wake payload never becomes work. Database facts are the durable input; a
lost channel value is free because the next wake re-derives the same question.
The message `:seon.cluster.message/to` datom is the work wake. Run, form,
receipt, and agent-pointer datoms are deliberately not wake attributes, so work
the loop commits cannot wake itself.

## Prompt, model call, plan, and eval fold

Before the unobservable model call, the loop commits one
`:seon.context.capture` row containing the exact prompt, rendered basis, and
ordered contribution evidence. The provider fold may stream complete prefixes
to the lossy render path, but only the settled attempt observations become
facts. Each model call records a `:seon.ai.attempt` row after the call; error
presence and wire-phase evidence determine failover or failure at read.

A readable reply becomes one frozen plan:

- `:seon.cluster.run/plan-digest` fences plan publication;
- each `:seon.cluster.run.form` row carries `/run`, `/ordinal`, `/source`, and
  the reader's optional namespace ref; and
- every form opens one `:seon.cluster.eval` receipt before evaluation.

The fold starts from the plan transaction's `:db-after`. Each terminal receipt
transaction supplies the next immutable database value. A receipt records the
form's admitted result, printed output, ending namespace, error, interruption,
and optional blob projection. Pure `my.run` disposition values decide whether
the run loop closes or releases:

- `my.run/complete` supplies the final result and closes the run;
- `my.run/wait` leaves a note in the last eval result and releases custody so a
  later message can wake the same open run.

Message and declination values are also pure. The loop interprets them and
commits ordinary `:seon.cluster.message` rows in the terminal transaction.
Agent code never transacts a delivery side channel.

## SCI interruption and admission

Agent-driven evaluation uses the turn's fresh
fork of the cluster base SCI `ctx`. `seon.sci.kernel` is the one guarded owner,
with exactly two entrances: `seon.sci.eval/evaluate` for a form, and
`seon.sci.kernel/invoke` for a named live Var — the entrance every renderer call
takes. They share one process guard, one arming rule, one deadline, one
admission, and one failure classifier, so their semantics cannot drift; the
invoked symbol is the only difference between the two error faces.

The context carries one stable zero-argument `:interrupt-fn`; SCI calls it at
every interpreted function-body entrance. The configured
`:seon.sci.eval/time-limit-ms` is the only execution limit. Arming is
per-thread: work reached while the identical context is already armed on that
thread inherits the governing arm and its deadline, so nested work can never
restart the clock, and a different context on an armed thread is refused.
Expiry invokes SCI's uncatchable `interrupt!` and returns a flat error value;
no exception escapes into a proc, including the arming refusal itself.

The admission record's `:seon.eval/fn-entries`, `/host-interop-count`,
`/duration-ms`, `/allocated-bytes`, and `/outcome` are in-memory diagnostics,
not durable receipt attributes or limits. Result admission enforces node,
collection, depth, and string ceilings. A serialized result above the blob
eligibility floor keeps a bounded projection plus
`:seon.cluster.eval/result-blob` and `/result-size` only when that complete
stored shape is smaller than retaining the full result inline.

The run is a faithful REPL. A definition becomes live in that run's fork when
SCI evaluates it, even if later persistence refuses. The persistence gate
decides which program and database facts the terminal transaction may commit;
it does not restrict which functions an agent may call. A `defn` evaluates to
the Var face, while an execution failure evaluates to its flat error face.

## Live program graph and the agent's defs

One cluster has one live program graph and acquired base SCI context; no other
cluster shares either. Every turn forks that live base and begins from the
run's current namespace. A contracted definition becomes cross-agent visible
after its facts join the program graph and the terminal transaction installs
the committed row into the base. The next turn's fork sees that install.
Boot acquisition rebuilds the program-only base; the same per-turn fork path
then rehydrates the selected agent's defs across turn boundaries, run
boundaries, JVM bounces, and stateless resume.

Contracted functions persist as `:seon.fn` rows. Their canonical `/spec`,
Malli-derived arity rows, and parsed AST facts commit through the same producer.
Namespace resolver inputs persist as `:seon.ns` plus owned alias/import/refer
bindings. Uncontracted REPL definitions persist under `:seon.def`, keyed by
agent plus qualified name:

- a faithful value uses `/value-edn` or `/blob` plus `/size`;
- a supported function root uses the same fact-safe value representation;
- a value that cannot be restored uses `/unrestorable-reason`;
- an atom stores its last settled value and `/atom?`; and
- `/ordinal` supplies deterministic restore order.

Restore binds faithful values and supported function roots directly from
facts, recreates atoms around their last settled values with one honest REPL
notice, and states every unrestorable name. No agent-authored form re-executes
during recovery. Exact replacement of the agent's defs
shares the terminal receipt transaction; clearing is explicit and agent-local.
Namespace ownership is `:seon.cluster.agent/namespace`; it coordinates who
should edit a namespace and never gates callability.

## Session curation

**[TARGET — ruled 2026-08-04]** An editor may revise a run it did not author.
It works in its own candidate context and scratch branch and returns a
**revision**: an ordered vector of form sources as data, not the editor's own
session. The system then performs a **proof** by mechanically re-executing the
revision on a fresh fork at the original run's opening commit. Proof makes no
model call and fails closed before any external sink.

Adoption requires zero error receipts, a terminal completed result, declared
content, and equivalence to the original intent. One transaction commits the
proved forms and receipts as a new run and connects it to the original with
`:seon.cluster.run/supersedes`. The original remains forensic history, but
queries for active runs exclude superseded runs. There is one future per
original: no context merge, replay of the editor's exploratory session, or
destructive rewrite of history.

## Messages and agent creation

Formal creation transacts the namespace row and agent row together. The agent
has an identity, cluster ref, namespace ownership ref, optional additive
instructions, and no current run. Committing `:seon.cluster.agent/id` also wakes
the cluster's arm owner so a graph is created for the new agent.

A message is one recipient, content, instant, and optional numeric ordinal,
with optional sender, `about`, and `caused-by` refs. Delivery vectors record
their source position in `/ordinal`; inbound singletons record zero. Consumers
order equal instants by transaction, ordinal, and numeric entity id, never by
the message identity string. Absence of `/from` means the message came from
outside the agent population; no origin enum repeats that fact. The `/to` ref
is the wake attribute. A run records its `/trigger` ref when it opens, so
answeredness is the presence of that connection rather than a flag on the
message or a temporal transaction artifact. Outbound delivery records the
trigger as the message's `/caused-by` ref, making conversation depth an
ordinary ref walk.

There is no durable parent tree, interaction entity, browser session, hop
counter, or delivery acknowledgement in the runtime model. Subagents are
ordinary agents connected by messages and namespace ownership.

Scheduling is per agent. Declared task, schedule, and fire identities feed the
schedule proc in the owning agent's graph. A due fire atomically claims its fire
and maintenance receipt, invokes the task's declared Var directly without a
model turn, and settles the receipt. Only an error settlement creates a message.
There is no central ticker.

Root owns the maintenance portfolio—database and blob reclamation, footprint
inspection, dead-root cleanup, log retention, process census, and related
repair—as ordinary root tasks. Scheduled work and explicit operator work invoke
the same owners. Explicit reset is authorization to remove the complete managed
`data/clusters` tree and succeeds only when its returned cleanup result reports
no residual paths.

## Crash recovery

Nothing re-executes after a process dies. Recovery scans open runs and compares
their custody strings with the live-process set. For each run whose holder is
absent or dead, one transaction:

1. stamps the run `:seon.cluster.run/interrupted-at`;
2. stamps every receipt with no terminal fact `/interrupted-at`;
3. retracts dead custody when present;
4. asserts the run's `/closed-at`; and
5. retracts the owning agent's `/run` pointer when it still points there.

Recovery marks what it interrupted, so "which runs did the last recovery cut?"
is a query over `:seon.cluster.run/interrupted-at`, never a count on a
process-local boot value. The run stamp is not a summary of the receipt
stamps: a process that died before its first receipt row existed leaves no
receipt to stamp, and without the run fact that run would be indistinguishable
from a normal close. Settled receipts remain untouched. Recovery never reopens, replans, retries a
provider call, or evaluates an unstarted plan suffix. The run and receipt
renderers tell the next agent episode what may have happened and what did not
run; the agent adapts from those facts.

## Process and workload boundaries

One process root holds the physical Datahike store lock and shared executors;
each cluster owns its branch connection, program graph, agents, render graph,
web service, and bounded compute work-launcher graph. Submissions carry that
cluster-owned launcher explicitly; starting or stopping a sibling cluster
cannot replace its configuration or interrupt its accepted work. The run's
process identity is custody, not agent identity or a second process registry.

Every proc explicitly uses `:io` or `:compute`. Remote model calls and socket
writes may block on `:io`; SCI evaluation and pure derivation run on bounded
`:compute`; unresolved mixed chains fail closed to Flow's expensive `:mixed`
workload. Core faults travel through Flow's error channel to the one fault
committer. Agent mistakes become flat values and receipts.

Orderly agent stop joins the turn's completion event. While work remains
process-observable, database commits wake that join without polling. A durable
prompt capture is the boundary after which a remote provider call may be
unobservable; only there may teardown arm a loud backstop derived from the
turn's effective provider timeout and finite retry budget. Per-agent error
fan-out blocks on the shared `:io` virtual-thread executor, never on a parked
platform worker.

## Source authority

- The family declarations under `resources/seon/schemas/` own runtime shapes.
- `src/seon/cluster/run.clj` owns in-transaction run and receipt transitions.
- `src/seon/cluster/{agent,wake,work,loop}.clj*` owns per-agent graph lifecycle,
  wake routing, work derivation, and the fold.
- `src/seon/sci/eval.clj` owns interruption, live context acquisition, and
  per-turn fork and rehydrating the agent's defs.
- `src/seon/render/{agent,transcript}.clj` owns the current queries and renders
  over agents, messages, runs, forms, and receipts.

## See also

- [[architecture]] — process topology, Flow scheduling, and the effect seam.
- [[data-model]] — the admitted attribute census.
- [[context]] — agent context, continuity of the agent's defs, and the context-capture contract.
- [[observability]] — forensic use of captures, attempts, receipts, and errors.
