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

A message opens one run with `:seon.cluster.run/id`, `/agent`, and `/opened-at`,
and asserts the agent's `/run` pointer in the same transaction. The transition
refuses when the run identity already exists or the agent already points to an
open run. The run's own `/agent` ref is the authority used when closing and
retracting that pointer.

Custody is the one `:seon.cluster.run/process` string:

- absent → a process may claim it;
- present and in the supplied live-process set → the claim is not stealable;
- present but not live → takeover first stamps every dangling receipt
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

An agent's graph contains the run-loop proc and the wake proc. The process-local
routing map connects the agent entity id to its mailbox; it is disposable live
channel state, not a database registry. The blueprint differs only by agent id
and cluster handle.

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

Agent-driven evaluation uses the cluster's one live SCI `ctx`. Every invocation
installs one zero-argument `:interrupt-fn`; SCI calls it at every interpreted
function-body entrance. The configured `:seon.sci.eval/time-limit-ms` is the
only execution limit. Expiry invokes SCI's uncatchable `interrupt!` and returns
a flat error value; no exception escapes into a proc.

The admission record's `:seon.eval/fn-entries`, `/host-interop-count`,
`/duration-ms`, `/allocated-bytes`, and `/outcome` are in-memory diagnostics,
not durable receipt attributes or limits. Result admission enforces node,
collection, depth, and string ceilings; large serialized results keep a bounded
projection plus `:seon.cluster.eval/result-blob` and `/result-size`.

The session is a faithful REPL. A definition becomes live in the cluster `ctx`
when SCI evaluates it, even if later persistence refuses. The persistence gate
decides which program and database facts the terminal transaction may commit;
it does not restrict which functions an agent may call.

## Live program graph and session image

One cluster has one live program graph and SCI context. An agent definition is
immediately visible to every agent in that cluster; no other cluster shares it.
Cold acquisition rebuilds from program and session facts at boot or recovery,
not at each turn.

Contracted functions persist as `:seon.fn` rows. Their canonical `/spec`,
Malli-derived arity rows, and parsed AST facts commit through the same producer.
Namespace resolver inputs persist as `:seon.ns` plus owned alias/import/refer
bindings. Uncontracted REPL definitions persist under `:seon.code.def`, keyed
by namespace and name:

- a proven replay-safe defining form uses `/source`;
- an effectful but faithful value uses `/value-edn` or `/blob` plus `/size`;
- a value that cannot be restored uses `/unrestorable`; and
- `/ordinal` supplies deterministic restore order.

Restore interns every name, binds faithful values, re-evaluates only forms
whose purity is proven from analysis and capability reachability, then states
unrestorable names. Nothing effectful re-executes during recovery. Namespace
ownership is `:seon.cluster.agent/namespace`; it coordinates who should edit a
namespace and never gates callability.

## Messages and agent creation

Formal creation transacts the namespace row and agent row together. The agent
has an identity, cluster ref, namespace ownership ref, optional additive
instructions, and no current run. Committing `:seon.cluster.agent/id` also wakes
the cluster's arm owner so a graph is created for the new agent.

A message is one recipient, content, and instant, with optional sender and
`about` ref. Absence of `/from` means the message came from outside the agent
population; no origin enum repeats that fact. The `/to` ref is the wake
attribute. Answeredness follows from the run-opening transaction metadata that
records its trigger, not a flag on the message.

There is no durable parent tree, schedule entity, interaction entity, browser
session, hop counter, or delivery acknowledgement in the runtime model.
Subagents are ordinary agents connected by messages and namespace ownership.

## Crash recovery

Nothing re-executes after a process dies. Recovery scans open runs and compares
their custody strings with the live-process set. For each run whose holder is
absent or dead, one transaction:

1. stamps every receipt with no terminal fact `/interrupted-at`;
2. retracts dead custody when present;
3. asserts the run's `/closed-at`; and
4. retracts the owning agent's `/run` pointer when it still points there.

Settled receipts remain untouched. Recovery never reopens, replans, retries a
provider call, or evaluates an unstarted plan suffix. The run and receipt
renderers tell the next agent episode what may have happened and what did not
run; the agent adapts from those facts.

## Process and workload boundaries

One process root holds the physical Datahike store lock and shared executors;
each cluster owns its branch connection, program graph, agents, render graph,
and web service. The run's process identity is custody, not agent identity or a
second process registry.

Every proc explicitly uses `:io` or `:compute`. Remote model calls and socket
writes may block on `:io`; SCI evaluation and pure derivation run on bounded
`:compute`; unresolved mixed chains fail closed to Flow's expensive `:mixed`
workload. Core faults travel through Flow's error channel to the one fault
committer. Agent mistakes become flat values and receipts.

## Source authority

- The agent, run, message, context, AI, eval, and program sections of
  `resources/seon/schema.edn` own runtime shapes.
- `src/seon/cluster/run.cljc` owns in-transaction run and receipt transitions.
- `src/seon/cluster/{agent,wake,work,loop}.clj*` owns per-agent graph lifecycle,
  wake routing, work derivation, and the fold.
- `src/seon/sci/eval.clj` owns interruption, live context acquisition, and
  session restore.
- `src/seon/render/{agent,transcript}.clj` owns the current queries and renders
  over agents, messages, runs, forms, and receipts.

## See also

- [[architecture]] — process topology, Flow scheduling, and the effect seam.
- [[data-model]] — the admitted attribute census.
- [[context]] — the REPL-session context and context-capture contract.
- [[observability]] — forensic use of captures, attempts, receipts, and errors.
