---
type: audit
status: static comparison; no implementation or installed proof
created: 2026-09-25
source-pin: d4b3c0e13af811432f20039adc60739f131182a5
scope: src/ and script/; existing dependency and Clojure library replacements
---

# Library replacement audit

Seon already parses, validates, queries and executes through libraries, but retains small parsers, collection traversals and cache mechanics around them. The principal Clojure approach is to consume the library's parsed or queried values and retain only the transformation into Seon's declared data.

| Rank | Candidate | Evidence | Risk / weight | Old definition lines | Replacement allowance | Estimated net deletion | Net / risk weight |
|---|---|---|---|---:|---:|---:|---:|
| 1 | D1 — Destructuring grammar → Malli parse | CONFIRMED | MED / 3 | 69 | 32 | 37 | 12.33 |
| 2 | D2 — Deepest Throwable → stacktrace/root-cause | CONFIRMED | LOW / 1 | 12 | 0 | 12 | 12.00 |
| 3 | D3 — Repeated literal splitting → string/split | CONFIRMED | LOW / 1 | 9 | 2 | 7 | 7.00 |
| 4 | D5 — Three data-tree enumerators → tree-seq | CONFIRMED | MED / 3 | 41 | 24 | 17 | 5.67 |
| 5 | D6 — JVM Var identity → core/symbol | CONFIRMED | LOW / 1 | 5 | 0 | 5 | 5.00 |
| 6 | D7 — Whole-array SHA-256 → hasch | CONFIRMED, JAR source | LOW / 1 | 9 | 4 | 5 | 5.00 |
| 7 | D8 — Registry composition → Malli registry | CONFIRMED comparison; precedence decision | MED / 3 | 23 | 10 | 13 | 4.33 |
| 8 | D9 — Cache installation mechanics → core.cache | CONFIRMED, JAR source | MED / 3 | 27 | 14 | 13 | 4.33 |
| 9 | D10 — Earliest assertion reduction → Datalog min | CONFIRMED | MED / 3 | 22 | 10 | 12 | 4.00 |
| 10 | D11 — Primitive function detection → Malli predicate | CONFIRMED; private dependency seam | MED / 3 | 16 | 5 | 11 | 3.67 |
| 11 | P13 — Key-aware map update → Medley | PLAUSIBLE, JAR source | MED / 3 | 7 | 3 | 4 | 1.33 |
| 12 | P12 — Handwritten schema syntax → Malli traversal | PLAUSIBLE | HIGH / 9 | 21 | 10 | 11 | 1.22 |
| **Compared candidates** | **10 groups** | | | **233** | **101** | **132** | |
| **Plausible candidates** | **2 groups** | | | **28** | **13** | **15** | |
| **Total** | **12 groups / 20 definitions** | | | **261** | **114** | **147** | |

These are **261 physical old definition lines**, not 261 lines removable without replacement. The 114-line allowance and 147-line net are estimates, including small retained delegates and producer adapters but excluding schema/test changes and wider caller rewrites. Risk weights are an explicit ranking heuristic, not measured engineering hours. CONFIRMED means both implementations and relevant callers were compared; it does **not** mean identical behavior on every input, a passed test, or approval to change a contract. Differences and prerequisites below are part of each finding.

The larger printer, MCP, schema-shape mirror, supplied-default and read-currency cuts belong to the [duplicate-paths audit](duplicate-paths-audit-2026-09-25.md), particularly §§3–8 and its R/C ledger. They receive **zero additional credit** here. Functions already appearing in that audit's lexical inventory are also omitted from this retirement ledger: for example, `program/map-binding-entries`, `markdown/split-on-character`, `web.jvm/split-once`, `edit/replace-occurrences`, and `print/compare-values` are discussed only as boundaries or exclusions below.

## Evidence and limits

All first-party ranges refer to **`d4b3c0e13af811432f20039adc60739f131182a5`**, not the moving checkout. The population is **104 source files / 86,330 lines** and **12 script files / 5,604 lines**, **116 files / 91,934 lines** total. `script/` includes its test files; none was executed. The scan enumerated every tracked file, definitions, recursion, reductions, caches, hashing, readers, schemas, query/history operations, printing, channel plumbing and collection utilities. Focused definitions and their callers were then read against dependency implementations. This is a whole-tree candidate sweep, **not a proof that every function has no further simplification**.

Read authorities: `AGENTS.md` end to end, the duplicate-paths audit end to end, `deps.edn`, and the data-oriented Clojure skill. Dependency investigation covered the gitlink census below, public API inventories and the concrete implementation seams cited in the findings. It did **not** read every implementation file in every vendored repository end to end; that portion of the requested breadth remains unfulfilled. No broad claim of complete dependency-source coverage is intended.

There is a material source-layout gap: **hasch, core.cache, core.memoize, medley, tools.reader and arrangement have no standalone source checkout under `reference-code/`**. Their actual local JAR source was read without extracting files. Therefore D7, D9 and P13 cannot honestly provide a nonexistent `reference-code/...:line` citation. They instead provide artifact version, internal source entry and line, plus artifact hashes in §5. Vendoring them would require another file assignment; this audit writes only this document.

Existing classpath files `.cpcache/2773714663.cp` and `.cpcache/573986955.cp` list hasch 0.4.100, core.cache 1.1.234, core.memoize 1.1.266, medley 1.4.0, tools.reader 1.6.0 and arrangement 2.1.0 on the source classpath. `.cpcache/3150042282.cp` and `.cpcache/895205101.cp` additionally list Puget 1.1.2, nREPL 1.0.0 and Hiccup 2.0.0-RC5 on the test classpath. These files establish a cached dependency resolution, **not the loaded JVM's classpath**. Puget's vendored revision must not silently stand in for that resolved JAR. No JVM was contacted to prove loading, arming, publication or adoption.

Git history was consulted for the touched owners, including `9b4a7522f` (program-owned keys), `5d8309442` (zero-argument source/contract round trip), `09d32292b` (instrumentation failure propagation), `b0e68bc03` (projection-owned derivation), `aa91ecb34` (inline rendering) and `17a075c84` (render selection). Those changes explain why a library replacement must preserve data ownership and refusal behavior rather than remove an entire orchestration owner.

## 1. Ranked replacements

### D1. Let Malli parse binding syntax; retain Seon's row transformation

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/program.cljc:795-840` | `sequential-binding-children` | 46 |
| `src/seon/fn/signature.cljc:100-122` | `source-signature` | 23 |

**Replacement:** `malli.core/parse` over `malli.destructure/Binding`, or `malli.destructure/parse` with `::md/inline-schemas false` and use of its `:parsed` member. The core parser is `reference-code/malli/src/malli/core.cljc:2686`; the grammar is `reference-code/malli/src/malli/destructure.cljc:10-59`; the public parser is `:125-162`. Its `"Vector"` production at `:28-39` already separates elements, optional rest and optional alias; `"Binding"` at `:51-56` separates fixed arguments and variadic rest. Read the entire parser/grammar, rather than treating `md/infer` as equivalent.

Convert `program/binding-row` (`program.cljc:869`) and `signature/function-signatures` (`signature.cljc:147`). Parse a nested destructuring vector as the binding inside an outer argument vector; transform its tagged values into the existing `binding-child` rows. Preserve IDs, order, roles, source binding form and numeric arity fields. Keep `binding-row`, `binding-child`, `argument-row` and the source-to-contract join.

**Differences:** the existing sequential parser rejects `[a & more :as all]` because it requires `&` to precede exactly one final element; Malli's nested vector grammar accepts rest followed by `:as`. Existing `source-signature` checks ampersand position, but does not itself validate every nested binding; Malli does. `md/parse` throws Malli's `::invalid-arglist`; `m/parse` exposes `::m/invalid`. Translate that outcome into the existing specific Seon refusal rather than propagating a new exception shape. Do not store `md/parse`'s inferred `:any` schema as an authored contract. Aliases, defaults and spelling evidence must come from parsed/raw binding data, not the inferred input schema.

Both approaches consume finite authored bindings eagerly in O(binding nodes). Compile/use the retained `Binding` schema once; parse when source changes, not per invocation. Parser allocation and actual cold/warm cost are unmeasured. **CONFIRMED / MED.** Replace 69 old lines with an estimated 32 lines of parse-result transformation and refusal adaptation. The prior audit's `map-binding-entries` inventory is excluded; no extra 41-line grammar credit is booked.

### D2. Delete the local deepest-cause traversal

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/error.clj:104-115` | `root-cause` | 12 |

**Replacement:** `clojure.stacktrace/root-cause`, `reference-code/clojure/src/clj/clojure/stacktrace.clj:20-26`. Change `error/message` at `error.clj:139` to call it. Both repeatedly follow the actual Throwable cause and return the last Throwable, with constant stack and O(cause depth) time per failure. Both can fail to terminate on a cyclic custom cause chain; neither is a cycle-safe diagnostic traversal. The declared input is a Throwable, so their different behavior on non-Throwables is outside this contract.

Do **not** substitute `clojure.main/root-cause` (`main.clj:35`): it may stop at a CompilerException with a source file. Do not change `error.refusal/refusal`, which searches error data under a different policy and is already covered by the prior audit. No serialization, error field or wrapper policy changes. **CONFIRMED / LOW; 12 old lines deleted**, with only a call/require substitution.

### D3. Replace repeated HTTP parameter string splitting

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/web/jvm.clj:231-239` | `split-all` | 9 |

**Replacement:** `clojure.string/split`, `reference-code/clojure/src/clj/clojure/string.clj:219-228`, with limit **-1** to retain all parts. Use a literal separator pattern for the actual `";"` calls; quote a general separator with `java.util.regex.Pattern/quote` if retaining a generic helper. Convert the only `split-all` caller, `content-charset` at `:260`.

Preserve empty input (`[""]`), leading/trailing empty parts and repeated separators. The default two-argument split drops trailing empties and is not equivalent. Both return eager vectors and reject nil text; callers already handle missing content type. An empty generic separator currently makes `split-all` nonterminating, whereas regex splitting terminates; all observed separators are nonempty. The old repeated tail substrings can copy quadratic total characters on current JDKs; fixed literal splitting scans O(text length) plus output allocation. No timing improvement is claimed without measurement.

**CONFIRMED / LOW; 9 old lines, 2 replacement lines proposed.** `AGENTS.md` requires owner permission for production regex; this read-only proposal does not grant it. The related `web.jvm/split-once :223-229` and `script/seon/dev/markdown.clj:681-687` `split-on-character` occur in the prior audit's inventory and are excluded. The former has a corresponding limit-2 library split, but receives no credit here.

### D5. Express finite data-tree enumeration with core/tree-seq

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/schema.clj:131-145` | `predicate-symbols-in` | 15 |
| `src/seon/cluster.clj:1474-1487` | `lookup-refs-in` | 14 |
| `src/seon/render/web.clj:1146-1157` | `referenced-entity-ids` | 12 |

**Replacement:** `clojure.core/tree-seq`, `reference-code/clojure/src/clj/clojure/core.clj:4988-5004`, followed by `keep`/`filter`, `distinct` and `into`. Retain three small domain selectors, not a new generic walker namespace.

- `predicate-symbols-in`: stop descent at a vector headed by `:fn`; inspect only its predicate slot, after an optional property map. Else traverse map keys **then** values, and other collection members. It currently stops at `:fn` even when its predicate is unqualified; walking that predicate's body would change results. Callers are `projection-registry :617`, predicate discovery at `:1397,1563,1571,1670,2155,2176`, projection constructors `:2238-2258,2478-2479,2750-2757`.
- `lookup-refs-in`: retain its database-derived lookup-ref predicate; stop descent at a recognized lookup-ref vector. Map traversal is through **values only**. Preserve `distinct`, printed sort order and final vector. Callers are lookup-ref refusal/admission in `cluster.clj:1518,1523,1552`. Do not follow entity refs or query during traversal.
- `referenced-entity-ids`: on maps, emit truthy `:db/id` and traverse `vals` after removing that key; on other collections, traverse members. Preserve first-occurrence order with `distinct` and realize into a vector. Its caller is `referenced-identities` at `render/web.clj:1176`. Map keys are not visited; a map inside the ID value itself must not be descended into.

`tree-seq` is lazy, while each public result here is eager; `into` restores eager realization at the same boundary. Both approaches visit O(nodes) with collection-sized output/state. Core's implementation recursively composes lazy sequences; it is **not an established stack-safety improvement**, and deeply nested/infinite or throwing lazy values need focused proof. These are finite declaration/pulled-data traversals; do not apply this proposal to the bounded iterative admission or print-node validator. **CONFIRMED / MED; 41 old lines, 24 replacement lines proposed.**

### D6. Use core's existing JVM Var conversion

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/instrument.clj:536-540` | `var-symbol` | 5 |

**Replacement:** `clojure.core/symbol`, `reference-code/clojure/src/clj/clojure/core.clj:591-605`; the Var case uses `.toSymbol`. Convert the callers at `instrument.clj:756,786,916,936,1073`. The local function reconstructs from mutable `:ns`/`:name` metadata; core returns the actual Var identity, so forged metadata no longer changes the answer. For ordinarily declared JVM Vars the result is the same qualified symbol. Constant work per call; no laziness or result error conversion.

**CONFIRMED / LOW; 5 lines deleted.** `test.runner/var-symbol :52-57` additionally tolerates nil and missing metadata; direct substitution there would change its domain. It receives zero deletion credit pending an input-contract decision. SCI Vars have their own `sci/var->symbol` (`reference-code/sci/src/sci/core.cljc:704`); core's JVM Var branch must not be applied to them.

### D7. Use hasch's raw SHA-256, not its structural EDN hash

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/fn.clj:113-116` | `sha-256` | 4 |
| `src/seon/test/cache.clj:21-25` | `sha-256` | 5 |

**Replacement:** `(comp hasch.core/hash->str hasch.core/sha256)`, or retain small delegates calling it. In **hasch 0.4.100**, `hasch/core.cljc:17` aliases `platform/sha256`; `hasch/platform.clj:40-43` uses the same JDK `MessageDigest/getInstance "SHA-256"` and `.digest` as the old functions. `hasch/core.cljc:10` aliases `hash->str`; `platform.clj:20-28` emits two lowercase hexadecimal characters per unsigned byte. These are JAR-entry citations; no standalone reference-code source exists.

Convert `fn/text-context :123`, `fn/manifest-data :2501`, and test cache file/gitlink/input digest paths at `test/cache.clj:65,204,219,244`. Keep byte encoding, input order and caller-specific hashing policy untouched. Empty byte arrays and bytes with their high bit set retain the same digest/hex output. Both are eager O(bytes), with a fixed digest plus 64-character result; errors from invalid inputs/JDK digest acquisition still propagate. The library replaces per-byte `format` with integer-to-hex conversion; speed is unmeasured.

**CONFIRMED / LOW; 9 old lines, 4 delegate lines proposed.** Do not replace these with `edn-hash`, `uuid`, or `b64-hash`: those frame EDN values and produce different identities. Do not concatenate streaming chunks merely to use `sha256`; `seon.id/sha-256 :17-27`, blob/filesystem/shell digest loops and `script/seon/dev/dependency_digest.clj` intentionally stream ordered bytes and retain their bounded memory behavior.

### D8. Retire a handwritten Registry implementation, after resolving precedence

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/schema.clj:761-783` | `reference-registry` | 23 |

**Replacement:** `malli.registry/composite-registry`, `reference-code/malli/src/malli/registry.cljc:54-61`, over the default registry, the existing canonical-key-to-ref map, and fallback. Keep the canonical reference stub map transformation; replace the local `reify Registry` and its lookup/enumeration machinery. Callers are `canonical-reference-graph :796` and `direct-reference-keys-in :829`.

There is an actual semantic discrepancy to settle: local `-schema` chooses **defaults → references → fallback**, but local `-schemas` merges **defaults, fallback, references**, so fallback can win over defaults in enumeration. Malli's composite consistently gives the **first** registry precedence for both lookup and enumeration. A three-way overlap cannot preserve both current answers. Confirm disjointness of real fallback keys or explicitly adopt coherent first-registry precedence; do not call it a drop-in replacement beforehand.

The old enumeration is memoized with `delay`; composite `-schemas` merges on each enumeration. Lookup remains at most three registry probes, O(1) expected. Enumeration becomes O(total registry entries) each call instead of once; callers should enumerate/seal once per immutable construction, not per schema use. No exception translation is currently owned by this helper. **CONFIRMED comparison / MED; 23 old lines, 10-line allowance**, conditional on the precedence and enumeration decisions.

### D9. Reuse cache installation mechanics while retaining immutable owners

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/schema.clj:422-441` | `projection-cache-value` | 20 |
| `src/seon/render/ns.clj:109-115` | `cached-schema-row` | 7 |

**Replacement:** `clojure.core.cache.wrapped/basic-cache-factory` and `lookup-or-miss`, **core.cache 1.1.234**, JAR entry `clojure/core/cache/wrapped.clj:130-133` and `:38-70`. `lookup-or-miss` operates on an atom containing a CacheProtocol value, not the existing plain map. Convert `schema/with-compiled-cache :382-410` to seed a basic cache with its current delay values; convert the render-local cache constructor together with its only lookup helper.

Keep the public projection cache function and its contract as a delegate: when the holder exists, look up/install a **delay**, then dereference the selected delay; when absent, invoke the supplied thunk as today. Passing the expensive thunk directly changes the concurrency proof: the wrapper's delay bounds computation per invocation across `swap!` retries, not automatically all competing invocations for a key. Storing delays keeps computation behind the winning installed value and preserves cached nil/false and cached thrown exceptions. Do not substitute `or` for presence checking.

For `cached-schema-row`, retain one immutable database and identical bounds per local cache lifetime, use a basic cache, and keep cached nil/refusal rows exactly as today. It currently may repeat a pull under races; selected-delay installation changes that to sharing the installed computation. Retained delay failures would differ from its current retry-on-thrown-exception behavior; either retain the existing exception retry behavior by eviction or restrict the replacement claim to normal returned rows and prove the failure policy before implementation.

Callers: the projection API is used by `call_preparation :576`, `cluster :1153,1315`, `db :1671,3182,3203,3389,3448,4170,4233,4250,4369,4383`, `error :191,1386,1648,1728,1752`, `instrument :646`, `program :46`, `render :311,362`, `sci/eval :2202,3004`, `test :395`, `test/runner :1042`, and schema-owned derivations. They can keep the public function; all direct accesses to its holder must be audited when changing the holder type. `referenced-schema-closure` calls the row helper at `render/ns.clj:201`.

Expected O(1) basic-cache lookup and insertion; derivation cost remains on first reached key for that immutable owner. Keep the existing basic/unbounded lifetime rather than introducing TTL: the wrapped implementation can return nil after ten expiration retries, which is not Seon's failure policy. **CONFIRMED comparison / MED; 27 old lines, 14-line allowance**, with row-exception behavior still requiring resolution. This does not authorize replacing branch scopes, content keys or Datahike revisions with timestamps.

### D10. Let Datahike compute the earliest assertion per identity

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/reconcile.cljc:106-127` | `first-assertion-transactions` | 22 |

**Replacement:** `datahike.api/q` with `(min ?tx)` on the same historical database, called through **`seon.db/q`**. Exact dependency implementation: `reference-code/datahike/src/datahike/query.cljc:573-579` (`-min`), `:631-658` (aggregate registration), `:2356-2378` (aggregate grouping); public alias declaration at `reference-code/datahike/src/datahike/api/specification.cljc:521`, emitted by `api.cljc:63-89`.

Use a query shaped as `[:find ?entity ?identity-attr ?value (min ?tx) :in $ [?identity-attr ...] :where [?entity ?identity-attr ?value ?tx true]]`, then transform tuples to the existing `{[eid [attr value]] tx}` map. Keep the `:keep-history?` check returning `{}` when disabled, and return `{}` immediately for an empty attribute set. The caller is `plan-transaction-data` at `reconcile.cljc:343`; no ownership/provenance rule changes.

The historical `true` assertion flag is essential: dropping it lets retraction transactions enter the minimum. Group by **entity, attribute and value**, not merely entity or attribute. Reassertions and repeated equal tuples have the same minimum; no rows still means an empty map. Seon's query refusal value must be surfaced before tuple transformation. Both consume O(matching historical assertions); one aggregate query avoids one query per attribute and reduces the transferred result to O(identity groups), but Datahike's generic aggregate itself can materialize/group tuples. This is not an O(1) history lookup or measured speedup. Recompute for the explicit historical input during reconciliation. **CONFIRMED / MED; 22 old lines, 10 replacement lines proposed.**

### D11. Stop copying Malli's primitive-function exclusion predicate

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/instrument.clj:65-80` | `primitive-fn?` | 16 |

**Replacement:** the existing private Var `malli.instrument/-primitive-fn?`, `reference-code/malli/src/malli/instrument.clj:15-16`. Its body and Seon's are equivalent: require `fn?`, inspect `supers`, and find an interface name starting with `clojure.lang.IFn$`. Both return boolean, accept nil/nonfunctions as false, and inspect the same class hierarchy. Cost is O(supertypes) for each class inspection.

Convert `armable :103`, `collect-contracts! :823`, and `apply! :914`; retain a contracted thin delegate if program analysis needs that Seon function identity. The function is **private**, so direct ordinary namespace-qualified invocation is not a supported public API. A pinned Var call can share it now; making this tiny predicate a public upstream/fork seam is the cleaner longer-term option. Do not replace the entire instrumentation owner with `mi/instrument!`, which mutates roots and does not implement Seon's recording, defaults, timing or branch policies. **CONFIRMED / MED; 16 old lines, 5-line delegate allowance.**

### P13. Use Medley for a key-aware value transformation

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/schema/datahike.clj:417-423` | `encode-entity-in` | 7 |

**Replacement:** `(medley.core/map-kv-vals (partial encode-value-in projection) entity)`, **medley 1.4.0**, JAR entry `medley/core.cljc:129-134`, with its `reduce-map` implementation at `:83-87`. Core `update-vals` cannot directly replace this because conversion depends on **both attribute and value**. Callers: recursive entity conversion in `encode-value-in :404,407,410,413` and the transaction entry at `:471`.

For persistent ordinary maps, including sorted maps, both transform each value once, preserve keys and eagerly rebuild in O(entries). Medley converts records to plain maps; the old `(empty entity)` does not provide that same behavior for records. The general admitted input universe must be checked before treating the replacement as equivalent; exceptions from `encode-value-in` otherwise propagate. Keep schema-specific encoding and transaction-function rules. **PLAUSIBLE / MED; 7 old lines, 3 replacement lines proposed.** This small example names a useful underused dependency, not a reason to replace concise existing `into`/transducer compositions everywhere.

### P12. Remove the local table of schema child grammar

| Pinned source range | Definition | Physical lines |
|---|---|---:|
| `src/seon/schema/admission.clj:198-201` | `form-properties` | 4 |
| `src/seon/schema/admission.clj:203-206` | `form-body` | 4 |
| `src/seon/schema/admission.clj:208-217` | `schema-children` | 10 |
| `src/seon/schema/admission.clj:219-221` | `schema-nodes` | 3 |

**Replacement:** `malli.util/subschemas`, `reference-code/malli/src/malli/util.cljc:168-181`, and `malli.core/properties` / `type` (`reference-code/malli/src/malli/core.cljc:2592` / `:2521`). `subschemas` returns a vector of `{:schema ... :path ... :in ...}` maps; its `find-first` traversal is at `util.cljc:37-51`. Use the supplied compilation scope and explicitly choose reference-following options. Convert `house-findings :243,261-273` and `direct-database-ref? :224-226` to inspect compiled nodes/properties.

The old code manually excludes literal children of `:enum`, `:=`, `:fn`, `:ref`, `:re`, peels map entries, and special-cases `:multi`. That table is vulnerable to grammar evolution: its `:multi` branch drops the first body entry. Malli owns schema children and does not treat enum data as subschemas.

This is **not immediately interchangeable at an admission boundary**. The old walker can inspect malformed/unresolved authored forms before compilation; `subschemas` compiles and can throw before all house findings are collected. Its defaults follow schema references and top-level refs, potentially examining declarations outside the submitted form. Local registries and reference cycles require explicit scope policy. Retain malformed-form diagnostics and their file/declaration identity, and avoid executing predicate/generator code while acquiring structure. If that requires another tolerant parser, the proposed deletion is not earned. A compiled finite schema walk is O(reached schema nodes), with eager descriptor-vector allocation and extra path derivation; run once per submitted declaration. **PLAUSIBLE / HIGH; 21 old lines, 10-line allowance**, conditional on reusing existing structural compilation safely.

## 2. Larger-looking substitutions that do not earn deletion credit

| Surface inspected | Exact library seam compared | Reason to retain, exclude, or require a separate decision |
|---|---|---|
| `db/value-changes :2997-3022`, `apply-diff :3024-3035` | `clojure.data/diff`, `reference-code/clojure/src/clj/clojure/data.clj:124-148`; `editscript.core/diff :22-65`, `patch :67-78` in `reference-code/editscript/src/editscript/core.cljc` | Already uses editscript. Core diff returns `[only-a only-b both]`, not snapshot-coordinate patch operations; sequences become index-aligned vectors, sets are not recursively diffed, strings are atoms. Missing vs nil and sequence insertion/deletion semantics require the current adapter. No complete replacement. |
| `reconcile/entity-exact-tx :254-295` and normalization family | The same core diff; Datahike transaction primitives | Normalizes peer refs/components and cardinality, preserves managed identity and performs writer-side recomputation. A recursive map diff is not a provenance-aware transaction. D10 prices only the aggregation reduction. |
| `schema/framed :847-848`, `canonical-coll-string :850-851`, `canonical-value-string :853-892`, `canonical-data-string :895-903` — 53 lines | hasch 0.4.100 `hasch.core/edn-hash`, `hasch/core.cljc:20-29`; Puget `canonical-printer`, `reference-code/puget/src/puget/printer.clj:494` | The local result is an exact framed comparison string, not just a hash. It distinguishes vector/sequential tags, normalizes Date/Instant to milliseconds and refuses unsupported values with named data. Hasch changes both identity bytes and equality/type conventions; a fixed-width hash also changes an exact comparison guarantee. Puget changes serialization and unknown-object behavior and is absent from the source-only classpath. No blanket 53-line retirement. |
| `print/compare-values :413-442` — 30 lines; `render.web/path-compare :422-433` — 12 | arrangement 2.1.0 `arrangement/core.cljc:57-133`, `rank :72` | `rank` compares numbers across types, maps/sets by size first and unknown objects via comparison or string conversion. Seon's whole-value comparator uses class-category ordering, lexicographic collection contents and returns zero on ClassCastException; it promises no content rendering. `path-compare` uses core compare on each path member, whose accepted types need proof before rank's broader rules can replace it. Whole-value comparator is also in the prior audit's inventory. Zero new credit. |
| `edit/replace-occurrences :384-394` — 11 lines | `clojure.string/replace`, `reference-code/clojure/src/clj/clojure/string.clj:75-111` | The string/string overload replaces nonoverlapping literal matches, preserving literal dollar signs and backslashes. It fits the actual `exact` caller after its unique/all-match gate, but this definition is already in the prior audit inventory. Excluded entirely from this retirement ledger. |
| Reader offsets in `fn :119-129,159-196`, `sci.reader :54-74`, `edit :81-114,190-196` | `edamame.core/parse-next+string`, `reference-code/edamame/src/edamame/core.cljc:144-154`; `rewrite-clj.zip/position-span`, `reference-code/rewrite-clj/src/rewrite_clj/zip.cljc:192-198` | Edamame's source string is whitespace-trimmed. Rewrite-clj supplies one-based row/column, already used by edit. Neither supplies Seon's required UTF-8 byte offsets or original-byte splice fence. Replacing the byte/character conversion with the existing row/column APIs loses a unit conversion, not a parser. Tools.reader's indexing/source logging must not be mistaken for byte indexing. Prior audit R7 owns the bridge reader. |
| `fn.analyzer/attributed-usages :535-574`, `parsed-facts :587-628`, `with-namespace-clauses :630-669` | clj-kondo exported analysis; `reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:40-41,188,198` | Already uses from-var, dispatch and protocol implementation evidence. The second node scan also captures literal-def initializers, var-quote containment, require-call spans and Seon task-map positions. No inspected exported API returns that complete result. Removing the scan based only on the existence of `:var-usages` loses evidence. Keep the producer-level opportunity open; do not book its full size. |
| `schema.internal/entity-entries :80-100`, `same-scoped-schema? :36-60` | `malli.util/merge`, `reference-code/malli/src/malli/util.cljc:53-101`; `equals :8-12` | Merge has value-combination policies; it does not supply the current scoped-reference equivalence and refusal of optionalizing inherited required members. Comparing forms without their registries is not equivalence. Prior audit C4/C3 owns shape/default mirrors. |
| SCI context acquisition, host copying and runtime bindings | `sci/fork :345-351`, `copy-var* :112`, `eval-form :421-428`, `namespace-state :751`, `install-var-roots! :903` in `reference-code/sci/src/sci/core.cljc` | Seon already calls these. `copy-host-var :233-246` repairs thread-bound dynamic-Var capture because library copying dereferences the Var. Replacing it with copy-var* alone pins an evaluation's world into the memoized base. Custody, installation, actual exit, arming and settlement are additional guarantees, not a duplicated interpreter to delete. |
| Branch merge base, `cluster.source/merge-base :748-770` — 23 lines | `datahike.versioning/branch-history`, `reference-code/datahike/src/datahike/versioning.cljc:191-211` | Branch history follows one connection, materializes history into a result and returns a channel. It does not find a nearest common ancestor of two supplied values under Seon's visit bound or return the same unavailable-history refusal. No inspected public merge-base seam exists. `history`, `since`, `as-of` already delegate through seon.db. |
| Projection/base-context/read-evidence caches | Datahike query/read dependency plans and native cache-context; `reference-code/datahike/src/datahike/query.cljc:133-171`; core.cache/core.memoize | Already uses native revisions and wrapped LRU caches. D9 replaces mechanics only. Plain `memoize` is unbounded and can compute a miss concurrently; core.memoize does not infer which database attributes a computation reads. Neither replaces owner-derived keys, refusal eviction, branch generation or custody. Prior audit C7 owns replay/currency deletion. |
| Flow step adapters, launcher and error fanout | `flow/map->step :291-309`, `lift*->step :311-321`, `lift1->step :323-332`, `futurize :334-352` in `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` | Lifts fit stateless single-in/out transforms. Inspected Seon procs carry lifecycle state, multiple ports, fault delivery or bounded admission/actual-exit ownership. A pipe/future cannot replace those guarantees. Prior audit §8.4 already excludes the launcher. |
| Blob chunking and GC | `konserve.core/bget :634-656`, `bget-range :658-671`, `multi-get :376`, `multi-dissoc :528` in `reference-code/konserve/src/konserve/core.cljc` | Range reads already have a library seam. Seon's digest verification, staging, database reachability and retention protection are not supplied by a raw KV read/delete. Do not replace bounded chunks with full materialization or collector policy with `keys` + deletion. Prior audit §4 owns blob/path distinctions. |
| Filesystem walkers and subprocess ownership | `babashka.fs/walk-file-tree`, `reference-code/babashka/fs/src/babashka/fs.cljc:227-273`; `babashka.process` source | Generic path walking does not retain SecureDirectoryStream-relative access, observed-file identity, no-follow preconditions or exact process `(pid,start-instant)` reaping. `walk-file-tree` also defaults its failed visit to continue: a naive substitution makes failures silent. Keep those policies; no whole filesystem/process function retirement is established. |
| Hiccup escaping and source/display printing | `clojure.string/escape` in `reference-code/clojure/src/clj/clojure/string.clj`; fipp `engine.cljc:247-260`; Puget printer | The local append-escaped! documents avoided intermediate allocation at a hot output boundary. A `str/escape` plus append is plausible only after equivalent escaping/allocation proof, and Hiccup library adoption needs the actual source-classpath decision. Floor fitting and emission are prior audit R1/R2; zero additional credit here. |
| Small map/filter/group helpers | core `update-vals :8136-8150`, `update-keys :8152-8168`, `clojure.set/index :95-104`, `join :115-144`; medley `index-by :246-254`, `remove-vals :166-170` | Existing concise `into {}`/transducers often already are the smallest composition. `index-by` silently keeps the last duplicate, so it cannot replace program/one-by-key's duplicate refusal. `update-keys` requires unique mapped keys; blanket conversion of normalization reducers can change collisions and metadata. P13 is the measured key-aware example. |

## 3. Libraries we have but barely use

The `src/` and `script/` namespace/import scan found **no direct references to `malli.destructure`, hasch, medley, arrangement, core.memoize, fipp, Puget or Orchard** at the source pin. Absence of a direct import is not absence from dependency execution: Malli uses fipp; Datahike uses hasch/medley; core.async's dependency closure brings cache libraries. Core.cache itself is already widely used in Seon.

The highest-value new adoption is **Malli's destructuring grammar** (D1), followed by **hasch's raw byte digest** (D7) and **core.cache's wrapped basic-cache mechanics** (D9). **Medley** offers small key-aware transformations (P13); it should replace actual machinery, not trigger a style-only rewrite of correct core transducers. **Fipp** can remove substantially more code, but all that saving is already assigned to the duplicate-paths/render audit.

Puget, Orchard, nREPL, clj-reload, notebook libraries and UI inspectors are not automatically available production replacements merely because they are vendored. They should not finance removal of Seon's declared render/REPL contract. Orchard's JVM metadata lookup cannot answer an alternate branch's interpreted program; nREPL's response combiner does not consume Seon's PREPL protocol; clj-reload's unload/reload lifecycle does not preserve branch adoption semantics. Most of these are useful references or optional tooling, with **zero bookable source deletion** from this sweep.

## 4. Every reference-code gitlink considered

Pins are from the audit source commit's tree. “Screened” means repository/API inventory and relevance screening, not end-to-end reading of the entire repository. The deeper implementation comparisons are cited above. The dependency list, not this directory roster, determines classpath inclusion.

| Gitlink under `reference-code/` | Pinned revision | Disposition |
|---|---|---|
| babashka | `12872c10c22206170ec6eeb0130e2d9c8d2ce672` | Nested fs source is a direct dependency; inspected path/walk semantics. Babashka runtime is not a replacement SCI owner. |
| babashka-process | `43bdd65e189f2ccacc56662c53a21f5470ff1500` | Already used for subprocess primitives; lifecycle/identity policy remains. |
| clay | `5ec4efe068710d7b44206da556bf837ff5fc232b` | Screened visualization API; not a source dependency or declared-render replacement. |
| clerk | `4c2599946256592858ca2d65e8d453f9f77489ba` | Screened notebook/analyzer API; not Seon's alternate-branch program authority. |
| clj-kondo | `57252e07975710aa579b24f0d1b2b1e04195caa2` | Direct dependency; exported analysis compared with Seon enrichment. |
| clj-reload | `61c6fa77855efc0b4d052f06c7cc8b2b8817c4aa` | Screened unload/topological reload owner; outside current source dependency closure. |
| clojure | `3bc2b3e91fdff462620d84bbdfd40379d9c562e5` | 1.12.5 source; core, string, walk, data, set, stacktrace and main seams inspected. |
| clojurescript | `26dea31110502ae718399868ad7385ffb8efe6f6` | Screened compiler/vendor-reader inventory; excluded from current JVM application dependency. No deleted pod reconstruction. |
| core.async | `dc35f3e0d7bc2eef502e77982f48641f025c8051` | Matches declared alpha3; Flow primitives compared, no wholesale launcher cut. |
| core.async.flow-monitor | `fbff8424696c7080ee7dc27b55cde1659ec18d8f` | Test dependency; monitoring is not lifecycle/recording ownership. |
| datahike | `4157916b5a1b8d80d7f809c889bce1b098b1d75d` | Direct dependency; aggregate, query/cache and versioning comparisons. |
| datastar-clojure | `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | SDK and http-kit adapter already selected; not a replacement data selection tree. |
| edamame | `63373df6ab056d86d106a79caecc2ebfca18d357` | Direct parser; source logging/location API inspected; no byte-offset substitute. |
| editscript | `b493ccf2e987fed84a05badb2626eebae4b91328` | Direct dependency already supplies diff/patch. |
| fipp | `255a19061fdc20d67eca09a17e9af282aa8bf909` | Available through Malli; important adoption, already priced by prior R1. |
| http-kit | `f56bbeaf9774e45bddc6a73f49171fdfd8c07b30` | Direct maintained transport; no fresh transport replacement claim. |
| hyperlith | `b08a8e8689e1654fd7e0ce654064a703ca1f4772` | Screened application/action/view API; not on the declared source classpath. |
| kaocha | `8846f91c9bf4338c561ffb866b5a8890e22889cd` | Screened test-plan/run API; adding another runner violates the current one-harness composition. |
| kindly | `f7fe1b6ebe25ae182aeee822c6c203b15168e262` | Screened metadata/kind API; doesn't implement Seon's schema render/default policy. |
| konserve | `5b39fddd6ae58bae7d08880daecd1ad7242ea273` | Declared fork SHA matches; read/range/batch/locking seams inspected. |
| langchain4clj | `889f9e60e3d2bb13948f9a9921aa294712fcace7` | Screened provider adapter inventory; not in current source closure, no verified equivalent usage/stream/error replacement. |
| malli | `befcd302a8e626a67d84b80aabee0cd1b38dadb6` | Direct dependency; grammar, registry, instrumentation and schema traversal candidates. |
| morse | `ff79378a5e21aed21587bd137f0e8994a7644b56` | Screened JavaFX/remote inspector API; not a drop-in declared browser face. |
| nrepl | `f3a044153eaa6050e7447655dab5bc42cfdd68c2` | Inspected response/session API; cached test-classpath JAR is 1.0.0, not proof of this source revision loading. |
| orchard | `da8b9de0e1323a4f4119052b662ae0acc4930c02` | Inspected info/metadata resolution; not found in inspected cached classpaths. |
| portal | `7f124943ebfa865ac4976aa60ff39342ef97fe64` | Screened submit/session/view API; no declared-render replacement credit. |
| puget | `f959e034ddf9c4910e290efe6b0eb49048ff1bec` | Inspected order/canonical/printer API; source pin differs from cached test dependency version. |
| reveal | `66c2d5e37996f4f135059efbb1f46f9c6886783c` | Screened PREPL/view APIs; needs separate UI/transport contract, no free deletion. |
| rewrite-clj | `60782e501aaf312cb90c9ff0bee05d5da5125563` | Direct dependency; edit already uses zipper positions/splices; preserve byte-fidelity fence. |
| sci | `409888f61fa1f3bbade8d7e3eecb9b8e09c8004a` | Direct interpreter; source APIs compared, no parallel evaluator proposed. |
| superv.async | `501b42945693804fd3e57276cc2a07ed4fe1d602` | Declared Throwable-carrying fork; preserve distinction between failure and closed-channel success. |

Other declared dependencies were screened in `deps.edn`: data.json, cron-utils, jsoup, Lucene, test.check/test.chuck, transit, timbre/SLF4J, reitit, and Proximum. The application already delegates JSON, schedule parsing, HTML extraction and the indexed query primitives. No complete equivalent replacement for remaining policy functions was established. Their sources are not all standalone gitlinks here; this is not an assertion that every transitive dependency implementation was inspected.

## 5. Reproduction, artifact evidence and delivery boundary

The read-only measurement recipe is embedded in the sole allowed file. It reads Git objects and this document, checks inclusive physical line counts, rejects ledger overlaps, and checks that no named retirement is present in the earlier audit's named-definition inventory/ledger. It does not load Clojure or run tests. The scanner counts forms from opening definition to closing delimiter, including contracts/docstrings/comments, excluding separating blanks. Dependency artifacts and semantic differences still require the cited source reads; line counts are not equivalence proof.

```python
import pathlib, re, subprocess
pin = "d4b3c0e13af811432f20039adc60739f131182a5"
path = pathlib.Path("docs/research/agent-platform/library-replacement-audit-2026-09-25.md")
doc = path.read_text()
ledger = doc.split("## 1. Ranked replacements", 1)[1].split("## 2.", 1)[0]
prior = subprocess.check_output([
    "git", "show", pin + ":docs/research/agent-platform/duplicate-paths-audit-2026-09-25.md"
], text=True)
seen, cache, count, definitions = set(), {}, 0, 0
for file, start, end, name, expected in re.findall(
    r"\| `((?:src|script)/[^`]+):(\d+)-(\d+)` \| `([^`]+)` \| (\d+) \|", ledger
):
    if file not in cache:
        cache[file] = subprocess.check_output(["git", "show", pin + ":" + file], text=True).splitlines()
    prior_rows = [row for row in prior.splitlines() if "`" + file + "`" in row]
    assert not any("`" + name + "@" in row for row in prior_rows), (file, name)
    a, b, n = int(start), int(end), int(expected)
    assert b - a + 1 == n == len(cache[file][a-1:b]), (file, name)
    assert re.match(r"\(defn-?\s+" + re.escape(name) + r"(?:\s|$)", cache[file][a-1])
    for line in range(a, b+1):
        assert (file, line) not in seen
        seen.add((file, line))
    count += n
    definitions += 1
assert count == 261, count
assert definitions == 20, definitions
print({"pin": pin, "old_lines": count, "definitions": definitions, "overlap": 0})
```

Pinned source/caller reproduction uses `git grep -n` on the pin, or `git show PIN:path` with the cited inclusive range. The initial discovery used `rg --files src script`, `rg` for definitions/loops/reductions/cache/hash/reader operations, and a Git-object sweep of all 116 files. The latter took **2.79 seconds** in the shell; it was an external static scan, not a Seon contracted operation or runtime performance proof. No measurement script outside this document was written.

JAR entries above were read directly with Python `zipfile`, under `~/.m2/repository/`. Exact SHA-256 of the inspected artifacts:

| Artifact | SHA-256 |
|---|---|
| `org/replikativ/hasch/0.4.100/hasch-0.4.100.jar` | `0e5769aefac3eaaf939412e4961a2f57b65f63f75cad86c0e533ffab902ad8c3` |
| `org/clojure/core.cache/1.1.234/core.cache-1.1.234.jar` | `ba9071044edb7b009288b2c5f800a7dcd918b0f1725c5ba800469238df972f63` |
| `medley/medley/1.4.0/medley-1.4.0.jar` | `4cf21169bbdcb46b99e77d409beeb7bfd1aa366088528757424cb997d496f355` |
| `mvxcvi/arrangement/2.1.0/arrangement-2.1.0.jar` | `f37251385d220df8951e68d5255abb5197adbcbd8fc6b3d3fca872a2339f39c8` |

**Delivery boundary:** one documentation file. Source/test changes **0 / 0**, executed tests **0**, runtime mutations **0**. The checkout initially contained unrelated edits in `src/seon/fn.clj`, `src/seon/test/runner.clj` and untracked content in the http-kit submodule; these were preserved. No foreign load failure was encountered because loading was outside this assignment. No worktree, scratch cluster, reset, stop, hook bypass, source edit, issue-index edit, agent launch or agent message was performed. Commit staging is restricted to this document and commitment uses `git commit --only --` this path.

Boot/reload/publication/adoption/arming, probe/test timings, parent-vs-change performance, allocation and memory are **unmeasured**. Those proofs belong to any subsequent implementation lane. The largest remaining research limits are exhaustive dependency-source reading, the missing reference-code checkouts, and the explicitly named input/precedence/failure-policy decisions above; none is reported as a pass.
