---
type: architecture
status: active
tags: [architecture, agent, database]
---

# Observability — inspect any agent and run

> **Target design** (present tense). Implementation state, gaps, order, and
> evidence live only in [[roadmap]].

Agent forensics are queries over messages, runs, context captures, provider
attempts, planned forms, eval receipts, program rows, and error facts. Process
logs remain necessary for startup, readiness, transport, and crashes, but they
are not a second durable agent-history model.

## The evidence spine

One episode leaves a connected set of facts:

```text
agent ← message
  ↑        ↓ run trigger
  └── run ← context capture
        ├── provider attempt(s)
        ├── ordered form(s)
        └── eval receipt(s)
               ↓ optional error/problem refs
```

The durable joins are concrete:

- the agent is `:seon.cluster.agent/id`;
- messages point through `:seon.cluster.message/to`, optional `/from`,
  optional `/about`, and optional `/caused-by`;
- runs point through `:seon.cluster.run/agent`, their recorded `/trigger`, and
  the agent's current `/run`;
- context captures and attempts point to the run through their `/run` refs;
- forms and receipts point to the run and join by `/ordinal`; and
- error facts may point to `/agent` and `/run`.

No turn identity, phase cursor, recovery anchor, route row, or browser-session
entity is required to reconstruct that chain.

## Context capture — what the model saw

Before a remote model call, the loop commits one
`:seon.context.capture/capture` containing:

- `:seon.context.capture/run` — the run;
- `/basis-t` — the basis transaction of the database value every projection
  read;
- `/prompt` — the exact prompt string handed to the model;
- `/contributions` — owned evidence rows ordered by their `/position`; and
- optional `:seon.cluster.run/live-processes` — the nondatabase input when a
  projection actually used it.

Each contribution records its render-block name, position, hash, token estimate,
and optional projection/error evidence. It does not duplicate its text: the
capture's prompt is the byte ground truth. Failed contribution state is the
presence of error attributes; omitted blocks create no contribution row.

The capture identity derives from run identity and basis. Re-deriving the same
prompt at the same basis upserts the same evidence; a later basis produces a
new capture. Writer ordering makes the boundary honest: no capture means no
prompt is claimed, while a capture with no attempt says only that the external
call may not have occurred.

## Provider attempts — what crossed the external boundary

One `:seon.ai/attempt` row records each completed observation of a model call.
It retains:

- run, ordinal, and attempt instant;
- the exact endpoint, model, and canonical effective settings used;
- open provider usage, finish reason, and optional reasoning content or blob;
- HTTP and request/response/output phase observations; and
- error, failover-from, and retry-delay facts when present.

These are observations, not replay authorization. Error-ref presence means the
attempt failed. `failover-from` identifies which attempt supplied the failure
context. Retry disposition, error class, normalized usage, and whether an
attempt was primary or backup derive from those facts; no outcome or role enum
duplicates them.

The attempt row is written after the external call. A process that dies during
the call may leave a context capture and no attempt. That is the honest limit:
the database says the call was not recorded, not that it certainly never
happened. Recovery never retries it.

## Forms and eval receipts — the authentic REPL history

The session displays messages through explicit query forms and their returned
values beside eval receipts. A frozen plan records
each form's exact source, ordinal, and optional reader namespace. Its matching
receipt records the instant, ending namespace, printed output, admitted result,
blob address and full size when large, error, or interruption.

Receipt state is presence:

- no result/error/interruption → running;
- `/result-edn` or `/result-blob` → returned value;
- `/error` → failed evaluation; and
- `/interrupted-at` → time-limit or crash cut.

The receipt and form share `[run ordinal]`; neither stores `ok?`, status,
error-data, turn, or phase. The run's own `/closed-at`, `/process`,
`/plan-digest`, and `/error` facts explain whether work is open, held, planned,
closed, or unable to start.

Printed REPL text is rendered from durable source, output, and result data.
`result-edn` remains the data projection; the text and HTML faces come from the
one print grammar and may re-render without changing the receipt.

## Large values and blobs

Content-addressed blobs use SHA-256 `:seon.blob/digest`. A result above the
configured eligibility floor moves to a blob only when the complete blob-side
shape—bounded window, digest/size envelope, and binary payload—is smaller than
the full inline receipt. Such receipts keep the bounded projection plus
`:seon.cluster.eval/result-blob` and `/result-size`; provider attempts use the
same digest family for large reasoning content; durable session definitions
may use `:seon.code.def/blob` and `/size`.

Blob state never becomes a second lifecycle or replay log. The referencing
row carries the semantic identity, digest, and size. Capped presentation derives
from the size and configured threshold. A missing blob is a loud forensic
failure attached to the referencing fact.

## Error facts

Agent-facing failure is the flat `:seon.error/value`. A failure worth retaining
becomes one `:seon.error/fact` with identity, instant, process identity, kind,
message, content signature, bounded data projection, capped flag, and optional
class/Flow/run/agent/instrumentation evidence.

Kinds are producer-owned namespaced keywords, never a central enum or entity
discriminator. Recurrence is a query over `/signature`. The core fault
committer retains at most one bounded fact per signature and process: its
disposable signature set collapses repeat attempts while a database writer is
unavailable, and a database query remains the authority after a proc rebuild.
Distinct signatures remain distinct; no stored recurrence tally is needed.
The `/agent` and `/run` refs route the same evidence into the responsible
agent's context and root's overview. A render failure therefore appears in
place and remains forensics; fixing the renderer removes the current derived
problem without deleting history.

Core faults enter through Flow's error channel and the fault committer. Agent
mistakes become flat values and eval receipts. The channel, not a guessed kind
list, determines which escalation policy applies.

## Transaction and program provenance

Every datom already names its transaction. `:seon.db/user`,
`:seon.db/process`, and `:db/txInstant` answer who, through which path, and when.
Joining a program row's datom through that transaction distinguishes admitted
source publication from agent-authored changes without storing a duplicate
author field.

Program rows provide the source side of a forensic answer:

- `:seon.fn` retains exact source, contract, call refs, parsed arities/AST, and
  explicit capability-leaf workload;
- `:seon.ns` retains source and effective resolver bindings;
- `:seon.schema` retains canonical forms;
- `:seon.test` and test observation rows retain recurring proof; and
- `:seon.code.def` retains the namespace session image used by cold restore.

Effective AI settings are recorded on every provider attempt, so a config
change after the call cannot rewrite history. The live config remains ordinary
database facts and can still be inspected at any temporal basis.

## Crash forensics

Recovery compares every open run's `:seon.cluster.run/process` with the live
process set. A dead or absent holder causes one transaction to stamp dangling
receipts `/interrupted-at`, close the run, release custody, and retract the
agent pointer. Settled receipts are unchanged and unstarted forms remain
unstarted.

The forensic answer is deliberately bounded:

- a terminal receipt proves the evaluation settled;
- an interrupted receipt says its effect may have happened;
- a frozen form with no terminal receipt says it did not produce a recorded
  result; and
- a context capture without an attempt says the external call may not have
  fired.

Nothing in the model claims automatic effect replay or exactly-once remote
execution. Recovery closes the wreckage and the agent adapts from the evidence.

## Page and operator inspection

The web UI exposes the same facts through `/`, `/ns/{namespace}`,
`/ns/{namespace}/debug`, `/agent/{id}`, `/agent/{id}/debug`, and `/data`.
Namespace and agent debug pages walk the current database value, retain refs for
drill navigation, and show AI/HTML projections from the same render owners.
They do not store a display selection or route entity.

The operator separately reports process identities, branches, ports, readiness,
and logs. Those facts govern process lifecycle, not agent history. Reproduction
uses an isolated cluster fork and the ordinary message/run path.

## Source authority

- The context, AI, run, error, program, and test sections of
  `resources/seon/schema.edn` own the durable evidence shapes.
- `src/seon/context.clj` commits captures from rendered context.
- `src/seon/cluster/loop.clj` opens attempts and receipts, persists blobs and
  session definitions, and settles the fold.
- `src/seon/cluster/run.clj` owns settle-once and recovery transitions.
- `src/seon/render/transcript.clj` owns the message/receipt queries and REPL
  interleave.
- `src/seon/render/{agent,root,ns,web}.clj` owns current agent, root, namespace,
  debug, and data-page inspection.

## See also

- [[data-model]] — admitted evidence attributes.
- [[agent-runtime]] — the transitions that create and settle them.
- [[context]] — current context derivation.
- [[ui]] — the pages that render the same facts for a human.
