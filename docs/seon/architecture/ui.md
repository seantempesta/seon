---
type: architecture
status: active
tags: [architecture, web, agent]
---

# UI — namespace pages, blocks, renders, and routes

> **Target design** (present tense). Implementation state, gaps, order, and
> evidence live only in [[roadmap]].

The human's UI and the agent's prompt are the same acquired data projected as a
form, a printed value, and Hiccup. Every settled web-surface state is a derived
projection of the database; transient streamed prefixes and retained render
entries are process-local values, and no rendered output is persisted as
database truth. The context unit is the **block**; the engine is `seon.render`; the HTTP router is
**reitit**; the live channel is a Datastar **morph** stream backed by one
normalized reactive registration and database-scoped selective interest per
demanded computation. Loopback streams are uncompressed by
default; remote compression is explicit configuration. Every layer is a
symbol or a datom, so a third party overrides any of it — blocks, canvas,
layout, the root agent’s surface, routes, CSS, client — reusing the same
primitives, with zero `src/seon` edits.

The web UI runs inside the **cluster JVM** beside the writer and the agent
graphs. It
serves HTTP through http-kit, frames SSE through the Datastar Clojure SDK, and
reads the cluster's current immutable database values directly. Agent-authored
render functions run only through the one guarded SCI invocation boundary: the retained
`ctx`/`fork`, one `:interrupt-fn`, the render invocation-class `time-limit`,
value admission, and bounded output. No direct Var invocation or
`requiring-resolve` path bypasses that boundary. Supervision, bounded evals, and
cheap flow-graph rebuilds protect the process; the UI does not rely on a
process wall.

There are exactly three render projections. The history asks for
`:seon.render/form` and `:seon.render/ai`; the browser asks for
`:seon.render/html`. Logs and other sinks call ordinary sink-specific functions
rather than extending a generic render kind. The boundary selects one of the
three projections; the data does not carry a stored request kind.

## Human input becomes a message

The only interactive mutation is the agent message form. Its POST route
validates same-origin input, target identity, and bounded content, then commits
one `:seon.cluster.message` row. The agent's own graph wakes from the `/to`
datom and derives its run from database truth. The HTTP response is not an
execution-result channel; the web UI feed renders the later message, run, and
eval facts.

## Canvas and controls

The canvas is the focal shared value on a namespace page: the surface the agent
and human inspect together. Generalized agent-authored canvas and control
constructors are **[TARGET]**; HEAD currently exposes derived namespace-page
HTML, browser-local disclosure, and the fixed message form, not a `my.canvas`
API or generic callback route. A future control produces ordinary render data.
Its action is either a pure value interpreted by the run loop or a genuine
capability request through `seon.effect/request!`; it never gains an effectful
eval helper that mutates web-surface state. Exact constructors and routes remain
unnamed until their schemas and action contract are settled.

## The projection contract — three outputs, one selection chain

Rendering is the complete output boundary. Every context unit crosses
`:seon.render/form` for its producing form and `:seon.render/ai` for its printed
value. Every semantic web UI value crosses `:seon.render/html`. Other
consumer-visible text values—MCP or tool results, runner output, logs, faults,
and terminal or operator faces—cross `:seon.render/ai`. JSON and SSE framing
plus literal authored static copy are transport bytes after that boundary, not
additional projections. A **unit** is schema'd data acquired by the one
schema-derived pull rooted at the agent. The boundary requests a projection,
and the render function resolves it through the program graph. First-party Vars
remain live under re-evaluation. A nonidentical base-Var redefinition is
accepted and emits the ordinary Clojure-style warning. Agent-authored program-graph
functions are installed SCI values invoked through the guarded boundary, never
host-resolved Vars.

Explicit declarations ride on the value being projected; otherwise the
selector queries the program graph and schema metadata. A failure inside the
projection machinery may emit one bounded emergency diagnostic; that escape is
a fault to repair, not a third normal output path. Program rows declare
`:seon.fn/external-sink` and `:seon.fn/projection-boundary`, so the remaining
projected, bypass, and unresolved paths are graph queries rather than a maintained list.

Failures are flat `:seon.error` values, never throws. No declaration is
required: a value with no specialist reaches the structural floor. An
ambiguous contract fit or a selected render-function failure is loud and does not
fall through to another render function. The browser receives only an unavailable
state; an explicitly assigned namespace owner receives the durable diagnostic
message.

### One resolution chain and one floor

Every acquired value resolves each of `:seon.render/form`, `:seon.render/ai`,
and `:seon.render/html` through one chain, most specific first:

1. the explicit projection key on the value;
2. the unique contract-fitting public function in the data's explicitly
   owning namespace, when a data or traversal ref names one;
3. the schema-attached default render function; and
4. the structural floor.

Function rows carry input and output schemas, so namespace candidate selection
is one ordinary program-graph query through the query cache. There is no render-function rule
registry, slot redirect, or second floor. A broken render function yields a flat error
unit and never promotes a different mechanism silently.

## The block and its three projections

A **block** is the informal name for one render function call's identified
output, never a database entity or stored data type. Its identity derives from
the render function and explicit arguments; the HTML
projection derives one escaped stable DOM element id from that identity.

- **form** (`:seon.render/form`) → the Clojure form that produces the value.
- **ai render** (`:seon.render/ai`) → prompt bytes.
- **html render** (`:seon.render/html`) → a surface whose output is hiccup.

The same call may provide all three projections. Explicit render keys on a value
override the program-graph/schema chain, but their absence never makes the
system partial. The form floor uses an attribute listing query or an entity
identity pull; the AI and HTML structural floors render any value. All three
include every acquired unit, and HTML's floor is the same prepared admitted
value as AI's floor, decorated by the value printer rather than hidden behind a
second visibility mechanism.

**The morph target is the block.** Each block is patched independently at its
stable element id. The render proc retains each logical call's dependency
evidence, latest output, and serialized entries, so unchanged units reuse their
bytes before fan-out. A refreshed render whose HTML is byte-equal still appends
the basis-labelled history observation while equality suppresses its web-surface patch.
Whole-web-surface morphing is not the live-update fallback: initial paint builds the
web surface once, then every change targets the smallest stable block whose bytes
changed. Serialization, authored evaluation, and slow-reader pressure
therefore scale with changed content rather than web-surface size.

## Membership is the root pull — one derivation, no stored set

**Blocks are derived, not installed.** One pull rooted at the agent
acquires the values reachable from it at one database value. Its selector is
generated from installed schema ref declarations, including nested forward and
reverse refs; the result is also the membership index for arrivals and
removals. Each acquired value resolves all three projections through the same
selection chain and emits one block. There is no static scaffold path.
The cluster entity owns the authoritative refs to shared instruction rows;
agent entities carry only genuine additions. Both are ordinary schema'd facts
rendered by ordinary, overridable render functions, and instruction text mutates in
place on its stable identity.

**Resolution is one chain, most specific first:**

1. the explicit `:seon.render/form`, `:seon.render/ai`, or
   `:seon.render/html` key on the value;
2. the unique contract-fitting public function in the data's explicitly
   owning namespace;
3. the schema-attached default render function;
4. the structural floor.

There is one chain and one floor. A layout never redirects resolution, and no
stored membership, band, or priority attribute exists.

Global-vs-per-agent is decided by the data's connections, never by the block.
The agent family is scoped by `:seon.cluster.agent/id`; messages, runs,
receipts, and errors point to their owning or receiving agents through refs.
The render function scopes by the facts it queries. See [[data-model]].

## The render engine

One engine, `seon.render`, renders every web surface and the prompt. Every render call
requires a complete immutable database value under `:seon.db/db`; absence is a
flat `:core-bug`, never permission to consult a process-global latest value. The
engine performs one root pull and runs each acquired unit through one selection
chain in three projections (`:form` → Clojure form, `:ai` → String, `:html` →
Hiccup). Renders are retained process-locally and never persisted as database
truth. A failing
render yields a flat error value (see [[data-model]] §6) for that render only;
siblings never crash.

**Prompt and namespace page share one retained artifact by construction.** The prompt,
root's preview of an attached agent, and that agent's namespace page use the same retained
block outputs at different fits. The history appends a block's form and AI
projection; the web UI places its HTML projection. "What the agent saw for this
model call" is the committed context capture: its exact prompt bytes plus the
basis-labelled ordered contribution evidence.

**The structural floor is one merged data browser.** It combines the bounded
value skeleton with `/data`'s `get-in` path navigation on the admission codec's one
walk discipline. It can render any value without throwing, preserves
identities/indices and explicit elision markers, and pays only for opened data.
Specialized message, source, error, and hiccup projections sit above it through
the one resolution chain; they do not create another walker or floor.

**Fit belongs to the consumer profile.** Profiles are database-derived config
facts carrying token, depth, child, blob, and composition policy.
`seon.print/fit` is the one owner that applies them after semantic render-function
selection. Render functions never invent local truncation rules. A value that does
not fit contains an ordinary elision value carrying its omitted count, known
total, path, next offset, producing profile, and requery identity or explicit
refusal. Elision can therefore be rendered and inspected like any other value.

**The history is form then actual value.** Eval receipts order by their durable
facts, while messages appear as explicit query forms followed by their actual
returned values. AI renders strict form-then-value REPL text and HTML renders
the same entries. Each retained entry is immutable; a refresh appends a
basis-labelled successor and never summarizes, edits, or deletes an earlier
observation. Database facts and blobs remain available through debug and
`/data`.

**Authorship makes re-execution unrepresentable.** Constructors assign every run
form as agent- or system-authored. Only a terminal system-authored read with no
successor may refresh, and its successor points uniquely to it while retaining
the receipt's read evidence. Agent-authored forms never enter that transition.
The web UI and prompt therefore display retained authored results; neither
re-executes an agent's source to refresh presentation.

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

A `get-in` result carries no feed: repainting it under a reader would move the
ground they are standing on, so reload is the refresh and the URL is the state.

**Large context projections are concise first.** Plan roots render as compact
title/progress disclosures; only the focused root starts open, and its tree has
a bounded internal scroll region. Long titles and goals line-clamp, every
technical surface wraps or horizontally scrolls, and the canvas has a bounded
default height. Scale is handled by disclosure and windowing, never smaller
unbounded text.

**Capability + cache.** The cluster JVM acquires authored program and ordinary
inputs at one immutable database value and invokes authored code through the
one SCI invocation boundary with the `:interrupt-fn`, invocation-class `time-limit`, and value
admission. A function may raise the class default only through `defn` metadata
lifted into its program row at index time; render owns no private timer dial.
Render functions are pure and return ordinary data. Functions invoked outside a
projection request genuine effects only through `seon.effect/request!`; a
refresh never turns a render read into an effect. The byte-stable retained
history prefix is preserved for provider prefix-caching.

**Byte identity is a design property.** The complete database value, verified
configuration and file fingerprints, render inputs, and program artifact
determine the cacheable bytes. Host timezone, process clocks, environment
reads, process-global database state, and process-local result liveness cannot alter
them. Process-local inputs are explicit render arguments and therefore part
of the per-call identity; no free tail renders outside the block system.

**Context assembly is its own domain.** The schema-derived root pull,
define-before-use ordering, append-only basis-labelled entries, and the
namespace-as-location model live in [[context]] — this doc owns the shared
block/render machinery and the human-facing projection. The structural HTML
floor remains visible for every block that has no specialist.

### Render coverage converges on blocks

A domain value becomes broadly observable by accreting the three projections on
the same unit: form plus AI for agent history and HTML for a surface. Operational sinks
call ordinary domain functions. Consumers never rebuild or reclassify the
value.

Prompt assembly follows the same convergence. Context entries are form and AI
projections of blocks, not strings assembled beside the render engine. As
domains gain form, AI, and HTML projections, prompt and namespace page become different
bounded fits of the same units rather than parallel presentation systems.

## Streamed replies — the one high-churn path

A streamed reply is the only genuinely high-churn thing the UI shows, and it
rides a channel, never the writer. Partials are COALESCED COMPLETE VALUES
offered onto one `(sliding-buffer 1)` conn from the model call's provider reducer
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
thread.** The reducer offers the newest complete prefix onto the sliding-1 conn
and returns; the put never parks. Presentation may lag and may DROP
intermediate snapshots — both are correct — and it may never slow the model
call. The governing invariant is the provider reference's own: partial display
cannot affect transport, parsing, usage, or evaluation. A streamed call and a
one-shot call return the same completion value, so nothing downstream can tell
which transport ran.

The live token count derives from the SAME reducer that produces the text, never a
second mechanism counting the same thing twice: the provider's own usage once it
has sent one, and the chunk count until then, which is an approximation and is
named as one.

**Both are ordinary blocks.** The token counter and the streaming reply declare
`:seon.render/html` and return hiccup like any other surface, and each is its
own morph target. The highest-churn thing in the system needs no render
machinery of its own — which is the reason those two were chosen as the
exercises that prove the design.

## The tree and layouts

A **layout** is an ordinary first-party HTML render function over an already resolved
render tree. It receives blocks and branch relationships as data and owns only
placement and CSS. It never names a block to trigger render-function resolution,
redirects one render to another, or changes membership. No slot redirect exists.

An agent web surface gives the newest-basis HTML block the large primary position and
places every other HTML-bearing block in a vertical column of smaller blocks.
The same newest-basis ranking places the freshest appended AI observations
nearest the next model turn. The `/` layout uses one live-window card per
attached agent, each showing that agent's newest-basis block.

**Expansion is bounded** by the root pull's selector depth and `:limit`, plus
the structural floor's node and child bounds. Stable identity detects an
already emitted unit and produces a back-reference. Elision is explicit and
drillable; a cycle, failed child render, or exhausted bound produces an in-place
error/elision unit so one branch never blanks the web surface.

**Web surfaces are bounded reductions over the acquired tree.** An agent web surface or namespace page
uses the same schema-derived pull rooted at its resolved agent, resolves each
schema'd value once through the one chain, and hands the resulting tree to its
layout. The `/data` browser is the floor exposed directly: paged `get-in`
navigation into any nested value uses the same bounded expansion and cursor
discipline.

## Web surfaces — root, namespace, agent, debug, and data

Every web surface is a projection over one database value and the same root-pull/block
machinery:

- `/` renders root's namespace page plus one live-window card per attached
  agent. Each card is that agent's newest-basis block in its HTML fit; the AI
  fit of the same retained block is root's agent preview.
- `/ns/{namespace}` resolves the namespace, its owner agent through
  `:seon.cluster.agent/namespace`, and the owner's root pull.
- `/ns/{namespace}/debug` shows the exact AI context and every acquired unit
  side by side.
- `/agent/{id}` resolves one `:seon.cluster.agent/id` and renders its agent web surface.
- `/agent/{id}/debug` exposes the corresponding acquired tree and projections.
- `/data` renders the structural database floor for inspection.

Namespace pages are generic: adding one is adding the route-table line, not a
new route-specific render function. Agent web surfaces query received and sent messages, runs,
eval receipts, and routed errors using the refs named in [[data-model]]. Root is
the ordinary agent whose identity is `"root"`; it has a specialized layout but
no second route, parent, role, or render model.

Browser-local selection, scroll, disclosure, and input state remain in the browser.
No web-session, canvas pin, focus, route, or selected-agent entity is stored.
The debug surface preserves entity identities and refs for `get-in` path navigation but
does not transact display choices.

## The shell and message action

The shared shell links root and data inspection, includes the current web-surface content,
opens its SSE feed, and adds the agent message form only when the route resolves
an agent identity. `POST /agent/{id}/message` is the one browser mutation
route. It applies same-origin middleware, validates the target agent and bounded
content, and transacts one ordinary `:seon.cluster.message` row. No generic
callback route or function descriptor crosses the browser boundary.

## Routing is one code table

`seon.render.route/routes` is the one route authority, compiled by reitit at
namespace load. The route entries are:

| Route | Method | Handler role |
|---|---|---|
| `/` | GET | root namespace page |
| `/ns/{namespace}` | GET | namespace page |
| `/ns/{namespace}/debug` | GET | namespace debug surface |
| `/agent/{id}` | GET | agent web surface |
| `/agent/{id}/debug` | GET | agent debug surface |
| `/agent/{id}/message` | POST | bounded inbound message |
| `/feed/{id}` | GET | web-surface SSE feed |
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

1. the one transaction listener keeps work wakes limited to addressed facts and
   separately offers one payload-free render signal through a
   `(sliding-buffer 1)` channel only when changed attributes intersect retained
   render interests;
2. the render proc dereferences the latest database value, uses retained
   Datahike read evidence to replay candidate reads, and invokes only
   semantically stale or newly acquired logical calls;
3. first-party pull/render work runs on correctly classified Flow procs, while
   agent-authored calls cross the bounded SCI launcher with `:interrupt-fn`,
   invocation-class `time-limit`, admission, and output caps;
4. the render proc appends one immutable basis-labelled history entry for every
   refreshed call and independently equality-suppresses unchanged HTML patches;
5. the render proc builds ONE REVISIONED COMPOSITE PACKAGE per web surface —
   `{:seon.render.package/revision, /base-revision, /keyframe-bytes,
   /delta-bytes, /keyframe-size, /delta-size}` — where the keyframe is one
   complete Datastar event carrying every block and the delta is one
   changed-fragment event, both serialized exactly once; and
6. a `mult` publishes that immutable package to ONE `(sliding-buffer 1)` tap
   PER TAB — not one per render unit — and one connection-owned virtual
   thread per tab reads its tap and writes to that tab's single SSE
   connection with bounded writes.

**Every pending value independently repairs the web surface.** A tab whose delivered
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

**Initial document load is fully rendered and cached.** The initial document embeds
the cached keyframe bytes rather than calling a render function, so a tab paints a
complete document immediately and thereafter receives only changed blocks. That
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
demands it, the first being the active streamed reply. A hot block remains
bounded and splits or pages before it exceeds the render budget.

Flow topology is static within each `graph-def`. The cluster render graph names
the interest and render procs, their `step-fn`s, bounded channels, and `conns`.
A tab is deliberately NOT a graph: connections churn with browsers while graph
topology is static, so opening a tab taps the `mult` and starts one virtual
thread that owns the SSE connection; closing the tab untaps and ends it.
Scheduling is global Flow policy, never a render-local scheduler. Blocking-only
pull/read leaves use `:io`, units proven entirely compute use `:compute`, and
mixed or unresolved work uses fail-closed `:mixed`; socket writes park their
connection-owned virtual threads. The bounded compute executor remains fair
when one agent floods wakes. Each graph's report channel and unmodified
`flow-monitor` are the operational and visualization surfaces. Flow channels
are disposable in-process scheduling state, never a second database work
ledger.

**No rendered output is persisted as database truth.** Retained read evidence,
latest-call outputs, immutable prompt entries, and latest packages exist only
in process memory. Restart discards them and derives the current projection from
facts after registering interest and injecting one initial render signal. It
does not promise the lost prompt generation's byte-identical prefix. There is
no stored render snapshot, presentation attribute, or invalidation replay log.

Streamed reply partials enter this same flow as another producer. The model
call's provider reducer reduces its byte stream to the completion value while
offering coalesced complete prefixes onto the render proc's sliding-1 in-port;
a full buffer drops intermediate prefixes rather than delaying inference. The
provider attempt and the completion's durable consequences remain the forensic
facts. A reconnect paints current database truth and does not replay transient
prefixes.

### Fine-grained Datastar element patches

Initial paint is the only whole-web-surface render. Later work is per visible render
unit: root cards, canvas, context, debug, `/data`, and shared header units keep
their stable element IDs and emit independent Datastar patches. Conditional
elements disappear through the same unit patch. Equivalent render-unit demands
share one evaluation before `mult` fan-out; a slow tab retains only its newest
patch per unit through its `(sliding-buffer 1)` tap.

Each passive render signal is payload-free. The render proc dereferences the
latest immutable database value once and compares it with retained attribute
revisions, so coalesced signals cannot lose an affecting change. A render
function's reads yield concrete dependency plans and replayable evidence. The
proc retains a reverse `attribute -> logical calls` projection, while the
listener sees only their union and never receives a writer-side query index.
Missing evidence uses the conservative revision, and code/schema changes use
the process-local code revision. This process-local state affects performance
only and never becomes database authority.

The shell document and feed remain distinct GET routes. http-kit and the Datastar SDK
own the one SSE response per tab; `Accept-Encoding` remains authoritative.
Transient browser state lives in Datastar signals, never DOM attributes.
Historical requests carry the complete
`{:db-name :t :as-of :since :history :datahike/commit-id}` value and are frozen
from current transaction wakes. Reconnect performs an initial paint from the
resolved current or historical database value; there is no numeric replay.

The hard invariant is unchanged: no agent code touches an SSE connection.
Agent-authored render functions return admitted values; connection-owned writer
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
one render pass. Fixing the underlying function replaces the current error card
on the next render, while database history retains the forensic fact.
Agent-authored route/layout failures follow the same guarantee.

## Downstream composition

A downstream cluster composes the same public mechanisms: the code route table
chooses web handlers, while schemas and entity facts choose render functions through
the one resolution chain. Consumer-specific web surfaces and routes belong in the
downstream repository; Seon core does not persist route or canvas entities for
them.

## Malli throughout

Every render, web-surface, feed, and message request has a registered Malli shape and
is instrumented like the rest of the running system. Reitit route-data remains plain
code data; it is not mirrored into a database schema.

## See also

Strict single-ownership: when a fact you need is owned by another doc, follow the
link and read it.

- [[architecture]] — the map: glossary, the cross-cutting principles, deployment topology.
- [[data-model]] — the agent, message, run, eval, and flat-error facts these
  renders read.
- [[agent-runtime]] — the agent graph that assembles the prompt and owns run
  transitions.
- The `ui-canvas` skill — the built-versus-target canvas/control boundary.
- [[context-rebuild]] — the measured arc for knowledge-on-demand (cards +
  state-gated teaching + pull); imported skill bodies remain explicit
  overrides rather than a default context block.
- [[roadmap]] — implementation state, gaps, work order, and evidence.
- [[datahike-primer]] — the datahike-in-the-grain mindset.
