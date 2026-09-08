---
type: architecture
status: active
tags: [architecture, agent]
---

# Context — stored evaluations, additive history

> Target contract: [agent record and turn loop PRD](../../prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
> §13–§15. Implementation status and ordering live in the program roadmap.

The prompt is the agent's stored evaluations rendered in order. System and
agent turns contribute to one sequence. System turn 0 stores the executed
opening; later system turns append observations of changed facts before
the next agent turn. Earlier prompt bytes remain unchanged until explicit
compaction.

The [agent runtime](agent-runtime.md) owns the algorithm and its additive
context and since-diff diagrams. This document owns what the agent sees.

## Blocks follow the record

The agent record has scalars and components. Derived concerns are queries.
One entity schema declares one AI/HTML render pair; scalar attributes
render together inside the entity's own block. Components render their
own concerns. The agent schema declares derived query functions once,
rather than storing their results or maintaining a manual membership list.

Opening order is the record's own block, plan, unanswered wakes, routed
faults, then history last. History is chronological, oldest first, with
all turns shown by default. A concern with nothing to say is absent; a
failed observation is an explicit diagnostic, not an empty concern.
No render pair means the ordinary attribute-map printer.

A render function is a function of its data and chooses useful forms.
For an empty plan it emits the absence comment and executes
`(dir my.plan)` and `(doc my.plan/add!)`. For a populated plan it emits
`(my.plan/current)`, `(my.plan/ready)`, and `(my.plan/blocked)`.
There is no separate teaching-prose mechanism.

`dir` returns data from public program rows: symbol, arglists, first
docstring line, and input/output contract. `doc` returns the full
docstring and contract. A `my.*` read returns small maps with the
items' own namespaced keys; a write returns the entity it changed.

## Refresh every read

Before each agent turn, inspect the latest evaluation of every distinct
read form in the history. Generated and agent-written reads participate
equally. A read form reads facts and neither transacts nor requests an
effect. Its read evidence and evaluation `:t` determine whether named
dependencies changed in the since-query diff.

Changed reads append fresh evaluations in an ordinary system turn.
Unchanged reads retain their existing observations. No changes means no
system turn. Writes and effects never rerun. Evidence must account for
empty results and removed facts; an unavailable observation is unknown,
not freshness.

The change algorithm belongs to the turn owner. Passive browser rendering
does not append evaluations, and a browser cache eviction does not cause
a write or turn. The same evidence machinery can avoid redundant render
work without becoming another source of historical truth.

## What remains stable

At evaluation time the one value renderer applies the render profile and
produces shown text, including elisions and requery forms. That text is
stored on the evaluation with source, out, error, and read evidence.
Projection later uses the stored text rather than printing the live value
again. A profile or program change therefore cannot rewrite an earlier
prompt prefix.

The history is the walk rendering evaluation entities through their
schema-declared `seon.repl/render-ai` and `seon.repl/render-html` pair.
`seon.repl/text` owns the REPL grammar. No hand-assembled history entries,
entry kinds, result formatter, or second clipping pass sits beside it.

Prompt bytes are derived from evaluation facts; a separate prompt capture
or contribution family is unnecessary. An attempt's prompt digest can
witness the bytes actually sent. Reconstructing those bytes after a restart
does not require re-executing forms or recreating result objects.

## What lives in the JVM

Every agent retains one live SCI context across turns. The base contributes
accepted program diffs to that context, while its private defs, atoms, and
result objects remain isolated. Result handles bind actual objects by
stable evaluation id, derived through `seon.id`. Installed functions,
schemas, and tests become program rows; plain defs do not.

A JVM restart loses private state and result objects. History retains
what was shown, including any saved requery form whose object is now gone.
Inspection states that limit plainly. Committed data is not rolled back,
and interrupted forms are not replayed.

## Compaction and inspection

Compaction wipes the agent's evaluations. The next system turn generates
the opening from the current record using the same algorithm. It does not
selectively edit or adopt a revised history.

`(my.turn/evals)` inspects evaluations by turn or form;
`(my.turn/eval id)` includes read evidence. The debug invocation cache is
process-local. `?prompt=true` renders stored evaluations plus the would-be
system turn without writing or calling a provider.

The HTML projection may inspect the live object while it exists and uses
shown text after restart. Human and agent surfaces share facts and render
owners, while their presentation serves their respective readers.

See [UI](ui.md) for block delivery and [observability](observability.md)
for the limits of historical evidence.
