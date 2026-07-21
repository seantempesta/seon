---
title: Async database consumer migration audit
type: research
status: complete
tags: [research, prd, database, flow, cljs, web, agent]
---

# Async database consumer migration audit

## Question

What is the smallest ordered replacement that moves every pod database reader
from the local Datahike replica to the direct Bun-to-JVM database session,
preserves one exact database coordinate through asynchronous work, maximizes
parallel execution, and then deletes the replica, replay feed, and Node adapter
path atomically?

This audit is deliberately about the consumer side of the settled database
authority. It does not reopen the wire protocol, writer admission, or Datahike
query execution unless a consumer falsifier demonstrates that one of those
settled contracts cannot support the replacement.

## Result

The strongest seam is an asynchronous `seon.db` API backed directly by one
persistent Bun Unix-domain-socket session per process. Core Seon views acquire
all ordinary data needed for one logical operation at one explicit database
coordinate, then run the rest of their computation as pure ClojureScript.
Agent-authored functions use the same public API from their owning Bun child
and await the result there. No Datahike database value, entity wrapper, lazy
sequence, or Bun-native socket value crosses either boundary.

The replacement should happen in place. There should be no `seon.db.remote`,
compatibility connection, second renderer, parallel feed, or production mode
switch. The current checkout already makes this more urgent than optional:
`seon.db.transport.uds` implements the persistent native Bun session, while
`seon.db.replica` still calls the removed `uds/rpc` and
`uds/connect-publisher!` functions. The cold pod bundle therefore stops at an
already-crossed boundary. The next coherent move is to finish the direct
consumer path, not restore the superseded adapter.

The first complete vertical proof should be route acquisition, because it is a
small async-outer/pure-inner consumer with a real committed-change interest.
The first performance-critical replacement should then be turn prompt
acquisition: one coordinate-pinned `execute-many` request before the provider
call, pure context construction after receipt, and a separate post-transaction
read for the final turn result. Rendering and Datastar follow the same shape,
with one acquisition shared by equivalent browser consumers.

Parallelism comes from independent Bun children and concurrent requests on the
same multiplexed JVM session. The JVM is not a global execution gate: it admits
and executes independent reads concurrently against immutable Datahike values,
while Datahike serializes commits per database. One session is a transport and
lifecycle owner, not a serial work queue.

## Dependency ledger

| Dependency or mechanism | Selected source | Relevant interior seam | Existing Seon evidence |
|---|---|---|---|
| Maintained Datahike | `reference-code/datahike` at `d21abadb9412f1b828b02ddb3c08ddc81d57c595` | immutable database values, query evidence, committed cache identity, bounded resource weights, pull-many, index pages | `seon.db.writer`, writer integration and admission tests |
| Bun | `reference-code/bun` at `be77b652884b16a103cfaa4af3c1102f72f2dcd3` | `Bun.connect`, socket write/drain, async context propagation, native HTTP and process APIs | `seon.db.transport.uds` and its CLJS tests |
| shadow-cljs | `reference-code/shadow-cljs` at `4e72595f57618f5c43388ad13d5136cd3bede566` | `:node-script` emits a runnable server artifact; test autorun alone hard-codes Node | current pod and test build configuration |
| ClojureScript | `1.12.145` | Promises and `^:async` functions at the host boundary | existing `seon.web.reactive.call` await containment |
| shadow-cljs release | `3.4.10` | Bun runs the emitted Node-targeted JavaScript | package and dependency manifests |

The exact Datahike source seams that make this design possible are:

- `datahike.query/q-with-evidence` returns the query result plus dependencies,
  cache outcome, and resource evidence.
- `datahike.query/db-cache-key` keys immutable snapshots by committed origin
  and transaction coordinate, including earlier `as-of` values.
- Datahike query execution already owns cache lookup, single-flight work, and
  bounded resource accounting. The pod must not duplicate those indexes.
- `datahike.pull-api/pull-many` preserves input order and returns `nil` for a
  missing entity, which is the correct ordinary-data batch boundary.
- `datahike.index-page/index-page` exposes a bounded materialized index page;
  no lazy Datahike iterator needs to cross the protocol.
- `datahike.db/committed-cache-identity` gives the JVM a stable identity for
  sharing work at one immutable database coordinate.

The selected protocol already provides capabilities, resolve-head, query,
pull, pull-many, schema, index-page, execute-many, cancel, listen, unlisten,
acquire, release, transaction, and KNN operations. `execute-many` preserves
member order and has a bounded aggregate result. Consumers should use those
operations rather than inventing another request language.

## Current boundary

### Direct session already built

`src/seon/db/transport/uds.cljs` already owns the native Bun connection. It
uses `Bun.connect`, handles partial writes and drain, parses fragmented and
coalesced frames, multiplexes request identifiers, enforces deadlines and
cancellation, coalesces database events, and fails pending work when a session
closes. `test/seon/db/transport_uds_test.cljs` covers fragmentation,
coalescing, out-of-order replies, events, native Bun round trips, deadlines,
and retained capacity after timeout.

This is the correct owner. Adding round-robin connections inside one cluster
would obscure admission and cancellation without creating database
parallelism. A single multiplexed session can have many in-flight requests;
separate Bun child processes naturally have separate sessions and isolate
failure domains.

### Old replica is no longer internally coherent

`src/seon/db/replica.cljs` still calls `uds/rpc` at six sites and
`uds/connect-publisher!` for the publisher half, but those functions no longer
exist in `seon.db.transport.uds`. `src/seon/db.cljs` still requires the replica
and local Datahike APIs. `seon.client`, `seon.embed`, and `seon.web.serve` still
import replica behavior.

This means a temporary compatibility layer would not preserve a known-good
system. It would reconstruct a mechanism already selected for deletion. The
safe migration strategy is source-coherent checkpoints followed by one final
runtime checkpoint, not two live production paths.

### Current public reads are synchronous and Datahike-shaped

`src/seon/db.cljs` currently exposes a live local connection through
`db/*conn*`, synchronous query/pull/entity/schema/index/temporal functions,
local `d/listen!`, and captured read replay. `entity` and `entity-lazy` expose
Datahike navigation semantics to consumers. Temporal functions return database
handles that callers can retain.

Those shapes cannot honestly survive a process boundary. The replacement API
must return a Promise of ordinary namespaced data. It may retain familiar
function names where the result remains honest, but it must not emulate a
connection, dereferenceable database, lazy entity, or temporal database value.

## Consumer inventory

A conservative lexical audit found database-read vocabulary in 76 production
CLJ/CLJS files. It counts examples and docstrings as well as calls, so the
numbers are an upper bound, but it accurately shows the migration surface:

| Read form | Occurrences |
|---|---:|
| `query` | 328 |
| `entity` | 131 |
| `pull` | 78 |
| `installed-schema` | 56 |
| `head-coordinate` | 20 |
| `at-coordinate` | 14 |
| `entity-lazy` | 13 |
| `as-of` | 8 |
| `listen!` | 7 |
| `history` | 7 |
| `rseek-datoms` | 5 |
| `unlisten!` | 4 |
| `index-datoms` | 4 |
| captured-read comparison | 3 |
| `basis-t` | 3 |
| `since` | 2 |

These consumers fall into migration cohorts rather than 76 independent API
rewrites.

### Core turn and agent execution

Files:

- `src/seon/agent.cljs`
- `src/seon/agent/loop.cljs`
- `src/seon/agent/run.cljs`
- `src/seon/agent/turn.cljs`
- `src/seon/agent/lifecycle.cljs`
- `src/seon/agent/message.cljs`
- `src/seon/agent/message/internal.cljs`
- `src/seon/agent/runtime.cljs`
- `src/seon/agent/schedule.cljs`
- `src/seon/agent/debug.cljs`
- `src/seon/agent/home.cljs`
- `src/seon/agent/internal.cljs`
- `src/seon/agent/search.cljs`
- `src/seon/agent/search/internal.cljs`
- `src/seon/agent/testrun.cljs`

`seon.agent.turn/run-turn!` is the critical transaction boundary. It currently
pins a local database value, derives the prompt context and current namespace,
then holds that value conceptually across a potentially long provider call. It
also performs a final pull after later writes. Those are two different reads
and must become two explicit coordinates:

1. Resolve or receive the pre-turn coordinate.
2. Execute the independent prompt inputs at that coordinate in one
   `execute-many` request.
3. Release all database acquisition state before calling the provider.
4. Perform writes and use the successful transaction coordinate.
5. Pull the final turn projection at the returned coordinate.

No Datahike snapshot, lease, or cache admission should remain held while a
model is thinking.

### Context construction

Files:

- `src/seon/agent/ctx.cljs`
- `src/seon/agent/ctx/canvas.cljs`
- `src/seon/agent/ctx/menu.cljs`
- `src/seon/agent/ctx/namespaces.cljs`
- `src/seon/agent/ctx/render_fns.cljs`
- `src/seon/agent/ctx/subagents.cljs`
- `src/seon/agent/ctx/transcript.cljs`
- `src/seon/agent/ctx/typeahead_steps.cljs`
- `src/seon/agent/ctx/warnings.cljs`

`pull-agent-entity`, `context-root`, and `rendered-context` currently pass an
ambient database into many nested renderers. The replacement is not a remote
database interpreter. Each owning context function defines the few query or
pull members it needs, an outer async acquisition returns one ordinary-data
input map, and the existing inner functions become pure transformations of
that map.

Read plans should remain ordinary local data beside the computation they
serve. Do not introduce a generic plan registry or new query vocabulary.
`execute-many` groups independent existing protocol operations; dependent work
remains a second request or should be expressed as one better Datahike query.

### Render and web UI

Files:

- `src/seon/render.cljs`
- `src/seon/render/canvas.cljs`
- `src/seon/render/chat.cljs`
- `src/seon/render/sci.cljs`
- `src/seon/render/surface.cljs`
- `src/seon/render/system.cljs`
- `src/seon/route.cljs`
- `src/seon/ui/agent_view.cljs`
- `src/seon/web/brand.cljs`
- `src/seon/web/datastar.cljs`
- `src/seon/web/debug.cljs`
- `src/seon/web/reactive/call.cljs`
- `src/seon/web/router.cljs`
- `src/seon/web/serve.cljs`
- `src/seon/web/view_unit.cljs`

`seon.route/projection->routes` is already pure. Only route acquisition and
listener attachment need replacement, making it the best first full slice.

`seon.web.view-unit/derive-unit` and
`seon.web.datastar/transition-active-units` currently permit database effects
inside state transitions. The target separates them:

- the view-unit registry purely selects missing or stale work;
- the web owner performs async acquisition and rendering outside the atom;
- completion is accepted only if the consumer still exists and both the
  database coordinate and renderer identity still match;
- serialization and latest-pending SSE behavior remain shared for equivalent
  browser consumers.

This preserves Datastar's strongest performance property: 100 browsers looking
at the same logical unit should cause one database acquisition, one render, and
one serialization—not 100 remote queries.

### Evaluation, compiler, and authored functions

Files:

- `src/seon/eval.cljs`
- `src/seon/repl/autocomplete.cljs`
- `src/seon/derive.cljs`
- `src/seon/instrument.cljc`
- `src/seon/schema.cljc`
- `src/seon/schema/internal.cljc`

An authored renderer or function executes and awaits in the Bun child that
owns its compiler/eval state. The host wraps any return with
`Promise.resolve`, awaits it, deep-forces the resolved ordinary value, and only
then applies Malli output validation. This prevents a Promise, lazy Datahike
value, or host-native owner from leaking into agent output.

The existing operation-capture AsyncLocalStorage is a useful seam. Bun's
`node:async_hooks` module is implemented through JavaScriptCore async-context
snapshot/restore, so the module specifier is not evidence of a slow Node
adapter. Retain it until measurement identifies a real cost.

### Agent-facing toolkit

Files:

- `src/my/blob.cljs`
- `src/my/canvas.cljs`
- `src/my/data.cljs`
- `src/my/kb.cljs`
- `src/my/kb/shared.cljs`
- `src/my/ns.cljs`
- `src/my/plan.cljs`
- `src/my/plan/internal.cljs`
- `src/my/skills.cljs`

These functions should keep namespaced maps and ordinary values. Their public
database reads become awaitable. The self-host compiler already knows how to
await `^:async` functions; a compatibility macro that blocks or unwraps a
Promise would hide the real contract and must not be added.

### Runtime, configuration, AI, and utilities

Files:

- `src/seon/client.cljs`
- `src/seon/config.cljs`
- `src/seon/state.cljs`
- `src/seon/db/browser.cljs`
- `src/seon/db/process.cljs`
- `src/seon/db/restore.cljc`
- `src/seon/db/restore/schema.cljc`
- `src/seon/runtime/admission.cljs`
- `src/seon/runtime/recovery.cljs`
- `src/seon/ai.cljs`
- `src/seon/ai/typeahead.cljs`
- `src/seon/diffusion/retrieval.cljs`
- `src/seon/embed.cljs`
- `src/seon/handlers/message.cljs`
- `src/seon/warn.cljs`

KNN and embeddings are already protocol operations conceptually. Embedding
production remains asynchronous and must never gate ordinary database reads or
commits. A query may observe the currently committed embedding facts; later
embedding commits advance the coordinate and interested consumers can refresh.
`seon.embed` should call the public `seon.db` KNN operation, not
`seon.db.replica/knn-search!`.

## Selected public seam

### One process-owned session

`seon.client` owns session startup, capabilities, database acquire/release, and
reconnection. `seon.db` owns the application API and maps namespaced inputs to
protocol constructors. Callers do not import the UDS transport.

A request has one database identity, one operation, an optional exact
coordinate, one deadline, and one request identifier assigned by the session.
The identifier is transport correlation only; it is not a new application
identity. The existing database coordinate remains the name for an immutable
database value.

### Honest async functions

Keep or add these public concepts in `seon.db` where their result is ordinary
data:

- `query` and `query-with-evidence`
- `pull` and `pull-many`
- eager `entity` convenience implemented as an explicit pull
- `installed-schema`
- `index-page`
- `execute-many`
- `transact!`
- `listen!` and `unlisten!`
- resolve or read the current database coordinate
- KNN search

Remove these shapes rather than emulating them:

- explicit Datahike database or connection positional inputs
- `db/*conn*` as a dereferenceable local connection
- `entity-lazy`
- temporal database handles returned by `history`, `as-of`, `since`, or
  `at-coordinate`
- raw lazy index sequences
- captured-read replay and `read-observation-changed?`
- raw Datahike transaction reports outside the writer

Earlier and historical reads remain features, but the input is an exact
coordinate or explicit temporal operation and the output is materialized
ordinary data. The client never receives a database handle.

### Coordinate scope

Core code always passes the explicit coordinate obtained for the logical
operation. Agent-authored code should run inside the existing asynchronous
operation scope, extended with the current exact database coordinate. A
successful write advances that scope to the returned commit coordinate, so a
later read in the same authored evaluation observes its own write. An explicit
coordinate option overrides the scoped value for reproducible reads.

There must not be a process-global “latest known coordinate.” Selective
interests intentionally do not report every commit, so such a value would be
silently stale. Outside an execution scope, a caller either supplies a
coordinate or `seon.db` resolves head, paying the visible round trip.

This is the recommended balance between exactness and agent ergonomics. Sean
should retain the option to require explicit coordinates for every authored
read; that is stricter and easier to reason about, but substantially noisier in
the agent-facing toolkit.

### Promise containment

Promises exist at outer orchestration boundaries only:

1. An async owner awaits `seon.db`.
2. The resolved value is checked for a `:seon/error` value.
3. Ordinary data is passed into pure ClojureScript.
4. A completed ordinary result is recorded, rendered, or returned.

Do not thread Promises through render functions, store them in database data,
or validate them as if they were completed values. Cancellations, deadlines,
session closure, and writer rejection all resolve to the existing namespaced
error shape at the agent boundary; none throws into the agent loop.

## Datastar interests and direct updates

The web process should own one logical database interest representing the
union of dependencies of all active shared view units. Browser connections do
not each subscribe to the database. A committed event provides a coordinate;
the web owner refreshes only affected units at that coordinate and shares the
result with all equivalent consumers.

The protocol currently accepts either one query form or 1–64 ORed datom
patterns. Before choosing an implementation, measure the union of active
attributes in representative root, agent, data, and debug views:

- If it is at most 64 patterns, use the existing datom-pattern interest.
- If it exceeds 64, compare one query-derived dependency interest with a small
  bounded set of session-owned interests. Do not silently fall back to the
  full committed feed.
- Only extend the protocol with a bounded attribute-set interest if the real
  measurement proves both existing choices materially worse.

Interest replacement must not create a gap. Register the replacement and
receive its acknowledgement coordinate before removing the old interest;
coalesce overlap by database coordinate, then unlisten the old identifier.
Reconnect resolves current head, reinstalls the current dependency union, and
refreshes active units once. It does not replay an application-maintained
transaction log.

## Cancellation, failure, and resilience

- Each request has an existing request identifier, deadline, and optional
  cancel frame. A timed-out caller stops caring; late responses are discarded.
- Disconnect fails every pending request once. Reconnect establishes a new
  session generation so a late frame from an old connection cannot complete
  new work.
- A crashed authored child loses only its session and in-flight work. It does
  not take down the pod, writer, or other children.
- A crashed pod loses browser connections and process-local render caches, not
  database truth. The supervising process can restart it against the same
  writer and database.
- A writer restart rejects or closes in-flight work; clients reconnect,
  reacquire the database, resolve head, reinstall interests, and recompute
  active projections.
- View completion is fenced by consumer existence, renderer identity, database
  coordinate, and session generation. Old async work cannot overwrite a newer
  render.

No tombstone registry or waiter identity is required. Pending request IDs and
ordinary registry membership already express whether anyone still cares.
When the last browser consumer leaves a shared view unit, remove the unit and
cancel its in-flight request when no other unit shares that request. Datahike's
bounded cache owns eviction of shared query indexes; client reachability does
not need to micromanage JVM cache entries.

## Ordered replacement

### 1. Freeze the settled authority and session

Do not change protocol vocabulary while migrating consumers. Prove the direct
session against the current writer: capabilities, multiplexed reads,
execute-many ordering and aggregate bound, deadline/cancel, interest event,
close, and reconnect. The already-green focused protocol/session/writer tests
are the entrance evidence.

### 2. Rewrite `seon.db` in place

Replace local Datahike and replica implementations with protocol-backed async
functions. Add the exact-coordinate execution scope to the existing operation
capture owner. Convert Datahike-specific results into namespaced ordinary data
at the writer/protocol boundary, not in each consumer.

Do not temporarily add a new public namespace. During this checkpoint only
source-level and focused API tests are expected to be coherent; the whole pod
may remain unbuildable until all synchronous consumers are migrated.

### 3. Migrate route projection first

Make route acquisition async at an explicit coordinate, keep
`projection->routes` pure, await the initial projection during startup, and
replace the local listener with one direct interest. A single route query need
not be wrapped in `execute-many`; batching is for multiple independent members,
not a ritual.

This slice proves startup ordering, committed-event refresh, reconnect, and
async/pure separation with a small behavioral surface.

### 4. Migrate turn and context acquisition

Define the pre-provider prompt acquisition beside `run-turn!`. Use
`execute-many` for independent root-agent, namespace, message, plan, warning,
and context projections at one coordinate. Convert nested context functions to
pure inputs cohort by cohort. Keep dependent database work as a better query or
a named second acquisition.

Do not acquire or retain database resources across the provider request. Use
the successful transaction coordinate for the final turn pull.

### 5. Migrate render and shared view units

Convert system view, agent view, surface catalog, selected surface, debug, and
data projections into async acquisition plus pure render slices. Change the
view-unit registry to select work and accept fenced completions only. Let the
web owner group independent acquisitions by database and coordinate.

Preserve equivalent-consumer sharing, input equality checks, serialized output
equality, latest-pending delivery, and gzip configurability. Compression is an
HTTP delivery concern after ordinary bytes exist; it must not change database
acquisition or render identity.

### 6. Migrate authored functions into owning Bun children

Expose the async `seon.db` functions in the self-host environment. Run each
authored renderer in its owning isolated child, await it there, validate the
resolved result, and return ordinary render data. Advance the child execution
scope after successful writes.

Child activation is a measured policy: eagerly keep already-active agent
children warm, but do not require every browser page open to spawn a dormant
agent child unless its authored renderer is actually selected.

### 7. Migrate remaining consumers by cohort

Move agent toolkit, eval/autocomplete, lifecycle/recovery, AI, browser data,
schema, configuration, and embedding/KNN call sites. Remove explicit database
arguments and replace lazy entity traversal with bounded pull shapes. Keep
each cohort source-coherent and run its narrow tests before proceeding.

### 8. Delete the superseded mechanism atomically

After the last consumer is async, delete the local replica, replay publisher,
dual-socket launch configuration, local Datahike CLJS dependency path, captured
read machinery, and tests that assert replica implementation details. Update
remaining tests to assert coordinates, values, interests, cancellation, and
reconnect behavior through `seon.db`.

At this boundary, also replace the web Node-to-Ring adapter with native
`Bun.serve` and `node:child_process` call sites with `Bun.spawn` where their
separate owners are ready. Do not remove `node:fs`, `node:crypto`, or
`node:async_hooks` merely because their specifier contains `node:`; Bun
implements those compatibility surfaces directly, and each replacement needs
a measured advantage.

## Atomic deletion set

The final deletion checkpoint includes:

- `src/seon/db/replica.cljs`
- replica-only tests and fixtures
- replay request/response protocol constructors and schemas
- writer replay paging and dispatch
- writer publication `d/listen!` full-feed path
- the JVM publisher half of the old UDS transport
- dual request/publisher socket fields in launch/config/process code
- `capture-reads`, read replay, and `read-observation-changed?`
- local Datahike CLJS calls and the ambient dereferenceable `db/*conn*`
- lazy entity and temporal-database-handle public functions
- replica attach/status/detach calls in `seon.client`
- `replica/knn-search!` in `seon.embed`
- replica imports in web startup and all tests
- Node HTTP-to-Ring and zlib adapter code once `Bun.serve` owns the web server

Before deletion, an exact `rg` inventory should become the gate. After
deletion, production source must have zero imports of `seon.db.replica`, zero
calls to removed UDS publisher/RPC functions, zero direct `datahike.api` calls
outside `src/seon/db/`, and zero Node HTTP/child-process adapter imports in the
owners replaced by native Bun APIs.

## Falsifiers and performance evidence

### Direct facade

- From one child, issue concurrent query, pull, and schema calls at the same
  coordinate. Responses may arrive out of order and must correlate correctly.
- Repeat equivalent queries from multiple children at the same coordinate.
  Datahike evidence must show one shared computation or cache hit rather than
  one index build per child.
- Timeout one request while another succeeds on the same session. Capacity and
  later correlation must remain intact.
- Assert recursively that no result contains a Datahike database/entity/lazy
  value, Promise, Bun socket, or JVM object wrapper.

### Route

- A route-changing commit produces one refresh at the event coordinate.
- An unrelated commit produces no route refresh.
- Disconnect during interest replacement, reconnect, and prove the resulting
  route projection equals current head without replay.

### Turn and context

- Commit unrelated data between members of a prompt acquisition. Every member
  must still report the same requested coordinate.
- Delay the provider and inspect JVM acquisition/resource evidence. No query
  lease or admitted resource remains held during the delay.
- Perform a write in a turn and prove the final pull uses the returned commit
  coordinate, not the pre-provider coordinate.
- Compare pure context output with a frozen fixture from the pre-deletion
  implementation for representative root, task, warning, plan, and transcript
  states.

### Datastar

- Connect 100 equivalent browser consumers. One coordinate change causes one
  acquisition, one render, and one serialization for the shared view unit.
- Change ten independent active units at one coordinate. Measure grouped
  execute-many latency and aggregate size against ten independent multiplexed
  requests; select grouping from evidence rather than assuming one giant batch.
- Complete an old slow render after a newer coordinate. The old completion
  must be ignored.
- Remove the last consumer during acquisition. The request is cancelled when
  no other unit shares it, and the completion cannot recreate the unit.
- With gzip on and off, database request count and render count remain equal;
  only transmitted bytes and compression CPU differ.

### Authored child

- A synchronous authored function, an async authored function, and a function
  that writes then reads all return fully resolved ordinary values.
- A child deadline, explicit cancel, malformed result, and process crash each
  become a bounded error value and do not affect siblings or the pod.
- Two child processes run CPU work and database requests concurrently; wall
  time demonstrates real process parallelism rather than one JS event-loop
  queue.

### Final system

- Cold Bun bundle and startup succeed with no Node runtime.
- Focused CLJS, writer, and operator gates pass from one frozen source digest.
- Default cluster root, agent, data, and debug views update through direct
  interests.
- A pod restart and writer restart recover current projections without replay.
- Memory and latency comparison records pod RSS, JVM RSS, child RSS, turn
  prompt acquisition p50/p95/p99, shared view refresh p50/p95/p99, request
  concurrency, query cache hits, and bytes per SSE consumer.

## Source-freeze checkpoints

Each checkpoint freezes every path in the artifact digest before its proof.
No source-editing lane changes those paths until the checkpoint ends.

1. **Session and authority:** protocol, writer, Datahike fork, Bun UDS session,
   and their focused tests.
2. **Async facade and route:** `seon.db`, client session ownership, route,
   router, interest handling, and focused CLJS tests.
3. **Turn and context:** turn, context namespaces, eval execution scope, and
   prompt/context tests.
4. **Web and authored rendering:** render namespaces, view units, Datastar,
   child execution, native web owner, and browser/server-side SSE proof.
5. **Atomic deletion and graduation:** all production build inputs, tests,
   launch configuration, dependencies, and documentation; run the full Bun
   build and live default-cluster proof once.

Because the checkout is already between transport generations, checkpoints
2–4 may prove coherent units without claiming the entire pod starts. Do not
restart lifecycle processes merely to rediscover the known cold-bundle failure.
Checkpoint 5 is the first whole-system claim and must use one frozen digest.

## Decisions to keep with Sean

### Authored coordinate ergonomics

Recommended: an exact coordinate in the existing async execution scope, with
an explicit override and a visible resolve-head cost outside a scope. Option:
require every authored call to pass a coordinate. The latter is maximally
explicit but increases agent-facing code and cognitive overhead.

### Execute-many grouping

Recommended: group the stable, independent reads for one logical core view and
keep large or optional members separate when measurement shows head-of-line or
aggregate-size cost. Option: one maximal batch per view. A maximal batch uses
fewer frames but delays all members behind the slowest and can waste large
optional results.

### Datastar dependency union beyond 64 patterns

Recommended: measure first, then choose one query-derived interest or a small
bounded number of existing interests. Option: extend the protocol with a
bounded attribute-set interest if both existing forms lose materially. A full
feed is not an acceptable convenience fallback.

### Dormant authored-render children

Recommended: keep active agents warm and spawn a dormant child only when its
authored renderer or agent work is selected. Option: eagerly maintain every
cluster child for the lowest first-render latency. The choice trades modest
idle memory for cold-start latency and should use measured child RSS/startup.

### Native Bun module replacement

Recommended: use `Bun.connect`, `Bun.serve`, and `Bun.spawn` where they expose
stronger lifecycle, backpressure, or streaming control. Retain Bun's direct
implementations of compatible `node:fs`, `node:crypto`, and
`node:async_hooks` until profiling justifies change. A source-string purge of
all `node:` imports would spend complexity without proving performance.

## Graduation condition

The consumer replacement is complete when every database operation in the pod
or authored child goes through the asynchronous `seon.db` protocol API at an
explicit or execution-scoped exact coordinate; core views acquire once and
compute purely; equivalent Datastar consumers share acquisition/render/output;
independent Bun children execute concurrently; reconnect recomputes current
state from selective interests; and the local replica, replay feed, lazy
Datahike values, dual socket, and replaced Node adapters no longer exist.

That design centralizes database truth without centralizing application work.
The JVM owns durable Datahike resources, transaction serialization, and shared
query/index computation. Bun processes own agent execution, rendering, web
delivery, cancellation, and failure isolation. The protocol between them is
small enough that a future non-JVM authority can implement it without changing
agent or web code.
