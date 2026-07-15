---
type: research
status: completed
tags: [research, web, cljs]
---

# Bun native HTTP adapter measurements — 2026-07-15

## Decision

`Bun.serve` is materially better than Bun's `node:http` compatibility adapter
for Seon's finite HTTP traffic and idle SSE footprint. Direct uncompressed Web
streams are much better again for long-lived feeds. The evidence supports a
Bun-native host adapter after runtime parity, but it does **not** yet support
blindly replacing the current feed with a `CompressionStream`: Seon needs
incremental flush, cancellation, and latest-wins pressure, and Bun's direct
Web-stream path supplies the latter two while Node-compatible zlib remains the
only measured incremental gzip implementation.

The recommended next implementation experiment is one `Bun.serve` owner that
keeps the existing Ring data/router and render/coalescing mechanism, uses direct
`Response` values for finite/static traffic, and serves loopback SSE
uncompressed by default. Retain a measured gzip mode only for a remote/proxied
deployment that proves bandwidth is the limiting resource. This removes an
adapter and one compressor plus zlib state per feed without creating a second
update model.

## Exact setup and retained evidence

The experiment used Bun 1.3.14 at
`/Users/sean/.bun/bin/bun` (SHA-256
`e0c90ec15d33363e6b70713d56bc3b2c7585c17f40a0fe0f8fd9305901d4e233`)
and the source checkout at
`be77b652884b16a103cfaa4af3c1102f72f2dcd3`. Node compatibility APIs therefore
ran inside Bun/JSC; this is a native-versus-adapter test, not Node/V8 versus
Bun/JSC. The host was macOS Darwin 25.5.0 on an 18-core Apple M5 Max with 128
GiB RAM. Repository HEAD was `7c97f932456d3a12618354a1fe6c417a05cc9f66`.

Fixtures and raw evidence are retained under
`tmp/bun-runtime-measurements/http/`:

- `server.js` — the three transport variants;
- `bench.js` and `raw.jsonl` — seven interleaved end-to-end runs;
- `fanout.js` and `fanout.raw.jsonl` — seven compression/fanout runs;
- `bench.stdout` — the complete human-readable run output; and
- `README.md` — reproduction commands and variant definitions.

The decisive commands were:

```bash
RUNS=7 bun tmp/bun-runtime-measurements/http/bench.js
bun tmp/bun-runtime-measurements/http/fanout.js
```

`server.js`, `bench.js`, and `raw.jsonl` SHA-256 values were respectively
`d92916b8cfbdbf53b354f78c449848e01f01e63657c1f66ea2f5abdfe22885b1`,
`048c26e61e48ba23ab76a0e4bcaa09c6d02dfb3bf183e175d0fdb56d386d14ed`,
and `ad0d6e289720b4f1965f22efa7644e575e8aeba98e66e6ba5ac97944ddfbc7ea`.
The fanout fixture and raw output hashes were
`78c85f717c92208dba561f5c4a43c008c4879e99f2f5c5c3625e506bcf6c112e`
and `af40a1dce9f96f5844f4bd07b0eadf7c463262d5d20ead4e4b4f5ea63d9ad73a`.

## End-to-end results

These are medians of seven interleaved runs. Every server ran under the same
Bun binary, bound `127.0.0.1:0`, returned the same 1 KiB finite body, and
pushed the same 4,135-byte SSE event. The gzip variants used zlib
`Z_SYNC_FLUSH`, matching Seon.

| Measurement | `node:http` + gzip | `Bun.serve` + gzip | `Bun.serve` plain |
|---|---:|---:|---:|
| Ready time | 21.88 ms | 18.07 ms | 19.81 ms |
| Idle RSS | 35.50 MiB | 35.19 MiB | 35.23 MiB |
| Sequential finite requests/s | 9,898 | 12,254 | 12,895 |
| Sequential p95 | 0.177 ms | 0.103 ms | 0.109 ms |
| 50-way finite requests/s | 59,678 | 96,713 | 95,759 |
| 50-way p95 | 1.210 ms | 1.161 ms | 1.039 ms |
| First SSE response bytes | 3.53 ms | 3.29 ms | 1.17 ms |
| RSS, 1 idle feed | 58.91 MiB | 41.20 MiB | 40.02 MiB |
| RSS, 10 idle feeds | 61.44 MiB | 44.38 MiB | 40.13 MiB |
| RSS, 100 idle feeds | 87.19 MiB | 78.03 MiB | 43.09 MiB |
| 100-feed increment from 1 | 28.28 MiB | 36.83 MiB | 3.07 MiB |

Direct `Bun.serve` improved median sequential throughput by 24% and bounded
concurrent throughput by 62% versus Bun's Node adapter when both used the same
gzip implementation. The concurrency result is more variable than the
sequential result, but six of seven native-gzip runs exceeded their interleaved
Node-adapter run. This microbenchmark does not imply a 62% whole-pod gain: Seon
also renders, serializes, queries, and compiles.

The largest design signal is the feed footprint. Direct uncompressed streams
added about 31 KiB per idle feed between one and 100. Native HTTP plus one zlib
bridge per feed added about 381 KiB per feed; the Node adapter plus gzip added
about 293 KiB per feed. RSS includes allocator/JIT history and is not a precise
object-size measure, but the 9–12x slope difference is too large to dismiss.
Native gzip had a lower one-feed starting point yet a steeper per-feed slope,
so removing `IncomingMessage`/`ServerResponse` alone does not remove the major
many-feed cost: independent compressor/stream state dominates.

## Slow clients, cancellation, and CPU

The slow-client probe requested 20,000 16 KiB chunks, paused the client after
its first response bytes, observed the server for one second, then cancelled.

| Measurement | `node:http` + gzip | `Bun.serve` + gzip | `Bun.serve` plain |
|---|---:|---:|---:|
| Median RSS growth while paused | 6.16 MiB | 17.59 MiB | 2.55 MiB |
| Median CPU consumed in 1 s | 0.88 s | 0.95 s | 0.06 s |
| Attempted writes before cancel | 20,102 | 20,102 | 1,866 |
| Pressure observations | 5,000 | 5,000 | 883 |
| Stream cancellation callbacks | unavailable | unavailable | 102 total |

The plain Web stream's `desiredSize` stopped production and its `cancel`
callback ran for every disconnected stream. It used 15–16x less CPU in this
adversarial second because it did not compress work the client could not
consume. Both zlib variants completed all compression rapidly into intervening
buffers despite downstream pressure. The native-zlib fixture's
`Readable.toWeb` wrapper did not expose a reliable response-cancellation hook;
production code would require an explicit abort bridge rather than the wrapper
assignment attempted here.

This changes the priority of the native adapter. Direct Web streams are not
just an RPS optimization; their pressure and cancellation interface matches
Seon's latest-wins policy more directly. A production pull source should retain
only the newest rendered event when `desiredSize <= 0`, enqueue it on `pull`,
and unsubscribe on `cancel`. It must not use the benchmark's 1 ms polling.

## Render, serialize, compress, and fanout attribution

Seon's existing mechanism already shares database observation, dirty-unit
selection, render, and HTML/SSE serialization for equivalent views. It then
writes the shared event into one stateful gzip stream per connection. The
native HTTP adapter cannot reduce query/render/serialization CPU by itself.
It can eliminate Node request/response adaptation, static-file copies, and the
per-feed gzip layer.

The isolated fanout probe used 100 identical 4,135-byte events and 100 feeds
(10,000 logical deliveries):

| Mode | Median wall time | Median process CPU |
|---|---:|---:|
| One stateful sync-flush gzip per feed | 20.62 ms | 269.40 ms |
| One independent gzip member per shared event | 0.49 ms | 0.99 ms |
| Shared uncompressed bytes | 0.008 ms | 0.010 ms |

The independent compressor mode consumed more CPU than wall time because zlib
used parallel native work. This is exactly the shape that can produce machine-
wide CPU spikes as tabs/agents multiply even while the JS thread appears
healthy. Compressing once per shared event was roughly 272x cheaper in CPU in
this deliberately compressible fixture; no compression was cheaper again.

Bun/Node zlib successfully decoded concatenated independent gzip members, so
compress-once fanout is technically plausible: encode each complete SSE event
as a self-contained gzip member and send the same member bytes to every
equivalent feed. It is **not graduated**. Browser streaming decompression of
concatenated members, proxy behavior, header semantics, latency, and the size
penalty versus one continuous stream require real-browser proof. Per-feed
latest-wins divergence makes sharing bytes from one continuous stateful gzip
stream unsafe; after one client drops an event, its decompressor state no
longer matches. Self-contained members avoid that state coupling.

## Connection topology

One browser connection multiplexing every visible agent/tab could reduce idle
connection and heartbeat overhead, but it would couple unrelated views under
one slow consumer and require client-side routing/reconnection semantics.
That is a larger protocol change and is not justified before taking the much
simpler gains: shared render/serialization already exists, direct plain streams
are about 31 KiB per connection in this fixture, and browsers cap connections
per origin in ways that HTTP/2 would address differently.

Do not introduce one feed per render unit or a second notification system.
Keep one page feed, one shared subscription for equivalent views, and
per-connection latest-wins delivery. Revisit a cluster-multiplexed feed only if
real workload evidence shows socket/heartbeat overhead remains material after
plain native streams and dormant-cluster admission.

## Caveats and next proof

- The client was Bun executing `node:http`, so client overhead limits absolute
  throughput. It was identical across variants and suitable for relative
  comparison, but a faster external load generator is required for saturation.
- RSS came from macOS `ps`; it is resident size, not `phys_footprint`, and
  includes allocator/JIT high-water effects. Production A/B proof must retain
  macOS physical footprint plus RSS and process-tree totals.
- The first-event measurement stops at first network bytes. The gzip variants'
  first chunk was compressed; browser-visible decompressed event latency still
  needs browser/server-side validation.
- The fixture isolates transport and compression. It does not include reitit,
  Ring conversion, Datahike observation, hiccup rendering, Datastar patch
  serialization, Shadow reload, or the full pod heap.
- The flood payload is synthetic and highly compressible. It intentionally
  exposes wasted compressor work under pressure; a representative recorded
  morph distribution must establish the magnitude in Seon.
- Bun documents a 10-second default server idle timeout. The fixture set
  `idleTimeout: 0`; a production adapter should call `server.timeout(req, 0)`
  only for long-lived feeds so ordinary requests retain protection.

The next falsifier is a thin native host adapter exercised against the existing
router and recorded real morph bytes, with Node-adapter and native modes over
the identical compiled artifact. Measure complete page/feed parity, first
decompressed morph latency, 1/10/100/1,000 feeds, physical footprint, render
CPU, compression CPU, cancellation, and a slow-client latest-wins trace. If
uncompressed loopback wins without bandwidth pressure, delete per-feed gzip
rather than maintaining it as tradition.
