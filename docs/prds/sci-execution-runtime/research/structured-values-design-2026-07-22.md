---
type: research
status: active
tags: [research, architecture, database]
---

# Structured shared values — fork design (2026-07-22 night)

Orchestrator-accepted staged plan. KEY FINDING: :db.type/tuple already
exists in the fork (8-element) — Stage 1 (S, Seon-only exposure) fixes the
small-ordered-value gap without fork surgery. Stage 0 probes FIRST: the
earliest unsettled contract is durable filestore cost at thousands of node
keys, not CPU. Stage 2 smallest DAG slice → Stage 3 O(diff) ops (100 MiB
leaf-mutation exit) → Stage 4 migration (EDN slots retire, blob goes
bytes-only) → Stage 5 derivation gear GATED on effect classification +
q28. Sequenced after the deletion; Stages 0-1 are small enough to
interleave.

# Grounded evidence

Read-only research completed; no files changed.

Source basis:

- Datahike fork: `c1c4c29382257317cd34e160df11985cb384f8a6`
- Konserve fork: `b5c99bc02a7175652a610324215288b78551801f`, pinned by `reference-code/datahike/deps.edn:4-7`
- Persistent Sorted Set: `0.4.137`, pinned at `reference-code/datahike/deps.edn:12-20`
- Proximum: `9846d3e79e1aee48474bc876d3d563d7137209c6`, pinned at `reference-code/datahike/deps.edn:98-100`
- Datahike pins Hasch `0.4.100`; Konserve declares Hasch `0.3.96` and Incognito `0.3.69` (`reference-code/datahike/deps.edn:1-7`; `$K/deps.edn:1-7`).

Here `$K` is `/Users/sean/.gitlibs/libs/org.replikativ/konserve/b5c99bc02a7175652a610324215288b78551801f`.

Neither Hasch nor Incognito source is present under `reference-code/`. Their internal canonicalization, digest truncation, collision properties, and tag-compatibility contracts are therefore **NOT GROUNDED**. The measurements below exercise their public APIs but do not replace a source audit.

## 1. The actual Datahike value-type seam

Datahike has no extensible value-type registry. Each type is a direct spec, and `:db.type/value` is a closed literal set (`reference-code/datahike/src/datahike/schema.cljc:11-55`). Attribute-reference databases additionally assign fixed system entity IDs to value types (`reference-code/datahike/src/datahike/constants.cljc:69-116`).

A new first-class type therefore touches at least:

- logical type spec and the closed type set;
- fixed system-schema ident/eid;
- old attribute-reference-store upgrade behavior;
- transaction validation and physical projection;
- index comparison;
- Fressian, Transit, EDN, remote Transit/EDN, and JSON;
- GC and audit;
- tests under both keyword-attribute and reference-attribute modes.

Fresh databases derive their system maps from those fixed constants, while restore replaces the fresh maps with stored ones (`reference-code/datahike/src/datahike/db.cljc:869-953`; `reference-code/datahike/src/datahike/writing.cljc:226-295`). Automatic adoption by old attribute-reference stores is **NOT GROUNDED** and needs an explicit migration test.

### Logical value versus stored value

Today transaction validation checks the incoming value and `transact-add` places that same value directly into the Datom (`reference-code/datahike/src/datahike/db/transaction.cljc:32-51`, `:775-800`).

The nearest precedent is `:db.secondary/only`: Datahike projects the primary value to a Hasch digest while retaining the original for the secondary and transaction report (`reference-code/datahike/src/datahike/db/transaction.cljc:371-426`, `:429-474`, `:574-606`). That is the correct neighborhood, but not reusable semantics: a structured DAG belongs in the primary Konserve store and the 100 MiB logical value must not leak into reports or secondary indexes.

Recommended physical value:

```clojure
#datahike/structured-ref
{:datahike.structured/codec :datahike.structured/v1
 :datahike.structured/kind  :map
 :datahike.structured/root  "<full canonical digest>"
 :datahike.structured/count 12345}
```

Equality and ordering should use `[codec kind root]`; `count` is checked metadata, not identity. The digest preimage must include codec version and collection kind.

### Index ordering

Every primary index eventually compares `Datom.v`, including EAVT and AEVT—not only AVET (`reference-code/datahike/src/datahike/datom.cljc:325-368`). Byte arrays already require explicit comparison handling (`reference-code/datahike/src/datahike/datom.cljc:262-292`).

Consequently:

- storing an ordinary map as `v` is invalid;
- “not AVET indexed” does not remove the ordering requirement;
- a custom root-reference type needs explicit cross-platform comparison;
- ordering by root digest is honest, but it is content-identity ordering, not logical map/vector ordering.

Use a full canonical digest encoded as a comparable string or explicitly compared byte sequence. Reusing PSS’s UUID address without first grounding Hasch’s truncation/collision contract is **NOT GROUNDED**.

### Query and pull semantics

Query, pull, and entity currently expose `Datom.v` directly (`reference-code/datahike/src/datahike/query/execute.cljc:559-573`; `reference-code/datahike/src/datahike/pull_api.cljc:197-243`; `reference-code/datahike/src/datahike/impl/entity.cljc:22-29`).

The correct initial behavior is therefore:

- `q`, `pull`, `entity`, `datoms`, history, and transaction reports return the small root reference;
- equality queries accept a root reference;
- full contents require `materialize`;
- partial contents require `value-at`/`value-page`;
- no implicit store I/O occurs inside ordinary Datalog evaluation.

Transparent materialization would recreate the 100 MiB frame problem. A lazy attached collection would require store lifetime, remote paging, equality, caching, and budget semantics; an existing such abstraction is **NOT GROUNDED**.

### Transaction construction must remain pure

`d/with` is explicitly an operation over an immutable database value (`reference-code/datahike/src/datahike/core.cljc:126-139`). DAG construction must therefore be:

```text
logical value
  → pure {root-ref, pending-nodes}
  → Datom contains root-ref
  → db-after carries pending nodes
  → commit publishes nodes
```

It must not perform Konserve writes during `transact-add`.

The authoritative writer receives full `tx-data` and applies it through `core/with` (`reference-code/datahike/src/datahike/writing.cljc:862-879`). Initial DAG construction belongs writer-side, but sending a fresh 100 MiB value still costs O(N) wire, traversal, and hashing. Structural sharing only reduces durable writes.

Actual O(changed path) behavior requires a later built-in operation such as:

```clojure
[:db.structured/assoc-in eid attr expected-root path value]
[:db.structured/vector-append eid attr expected-root value]
```

The current built-in operation set is closed (`reference-code/datahike/src/datahike/db/transaction.cljc:855-869`).

For initial values larger than Seon’s frame, the writer needs a staged upload protocol or temporary upload manifest. Its nodes remain unreachable until the root Datom commits; the manifest must itself be a temporary GC root or remain under the GC guard.

## 2. Storage engine and publication

### New codec, not PSS node semantics

PSS’s durable nodes are specifically sorted-set B-tree leaves and branches with keys, separator addresses, level, subtree count, measures, and buffered slots (`reference-code/persistent-sorted-set/src-clojure/org/replikativ/persistent_sorted_set/fressian.cljc:122-163`). Its root separates durable address/count metadata from runtime storage and comparator resolution (`reference-code/persistent-sorted-set/src-clojure/org/replikativ/persistent_sorted_set/fressian.cljc:269-349`).

Reuse these ideas:

- versioned plain-data node projection;
- root separated from runtime storage;
- lazy child restoration;
- content-addressed pending writes;
- consumer-installed Fressian handlers;
- audit by recomputing node addresses.

Do not reuse its exact node format as the general collection representation. Maps, vectors, and sets have different mutation and lookup semantics. PSS’s content-defined MST mode could make sorted sets history-independent, but it is explicitly experimental and unstable (`reference-code/persistent-sorted-set/doc/merkle-search-tree.md:1-9`). Default PSS shape is insertion-history-dependent (`reference-code/persistent-sorted-set/doc/merkle-search-tree.md:11-28`).

Recommended codec:

- map/set: deterministic Merkle HAMT keyed by canonical key digest, with explicit collision buckets containing canonical keys;
- vector: 32-way Merkle vector trie plus tail;
- nested collections: child references;
- small scalars: inline;
- large strings/byte sequences: chunked leaves;
- all node maps versioned and fully namespaced.

This provides history-independent maps/sets and position-determined vectors without requiring a total comparator over arbitrary Clojure keys.

### Hasch

PSS documents `hasch.fast/edn-hash` as byte-identical across JVM and JavaScript for its MST boundary calculation (`reference-code/persistent-sorted-set/doc/merkle-search-tree.md:53-63`). Datahike already uses Hasch-derived UUIDs for content-addressed PSS nodes (`reference-code/datahike/src/datahike/index/persistent_set.cljc:239-282`).

However, Hasch `0.4.100` source is absent. Before making it the permanent structured-value identity:

- vendor/audit its exact canonical encoding;
- prove CLJ/CLJS equivalence over every admitted scalar and map-key type;
- determine whether `uuid` truncates and what `b64-hash` contains;
- freeze that behavior behind the DAG codec version.

Until then, “Hasch `b64-hash` is a full collision-resistant canonical digest suitable as a permanent database identity” is **NOT GROUNDED**.

Incognito should only help register runtime serialization handlers. Konserve merges Clojure, explicit, and Incognito handlers (`$K/src/konserve/serializers.cljc:29-59`); it does not create graph persistence or GC reachability automatically.

### Atomic publication

Datahike already has the required publication discipline. PSS queues immutable `[address node]` writes (`reference-code/datahike/src/datahike/index/persistent_set.cljc:409-425`). Commit then writes pending nodes and metadata before the immutable commit record and mutable branch head (`reference-code/datahike/src/datahike/writing.cljc:467-552`).

Konserve’s ordered `multi-assoc` formalizes the same leaves-first, pointer-last rule: a torn prefix creates unreachable orphans, never a dangling published root (`$K/src/konserve/core.cljc:434-462`).

Therefore:

1. Add DAG nodes to Datahike’s existing pending publication set.
2. Publish them in the same primary Konserve store.
3. Await every DAG/index node.
4. Publish commit record.
5. Flip branch head last.

Multi-key atomicity is unnecessary.

### Batch and per-key overhead

The pinned memory store implements atomic `multi-assoc` as one atom update (`$K/src/konserve/memory.cljc:105-124`). IndexedDB implements backing multi-write/read (`$K/src/konserve/indexeddb.cljs:419-431`).

The JVM filestore does not implement `PMultiWriteBackingStore`; `DefaultStore` only enables multi-key writes when the backing implements it (`$K/src/konserve/impl/defaults.cljc:632-667`). Its ordinary path maps every logical key to one `.ksv` object, serializes a complete value, writes a temporary blob, optionally syncs it, atomically moves it, and optionally syncs the store (`$K/src/konserve/impl/defaults.cljc:44-53`, `:57-123`). Filestore defaults enable blob sync and use non-in-place writes (`$K/src/konserve/filestore.clj:685-695`).

So a 10k-node DAG means approximately 10k filestore objects and 10k publication operations today. A packed-node/segment backend may be required if real durable testing confirms that this dominates.

### Read-only CPU probes

Seven-run medians using representative 32-entry plain-data nodes:

| Operation | Median/node | 10k projected |
|---|---:|---:|
| Hasch UUID, DAG map leaf | 12.380 µs | 124 ms |
| Hasch UUID, DAG branch | 5.187 µs | 52 ms |
| Hasch UUID, vector node | 3.595 µs | 36 ms |
| Fressian preparation, leaf | 10.871 µs; 315.6 B | 109 ms; 3.16 MB |
| Fressian preparation, branch | 8.668 µs; 738 B | 87 ms; 7.38 MB |
| Memory Konserve, individual assoc | 2.531 µs | 25 ms |
| Memory Konserve, one multi-assoc | 0.438 µs | 4 ms |
| Jimfs filestore, leaf | 19.286 µs | 193 ms |
| Jimfs filestore, branch | 17.032 µs | 170 ms |

The probe used the pinned classpath and public `hasch/uuid`, Konserve preparation, `assoc`, and `multi-assoc` operations. Jimfs demonstrates one-file-per-key CPU/path overhead but excludes real disk latency and durable `fsync`.

Conclusion: hashing and serialization do not kill a 10k-node DAG—roughly 0.14–0.23 seconds combined for the measured branch/leaf shapes. Real durable filestore viability is **NOT GROUNDED** and is now the earliest unsettled performance contract.

## 3. History, GC, and audit

History already works at the root-reference level. Cardinality-one replacement writes prior values into temporal indexes when history is enabled (`reference-code/datahike/src/datahike/db/transaction.cljc:459-474`, `:554-572`). Old versions therefore retain old roots without copying their DAGs.

GC does not work automatically. Offline GC marks commit records, schema metadata, primary PSS roots, temporal PSS roots, and secondary roots, but never descends from `Datom.v` into another object graph (`reference-code/datahike/src/datahike/gc.cljc:22-81`). Konserve itself only sweeps against a supplied whitelist (`$K/src/konserve/gc.cljc:8-41`).

The smallest correct GC implementation must:

- find structured refs in every retained current snapshot;
- include temporal roots/history where configured;
- walk each DAG using a visited-digest set;
- add all reachable node keys to the Konserve whitelist;
- verify every node’s digest during audit.

A later performance optimization can persist a content-addressed structured-root manifest per database value, audited against Datoms. The first correctness slice should scan rather than introduce an unproven second authority.

Online GC only reclaims PSS addresses explicitly reported as freed and is disabled for multiple branches (`reference-code/datahike/src/datahike/online_gc.cljc:137-213`). DAG online reclamation is **NOT GROUNDED**; ship offline DAG GC first.

## 4. The ordered-collection gap

The premise needs narrowing: Datahike already has an ordered vector-valued type, `:db.type/tuple` (`reference-code/datahike/src/datahike/schema.cljc:33-55`).

It supports:

- whole-vector query results (`reference-code/datahike/test/datahike/test/tuples_test.cljc:110-134`);
- positional destructuring through `untuple` (`reference-code/datahike/src/datahike/query.cljc:606-621`; `reference-code/datahike/test/datahike/test/tuples_test.cljc:350-359`);
- homogeneous or heterogeneous declared element types;
- at most eight elements for homogeneous tuples (`reference-code/datahike/src/datahike/db/transaction.cljc:1006-1032`).

Seon currently fails to expose this: Malli `:vector`, `:set`, and `:sequential` become cardinality-many, losing order (`src/seon/db/internal.cljs:172-217`).

Therefore:

- Small bounded ordered values need a Seon schema-bridge change to existing `:db.type/tuple`, not a fork.
- A new whole-copy `:db.type/vector` is mechanically smaller than a DAG, but provides no structural sharing, no maps/sets, and no safe arbitrary heterogeneous comparator.
- A general DAG vector should be position-addressable through explicit value APIs, not position-indexed in Datalog initially.
- Datalog equality over a DAG value means root/content equality.

Whether upstream would accept `:db.type/vector` or `:db.type/structured` is **NOT GROUNDED**. The repository requires an accepted feature request before a feature PR and asks for use case, alternatives, integration tests, docs, and an ADR (`reference-code/datahike/.github/ISSUE_TEMPLATE/2-feature-request.yml:1-28`; `reference-code/datahike/.github/pull_request_template.md:1-18`; `reference-code/datahike/doc/contributing.md:17-49`).

The lowest-maintenance upstream strategy is:

1. expose tuple in Seon locally;
2. prove the DAG in our fork;
3. then discuss a generic value-codec/reachability seam upstream;
4. avoid proposing an opaque whole-vector type merely as an intermediate.

## 5. Derivation storage

Derivations belong in Seon above Datahike.

Datahike should know only structured roots and ordinary facts. Seon should store a descriptor such as:

```clojure
{:seon.derived/base-root         <root>
 :seon.derived/transformation    <function identity>
 :seon.derived/closure-digest    <transitive program/package digest>
 :seon.derived/arguments-root    <root>
 :seon.derived/database-value    <optional immutable basis>
 :seon.derived/semantics-version <runtime/codec version>
 :seon.derived/result-root       <optional memoized cache>}
```

Resolution should be explicit, publish the result through the normal DAG path, and memoize with a CAS/idempotency fence.

W3d1 records exact function source and its fingerprint and verifies that fingerprint during graduation (`src/seon/host/record.clj:122-161`; `src/seon/host/graduate.clj:101-125`). It does not fingerprint the complete call/dependency closure or prove determinism.

“Zero recorded effects” is also not proof of purity: it does not exclude clocks, randomness, mutable globals, environment reads, or unpinned callees. Derivation eligibility needs:

- a sealed capability-free execution surface;
- no time/random/environment/provider access;
- a transitive code and package digest;
- explicit arguments only;
- database reads prohibited or pinned to an immutable database value;
- reproducible failure values and resource limits.

Cheap falsifier: cache `A(base)` using only A’s source fingerprint while A calls B; change B without changing A. A cache hit proves the identity is incomplete.

Proximum-dependent transformations should initially be excluded. Its adapter only accepts numeric sequential embeddings/float arrays (`reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj:289-310`), and `force-branch!` still spans separate primary and Proximum stores without a grounded atomic recovery contract (`reference-code/datahike/src/datahike/versioning.cljc:339-398`; `reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj:388-459`).

## 6. Migration and q22/q28 interplay

The EDN-slot encoder currently converts mixed values to opaque strings with `pr-str`, while reads use `reader/read-string` (`src/seon/db/internal.cljs:229-258`; `src/seon/db.cljs:1421-1443`).

Existing attributes cannot simply change `:db/valueType`; Datahike rejects incompatible schema changes (`reference-code/datahike/src/datahike/schema.cljc:257-302`; `reference-code/datahike/src/datahike/db/transaction.cljc:913-933`).

Migration therefore requires:

1. new structured-valued attributes;
2. backfill from decoded legacy values;
3. dual-read: new first, legacy fallback;
4. cutover of writes;
5. retained legacy decoding while old history/branches remain;
6. later removal under an explicit history policy.

The current decoder’s reliance on the current Malli registry is unsafe during migration. Legacy decoding should be keyed by installed/known legacy attributes, not by the new registered form.

`my.blob` is not currently a true binary API: it accepts, hashes, reads, and writes UTF-8 strings (`src/my/blob.cljs:27-35`, `:211-214`, `:265-282`, `:386-409`). Binary-only blob storage therefore needs a separate bytes/stream API migration.

A small DAG root does dissolve the “one enormous attribute crosses the wire” part of q22, because Seon currently Transit-encodes complete messages before enforcing the frame bound (`src/seon/db/transport/uds.cljc:210-247`; `src/seon/db/transport/uds.cljs:178-206`). It does not dissolve:

- large row counts;
- grouped program acquisition;
- maps containing thousands of roots;
- deliberate full materialization;
- index/query paging generally.

q28 remains orthogonal. Keeping DAG nodes in Datahike’s primary Konserve store avoids creating another cross-store publication boundary; Proximum still has its own branch/commit recovery problem.

## Cost model

Assumptions: deterministic 32-way tries, 4–16 KiB target serialized nodes, explicit node/path reads.

| Case | Expected durable write | Read behavior |
|---|---|---|
| 100 MiB map, one leaf changed through DAG-native `assoc-in` | One leaf plus roughly 4–6 ancestors: approximately 20–110 KiB | Cold lookup: roughly 4–6 node reads; full materialization: all nodes |
| Same mutation submitted as a fresh ordinary 100 MiB map | Similar deduplicated durable bytes, but O(100 MiB) wire/traversal/hashing | Same stored result |
| Append-heavy vector | Tail leaf plus path; approximately 15–100 KiB/append depending element and node size | `nth`: trie height; sequential scan benefits from leaf prefetch |
| Deep single-child tree | Every ancestor changes: O(depth), potentially near whole-tree bytes | O(depth) reads |
| Initial 100 MiB value | All unique nodes; approximately 6k–26k nodes at 16–4 KiB targets | Full cold read remains approximately 100 MiB plus per-key overhead |

These are design estimates, not measured end-to-end results. Compression, Fressian shape, actual element sizes, cache behavior, and durable filestore latency are **NOT GROUNDED**.

The key distinction is:

> Content addressing can make bytes written O(changed nodes). It does not make ingestion or mutation CPU O(diff) unless the operation starts from an existing DAG root and changes paths directly.

## Riskiest assumptions and cheapest falsifiers

1. **10k durable Konserve keys are viable.**  
   Run the measured codec on the real configured filestore with sync enabled at 4, 16, and 64 KiB node targets. Measure commit latency, bytes, file count, cold reopen, and GC. Compare one-file-per-node against a packed immutable segment prototype.

2. **The implementation really achieves O(diff), not only write deduplication.**  
   Execute the prior 100 MiB mutation falsifier twice: fresh full-map transaction versus base-root/path operation. Record request bytes, hashing CPU, node reads, nodes published, and durable bytes.

3. **Canonical identity and reachability survive every lifecycle.**  
   Build identical values in different insertion orders on JVM and CLJS; require identical roots. Then commit v1/v2, branch, retain history, inject crashes after arbitrary node writes, cold reopen, run offline GC, and prove every retained version materializes while only unreachable nodes disappear.

## Staged recommendation

### Stage 0 — decisive probes, S

No production type yet.

- Vendor/audit Hasch `0.4.100` and Incognito `0.3.69`.
- Prototype the canonical map/vector codec as a pure `{root pending-nodes}` function.
- Run the real-filestore 10k-key probe.
- Run cross-platform canonicality and crash-prefix tests.
- Prove `d/with` performs zero storage writes.

Exit: canonical identity, durable cost, and publication behavior are measured rather than assumed.

### Stage 1 — bounded ordered values, S

Seon-only:

- expose explicit homogeneous/heterogeneous `:db.type/tuple`;
- retain Datahike’s eight-element contract;
- test `untuple`, pull, reopen, and CLJ/CLJS behavior.

This fixes the honest small ordered-value gap without pretending to solve large structures.

### Stage 2 — smallest honest DAG slice, M/L

In our Datahike fork:

- provisional `:db.type/structured`; upstream name **NOT GROUNDED**;
- cardinality-one map and vector only;
- custom comparable root ref;
- pure pending-node projection;
- same-store nodes-before-head publication;
- root refs returned by all read surfaces;
- explicit bounded `materialize`, `value-at`, and `value-page`;
- Fressian/Transit/EDN/JSON coverage;
- offline GC and audit traversal;
- reject uniqueness, component, cardinality-many, AVET opt-in, and all secondary indexes.

Exit: commit, cold reopen, history/as-of, branch, crash injection, and offline GC all preserve the right values.

### Stage 3 — actual O(diff), M

- built-in root/path map operations;
- vector `assoc`, `conj`, and `pop`;
- expected-root CAS fencing;
- staged initial upload for oversized values;
- bounded node caching and prefetch.

Exit: the 100 MiB mutation changes only leaf/path nodes and sends a bounded request.

### Stage 4 — full structured type and migration, L

- sets, collision buckets, nested collection keys, chunked strings;
- attribute-reference-store upgrade;
- new-attribute backfill and dual-read;
- retire new EDN-slot writes while retaining legacy reads;
- introduce a genuine bytes-only `my.blob`;
- tune or replace one-file-per-node storage if the durable probe requires it.

### Stage 5 — derivation gear, dependent L

Only after transitive program fingerprints and enforceable effect classification exist:

- Seon-level base-root + transformation-closure descriptors;
- explicit lazy resolution;
- memoized result roots with CAS/idempotency;
- eviction/recomputation semantics;
- Proximum-dependent derivations deferred until q28 has a verified cross-store recovery contract.

The final graduation gate should remain the prior 100 MiB falsifier plus crash/reopen/history/branch/GC proof. The earliest unsettled contract is real durable filestore cost at thousands of immutable keys—not hashing or Fressian CPU.