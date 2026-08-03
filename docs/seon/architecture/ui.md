---
type: architecture
status: active
tags: [architecture, web, agent]
---

# UI — pages, blocks, renders, and routes

> **Target design** (present tense). Implementation state, gaps, order, and
> evidence live only in [[roadmap]].

The human's UI and the agent's prompt are the same data, dual-rendered. Every
settled page state is a derived projection of the database; transient streamed
prefixes are process-local render-unit inputs, and nothing rendered is stored. The
context unit is the **block**; the engine is `seon.render`; the HTTP router is
**reitit**; the live channel is a Datastar **morph** stream backed by one
normalized reactive registration and database-scoped selective interest per
demanded computation. Loopback streams are uncompressed by
default; remote compression is explicit configuration. Every layer is a
symbol or a datom, so a third party overrides any of it — blocks, canvas,
layout, the root agent’s view, routes, CSS, client — reusing the same
primitives, with zero `src/seon` edits.

The web UI runs inside the **cluster JVM** beside the writer and the agent
graphs. It
serves HTTP through http-kit, frames SSE through the Datastar Clojure SDK, and
reads the cluster's current immutable database values directly. Agent-authored
renderers run only through the one guarded SCI invocation boundary: the retained
`ctx`/`fork`, one `:interrupt-fn`, the render invocation-class `time-limit`,
value admission, and bounded output. No direct Var invocation or
`requiring-resolve` path bypasses that door. Supervision, bounded evals, and
cheap flow-graph rebuilds protect the process; the UI does not rely on a
process wall.

There are exactly two typed render outputs. The agent prompt asks for
`:seon.render/ai`; the browser asks for `:seon.render/html`. Logs and other
sinks call ordinary sink-specific functions rather than extending a generic
render kind. The boundary selects one of the two outputs; the data does not
carry a stored request kind.

## Human input becomes a message

The only current interactive mutation is the agent message form. Its POST route
validates same-origin input, target identity, and bounded content, then commits
one `:seon.cluster.message` row. The agent's own graph wakes from the `/to`
datom and derives its run from database truth. The HTTP response is not an
execution-result channel; the page feed renders the later message, run, and
eval facts.

## The projection contract — two typed outputs

Rendering is not a UI mechanism that other things borrow; it is one contract
the UI is the largest consumer of. A **unit** is schema'd data discovered by
the recursive entity walk. The boundary requests an output kind, and the
renderer resolves one projection through the program graph. First-party Vars
remain live under re-evaluation. A nonidentical base-Var redefinition is
accepted and emits the ordinary Clojure-style warning. Agent-authored corpus
functions are installed SCI values invoked through the guarded door, never
host-resolved Vars.

Explicit declarations ride on the value being projected; otherwise the
selector queries the program graph and schema metadata. Logs use the error or
problem domain's ordinary log function. A recursion-fence failure, overflow
callback, development panic, or startup/export invariant may still write a
brief direct stderr diagnostic because it reports the projection or durability
machinery itself.

Failures are flat `:seon.error` values, never throws. No declaration is
required: a value with no specialist reaches the structural floor. An
ambiguous contract fit or a selected producer failure is loud and does not
fall through to another producer. The browser receives only an unavailable
state; an explicitly assigned namespace owner receives the durable diagnostic
message.

### One resolution chain and one floor

Every discovered value resolves through one chain, most specific first:

1. explicit `:seon.render/ai` or `:seon.render/html` keys on the value;
2. the unique contract-fitting public function in the data's explicitly
   owning namespace, when a data or traversal ref names one;
3. the schema-attached default renderer; and
4. the structural floor.

Function rows carry input and output schemas, so namespace candidate selection
is one ordinary corpus query through the query cache. There is no producer rule
registry, slot redirect, or second floor. A broken renderer yields a flat error
unit and never promotes a different mechanism silently.

## The block and its two renders

A **block** is the informal name for one renderer function call's identified
output in either projection, never a database entity or stored data type. Its
identity derives from the renderer function and explicit arguments; the HTML
projection derives one escaped stable DOM element id from that identity.

- **ai render** (`:seon.render/ai`) → prompt bytes.
- **html render** (`:seon.render/html`) → a surface whose output is hiccup.

The same call may provide either or both projections. Explicit render keys on a
value override the corpus/schema chain, but their absence never makes the
system partial: the structural floor can render any value in both projections.
AI and HTML both include every discovered unit. HTML's floor is the same
prepared admitted value as AI's floor, decorated by the value printer rather
than hidden behind a second visibility mechanism.

**The morph target is the block.** Each block is patched independently at its
stable element id, so a transcript append repaints the transcript and the
header is not recomputed. The render proc retains each fragment's dependency
evidence and serialized bytes, so unchanged units reuse their bytes before
fan-out.
Whole-page morphing is not the live-update fallback: initial paint builds the
page once, then every change targets the smallest stable block whose bytes
changed. Serialization, authored evaluation, and slow-reader pressure
therefore scale with changed content rather than page size.

## Membership is the walk — one derivation, no stored set

**Blocks are derived, not installed** (owner ruling 2026-07-31; see
`docs/prds/sci-execution-runtime/plan/README.md`). The render walks the agent
entity and the values reachable from it at one database value, resolves a
renderer per value, and emits one block per rendered value. Membership and
order both fall out of the render calls: order is the basis at which each
call's bytes last changed ([[context]]), and there is no static scaffold path.
The cluster entity owns the authoritative refs to shared instruction rows;
agent entities carry only genuine additions. Both are ordinary schema'd facts
rendered by ordinary, overridable renderers, and instruction text mutates in
place on its stable identity.

**Resolution is one chain, most specific first:**

1. explicit `:seon.render/ai` / `:seon.render/html` keys ON THE VALUE;
2. the unique contract-fitting public function in the data's explicitly
   owning namespace;
3. the schema-attached default renderer;
4. the structural floor.

There is one chain and one floor. A layout never redirects resolution, and no
stored membership, band, or priority attribute exists.

Global-vs-per-agent is decided by the data's connections, never by the block.
The current agent family is scoped by `:seon.cluster.agent/id`; messages, runs,
receipts, and errors point to their owning or receiving agents through refs.
The render function scopes by the facts it queries. See [[data-model]].

## The render engine

One engine, `seon.render`, renders every page and the prompt. Every render call
requires a complete immutable database value under `:seon.db/db`; absence is a
flat `:core-bug`, never permission to consult an ambient latest value. The
engine is a single guarded walker over the agent's blocks in two views (`:ai` →
String, `:html` → hiccup). Renders are projections, never persisted. A failing
render yields a flat error value (see [[data-model]] §6) for that render only;
siblings never crash.

**prompt == page by construction.** Both derive from the same blocks at the
model call's complete ordinary database value. The run loop acquires and formats
the AI renders in last-bytes-changed order ([[context]]); the web UI applies
the same order, including branch tie-clustering, to visible HTML renders.
"What the agent saw for this model call" is the committed context capture: its
prompt bytes plus the basis transaction and ordered contribution evidence.

**The structural floor is one merged data browser.** It combines the bounded
value skeleton with `/data`'s drill navigation on the admission codec's one
walk discipline. It can render any value without throwing, preserves
identities/indices and explicit elision markers, and pays only for opened data.
Specialized message, source, error, and hiccup projections sit above it through
the one resolution chain; they do not create another walker or floor.

**The transcript has one bounded projection.** Eval receipts order by their
durable instants, while messages appear as explicit query forms followed by
their actual returned values. AI renders strict form-then-value REPL text and
HTML renders stable twin entries. Provider reasoning
observations join the HTML projection only after the shared token-budget
decisions. The budget preserves the bootstrap prefix, keeps a fixed recent tail
at full detail when it fits, summarizes older entries when possible, and keeps
the elision count in an ordinary returned value. The database facts and blobs remain
available through debug and `/data`; projection never rewrites them.

**The database browser pays for opened data.** `/data` exposes the same
structural floor directly. Bounded expansion uses a cursor that says where to
resume instead of silently eliding.

Navigation is a `get-in` path, and the cursor — path plus offset — lives in the
URL. So a drilled position is a link somebody can send, reconnecting to one
costs one derivation, and nothing stores or retracts where a reader is looking.
Breadcrumbs are the path's own prefixes: the path IS the trail, so there is no
history to keep.

The window reports its honest total, because a reader must know a window is a
window, and offers a resume offset exactly when there is somewhere to resume, so
"is there a next page?" is key presence rather than arithmetic repeated at every
call site. Ordering is derived and stable — an unstable order shows a row twice
and never shows another.

The cost of displaying a page does not depend on the size of what it links to.
A child is summarised by what it IS, never by walking it. Selecting an attribute
opens a bounded AEVT window through the same discipline; the URL carries the
last visible cursor, and the server reads only enough to render the page and
prove whether a next one exists. It never scans every entity or transaction to
manufacture counts. Domain attributes lead by default; framework attributes
remain reachable explicitly.

A drilled page carries no feed: repainting a page under a reader would move the
ground they are standing on, so reload is the refresh and the URL is the state.

**Large context twins are summaries first.** Plan roots render as compact
title/progress disclosures; only the focused root starts open, and its tree has
a bounded internal scroll region. Long titles and goals line-clamp, every
technical surface wraps or horizontally scrolls, and the canvas has a bounded
default height. Scale is handled by disclosure and windowing, never smaller
unbounded text.

**Capability + cache.** The cluster JVM acquires authored program and ordinary
inputs at one immutable database value and invokes authored code through the
one SCI door with the `:interrupt-fn`, invocation-class `time-limit`, and value
admission. A function may raise the class default only through `defn` metadata
lifted into its program row at index time; render owns no private timer dial.
Renderers may request genuine effects only through
`seon.effect/request!`; the normal render path remains pure and returns
ordinary data. The byte-stable prefix of longest-unchanged blocks is
preserved for provider prefix-caching.

**Byte identity is a design property.** The complete database value, verified
configuration and file fingerprints, render inputs, and program artifact
determine the cacheable bytes. Host timezone, process clocks, environment
reads, ambient database state, and process-local result liveness cannot alter
them. Process-local inputs are explicit renderer arguments and therefore part
of the per-call identity; no free tail renders outside the block system.

**Context assembly is its own domain.** How the prompt derives a tree and orders
units by last-bytes-changed (no bands, no priority attributes) and the
namespace-as-location model live in [[context]] — this doc owns the shared
block/render machinery and the human-facing projection. The structural HTML
floor remains visible for every block that has no specialist.

### Render coverage converges on blocks

A domain value becomes broadly observable by accreting the two projections on
the same unit: AI for agent context and HTML for a surface. Operational sinks
call ordinary domain functions. Consumers never rebuild or reclassify the
value.

Prompt assembly follows the same convergence. Context sections are AI renders
of blocks, not strings assembled beside the renderer. As domains gain AI and
HTML projections, prompt and page become different bounded projections of the
same units rather than parallel presentation systems.

## Streamed replies — the one high-churn path

A streamed reply is the only genuinely high-churn thing the UI shows, and it
rides a channel, never the writer. Partials are COALESCED COMPLETE VALUES
offered onto one `(sliding-buffer 1)` conn from the model call's provider fold
into the render proc's in-port. Provider attempts, frozen forms, eval receipts,
run errors, and resulting messages are durable facts; streamed partials never
touch a database attribute.

Each clause is load-bearing. Complete values rather than deltas, because a
consumer that missed a delta is permanently wrong while one that missed a
snapshot is briefly behind. A channel rather than the writer, because a
token-by-token record of a reply that already exists in full is pure churn
through the durable store. Latest-wins, so there is never a queue of stale
prefixes between the model and the eye, and the settled fact simply supersedes
the last snapshot.

**The producer side is isolated because it runs on the provider's socket
thread.** The fold offers the newest complete prefix onto the sliding-1 conn
and returns; the put never parks. Presentation may lag and may DROP
intermediate snapshots — both are correct — and it may never slow the model
call. The governing invariant is the provider reference's own: partial display
cannot affect transport, parsing, usage, or evaluation. A streamed call and a
one-shot call return the same completion value, so nothing downstream can tell
which transport ran.

The live token count derives from the SAME fold that produces the text, never a
second mechanism counting the same thing twice: the provider's own usage once it
has sent one, and the chunk count until then, which is an approximation and is
named as one.

**Both are ordinary blocks.** The token counter and the streaming reply declare
`:seon.render/html` and return hiccup like any other surface, and each is its
own morph target. The highest-churn thing in the system needs no render
machinery of its own — which is the reason those two were chosen as the
exercises that prove the design.

## The tree and layouts

A **layout** is an ordinary first-party HTML renderer over an already resolved
render tree. It receives blocks and branch relationships as data and owns only
placement and CSS. It never names a block to trigger renderer resolution,
redirects one render to another, or changes membership. The retired slot
redirect has no dormant fixpoint or missing-block installation behavior.

**Expansion is bounded** by node and depth budgets shared with the structural
floor. The per-walk rendered set detects an already emitted unit and produces a
back-reference, while branch budgets bound wide fan-out. Elision is explicit
and drillable; a cycle, failed child render, or exhausted budget produces an
in-place error/elision unit so one branch never blanks the page.

**Pages are bounded folds over the entity tree.** A page begins at the route's
agent entity, walks refs, resolves each schema'd value once through the one
chain, and hands the resulting tree to its layout. The `/data` browser is the
floor exposed directly: paged `get-in` navigation into any nested value uses
the same bounded expansion and cursor discipline.

## Pages — root, namespace, agent, debug, and data

Every page is a projection over one database value and the same block/walk
machinery:

- `/` renders root's namespace page and the cluster overview.
- `/ns/{namespace}` resolves the namespace, its owner agent through
  `:seon.cluster.agent/namespace`, and the namespace walk.
- `/ns/{namespace}/debug` shows that same walk with exact AI context and the
  complete walked value side by side.
- `/agent/{id}` resolves one `:seon.cluster.agent/id` and renders its agent page.
- `/agent/{id}/debug` exposes the corresponding debug walk.
- `/data` renders the structural database floor for inspection.

Namespace pages are generic: adding one is adding the route-table line, not a
new page-specific renderer. Agent pages query received and sent messages, runs,
eval receipts, and routed errors using the refs named in [[data-model]]. Root is
the ordinary agent whose identity is `"root"`; it has a specialized layout but
no second route, parent, role, or render model.

Page-local selection, scroll, disclosure, and input state remain in the browser.
No web-session, canvas pin, focus, route, or selected-agent entity is stored.
The debug view preserves entity identities and refs for drill navigation but
does not transact display choices.

## The shell and message action

The shared shell links root and data inspection, includes the page content,
opens the page's SSE feed, and adds the agent message form only when the page
has an agent identity. `POST /agent/{id}/message` is the one browser mutation
route. It applies same-origin middleware, validates the target agent and bounded
content, and transacts one ordinary `:seon.cluster.message` row. No generic
callback route or function descriptor crosses the browser boundary.

## Routing is one code table

`seon.render.route/routes` is the one route authority, compiled by reitit at
namespace load. The current entries are:

| Route | Method | Handler role |
|---|---|---|
| `/` | GET | root namespace page |
| `/ns/{namespace}` | GET | namespace page |
| `/ns/{namespace}/debug` | GET | namespace debug page |
| `/agent/{id}` | GET | agent page |
| `/agent/{id}/debug` | GET | agent debug page |
| `/agent/{id}/message` | POST | bounded inbound message |
| `/feed/{id}` | GET | page SSE feed |
| `/data` | GET | database inspection |
| `/css/{*path}` and `/js/{*path}` | GET | static assets |

Named reverse routing uses the same compiled router. Reitit's conflict checks
run before the HTTP service binds. The table has no database mirror or dynamic
route population.

An unknown route produces the web owner's not-found response. Unknown agent or
namespace identities are resolved against the current database value and fail
as explicit HTTP responses rather than creating route state.

### Browser-local state

Each feed connection is process-local and keyed by its URL identity. Open-tab
registration, SSE taps, transient selection, inputs, scroll, and disclosure are
disposable browser/process state. They are not database facts and recovery does
not pretend otherwise. Reconnect renders current database truth.

## The in-process render flow

The live channel uses `core.async.flow` graphs inside the cluster JVM:

1. a transaction commits and offers a coalesced wake through a
   `(sliding-buffer 1)` channel;
2. the walk derives flat render-call units; retained fragment evidence lets the
   render proc reuse already serialized bytes for unchanged units;
3. first-party walk/render work runs on correctly classified Flow procs, while
   agent-authored calls cross the bounded SCI launcher with `:interrupt-fn`,
   invocation-class `time-limit`, admission, and output caps;
4. the render proc compares fragment evidence and completed bytes, suppressing
   equal output in the same state that owns the package;
5. the render proc builds ONE REVISIONED COMPOSITE PACKAGE per page —
   `{:seon.render.package/revision, /base-revision, /keyframe-bytes,
   /delta-bytes, /keyframe-size, /delta-size}` — where the keyframe is one
   complete Datastar event carrying every block and the delta is one
   changed-fragment event, both serialized exactly once; and
6. a `mult` publishes that immutable package to ONE `(sliding-buffer 1)` tap
   PER TAB — not one per render unit — and one connection-owned virtual
   thread per tab reads its tap and writes to that tab's single SSE
   connection with bounded writes.

**Every pending value independently repairs the page.** A tab whose delivered
revision is contiguous applies the delta; a tab that detects a REVISION GAP —
because it was late, stalled, or parked behind a slow write — applies the
package's keyframe instead. There is no delta-only buffer and no accumulated
patch list, so a slow browser can never hold up the `mult`, the render proc, or
any other tab. Tabs hold only their delivered revision; they do not keep
complete HTML maps and never diff.

**Late tabs need a snapshot, because `mult` does not replay.** The render proc
is the single writer of one process-local latest-package snapshot; feeds only
dereference it when joining. Like the equality cache, it is disposable derived
memory — never a database fact, a replay log, or a second render owner. A proc
restart simply derives a fresh keyframe from current database truth.

**Initial page load is fully rendered and cached.** The initial document embeds
the cached keyframe bytes rather than calling the renderer, so a tab paints a
complete page immediately and thereafter receives only changed blocks. That
preserves ONE serialization owner: bytes are produced once by the render proc
and reused everywhere, never re-derived per connection.

**Serialization happens once, in the render proc.** The connection writer does
no Hiccup work and no Datastar framing — it sends already-framed bytes. SCI
render, admission, serialization, equality comparison and event framing are
`:compute` work; connection reads, sends, and pending-write waits are `:io`.

**No generic server-side Hiccup differ exists or should be built.** Datastar
computes persistent IDs, greedily matches children, preserves ID-addressed
nodes, and stops descending at `isEqualNode`. Server granularity is the
stable-ID block; a finer semantic fragment is introduced only where measurement
demands it, the first being the active streamed reply. Measured: a 250-event
block morph is 1.2–1.5 ms p95 in Chrome and its patch about 85.5 KB, against
437 bytes for the active row — so a hot block stays bounded (roughly 1,000
events) and splits or pages before it grows past a 16 ms budget.

Full measured derivation, alternatives rejected, and the owner decisions behind
this shape are in
`docs/prds/sci-execution-runtime/research/render-pipeline-design-2026-07-29.md`.
READ THAT DOCUMENT WHOLE before changing the pipeline; it is 904 lines and its
verdict, its "differences from the current pipeline" section, and its owner
decisions each carry constraints a grep will miss.

Flow topology is static within each `graph-def`. The cluster render graph names
the interest and render procs, their `step-fn`s, bounded channels, and `conns`.
A tab is deliberately NOT a graph: connections churn with browsers while graph
topology is static, so opening a tab taps the `mult` and starts one virtual
thread that owns the SSE connection; closing the tab untaps and ends it.
Scheduling is global Flow policy, never a render-local scheduler. Blocking-only
walk/read leaves use `:io`, units proven entirely compute use `:compute`, and
mixed or unresolved work uses fail-closed `:mixed`; socket writes park their
connection-owned virtual threads. The bounded compute executor remains fair
when one agent floods wakes. Each graph's report channel and unmodified
`flow-monitor` are the operational and visualization surfaces. Flow channels
are disposable in-process scheduling state, never a second database work
ledger.

**Nothing rendered is stored.** Retained fragment evidence, serialized bytes,
and latest packages exist only in process memory. Restart discards them and
re-derives demanded pages from facts. There is no stored render
snapshot, presentation attribute, or replay log for display output.

Streamed reply partials enter this same flow as another producer. The model
call's provider fold reduces its byte stream to the completion value while
offering coalesced complete prefixes onto the render proc's sliding-1 in-port;
a full buffer drops intermediate prefixes rather than delaying inference. The
provider attempt and the completion's durable consequences remain the forensic
facts. A reconnect paints current database truth and does not replay transient
prefixes.

### Fine-grained Datastar element patches

Initial paint is the only whole-page render. Later work is per visible render
unit: root cards, canvas, context, debug, `/data`, and shared header units keep
their stable element IDs and emit independent Datastar patches. Conditional
elements disappear through the same unit patch. Equivalent render-unit demands
share one evaluation before `mult` fan-out; a slow tab retains only its newest
patch per unit through its `(sliding-buffer 1)` tap.

Each evaluation receives a committed report's exact immutable `:db-after`
value. A renderer's read form yields its concrete attribute dependency plan and
pull selector. The cache keeps last-seen per-attribute commit IDs and compares
them directly on wake; there is no writer-side reverse registration index.
Missing evidence uses the conservative revision, and code/schema changes use
the process-local code revision. This process-local state affects performance
only and never becomes database authority.

The shim page and feed remain distinct GET routes. http-kit and the Datastar SDK
own the one SSE response per tab; `Accept-Encoding` remains authoritative.
Transient browser state lives in Datastar signals, never DOM attributes.
Historical requests carry the complete
`{:db-name :t :as-of :since :history :datahike/commit-id}` value and are frozen
from current transaction wakes. Reconnect performs an initial paint from the
resolved current or historical database value; there is no numeric replay.

The hard invariant is unchanged: no agent code touches an SSE connection.
Agent-authored renderers return admitted values; connection-owned writer
threads alone serialize
and write patches. Browser actions become database facts or guarded callback
results, and the same render flow derives the visible consequence.

## Errors render as surfaces

Any render failure becomes the flat error value from [[data-model]] §6 instead
of crashing siblings. The HTML render shows it as an **error card**—friendly
message, offending render-call identity and symbol, actionable hint—while
ancestors and siblings continue. At the SCI boundary the same failure enters
durable problem routing as a fact naming the agent that ran the code. That
agent's next context reaches it and root receives the escalation through the
same graph; no exception crosses into a proc and no failure disappears after
one page pass. Fixing the underlying function replaces the current error card
on the next render, while database history retains the forensic fact.
Agent-authored route/layout failures follow the same guarantee.

## Downstream composition

A downstream cluster composes the same public mechanisms: the code route table
chooses page handlers, while schemas and entity facts choose renderers through
the one resolution chain. Consumer-specific pages and routes belong in the
downstream repository; Seon core does not persist route or canvas entities for
them.

## Malli throughout

Every render, page, feed, and message request has a registered Malli shape and
is instrumented like the rest of the runtime. Reitit route-data remains plain
code data; it is not mirrored into a database schema.

## See also

Strict single-ownership: when a fact you need is owned by another doc, follow the
link and read it.

- [[architecture]] — the map: glossary, the cross-cutting principles, deployment topology.
- [[data-model]] — the agent, message, run, eval, and flat-error facts these
  renders read.
- [[agent-runtime]] — the agent graph that assembles the prompt and owns run
  transitions.
- [[toolkit]] — the **[TARGET]** generalized canvas and control boundary.
- [[context-rebuild]] — the measured arc for knowledge-on-demand (cards +
  state-gated teaching + pull); imported `my.skills` bodies remain explicit
  overrides rather than a default context block.
- [[roadmap]] — implementation state, gaps, work order, and evidence.
- [[datahike-primer]] — the datahike-in-the-grain mindset.
