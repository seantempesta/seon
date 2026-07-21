---
type: research
status: complete
tags: [research, database, flow, pod, web]
---

# Datastar, Bun, and database-authority interface audit

## Question and dependency ledger

This audit asks for the smallest native interface joining Seon's sole JVM
Datahike authority, Bun UI host and agent children, and Datastar browser feed.
It does not propose a second cache, event bus, renderer, or database replica.

The exact checked-out dependencies are:

- Datastar `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` at
  `reference-code/datastar/`;
- Datastar Clojure `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` at
  `reference-code/datastar-clojure/`;
- Datahike `a464cd887458d2572414a6ea951c477b0981fdae` at
  `reference-code/datahike/`; and
- Bun `be77b652884b16a103cfaa4af3c1102f72f2dcd3` at
  `reference-code/bun/`.

The first-party mechanisms are the normalized feed registry, coalescer, and
database interest in `src/seon/web/datastar.cljs`; child-owned page projection
in `src/seon/execution/runtime.cljs`; the asynchronous `seon.db` facade in
`src/seon/db.cljs`; selective committed-report interests in
`src/seon/db/writer.clj`; and the one render engine in `src/seon/render.cljs`.

The shortest falsifier for a proposed custom Datastar server layer is this:
does Datastar define a database-aware server lifecycle, render cache, or
subscription protocol that Seon should implement? It does not. Its native
server seam is an HTTP response carrying ordered SSE events; its meaningful
application operation is `datastar-patch-elements`. A custom Bun adapter is
useful only as the transport implementation of that seam. Moving database
interest, query dependency, or render scheduling into a Datastar adapter would
create a second reactive system.

## Finding: the native Datastar seam is the SSE event protocol

Datastar's language-neutral SDK ADR requires a `ServerSentEventGenerator`
constructed from request and response objects, with immediate SSE headers and
ordered writes (`reference-code/datastar/sdk/ADR.md:36-68`). The only relevant
application primitive is `PatchElements`: complete HTML elements plus optional
selector, mode, namespace, view-transition, event id, and retry duration
(`ADR.md:103-117`, `194-248`). Datastar explicitly distinguishes complete
elements from arbitrary fragments (`ADR.md:199-211`).

The browser watcher confirms the actual boundary. It parses one
`datastar-patch-elements` event, defaults to `outer`, and parses the received
HTML once (`reference-code/datastar/library/src/plugins/watchers/patchElements.ts:39-75`,
`79-123`). With no selector, each top-level element is matched by ID
(`patchElements.ts:125-145`). `outer` and `inner` invoke the same morph engine;
the other modes perform direct DOM operations (`patchElements.ts:193-219`).
The morph algorithm uses persistent IDs to preserve matching DOM nodes
(`patchElements.ts:222-297`).

Therefore the closest and best custom interface is not a Datastar fork or a
new Clojure SDK. It is one small Bun HTTP response writer that accepts already
serialized Datastar event bytes from the existing feed owner. Seon should keep
its event encoder as a pure function, verify it against Datastar's SDK golden
cases, and let `Bun.serve` own request, response, cancellation, and stream flow
control. Datastar Clojure is useful as executable protocol evidence, especially
`reference-code/datastar-clojure/src/dev/examples/tiny_gzip.clj`; importing its
Ring and JVM adapter hierarchy into the Bun pod would add an irrelevant host
abstraction.

The existing first-party shape is already close: `patch-elements*` emits the
minimal default-outer event (`src/seon/web/datastar.cljs:103-117`), normalized
subscriptions share one render and serialized event (`431-494`), and one event
fans out to all equivalent sockets (`496-504`). The desired cut replaces only
the Node response/gzip ownership around `prepare-feed` and `open-feed!`, not the
database interest, subscription, coalescing, render, or Datastar event model.

## Bun flow control: what is automatic and what is not

There are three distinct Bun APIs and they must not be conflated.

### `Bun.serve` response bodies

For an ordinary `ReadableStream` or async-generator `Response` body, Bun pauses
the producer while the HTTP destination is backed up. This is explicitly
automatic (`reference-code/bun/docs/runtime/streams.mdx:95-125`). This is the
best default for Seon's long-lived SSE response because the transport can
request the next item only when writable.

A Bun direct `ReadableStream` avoids the ordinary stream's queue and copy, but
flow control becomes explicit. `controller.write` accepts the chunk and returns
a negative number when the destination is backed up; the producer must then
await `controller.flush(true)` before another write
(`streams.mdx:43-95`). A direct stream is therefore a measured optimization,
not the initial correctness seam.

Automatic transport pressure does **not** define application retention. Even
with an ordinary response stream, Seon must bound how much newer derived state
it retains while one browser is slow. The correct application rule remains
latest complete event per socket: a complete `outer` morph supersedes every
undelivered older morph for the same demanded view. Heartbeats never displace a
pending application event. The current Node implementation expresses that
policy at `src/seon/web/datastar.cljs:189-250`.

### `Bun.connect` and `Bun.listen` sockets

Low-level TCP/Unix-domain sockets do not provide automatic producer flow
control. `Socket.write` returns a byte count, sockets do not combine small
writes, and a partial write must retain the unwritten suffix until `drain`.
Bun's own documentation shows the partial-suffix rule and says explicitly that
backpressure must be managed with the drain handler
(`reference-code/bun/docs/runtime/networking/tcp.mdx:189-239`; type signature
at `reference-code/bun/packages/bun-types/bun.d.ts:5785-5811`). This is the
authority-session behavior, not the browser SSE behavior.

The socket `data` handler is a synchronous callback. It cannot be treated as
awaiting an asynchronous consumer; framing must either consume the supplied
chunk synchronously or retain the unconsumed suffix in bounded session state.
The existing persistent authority session and its bounded request/event queues
remain the owner of this rule.

### Server WebSockets

Server WebSocket `send` similarly reports backpressure and has a `drain`
callback and configurable pressure limit. It offers no benefit for Datastar's
native SSE protocol and would add a second browser channel. It should not be
introduced.

## Database commits, query dependencies, and minimal recomputation

The authority already contains the required computation graph; it must be
composed, not copied.

Datahike derives conservative query attribute dependencies from Datalog data
patterns and pull expressions. Variable attributes, rules, wildcard pulls, and
unknown forms correctly widen to `:all`
(`reference-code/datahike/src/datahike/query.cljc:2626-2721`). Cached query
entries retain these dependencies. After durable publication, the child
database value structurally shares the parent's result-cache bucket minus only
entries whose dependencies overlap the transaction's modified attributes
(`query.cljc:2413-2437`, `2766-2797`). The cache is keyed by exact committed
connection, generation, and commit identity, including composite database
sources (`query.cljc:2424-2437`, `2524-2555`, `2611-2624`). Concurrent identical
misses join the existing single-flight owner, while a completed hit bypasses
flight allocation (`query.cljc:4329-4435`).

The writer already turns a query interest into the same dependency analysis
(`src/seon/db/writer.clj:2143-2156`), indexes interests by attribute
(`2075-2112`), selects only candidate interests touched by committed datoms
(`2315-2347`), and sends the native transaction report with `db-after`. This is
the wakeup mechanism. The query cache is not and should not become an event
bus: it answers repeated reads cheaply and propagates known-unchanged results;
the committed transaction report says when a derivation might need work.

The minimal recomputation pipeline is therefore:

1. The Bun UI host owns one query interest for the union of dependencies of
   all currently demanded live views in one database.
2. A commit event supplies exact `db-after`, `tx-data`, and changed attributes.
3. The feed owner ignores a demanded view whose dependency set does not
   intersect the changed attributes and advances that view's retained complete
   event to the new database value without recomputing it.
4. Affected demanded views coalesce commits and render once at the newest exact
   database value.
5. Their bounded `execute-many` reads hit propagated Datahike results for every
   query proven unchanged; identical misses across children or the UI host join
   at the authority.
6. Equal serialized complete elements are suppressed, then the remaining event
   is fanned out once to equivalent browser sockets.

Steps 1-4 and 6 are substantially present in
`src/seon/web/datastar.cljs:278-345`, `496-604`, and `626-741`. The current
interest uses a synthetic count query solely to communicate a dependency union
(`640-647`). That is acceptable with the present protocol but unnecessarily
indirect. The simplest protocol improvement is for `listen!` to accept the
existing concrete dependency set directly as an alternative to a query form;
the writer already represents and indexes that exact set. This removes query
fabrication without changing matching or delivery semantics. It is a protocol
clarification, not a new subscription abstraction.

The larger current weakness is dependency evidence quality. The child page
projection hard-codes broad fixed attributes and unions agent-declared surface
read attributes (`src/seon/execution/runtime.cljs:316-331`, `496-503`). Its
initial `execute-many` ignores the dependency evidence Datahike can return.
`seon.db/query-with-evidence` already exposes it
(`src/seon/db.cljs:849-859`). `execute-many` member results should retain each
query's existing `:datahike.query/attribute-dependencies`, and the page
projection should union those native sets with the dependencies of non-query
pull/index reads and each authored surface's declared `:seon.fn/read-attrs`.
Unknown or dynamic access remains `:all`. This replaces avoidable hand-copied
query attributes; it must not pretend arbitrary agent code can be inferred.

## Sharing across agents and sockets without another cache

There are two legitimate sharing owners:

- Datahike shares query results and in-flight query work across every process
  that asks the authority for the same query, arguments, and committed database
  value.
- The Bun UI host shares one completed render and serialized Datastar event
  across equivalent browser demands. The current normalized subscription key
  already does this for identical agent feeds.

There should be no cluster-wide rendered-result cache and no render event bus.
Different agent pages usually have different arguments, surfaces, and access
scope, so sharing a whole render is incorrect. They nevertheless share every
identical underlying query automatically at the authority. A common system
surface can share more only if its demanded render has the same function,
arguments, configuration/program identity, and database value; in that case
the UI host may normalize those consumers to the same existing render
subscription, just as it already normalizes sockets. This is ephemeral
in-flight plus last-complete output ownership required for fanout, not a second
general cache.

Agent children never own browser sockets. An authored renderer runs in its
agent child, returns ordinary hiccup plus read dependencies, and the UI host
serializes and streams it. Other agents benefit from the authority's cache,
not by receiving that child's private render. Cross-agent collaboration remains
database facts and refs.

## Whole morphs, demanded surfaces, and selective patches

Datastar's default `outer` patch is the correct baseline. A complete element is
replay-free, reconnect-safe, naturally latest-wins, and lets Datastar preserve
stable descendants by ID. Sending a server-computed tree edit would duplicate
the browser morph algorithm and add retained DOM state on the server.

One whole `#app-view` morph is not always the right complete element. Seon
already has independently identified surfaces. The ideal demand unit is the
smallest independently renderable, stable-ID surface whose database reads and
renderer can be acquired without first materializing the entire page. A single
Datastar event may carry several complete top-level elements by ID
(`reference-code/datastar/sdk/ADR.md:149-157`), so all dirty demanded surfaces
from one coalesced database value can still be one ordered event and one flush.

Adopt independent demanded surfaces only after the page projection is split as
one pure page-structure acquisition plus bounded surface acquisitions. Do not
slice serialized HTML after whole-page rendering: that saves browser bytes but
not database work, renderer work, or serialization, and creates a second DOM
parser on the server. Structural attributes that add, remove, reorder, or
retarget surfaces must send the complete `#app-view`; a non-structural change
may send only the affected complete surface elements. Reconnect always sends a
complete `#app-view` before incremental surface events.

Do not go below complete surface elements unless measurement proves a large
hot surface dominates. A finer child element must have its own stable ID,
independent pure renderer, explicit read dependencies, and reconnect inclusion.
Otherwise Datastar's browser morph is already the selective patch algorithm.

The decision threshold should be measured, not guessed:

- retain whole-view morphing while p95 render plus serialization is below one
  16 ms frame, p95 gzip event bytes are below 64 KiB, and browser morph p95 is
  below 8 ms under the representative busiest page;
- graduate to independently demanded surfaces when one non-structural change
  repeatedly causes more than 4x unaffected render/serialization work or more
  than 4x bytes, and the split reduces end-to-end p95 by at least 25% without
  increasing retained UI-host bytes by more than 10%; and
- consider a finer complete element only when one surface alone exceeds those
  thresholds and a stable independently derived child accounts for at least
  75% of its changed cost.

These are falsifiable admission thresholds, not permanent constants. Record
whole view, dirty surfaces, query cache outcomes, child render CPU, serialization
CPU, gzip bytes, time to first patch, browser morph duration, event-loop lag,
and retained bytes per socket.

## Failure, reconnect, and multiple databases

A browser reconnect requires no transaction replay. It opens a new demand and
receives one complete render at the UI host's current immutable database value;
later events are complete superseding elements. Datastar's fetch parser honors
SSE event IDs and retry fields (`reference-code/datastar/library/src/plugins/actions/fetch.ts:358-419`),
but Seon does not need `Last-Event-ID` replay for derived views. Replay would
retain a second history already available as Datahike database values.

On a slow browser, retain at most the in-flight chunk plus the newest complete
event for its demand; close the stream on a configured hard byte/time bound.
On cancellation, cancel the response body, detach the socket, and remove the
normalized subscription only after its last consumer. On renderer failure,
send the existing complete visible error element and keep the interest alive.

On authority-session disconnect, stop admitting new renders, terminate the
affected HTTP streams, reconnect and reacquire the selected named database,
restore the union interest, then repaint every live demand completely. Do not
render against an older cached database value after loss. A report gap has the
same consequence: reacquire and complete repaint, not guessed incremental
delivery.

One UI host may manage several cluster databases, but all state is keyed first
by the selected database identity. It owns one acquired database value and one
union interest per active database, and never unions dependencies or normalized
render keys across databases. Datahike query cache entries already include the
connection generation and commit identity. A browser route must select its
cluster/database before opening the feed. A multi-database query is an explicit
query with multiple database sources executed by the authority; it is not a
render-side join of results from independently advancing values. This preserves
one immutable database-value fence for each source and lets the authority's
composite cache key remain correct.

## Recommended interface

Keep one application-facing feed operation:

```clojure
{:seon.web.feed/key semantic-demand
 :seon.web.feed/database database-value
 :seon.web.feed/live? true
 :seon.web.feed/render render-function
 :seon.web.feed/dependencies #{:qualified/attribute}}

```

This is the existing feed definition strengthened, not a public framework.
`render-function` returns complete stable-ID hiccup, or a vector of complete
stable-ID elements for independently demanded dirty surfaces, plus the exact
dependency union learned from native query evidence, non-query database reads,
and authored `:seon.fn/read-attrs`. The feed owner normalizes equal demands,
coalesces committed transaction reports, renders at exact `db-after`, suppresses
equal serialized output, and publishes one already encoded Datastar event.

The Bun transport interface is smaller:

```text
open SSE response -> demand consumer -> next encoded event or close

```

Use an ordinary `ReadableStream` or async-generator `Response` first so Bun
owns HTTP backpressure automatically. The demand consumer retains only the
newest complete pending event. Benchmark a direct `ReadableStream` later; if it
wins materially, honor negative `write` and await `flush(true)`. Keep gzip only
if real browser measurements show it improves end-to-end time and CPU at the
selected event sizes; use Bun's response compression facility if it provides
streaming flush semantics, otherwise keep one measured compression transform.

The authority interest request should directly accept one of:

- a Datalog query form, from which Datahike derives dependencies;
- a concrete attribute dependency set already produced by Datahike/read
  composition; or
- exact datom patterns.

These are three selectors for the existing database interest, not three feed
systems. A replaced listener key atomically replaces its prior selector. The
event remains a native committed transaction report projection with exact
`db-after` and matching `tx-data`.

## Alternatives rejected

- **Datastar SDK object graph in CLJS:** adds host abstractions but no useful
  database semantics. Retain golden protocol fixtures and a pure encoder.
- **WebSocket browser transport:** non-native to Datastar's core SSE flow,
  duplicates reconnection and pressure behavior, and provides no derivation
  advantage.
- **Query cache notifications:** conflates computation reuse with commit
  delivery. Keep transaction interests as wakeups and the cache as computation.
- **Per-agent event bus or render cache:** duplicates database coordination and
  risks cross-agent/config leakage. Share identical queries at the authority
  and identical demanded renders only in the UI host.
- **Server-side DOM diff:** duplicates Datastar's ID-aware morph and requires
  retained DOM state. Send complete stable-ID elements.
- **Always surface-level patches immediately:** premature while current page
  acquisition and renderer execution are whole-page. First measure and split
  the derivation boundary; never parse the completed HTML to simulate savings.
- **One all-attributes database interest forever:** correct but makes unrelated
  commits wake and scan the UI. Retain widening to `:all` only for genuinely
  dynamic/unknown reads.

## Implementation order and deletion opportunities

1. Complete the current correctness recovery: Promise-free render output,
   maintained tests, live whole-view feed, restart, and child recovery. The
   optimization seam cannot be benchmarked while the feed is semantically red.
2. Preserve query dependency evidence through `execute-many` and derive page
   dependencies from native evidence plus pull/index read declarations and
   authored surface read attributes. Delete hand-copied fixed query attributes
   that become redundant; unknown access remains `:all`.
3. Let the protocol accept concrete dependency sets for the existing interest.
   Delete `dependencies-query` and `all-datoms-query` from
   `seon.web.datastar`; retain one writer interest index and delivery path.
4. Add counters and timings at the existing owners: interest candidates and
   deliveries, coalesced commits, affected views, Datahike cache evidence,
   joined flights, child render time, serialized bytes, suppressed events,
   per-socket pending bytes, and reconnect paints.
5. Implement native `Bun.serve` using one ordinary response stream per browser
   over the retained normalized subscription/feed registry. Delete Node
   `req`/`res`, `zlib`, `writableEnded`, `drain` event, and Ring hijack ownership
   only when Bun response parity is proven. Do not fork routes or feeds.
6. Run the whole-view benchmark matrix. If it crosses the stated thresholds,
   split page structure from independently demanded surface acquisitions and
   send all dirty complete surfaces in one Datastar event. Keep complete
   `#app-view` for first paint, reconnect, and structural changes.
7. Benchmark ordinary versus direct Bun response streams, and gzip versus
   uncompressed events, under 1/8/32 sockets including deliberately slow
   consumers. Select the simplest winner that meets latency and memory bounds.
8. Prove one host over two independent named databases, authority reconnect,
   report-gap resynchronization, child crash during a render, listener
   replacement, last-consumer release, and no retained subscription after
   disconnect.

The important deletions are the fabricated dependency query, eventually the
Node HTTP/gzip/drain adapter, redundant fixed query-attribute lists, and any
whole-page-only acquisition once independently demanded surfaces prove a
measured win. Datastar's browser morph, Datahike's query cache/single-flight,
the writer's committed-report interest, the child renderer, and the normalized
feed registry are the retained mechanisms.

## Graduation benchmarks

Run from one frozen release artifact and record p50/p95/p99 plus CPU,
allocations, RSS, and event-loop lag for:

- one unrelated commit against 1,000 demanded-view dependency sets: zero
  renders and zero events for unaffected views;
- 1/8/32 equivalent sockets: one query/render/serialization and N streamed
  consumers, with bounded latest-event retention for one slow consumer;
- 32 distinct agent views whose shared header query is identical: one miss
  owner, joined misses or propagated hits, without a Bun-side query cache;
- bursts of 1/10/100 commits: one render at newest `db-after` within the
  existing 500 ms hard coalescing bound;
- whole view versus dirty surfaces on representative transcript, canvas, root,
  debug, and data pages;
- ordinary versus direct `ReadableStream`, gzip versus plain, with event sizes
  4/16/64/256 KiB and a slow consumer;
- authority disconnect and report gap: no stale render after loss, bounded
  reconnect, one complete repaint, and restored selective interest;
- two cluster databases with overlapping agent IDs: no subscription, database
  value, cached result, or event crosses database identity; and
- first paint/reconnect: complete `#app-view` arrives before any surface-only
  event and yields no Datastar missing-target warning.

Success is not merely fewer bytes. It is one understandable path in which a
committed transaction report selects demanded derivations, Datahike reuses or
computes their queries once, a child renders the necessary complete elements,
the UI host shares serialized output across equivalent sockets, and Bun applies
bounded transport flow control.
