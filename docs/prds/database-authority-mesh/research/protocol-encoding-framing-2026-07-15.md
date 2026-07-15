---
type: research
status: completed
tags: [research, prd, database, flow, agent]
---

# Protocol encoding and framing — 2026-07-15

## Conclusion without a production decision

Keep Transit JSON as the conformance baseline and first native-socket encoding.
Its JVM performance was the best balanced result that already round-trips the
required Clojure values in both CLJ and CLJS. First remove framing copies, add
bounded semantic pages/chunks, and share completed encoded bytes. Do not change
encoding merely because a binary format is smaller.

Transit MessagePack is not a Bun candidate: the checked-in Transit JavaScript
implementation accepts only `json` and `json-verbose`, and its own README says
it does not support MessagePack. On the JVM it halved the datom-page bytes but
decoded that page about five times slower and allocated about twelve times more
than Transit JSON.

Schema-defined CBOR remains the strongest future Rust/mobile candidate, not the
generic Clojure-object encoding measured here. The existing `clj-cbor` path did
not exactly round-trip the test corpus: `Date` became `Instant` and a Clojure
`BigInt` became `Long`. A portable CBOR protocol needs explicit semantic tags or
schema transformations and cross-language fixtures before it can compete.

This report sets evidence thresholds and preserves the encoding decision for
Sean.

## Source and dependency ledger

- Seon: `9192c0bf8f8c309ecf9c213dc3c48e453271f753` at the start
  of this lane.
- Current transports:
  `src/seon/db/transport/uds.cljs:50-143` and
  `src/seon/db/transport/uds.clj:62-143`.
- Transit CLJ 1.0.333 and Transit CLJS 0.8.280 are selected in `deps.edn`.
- Checked-in Transit sources:
  `reference-code/transit-clj`, `reference-code/transit-cljs`, and
  `reference-code/transit-js`, rooted at Seon commit
  `be30f4206445a186c7fbbfa6dc608e9abee25846`.
- `reference-code/transit-js/src/com/cognitect/transit.js:62-131` permits only
  JSON/verbose JSON; its integer and UUID representations are in
  `reference-code/transit-js/src/com/cognitect/transit/types.js:82-356`.
- Datahike already defines Transit handlers for Datom and internal database
  values at `reference-code/datahike/src/datahike/transit.cljc:13-78`. The
  authority protocol should continue sending ordinary datom vectors and
  namespaced data, not remote Datahike objects.
- Datahike selects `mvxcvi/clj-cbor` 1.1.1 for migration/CLI use. Its exact
  source is not checked into `reference-code`; that absence prevents selecting
  it as a maintained protocol dependency before mirroring and audit.
- No Bun MessagePack or CBOR implementation is installed or checked into this
  repository.

Transit documents that its JavaScript implementation currently supports JSON,
not MessagePack ([Transit tour](https://cognitect.github.io/transit-tour/)). For
a possible Rust path, Ciborium exposes Serde CBOR plus a dynamic `Value` model
and warns that packed field encodings are fragile across implementations
([Ciborium documentation](https://docs.rs/ciborium/latest/ciborium/)). These are
capability signals, not dependency selections.

## Corpus and method

An isolated OpenJDK 26/Clojure 1.12 process used the repository's `:writer`
dependency basis. The disposable script lived under `tmp/` and was removed.
No database or Seon lifecycle ran.

The values deliberately included:

- fully namespaced keyword keys and values;
- a namespaced symbol and Datalog query form;
- sets, vectors, maps, booleans, strings, UUIDs, and instants;
- a value above JavaScript's safe integer boundary;
- coordinate-shaped data and an error envelope;
- a 1,000-entity pull-shaped result with 256-byte descriptions; and
- 4,096 protocol datom vectors `[e a v tx added?]`.

Four JVM codecs were measured:

- current Transit JSON;
- Transit MessagePack;
- `clj-cbor`'s generic encode/decode; and
- EDN UTF-8 as the debuggable textual control.

Each small value ran for 2,000 iterations and each large value for 50 after 20
warmups. CPU numbers are elapsed microseconds per call, not JMH-quality scores.
Allocations came from the current thread's JVM allocated-byte counter. The
single-run results are directional and should be repeated before implementation.

Command:

```bash
clj -M:writer tmp/encoding-bench.clj
```

## JVM results

### Bytes and exactness

| Workload | Transit JSON | Transit MsgPack | CBOR | EDN |
|---|---:|---:|---:|---:|
| Small query | 797 | 676 | 691* | 669 |
| Large pull | 331,447 | 302,089 | 438,053* | 428,232 |
| Datom page | 132,592 | 65,650 | 250,021* | 279,919 |
| Error | 1,070 | 864 | 1,042* | 1,022 |

`*` CBOR did not exactly round-trip the original value. Transit JSON,
Transit MessagePack, and EDN did.

MessagePack saved only 9% for the large pull but 50% for repetitive datom
vectors. Transit JSON's caching already made the pull 23% smaller than EDN and
the datom page 53% smaller. Generic CBOR was larger than Transit JSON on both
large shapes because it did not reproduce Transit's repeated-value caching.

### Encode/decode CPU

| Workload | Codec | Encode | Decode |
|---|---|---:|---:|
| Small query | Transit JSON | 24.2 us | 15.5 us |
|  | Transit MsgPack | 18.0 us | 19.3 us |
|  | CBOR | 32.4 us | 18.7 us |
|  | EDN | 16.5 us | 39.4 us |
| Large pull | Transit JSON | 929 us | 403 us |
|  | Transit MsgPack | 964 us | 1,337 us |
|  | CBOR | 1,060 us | 1,232 us |
|  | EDN | 2,841 us | 4,674 us |
| Datom page | Transit JSON | 831 us | 514 us |
|  | Transit MsgPack | 916 us | 2,506 us |
|  | CBOR | 1,348 us | 836 us |
|  | EDN | 1,635 us | 4,767 us |
| Error | Transit JSON | 5.9 us | 5.4 us |
|  | Transit MsgPack | 6.9 us | 9.7 us |
|  | CBOR | 6.9 us | 5.7 us |
|  | EDN | 5.9 us | 21.3 us |

Transit JSON decoded the large pull three times faster than Transit MessagePack
and the datom page 4.9 times faster. MessagePack's smaller datom page therefore
did not translate into lower local end-to-end cost.

### Allocated bytes per call

| Workload | Codec | Encode | Decode |
|---|---|---:|---:|
| Small query | Transit JSON | 45.7 KiB | 33.2 KiB |
|  | Transit MsgPack | 34.1 KiB | 94.5 KiB |
|  | CBOR | 8.7 KiB | 20.4 KiB |
|  | EDN | 15.8 KiB | 25.8 KiB |
| Large pull | Transit JSON | 3.96 MiB | 1.29 MiB |
|  | Transit MsgPack | 4.41 MiB | 19.09 MiB |
|  | CBOR | 3.47 MiB | 4.25 MiB |
|  | EDN | 4.81 MiB | 6.64 MiB |
| Datom page | Transit JSON | 2.82 MiB | 2.33 MiB |
|  | Transit MsgPack | 2.68 MiB | 28.58 MiB |
|  | CBOR | 3.73 MiB | 3.05 MiB |
|  | EDN | 5.79 MiB | 9.44 MiB |
| Error | Transit JSON | 50.6 KiB | 33.0 KiB |
|  | Transit MsgPack | 37.8 KiB | 117.3 KiB |
|  | CBOR | 14.5 KiB | 24.1 KiB |
|  | EDN | 21.1 KiB | 30.7 KiB |

The current encoding is not cheap: one large-pull encode allocated about 4 MiB
to produce 324 KiB. That makes encode-once sharing a higher-confidence first
optimization than selecting a new format. Transit MessagePack's large decode
allocation is disqualifying for this path even before its missing JS support.

## Value-law findings

### Transit JSON

The entire JVM corpus round-tripped. Transit has defined tags/representations
for keyword, symbol, set, UUID, instant, and integers outside JavaScript's safe
number range. The checked-in JavaScript source returns its own 64-bit integer or
tagged big-integer value where needed, so conformance must compare semantic
values rather than require every decoded host class to match.

CLJS application schemas must still prevent accidental coercion after decode.
The existing writer already repairs some numeric types lost at the JavaScript
boundary; the new protocol fixtures should make integer, float, big integer,
UUID, and instant laws explicit rather than relying on repair.

### Transit MessagePack

JVM fidelity was correct, but Transit JS has no MessagePack reader or writer.
Adding an unrelated MessagePack implementation would not automatically
understand Transit's tags and cache codes. It would create a new codec project,
not flip a format flag.

### Generic CBOR

Keywords, symbols, sets, and UUIDs survived `clj-cbor`, but host-class semantics
did not: `java.util.Date` decoded as `java.time.Instant`, and a Clojure `BigInt`
within signed 64-bit range decoded as `Long`. Those may be acceptable under a
new semantic protocol, but they are not transparent replacement behavior.

A portable CBOR design must specify each wire value independently of Clojure:

- namespaced keywords and symbols;
- sets versus vectors;
- UUID and instant representation;
- signed/unsigned 64-bit and arbitrary integers;
- datom tuples and entity/reference values;
- errors and unknown extension tags; and
- canonical/deterministic form where hashes or fixtures require it.

Rust Serde and Swift Codable integrations make CBOR attractive, but generic
host-object derivation is exactly what should be avoided. Malli/schema-owned
transformations plus byte fixtures must define the wire contract.

### Plain JSON and EDN

Plain JSON lacks keyword, symbol, set, UUID, instant, and big-integer semantics
without a tagged/schema transformation. Once those are added, it recreates a
subset of Transit. EDN is excellent for captured fixtures and debugging, but its
large decode cost and weak native mobile support make it unsuitable as the hot
data plane.

## Linear framing contract

The four-byte unsigned big-endian payload length can remain. It is simple across
JVM, Bun, Rust, and mobile and is not the measured cost. The implementation must
change:

- receive into a chunk queue/cursor and copy each payload at most once;
- never repeatedly concatenate the retained prefix;
- encode payload and header without concatenating them solely for `write`;
- preserve the exact unwritten suffix after partial native writes;
- resume only on `drain` and cap queued bytes per session and database;
- reject over-limit lengths before allocating the payload;
- keep request identity and chunk sequence in the semantic envelope; and
- collect frame, payload, queued, and decoded-size metrics separately.

The present CLJS path creates a Transit string, UTF-8 buffer, header, and a
concatenated frame, then repeatedly concatenates receive chunks. The JVM path
creates a `ByteArrayOutputStream`, copies it to a byte array, and allocates a
second combined `ByteBuffer` for publisher frames. Those copies must be removed
before comparing codecs over native sockets.

## Paging and chunking

Raising the current 16 MiB frame limit is not the answer. Large query surfaces
should have semantic pages:

- datoms, index-range, seek, replay, and pull-many return bounded item/byte pages;
- each page carries request identity, sequence, exact database coordinate,
  continuation, and completion state;
- cancellation stops future page production and releases retained values;
- one page is independently framed and encoded; and
- the authority admits by estimated/observed work and bytes, not item count.

Transport fragmentation may split any frame across socket reads; it does not
reduce the memory needed to decode that frame. Arbitrarily byte-chunking one
huge Transit document likewise does not give consumers useful incremental
values. Semantic pages do.

Initial experiment sizes should be 64, 256, and 1,024 KiB, selected separately
for latency-sensitive queries and bulk export. The winning size is where
throughput has flattened without violating cancellation latency, event-loop
responsiveness, queued-byte limits, or modest-client memory.

## Shared encoded bytes

When several clients request the same cacheable result at the exact same
immutable database value, encode it once after Datahike single-flight completes.
The encoding-cache key must include:

- exact Datahike database-value identity;
- normalized operation/query and non-database arguments;
- page/continuation identity;
- protocol and codec version; and
- any capability that changes the response shape.

One immutable JVM byte array can back independent `ByteBuffer` views with their
own cursors. A slow session retains only its view/reference and counts the full
payload against its byte budget. The last sender releases the in-flight
reference; completed encoded entries use the database-scoped weighted cache and
evict with the database value. Socket/Future objects never enter the cache.

For the measured 331,447-byte pull, encoding separately for 32 clients would
produce 10.11 MiB and perform about 29.7 ms of JVM encode CPU while allocating
roughly 126.6 MiB. One completed encoding is 0.32 MiB, about 0.93 ms CPU, and
about 4 MiB allocation before socket delivery. This theoretical fanout saving
is much larger than Transit MessagePack's 9% size reduction.

Sharing is valid only for exact completed bytes. Per-session request identity
belongs in a small frame envelope or separate header so it does not force body
re-encoding. That envelope design must remain simple and conformance-tested; do
not add a broker just to share bytes the authority already owns.

## Debuggability and conformance

Transit JSON remains inspectable as UTF-8 and has mature CLJ/CLJS value support,
though cache codes make it less friendly than verbose JSON. Development tools
should decode through the real codec and render EDN; production and development
must not use different semantic encodings.

Every candidate must pass the same byte-level corpus in JVM, Bun/CLJS, Rust, and
Swift before selection:

- valid values listed above;
- unknown keyword/symbol namespaces and extension tags;
- integer boundaries around 32-bit, 53-bit, 64-bit, and arbitrary precision;
- malformed/truncated/oversized frames;
- duplicate/unknown fields according to schema policy;
- fragmented input and partial output;
- page ordering, cancellation, stale coordinate, and reconnect;
- deterministic expected values independent of host collection classes; and
- forward/backward protocol and codec version behavior.

Malli owns semantic request/result validation. A binary codec is never trusted
as validation merely because it decoded.

## Options and thresholds for Sean

### Option A — Transit JSON plus framing/byte sharing

This has the smallest conceptual and compatibility cost. It is the required
baseline and currently the best-supported option.

Advance it unless profiling after linear framing and encode-once shows either:

- encode plus decode consumes at least 10% of end-to-end CPU at target density;
  or
- payload/queued bytes are the binding p95/p99 latency or memory limit.

### Option B — schema-defined CBOR for the whole protocol

Choose this only after exact Bun, JVM, Rust, and Swift fixtures pass and it
improves realistic end-to-end CPU or bytes by at least 25% without worse tail
latency or allocations. The 25% threshold pays for a new dependency, binary
debug tooling, tags, migrations, and conformance maintenance. Generic
`clj-cbor` results do not meet the correctness gate.

### Option C — Transit control plus one binary bulk projection

A specialized datom/index/export page could be justified if it delivers at
least 2x end-to-end throughput or half the retained/queued bytes on a measured
dominant workload. It must be a named protocol capability with one schema, not a
general second encoding selected opportunistically. This option costs the most
cognitive surface and should be last.

### Rejected for the current Bun boundary — Transit MessagePack

It lacks JavaScript support in the selected Transit implementation and had much
worse JVM decode CPU/allocation. Its datom compression result is a useful target
for a future schema-defined bulk representation, not a production option.

## Next proof

1. Implement only an isolated linear-framing/native-socket fixture for current
   Transit JSON; measure Bun and JVM encode/decode/copies end to end.
2. Couple exact Datahike single-flight output to one shared encoded body and
   repeat 1/8/32 fanout without a broker.
3. Measure 64/256/1,024 KiB semantic pages under cancellation and slow clients.
4. Mirror and audit the exact CBOR libraries proposed for JVM, Bun, Rust, and
   Swift; define the schema transformation and cross-language corpus.
5. Run the same density matrix with Transit JSON and schema-defined CBOR.
6. Bring the CPU/bytes/latency evidence and maintenance surface back to Sean
   before changing the protocol codec.

Encoding remains deliberately reversible because semantics, paging, request
identity, and codec version are data rather than socket or host-language types.
