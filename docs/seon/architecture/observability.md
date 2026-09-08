---
type: architecture
status: active
tags: [architecture, agent, database]
---

# Observability — inspect what an agent evaluated and saw

> Target contract: [agent record and turn loop PRD](../../prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
> §13–§15. Implementation evidence and ordering live in the program roadmap.

Agent forensics query turns, evaluations, provider attempts, messages,
program rows, and error facts. Logs explain process startup, transport,
and crashes; they do not replace the durable history.

## What the agent saw

An evaluation records its exact form, namespace, comment, ordinal, shown
text, out, error, and read evidence as applicable. Shown text is the
value renderer's output at evaluation time, including profile elisions
and requery forms. It is an observation, not a serialization of the result.

The walk renders evaluation entities in chronological turn order and
ordinal through the schema's `seon.repl/render-ai` and
`seon.repl/render-html` pair. `seon.repl/text` owns the REPL grammar.
Every turn is shown by default; no separate history formatter or clipping
pass changes saved bytes.

To reconstruct a sent prompt, render the evaluations that preceded that
agent turn from their stored shown text. A later profile change, mutated
atom, program adoption, or JVM restart cannot rewrite that prefix.
An attempt's prompt digest witnesses sent bytes. A separate prompt
capture, print-node store, or result blob is unnecessary.

The turn's identity datom supplies its basis `:t`. This remains useful
for temporal joins, but re-executing reads at that basis is not the
authority for what was shown: the saved text is.

## Inspect an evaluation

`(my.turn/evals)` returns the agent's evaluation maps, filterable by turn
or form. `(my.turn/eval id)` returns one in full, including read evidence.
`dir` presents the API as program data.

An evaluation id derives from branch id, turn id, and ordinal through
`seon.id/evaluation`. Its `result/e<id>` handle refers directly to the
actual object in that agent's live SCI context. A handle is not an EDN
decoder or a durable object locator.

While the object exists, HTML may inspect it without presentation
clipping. After restart the inspection map says the object is gone and
shown text remains. A previously saved requery form may then have nothing
to reach. Do not report text recovery as object recovery.

## Why a system turn appeared

A system turn is an ordinary turn with a reply and no provider attempt.
Opening context and refreshed observations use this shape. For every
distinct read form, the latest evaluation's evidence and `:t` explain
the since-query diff that selected it for a new system turn.

Generated and agent-written reads participate equally. Writes and effects
never refresh. Changed dependencies append an observation even though the
old observation remains in history. Empty reads, disappeared facts, and
unavailable evidence need explicit interpretation rather than a false
“nothing changed” conclusion.

A system turn holding wakes' results answers those wakes under the `:t`
rule. A reply alone, or an unrelated source submission, is not proof that
a particular wake was observed.

## Provider attempts and uncertainty

A turn owns its provider attempts. Each attempt records the provider's
observed result, effective settings, usage, and error evidence as declared
by the AI schema. A system turn has no provider attempt and costs no model
call. The attempt facts are observations, never replay authorization.

The reply, attempts, and forms are stored before evaluation. A process
that dies during an external call may leave no completed attempt fact.
That absence says the call was not recorded, not that it never happened.
Likewise, absence of an evaluation outcome cannot prove that a side
effect did not happen.

Boot closes open turns and marks unfinished evaluations interrupted.
Stored outcomes remain unchanged. No interrupted turn, write, or effect
re-executes during recovery. The next agent turn adapts to that evidence.

## Private state and program provenance

Each agent's persistent SCI context retains its private defs, atoms,
and actual result objects across turns. Those objects are lost on JVM
restart. Functions, schemas, and tests accepted as program rows rebuild
the base; accepted base diffs reach each live agent context.

Namespace stewardship describes responsibility, not execution permission
or agent identity. Program facts and transaction provenance answer which
definition changed and who changed it. Read evidence names the facts
a computation observed; it is not a duplicate result payload.

Core faults arrive through Flow's error channel and are committed with
provenance. Agent mistakes are flat evaluation values. A diagnostic names
the failed subject and unavailable observation; silence is never health.

## Inspection does not rewrite history

The debug invocation cache is disposable preview state.
`?prompt=true` previews the stored history plus the would-be system turn
without writing or calling a provider. Cache loss changes preview cost,
not the old prompt bytes.

Compaction explicitly retracts evaluations. The next system turn
regenerates the opening from current record data. This is the one prefix
reset; there is no manual curation or adoption of revised historical forms.
Forensics cannot promise to retrieve evaluations that compaction removed.

The web UI shows the same facts through the canonical route table.
The operator reports process identities, ports, readiness, and footprint.
A reachable HTTP endpoint proves reachability, not that the new context
algorithm or browser repaint has been exercised.

See [agent runtime](agent-runtime.md) for transition diagrams,
[data model](data-model.md) for relationships,
[context](context.md) for prompt continuity, and [UI](ui.md) for delivery.
