---
type: research
status: completed
tags: [research, prd, flow, web, agent]
---

# Removal-first Bun integration audit — 2026-07-15

## Conclusion

Use Bun as the one JavaScript runtime and move inward to three native host
capabilities:

1. `Bun.spawn` behind one bounded subprocess owner;
2. `Bun.serve` plus Web streams behind the existing router/feed owner; and
3. native Bun Unix sockets behind the existing framed database transport.

This is not a mandate to rewrite every Node import. Compatibility APIs that are
not measured costs may remain temporarily, then disappear when their owning cut
can delete more code than it adds. The target is fewer concepts and allocations,
not a Bun-branded copy of the Node architecture.

## Why `Bun.spawn` is the stronger subprocess interface

The retained spawn benchmark found no material throughput or RSS advantage over
Bun's `node:child_process` adapter. The native API still wins as the long-term
interface because it directly exposes the concepts Seon needs:

- argv is a string vector, never a shell command;
- `stdin`, `stdout`, and `stderr` are explicit sinks or Web streams;
- `exited` is one Promise rather than a callback plus several events;
- `exitCode`, `signalCode`, `killed`, and `pid` expose process state;
- `kill`, `ref`, and `unref` make lifecycle ownership explicit;
- `resourceUsage()` exposes per-child CPU and memory evidence;
- an `ipc` callback and `send` exist when a Bun child genuinely needs IPC; and
- the subprocess is asynchronously disposable.

The exact audited declarations are in
`reference-code/bun/packages/bun-types/bun.d.ts` around `Subprocess` and
`spawn`. Native implementation lives in
`reference-code/bun/src/runtime/api/bun/js_bun_spawn_bindings.rs` and
`reference-code/bun/src/runtime/api/bun/subprocess.rs`. Bun's own
`node:child_process` implementation is an adapter in
`reference-code/bun/src/js/node/child_process.ts`; retaining it means retaining
Node's callback/EventEmitter contract without gaining a faster primitive.

Native spawn does not supply Seon's complete policy. The owner must still
implement timeout, bounded incremental capture, UTF-8 decoding, stdin closure,
kill escalation, output-overflow classification, and errors-as-values. Those
are product semantics, not adapter compatibility.

## The closer conceptual seams

### Subprocess capability

The current shell and search internals each own an `execFile` wrapper with
similar timeout, buffer, callback, and error conversion. Their public results
are different, but process mechanics are the same. The new internal capability
takes one namespaced request and resolves one namespaced process result. Shell
and search retain only authorization and domain-specific interpretation.

The capability should stream and count bytes while the child runs. It should
not recreate `execFile`'s full-buffer callback internally. Foreground capture
and background paging become policies over one stream pump rather than two
process implementations. Per-child `resourceUsage()` can feed bounded
diagnostics without becoming durable application state.

### HTTP and feed capability

The current web owner translates Bun-native HTTP into Node request/response
objects, then translates Node gzip streams into a long-lived Datastar feed.
Direct `Bun.serve` removes that translation layer and exposes request abort,
Web-stream backpressure, and response construction at the host boundary.

The router still owns routing and Ring-shaped application responses. The host
adapter should translate one Web `Request` into that existing pure request map
and one response value back into a Web `Response`. Feed derivation, equivalent
view sharing, transaction invalidation, coalescing, and latest-wins semantics
remain unchanged.

Loopback feeds default to uncompressed SSE. The retained measurement found 100
plain feeds at 43.09 MiB RSS, versus 78.03 MiB with native HTTP plus per-feed
gzip and 87.19 MiB through `node:http` plus per-feed gzip. The isolated 100 by
100 fanout cost fell from 269.4 ms CPU for stateful per-feed gzip to 0.99 ms for
one shared gzip member per event and 0.01 ms uncompressed. Shared compressed
members remain an open option only after real browser and proxy proof.

### Framed socket capability

The database protocol remains Transit JSON with length framing. Native Bun
sockets replace `node:net`, not the protocol. The current decoder repeatedly
concatenates the accumulated prefix under fragmentation; replace it first with
a cursor/chunk queue so the native comparison is honest.

Native socket writes may be partial or return zero. The transport must retain
the exact unwritten suffix and resume on drain. The retained compact-frame
measurement showed about three times the throughput, 68% less CPU, and half the
short-run RSS growth of Bun's `node:net` adapter; deliberate fragmentation
narrowed the throughput win to about 10% but retained CPU/allocation benefit.

## Candidate deletion inventory

The initial candidates are deliberately broader than the committed migration
order. Each must be confirmed against current source immediately before its cut.

| Current mechanism | Target owner | Candidate removal |
|---|---|---|
| `node:child_process` in shell | one subprocess capability | callback wrapper, `execFile` option translation, duplicated exit classification |
| `node:child_process` in search | one subprocess capability | second timeout/buffer/callback implementation |
| separate foreground and background pumps | subprocess capability policies | duplicated stream listeners and cap bookkeeping where one pump suffices |
| `node:http.createServer` | Bun web host | Node request/response adapter, EventEmitter listen/error glue |
| one `zlib.createGzip` per feed | feed encoder/host | per-connection compressor, flush calls, gzip pipe/error lifecycle |
| Node response backpressure | Web stream feed | drain listeners and writable-state inference replaced by desired-size/cancel semantics |
| `node:net.createConnection` | framed socket transport | Node socket event adapter and queued-write assumptions |
| repeated `Buffer.concat` receive prefix | framed decoder | quadratic prefix copies |
| hard-coded Node execution doors | launch/runtime identity | Node-only doctor/package/test commands after Bun graduation |
| Node production dependency | release package | Node executable requirement and Node compatibility declarations |
| dual-runtime differential flags | release manifest | temporary Node rollback selector after the final checkpoint |

## Things deliberately left open

- A single shared uncompressed event encoding may be enough; do not add shared
  gzip merely because its microbenchmark is good.
- Static responses may use `Bun.file` if package/path proof shows a real
  allocation or latency gain.
- Bun IPC may replace ad hoc child protocols only when a Bun child already
  exists and structured messages simplify the contract.
- Execution cells may use processes, workers, or a mixture. Workers are not
  assumed cheap because each owns a JavaScriptCore VM.
- One writer may serve a family of databases, allowing many dormant or active
  clusters without one JVM each. That is a database-server topology decision,
  not part of the Bun host rewrite.
- The best final code may delete entire background-job concepts in favor of
  addressable execution results, but only after agent workflow evidence.

## Main risk

The dangerous migration is a line-for-line API substitution. It would retain
Node-shaped buffering and lifecycle assumptions while adding Bun-specific
failure modes. Every implementation cut therefore starts by specifying the
smallest product contract, implements it natively, redirects all consumers, and
deletes the old mechanism before the cut graduates.
