---
type: research
status: recommendation, not implementation
created: 2026-10-08
lane: codec-research-astra
basis: Seon a5fc4cb493ac5f5f2ec11ed4175497cdc9fdd651; Datahike 86c3301a00de8c49b2690083b2b1e69677bf0c8d
---

# How much storage codec does Seon need?

Seon currently derives Datahike schemas from Malli, serializes heterogeneous values into EDN strings, and implements transparent vectors with component cells. Keep the schema derivation and the vector semantics; eliminate the general printed-value codec by first removing facts whose consumers are retiring, then modeling surviving facts with native Datahike types.

**Recommendation: option A below.** Do not build a universal EDN storage type to accelerate machinery the agent-loop cut deletes. The live census found **20 EDN-backed attributes, only three populated, holding 251 datoms**. Of those, **246 are stored refresh plans/revisions**; `docs/seon/specs/agent-loop.md` §3.1 and ledger S4-06 already retire stored refresh evidence with its last consumers. The other five are printer-default metadata, consumed from Malli properties. That is a much smaller problem than “serialize arbitrary Clojure values.” A safe native structured-value extension to Datahike remains an option if a surviving consumer requires it; it is not a one-line comparator fix.

This does **not** mean deleting vectors or weakening their contract. The owner's September 27 requirement is transparent put/get with order, duplicates, and presence; the installed design additionally preserves element refs, components, membership queries, and identity on reorder. Datahike's cardinality-many and tuples do not jointly supply that contract. A small Seon vector adapter over native components remains justified. “No general value codec” and “no adaptation whatsoever” are different decisions.

## 1. Evidence, scope, and limitations

Read AGENTS.md, the relevant skills, the codec, its database callers, the pinned Datahike schema/transaction/index/comparison/read owners, Malli's retained compilation seams, Konserve's serializer seam, the vector design/research, the ledger, and the agent-loop storage/record contract. No source was changed, no uncommitted source was loaded, and default was neither transacted by a probe nor restarted. MCP probes used JVM mode, cluster `default`, agent `codec-research-astra`, and explicitly dereferenced `seon.cluster.boot/connection`. Dependency experiments used immutable, storeless `datahike.db/empty-db` values and `datahike.api/with`; no database was created on disk. MCP itself records its ordinary observations.

The running source URLs confirmed the Seon archive **a5fc4cb493ac5f5f2ec11ed4175497cdc9fdd651** and Datahike pin **86c3301a00de8c49b2690083b2b1e69677bf0c8d**. Konserve's checkout pin was **e4334756413917305968333bf305e658c177765d**; Malli's was **418df4417827b66846b799b5e87267a344dffc29**. Source observations below refer to that Seon/Datahike basis, even though other lanes subsequently changed HEAD and the working tree. File offsets are dated evidence, not living API references.

**Broken baseline first.** Both status entrances reported default degraded: 11 over-bound warnings, 22 errored receipts, one unowned namespace. The process was PID 40766, start instant `2026-10-08T10:30:32.694Z`, heap used about 2.56 GB, reported live heap 750 MB, heap maximum 4 GiB. These are status observations, not memory attributable to this lane. The supplied root profile—33 ms / 112 MB / 73k armed calls for a current-state block, and about 66 seconds during recording—is prior evidence, not a workload reproduced here.

The existing task `seon.schema.datahike/the-codec-reprints-values-to-compare-them` adds an important cause: the long recording carried **440,605 read-evidence rows, only 8,266 distinct**. That is about **53.3 copies per distinct row**. Faster encoding alone would retain the wrong multiplicity. Equal *complete evidence values* can be coalesced at their producer; equal query text at different bases or source positions cannot. The target deletion of durable refresh evidence is stronger still. Process-local read evidence for rendering and derivation remains useful; its presence is not an argument for durable copies on every evaluation.

Existing defect owners: `seon.schema.datahike/the-datahike-fork-collapses-distinct-incomparable-values` (VEC-9), `seon.schema.datahike/decoding-pays-a-projection-scope-and-cache-claim-per-value`, `seon.schema.datahike/an-attribute-query-calls-logical-attribute-once-per-datom`, and `seon.schema.datahike/the-codec-reprints-values-to-compare-them`. This note supplies additional evidence to these classes; it does not edit the owner's issue index.

### Live census

At basis **536871271**: 559,678 current datoms; 2,354 entries in Datahike's schema map, of which **1,112 have a value type**. Do not call all 2,354 entries installed typed attributes: 1,242 lack `:db/valueType`. Typed entries: string 292, long 220, ref 213, keyword 160, symbol 86, boolean 70, instant 30, tuple 17, double 16, UUID 8. The 20 encoded attributes below are all physically cardinality-one strings, not 20 new value types.

At basis **536871276**, commit `6ac772dd-e84a-598e-8972-f8ada18007b3`, their populated ranges were:

| Attribute | Datoms | Total UTF-8 storage bytes | Largest bytes | Nodes in largest decoded value | EDN reader, largest value, 100 reads |
|---|---:|---:|---:|---:|---:|
| `:datahike.read/dependency-plan` | 123 | 76,244 | 3,468 | 339 | 10.609 ms |
| `:datahike.read/revision` | 123 | 58,279 | 1,253 | 52 | 2.770 ms |
| `:seon.print/default` | 5 | 17 | 8 | 1 | 0.0268 ms |
| Other 17 encoded attributes | 0 | 0 | — | — | — |

Total: **134,540 storage bytes**, about **0.045% of current datoms**. Storage-byte measurements here describe the storage problem, not a suggested display budget. The first whole-store scan took **1,686.22 ms** inside a **1,694 ms** evaluation and produced a slow finding. It deliberately visited all 559,678 datoms; do not put that census on an ordinary read path. The targeted size/reader probe took **30 ms**. No whole-store scan was repeated.

There were **29 vector-shaped attributes**, 11,160 position datoms and 6,889 presence datoms. The largest live element populations were arity arguments 6,316, arities 3,263, binding entries 1,213, binding children 202, read evidence 123. Other nonzero attributes: health input 25, AI wire 9, render units 7, filesystem roots 1, AI stop 1. The current cell representation adds roughly **2 × 11,160 + 6,889 = 29,209 datoms** beyond element datoms, about **5.2%** of the audited store. This is a real cost, but a different cost from EDN encoding. Schema declarations with no current values still need correct future semantics.

### Codec cost and read overhead

One largest stored value per populated attribute, existing installed functions; canonical decode was called directly to avoid its content-cache hit. These are small samples, not percentiles or a production benchmark. Allocation used the current thread's `ThreadMXBean` counter; it excludes other threads and is not retained heap. Evaluation: **7 ms**.

| Value | Encode once | Canonical decode once | Warm cached decode, 100 calls |
|---|---:|---:|---:|
| Dependency plan | 0.890 ms / 1.083 MiB | 0.498 ms / 1.182 MiB | 0.253 ms / 0.388 MiB |
| Revision | 0.203 ms / 0.440 MiB | 0.187 ms / 0.464 MiB | 0.250 ms / 0.388 MiB |
| Printer default | 0.0205 ms / 0.0135 MiB | 0.00383 ms / 0.0116 MiB | 0.256 ms / 0.386 MiB |

The smallest default still pays roughly the same warm decoder overhead as the largest plan. The cache masks reconstruction cost; it does not make repeated schema dispatch and wrapper allocation useful.

At basis **536871322**, entity 76322, one dependency-plan value, 10 warmups then 100 operations, the native and Seon entrances measured:

| Entrance | Total for 100 | Mean per call |
|---|---:|---:|
| `datahike.api/q` | 0.340 ms | 3.40 µs |
| `seon.db/q` | 24.085 ms | 240.85 µs |
| `datahike.api/pull` | 0.590 ms | 5.90 µs |
| `seon.db/pull` | 4.533 ms | 45.33 µs |

Evaluation **36 ms**. Native results were stored strings; Seon returned the equal decoded value. This comparison includes contracts, query/pull preparation, and other Seon read checks, not just the codec. It is a warm repeated-key comparison, not a bound on joins, fresh plans, disk reads, or captured read evidence. It shows substantial removable work around a tiny read, without assigning all of that work to serialization. A first submission had a missing closing parenthesis and returned a reader error at 0 ms; it evaluated no benchmark, was corrected, and is not a system performance failure.

## 2. What each codec function does

All symbols in this inventory are in the original 1,180-line `seon.schema.datahike` owner. “Keep” means keep the responsibility at its current owner, not preserve its current implementation or function count.

### Schema derivation and metadata: not a value serializer

| Functions | Job and shapes served | Disposition |
|---|---|---|
| `seon.schema.datahike/literal->datahike-value-type` | Maps scalar literal classes to native types. | Keep as part of schema derivation; no per-value read work. |
| `seon.schema.datahike/storage-schema` | Follows Malli refs and `:and`, preserving the special ref declaration and detecting cycles. | Keep where storage shape is derived. |
| `seon.schema.datahike/value-schema` | Selects the element schema for collections. | Keep where needed for declaration/write validation. |
| `seon.schema.datahike/compiled-storage` | Malli walk deriving type, cardinality, properties, tuple slots, vector flag, and EDN flag. Unions with unlike or unknown types fall back to string. A union of maps also falls back; “heterogeneous” is an imprecise name for this. | Keep native translation; delete implicit string fallback. Compile once per changed schema content, not per datom. |
| `seon.schema.datahike/attribute-declaration` | Produces Datahike schema maps or an unstorable diagnostic; carries identity/index/component/history/secondary-index properties. | Keep. Malli is not itself a Datahike schema publisher. |
| `seon.schema.datahike/malli->datahike-attr-in` | Throws an attribute-declaration refusal for old callers. | Retain only until caller error handling is unified. |
| `seon.schema.datahike/declared-storable?` | Tests retained compiled facts without constructing a refusal. | Keep declaration-time decision. |
| `seon.schema.datahike/assert-storable-schema!` | Rejects error observations carrying an entity upsert identity. | Seon admission invariant, not serialization. |
| `seon.schema.datahike/compiled-attribute-selection` | Selects entity members and independently persisted/lifted metadata attributes. | Keep selection, but require an actual stored consumer. Storability alone is not a reason to lift metadata. |
| `seon.schema.datahike/malli->datahike-schema-in` | Maps selected attributes to declarations. | Keep publication-time operation. |
| `seon.schema.datahike/storable-attribute-in?` | Registered-and-storable predicate. | Keep derived answer, not repeated schema walks. |
| `seon.schema.datahike/chain-properties` | Resolves property precedence along refs/`:and`. | Metadata inheritance, outside value encoding. |
| `seon.schema.datahike/declared-reference` | Finds a property's effective qualified function symbol. | Keep where render/reference metadata needs it. |
| `seon.schema.datahike/storable-properties-in` | Lifts metadata into schema entities; turns function metadata into refs. | Audit consumers. This is why five printer defaults became stored union strings. |
| `seon.schema.datahike/property-dependents-in` | Finds schema rows affected by a changed property declaration. | Keep incremental publication dependency logic. |
| `seon.schema.datahike/database-attributes-core-in` | Selects core entity/independent attributes. | Keep publication selection. |
| `seon.schema.datahike/database-attributes-for-in` | Selects attributes for supplied authored forms. | Keep publication selection. |
| `seon.schema.datahike/database-attributes-in` | Selects core plus lifted property attributes. | Keep after removing unjustified stored properties. |

The native type table `seon.schema.datahike/malli-type->datahike-type` already maps symbols directly. It is narrower than Datahike: no bytes/bigint/bigdec/number mapping, for example. Missing bridge mappings are not missing Datahike capabilities. `malli.core/walk`, `malli.core/-cached`, `malli.core/validator`, and `malli.registry/schema` are the existing dependency seams; do not build a second schema compiler or validation registry. The compiled derivation's input is the schema plus referenced declarations; its recomputation event is changed declaration content. A database commit that changes only an evaluation is not that event.

### EDN serialization: removable as a family

| Functions/data | Job and shapes served | Disposition |
|---|---|---|
| `seon.schema.datahike/edn-encoded-attr-in?` | Consults compiled storage facts for the union-string flag. | Delete with encoded attributes; until then decide once per distinct attribute in a read plan. |
| `seon.schema.datahike/edn-encoded-slot?` | Reconciles logical declarations with installed string type during retyping. | Delete after reset/conversion; no permanent compatibility branch for disposable data. |
| `seon.schema.datahike/validate-logical-slot-in!` | Validates a logical union value before printing it. | Ordinary producer/write validation remains; the codec-specific duplicate goes. |
| `seon.schema.datahike/storage-readers` | Custom keyword/symbol tags restore names that EDN spelling cannot express. | Delete. Native symbols/keywords or a dependency binary serializer preserve their parts. |
| `seon.schema.datahike/reader-round-trips?` | Prints each keyword/symbol and reads it to decide whether to tag it. | Delete, not a printer cache or another identifier parser. |
| `seon.schema.datahike/storage-compare` | Calls the presentation comparator, then prints ties to distinguish them. | Delete. Database comparison belongs to Datahike and must not depend on rendering. |
| `seon.schema.datahike/storage-data` | Postwalks everything, tags names, replaces every map/set with a sorted collection. | Delete. Serialization need not impose presentation order. |
| `seon.schema.datahike/storage-string` | Prints the canonicalized tree to an EDN string. | Delete. |
| `seon.schema.datahike/encode-attribute-value-in` | Validates and prints a query/index/write value. | Delete EDN branch and its query callers. Native values pass unchanged. |
| `seon.schema.datahike/decode-canonical-edn` | Reads stored text, then re-encodes it to insist on canonical spelling. | Delete. Reads of committed data should not reproduce the write work. |
| `seon.schema.datahike/attribute-decoder-in` | Returns identity or a content-cached EDN decoder. | Delete generic decoder selection once no generic encoding remains. |
| `seon.schema.datahike/decode-attribute-value-in` | Acquires the decoder for one value. | Delete. |
| `seon.schema.datahike/decode-attribute-value` | Acquires the declaration population and decodes. | Delete. |

Canonical spelling currently serves whole-value equality in string indexes. Removing sorting while retaining this representation would change equality behavior: two equal maps can produce different strings. Therefore removing these functions is a representation change, not permission to replace `storage-string` with an unsorted `pr-str`. No proposed path prints to compare, sort, identify, or check readability.

### Transaction grammar and vector writes

| Function | Job | Disposition |
|---|---|---|
| `seon.schema.datahike/vector-shaped-attr-in?` | Derives whether the Malli shape is vector/sequential. | Keep a retained declaration fact. |
| `seon.schema.datahike/vector-attribute-in?` | Tests shape and installed cell-era switch. | Keep shape selection; a reset permits removing pre-cell compatibility. |
| `seon.schema.datahike/vector-attributes-in` | Filters distinct attributes once. | Keep if consumers need this set. |
| `seon.schema.datahike/logical-collection` | Returns vector for ordered attributes, set for other many values. | Keep logical collection semantics. |
| `seon.schema.datahike/refuse-slot!` | Throws codec/vector-specific errors. | Keep required vector refusals; delete EDN variants. |
| `seon.schema.datahike/encoded-operation?` | Detects already physical operations by metadata. | Retain only while recursive vector conversion needs it. |
| `seon.schema.datahike/mark-encoded` | Adds that metadata marker. | Same. |
| `seon.schema.datahike/already-encoded` | Marks a physical conversion transaction. | Delete historical conversion-only uses after reset; audit other callers. |
| `seon.schema.datahike/encode-call-output-in` | Calls a transaction function, propagates returned errors, encodes its output using in-transaction schema. | Vector output still needs adaptation. Error translation is independently necessary; do not delete it accidentally with EDN. |
| `seon.schema.datahike/identity-attr-in?` | Identifies upsert keys when constructing vector handles. | Keep in vector write plan. |
| `seon.schema.datahike/ref-attr-in?` | Distinguishes entity values from opaque data on nested reads. | Keep for vector/component reconstruction. |
| `seon.schema.datahike/new-entity-id?` | Classifies nil/string/negative ids as newly allocated. | Keep only the adapter's needed grammar; Datahike still resolves ids. |
| `seon.schema.datahike/vector-handle` | Finds existing id or unique identity for an upserted vector owner. | Keep. |
| `seon.schema.datahike/two-keyword-members?` | Avoids Datahike's collection/lookup-ref ambiguity by explicit datom operations. | Fix the dependency ambiguity, then delete this workaround. Its current test is broader than the fork's actual identity-key condition. |
| `seon.schema.datahike/encode-value` | Recursively processes nested maps and collections containing entity maps. | Narrow to declared refs/components/vectors; native Datahike already handles ordinary nested entity maps. |
| `seon.schema.datahike/encode-entity` | Encodes union slots; creates cells or deferred vector requests; handles two-keyword members. | Delete EDN/lookup-workaround arms; keep the vector transformation. |
| `seon.schema.datahike/vector-request` | Gives raw operations append/remove-all/clear semantics on logical vectors. | Keep while that public contract remains. |
| `seon.schema.datahike/encode-transaction-data-in` | Scans tempid identity assertions, encodes maps/operations, wraps transaction functions, batches vector requests. | Reduce to vector adaptation. No whole-value serialization. |
| `seon.schema.datahike/encode-transaction-in` | Supplies declaration context and delegates transaction encoding. | Keep one vector seam if required; no generic encoder. |
| `seon.schema.datahike/encode-transaction` | Acquires declaration population for the context-free entrance. | Audit callers; avoid an ambient duplicate of the explicit writer path. |

`seon.db/element-kind`, `seon.db/comparable-component`, `seon.db/element-key`, `seon.db/matched-cells`, `seon.db/vector-diff`, and `seon.db/vectors-tx` implement existing-vector matching and updates. Their work preserves native ref semantics and retained component identity, not a text representation. Component value matching can read complete children; it is not O(1) per changed element. `seon.db/vector-migration-tx` is historical conversion machinery a reset can retire.

### Vector reads and Datalog

| Functions/data | Job | Disposition |
|---|---|---|
| `seon.schema.datahike/wildcard-spec`, `seon.schema.datahike/id-spec` | Retained Datahike pull-parser results. | Reuse the dependency parser. |
| `seon.schema.datahike/cell-attribute` | Classifies a selector entry as vector, reverse vector, or plain. | Keep vector plan derivation. |
| `seon.schema.datahike/reads-cells?` | Detects vector work recursively in a selector. | Keep as a once-per-selector decision. |
| `seon.schema.datahike/physical-selector` | Converts logical vector pulls to shared cells/presence pulls. | Required by transparent vectors unless moved into Datahike. |
| `seon.schema.datahike/vector-pull` | Caches physical selector plus logical specification by schema content/selector. | Keep content reuse; no need to resolve on every returned value. |
| `seon.schema.datahike/decode-pulled` | Reconstructs nested selected entity values. | Keep only where vector/ref shape requires it. |
| `seon.schema.datahike/decode-vectors` | Groups cells, orders by position, reconstructs vectors, applies aliases/defaults/limits and reverse refs. | Keep. This is domain order, not canonical sorting of arbitrary values. |
| `seon.schema.datahike/logical-attribute?` | Hides physical bridge attributes in variable-attribute queries. | Keep semantics; avoid an armed call per physical datom. Existing task owns that defect. |
| `seon.schema.datahike/query-variable?`, `seon.schema.datahike/query-source?` | Recognize query variable/source symbols. | Use Datahike's parsed query nodes where possible, rather than another query grammar. |
| `seon.schema.datahike/clause-variables` | Collects variables for introduced joins. | Same: dependency analysis should supply binding information. |
| `seon.schema.datahike/vector-position?` | Classifies an attribute position using constants or input-bound alternatives. | Keep plan-time classification if transparent membership remains. |
| `seon.schema.datahike/lower-pattern` | Turns vector membership into a cell join; unknown attributes require a union. | Required logical mapping, not EDN encoding. |
| `seon.schema.datahike/lower-branch` | Wraps multiple lowered clauses in `and`. | Part of that mapping. |
| `seon.schema.datahike/lower-clause` | Rewrites negation/disjunction, `missing?`, and rejects scalar functions over vectors. | Preserve binding semantics; belongs near the dependency query planner if a general extension is later built. |
| `seon.schema.datahike/lower-clauses` | Derives sibling variable bindings per clause. | Current sibling rescanning is potentially quadratic in clause count; reuse parsed binding analysis. |
| `seon.schema.datahike/lower-where` | Lowers clauses and gives introduced variables stable names for query-cache reuse. | Keep only vector mapping; reuse plans for unchanged inputs. |

The EDN codec also spills into `seon.db/edn-encoded?`, `seon.db/decode-attribute-value`, `seon.db/decode-attribute-maps`, `seon.db/query-find-attributes`, `seon.db/encode-query-request`, `seon.db/decode-query-field`, `seon.db/decode-query-tuple`, and `seon.db/decode-query-return-maps`. Delete their EDN responsibilities with the representation. A query should not have to select an extra attribute just so Seon can guess which decoder produced a result. Preserve the parts that deal with vector pulls, return-map syntax, read evidence, and useful admission errors; their removal does not follow from deleting EDN strings.

## 3. What Datahike supplies, and what it does not

### Native values, tuples, and many-valued attributes

The pinned `datahike.schema` admits booleans, keywords, symbols, strings, longs, floats, doubles, bigints, bigdecimals, numbers, instants, UUIDs, bytes, refs, tuples and schema-specific enum types. `datahike.db.transaction/validate-val` rejects top-level nil and checks types in write mode. Symbols and keywords need no textual round trip. A live storeless probe transacted and pulled an unusual symbol built with `(symbol "a b" "x y")` directly.

`datahike.db.transaction/explode` and `datahike.db.transaction/maybe-wrap-multival` expand a many-valued entity-map entry into separate datoms. Many means set semantics: no order, no duplicate datom, no positive empty collection fact. Nested maps under declared refs become entities using native transaction processing. Components give cascading retraction; they do not alone give Seon's exclusive ownership or required-peer guarantees.

`datahike.db.transaction/check-tuple` supplies homogeneous, heterogeneous, and composite-tuple checks. The homogeneous route caps length at eight. The heterogeneous route checks declared arity and **every** member; the old all-invalid-members bug is fixed by fork commit **76c2a9e9**. This lane's `[1 "x"]` tuple round-tripped; `["bad" 1]` under long/string refused. Do not copy the outdated contrary claim in `.agents/skills/datahike/SKILL.md` into an implementation card. That skill correction is outside this lane's sole-file ownership and is recorded here for its owner.

Tuples are whole values, good for a small fixed observation or composite key. They are not an arbitrary-length list abstraction, and refs nested in an arbitrary tuple/EDN value do not acquire the direct ref attribute's sweep/cascade/tempid behavior. Datomic's documented scalar tuple domain and 2–8 slots are useful design guidance, not proof that every detail of the fork's validation matches Datomic. [Datomic schema reference](https://docs.datomic.com/schema/schema-reference.html#tuples).

Datahike's own [schema documentation](https://github.com/replikativ/datahike/blob/main/doc/schema.md) distinguishes schema-on-read and schema-on-write and documents its tuple declarations. The fork source is the authority for the behavior just measured. The Datomic [ordered-list discussion](https://forum.datomic.com/t/handling-ordered-lists/305) records the positional-list idiom and its central tradeoff: whether individual elements must be queried. Seon's cells implement that idiom. This discussion is historical design context, not a new dependency API guarantee.

### Schema-on-read is not safe arbitrary-EDN storage

In read mode, `datahike.db.transaction/validate-val` skips type validation; it does not install a structural ordering, new query semantics, or serialization support for arbitrary objects.

Live pure probes (6 ms combined):

| Input pair on one card-many attribute in read mode | `datahike.datom/safe-compare` | `datahike.datom/compare-value` | Values retained after `with` |
|---|---:|---|---|
| `[1 2]`, `[1 "x"]` | 0 | ClassCastException | only `[1 2]` |
| `{:x 1}`, `{:x 2}` | 0 | ClassCastException | only `{:x 1}` |
| `:a`, `"a"` | -7 | -7 | comparison only |

At this pin, each losing transaction reported **one** matching effective added datom, not the two reported in the older vector study. Both observations demonstrate loss, but the exact report count must not be copied across pins.

The cause is explicit: `datahike.datom/compare-value` delegates to Clojure comparison, falling back on class order for unlike classes; `datahike.datom/safe-compare` catches broader failures and uses `datahike.datom/class-order`. Two unequal values of the same incomparable class therefore compare as zero. Quick/prefix index comparators use `compare-value` directly while boundary comparisons use `safe-compare`; fixing only one route leaves inconsistent index semantics. The unlike-class arm came from upstream **515786f6 / #955**, ported as **fe1d4e26**. It does not implement recursive EDN ordering.

`datahike.schema` defines an internal `:db.type/any` predicate, used for secondary-index configuration, but does not include it in the public value-type enumeration. A normal schema transaction installing it refused with `:transact/schema` (7 ms combined probe). Supplying it directly to the lower-level `empty-db` schema happened to accept and store one map; **that constructor bypass is not evidence that the public schema API supports any**.

Queries are route-dependent today. A three-element vector stored under read mode was returned by an unbound query and matched by an input-bound compiled query. Yet `datahike.db.search/validate-pattern` rejected the same value on a non-tuple attribute with `:search/pattern`. `datahike.query.lower/plan-rule-op` also restricts collection constants. Thus “vectors always fail queries” is too broad, and “my query worked, arbitrary EDN is supported” is also wrong. A native extension must cover pattern search, compiled queries, rules, lookup-ref disambiguation, retraction and history consistently.

### Persistence already has a serializer

`datahike.index.persistent-set/datom-write-handler` writes the datom vector to the dependency serializer; `datahike.index/add-konserve-handlers` installs persistent-set and datom handlers. `konserve.serializers/FressianSerializer` delegates to `clojure.data.fressian` with its Clojure and custom handlers. These are binary persistence mechanisms, not presentation printers. They can carry structured data without imposing printed EDN as an intermediate representation. They do **not** make the Datahike comparator safe.

Live `clojure.data.fressian/write` → `clojure.data.fressian/read` returned equal maps, nested vector/set/map values, and the unusual symbol and keyword without Seon's tagged text readers. Two equal maps with reversed insertion order produced **different bytes** (14 bytes each). The third nested example occupied 68 bytes. Combined probe: 26 ms. This is an in-memory serialization round trip, **not** a file-store close/reopen proof and not support for every EDN extension, record, host object or backend.

Native bytes also worked in the pure Datahike probe: pull compared equal through `datahike.array/a=`, and an equal freshly allocated byte array matched a query. `datahike.array/compare-arrays`, `datahike.array/a=`, and `datahike.array/wrap-comparable` are the existing byte seams. Do not substitute Java array identity or claim that serializing equal maps gives equal index keys. Datomic itself now documents byte-array value-semantics limitations and deprecates that type; those Datomic restrictions are not identical to this fork's measured behavior. [Datomic bytes limitations](https://docs.datomic.com/schema/schema-reference.html#limitations-of-bytes).

### A total comparator is necessary, but not sufficient

A safe structured-value extension needs one declared persisted value domain and consistent comparator/equality/hash behavior across all index routes. It must survive close/reopen, history, branching, same-value upsert/retract and query joins. It must distinguish unequal values even on hash collisions; **a value hash alone is not a total order**. Clojure numeric comparison and equality differ for some representations, NaN needs explicit treatment, equal maps need representation-independent order, and list/vector equality needs a deliberate compatible policy. Nested bytes and tagged literals require explicit support or a loud admission error. There is no justification for admitting arbitrary JVM objects, unbounded lazy sequences or live resources as durable values.

The [Clojure comparator guide](https://clojure.org/guides/comparators) recommends looking at Arrangement. I read [Arrangement's source](https://github.com/greglook/clj-arrangement/blob/main/src/arrangement/core.cljc): `arrangement.core/rank` recursively compares collections, but its unsupported-object branch uses string representation, and its sequence-kind/numeric choices are not automatically Datahike's equality policy. **Do not install it blindly as a durable index comparator.** A dependency-quality change could reuse a proven library over a restricted, validated domain or improve the maintained dependency. The exact unsupported domain must be brought to the owner; copying Seon's printer comparator into Datahike is not an acceptable shortcut. `seon.print/compare-values` itself returns zero for some incomparable leaves.

## 4. Which of the 20 attributes actually need encoding?

This is a disposition inventory, not permission to delete an attribute merely because it has zero datoms. “Native union” below means split into meaningful typed facts or use the proposed dependency extension; Datahike write mode does not currently have a public mixed-type attribute.

| Attribute | Declared logical shape | Current datoms | Smallest justified direction |
|---|---|---:|---|
| `:datahike.read/dependency-plan` | `:all` or map of source plans/patterns | 123 | Delete durable refresh use with S4-06. Keep process-local plans as native values. Audit publication/other retained evidence before removing the attribute entirely. |
| `:datahike.read/revision` | Three map variants | 123 | Same. Do not normalize a retiring cache-revision map into a new entity tree. |
| `:seon.print/default` | boolean/int/keyword | 5 | `seon.print/shipped-option-defaults` reads Malli properties. Stop lifting this redundant property unless a separate persisted consumer is demonstrated; keep the actual native metadata/default. |
| `:my.shell/cwd-observation` | stat or error maps | 0 | Observation rule: shown text plus ids; independently queried facts only. No generic serialized error tree. |
| `:seon.cluster.wake/undeliverable-wake` | string/keyword | 0 | Native typed identity/value field at the error producer, if durable at all. |
| `:seon.db/read-request` | query/pull/index request maps | 0 | Primarily process-local replay. Code may contain host patterns; it is not arbitrary EDN. Retire durable refresh usage; preserve only a demonstrated durable consumer. |
| `:seon.error/member` | qualified keyword/symbol | 0 | Error storage rule first; native fields if independently queried. |
| `:seon.error.evidence/value` | scalar union incl. instant | 0 | Full error value stays runtime-only; shown text/ids persist. Queryable evidence facts must name their consumer. |
| `:seon.error.key/scalar` | keyword/symbol/string/int/boolean | 0 | Same; no reason for generic string serialization. |
| `:seon.flow/submission-id` | UUID/keyword/string | 0 | Preserve the owner's actual id domain; distinguish kinds only if semantics require them. |
| `:seon.listen/value` | arbitrary value or empty vector | 0 | Genuine possible residual: persisted listener equality needs native value semantics. Audit actual admitted patterns, including whether values are ref ids, scalars, or tuples; a binary blob cannot transparently replace a Datalog equality constraint. |
| `:seon.program/blocked-subject` | symbol/qualified keyword | 0 | Native subject identity fields or runtime diagnostic plus shown text. |
| `:seon.program/not-found` | symbol/qualified keyword | 0 | Same. |
| `:seon.render.walk/continuation-node` | symbol/blob tuple/lookup union | 0 | Inspect current continuation consumer after printer cut; retain native handle facts only if needed. |
| `:seon.render.walk/continuation-subject` | lookup union | 0 | Same. |
| `:seon.render.walk/missing-lookup` | lookup union | 0 | Observation, not a live entity relation; persist only what its consumer needs. |
| `:seon.sci.eval/acquisition-member` | qualified symbol/keyword | 0 | Native identity or shown diagnostic. |
| `:seon.sci.eval/documentation-unavailable` | authored scalar/sequence/map data, including nil | 0 | Runtime value/handle and shown diagnostic. No top-level stored nil loophole disguised as the string `"nil"`. |
| `:seon.sci.eval/row-member` | symbol/qualified keyword | 0 | Its own description says RETIRING, no producer/reader; remove with its owning retirement. |
| `:seon.sci.reader/refused-token` | symbol/string | 0 | Preserve the source/token or shown diagnostic according to actual consumer, without printing native symbols into storage strings. |

The source search for `:seon.print/default` found its consumers in `seon.print/shipped-option-defaults` and schema metadata. The generic schema-entity publication path remains a consumer of *storable properties*, but that is the producer of these datoms, not evidence of a reader that needs them. The recommendation to stop lifting it is a source-backed inference; no deletion was tested here.

Importantly, read-evidence names also occur in `seon.render`, `seon.render.web`, `seon.health` and `seon.db`. Some are process-local cache dependencies. **S4-06 is a consumer cut, not a global search-and-delete of read evidence.** A persisted branch/publication provenance consumer, if it remains, must receive ordinary facts or a deliberate data value. Its existence is the decision point for options B/C, not the historic 66-second recording alone.

## 5. Three priced options

Ordered by the smallest viable architecture first. Effort ranges are engineering estimates for implementation and focused proofs, not measurements, commitments, or authorization to launch lanes. Every option keeps the currently required transparent vector behavior.

| Option | Guarantee | Cost | Sacrifice and reset/conversion |
|---|---|---|---|
| **A — Native facts, declared cells, no general value codec (recommended)** | Native scalars/symbols/tuples/refs stay native; vectors retain order, duplicates, empty presence and element lifecycle; runtime values and shown text follow their existing owners. | Coordinate S4-06/error/printer cuts; audit the 20 attributes; remove text encoder/decoder and EDN query translation. Roughly 1–3 engineer-days across owners after prerequisite cuts, in small proven slices. No new serializer or universal value type. | Some heterogeneous persisted APIs need meaningful typed fields or retire with consumers. Cannot promise arbitrary EDN as an indexed datom. Reset disposable data from committed declarations; do not retain dual EDN/native readers forever. |
| **B — Explicit opaque data boundary using the dependency binary serializer** | A demonstrated durable *data* payload round-trips without printing, through Datahike bytes or Konserve. Native metadata/ref fields stay queryable. | A small producer-owned adapter, reuse `clojure.data.fressian`/Konserve, plus value-domain validation and file-reopen tests; roughly 1–2 engineer-days for one payload family. O(payload size) serialization/deserialization, only when writing/fetching that payload. | Opaque payloads lose structural Datalog equality/indexing and nested ref lifecycle. Equal Clojure maps need not produce equal bytes. No automatic byte codec for every union and no serialized copy of a live result. Binary inline-or-blob selection needs an extension of the existing storage owner; `seon.blob/inline-or-stage` currently accepts text. Reset the affected representation; never infer legacy format from content. |
| **C — A native, finite structured-value type in the Datahike fork** | Existing heterogeneous-value API can remain data through transaction, pull and query, with no Seon EDN decoder; equality follows the declared storage semantics. | Comparator/equality/domain admission, public schema type, query/search/rule handling, serialization verification, fork tests and pin bump; roughly 3–5 engineer-days, subject to library/domain research. This is more than `safe-compare`. | Dependency maintenance and index-format semantics become obligations. Whole-value indexing can compare/serialize large trees; no element ref lifecycle. It does not replace vector cells. Reset/rebuild **all indexes affected by changed ordering**, including retained temporal roots/branches; never reopen old sorted indexes under a new comparator and hope. |

Option B is an escape for actual opaque domain data, not a way to preserve copies of evaluations/errors that the storage policy forbids. For the current live hotspot, option A removes the data and generic work instead of changing its wire format. If a consumer audit demonstrates broadly used persistent heterogeneous equality—especially listeners—option C becomes the appropriate dependency-owned solution. The earlier vector study explicitly deferred such a type; this note does not silently reverse that decision.

### How small can A be?

The target **general value codec is zero**: no recursive EDN canonicalizer, identifier readability test, storage printer, query literal string encoder, result-column decoder, or decoded-EDN cache.

The schema bridge is still meaningful. Roughly the first **390 lines** of the current owner concern schema declarations/metadata, not serialization. About **400 lines** explicitly concern vector writes/pulls/query rewriting, before shared transaction/error glue; `seon.db` carries another substantial vector/write-validation share. Removing EDN alone will not turn 1,180 lines into a 50-line file. A realistic target is **a few hundred lines of schema derivation and a few hundred lines of vector adaptation**, with measured simplification of shared glue; this is a structural estimate, not a promised line count. Preserve the vector regressions rather than retaining a particular implementation size.

To make *all* query/pull adaptation disappear, either change the owner-approved vector API to expose positional entities, or move a general ordered-relation facility into Datahike. Neither follows from the codec evidence. Merely wrapping the existing 500-line conversion in a new namespace does not remove a mechanism. Using native tuples for vectors would break both length and ref semantics.

A small dependency fix worth doing independently is the collection/lookup-ref ambiguity in `datahike.db.transaction/maybe-wrap-multival`: use the declared ref context before treating a keyword-headed pair as one reference. This lets Seon delete `two-keyword-members?` and its operation expansion. The current fork narrowed the guess to identity-headed pairs, so the old assertion that *every* two-keyword collection triggers it is stale; the remaining identity-headed case still matters. Prove it with native transactions before deleting the workaround.

## 6. Cost model and acceptance criteria

Let Q be query/selector size, R the returned datoms/nodes, U the distinct attributes involved, N the database datom count, D the transaction's effective datoms, B the size of newly persisted payloads, and V the selected/touched vector cells.

**Ordinary native query.** Seon adds custody, error translation and required read evidence, not a generic value traversal. A reused query/storage plan should need O(1) plan lookup plus binding work; a new plan does O(Q + U) metadata work, then Datahike owns index selection and joins. There should be **zero calls** to storage printing, round-trip checking, canonical sorting or EDN decoding. Runtime depends on the query's actual joins/ranges; it cannot honestly be promised O(log N) for every query. A one-pattern indexed lookup should be near the corresponding Datahike lookup, not proportional to program size, stored schema count, or retained cache buckets (DH-5 owns the latter).

**Ordinary native pull.** Datahike performs the selected reads and assembles the result. Seon need not copy/walk the result a second time to discover whether arbitrary maps contain encoded attributes. Cost follows selected entities/attributes and output R, including any genuine contract/evidence work. For vectors, pay for the selected cells and their positions: current reconstruction is O(V log V) sorting plus O(V) assembly, with component/index read costs. The existing shared-cell layout can read sibling vectors; that is an explicit cost to shrink, not an excuse for unrelated schema work. There is no constant-time claim for returning a large value.

**Scalar write.** Retain one per-attribute compiled storage/validation plan; native values pass through. Seon representation work should be O(1) per scalar datom, plus unavoidable validation of its actual value. Datahike index work is roughly O(D log N × comparison cost), with node serialization/fsync costs separate. For variable-sized strings/bytes/structured values, “per datom” alone hides the size: charge O(B) serialization and any comparator's inspected prefix. Do not repeat the same evidence row thousands of times or recursively print it before handing it to the writer.

**Vector write.** Current matching is linear after order reconstruction, plus component-value reads where requested; a dense-position insertion/reorder can change O(V) positions. Preserve zero effective datoms for reasserting an unchanged vector. The native representation is about three datoms per element plus one presence datom, so one logical append is not one physical datom. Batch vector requests once per transaction; do not reintroduce the old per-parent transaction-function cost proportional to prior effective datoms.

**Budget expectations.** The measured warm one-value native reads were 3–6 µs; they establish a scale for local overhead, not a hard service-level bound. Aim for warm small reads in tens of microseconds when contracts/evidence require extra work, and justify larger numbers by the values actually touched. All ordinary complete operations remain under the project's one-second requirement; the eval request's single durable record still has its separate under-100-ms target. Codec deletion alone does not prove that target: DH-4/5/8, writer validation, storage serialization and recording multiplicity have separate costs. No timing for an unimplemented option is presented as measured.

**One storage decision.** `seon.blob/inline-or-stage` is the installed decision for shown text using the existing configured UTF-8 threshold. Keep it at the producer; do not make the transaction codec measure and route everything again. `seon.blob/stage-binary!` accepts a stream and stages bytes but is not a generic equivalent of the text fact-producing function. A binary-data option must extend the existing owner and preserve its publication/retention contract, not add a parallel blob store. Large runtime values stay in `seon.cache`; a large tree returned by a read is not automatically a durable payload.

## 7. Conversion and proof boundaries

1. Start with the actual consumer cut: S4-06's stored refresh evidence, error storage roles, dead row-member, and unjustified printer-property lifting. Stop recording retired data, retire required schema entries with their callers, and keep useful in-memory read plans. A zero-count attribute still needs a consumer decision.
2. For each surviving attribute, choose native scalar/tuple/ref/components by domain meaning. Use components for independently queried children; **do not build a generic EDN-to-entity-tree codec**. If its only consumer needs the complete opaque datum, price B. If it needs heterogeneous value equality/querying, price C.
3. Remove EDN writes, reads, query translation and caches together; no call site may keep comparing the new native value against a legacy string. Eliminate physical/logical compatibility markers whose only purpose was preserving the discarded store. Keep vector transformations and native writer validation.
4. Prove candidate publication/adoption on a disposable branch and the named reaching tests before root's reset/restart. The owner has authorized disposable data for this design; **this lane performed no reset**. Rebuild from committed program/schema inputs and regenerate behavior evidence. A comparator change requires an index rebuild, not merely a new decoder.
5. Measure the supplied current-state and recording workloads before/after on equivalent data: same logical result, no lost union alternatives, zero storage-printer calls, metadata derivations proportional to U, one recording transaction, no duplicate complete evidence rows, and timings/allocations split by producer and writer. Report inability to reach a changed host-bound owner explicitly.

Existing proof subjects to retain or adapt include `seon.db-test/final-entity-keyword-sets-are-not-lookup-refs`, `seon.db-test/query-symbol-values-use-the-declared-codec-in-every-binding-shape`, `seon.schema.vector-storage-test/vectors-round-trip-with-order-duplicates-and-presence`, `seon.schema.vector-storage-test/peer-vectors-keep-ref-semantics`, `seon.schema.vector-storage-test/component-vectors-keep-identity`, `seon.schema.vector-storage-test/pulls-read-logical-vectors-at-every-basis`, and `seon.schema.vector-storage-test/datalog-over-a-vector-attribute-means-membership`. Their existence is source-verified, not a claim that this lane ran them or that they are green. S4-06 names the proposed proof `seon.turn-test/settlement-does-not-store-refresh-evidence`; its ledger entry is not proof of installation.

For C, fork proofs must include: unequal same-class maps/vectors retained separately; collision-safe ordering; antisymmetry/transitivity/equality consistency across the declared domain; all quick/boundary/prefix routes; insert/upsert/retract; scalar, collection and tuple query inputs, rules and variable attributes; history/as-of; close/reopen through the real serializer; and scalar fast-path regressions. No new stored type is accepted merely because `with` on an empty in-memory value succeeds.

## 8. Reproducible probes

These forms are the reusable evidence embedded in this documentation-only artifact. All use MCP JVM mode as `codec-research-astra`; all database writes below are to immutable storeless values, never default's connection. Returned result handles identify the captured observations, but may expire; the forms and relevant values are preserved here.

### A. Census (`result/e1fac2feb71d7`, 1,694 ms)

```clojure
(let [database @(seon.cluster.boot/connection "default")
      projection (seon.db/carried-projection database)
      installed (datahike.db.interface/-schema database)
      encoded (seon.schema/call-with-projection
               projection
               #(into #{} (filter (fn [a]
                                   (and (qualified-keyword? a)
                                        (seon.schema.datahike/edn-encoded-attr-in? projection a))))
                      (keys installed)))
      vectors (seon.schema.datahike/vector-attributes-in projection (keys installed))
      started (System/nanoTime)
      counts (reduce (fn [acc datom] (update acc (:a datom) (fnil inc 0)))
                     {} (datahike.db.interface/datoms database :aevt []))]
  {:basis (:max-tx database)
   :installed-attributes (count installed)
   :types (frequencies (map :db/valueType (vals installed)))
   :edn (into (sorted-map) (map (fn [a] [a (get counts a 0)])) encoded)
   :vectors (into (sorted-map) (map (fn [a] [a (get counts a 0)])) vectors)
   :datoms (reduce + (vals counts))
   :scan-ms (/ (- (System/nanoTime) started) 1000000.0)})
```

The original also read `:datahike/commit-id` from database metadata and got nil; this was the wrong accessor, not evidence of an uncommitted database. Subsequent probes used `seon.db/commit-id`.

### B. Targeted sizes and reader cost (`result/e239ed0e2230f`, 30 ms)

```clojure
(let [database @(seon.cluster.boot/connection "default")]
  {:basis (:max-tx database) :commit (seon.db/commit-id database)
   :values
   (mapv
    (fn [attribute]
      (let [values (mapv :v (datahike.db.interface/datoms database :aevt [attribute]))
            sizes (mapv #(alength (.getBytes ^String % java.nio.charset.StandardCharsets/UTF_8)) values)
            largest (when (seq values) (apply max-key count values))
            decoded (when largest (clojure.edn/read-string largest))
            started (System/nanoTime)
            _ (dotimes [_ 100] (when largest (clojure.edn/read-string largest)))
            read-ms (/ (- (System/nanoTime) started) 1000000.0)]
        {:attribute attribute :datoms (count values)
         :total-bytes (reduce + sizes) :max-bytes (reduce max 0 sizes)
         :decoded-type (when decoded (.getName (class decoded)))
         :decoded-top-keys (when (map? decoded) (vec (keys decoded)))
         :nodes-in-largest (when decoded (count (tree-seq coll? seq decoded)))
         :edn-read-100-ms read-ms}))
    [:datahike.read/dependency-plan :datahike.read/revision :seon.print/default])
   :cells (count (datahike.db.interface/datoms database :aevt [:seon.db.vector/position]))
   :presence (count (datahike.db.interface/datoms database :aevt [:seon.db.vector/present]))})
```

### C. Codec work (`result/e1429665b7b99`, 7 ms)

```clojure
(let [database @(seon.cluster.boot/connection "default")
      projection (seon.db/carried-projection database)
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      thread (.threadId (Thread/currentThread))
      measure (fn [f n]
                (let [bytes (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread)
                      started (System/nanoTime)]
                  (dotimes [_ n] (f))
                  {:n n :ms (/ (- (System/nanoTime) started) 1000000.0)
                   :alloc-mb (/ (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread)
                                   bytes) 1048576.0)}))]
  (seon.schema/call-with-projection
   projection
   #(mapv
     (fn [attribute]
       (let [stored (apply max-key count (map :v (datahike.db.interface/datoms database :aevt [attribute])))
             value (clojure.edn/read-string stored)]
         {:attribute attribute
          :encode (measure (fn [] (seon.schema.datahike/encode-attribute-value-in projection attribute value)) 1)
          :canonical-decode (measure (fn [] (#'seon.schema.datahike/decode-canonical-edn attribute stored)) 1)
          :warm-decode (do (seon.schema.datahike/decode-attribute-value-in projection attribute stored)
                          (measure (fn [] (seon.schema.datahike/decode-attribute-value-in projection attribute stored))
                                   100))}))
     [:datahike.read/dependency-plan :datahike.read/revision :seon.print/default])))
```

### D. Native failures and successes (`result/e8f1bc39b4873`, 6 ms)

```clojure
(let [cases
      [[:mixed-vectors {:codec/m {:db/cardinality :db.cardinality/many}}
        {:schema-flexibility :read}
        [[:db/add 101 :codec/m [1 2]] [:db/add 101 :codec/m [1 "x"]]]]
       [:maps {:codec/m {:db/cardinality :db.cardinality/many}}
        {:schema-flexibility :read}
        [[:db/add 101 :codec/m {:x 1}] [:db/add 101 :codec/m {:x 2}]]]
       [:symbol {:codec/m {:db/valueType :db.type/symbol :db/cardinality :db.cardinality/one}}
        {:schema-flexibility :write} [[:db/add 101 :codec/m (symbol "a b" "x y")]]]
       [:tuple {:codec/m {:db/valueType :db.type/tuple :db/tupleTypes [:db.type/long :db.type/string]
                         :db/cardinality :db.cardinality/one}}
        {:schema-flexibility :write} [[:db/add 101 :codec/m [1 "x"]]]]
       [:invalid-tuple {:codec/m {:db/valueType :db.type/tuple :db/tupleTypes [:db.type/long :db.type/string]
                                 :db/cardinality :db.cardinality/one}}
        {:schema-flexibility :write} [[:db/add 101 :codec/m ["bad" 1]]]]
       [:any {:codec/m {:db/valueType :db.type/any :db/cardinality :db.cardinality/one}}
        {:schema-flexibility :write} [[:db/add 101 :codec/m {:x 1}]]]]]
  {:comparisons
   (mapv (fn [[left right]]
           {:values [left right] :safe (#'datahike.datom/safe-compare left right)
            :quick (try (datahike.datom/compare-value left right)
                        (catch Exception failure {:exception (.getName (class failure))}))})
         [[[1 2] [1 "x"]] [{:x 1} {:x 2}] [:a "a"]])
   :cases
   (mapv (fn [[label schema config tx]]
           (try
             (let [report (datahike.api/with (datahike.db/empty-db schema config) tx)
                   database (:db-after report)]
               {:case label :reported (count (filter #(= :codec/m (:a %)) (:tx-data report)))
                :stored (mapv :v (datahike.db.interface/datoms database :eavt [101 :codec/m]))
                :pulled (datahike.api/pull database [:codec/m] 101)})
             (catch Exception failure
               {:case label :error (:error (ex-data failure)) :exception (.getName (class failure))})))
         cases)})
```

### E. Public schema, query routes, bytes and loaded sources (`result/e6d368dd1b588`, 7 ms)

```clojure
(let [attempt (fn [f] (try (f) (catch Exception failure
                                 {:error (:error (ex-data failure)) :exception (.getName (class failure))})))
      empty-write (datahike.db/empty-db nil {:schema-flexibility :write})
      bytes-schema {:codec/b {:db/valueType :db.type/bytes :db/cardinality :db.cardinality/one :db/index true}}
      bytes-value (byte-array [1 2 3])
      bytes-db (:db-after (datahike.api/with (datahike.db/empty-db bytes-schema {:schema-flexibility :write})
                                            [[:db/add 101 :codec/b bytes-value]]))
      vector-db (:db-after (datahike.api/with (datahike.db/empty-db nil {:schema-flexibility :read})
                                             [[:db/add 101 :codec/v [1 2 3]]]))]
  {:install-any (attempt (fn [] (datahike.api/with empty-write
                                [{:db/ident :codec/value :db/valueType :db.type/any
                                  :db/cardinality :db.cardinality/one}]) :accepted))
   :vector-unbound-query (attempt #(datahike.api/q '[:find ?v :where [?e :codec/v ?v]] vector-db))
   :vector-bound-query (attempt #(datahike.api/q '[:find ?e :in $ ?v :where [?e :codec/v ?v]] vector-db [1 2 3]))
   :bytes-query (attempt #(datahike.api/q '[:find ?e :in $ ?b :where [?e :codec/b ?b]] bytes-db (byte-array [1 2 3])))
   :bytes-pull-equal (datahike.array/a= bytes-value (:codec/b (datahike.api/pull bytes-db [:codec/b] 101)))
   :loaded-sources (mapv (fn [path] [path (str (clojure.java.io/resource path))])
                        ["datahike/datom.cljc" "datahike/db/transaction.cljc" "seon/schema/datahike.clj"])})
```

### F. Binary values are not canonical equality keys (`result/edc3ad1103d6f`, 26 ms)

```clojure
(let [values [{:a 1 :b 2} (array-map :b 2 :a 1)
              {:value (symbol "a b" "x y") :keyword (keyword "a b" "x y")
               :items [1 "x" {:k #{:b :a}}]}]
      encoded (mapv clojure.data.fressian/write values)]
  {:round-trip (mapv (fn [value buffer] (= value (clojure.data.fressian/read buffer))) values encoded)
   :equal-maps (= (first values) (second values))
   :equal-map-bytes (java.util.Arrays/equals (.array ^java.nio.ByteBuffer (first encoded))
                                           (.array ^java.nio.ByteBuffer (second encoded)))
   :sizes (mapv #(.remaining ^java.nio.ByteBuffer %) encoded)
   :read-api-vector-guard
   (try (let [database (:db-after (datahike.api/with (datahike.db/empty-db nil {:schema-flexibility :read})
                                                   [[:db/add 101 :codec/v [1 2 3]]]))]
          (datahike.db.search/validate-pattern database [nil :codec/v [1 2 3]] false) :accepted)
        (catch Exception failure (:error (ex-data failure))))})
```

### G. Small warm read comparison (`result/e00c2eb40c1e6`, 36 ms)

```clojure
(let [database @(seon.cluster.boot/connection "default")
      eid (:e (first (datahike.db.interface/datoms database :aevt [:datahike.read/dependency-plan])))
      attribute :datahike.read/dependency-plan
      query '[:find ?v . :in $ ?e :where [?e :datahike.read/dependency-plan ?v]]
      measure (fn [f]
                (dotimes [_ 10] (f))
                (let [started (System/nanoTime)]
                  (dotimes [_ 100] (f))
                  (/ (- (System/nanoTime) started) 1000000.0)))]
  {:basis (:max-tx database) :entity eid :iterations 100
   :native-query-ms (measure #(datahike.api/q query database eid))
   :seon-query-ms (measure #(seon.db/q database query eid))
   :native-pull-ms (measure #(datahike.api/pull database [attribute] eid))
   :seon-pull-ms (measure #(seon.db/pull database [attribute] eid))
   :decoded-equal (= (clojure.edn/read-string (get (datahike.api/pull database [attribute] eid) attribute))
                     (get (seon.db/pull database [attribute] eid) attribute))})
```

## 9. Landing evidence

Documentation only; source/test line delta **0/0**. No tests reach a Markdown-only change; no `--test`, candidate JVM boot, publication, adoption, or restart is warranted. Implementation tests named above are future acceptance criteria, not completed proof. The shared tree contains other lanes' edits, including the database and codec owners; those are a foreign boundary, untouched by this lane. The research remains pinned to the verified running archive rather than claiming those edits are installed.

`clj-kondo --lint src script test --cache false` completed in **9,024 ms: 0 errors, 1,489 warnings** (exit 2 for warnings). This was the requested full shared-tree lint, not a claim of zero findings. No warning was introduced by this Markdown-only change. `git diff --check` passed. The document was claimed through `bin/lane claim codec-research-astra`; its containing directory had to be created and claimed first because the installed claim tool refuses a nonexistent unclaimed path.

The final live-source check still named archive a5fc4cb493 and found this agent's branch as `:agent-901ec5eb47d6` (2 ms). Cleanup was attempted through the agent's SCI entrance with `(my.agent/retire-branch! {:seon.store/branch :agent-901ec5eb47d6})`; the tool refused before evaluation with `{:seon.error/message "run transition refused: admission-closed"}`. Branch retirement is **not verified**. The branch is left for root to inspect; no forced release, default write, restart, or interaction with another lane was attempted. This coarse admission refusal is also a context-delivery limitation: it supplied no result handle, timing, or task.

The commit command is `bin/lane commit codec-research-astra -m "Research native Datahike storage and the smallest Seon codec" docs/research/agent-platform/2026-10-08/codec/codec-astra.md`, with the explicit documentation-only exemption and no `--test`. The resulting commit id is reported in the lane's final handoff rather than embedded into its own content.
