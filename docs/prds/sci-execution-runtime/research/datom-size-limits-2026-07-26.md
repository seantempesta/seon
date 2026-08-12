---
type: research
status: complete
tags: [prd, database, architecture, agent]
---

# Datom string-size limit and render-snapshot storage

## Verdict

**There is no 64 KiB or 65k-character limit on a Datahike
`:db.type/string`; the 65,536 found in the storage path is Fressian's reusable
UTF-8 chunk size, and the writer loops until the whole string is encoded.**
Both an in-memory database and the maintained file backend accepted and read
back 10k, 100k, 1M, and 10M ASCII-character strings; a separate JVM reopened
the file database and recovered all four exact lengths.

For O14, commit a cardinality-one, unindexed, no-history **ref to a
content-addressed blob**, not the rendered HTML itself. The blob is the full
content; the datom is the current queryable projection. This is not a
correctness workaround for 65k—there is no such boundary. It is the honest
storage choice because Datahike's primary indexes duplicate an unindexed value,
PersistentSortedSet pages by datom count rather than byte size, and the
repository already owns the large-content lifecycle in `my.blob`.

## Dependency ledger and conditions

The source and probe used the checkout's maintained writer dependency closure:

- Datahike `caf526850084`
  (`reference-code/datahike`; selected by `deps.edn:25-28`).
- Konserve `b5c99bc02a71`
  (`reference-code/konserve`; selected by `deps.edn:29-35` and by Datahike at
  `reference-code/datahike/deps.edn:4-7`).
- PersistentSortedSet 0.4.137,
  `e1a17bbe767c7801e67407c81f64efabfd2f1601`
  (`reference-code/persistent-sorted-set`;
  `reference-code/datahike/deps.edn:12-20`).
- Konserve's file serializer dependency is
  `org.clojure/data.fressian` 1.0.0, which selects Fressian 0.6.6
  (`reference-code/konserve/deps.edn:3`; the exact Maven POM supplies the
  transitive version).

Probe host and JVM:

- Apple M5 Max, 128 GiB RAM, Darwin 25.5.0 arm64.
- OpenJDK 26.0.1, `-Xmx4g`.
- One observation per size, after database creation and schema installation.
  These are boundary falsifiers, not a throughput benchmark.
- Values were newly allocated strings of repeated ASCII `x`; character and
  UTF-8 byte counts are therefore equal. Real HTML containing non-ASCII text
  may occupy more UTF-8 bytes.
- Attribute shape:
  `:db.type/string`, cardinality one, unindexed, `:db/noHistory true`;
  database `:keep-history? true`.
- File path:
  `tmp/datom-size-probe-file-20260726`.
- `d/transact` used its synchronous commit path
  (`reference-code/datahike/src/datahike/writing.cljc:423-429`).

The measured transaction starts after allocating the input string and ends
when `d/transact` returns. Read-back equality was checked immediately. The file
connection was released and reopened once in-process, then a second
`clojure -M:writer` JVM connected and checked the stored values again.

## What the source actually permits

### Datahike has a type check, not a length check

Datahike defines `:db.type/string` as exactly `string?`
(`reference-code/datahike/src/datahike/schema.cljc:20-33`). Transaction
validation resolves the attribute's value type and calls that spec; it does not
inspect string length
(`reference-code/datahike/src/datahike/schema.cljc:228-234`,
`reference-code/datahike/src/datahike/db/transaction.cljc:32-51`).

An unindexed datom is still inserted in two primary indexes:

- always EAVT;
- always AEVT; and
- AVET only when `indexing?` is true.

That is explicit at
`reference-code/datahike/src/datahike/db/transaction.cljc:446-451`. Therefore
“unindexed” avoids the third full-value primary index; it does not make the
value exist once.

For a replacement, `:db/noHistory true` makes `keep-history?` false
(`reference-code/datahike/src/datahike/db/transaction.cljc:429-442`), so the
old/new datoms are not added to temporal EAVT/AEVT/AVET. Without no-history,
the replacement writes both the removing and replacement datoms into the
temporal indexes
(`reference-code/datahike/src/datahike/db/transaction.cljc:459-474`).

That temporal guarantee is narrower than immediate disk reclamation. The file
database uses immutable copy-on-write index roots and, by default, a commit
graph. Storage GC retains the branch head and follows parent commits inside
its configured history window; its default invocation erases no snapshots
(`reference-code/datahike/src/datahike/gc.cljc:22-81`,
`reference-code/datahike/src/datahike/gc.cljc:83-118`). Thus no-history stops
Datahike temporal-index accumulation, but old serialized tree nodes can remain
reachable through retained commit snapshots (and otherwise remain until GC).

### The index page contains the full strings

Datahike encodes each Datom as `[e a v tx added]`, including `v`, inside the
PersistentSortedSet node serializer
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:513-524`).
PersistentSortedSet's serialized leaf shape is `{:keys <elements> ...}`—the
whole leaf, not externalized values
(`reference-code/persistent-sorted-set/doc/serialization.md:66-82`).

The default branching factor is 512 datoms
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:471-484`).
Its normal B-tree split code uses element count, not serialized byte size
(`reference-code/persistent-sorted-set/src-clojure/org/replikativ/persistent_sorted_set.clj:108-125`).
A leaf can therefore contain hundreds of large strings. Reading any member
restores and LRU-caches the whole node
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:409-444`).

This is the important performance boundary for HTML snapshots: the database
has no small-string restriction, but a large string makes the index page large
for every other datom sharing it.

### Konserve uses Fressian, not CBOR, for this path

Konserve supports String, Fressian, and (on the JVM) CBOR serializers
(`reference-code/konserve/src/konserve/serializers.cljc:61-103`), but the file
backend defaults to `:FressianSerializer`, null compression, and null
encryption
(`reference-code/konserve/src/konserve/filestore.clj:662-703`). Datahike
replaces that Fressian instance with the canonical PersistentSortedSet and
Datom handlers
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:541-570`).
CBOR is not the primary-index encoding in the probed configuration.

Konserve's 20-byte header carries metadata size as a four-byte big-endian
integer, not a 16-bit length
(`reference-code/konserve/src/konserve/impl/storage_layout.cljc:10-47`,
`reference-code/konserve/src/konserve/impl/storage_layout.cljc:65-84`).
The value occupies the rest of the file; the file backend writes the full byte
array and reads it into one `ByteBuffer`
(`reference-code/konserve/src/konserve/filestore.clj:251-264`,
`reference-code/konserve/src/konserve/filestore.clj:321-338`,
`reference-code/konserve/src/konserve/filestore.clj:376-418`).

The exact transitive Fressian 0.6.6 source explains the misleading number.
`org/fressian/FressianWriter.java:113-139` sizes a reusable UTF-8 buffer to at
most 65,536 bytes, emits `STRING_CHUNK` when more input remains, and repeats
the loop while `stringPos < s.length()`. **65,536 is a chunk size, not a
string limit.**

There are still ordinary platform ceilings, just not a 65k database rule:
Java `String` and arrays are integer-indexed, and Konserve materializes one
serialized node into a single byte array/`ByteBuffer`. A serialized node near
the JVM's roughly 2 GiB array/`ByteBuffer` ceiling, or one that exhausts the
heap earlier, will fail. The source exposes no smaller per-datom maximum.

## Live probe

The probe created these two schema rows:

```clojure
[{:db/ident :probe/id
  :db/valueType :db.type/string
  :db/cardinality :db.cardinality/one
  :db/unique :db.unique/identity}
 {:db/ident :probe/value
  :db/valueType :db.type/string
  :db/cardinality :db.cardinality/one
  :db/noHistory true}]

```

Each size was transacted on its own entity, pulled back by `:probe/id`, and
compared with the input. The file database was then released and reopened.

| characters | in-memory transact | file transact | immediate read-back |
|---:|---:|---:|---|
| 10,000 | 2.593 ms | 45.081 ms | 10,000, equal |
| 100,000 | 1.982 ms | 45.437 ms | 100,000, equal |
| 1,000,000 | 2.113 ms | 52.523 ms | 1,000,000, equal |
| 10,000,000 | 2.885 ms | 62.507 ms | 10,000,000, equal |

The in-memory backend does not serialize values
(`reference-code/konserve/src/konserve/memory.cljc:95-96`), so that half
falsifies a Datahike type/index cap. The file half additionally exercises the
Fressian/Konserve/PersistentSortedSet path.

Separate-JVM durable reopen:

| operation | measured time | evidence |
|---|---:|---|
| connect to existing file database | 15.371 ms | `database-exists?` true |
| first cold pull, 10M value | 66.658 ms | 10,000,000 chars, first/last `x` |
| subsequent pull, 1M value | 0.239 ms | 1,000,000 chars, first/last `x` |
| subsequent pull, 100k value | 0.138 ms | 100,000 chars, first/last `x` |
| subsequent pull, 10k value | 0.094 ms | 10,000 chars, first/last `x` |

An earlier release/reconnect in the writer JVM deliberately pulled the 10k
value first. That first pull took 62.612 ms; the other three pulls were
0.105–0.238 ms. This is consistent with the source: all four datoms fit in one
count-bounded leaf, so touching the smallest value deserializes and caches the
leaf containing all 11.11M characters.

The file database occupied 24,268 KiB. Its largest data files appeared in
pairs—one EAVT node and one AEVT node:

| serialized leaf generation | bytes per file | count |
|---|---:|---:|
| after 10k | 10,542 | 2 |
| after 100k total | 110,610 | 2 |
| after 1.11M total | 1,110,735 | 2 |
| after 11.11M total | 11,111,409 | 2 |

This is direct evidence of both costs: a large unindexed string is present in
two primary index leaves, and retained immutable commit generations keep prior
leaf blobs. No 65k failure occurred.

## Existing Seon large-content mechanism

The repository has already ruled and built the three-tier storage shape:
datoms are projections, blobs are persistent full content, and process-local
values are volatile. The code matches the architecture.

- `seon.content-hash/sha-256` defines one lowercase SHA-256 identity over exact
  UTF-8 bytes (`src/seon/content_hash.cljc:15-29`).
- `my.blob` registers a database projection keyed by identity
  `:my.blob/hash`, plus token estimate, media hint, and recorded time
  (`src/my/blob.cljc:24-34`).
- The JVM leaf writes the content once under the hash, fsyncs the file, applies
  an atomic rename, and fsyncs the shard directory
  (`src/my/blob/host.clj:121-153`).
- Only after publication does it transact the small
  `{:my.blob/hash ... :my.blob/tokens ... :my.blob/at ...
  :my.blob/media ...}` projection
  (`src/my/blob/host.clj:155-199`).
- `my.blob/put!` explicitly teaches callers to retain the hash and not carry
  content in datoms; `text` provides bounded pages and `stat` budgets from the
  database projection without reading the file
  (`src/my/blob.cljc:289-310`, `src/my/blob.cljc:431-439`).
- The current archive is append-only and has no garbage collection
  (`src/my/blob.cljc:334-344`). Unique render versions therefore still
  accumulate once each; blob placement does not make retention free.
- Existing turn capture connects `:seon.agent.turn/prompt-blob` and
  `:seon.agent.turn/reply-blob` as cardinality-one `:seon.db/ref` attributes
  (`src/seon/agent/turn.cljc:21-25`,
  `test/seon/agent/jvm_runtime_schema_test.clj:102-133`).

The datom-side shape for a rendered snapshot should follow that precedent:

1. `my.blob/put!` returns the hash and ensures the blob projection exists.
2. The canvas/surface entity commits one cardinality-one, unindexed,
   no-history `:seon.db/ref`.
3. Transaction data can name the target by lookup ref
   `[:my.blob/hash hash]`; the stored value is the blob entity ref, not the
   HTML string and not a second raw-hash attribute.

## O14 recommendation

Use **blob + committed ref fact** for a rendered canvas snapshot in the stated
tens-to-hundreds-of-KiB range.

The three strongest reasons are:

1. **Database working-set amplification is structural.** Even unindexed HTML
   lives in both EAVT and AEVT, and count-bounded leaves can co-locate up to
   512 large datoms. The cold probe showed that touching the 10k member loaded
   the leaf containing 11.11M characters. A 64-byte blob identity keeps
   ordinary database reads and the node cache small; full HTML is loaded only
   when demanded.
2. **No-history is not “one copy on disk.”** It correctly prevents temporal
   index rows, but immutable commit roots and GC policy are a separate
   retention axis. The probe produced paired, cumulative leaf generations and
   used 24,268 KiB for 11.11 MB of current ASCII content. The append-only blob
   archive still retains each unique render, but stores its full bytes once
   rather than in two primary indexes and their retained tree generations;
   identical snapshots deduplicate, and database generations contain only
   small refs/projections.
3. **The exact lifecycle already exists.** SHA-256 identity, idempotent
   publication, fsync + atomic rename, integrity verification, queryable token
   and media projections, bounded reads, and consumer ref attributes are all
   maintained code. A direct HTML fact would bypass the repository's one
   established full-content mechanism for no correctness benefit.

A direct cardinality-one no-history string is technically valid and is the
right pattern for bounded, ephemeral presentation prefixes that are retracted
at terminal settlement. It wins one fewer indirection and one fewer archive
read. A durable exact HTML snapshot is different: it is full content, and at
the measured sizes those small conveniences do not outweigh the database
index/cache/retention costs.
