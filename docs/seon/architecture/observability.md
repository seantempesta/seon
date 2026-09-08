---
type: architecture
status: active
tags: [architecture, agent, database]
---

# Observability — inspect any agent and turn

> **Target design** (present tense). Implementation state, gaps, order, and
> evidence live only in [[roadmap]].

Agent forensics are queries over messages, turns, provider attempts,
evaluations, program rows, and error facts. Process logs remain necessary
for startup, readiness, transport, and crashes, but they are not a second
durable agent-history model.

## The evidence spine

One episode leaves a connected set of facts:

```text
agent ← message (a wake)
  ↑        ↓
  └── turn ← provider attempt(s)
        └── evaluation(s)
               ↓ optional error/problem refs
```

The durable joins are concrete:

- the agent is `:seon.agent/id`;
- messages point through `:seon.message/to`, optional `/from`, optional
  `/about`, and optional `/caused-by`;
- a turn points through `:seon.turn/agent`; "which turn answered a given
  wake" is a query comparing the wake's `:t` against turns' own `:t`, not a
  stored trigger ref;
- provider attempts are the turn's component `:seon.turn/attempts`;
- evaluations point to the turn through `:seon.eval/turn` and order by
  `:seon.eval/ordinal`; and
- error facts may point to `/agent` and `/steward`.

No turn identity beyond `:seon.turn/id`, phase cursor, custody stamp, route
row, or browser-session entity is required to reconstruct that chain.

## The basis — what the model saw, reproduced exactly

There is no separately committed prompt-capture row. **The basis is the
turn's own transaction `:t`** — the `:t` on the turn's identity datom.
Because the turn opens before the context is projected, `(as-of db turn-t)`
is the exact database value `project(db, agent)` read, and replaying that
projection under the same adopted program commit and the same render profile
(which includes `:seon.render/distance`) reproduces the sent prompt
byte-for-byte. `:seon.ai.attempt/prompt-digest` is the byte-identity
witness recorded on the attempt itself.

This replaces context captures and per-segment contribution rows: nothing
needs to be stored beyond the basis, because the projection is pure and
deterministic. "What did the model see for this turn?" is
`(seon.turn/basis-db turn)` piped through the same projection function the
loop itself calls, not a lookup into a separate evidence table.

## Provider attempts — what crossed the external boundary

One `:seon.ai.attempt` row, a component of its turn, records each completed
observation of a model call. It retains:

- turn, attempt instant, and `/prompt-digest` (the byte-identity witness
  above);
- the exact endpoint, model, and canonical effective settings used;
- open provider usage, finish reason, and optional reasoning content or
  blob;
- HTTP and request/response/output phase observations; and
- error, failover-from, and retry-delay facts when present.

`:seon.ai.attempt/ordinal` is deleted — component-set membership under the
turn plus attempt instant is sufficient, and the count was always taken
before the write. These are observations, not replay authorization.
Error-ref presence means the attempt failed. `failover-from` identifies
which attempt supplied the failure context. Retry disposition, error class,
normalized usage, and whether an attempt was primary or backup derive from
those facts; no outcome or role enum duplicates them.

The attempt row is written in the same commit as the turn's reply and
evaluations — after the external call, before evaluation. A process that
dies during the call leaves no attempt at all, which is the honest limit:
the database says the call was not recorded, not that it certainly never
happened. Recovery never retries it. A turn's wakes are answered only when
some attempt on it actually produced the reply (§"Waking" in
[[agent-runtime]]); a turn with only failed attempts answers nothing.

## Evaluations — the authentic REPL history

The session displays messages through explicit query forms and their
returned values beside evaluations. One entity per `(turn, ordinal)` carries
both halves: the form's exact `source`, `ordinal`, `comment`, and optional
reader `ns`, and — once run — its `ending-ns` when changed, printed `out`,
admitted `value` (or blob), `error` with `triage-edn`, or `missing` naming
why (over the storage bound, unserializable, or lost).

Evaluation state is presence:

- no `value`/`missing`/`error`/`interrupted-at` → running;
- `value` or `value-blob` → returned value;
- `missing` → the value could not be stored, with a reason and the size
  reached;
- `error` → failed evaluation; and
- `interrupted-at` → asserted only at boot, on an evaluation whose turn was
  still open.

Neither the evaluation nor the turn stores `ok?`, status, error-data, phase,
or outcome. The turn's own `/closed-at` and `/reply` facts explain whether
work is open, mid-reply, or closed; whether it produced anything is read
from its evaluations and attempts directly.

Printed REPL text is rendered from durable source, output, and result data.
`:seon.eval/value` remains the data projection; the text and HTML faces come
from the one print grammar and may re-render without changing the
evaluation.

## Large values and blobs

Content-addressed blobs use SHA-256 `:seon.blob/digest`. A result above the
configured eligibility floor moves to a blob only when the complete
blob-side shape — bounded projection, digest/size envelope, and binary
payload — is smaller than the full inline evaluation. Such evaluations keep
the bounded projection plus `:seon.eval/value-blob` and `/size`; provider
attempts use the same digest family for large reasoning content and for
`/prompt-digest`; a turn's reply uses `:seon.turn/reply-blob` the same way.

Blob state never becomes a second lifecycle or replay log. The referencing
row carries the semantic identity, digest, and size. Consumer presentation
is fitted separately by its render profile at the one AI-context elision
boundary; HTML renders the stored value without a second bound. A missing
blob is a loud forensic failure attached to the referencing fact — a reply
with `:seon.turn/reply-missing :lost`, or an evaluation with
`:seon.eval/missing :lost`, not silent absence.

## Error facts

Agent-facing failure is the flat `:seon.error/value`. A failure worth
retaining becomes one `:seon.error/fact` with identity, instant, process
identity, kind, message, content signature, bounded data projection, capped
flag, and optional class/Flow/agent/instrumentation evidence.
`:seon.error/steward` — function → namespace → `:seon.ns/steward`, computed
inside the committing transaction — is itself a listened attribute: a fault
wakes its steward exactly like a message wakes its recipient, and the turn
bound is what stops that loop, not a per-item escalation guard.

Kinds are producer-owned namespaced keywords, never a central enum or entity
discriminator. Recurrence is a query over `/signature`. The core fault
committer retains at most one bounded fact per signature and process: its
disposable signature set collapses repeat attempts while a database writer
is unavailable, and a database query remains the authority after a proc
rebuild. Distinct signatures remain distinct; no stored recurrence tally is
needed. The `/agent` and `/steward` refs route the same evidence into the
responsible agent's context and root's overview. A render failure therefore
appears in place and remains forensics; fixing the renderer removes the
current derived problem without deleting history.

Core faults enter through Flow's error channel and the fault committer.
Agent mistakes become flat values and evaluation records. The channel, not a
guessed kind list, determines which escalation policy applies.

## Transaction and program provenance

Every datom already names its transaction. `:seon.db/user`,
`:seon.db/process`, and `:db/txInstant` answer who, through which path, and
when. Joining a program row's datom through that transaction distinguishes
admitted source publication from agent-authored changes. **[TARGET — ruled
2026-08-04]** `:seon.fn/author` records the function author directly for
curation and accountability queries.

Program rows provide the source side of a forensic answer:

- `:seon.fn` retains exact source, contract, call refs, parsed arities/AST,
  and explicit capability-leaf workload;
- `:seon.ns` retains source and effective resolver bindings;
- `:seon.schema` retains canonical forms; and
- `:seon.test` and test observation rows retain recurring proof.

There is no `:seon.def` family: an agent's uncontracted definitions live
only in that turn's SCI fork and are not restored across turns, so there is
nothing to inspect there beyond the turn's own evaluations.

Effective AI settings are recorded on every provider attempt, so a config
change after the call cannot rewrite history. The live config remains
ordinary database facts and can still be inspected at any temporal basis.

## Projection-boundary evidence

Program rows carry queryable `:seon.fn/external-sink` and
`:seon.fn/projection-boundary` leaf facts. `seon.fn/output-path-report`
derives the shortest projected, bypass, and unresolved paths to every sink.
At a particular crossing, the render profile identity and structured elision
values record why the consumer received a bounded face and how omitted data
can be queried — or why continuation is refused. This is the ONE elision
point in the system: everything upstream of it (the storage bound on a
stored value, streaming serialization) is a different mechanism guarding a
different failure, never a second trim of the same bytes.

## Crash forensics

Boot closes every turn with no `closed-at` in one transaction: it stamps
every one of that turn's unsettled evaluations `/interrupted-at` and asserts
the turn's own `/closed-at`. There is no custody comparison to make first —
one JVM per store `flock` and one in-memory turn permit per agent make a
second live holder unrepresentable, so an open turn found at boot belongs to
a dead process by construction. Settled evaluations are unchanged and
unstarted forms remain unstarted.

The forensic answer is deliberately bounded:

- a terminal evaluation proves the form settled;
- an interrupted evaluation says its effect may have happened;
- an evaluation with no terminal fact says it did not produce a recorded
  result; and
- a turn with attempts but no evaluations says the model replied but nothing
  froze — either the reply held no forms or the crash landed before any did.

Nothing in the model claims automatic effect replay or exactly-once remote
execution. Recovery closes the wreckage and the agent adapts from the
evidence in its next context, which names the interrupted evaluation and
process failure without implying that committed transactions were rolled
back or that the interrupted form was replayed.

## Web UI and operator inspection

The web UI exposes the same facts through `/`, `/ns/{namespace}`,
`/ns/{namespace}/debug`, `/agent/{id}`, `/agent/{id}/debug`, and `/data`.
Namespace and agent debug surfaces walk the current database value, retain
refs for `get-in` path navigation, and show AI/HTML projections from the
same render owners. They do not store a display selection or route entity.

The operator separately reports process identities, branches, ports,
readiness, logs, and per-root disk footprint. Before creating a managed
root, store, log, or cluster, it publishes one atomic EDN claim under the
installation control root outside the managed `data/clusters`, `data/store`,
`data/store.lock`, and `data/blob-staging` siblings. That catalog records the
canonical root, store, clusters, durable/ephemeral disposition, creator, and
exact process generations; status derives liveness from `(pid,
start-instant)` without opening Datahike. The claim survives the process and
managed tree it describes. These facts govern process lifecycle, not agent
history. Reproduction uses an isolated cluster fork and the ordinary
message/turn path.

## Source authority

The admitted schemas own durable evidence shapes. Program-graph queries
locate the current functions that produce, settle, and render those facts;
this page does not maintain a parallel source-file roster.

## See also

- [[data-model]] — durable evidence relationships and schema authority.
- [[agent-runtime]] — the transitions that create and settle them.
- [[context]] — byte-identical projection and continuity.
- [[ui]] — the web surfaces that render the same facts for a human.
