---
type: research
status: active
tags: [research, web]
---

# http-kit server write path and flow integration — 2026-07-29

## Verdict

This is a **real upstream gap, not a Seon misunderstanding**.

Pinned http-kit `v2.9.0-beta2` at commit
`70432d3ab3c9f23cb4672c7656d94fe8d71726d6` has:

- one Java NIO selector loop;
- request/WebSocket handlers on a separate worker executor;
- one unbounded `LinkedList<ByteBuffer>` named `ServerAtta.toWrites` per
  connection;
- direct nonblocking writes from any sending worker under the connection
  attachment's monitor;
- later draining of partial writes by the selector thread on `OP_WRITE`; and
- no core.async channel, output-queue bound, pending-byte accessor,
  queue-drained notification, or per-write completion surface.

`AsyncChannel.send` returns `false` only when its logical `closedRan` flag is
already set at method entry. It returns `true` after `HttpServer.tryWrite`,
whether the supplied buffers were written immediately, partially retained in
`toWrites`, appended behind an existing backlog, or met an `IOException` that
`tryWrite` converted into a pending close operation. The boolean is therefore
a logical-open/admission result, not write completion or backpressure
(`AsyncChannel.java:251-293`; `HttpServer.java:364-400` at the pinned commit).

Upstream's history confirms the same reading. A real slow-consumer OOM was
reported in [issue #180](https://github.com/http-kit/http-kit/issues/180), and
[issue #474](https://github.com/http-kit/http-kit/issues/474) later named SSE,
`toWrites`, and the absent backpressure surface exactly. Issue #474 was closed
by pointing at [issue #407](https://github.com/http-kit/http-kit/issues/407),
but #407 concerns the separate **request-worker queue**. Its remedy is a custom
`ThreadPoolExecutor` plus inspection of that executor's `ArrayBlockingQueue`;
it neither bounds nor exposes `ServerAtta.toWrites`.

The uncommitted candidate fork is a **locally useful emergency cap but not the
recommended contract**. For HTTP streaming it makes a partial or already
queued write return `false` after retaining the buffers. Seon's current caller
then stops and closes, so the candidate prevents further morphs from entering
http-kit. But it:

- changes the established meaning of `send!` from “channel was open” to a
  mixture of logical openness and immediate kernel-buffer acceptance;
- changes public Java `tryWrite` method descriptors from `void` to `boolean`,
  which is binary-incompatible for any precompiled direct caller;
- provides no event on the later `toWrites nonempty → empty` transition, so a
  producer can only close, poll, or guess when to resume;
- still returns `true` for WebSocket sends because their `tryWrite` results are
  ignored;
- can return `true` for an HTTP close-only call even when the final chunk was
  queued, because `serverClose` ignores `tryWrite`'s result; and
- documents a guarantee broader than the code implements.

The smallest honest durable change is an **observable per-channel write
state**, not a reinterpretation of `send!`:

```clojure
{:http-kit.write/pending-bytes 262144
 :http-kit.write/drained       completion}
```

The snapshot and its one-shot completion must be acquired atomically under the
same attachment monitor that owns `toWrites`. `drained` completes when that
observed queue reaches empty or the connection closes. A completed state has
zero pending bytes and an already-completed completion. This avoids the
check-then-register lost-wake race.

Seon's connection-owned `:io` writer then:

1. sends at most one Datastar event;
2. reads the returned/current write state;
3. if bytes are pending, parks its virtual thread on that exact drain-or-close
   completion;
4. while parked, allows the existing per-tab `(sliding-buffer 1)` tap to retain
   only the newest complete page; and
5. after drain, takes and sends that newest value.

That is the datahike-style integration: http-kit publishes the state transition
it owns; the Flow consumer waits on the transition instead of inferring it
from time or polling private fields. The http-kit user-space backlog becomes
at most the remainder of one event, the kernel send buffer remains
OS-bounded, and transient partial-write pressure does not force a healthy
connection to reconnect.

## Scope and dependency ledger

| dependency or mechanism | selected revision | source read |
|---|---|---|
| http-kit server | `v2.9.0-beta2`, `70432d3ab3c9` | every `reference-code/http-kit/src/java/org/httpkit/server/*.java`; pristine `src/org/httpkit/server.clj`; `README.md`; `CHANGELOG.md`; server tests |
| current upstream check | `master`, `7bd0a06ae2d5e50a44ec38fb0f672897352cfe8d` on 2026-07-29 | GitHub source for `AsyncChannel.java`, `HttpServer.java`, `ServerAtta.java`, and `server.clj` |
| Ring WebSocket protocols | `ring-clojure/ring` source at `75914ca942330ae6f8499b0ab9adda4664ad5f1b` | `ring-websocket-protocols/src/ring/websocket/protocols.clj`; `ring-core/src/ring/websocket.clj` |
| Datastar Clojure http-kit adapter | checked-out `reference-code/datastar-clojure` | `libraries/sdk-http-kit/.../adapter/http_kit.clj` and `adapter/http_kit/impl.cljc` |
| Seon render writer | current fresh tree | `src/seon/render/web.clj:493-589`; `test/seon/render/web_test.clj:297-321,617-660` |
| Flow transport rule | current architecture | `docs/seon/architecture/ui.md:650-717`; `docs/prds/sci-execution-runtime/research/flow-mechanics-2026-07-28.md` |

The http-kit submodule had an uncommitted candidate diff in exactly three
tracked files:

```text
M src/java/org/httpkit/server/AsyncChannel.java
M src/java/org/httpkit/server/HttpServer.java
M src/org/httpkit/server.clj
```

All pristine claims below were reconstructed with `git show HEAD:<path>`,
before reading that diff. No http-kit file was changed, staged, committed, or
reverted by this audit.

The current upstream `master` check matters because a newer honest surface
would change the recommendation. It does not: `AsyncChannel.java`,
`ServerAtta.java`, and `server.clj` are byte-identical to the pinned tag.
`HttpServer.java` differs only by an unrelated change that sends HTTP 400 on a
protocol error before closing. Upstream still has the same write queue and
API.

## The actual concurrency model

### Threads and ownership

http-kit describes itself as event-driven and nonblocking. That is accurate,
but it does not mean that writes are represented by channels.

`HttpServer` owns:

- one `Selector`;
- one nonblocking `ServerSocketChannel`;
- one `server-loop` thread running `HttpServer.run`;
- one shared direct 64 KiB read buffer, safe because only the selector thread
  reads sockets;
- one `ConcurrentLinkedQueue<PendingKey>` carrying cross-thread selector
  operations; and
- one attachment per accepted socket (`HttpAtta`, later possibly `WsAtta`).

`RingHandler` owns the request/WebSocket worker executor. On JVM 21+ the
default is a virtual-thread-per-task executor; otherwise it is a configurable
`ThreadPoolExecutor`. Its `queue-size` is the request task queue capacity, not
an output-byte capacity (`server.clj:54-73`; `RingHandler.java:262-316`).

The selector thread:

- accepts sockets and attaches `HttpAtta`;
- reads and decodes HTTP or WebSocket input;
- drains pending selector operations from `HttpServer.pending`;
- changes interest sets to `OP_WRITE` or closes keys;
- calls `doWrite` when a key becomes writable; and
- returns a drained keep-alive connection to `OP_READ`, or closes it when the
  attachment says not to keep it alive (`HttpServer.java:172-323,325-362,
  404-451`).

Request workers invoke user Ring handlers. `as-channel` hands application code
the request's `AsyncChannel`; an application may retain it and call `send!`
from any thread (`server.clj:343-402`).

### The two queues must not be confused

There are two unrelated server-side queues.

| queue | content | bound | purpose |
|---|---|---|---|
| `RingHandler` worker queue | request or inbound WebSocket handler `Runnable`s | configurable/bounded for a standard worker | admission to application work |
| `ServerAtta.toWrites` | remaining outbound `ByteBuffer`s for one socket | unbounded `LinkedList` | preserve output order until the socket accepts bytes |

There is also `HttpServer.pending`, a `ConcurrentLinkedQueue<PendingKey>`.
That queue contains selector commands such as “set this key to `OP_WRITE`” or
“close this key with status.” It does not contain response bytes, does not
represent write completion, and may contain duplicate `OP_WRITE` commands for
the same key.

The official wiki's “Custom request queues” example and issue #407 expose only
the first row. Neither reaches `toWrites`. The server options reinforce the
same boundary:

- `max-body` caps an inbound HTTP request body;
- `max-line` caps an inbound request/header line;
- `max-ws` caps an inbound decoded WebSocket message;
- `proxy-protocol` controls inbound PROXY header parsing;
- `worker-pool`/`queue-size` controls handler-task admission; and
- `server-header`, logging, address, and content-length options do not affect
  output retention.

There is no server option for pending write buffers or bytes, no socket send
buffer option, and no output overflow policy (`server.clj:79-148`).

### `tryWrite`: immediate attempt, then unbounded retention

Every send reaches one of the two `HttpServer.tryWrite` overloads. The
overloads return `void` in pristine source.

Under `synchronized (atta)`:

1. The call updates the HTTP chunk-in-progress flag.
2. If `atta.toWrites` is empty, the calling thread directly invokes the
   nonblocking `SocketChannel.write(ByteBuffer[])`.
3. If the final supplied buffer still has remaining bytes, every buffer with
   remaining bytes is appended to `toWrites`. An `OP_WRITE` command is added
   to `pending`, and the selector is woken.
4. If all bytes were accepted immediately and the connection is not
   keep-alive, a close command is queued.
5. An `IOException` is swallowed into a close command and selector wake.
6. If `toWrites` was already nonempty, **all supplied buffers are appended**
   with `Collections.addAll`, followed by another `OP_WRITE` command and
   wakeup.

Nothing in this method counts buffers or bytes. Nothing refuses, displaces,
blocks, or notifies the sender. The only limiting resources are heap and the
rate at which the peer eventually allows the selector thread to drain the
list (`HttpServer.java:364-400`).

The immediate `SocketChannel.write` does not mean bytes reached the peer. It
means Java transferred bytes into the operating system's bounded socket send
buffer. The honest transition available inside http-kit is therefore
**user-space pending queue drained**, not remote delivery acknowledged.

### `doWrite`: selector-driven drain

When the selector reports the socket writable, `doWrite` takes the same
attachment monitor:

1. With one queued buffer it calls `SocketChannel.write(buffer)`.
2. With several it snapshots the list to an array and performs a gathering
   write.
3. It removes every buffer whose mutable position now has no remaining bytes.
4. If the list is empty, it returns a keep-alive connection to `OP_READ` or
   closes a non-keep-alive connection.
5. If bytes remain, the key stays interested in `OP_WRITE`.

This is the exact event Seon needs: after step 3, the transition from a
nonempty list to an empty list is already known under the correct monitor.
Pristine http-kit simply does not publish it (`HttpServer.java:325-362`).

### Ordering

Concurrent sends are serialized by `synchronized (atta)`. A worker's direct
write and the selector's draining write take the same monitor, so buffer order
is preserved. Once any bytes are pending, later sends append behind them.

`LinkingRunnable` and `AsyncChannel.serialTask` do **not** implement outbound
write ordering or backpressure. `RingHandler.handle(AsyncChannel, Frame)` uses
that linked runnable chain only to make inbound WebSocket callbacks from one
client execute in message order (`RingHandler.java:203-221,334-355`).

Outbound HTTP streaming and WebSocket sends share `tryWrite`, the attachment
monitor, and `toWrites`. Their framing differs; their queue does not.

## What `send!` and close actually mean

### Every path that returns `false`

Pristine `AsyncChannel.send(data, close)` has one false return:

```java
if (closedRan.get()) {
    return false;
}
```

After that check it always reaches `return true`, unless encoding or body
conversion throws (`AsyncChannel.java:251-294`).

`closedRan` becomes true in two places:

- `serverClose`, which wins a compare-and-set before it enqueues a WebSocket
  close frame or HTTP final chunk; and
- `onClose`, which wins the same compare-and-set before invoking registered
  close callbacks.

Consequences:

- The first ordinary HTTP `send!` defaults to close-after-send, marks the
  channel logically closed, and normally returns `true`; later sends return
  `false`.
- A streaming send with `close-after-send? false` stays logically open and
  returns `true`, even when its bytes are queued.
- A kernel partial write does not make `send!` false.
- An already nonempty `toWrites` list does not make `send!` false.
- An `IOException` caught inside `tryWrite` does not make `send!` false.
- `open?` is only `(not (.isClosed ch))`, so it reports the same logical flag,
  not key validity, queue state, or peer receipt (`server.clj:256-319`).

There is a further close-detection caveat. `HttpServer.closeKey` calls
`RingHandler.clientClose`; that code calls `AsyncChannel.onClose` when the
channel has a close handler. With no handler it does not set `closedRan` true
(`RingHandler.java:357-405`). A retained channel without a close handler can
therefore lag physical socket closure in its logical state. SSE correctly
registers `on-close`, and the fix for
[issue #578](https://github.com/http-kit/http-kit/issues/578) ensures the
handler is attached to the same reusable HTTP channel that `closeKey` later
reports, but this still does not turn `send!` into a write-state signal.

### Close is logical and asynchronous with respect to bytes

For HTTP, `serverClose`:

1. atomically marks `closedRan`;
2. calls `tryWrite` with the final chunk `0\r\n\r\n` and
   `chunkInprogress=false`;
3. invokes close callbacks immediately; and
4. returns whether this call won the logical close race.

It does not synchronously close the `SelectionKey`. If prior bytes or the final
chunk remain, they stay in `toWrites`; `doWrite` closes the key only after the
list empties and `isKeepAlive` becomes false. A peer that never reads can
therefore retain the bounded final backlog and socket until the network stack
reports closure. `close` is useful cleanup, not a drain completion
(`AsyncChannel.java:225-249`; `HttpServer.java:349-356`).

### HTTP streaming versus WebSocket

| aspect | HTTP streaming | WebSocket |
|---|---|---|
| first send | encodes status/headers, enables HTTP/1.1 chunking when kept open | handshake is separate |
| later send | encodes chunk size, body, CRLF | encodes one RFC 6455 frame |
| default `send!` close | true | false |
| close bytes | HTTP final chunk | WebSocket close frame |
| outbound synchronization | attachment monitor | same attachment monitor |
| pending output | `ServerAtta.toWrites` | same inherited list |
| queue cap or drain signal | none | none |
| inbound callback order | ordinary request semantics | `LinkingRunnable` serializes received frames |

The candidate currently propagates `tryWrite`'s new boolean only through the
HTTP `firstWrite`/`writeChunk` paths. Its WebSocket branch still ignores every
`tryWrite` result and returns `true`, so its new Clojure docstring is false for
WebSockets.

## Existing notification and observability surfaces

### What exists

- `as-channel :on-open` says the `AsyncChannel` is ready for application
  sends. It fires once; it is not repeated writability.
- `on-close` reports logical/server/client close at most once. It does not
  report temporary output pressure or drain.
- `on-receive`, `on-ping`, and the Ring WebSocket listener report inbound
  WebSocket events.
- `RespCallback` is the ordinary Ring response bridge. Its `run` method merely
  calls `server.tryWrite`; it has no completion callback
  (`RespCallback.java:6-18`).
- `server-stop!` returns a Clojure promise delivered when the whole server
  thread stops. It is unrelated to a connection write
  (`server.clj:20-52`; `HttpServer.java:460-530`).
- event loggers report status/error classes, not per-channel write state.

### The apparent Ring WebSocket callback does not help

The official http-kit wiki says the Ring WebSocket API can detect send
success/failure through callbacks. Ring does define an optional
`ring.websocket.protocols/AsyncSocket`:

```clojure
(-send-async [socket message succeed fail])
```

and four-argument `ring.websocket/send` dispatches directly to it. But
http-kit's `ring-websocket-resp` reifies only `ring.websocket.protocols/Socket`,
implementing synchronous `-send`, `-ping`, `-pong`, and `-close`; it does not
implement `AsyncSocket` (`server.clj:417-450`). Therefore this optional Ring
surface is not an existing http-kit completion hook. It is WebSocket-specific
in any case and cannot serve SSE.

Ring source:
[protocols.clj](https://github.com/ring-clojure/ring/blob/75914ca942330ae6f8499b0ab9adda4664ad5f1b/ring-websocket-protocols/src/ring/websocket/protocols.clj#L29-L50)
and
[websocket.clj](https://github.com/ring-clojure/ring/blob/75914ca942330ae6f8499b0ab9adda4664ad5f1b/ring-core/src/ring/websocket.clj#L26-L38).

### The Datastar adapter preserves the misleading boolean

The Datastar http-kit adapter's basic send function calls
`org.httpkit.server/send! ch event false` and returns that value. Its
`SSEGenerator.send-event!` returns the send function's value unless an
exception is thrown. `patch-elements!` therefore propagates http-kit's boolean
unchanged.

The adapter adds a `ReentrantLock`, so events through one generator are
serialized, but it adds no bound or readiness event. Its `close-sse!` calls
http-kit's logical `close`. Seon's `write-patches!` is consequently correct
about propagation but wrong to call pristine `false` a backpressure signal:
pristine `false` means the channel was already logically closed.

## Upstream issue and documentation audit

### Issue #180: measured slow-consumer OOM

[Slow consumer can exhaust memory on Server](https://github.com/http-kit/http-kit/issues/180)
reported in 2014 that streaming a large amount of data to a client that could
not consume fast enough exhausted server memory. The report asked for a
bounded queue whose writes block at capacity. It was closed as a duplicate of
#90, but #90 concerns buffering complete inbound request/client response
bodies, not the per-socket outbound streaming list. The source-level output
gap remained.

### Issue #474: exact diagnosis, closed against the wrong queue

[Possible Out of Memory due to missing back pressure in Server-Sent Events
(and elsewhere)?](https://github.com/http-kit/http-kit/issues/474) quoted the
earlier diagnosis, pointed directly at `tryWrite` and `toWrites`, and asked for
output-empty or queue-size visibility. The maintainer explicitly confirmed the
concern as an unbounded pending `LinkedList` and suggested queue size could be
useful.

The issue was later closed as addressable by #407. But
[getting info on queue depth](https://github.com/http-kit/http-kit/issues/407)
asks for the number of HTTP **requests waiting to start application
processing**. Its accepted workaround and wiki recipe expose the custom
executor's request `ArrayBlockingQueue`. That is a different object, lifetime,
owner, and resource from `ServerAtta.toWrites`. The closure was a
queue-name conflation, not a fix.

### Changelog, API docs, tests, and wiki

- The `v2.9.0-beta2` changelog has no server write backpressure, output queue,
  or completion addition.
- The `Channel/send!` doc says true when data was “successfully sent” and false
  when closed, but the implementation and server tests use the boolean as
  logical channel reuse/open state.
- `test-channel-reuse-async` expects `[true false false false]` after the first
  close-after-send call, proving the closed-channel interpretation
  (`server_test.clj:564-622`).
- `test-channel-async-client-side-close` expects two open SSE sends to return
  `[true true]`; it proves close detection, not write completion or pressure
  (`server_test.clj:624-667`).
- The WebSocket stress test contains a historical comment that concurrent
  large writes appeared to drop buffers, but provides no output-state API
  (`ws_test.clj:172-190`).
- The official [server wiki](https://github.com/http-kit/http-kit/wiki/3-Server)
  documents event-driven NIO, WebSockets, request-worker queue customization,
  virtual threads, and inbound/config options. It documents no output cap,
  pending-byte state, drain event, or SSE backpressure contract.

The exhaustive GitHub issue/PR search for `backpressure`, `back pressure`,
`slow consumer`, `toWrites`, `pending queue`, `write queue`, and send
callbacks found #180 and #474 as the server output reports and no merged or
open PR implementing a server write-pressure surface.

## Review of the uncommitted candidate

### What it changes

The candidate:

- changes both `HttpServer.tryWrite` overloads to return `boolean`;
- returns `false` after a partial immediate write has been appended;
- returns `false` when new buffers are appended behind an existing backlog;
- returns `false` when `SocketChannel.write` throws;
- returns `true` when all supplied buffers were immediately consumed;
- threads that result through HTTP `firstWrite` and `writeChunk`; and
- rewrites `Channel/send!` documentation to describe full immediate writes
  versus pending-queue entry.

It adds 21 lines and removes 13 across three files. It adds no queue bound,
counter, callback, or state object.

### What is correct

- The new `tryWrite` result accurately distinguishes “all buffers from this
  call were consumed by the immediate nonblocking write” from “some or all
  buffers are now retained in http-kit's user-space queue,” for that invocation
  under the attachment monitor.
- It reports an already queued call as false.
- It reports a caught immediate `IOException` as false.
- In Seon's current HTTP-only use, Datastar propagates the boolean;
  `write-patches!` closes on false; and the writer stops submitting more
  morphs. This converts unbounded growth into at most the first retained morph
  plus framing/final-close bytes. It is a valid emergency safety behavior for
  a latest-wins stream whose reconnect is repaint.
- It does not disturb buffer ordering or selector drain mechanics.

### What is incorrect or incomplete

1. **False comes after acceptance.** The partial or subsequent buffers are
   already in `toWrites`. A generic caller reading false as “not sent” and
   retrying will duplicate data. The result is a state classification, not
   success/failure.
2. **It is not completion.** True means all buffers were accepted into the
   operating system send buffer during one call. It does not mean the peer
   received them.
3. **It changes an established public meaning.** Existing tests and docs have
   long treated false as closed-channel reuse failure. Overloading false with
   transient pressure makes the old two-state API ambiguous.
4. **It is Java binary-incompatible.** JVM method descriptors include return
   type. Precompiled code invoking public
   `HttpServer.tryWrite(...):void` will not link against
   `tryWrite(...):boolean`, even though recompiled Java may ignore a returned
   value.
5. **WebSocket sends ignore the result.** The candidate assigns
   `fullyWritten` only in the HTTP branch. WebSocket text, binary, ping, pong,
   and input stream sends still return true after `tryWrite`.
6. **Some close writes ignore the result.** `serverClose` calls the new
   `tryWrite` but discards its boolean. A `writeChunk` with no body and
   `close=true` initializes `fullyWritten=true`, queues a possibly partial
   final chunk through `serverClose`, and returns true.
7. **No resume event exists.** A caller that wants to preserve the connection
   cannot know when to continue without polling private state. Seon's current
   response is to close on the first transient partial write.
8. **It does not bound http-kit itself.** Any caller that ignores the boolean,
   including the unchanged WebSocket path, can still grow `toWrites`
   indefinitely.
9. **The docstring overclaims.** “False if the channel is closed or any bytes
   entered its pending write queue” is not true across the implemented channel
   modes and close paths.

### Candidate verdict

**Not correct as the final fork contract; not oversized in line count, but
oversized in compatibility blast radius and undersized in semantics.**

Keep the idea that http-kit must reveal immediate queue entry. Do not land it
by redefining the existing boolean. If an emergency patch must precede the
observable-state change, narrow and name it as an HTTP-only “queued, stop
submitting and close” signal, add the stalled-consumer regression, and treat it
as temporary. The durable fork should preserve `send!` and publish write
state.

## Three integration shapes

### Shape A — minimal fork: immediate status or pending-byte accessor

#### A1. Candidate boolean

Cost: roughly the existing 21-line change, no Datastar fork because the
adapter already propagates the boolean.

Behavior: Seon closes whenever one event cannot be completely accepted
immediately. This bounds further application writes but turns ordinary
transient kernel pressure into connection churn.

Risks: ambiguous false-after-acceptance, existing API break, no resume, HTTP/
WebSocket mismatch, and no universal bound.

Verdict: acceptable only as an emergency local stopgap after correcting the
scope and tests; not recommended as the lasting integration.

#### A2. Add-only state query

Leave `tryWrite` and `send!` unchanged. Add an `AsyncChannel` method/Clojure
protocol function that returns, under the attachment monitor:

```clojure
{:http-kit.write/pending-buffers n
 :http-kit.write/pending-bytes   bytes}
```

Cost: a small add-only Java/Clojure surface. Exact bytes can be derived by
summing `ByteBuffer.remaining()` under the monitor. An O(1) counter is
possible but requires careful updates around mutable buffer positions and
close.

Behavior: after each Datastar send, Seon can close immediately when pending
bytes become positive. That preserves the existing boolean and gives honest
measurement.

Risk: using the accessor to wait requires polling, which violates the
event-driven readiness rule. A check followed by callback registration would
also race with a drain between those operations.

Verdict: better than the candidate for observability and emergency close-on-
pressure, but incomplete for the desired Flow integration.

### Shape B — callback/notification integration

No existing http-kit callback reports output drain. `on-close` is too late;
`on-open` is one-shot connection setup; `RespCallback` is an ordinary response
bridge; Ring `AsyncSocket` is not implemented and is WebSocket-only.

A new persistent `on-drain` callback could fire when `doWrite` removes the
last queued buffer. Seon would register it before its first send and have the
callback make a nonblocking offer to a one-slot connection channel. After a
send observes pending bytes, the writer parks until the drain channel fires.
Register-first plus a buffered event avoids a drain-before-park loss.

Cost inside http-kit:

- one handler slot on `AsyncChannel`, cleared on reset;
- detection of `toWrites nonempty → empty` in `doWrite`;
- completion on close so a parked writer cannot wedge; and
- a deliberate callback threading contract.

The threading contract is the hard part. Running arbitrary Clojure callback
code on the selector thread can stall I/O for every connection. Submitting it
through `RingHandler` follows close-handler precedent but makes delivery
subject to worker rejection and delay. A callback that is specified to do
only nonblocking notification is small for Seon but less robust as a general
upstream API.

Per-write success callbacks would be larger still: `toWrites` would need
write-boundary markers or queue entries carrying callbacks, failure fan-out on
close, and a policy for callback execution. Latest-wins rendering does not
need that precision.

Verdict: viable and much better than polling. A queue-level drain notification,
not per-message acknowledgements, fits the need. But callback scheduling and
lost-notification semantics must be explicit.

### Shape C — observable write state with drain completion

Expose one immutable snapshot acquired under the `ServerAtta` monitor:

```clojure
{:http-kit.write/pending-buffers n
 :http-kit.write/pending-bytes   bytes
 :http-kit.write/drained         completion}
```

Properties:

- When the queue is empty, counts are zero and `drained` is already complete.
- When the queue first becomes nonempty, the attachment creates one new
  one-shot completion for that pending epoch.
- Every state read while the same backlog remains returns that same
  completion.
- `doWrite` atomically detaches the completion when it empties the queue, then
  completes it outside the monitor.
- `closeKey` also completes/cancels the pending completion with closed state,
  so no waiter wedges.
- A later backlog gets a new completion.

This can be implemented with a Java 8 `CompletableFuture` or a tiny
http-kit-owned completion interface. It does not require a callback per
buffer, does not mutate the buffer-list element type, and does not run user
code while holding the attachment monitor. A completion action may still run
on the completing thread, so Seon's action must only signal its channel; the
connection-owned virtual thread performs all later work.

The atomic snapshot removes the check/register race:

- if the queue drains before the writer waits, the returned completion is
  already complete;
- if it drains later, that exact completion fires; and
- if it was already empty, the writer does not park.

Cost: likely four or five small owner-aligned changes:

- `ServerAtta`: pending-epoch completion state and snapshot;
- `HttpServer`: create/complete it at the existing queue transitions and
  close path;
- `AsyncChannel`: public state access;
- `server.clj`: one named Clojure function/protocol surface; and
- optionally a small `WriteState` value class or interface.

This is somewhat larger than the candidate but smaller in behavioral blast
radius because existing `send!` semantics remain untouched. It naturally
serves HTTP streaming and WebSocket because both inherit `ServerAtta` and use
`tryWrite`.

Verdict: **recommended**.

## Recommended Seon integration

The Flow writer should not move into http-kit, and http-kit's selector should
not become a core.async proc. The integration boundary is the write-state
transition.

For each tab:

```text
render mult
  → per-tab (sliding-buffer 1)
  → connection-owned :io writer
  → Datastar event serialization
  → http-kit send!
  → inspect atomic write-state
      zero pending: continue
      pending: park on drained-or-closed completion
  → after drain, take newest page from sliding-1
```

This preserves each owner's mechanism:

- Flow owns latest-wins displacement before the external connection.
- Datastar owns SSE framing.
- http-kit owns NIO, buffer ordering, and knowledge of when its own pending
  list drains.
- The operating system owns the bounded socket send buffer.
- Reconnect remains repaint from current database truth.

The writer must never interpret queue drain as remote receipt. It is only
permission to submit another latest complete value without creating a second
unbounded application queue.

The writer also must not enqueue multiple block patches blindly after the
first one becomes pending. `write-patches!` should check state after each
Datastar event; if pending, park before serializing the next patch. While
parked, later complete pages coalesce in the existing sliding tap.

## Proof required before landing

### Dependency fork

- Unit-level Java test: immediate complete write yields an already-drained
  zero-pending state.
- Real nonblocking socket test: a partial write yields positive pending bytes
  and an incomplete completion.
- Selector test: reading from the peer lets `doWrite` empty the list and
  complete exactly the observed pending epoch once.
- Race test: drain between state acquisition and waiting cannot lose the
  completion.
- Close test: peer/server close settles a pending completion and does not
  strand a waiter.
- HTTP and WebSocket test: both report state through the same attachment
  mechanism.
- Existing `Channel/send!` tests retain their old boolean expectations.

### Seon integration

- The existing paused-reader proof must observe a constant bound through a
  supported accessor rather than reflection.
- Under repeated 256 KiB complete morphs, the maximum http-kit user-space
  pending bytes must remain at no more than one event remainder plus framing,
  independent of commit count.
- The connection-owned writer must visibly park while pending, while the
  per-tab tap contains at most one newest complete page.
- When the client resumes reading, the writer must wake from the drain event
  and send the newest page, not every displaced intermediate page.
- A fast consumer must remain connected and receive the same patches; the
  integration must not turn every harmless partial write into reconnect.
- Closing the browser or server must untap and terminate the writer without a
  timeout.
- Reconnect must still repaint current database truth.

## Final answer to the owner directive

http-kit is not using channels internally. It is a compact Java NIO server
whose application threads and selector thread coordinate through Java queues,
one per-attachment monitor, and selector wakeups. We did not miss an upstream
backpressure API. Upstream users reported the exact gap twice; the later issue
was closed against the wrong queue, and the gap remains in current master.

Do not discard the candidate's key observation: the first partial write is the
boundary where Seon must stop submitting. But do not make the existing
`send!` boolean carry a new ambiguous meaning. Publish the per-channel pending
write state and its exact drain-or-close completion, then let the Flow `:io`
writer park on that event while the existing sliding-1 tap keeps the newest
render. That is the smallest honest change that both bounds memory and
integrates with the surviving architecture.
