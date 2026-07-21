---
type: research
status: active
tags: [research, prd, web, flow]
---

# Bun serve and Datastar internals — 2026-07-16

## Decision

Replace the Node HTTP owner with `Bun.serve`, but preserve Seon's one Datastar
model: one database-derived render per equivalent view, one complete SSE event,
and latest-state-wins delivery per browser connection. The native feed body is a
`ReadableStream` with `type: "direct"`; its controller is the only socket-facing
value. Reitit continues to select ordinary ClojureScript handlers, but handlers
return ordinary response data or a Bun `Response` at the final host boundary.

Do not retain the Node response hijack sentinel, `node:http` request/response
objects, or one Node `zlib` transform piped into every response. They are one
adapter layer around the exact ownership that `Bun.serve` already provides.
Start native loopback SSE without compression. Add remote compression as an
explicit measured policy after the native path is correct; Bun's selected
server source still says built-in response compression is unsupported.

This is not a WebSocket migration. Datastar consumes HTTP SSE, reconnects with
ordinary GET semantics, and morphs complete elements. Bun's WebSocket topics
and publish API would introduce a second browser protocol without removing the
database render or Datastar event work.

## Dependency ledger

- Seon checkout `d9205b2b02d320352489b89c3b383411f6a2779a`:
  `src/seon/web/serve.cljs`, `router.cljs`, `datastar.cljs`, `debug.cljs`, and
  their tests under `test/seon/web/`.
- Bun `be77b652884b16a103cfaa4af3c1102f72f2dcd3`:
  `packages/bun-types/serve.d.ts`, `packages/bun-types/globals.d.ts`,
  `src/runtime/server/RequestContext.rs`, `ServerConfig.rs`, and native stream
  regression tests in
  `test/js/node/http/node-http-nested-cork.test.ts` and
  `test/js/node/stream/node-stream.test.js`.
- Datastar `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` and
  Datastar Clojure `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2`:
  the browser remains the existing Datastar SSE consumer; no client protocol
  change is required.
- Reitit `106fc4c7a09290c8e2df2d4ef9570ea1322ab2ab`:
  retain database-derived route matching and late handler resolution. Replace
  only the Node-specific request/response adapter at its edge.
- The database delivery findings in
  [[transit-bun-delivery-internals-2026-07-16]] apply the same law here:
  create one immutable body per semantic result, then keep connection-local
  progress and byte bounds.

## Current Seon behavior

`datastar.cljs` already performs the expensive semantic work at the right
level. Equivalent live feeds share a database subscription and render. A
transaction coalescer retains the earliest before-value and latest after-value,
dirty render units suppress equal reads and serialized output, and one complete
`datastar-patch-elements` event is offered to every matching connection.

Delivery is still Node-specific:

1. `serve.cljs` starts `node:http` and exposes raw request and response objects.
2. `router.cljs` converts the request to a Ring-shaped map, then injects those
   raw objects under `:seon.http/node-req` and `:seon.http/node-res`.
3. Streaming handlers write the response themselves and return the hijack
   sentinel so Reitit does not write a second response.
4. `datastar.cljs:1144-1200` creates one `zlib.createGzip()` per open feed,
   pipes it to the Node response, and writes every event through that transform.

The existing feed backpressure rule is valuable and must survive. At
`datastar.cljs:355-387`, the first rejected stream write marks the connection
draining; later database updates replace one pending event, and the drain
callback sends only the newest derived state. Tests at
`datastar_test.cljs:2002-2045` pin heartbeat behavior and latest-state-wins
delivery. This bounds pending event count to one, but not bytes: one rendered
event may itself be large, and Node/zlib/socket buffers remain outside that
count.

Compression currently happens once per connection, not once per shared render.
Every event forces `Z_SYNC_FLUSH` so a long-lived gzip response emits promptly.
That is correct for browser visibility, but it repeats compression CPU and
dictionary memory for every observer of the same view. It also makes every feed
opaque during development and forces compression on loopback where saved wire
bytes are usually worth less than CPU and latency.

The HTTP surface is not yet a narrow host seam. There are roughly one hundred
raw request/response or imperative write references across `serve.cljs`,
`router.cljs`, `datastar.cljs`, and `debug.cljs`. A compatibility wrapper around
fake Node objects would preserve that cognitive and allocation overhead.

## Native Bun behavior to use

`Bun.serve` calls one `fetch(Request, Server)` function and accepts a standard
`Response`. A response body may be Bun's direct `ReadableStream`. The controller
contract in `packages/bun-types/globals.d.ts:696-730` is unusually useful for
SSE: `write` returns bytes written, or a negative number when backpressure is
present. A negative result means the chunk was accepted; it must not be written
again. `await controller.flush(true)` resumes only after the destination drains.

This is a stronger and clearer interface than Node's boolean transform write:
Seon can keep exactly one pending latest event, await native drainage, and then
write that latest event. It also exposes a natural point to account bytes per
connection. Bun's `RequestContext.rs:1615-1638` registers the uWebSockets
writable callback once for the streaming response, and the native response sink
resolves the pending flush from that callback. Its stream completion path waits
for a pending transport flush rather than truncating the tail
(`RequestContext.rs:2848-2887`). The vendored regression tests exercise direct
streams across asynchronous yields, explicit flush, native sink completion, and
mixed write bursts.

For long-lived SSE, call `server.timeout(request, 0)`. The public contract says
zero disables the request timeout; the server default is ten seconds. Keep the
existing 15-second SSE comment heartbeat for proxies and connection health,
not as a workaround for Bun's own timeout.

Connection close is already a web primitive: observe `request.signal` and make
the stream's `cancel`/cleanup path release only the connection that owns the
current view. Preserve Seon's existing feed ID ownership check so a late close
from a replaced browser connection cannot remove its replacement.

`Bun.serve` has efficient `Bun.file` response paths and native sendfile on
supported non-TLS hosts. Static CSS and JavaScript should return `new
Response(Bun.file(path))` rather than synchronously reading the complete file
with `node:fs.readFileSync` on every request. Bun's file response owner handles
backpressure, aborts, ranges, and file descriptor lifetime in
`src/runtime/server/FileResponseStream.rs`.

## Compression constraint

The selected Bun server's `RequestContext` contains the explicit marker `TODO:
support builtin compression` at `RequestContext.rs:212`. Its HTTP compression
implementation elsewhere is for client `fetch` request bodies, not automatic
streaming server responses. WebSocket per-message deflate is implemented but is
irrelevant to Datastar SSE.

Therefore the first native feed should omit `Content-Encoding` on loopback. A
remote deployment may select one of two later implementations behind one
configuration value:

- a web `CompressionStream("gzip")` response body, retaining streaming and one
  compressor per connection; or
- compress each complete Datastar event once and share the resulting bytes
  across equivalent connections, if a standards-correct concatenated/member or
  continuously framed gzip experiment proves browsers and intermediaries accept
  it with prompt event delivery.

Do not assume the second form is valid merely because it saves CPU. The current
continuous stream plus `Z_SYNC_FLUSH` has one compressor history per response;
independent gzip members or reset dictionaries change wire behavior. Measure
real Datastar parsing, proxy buffering, compression ratio, time to first morph,
and retained bytes before choosing it. Configuration should distinguish
loopback from remote binding and default loopback to identity encoding.

## Smallest native seam

Keep the existing pure and database-derived owners:

- `router/db->routes`, route facts, late handler resolution, and middleware;
- Datastar render units, subscription normalization, dirty-read evidence,
  coalescing, event construction, and latest-state-wins semantics; and
- the feed registry's view ID and feed ID replacement fence.

Replace the host edge in one coherent cut:

1. Express the incoming Bun `Request` as the existing ordinary Ring request
   fields plus one host-local request value at the final boundary. Same-origin,
   query, form body, and peer checks read Web `Request`/`URL` data.
2. Make ordinary handlers return `{:status :headers :body}` or Promises of that
   data. One final function creates a Bun `Response`. Remove imperative
   `writeHead`/`end` helpers and the hijack sentinel.
3. Let the feed handler return a `Response` whose body is one direct stream.
   Store only its controller/cleanup function as the connection-local host
   owner; all agent-facing and database values remain ordinary namespaced data.
4. On each shared event, offer the same immutable string or encoded byte view to
   each controller. When `write` reports pressure, retain only the newest event
   and its exact byte count, await `flush(true)`, then retry only that pending
   newer event—not the accepted pressured write.
5. Return `Bun.file` for static assets. Stop the server with Bun's graceful
   `server.stop`; feed cleanup remains explicit and idempotent.

This cut removes the adapter rather than layering Bun underneath it. A staged
implementation may first convert handlers to response data while still running
under Node, but the optimized full-control plan should land the response-data
cut and `Bun.serve` together so no compatibility owner survives.

## Performance implications

- Native direct streams remove the Node `ServerResponse` compatibility path,
  one gzip transform and pipe per loopback feed, EventEmitter drain wiring, and
  the hijack/double-write control path.
- Loopback identity encoding removes per-event deflate CPU, `Z_SYNC_FLUSH`
  overhead, compressor state per observer, and browser/server debugging cost.
- Shared render/event construction remains compute-once. The native stream may
  still copy accepted bytes into uWebSockets transport buffers; no zero-copy
  claim is justified until allocation/copy instrumentation proves it.
- Latest-state-wins prevents a slow browser from building an obsolete morph
  queue. Adding byte accounting closes the remaining single-huge-event memory
  hole.
- `Bun.file` avoids synchronous full-file reads and reaches Bun's native
  sendfile path where supported.
- `Bun.serve` is event-loop based. Rendering and database query CPU must remain
  outside its callback or be awaited through the database authority; native
  serving does not make synchronous CLJS rendering parallel.

## Risks and falsifiers

- **Direct-stream pressure semantics:** force a client to stop reading after
  headers, publish three distinct large morphs, and prove the first pressured
  write is not duplicated while only the third pending morph is later sent.
- **Abort/replacement fence:** reconnect the same view repeatedly while old
  requests abort out of order; exactly one current feed remains and final close
  releases the database listener when the registry becomes empty.
- **Timeout:** leave an otherwise idle feed open beyond 10 and 60 seconds with
  `server.timeout(request, 0)` and prove heartbeats and later morphs arrive.
- **Datastar compatibility:** exercise root, agent, historical, debug, and data
  feeds through the shipped browser client; exact event names, multiline data,
  coordinate header, reconnect, and morph identity remain unchanged.
- **Compression:** compare identity, `CompressionStream`, and the current Node
  gzip baseline locally and through the intended remote proxy. Reject any mode
  that delays a morph until stream close, buffers across `Z_SYNC_FLUSH`
  equivalents, or materially increases retained memory.
- **Routing semantics:** malformed URLs, same-origin POST refusal, loopback-only
  doors, late-resolved handlers, 404/422/500 results, form reads, and async
  handler failures remain values rather than double responses or uncaught
  exceptions.
- **Native-runtime correctness:** run the complete web test suite and a live
  browser proof under the selected Bun/Shadow artifact. Node compatibility
  success alone does not prove the native server is in use.

## Acceptance measurements

Measure Node adapter/current gzip and native Bun/identity on the same build and
hardware after warm-up:

- requests/second and p50/p95/p99 latency for static, shim, and small POST
  routes at 1, 8, and 64 concurrent clients;
- feed open latency and transaction-to-visible-morph latency for 1, 8, 32, and
  128 simultaneous feeds;
- CPU time and allocated/retained bytes per 1,000 broadcasts for one shared
  view and many distinct views;
- event bytes, transport-buffer bytes, pending bytes, maximum resident set,
  and cleanup-to-baseline after every client disconnects;
- number of renders, serialized SSE events, compression operations, and socket
  writes for one update fanned to 1/8/64 equivalent feeds; and
- slow-reader isolation: fast-feed p99 and database-to-render progress must not
  regress while one connection remains pressured.

Graduate the native seam only when all existing semantic tests and live feed
proofs pass, no Node HTTP/zlib/hijack owner remains reachable, queued bytes are
bounded and observable per connection and globally, and native Bun improves
latency or CPU materially without unacceptable memory growth. A small memory
increase is acceptable when it purchases measured responsiveness; hidden or
unbounded retention is not.
