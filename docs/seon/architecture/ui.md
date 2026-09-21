---
type: architecture
status: active — first pass 2026-09-21 (Fable), for astra review before the clean write
tags: [architecture, web, ui, datastar]
---

# UI — namespace pages, blocks, whole-view delivery

The web UI runs in the cluster JVM and renders the same entity facts the
agent reads: AI and HTML are two projections of one render pair per entity
schema. A browser page is derived from data; the agent's shown text is a
durable fact. The browser owns no database logic and no durable UI state.
Marking as in [README.md](README.md).

## 1. Namespace pages and blocks

Routes are `/`, `/ns/{namespace}`, `/agent/{id}`, their debug routes and
`/data`; the route table is code data compiled by Reitit, never a second
catalog. A namespace page is route → namespace → responsible agents → the
walk rendered as blocks: one identified block per entity, whole concern each
(scalars together; components and declared derived queries own theirs). The
identified block is the morph target, so Datastar preserves unaffected DOM
and browser input. A layout receives blocks as data and controls placement
and CSS, never membership or renderer selection. The data browser navigates
with explicit `get-in` paths and offsets; a query-work window names its
boundary and continuation.

**Data flow.** Per request: one walk over the page's entities, one render
per block through its pair, Hiccup out. Proportional to the page's entities.

**Current / Target.** Current: `src/seon/render/route.clj:5` `routes`, `:37`
the Reitit router; `src/seon/render/block.clj:61` `surface-id` (the DOM id,
injective by docstring); `src/seon/render/ns.clj:895` `render-ai`, `:928`
`render-html`. Several agents may share a namespace; the responsibility ref
is single-valued under a retired spelling (`resources/seon/schemas/seon.ns.edn:28`).
Target: `:seon.ns/agents`, many-to-many (ruling D1); "everything about
namespace N" is ONE pull that returns tests, errors, lint, tasks and the
responsible agents, and an agent's opening IS this view (goals note §2d row
10); lanes B2, B3.

**Reference code.** datastar-clojure
`libraries/sdk/src/main/starfederation/datastar/clojure/api/elements.clj:112`
`->patch-elements-seq` (the one SDK call the page uses).

## 2. Whole-view delivery, hyperlith-style

Each browser tab taps the cluster's refresh mult through a
`(dropping-buffer 1)` channel; on each signal the tab's thread renders the
WHOLE view, streams it as one `datastar-patch-elements` event through a
brotli writer, and the browser's idiomorph does the diff. No revisioning, no
delta, no keyframe, no per-tab registry, no drain queue: loss is the dropping
buffer's, and a late tab simply renders on connect. Streamed provider replies
ride the same path as complete prefixes; only the completed reply becomes a
turn fact. No agent code receives an SSE connection; authored render
functions return values and the HTTP owner performs transport.

**Data flow.** Per change: one signal on the mult; per tab: at most one
pending render (the buffer), one whole-view render, one compressed write.
Proportional to tabs × view size, never to history.

**Current / Target.** Current: `src/seon/render/web.clj:1849` `join-package`,
`:1877` `next-package`, `:1905` `package-patches` — revisioned packages with
a delta and a keyframe, a per-tab drain feed (`:2689-2891`), and an
invocation cache at `src/seon/render.clj:701-889` whose question ("did the
program change?") a commit id answers; `next-package` and `package-patches`
have no test (data pack B2 §6). Target: the hyperlith shape above; lane B2.

**Reference code.** hyperlith `src/hyperlith/impl/datastar.clj:122-189`
`render-handler` (`:143-145` the dropping-buffer tap with its reason in the
comment: the mult distributes synchronously, so a slow handler must not
block; `:148` `hk/as-channel`; `:179-182` `:on-close` closes the tap);
http-kit (our fork) `src/org/httpkit/server.clj:321` `write-state` (atomic
pending-byte state for bounded SSE writes); datastar-clojure `elements.clj:112`.

## 3. HTML never clips

HTML renders the live result object without presentation clipping while it
exists, and the saved shown text after a restart, saying plainly that the
object is gone. The only elisions an HTML page shows are query-work bounds
reported as elision values (a walk's distance or connection limit, a pull
over 1,000 members), each naming the bound and a continuation.

**Current / Target.** Current: `src/seon/render/ns.clj:793` `html-within-budget?`,
`:797` `budgeted-html`, a three-tier ladder at `:808-818` under `:416`
`token-budget` — the namespace page clips HTML, violating AGENTS.md §2.4.
Target: the ladder is deleted; lane B2.

## 4. The debug page shows the algorithm

`/ns/{ns}/debug` for an agent is where the turn loop is visible in every
state without a model call: a state line (evaluations held, last turn `:t`,
fresh or continuing); context now — every evaluation through `seon.repl/text`
with a per-evaluation as-of check (the stored text rendered again at its own
`:t` equals the stored text, or the renderer stopped being a function of the
data); the would-be system turn computed now and writing nothing (none /
unchanged / changed per read form, with the bytes that would run); the prompt
bytes with their digest, always present, collapsed; three controls — run
system turn, virtual turn (one turn through the ordinary loop with a fixture
reply), compact — each a same-origin POST after which the page repaints
through the feed. Provider cards separate what we sent, the raw reply and the
saved evaluations; rebuilt token estimates and provider-billed tokens are
shown separately.

**Current / Target.** Current: `src/seon/render/web.clj:3217`, `:3242-3245`
the `?prompt=true` flag; `src/seon/turn.clj:2103` `system-turn` with
`:write? false` for the preview, `:2330` `virtual-turn!`, `:2323` `compact!`;
the debug value and experiment views at `web.clj:583-1357` (ten functions).
Target: the flag retires — context-now is always primary and the prompt
comparison is always present (goals note §4); the page's UI-state prose of
the previous version of this file is not architecture and is not carried.

## 5. Messages

The message form commits an ordinary `seon.message` addressed to the agent;
the listened `:seon.message/to` datom wakes its graph; the feed shows the
resulting facts. HTTP submission is never an evaluation result channel.
`my.message` is the thin agent-facing protocol over the same facts.

**Current / Target.** Current: `src/seon/cluster/message.clj` (owner),
`src/my/message.clj:31` `send`; `resources/seon/schemas/seon.message.edn`.
Target: a conversation is derived from message facts — done is "no outside
wake newer than my reply" — and a reply task uses the same context mechanism
as any task (ruling D1, F4).

## 6. The canvas — target

A generalized agent-authored canvas (forms, buttons, inputs) remains a
separate target contract: its schemas and action boundary are declared
before any callback route or API exists. No declared attribute exists at HEAD.
Consumer products and their domain routes belong downstream, never in
`src/`.
