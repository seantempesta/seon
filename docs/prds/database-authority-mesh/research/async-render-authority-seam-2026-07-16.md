---
type: research
status: complete
tags: [research, database, flow, web, agent]
---

# Async render authority seam — 2026-07-16

## Decision

Replica deletion should not make the recursive render walker asynchronous and
should not move render code into the JVM. Use two execution shapes behind the
same ordinary render-result data:

- Core pages and surfaces perform one explicit, coordinate-pinned
  `execute-many` acquisition, then call pure synchronous ClojureScript render
  functions over the returned data.
- Agent-authored render functions execute in that agent's isolated Bun child.
  The child invokes the function through the existing SCI-bounded path, awaits
  its ordinary asynchronous `seon.db` calls, validates the resolved result, and
  returns ordinary hiccup/AI data plus the authority dependency evidence for
  the reads actually used.

Keep `seon.web.view-unit` as the pure owner of consumer sharing, dependency
routing, last serialized output, and final-consumer release. Do **not** make its
state transitions perform I/O. The Bun web owner starts awaited acquisition or
child execution outside the atom, then installs the completed result only when
the unit is still active at the same requested database coordinate and renderer
token. The existing universal database request ID owns authority cancellation.

This is the smallest seam that preserves all current features while removing
local Datahike values and read replay. Core rendering pays one authority round
trip per grouped refresh, not one round trip per database call. Equivalent
browser views still acquire, render, serialize, and retain output once.
Agent-authored functions retain open-ended branching and direct `seon.db`
capabilities without putting untrusted code or a new template interpreter in
the authority.

## Dependency ledger

| Owner | Selected source | Relevant fact |
|---|---|---|
| Current shared render unit | `src/seon/web/view_unit.cljs` at `567ec491` | A unit is keyed by a stable coordinate token, shares one serialized element among consumer IDs, reverse-indexes captured dependencies, and releases everything when its last consumer detaches. Its producer and database value are invocation inputs, not retained state. |
| Datastar owner | `src/seon/web/datastar.cljs` at `567ec491` | `transition-active-units` currently invokes all candidate producers synchronously inside one read/derive/reset extent. Browser count does not multiply managed unit work. Feed delivery already keeps only the latest pending event for a slow connection. |
| Current read observation | `src/seon/db.cljs:430-599`, `src/seon/db/internal.cljs:91-138,1253-1302` | `capture-reads` deliberately rejects a Promise. The lower `capture-operations!` owner already awaits a function and captures nested operations through `AsyncLocalStorage`; eval uses it today. |
| Current UI derivation | `src/seon/ui/agent_view.cljs`, `src/seon/render/surface.cljs` | The agent page renders a catalog, headers, and selected surfaces from one immutable database value. It retains per-surface captured observations to avoid rerendering unchanged surfaces. |
| Render engine | `src/seon/render.cljs`, `src/seon/render/canvas.cljs` | The recursive walker, slot expansion, core render functions, and output schemas are synchronous. `:seon.render/system-input` currently requires a Datahike value and renderers may query while walking. |
| Authored render isolation | `src/seon/render/sci.cljs:392-535` | Agent-authored symbols are reconstructed from database source and namespace facts, interpreted under an SCI deadline, deep-forced before returning, and never fall back to unbounded compiled execution. The current invocation is synchronous and validates before a Promise could settle. |
| SCI async support | vendored SCI `reference-code/sci/src/sci/impl/async_macro.cljc` and `src/sci/async.cljs` | `^:async` bodies are transformed to Promise chains; `await` works in lets, expressions, branches, loops, `try`, and nested async functions. A non-awaited async function returns a Promise by design. |
| Existing awaited invocation | `src/seon/web/reactive/call.cljs:128-179` | Seon already resolves a function, invokes it inside agent/transaction context, wraps its return in `Promise.resolve`, awaits settlement, and converts throws/rejections to ordinary result data. |
| Authority read surface | `research/remote-datahike-operation-seams-2026-07-16.md`, `research/atomic-bun-authority-consumer-replacement-2026-07-16.md` | `execute-many` resolves one immutable value once. Query evidence returns conservative attribute dependencies. One web-host interest receives coordinates and matching datoms; no Datahike value or function crosses the wire. |
| Bun server | Bun `be77b652884b16a103cfaa4af3c1102f72f2dcd3` and `research/bun-serve-datastar-internals-2026-07-16.md` | A direct `ReadableStream` exposes accepted-byte counts and `flush(true)` drainage. `Request.signal` and stream cancellation provide connection-scoped cleanup. |
| Datastar client | `reference-code/datastar/library/src/plugins/actions/fetch.ts:277-325` | The client consumes arbitrary asynchronous `ReadableStream<Uint8Array>` chunks; render acquisition timing is not part of its morph protocol. |

## What production rendering does today

There are three distinct shapes, and treating them as one is what would make
the replacement unnecessarily complicated.

### Small core managed units

The normal agent-page catalog currently declares two managed units:
`system-header` and `agent-header`. Debug pages add more managed units, but use
the same descriptor shape. `view-unit/attach-consumer` invokes the producer only
for the first equivalent consumer. `candidate-tokens` uses a broad bucket or
changed attributes, exact replay suppresses unchanged reads, and serialized
equality suppresses unchanged output.

These are ideal explicit acquisitions. Their query/pull inputs are known in
core source, so a grouped authority request can fetch all required ordinary
data at one coordinate before the synchronous header functions run.

### The core agent page and surfaces

`agent-view/render-agent-view` first derives the surface catalog, materializes
each selected surface once, chooses focus, and renders headers and layout.
`agent-view/transition` distinguishes structural attributes from per-surface
attributes. `render.surface` currently discovers catalog/focus through several
queries and captures the database reads performed inside every selected
renderer. This is the high-value batching target: catalog, focus, headers,
canvas state, context nodes, and bounded transcript data can be members of one
coordinate-pinned `execute-many` request, with `pull-many` used where entities
share a pattern. The returned ordinary input feeds the existing pure layout,
face projection, Hiccup serialization, and Datastar morph code.

The recursive walker should receive ordinary namespaced input, node data, and
its recursion function. It should no longer receive `:seon.db/db`. Core
renderers that still query during recursion have not crossed the new boundary
and must be refactored to acquire at their owning outer computation.

### Open-ended agent-authored renderers

Stored `:seon.render/html` values may be literal hiccup, trusted core symbols,
or agent-authored symbols. Agent-authored canvas/block renderers can branch and
issue arbitrary allowed `seon.db` reads. Their source and required namespace
facts are themselves database data used to reconstruct a fresh SCI context.

An explicit static read plan cannot faithfully describe those functions
without either restricting the feature or inventing a second query language.
The isolated agent child is therefore the correct async boundary. It already
owns the agent's compiler/runtime context and direct authority session; process
failure is contained to that child instead of the web host.

## The replacement flow

### Initial or changed core view

1. The web host normalizes equivalent browser consumers to the existing view
   coordinate/token and retains one active unit.
2. Its one database-scoped interest receives a committed coordinate and
   changed-attribute evidence. The existing reverse attribute index selects
   candidate units; unrelated units only advance their coordinate.
3. The owner groups all candidate core reads into one `execute-many` request at
   that coordinate. Datahike resolves one immutable value, shares indexes,
   query cache entries, single-flight work, and `pull-many` parsing internally.
4. If returned ordinary input equals the unit's previous input, the owner skips
   the pure renderer. Otherwise it renders and serializes once. Equal serialized
   output suppresses the Datastar event exactly as today.
5. A completed result is installed only if the unit still has consumers and
   still expects the same database coordinate and renderer token. A later
   coordinate makes the completion stale data, not state.

The first cut may compare the bounded ordinary result directly. It should not
add a speculative projection cache or a JVM-computed result hash merely to
avoid that equality check. Datahike's exact query cache and Seon's retained
active-unit input already cover the measured reuse boundary.

### Initial or changed authored view

1. The web host sends one render request to the owning agent child containing
   the existing agent ID, renderer symbol, requested database coordinate, view,
   and ordinary node/context input. It does not send a database value or a
   function.
2. The child reconstructs and invokes the symbol through the existing SCI
   deadline. The invocation accepts both synchronous functions and `^:async`
   functions by wrapping the return in `Promise.resolve` and awaiting it.
3. Remote `seon.db` calls target the request's fixed coordinate. The child's
   existing async operation-capture scope records the operations that actually
   ran; authority query evidence supplies their conservative dependency
   attributes. The renderer returns only resolved ordinary render data.
4. After settlement, the child deep-forces and validates the Hiccup/AI response.
   Malli must validate the **resolved value**, never the Promise. A synchronous
   render-result schema therefore remains truthful; the host Promise owner is
   an internal execution boundary rather than a function falsely claiming to
   return `:seon.render/html-response` immediately.
5. The web host retains the dependency union and serialized output in the same
   active unit. Equivalent browsers share that one child execution. A matching
   change reruns the renderer once because its branch may change; output
   equality still suppresses a morph.

This path permits an authored renderer to make sequential reads when its next
read genuinely depends on the previous result. Authors can use `execute-many`
for independent reads. The system should not pretend arbitrary branching is
one round trip. The performance rule is instead: core paths are deliberately
grouped; open-ended paths pay for the behavior they choose, remain bounded, and
never multiply by browser count.

## Why the view-unit producer itself should not become async

Changing `::producer` from `fn?` to a Promise-returning function is superficially
small but puts effects inside the wrong owner. Current transitions rely on one
synchronous read/derive/reset extent specifically so a producer runs once and
`swap!` cannot retry it. Awaiting inside that extent allows detach, replacement,
and newer commits to interleave; holding an atom transaction across the await is
not possible.

Keep pure operations for:

- attach/detach and final release;
- candidate selection from dependency attributes;
- deciding whether a completed result still belongs to the active unit;
- installing ordinary input/dependencies/serialized output; and
- deriving per-transition diagnostics.

The web owner performs the I/O between selection and installation. This does
not require a parallel view-unit implementation. It is an in-place replacement
of `derive-unit`, local observation replay, and synchronous producer invocation.

## Interest, sharing, and round trips

The web host should retain one database-scoped authority interest, not one per
browser and not one per renderer. Its filter is the union of dependency
attributes for active units; the current reverse index maps each event back to
candidate unit tokens. Registration/replacement must obey the authority's
coordinate ordering so a commit is represented either in the acknowledged
coordinate or a later event.

Expected costs are:

| Case | Authority work | Bun render work |
|---|---|---|
| Second equivalent browser | none | reuse retained serialized element |
| Unrelated commit | no read | advance coordinate only |
| Candidate core refresh | one grouped `execute-many` for all ready core units | pure render only for changed ordinary input |
| Candidate authored refresh | the reads actually executed by one isolated render | one SCI invocation shared by all equivalent browsers |
| Slow browser | none beyond shared refresh | retain only its latest pending SSE event |

If many candidate core units become ready at the same committed coordinate,
the owner should coalesce the current event-loop turn and issue one grouped
request rather than one request per unit. It must not delay indefinitely to
manufacture larger batches.

## Cancellation, pressure, and failure

- **Superseding commits:** cancel the older universal database request ID when
  no other caller needs it, or ignore its fenced late completion. Start at most
  the latest coordinate retained for that unit.
- **Final consumer close:** detach the consumer. If it was the final consumer,
  cancel in-flight authority work and any child render request, then release
  dependencies, ordinary input, and serialized output. This preserves the
  desired scope-based cache eviction without weak references or stored flags.
- **Browser pressure:** Bun's direct stream accepts a chunk even when `write`
  reports negative pressure. Await `flush(true)` and retain only the newest
  later event. Browser pressure must never keep a database request or child
  render alive.
- **Agent-child crash:** resolve only that authored render as a namespaced
  `:seon/error` surface, release its in-flight database requests, and let the
  supervisor restart the child. Core pages, sibling authored units, the web
  host, and the JVM authority remain alive. Do not persist a last-good rendered
  projection as a recovery cache.
- **SCI timeout:** preserve the current no-unbounded-fallback law. Cancellation
  must reach awaited database calls as well as SCI execution. A timeout returns
  the existing interrupt/error result; it never wedges the web host.
- **Authority/session loss:** active units remain ordinary ephemeral state but
  are marked for refresh after reattachment. A gap produces the existing
  explicit resynchronization at a coordinate, not replay of a full replica
  feed.

## Deletions enabled by this seam

Delete, rather than adapt:

- the CLJS Datahike database value from `:seon.render/system-input`, section
  requests, view-unit attach/transition requests, and every page producer;
- `capture-reads`, `read-observation-changed?`, result replay, captured local
  Datahike observation schemas, and the surface observation maps used only for
  local replay;
- `render-observed`/`transition-observed` and the synchronous
  `transition-active-units` effectful producer extent;
- local `db/history`, entity caching, pull/query/index traversal, and replica
  listener assumptions from render/UI/debug owners; and
- any Promise-shaped compatibility object pretending to be a Datahike database
  value.

Retain and strengthen in place:

- `seon.db` names and ordinary operation requests;
- `seon.render`'s one guarded walker and standard Hiccup/AI response;
- SCI isolation, deadline, deep-force, and errors-as-values behavior;
- view-coordinate normalization, one serialized output per equivalent view,
  reverse dependency routing, and final-consumer release; and
- Datastar element morphs and latest-wins per-browser delivery.

The async operation-capture mechanism remains useful for eval and authored
render execution, but its remote form records ordinary request/evidence data.
It must not retain a database value or reintroduce local result replay.

## Rejected alternatives

### Make every renderer async

This spreads Promises through recursive slots, face projection, Hiccup
validation, Malli output schemas, serialization, and every core function. It
also turns known core database reads into avoidable sequential round trips.
There is no benefit once outer acquisition exists.

### Run render functions in the JVM

This crosses the wrong boundary: the authority would need CLJS/SCI code,
compiler state, function transfer, Hiccup schemas, deadlines, and agent crash
policy. It also makes database capacity compete with untrusted rendering. The
JVM returns ordinary data and dependency evidence only.

### Define a remote render/query template language

Core reads already compose through `execute-many`; authored renderers already
have ClojureScript plus async `seon.db`. A template language duplicates both,
restricts branching, and creates another validator/interpreter to maintain.

### Keep a local projection cache after replica deletion

The active view unit may retain bounded ordinary input and its last serialized
element while consumers exist. A second durable or speculative projection
cache would have different eviction and coordinate semantics and is not
justified by source or measurement. Datahike owns exact query caching.

### One render worker inside the web host

It contains neither failure nor CPU starvation: one authored loop can still
damage every browser feed. The owning agent child is the existing isolation and
compiler-context boundary. If an inactive agent child is not running when its
page is opened, the supervisor starts it on demand; the web host must not gain a
second unisolated authored execution path.

## Implementation order and proof

1. Define ordinary core acquisition results for headers, agent page/surfaces,
   root page, debug, and data browser. Measure request members and returned
   weight; merge N+1 pulls into `pull-many` or a bounded query.
2. Refactor each core owner to `^:async` outer acquisition plus pure renderer,
   while the local replica still supplies a comparison fixture. Assert the pure
   renderer receives no Datahike value and returns no Promise.
3. Add isolated authored-render invocation to the agent child by adapting the
   existing SCI and awaited invocation owners. Prove sync and `^:async`
   renderers, sequential and grouped reads, resolved-value Malli validation,
   timeout, rejection, cancellation, and child death.
4. Replace view-unit derivation with pure candidate/completion transitions and
   an awaited web owner. Prove detach and a newer coordinate during an in-flight
   read cannot resurrect or overwrite a unit.
5. Replace local observations with authority query evidence, install one web
   interest, and prove targeted commits select the same units as the old
   fixture. An unknown dependency fails broad.
6. Delete replica/read-replay reachability and run one atomic Bun build. No
   compatibility mode remains.

Graduation evidence must show:

- 100 equivalent browser consumers cause one authority acquisition, one
  renderer execution, and one serialization;
- one commit affecting ten ready core units produces one grouped authority
  request, while an unrelated commit produces none;
- equal ordinary results and equal serialized output suppress independently;
- an authored renderer can await a database read, branch, issue a second read,
  and return valid hiccup without a Promise reaching Malli or serialization;
- closing the last consumer and superseding a coordinate cancel/reject late
  work with zero retained request, child-call, input, dependency, or output;
- a child timeout/crash affects only its authored surface; and
- a slow/closed browser retains at most one pending latest event and never pins
  authority or child work.

## Remaining measured choice

The design does not require choosing a fixed core batch size in advance. During
implementation, measure the ordinary result weight and latency for agent page,
root page, debug, and data browser separately. If one page's grouped request
creates excessive head-of-line latency, split it only at an existing independent
surface boundary and run those members in parallel at the same coordinate.
That is an implementation choice under the same seam, not a second protocol or
render architecture.
