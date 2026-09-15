---
type: architecture
status: active
tags: [architecture, web, agent]
---

# UI — entity blocks and two render projections

> Target contract: [agent record and turn loop PRD](../../prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
> §13–§15. Current implementation state belongs in the program roadmap.

The web UI runs in the cluster JVM and renders the same entity facts the
agent reads. AI and HTML are two projections, each structured for its
reader. A browser page is derived from data; an evaluation's shown text is
a durable fact about what the agent saw. These have different lifetimes.

## One block per concern

One entity schema declares one pair, `:seon.render/ai` and
`:seon.render/html`. Scalar attributes render together in the entity's
own block. Components own their blocks; derived concerns are queried by
functions declared once on the agent schema. A scalar does not acquire its
own render pair merely because it is separately queryable.

The record's opening order is identity, plan, unanswered wakes, routed
faults, and history last. History is chronological with every turn shown
by default. No render pair means the default attribute-map printer; an
empty concern may be absent, but a failed render produces a diagnostic.

A render function chooses its source from the data. An empty plan emits
a short absence comment and useful `dir`/`doc` forms; a populated plan
emits its current, ready, and blocked queries. `dir` and `doc` return
program data, not a parallel body of teaching prose.

AI source executes through the ordinary turn evaluation path and produces
stored evaluations. HTML returns Hiccup. There is no third form projection.
Every value inside a block prints through the one value renderer.

## History is ordinary entity rendering

The walk renders the ordered evaluation entities through their declared
`seon.repl/render-ai` and `seon.repl/render-html` pair.
`seon.repl/text` is the single REPL grammar. No history-specific assembly
of entry kinds or independently formatted result strings participates.

At evaluation time, the value renderer applies the profile once and the
evaluation stores its shown text, including elisions and requery forms.
The history uses those saved bytes; it never reruns a form to display it
or applies another history-wide clip. All turns remain visible until
explicit compaction.

HTML can render the actual live result object without presentation
clipping. After a JVM restart it renders the saved shown text and makes
the loss of the live object explicit. It never implies that saved text
is a restored atom, channel, function, or lazy sequence. Query-work bounds
and evaluation deadlines remain separate from presentation.

## Context mutation and previews

The turn owner appends a system turn before an agent turn when read
evidence changed. The since-query algorithm covers every distinct read
form, generated or agent-written; it never repeats writes or effects.
Passive page rendering does not append history.

The agent debug page defaults to a chronological turn ledger. Each provider
card separates WE SENT (generated context), AGENT REPLIED (the exact raw
reply), and RESULTS (the saved evaluations). System cards contain only WE
GENERATED. Section labels and colours identify authorship without changing
bytes. The selected turn and last three cards are open; other card bodies
load on demand. A card's Full context as sent disclosure opens the faithful
REPL transcript below that card. Its context comes from the provider acquisition fold at the turn's
opening database. `render/acquire-context!` owns this temporal selection even
when passed the current database; completed generated system turns use their
close transaction. Rebuilt estimates and provider-billed tokens are shown
separately, since the estimator does not prove byte identity.
`seon.repl/render-emission-html` colourises the exact
`seon.repl/text` bytes; prompt, comment, form and response stay in their
original order. Turn boundaries and origin gutters sit outside those bytes.
Repeated system reads fold in place, with every exact entry available at
its original position. The `?prompt=true` toggle shows the complete acquired
prompt as one unchanged block. Selected-turn content loads on demand;
the initial response never acquires all historical prompts. The Record
section loads the existing agent record blocks on demand below the session.
A compact header shows the agent, namespace, objective, local state time,
and cluster. System/virtual/compact actions and the raw toggle share its
toolbar; the message form is collapsed until requested.
The same header appears on the ordinary namespace page, with agent/debug
navigation. Debug history uses normal document scrolling, with selected-turn
facts and navigation in the sticky header. Re-read disclosures reveal entries
in place, and the selected card scrolls into view on load.
The ordinary page is one full-width column ordered by declared concern:
plan, runtime, inbox, notes, settings, identity, faults, namespace bindings.
The walk still owns membership and stable block identities; presentation
order no longer promotes the most recently changed empty block. Blocks have
no inner scroll boxes. Runtime's existing turn table is an expandable
disclosure, with a fixed-layout wrapping table when opened.
Compaction wipes the agent's evaluations;
the next system turn regenerates the opening. There is no manual
Add/remove/revision/proof/adoption path for editing context.

The message form commits an ordinary message addressed to the agent.
The corresponding listened datom wakes its graph; the ordinary render
feed shows the resulting facts. HTTP submission is not an evaluation
result channel.

## Namespace pages and navigation

The route table belongs to `seon.render.route/routes`, compiled by
Reitit. It is code data, not a second database catalog. Namespace and
agent routes resolve their identities through facts. Several agents may
share a namespace; namespace assignment is not unique and stewardship
belongs to the namespace.

Root, namespace, agent aliases, debug inspection, and the data browser use
the same render owners. Inspect the route table for exact live paths
rather than copying a route inventory into this document. Browser-local
selection, scroll, disclosure, and inputs remain browser state.

The data browser uses explicit `get-in` paths and offsets. A query-work
window names its boundary and continuation; it does not silently turn an
unavailable observation into an empty value. A link identifies the
position being inspected.

Generalized agent-authored canvas and control constructors remain a
separate target contract. Their schemas and action boundary must be
declared before a new callback route or API is introduced. Consumer
products and their domain-specific routes belong downstream.

## Stable blocks and delivery

The identified block is the morph target. Stable DOM ids let Datastar
preserve unaffected content and browser input. A layout receives blocks
and relationships as data; it controls placement and CSS, not membership
or renderer selection.

The cluster render proc owns revisioned packages, each carrying a delta
and a complete keyframe. It serializes once and publishes through a mult.
Each tab taps with a sliding-one buffer: contiguous revisions use the
delta, while a gap uses the complete keyframe. An unchanged HTML block
does not need a morph.

A late tab needs an initial keyframe because a mult does not replay.
Latest packages, render caches, and browser connections are disposable
process state. Their loss changes render work, never the stored prompt
bytes. A tab's connection-owned writer waits for socket drain or close;
slow readers cannot accumulate a queue of stale packages.

Streamed provider replies use the same delivery path. Each partial is a
complete prefix, offered without blocking the provider reducer onto a
sliding-one channel. Only the completed reply and its durable consequences
are written as turn facts. Reconnect does not replay discarded partials.

No agent code receives an SSE connection. Authored render functions run
through the bounded SCI invocation owner and return values; the HTTP
owner performs transport. A render failure remains an in-place
diagnostic and a durable core-fault observation when appropriate, while
siblings continue.

See [context](context.md) for additive prompt semantics,
[agent runtime](agent-runtime.md) for system turns,
[data model](data-model.md) for relationships, and
[observability](observability.md) for evidence limits.
