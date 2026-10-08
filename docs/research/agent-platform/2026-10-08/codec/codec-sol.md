---
type: research
status: findings
created: 2026-10-08
lane: codec-idea-sol
basis: Datahike 86c3301a00de8c49b2690083b2b1e69677bf0c8d; Konserve e4334756413917305968333bf305e658c177765d
---

# Native values in Datahike: independent design research

Research date: 2026-10-08. Lane: codec-idea-sol. Documentation only; no implementation, dependency change, default-store transaction, or restart by this lane. The owner's permission to discard existing database data removes the need to preserve the current physical encoding, but does not remove the need for exact equality, relational references, or coherent indexes.

## Verdict and recommendation

**Reject the candidate comparator as stated; accept the direction of storing plain immutable values whole.** “Natural comparison when it works, otherwise digest comparison” is not a total order. A four-millisecond probe below produces a three-value comparison cycle. A digest alone also cannot guarantee exact equality, and Seon's existing structural identity does not follow all of Clojure's equality equivalences.

The smallest correct first design is to improve the maintained Datahike fork's existing value comparator to a total structural order over an explicitly admitted native value domain, add a declared `:db.type/edn`, and preserve native collection types through its existing Fressian handlers. Use one datom for a plain value vector, map, set, list, or heterogeneous value. Preserve ordinary scalar and valid tuple ordering; any existing equality edge case that requires changing that order must be named and proved explicitly. Do not initially add a digest field or another Seon encoder. Digest ordering is a possible later optimization, applied uniformly to a whole semantic category with an exact structural tie-breaker, and only after measurement justifies its representation and equality costs.

This recommendation eliminates printed EDN storage and plain-value vector cells. It **does not eliminate ordered relational edges**: current component/reference vectors must retain position-bearing entities, or undergo an explicit domain redesign with proofs for every join, ownership rule and deletion behavior. The database being a set of datoms is no obstacle to one datom's value being an ordered vector. Datom set membership and order inside an atomic value are different concerns.

Large payloads remain producer-selected blobs through the existing storage owner. A blob is an explicit digest-bearing value with a requery, not a new implicit decoder inside every query. Generalizing the existing inline-or-stage operation to native binary payloads is additional work: its installed input is text, not arbitrary EDN.

## Authorities, revisions and observed installation

Read authorities: AGENTS.md in full, especially “Preserve native values”, “Three places, one decision”, “Words and where they come from”, and “Read the dependency first”; the REPL, Datahike, data-oriented Clojure and testing skills; docs/seon/architecture/data-modeling-guide.md; docs/seon/architecture/reference-code.md; docs/research/agent-platform/vector-storage-design-2026-09-27.md; docs/research/agent-platform/2026-10-08/vectors/vectors.md; docs/seon/schedule/agent-loop-ledger.md §3.20 and §3.23. This report was written independently without reading codec-astra.md.

| Dependency / owner | Examined revision | Installed seam and role |
| --- | --- | --- |
| Datahike fork | 86c3301a00de8c49b2690083b2b1e69677bf0c8d | `datahike.datom/compare-value`, `datahike.datom/cmp-nil`, `datahike.index.persistent-set/insert`, `datahike.index.interface/add-konserve-handlers` |
| Konserve fork | e4334756413917305968333bf305e658c177765d | `konserve.serializers/FressianSerializer`, existing handler composition and durable node serialization |
| persistent-sorted-set | org.replikativ/persistent-sorted-set 0.4.137; dependency JAR source | `org.replikativ.persistent-sorted-set/sorted-set-by`, `lookup`, `disj`; ordered membership depends on comparator zero |
| data.fressian | org.clojure/data.fressian 1.0.0; dependency JAR source | `clojure.data.fressian/clojure-write-handlers`, `clojure-read-handlers`, `create-writer`, `create-reader` |
| Seon | initial live archive a5fc4cb493ac5f5f2ec11ed4175497cdc9fdd651; later live archive 6cc5dbbb406d27850820525fd727aca278d8a9fe | `seon.schema.datahike/compiled-storage`, query/pull encoding; `seon.blob/inline-or-stage`; `seon.id/id` |

The Datahike and Konserve gitlinks matched their dependency checkouts. Source examination is evidence of the current seam, not proof that a proposed `:db.type/edn` or new comparator is installed. Neither exists as proposed. Native-value probes used the reachable default JVM, MCP `eval_clj` with agent codec-idea-sol, cluster default, and JVM mode. Local immutable databases, sorted sets and handler instances were disposable values; no connection was transacted against. MCP's own evaluation recording is the tool's ordinary operation.

Health was degraded before research: MCP reported `:faulted`, five open faults and 26 errored receipts; the initial runtime had `seon.schema/call-with-projection-state` maximum 134.722 s and SCI evaluation maximum 122.319 s. Default changed externally during research, from PID 40766 to PID 61758; a probe during acquisition answered that prepl/store were starting. The replacement reported readiness in 19,263 ms, heap live 908,887,368 bytes, heap used about 1.64 GB, and 1,502 platform threads. The five faults and 26 errored receipts remained. These are foreign operating boundaries, not an excuse to stop this read-only lane. Existing ledger performance/recovery work remains applicable; this report neither fixes nor certifies it. Successful local probes reported `:seon.repl/ms` between 1 and 32. The envelope did not expose `:seon.repl/alloc-mb`; allocation measurements below use the JVM thread allocation counter explicitly.

## Why the candidate fails, and what a corrected order requires

### Exception-dependent choice is not transitive

For vectors of one class, `[0 0] < [0 1]` by natural comparison. Comparing either to `[0 {:x 5}]` cannot naturally compare the second elements, so the candidate switches to digest comparison. The resulting signs were:

| a | b | c | compare a b | compare b c | compare a c |
| --- | --- | --- | --- | --- | --- |
| `[0 0]` | `[0 1]` | `[0 {:x 5}]` | -1 | -2 | 1 |

This cycle is fatal to a sorted index regardless of collision probability. A top-level type rank does not help: all three values are vectors. “Try natural compare” must be replaced by one predetermined ordering rule for each semantic category, recursively, or by uniform digest ordering for that entire category. A catch is not an ordering rule.

### Equality is part of the index contract

The contract needed by Datahike's indexes is comparator zero **if and only if the admitted values are equal**, with antisymmetry, transitivity and equality substitution. An equality-first check alone is insufficient if equal values have different order keys: their comparisons to a third value can disagree.

Observed `(= [1 2] '(1 2))` is true, but full-length `seon.id/id` differs. Array maps and hash maps can be equal; different persistent vector implementations can be equal. Numeric examples observed: long and BigInt can be equal, float and double can be equal, integer and double are not `=` even when natural numeric comparison returns zero, and positive/negative floating zero are equal. A JVM class rank is therefore unsuitable as the semantic rank. Metadata does not participate in ordinary collection equality. An index identity scheme must match the equality used by transaction deduplication, joins, unique lookup and datom equality, not merely be a convenient source/content identity.

Seon's default identity length is 12 hexadecimal characters. That abbreviated identity is especially inappropriate as an exact value comparator. A full cryptographic digest still needs a structural tie-breaker for distinct values sharing a digest. A 32-bit Clojure hash without a tie-breaker is also insufficient. Forced collisions must be tested, not dismissed as unlikely.

For the first fork change, keep native values and put the structural order in `datahike.datom/compare-value`. Semantic numeric categories must distinguish values that numeric `compare` conflates while merging representations that `=` equates. Sequential representations that Clojure equates must share an ordering rule. Maps compare canonical key/value sequences; sets compare canonical members under the same order, independent of insertion order and implementation class. Symbols and keywords compare namespace and name components without printing or EDN reparsing. Nested nil can be an admitted data value where the owning schema permits it; an absent attribute remains absence, never a stored nil fact. Preserve the existing nil wildcard in index-bound datoms separately from ordering a nested nil.

This is a dependency gap, not permission to invent a Seon comparator. The required change belongs in the maintained Datahike fork and must be offered upstream. Existing `seon.print/compare-values` is useful bounded reference evidence, but is not an exact index comparator: its unknown-value handling and sequence categories are not an equality proof. Do not copy it into another owner.

Only finite, immutable, serializable values are admissible. Arbitrary host objects, executable values, lazy/infinite sequences, mutable collection implementations and unhandled tagged objects need explicit refusal or an existing owner-provided durable value. Bytes and dates already have dependency semantics; their mutable inputs must not be retained as mutable index keys. NaN and decimal scale require explicit dependency-consistent equality tests before admission. An unsupported value must never compare equal by a class-order fallback. Bounds on admission and encoded size are configured dials, not constants in a new encoder.

### A later digest optimization has more work than a comparator branch

A coherent optimized order could be `(semantic rank, equality-coherent full digest, exact structural tie-breaker)`, with the digest used for every value in its chosen category. It needs equality-normalized hashing, including sequential and numeric equivalences, deterministic unordered collections, and exact verification on a matching digest. Structural tie-breaking remains necessary. `seon.id/id` cannot be used unchanged for that equality contract.

Computing the digest on each tree comparison would do O(B log N) hashing for a B-sized value in an N-datom index. A useful digest must instead be retained immutably with the accepted value or datom, or by the dependency's properly bounded cache. Query constants also need their key computed once. Do not add a Seon atom, rehash on every pull, or store only a digest and silently lose the value. A new datom sort-key field touches construction, association, search bounds, serialization, import, every comparator and sorted merge join. A custom wrapper must not leak through `:v`, query results or pull. That representation work is why it is not the smallest first slice.

For the structural design, repeated map/set sorting is the principal risk. Canonical native ordered collection representations constructed once at admission and on restoration are a possible improvement, preserving semantic map/set equality rather than ranking implementation classes. Measure this before introducing it. No text printer, canonical EDN string, or hidden second store is involved.

## What the current indexes and serializer actually do

The previous VEC-9 warning is reproducible on the installed fork. An in-memory database in read-schema mode accepted two reported additions of `[1 2]` and `[1 "x"]` to one indexed cardinality-many attribute, but EAVT, AVET and pull retained only `[1 2]`. `datahike.datom/cmp-nil` returns zero for those vectors because `safe-compare` falls back to their identical class order. Some quick comparator paths instead call `compare-value` directly and can throw for same-class incomparable values. Fixing only the catch or only AVET is insufficient.

| Surface | Existing dependency seam | Guarantee needed / change |
| --- | --- | --- |
| EAVT, AEVT, AVET | `datahike.datom/compare-value`, generated comparison expressions, quick and prefix comparators | One coherent order for insert, lookup, remove, seek, slices, old-value replacement and sorted joins. Zero must mean equality. |
| Temporal indexes | `datahike.index.persistent-set/temporal-insert`, `temporal-upsert` | Same value order with transaction/addition fields; retain both old and new composite values and their retractions. |
| Current index updates | `datahike.index.persistent-set/insert`, `upsert` | PSS lookup/replacement must not lose distinct values. E/A prefix replacement for cardinality-one still replaces the whole value. |
| Diff | `datahike.datom/diff-sorted` | Both sides must use the same comparator version. Value changes are whole-datom changes, not element edits. |
| Node identity | `datahike.index.persistent-set/leaf-content-uuid`, `branch-content-uuid` | Reconstituted datoms must have stable content identity; verify existing hasch-based node IDs before/after restoration. |
| Node restoration | `datahike.index.interface/add-konserve-handlers`, persistent-set root handlers | Root restoration selects a comparator by index kind; old roots are not automatically resorted when comparison changes. Version the format/order and reject mismatched roots. |
| Serialization | `konserve.serializers/FressianSerializer` and Datahike datom handlers | Native collection category and nested contents must survive cold restore, not just pass `=`. Include preconfigured stores whose handlers are already attached. |

PSS itself is not a scalar-only obstacle: with a consistent digest-first comparator on the finite 32-vector corpus, it retained all 32 values; all lookups and each removal worked, and all enumerated transitivity triples passed. This is a small algebra probe, not a proof of Datahike integration, collision handling, persistence or merge.

There is a separate persistence defect to address before enabling whole values. The installed Fressian defaults restore a vector/list as `java.util.Arrays$ArrayList` and a persistent set as `java.util.HashSet`. Equality checks can still return true, concealing the loss of `vector?`, `list?` and `set?`. The Datahike datom handler reconstructs the outer datom but does not restore nested collection categories. A datom containing nested vectors reproduced this loss.

A local handler experiment, composed with the existing Datahike and data.fressian handlers, used separate vector/set/list tags and restored their ordinary persistent forms. It preserved an outer vector, nested vector, nested set, nested list, and a symbol made from namespace `"space here"` and name `"a/b"`, all without EDN text. This establishes that Fressian offers the required extension seam. It does not prove cold Konserve index restoration; that remains a required fork proof. The handler change should extend the existing serializer, not introduce a separate serializer or wrapper registry in Seon.

Changing any existing comparison order makes old persisted roots unsafe. Disposable database data permits a coordinated fresh installation from committed inputs once proofs pass, or a dependency-supported rebuild. Every retained branch and temporal root must use the new order. Opening old roots and hoping natural comparison was mostly compatible is unacceptable. This lane performed neither a reset nor a rebuild.

## Transaction, query, pull and branch semantics

Add `:db.type/edn` to the dependency's declared value types and predicate, not merely a Seon schema alias. `datahike.schema` currently has a closed value-type enumeration; the internal permissive `:any` is not the proposed public storage contract. Seon continues to validate final owning values with `malli.core/validator` / `malli.core/explain`; Datahike's native value admission refuses unsupported objects at its own producer. Malli remains the schema authority, not a second handwritten Seon validator.

An EDN cardinality-one attribute stores `[a a b]` as one value, including order and duplicates. `[]` is one present empty value. A cardinality-many attribute stores a set of atomic values, e.g. `#{[1 2] [1 "x"]}`; the datom set deduplicates equal whole values, not their elements. Empty cardinality-many input still adds no facts. `[:db/add e a vector]` already offers an unambiguous atomic operation. Entity-map cardinality-many collections denote many values, so a singleton collection of vectors is required to assert one vector through that form.

`datahike.db.transaction/maybe-wrap-multival` currently guesses lookup refs from a two-item collection starting with a unique identity attribute even when the destination is not a ref. Restrict that interpretation to declared ref attributes; EDN data such as `[:some/identity "literal"]` must remain data. `explode` already only turns nested maps into entities for ref attributes. No new recursive Seon entity decoder is needed for plain EDN maps.

Query changes are real, but belong in Datahike. `datahike.db.search/validate-pattern` restricts vector value shapes to two members or declared tuples on the paths that call it; `datahike.query.lower/plan-rule-op` rejects map/set literal arguments and vectors with other lengths in relevant clause planning. Composite values must be valid bound inputs and literal value constants where the query grammar permits them. Attribute and entity grammar must remain distinct from value grammar. Test `:in`, direct constants, rules, unknown attributes, map/set keys, joins between equal values and two-element keyword vectors. Do not claim that adding a schema type alone fixes query planning.

The current query path is not wholly incapable of vector equality: a read-schema in-memory database storing `[1 2]` returned `#{[101]}` for both a bound `:in` value and a direct value literal (result/edcc93a4c731d; 2 ms). The same simple queries also accepted `[1 2 3]` (bound input result/e16d8c7c58d34, 1 ms; literal result/e96c7b0a34955, 2 ms). Those execution paths do not establish that every search/planner path accepts the value. The required work is removal of specific grammar and comparator gaps, not replacement of Datahike's query engine.

`datahike.pull-api` can return an atomic non-ref value directly. Wildcard pull, entity access and datoms should preserve that value without a decode walk. Nested EDN maps are data; they do not grant nested entity pull or ref resolution. Composite index ordering is an internal implementation detail; `index-range` over EDN composites must explicitly refuse a promised semantic range. Ordinary predicates/functions over a bound value (`get`, `nth`, etc.) are possible, but read that whole value. Preserve existing supported scalar/tuple range behavior.

`datahike.versioning/branch!` shares immutable index roots, not copied vector contents. The new type does not require a new branch mechanism. `merge!` accepts parents and transaction data; it does not automatically merge vector positions. Cardinality-one concurrent edits conflict at the whole attribute/value. Cardinality-many edits add/remove whole equal members. Seon's merge selection remains the authority for resolving those changes. Tests must cover unchanged-root reuse, divergent whole-value edits, history/as-of reads and serialization across reopen; no candidate branch/merge proof was run here.

Embedding an entity id in an EDN vector does not make it a Datahike ref. It loses tempid resolution, reverse lookup, required/optional ref checks, component ownership/cascade and graph joins. Therefore schema derivation must distinguish value collections from relationships, using the existing declared relationship properties rather than a guessed shape. Ordered relationships stay relational until their owning model changes explicitly.

## What disappears from Seon's codec, and what does not

The examined bridge was 1,201 lines, rather than the assignment's approximate 1,100. Recent commit 390652aae already coalesced equal reads and removed repeat printing on decoded reads. That is a useful reduction, but heterogeneous slots remain strings and vectors remain cells. The supplied measurements (49 µs `seon.db/q` versus 2.2 µs `d/q`, about 49 armed calls for a one-clause query, 30–70 s encoding a recording with many reads) are prior assignment evidence, not independently rerun benchmarks in this lane.

| Change | Delete or simplify in the existing owner | Keep / convert callers |
| --- | --- | --- |
| Heterogeneous values become native EDN | `seon.schema.datahike/storage-string`, `storage-data`, `storage-compare`, `reader-round-trips?`, reader-identifier restrictions, `decode-edn`, storage readers and encoded-slot detection; retire their derivation cache kinds | Schema derivation becomes declared native value type; final owning Malli validation remains. |
| Native value results | `encode-attribute-value-in` / `decode-attribute-value-in` EDN branches and their query, datom and pull decode paths | Checked transaction writer, read-evidence capture, identity resolution and declared relation handling remain. Convert all callers and contracts together. |
| Plain ordered values become cardinality-one vectors | Their cell creation, position sorting, presence handling, selector rewriting and membership-query lowering | Empty vector remains present. Raw add/retract and Datalog membership semantics need explicitly changed contracts/callers; no silent switch. |
| Ordered refs/components | No blanket cell deletion | Keep relational edges and their position representation, or redesign the owning schema and every reaching query/test. |
| Large values | No codec-string round trip or printer-based threshold calculation | Existing blob staging/publication and one producer decision; native binary generalization separately proved. |

Current examples requiring relational inspection include `:seon.fn/arities`, `:seon.fn.arity/arguments`, binding children and evaluation read-evidence components. `seon.db/report-arity-comparison` and prepared arity bounds join through such relationships. Converting all `[:vector T]` declarations to opaque EDN would sever surviving queries. The smaller schema bridge still has a valid job: translate Malli's declared cardinality, identity, index, ref, component and no-history properties into dependency schema. It should not translate the values that dependency storage now natively supports.

The current vector contract also has behavior beyond preservation of order: entity-map assignment replaces the vector, raw add appends an element, raw retract removes matching elements, and a Datalog pattern binds members through cells. An atomic EDN attribute instead binds the whole value; a cardinality-one add replaces it. Positional/indexed membership queries require explicit edges or a separate declared index, not a hidden expansion of every native value. Partial replacement of a B-sized vector still serializes/hashes O(B) content; persistent in-memory structural sharing alone does not turn durable storage into a patch operation. Large frequently edited ordered data is a reason to keep components or redesign the payload owner.

## Blobs: one decision, explicit meaning

The installed `seon.blob/inline-or-stage` accepts text and decides from its UTF-8 size against `:seon.config.eval.result/blob-threshold`. `seon.blob/stage-binary!` provides an existing binary staging seam. Neither proves a native EDN spill design is installed. Generalize the existing producer operation to binary serialization without printing, using the same configured decision, then stage and commit its descriptor with the fact through the existing writer. No threshold decision in Datahike's comparator, no second size policy, no implicit fetch on every query.

Exact lookup of a blob descriptor finds a digest-bearing fact. Lookup by original large content requires computing the producer's content identity and verifying matching content when exactness is required; it does not become an automatic native payload index. Digests used to locate durable bytes and digests used to order semantic values are different contracts and need not be the same hash. GC must retain payloads reachable through every retained branch/history root; branch deletion must use dependency retention, not per-value cleanup in the comparator.

## Alternatives and outside systems

The research falsifies the premise that all datom databases necessarily lack composite values. It does not establish that any outside database has a drop-in correct comparator for Seon's domain.

| Alternative / system | Evidence and guarantee | Fit and sacrifice |
| --- | --- | --- |
| Datahike tuples | Installed `datahike.db.transaction/check-tuple` supports fixed heterogeneous scalar slots and homogeneous tuples bounded at eight members | Good for small known keys/positions; not arbitrary nested maps, empty/general vectors or heterogeneous union data. Keep valid tuple range semantics. Removing the length check alone does not fix comparison or serialization. |
| Position-bearing component entities, current cells | Current Seon vector design restores order/duplicates, membership and real ref edges | Correct for relational vectors. O(K) cells/datom overhead for K members, query/pull rewrites, many armed calls. A smaller dependency-owned ordered edge representation could later replace Seon's lowering, but it is a different design from an atomic EDN value. |
| Linked ordered refs | One entity/edge per member plus next link | Supports local insertion without shifting every position, but traversal, cycle/ownership validation and membership need explicit indexes; whole-vector reads are O(K) traversals. No reason to replace existing position cells wholesale. |
| Blob-only ordered values | Existing Konserve bytes + declared descriptor | Lowest index amplification for large read-whole payloads; loses native value lookup, element joins and partial updates. Appropriate only when consumers accept descriptor/requery semantics. |
| Datomic | [Official schema reference](https://docs.datomic.com/schema/schema-reference.html): cardinality-many is a set of values; tuples have 2–8 scalar slots, including fixed and homogeneous variants | Its tuple limits are a product contract, not proof that a datom cannot contain a vector. [Official forum discussion of ordered lists](https://forum.datomic.com/t/handling-ordered-lists/305) emphasizes choosing graph structure when internal queries matter. |
| XTDB | [Current native data types](https://docs.xtdb.com/reference/main/data-types.html) include heterogeneous lists, sets and structs. [XTDB 1 codec source at 094e36f2f93d6543c899e2ba96947747125423a2](https://github.com/xtdb/xtdb/blob/094e36f2f93d6543c899e2ba96947747125423a2/core/src/xtdb/codec.clj) uses typed value buffers and hashes for some larger objects | Document-first storage shows a workable separation of documents and value indexes. Current XTDB's columnar architecture is not a small Datahike change; do not copy its codec or infer its collection query semantics without further proof. |
| Datalevin | [Project documentation](https://github.com/datalevin/datalevin) describes arbitrary EDN value storage without a specified value type and cautions about range behavior. [Value encoding at 4285f2a9c19c096ab2748ff6cba72db2ba9be78a](https://github.com/datalevin/datalevin/blob/4285f2a9c19c096ab2748ff6cba72db2ba9be78a/src/datalevin/bits.clj) serializes generic data with Nippy | Strong counterexample to scalar-only datom claims. Its byte ordering/overflow storage is not proof of Clojure equality-coherent ordering; exact collision and canonicalization behavior was not independently tested here. |
| Asami | [Entity writer at ea5c70d5330be001dd8694d2b35d89681d3a9acb](https://github.com/quoll/asami/blob/ea5c70d5330be001dd8694d2b35d89681d3a9acb/src/asami/entities/writer.cljc) builds ordered lists with `:a/first`, `:a/rest` and membership links; its reader reconstructs collections | A native-looking collection API can still expand into graph entities. This preserves relational operations but does not remove cell/edge amplification. |
| DataScript | [Value comparison at 34915cf673c02cbc64779fe56e0288d70fea63f8](https://github.com/tonsky/datascript/blob/34915cf673c02cbc64779fe56e0288d70fea63f8/src/datascript/db.cljc) recursively compares sequences and has fallback hash ordering for otherwise unsupported values | Useful upstream relative for structural sequence comparison. A hash fallback without an exact distinct-value tie-breaker is not the guarantee required here; do not copy it as a total exact order. |

## Costs and measured probes

Let N be index datoms, K collection members, B native payload size, and P a shared structural prefix length. Scalars retain their normal comparison cost. Structural vector comparisons are O(P) worst-case O(K); map/set canonical ordering can cost O(K log K) unless reused from the accepted representation. Index insertion is O(log N) comparisons, with persistent tree node changes and serialization. Equality verification of separately constructed equal B-sized values is O(B). Hashing once is O(B), but does not make payload writing or exact equality free.

Warm local measurements used 50 warmups, 500 repetitions and `com.sun.management.ThreadMXBean` current-thread allocated bytes. Full digests were 64-character `seon.id/id` outputs. These are references for costs, **not** timings of the proposed Datahike comparator or a before/after query benchmark.

| Operation | Input | Mean µs | Allocated bytes / operation |
| --- | --- | ---: | ---: |
| Full structural identity | Vector of ten integers | 3.9845 | 11,225.488 |
| Full structural identity | Twenty-entry map, each value `[i :k "value"]` | 9.993916 | 17,182.768 |
| Existing structural presentation comparator | Ten-element vectors differing at final member | 0.8575 | 2,480.192 |
| Existing structural presentation comparator | Twenty-entry maps differing at one nested integer | 32.398332 | 28,784.048 |

A full digest adds at least 32 binary bytes per retained key plus object/reference overhead, and possibly duplicates in current and temporal indexes or serialized leaves. Do not use 64-character hex in an internal key without a reason. The measured `seon.id/id` allocation is much larger than 32 bytes; retaining the digest avoids repeat computation, not its initial cost. That operation uses Seon's structural SHA-256 implementation, not Datahike's hasch-based node identity. No candidate memory footprint, disk amplification or fork throughput was measured. The map comparison result warns against sorting large maps during every tree comparison; it does not prove the dependency implementation would have that cost.

A repeat of the hashing loop later in the same research session returned 1.43875 µs / 11,224.144 allocated bytes for the vector and 5.9215 µs / 17,176 bytes for the map (result/ecce40697fd4c; envelope 6 ms). The timing variation means these loops are cost probes, not a stable performance gate. Allocation is consistently substantial. The initial hashing and structural-comparison envelopes were result/ed2393f312cc1 (12 ms) and result/eb504437d78f9 (32 ms), respectively.

The expected gain is removing repeated print/parse/encode/decode and per-member entities for plain values, not guaranteeing the 49 µs query becomes exactly 2.2 µs. Seon's read evidence, contracts and custody still have work. Required before/after measurements are the assignment's one-clause query, equal and changed small writes, cold/warm whole-value pull, the formerly 30–70 s recording, and bounded map/vector writes at shipped thresholds. Measure armed functions, allocations, node bytes and cache behavior, using the same live inputs and fixed configuration. Over-one-second ordinary operations must be addressed by their owner; this report makes no claim that the cost is already fixed.

## Fork and Seon changes as bounded slices with proofs

Each row is a proposed approximately fifteen-minute implementation card, not a promise the full feature takes fifteen minutes. Stop at an unexpected semantic gap; split the work rather than patch around it. Root assigns disjoint ownership. Proposed new test symbols are deliberately not presented as installed APIs. The first slice adds failing proofs; later slices name the exact accepted tests reaching their changed declarations. Broad tiers run centrally.

| Slice / owner | Smallest change | Proof before acceptance |
| --- | --- | --- |
| 1. Datahike comparator tests | Extend existing datom/index tests with VEC-9 and the three-vector cycle; no new value type yet | Current fork fails incomparable-member retention. Bounded equality/substitution, antisymmetry and transitivity properties over admitted types; forced hash collisions if testing an accelerator. Scalar and valid tuple ordering unchanged. |
| 2. Datahike sequence order | Replace exception/class fallback for supported sequential values in existing `compare-value`; use the same rule in quick, prefix and generated comparators | EAVT/AEVT/AVET insert, remove, exact seek and unique checks retain every distinct vector; list/vector equality consistent; nil bound sentinels preserved. Unsupported keys fail locally. |
| 3. Datahike maps/sets and numeric equivalence | Extend the existing structural order by semantic category, with complete named admission predicate | Different map/set construction orders compare zero iff equal; equal numeric representations substitute coherently; unequal natural-compare-zero cases remain distinct. NaN, scale, signed zero and mutable inputs follow documented dependency semantics. Profile repeated map comparison before accepting cost. |
| 4. Datahike/Konserve handlers | Extend existing Fressian vector/set/list handlers, including nested values and empty collections | Round trip through actual node/Datom serialization preserves category, named values and node content IDs; cold close/reopen Konserve checks, including preconfigured stores. No text round trip. |
| 5. Datahike type and transaction grammar | Declare `:db.type/edn`; scope lookup-ref guessing to refs; preserve atomic `:db/add` | Cardinality-one empty vector vs absence, many whole vectors, equal member deduplication, maps, nested nil under valid schema, literal identity-looking vector; reject unsupported values before insertion. |
| 6. Datahike query constants | Admit composite bound inputs/constants in the current planner and pattern validator | `:in`, literals, rules and joins return exact native values with no Seon rewrite; two-member keyword vectors do not become lookup refs in value position; entity/ref grammar remains correct. |
| 7. Datahike history and format | Version comparator/serialization format using existing store metadata and restore owner | History/as-of assertion/retraction, sorted diffs, branch root sharing, divergent merge transaction data, cold reopen; incompatible old roots explicitly refused. Do not boot default to discover compatibility. |
| 8. Seon heterogeneous-slot cut | Derive native EDN type and delete string encoder/decoder functions with every caller/cache/schema reference | Existing union/native-value tests through the real writer and SCI path compare actual data, including unusual native symbols; no printer calls for storage. Compile packaged contracts/resources; branch publication/adoption proof. |
| 9. Seon plain-vector cut | Select declared value vectors for atomic storage; retain declared ref/component vectors | Existing order/duplicates/presence tests converted only for the intended semantics; caller tests for raw operations and membership explicitly changed. Arity/binding/ref validation and deletion proofs remain green. |
| 10. Native payload spill | Generalize the existing producer storage decision over binary input; reuse staging/publication | Threshold boundaries, no double decision, exact payload recovery, failed transaction publication and all retained branch/history references; blob descriptor render/requery rather than hidden decode. |
| 11. Optional digest optimization | Only after comparison profiles justify it; retain immutable equality-normalized key within dependency owner | Forced collisions, equality substitution, every comparator/search constructor/serializer path, same-input throughput and allocation improvement without scalar/tuple regression. Delete it if structural order is already cheap enough. |

Rows 2–4 cannot ship as a new persistent format without row 7's coordination. A disposable in-memory branch proof can precede persistence; production admission waits for the complete loadable dependency slice. Seon rows publish schemas and consumers together, then adopt them on a branch of the live store. Root alone coordinates a disposable-store transition, committed dependency bump and restart. No blanket change to every vector schema is a valid slice.

## Durable probe evidence

These exact small forms were evaluated in JVM mode with explicit agent and cluster. Private dependency symbols below are read evidence, not proposed public APIs. They construct local values, not writes to the default connection.

### Repeated digest timing (result/ecce40697fd4c; 6 ms)

```clojure
(let [bean (java.lang.management.ManagementFactory/getThreadMXBean)
      thread-id (.getId (Thread/currentThread))
      samples 500
      inputs [[:vector (vec (range 10))]
              [:map (into {} (map (fn [i] [i [i :k "value"]]) (range 20)))]]]
  (mapv
    (fn [[kind value]]
      (dotimes [_ 50] (seon.id/id value 64))
      (let [before (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id)
            started (System/nanoTime)]
        (dotimes [_ samples] (seon.id/id value 64))
        (let [elapsed (- (System/nanoTime) started)
              allocated (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id) before)]
          {:kind kind :ns-per-op (/ (double elapsed) samples)
           :allocated-bytes-per-op (/ (double allocated) samples)})))
    inputs))
```

### Candidate cycle and equality (result/e272625518743; 4 ms)

```clojure
(let [values (vec (concat (map #(vector 0 %) (range 16))
                          (map #(vector 0 {:x %}) (range 16))))
      hashes (into {} (map (fn [v] [v (seon.id/id v 64)])) values)
      cmp (fn [a b]
            (try (compare a b)
                 (catch ClassCastException _
                   (compare (hashes a) (hashes b)))))
      cycle (first
             (for [a values b values c values
                   :when (and (neg? (cmp a b)) (neg? (cmp b c))
                              (pos? (cmp a c)))]
               {:a a :b b :c c :ab (cmp a b) :bc (cmp b c) :ac (cmp a c)}))]
  {:cycle cycle
   :vector-list-equal (= [1 2] '(1 2))
   :digests-equal (= (seon.id/id [1 2] 64) (seon.id/id '(1 2) 64))
   :safe-compare (#'datahike.datom/cmp-nil [1 2] [1 "x"])})
```

Returned cycle above, vector/list equality true, digests equality false, safe-compare zero.

### Installed value collapse (result/e2ddba506f0e1; 2 ms)

```clojure
(let [db (datahike.core/empty-db
           {:t/m {:db/cardinality :db.cardinality/many :db/index true}}
           {:schema-flexibility :read :keep-history? true})
      r (datahike.api/with db [[:db/add 101 :t/m [1 2]]
                              [:db/add 101 :t/m [1 "x"]]])
      after (:db-after r)]
  {:reported (count (:tx-data r))
   :eavt (mapv :v (datahike.api/datoms after :eavt 101 :t/m))
   :avet (mapv :v (datahike.api/datoms after :avet :t/m))
   :pull (datahike.api/pull after [:t/m] 101)})
```

Returned two reported additions but only `[[1 2]]` in both indexes and pull. Existing task `seon.schema.datahike/the-datahike-fork-collapses-distinct-incomparable-values` / VEC-9 is the owning defect; no duplicate task was created.

### Finite PSS test (result/e47459b92c3f7; 6 ms)

```clojure
(let [values (vec (concat (map #(vector 0 %) (range 16))
                          (map #(vector 0 {:x %}) (range 16))))
      digests (into {} (map (fn [v] [v (seon.id/id v 64)])) values)
      cmp (fn [a b]
            (let [c (compare (digests a) (digests b))]
              (if (zero? c) (seon.print/compare-values a b) c)))
      index (into (org.replikativ.persistent-sorted-set/sorted-set-by cmp) values)]
  {:stored (count index)
   :every-lookup (every? #(= % (org.replikativ.persistent-sorted-set/lookup index %)) values)
   :transitive (every? true?
                 (for [a values b values c values
                       :when (and (neg? (cmp a b)) (neg? (cmp b c)))]
                   (neg? (cmp a c))))
   :every-retraction
   (every? #(= 31 (count (org.replikativ.persistent-sorted-set/disj index % cmp))) values)})
```

Returned 32 and true for each check. The tie-breaker is only adequate for this known finite corpus; this is not a reusable proposed comparator.

### Type-preserving Fressian extension (result/ee87ac9519fb2; 5 ms)

```clojure
(let [write-handler
      (fn [tag]
        (reify org.fressian.handlers.WriteHandler
          (write [_ w value]
            (.writeTag w tag 1)
            (.writeObject w (java.util.ArrayList. ^java.util.Collection value)))))
      read-handler
      (fn [build]
        (reify org.fressian.handlers.ReadHandler
          (read [_ rdr _ _] (build (.readObject rdr)))))
      wh (-> (merge clojure.data.fressian/clojure-write-handlers
                    @#'datahike.index.persistent-set/datom-write-handler
                    {clojure.lang.IPersistentVector {"codec-idea/vector" (write-handler "codec-idea/vector")}
                     clojure.lang.IPersistentSet {"codec-idea/set" (write-handler "codec-idea/set")}
                     clojure.lang.ISeq {"codec-idea/list" (write-handler "codec-idea/list")}})
             clojure.data.fressian/associative-lookup
             clojure.data.fressian/inheritance-lookup)
      rh (clojure.data.fressian/associative-lookup
           (merge clojure.data.fressian/clojure-read-handlers
                  @#'datahike.index.persistent-set/datom-read-handler
                  {"codec-idea/vector" (read-handler vec)
                   "codec-idea/set" (read-handler set)
                   "codec-idea/list" (read-handler #(apply list %))}))
      value (datahike.datom/datom 1 :v
              [(symbol "space here" "a/b") {:v [1 2] :s #{:a :b} :l '(1 2)}])
      out (java.io.ByteArrayOutputStream.)]
  (clojure.data.fressian/write-object
    (clojure.data.fressian/create-writer out :handlers wh) value)
  (let [got (clojure.data.fressian/read-object
              (clojure.data.fressian/create-reader
                (java.io.ByteArrayInputStream. (.toByteArray out)) :handlers rh))
        nested (get-in got [:v 1])]
    {:equal (= value got) :outer-vector? (vector? (:v got))
     :nested-vector? (vector? (:v nested)) :nested-set? (set? (:s nested))
     :nested-list? (list? (:l nested)) :native-symbol? (symbol? (get-in got [:v 0]))}))
```

All six booleans returned true. The earlier existing-handler datom probe (result/e7d9b008ca671; 1 ms) returned equal datoms but false outer/nested vector predicates and class `java.util.Arrays$ArrayList`. Generic set restoration likewise returned `java.util.HashSet` (result/e1f30d7e0e22f; 2 ms). The explicit tags are local experiment strings, not registered production tags.

## Landing and limits

No source/test lines changed. The report records the failed candidate, existing comparator loss, serialization category loss, local proofs and unimplemented boundaries. It does not certify a new database value type, cold store restoration, blob spill, history/merge behavior or performance improvement.

Standalone `clj-kondo --lint src script test --cache false` completed in 6,953 ms: **0 errors, 1,489 warnings**, exit status 2 from warnings. This is not zero findings. Foreign source edits/landings during research were not changed by this lane; documentation is no program input and no test reaches this Markdown change. The requested lane commit additionally names `seon.schema.datahike-test/fixed-tuples-derive-native-ordered-storage` as an existing tuple baseline, using the candidate archive check. Its actual result is the commit gate evidence; it cannot prove the proposed EDN design because that design is not implemented. No pre-existing red is assumed green or accepted with an exemption.

Initial report commit: 8878ba93b, through `bin/lane commit codec-idea-sol`, with the named var above. The gate's candidate source was 8e83ea4d433529dac46764f4bfebdfd4e4821526. Run 3c56de654686 executed one test, reused zero, passed three assertions, failed zero and errored zero, in **3,438 ms**. Full `bin/seon check` took **90,123 ms**: slot wait 31,808 ms, readiness 39,493 ms, test phase 3,522 ms, publication 1,852 ms and adoption 366 ms. These operator phase fields overlap; they must not be summed. Source built and the rehearsal adopted 137 identities. The lane gate took 91 s before committing. Its lint reused unchanged HEAD; the independent full-root lint above is the zero-error evidence requested by the assignment.

The test exceeded the ordinary one-second goal despite passing; boot/check also exceeded ten seconds. These are existing harness/operator costs, explicitly authorized by the requested throwaway check, not timings of an EDN implementation or an excuse to call ordinary operations sub-second. The report's degraded-runtime evidence and existing ledger performance work are the foreign boundary. This evidence update remains documentation only; no other lane's source, index or resources were changed.

No owned long-lived database, graph, connection or test root was created by the read-only forms. Ordinary MCP agent bookkeeping remains under the system's lifecycle owner; this lane did not mutate default to retire it. Root retains responsibility for the existing faults, external restart, branch cleanup and any subsequent implementation assignments.
