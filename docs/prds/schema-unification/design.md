# Schema Unification Design

**Status:** Draft (Updated 2026-03-05)
**Goal:** Malli is the SINGLE source of truth. No hardcoded Datalevin schemas. The bridge derives everything.

## Problem

Seon maintains two parallel schema systems that diverge, causing LMDB crashes:
1. **Malli schemas** (`seon.schema/register!`) — define types for validation
2. **Datalevin schemas** (hardcoded maps in runtime.clj, ingest.clj, ctx.clj, repl.clj, trace.clj) — define storage types

15 LMDB assertion failures in 3 days. Root cause: bad data bypasses Malli and crashes Datalevin's C layer.

## Complete Type Mapping: Malli ↔ Datalevin

### Verified Roundtrip Types (from test suite)

| Malli Type | Datalevin Type | Java Type | Roundtrips? | Notes |
|---|---|---|---|---|
| `:string` / `string?` | `:db.type/string` | `String` | ✅ | Empty strings OK |
| `:int` / `int?` | `:db.type/long` | `Long` | ✅ | Datalevin coerces via `(long v)` |
| `:double` / `double?` | `:db.type/double` | `Double` | ✅ | NaN rejected (AssertionError); Inf OK |
| `:float` / `float?` | `:db.type/float` | `Float` | ⚠️ | Malli generates Double; Datalevin coerces, precision loss |
| `:boolean` / `boolean?` | `:db.type/boolean` | `Boolean` | ✅ | |
| `:keyword` / `keyword?` | `:db.type/keyword` | `Keyword` | ✅ | Namespaced keywords preserved |
| `:symbol` / `symbol?` | `:db.type/symbol` | `Symbol` | ✅ | |
| `:uuid` / `uuid?` | `:db.type/uuid` | `UUID` | ✅ | |
| `:inst` / `inst?` | `:db.type/instant` | `Date` | ✅ | |

### Types Needing Custom Malli Schemas

| Datalevin Type | Java Type | Proposed Malli Schema | Generator |
|---|---|---|---|
| `:db.type/bigint` | `BigInteger` | `[:fn {:db/valueType :db.type/bigint} #(instance? BigInteger %)]` | Custom |
| `:db.type/bigdec` | `BigDecimal` | `[:fn {:db/valueType :db.type/bigdec} #(instance? BigDecimal %)]` | Custom |
| `:db.type/bytes` | `byte[]` | `[:fn {:db/valueType :db.type/bytes} bytes?]` | Custom |
| `:db.type/ref` | `Long` or `[kw val]` | See Refs section below | N/A |

### Composite Type Mappings

| Malli Pattern | Datalevin Schema | Notes |
|---|---|---|
| `[:enum :a :b :c]` | `{:db/valueType :db.type/keyword}` | All same type → infer. Mixed types → reject at registration. |
| `[:enum "x" "y"]` | `{:db/valueType :db.type/string}` | String enum |
| `[:enum 1 2 3]` | `{:db/valueType :db.type/long}` | Long enum |
| `[:maybe X]` | Same as X | Nil stripped before transact; absent key on pull = nil |
| `[:vector X]` / `[:set X]` | `{:db/valueType <type-of-X> :db/cardinality :db.cardinality/many}` | Pull returns vector, not set. Coerce on read. |
| Nested `[:map ...]` | `{:db/valueType :db.type/ref :db/isComponent true}` + flattened child attrs | Component entities. Pull `[*]` returns nested map. |

### Types We Reject

| Malli Type | Why |
|---|---|
| `:any` | Too broad. Datalevin needs specific types. **Banned.** |
| `:some` | Same — non-nil but untyped. **Banned.** |
| `[:enum :a "b"]` | Mixed-type enum has no single Datalevin type. **Reject at registration.** |
| `:nil` | Datalevin cannot store nil. Use `[:maybe X]` + strip nils. |
| `:fn` / `:=>` | Functions are not data. |

## Key Behavioral Findings (from REPL testing)

### Nil Values
- Datalevin throws `"Cannot store nil as a value"`
- `[:maybe X]` → same Datalevin type as X, but nil values MUST be removed from map before transact
- On pull, absent attribute = key not in result map (not nil)

### Cardinality-Many
- Datalevin stores as individual datoms (one per value)
- `d/pull` returns a **vector** (not a set)
- Both vector and set inputs work at transact time
- For Malli roundtrip: coerce vector back to set on read, or use `[:vector X]` in output schema

### Refs
- **Component refs** (`{:db/isComponent true}`): `d/pull [*]` returns full nested entity
- **Non-component refs**: `d/pull [*]` returns `{:db/id N}` (just entity ID in map)
- **Lookup refs**: `[:identity-attr value]` resolved to entity ID at transact time
- **Target must exist**: lookup ref to nonexistent entity fails

### Keywords in Datalevin
- Stored as-is (namespace + name bytes via `key-sym-bytes`)
- `clojure.core/namespace` and `clojure.core/name` called on the value
- **If value is not Named (String, Vector, etc.), LMDB crashes** — this is our #1 bug source

### NaN
- `Double/NaN` causes `AssertionError` in `datalevin.bits/encode-double`
- Must reject before transact

### Float
- Malli `:float` generator produces `Double`, not `Float`
- Datalevin coerces via `(float v)` — works but loses precision
- Roundtrip: `Double` in → `Float` out

## Refs Design

Current: registered as `:any` (e.g., `:seon.fn/input-spec`). This is the root cause of Bug 3.

**Proposal:** Model refs explicitly. Two options:

### Option A: `:db/valueType` property (minimal)
```clojure
(schema/register! :seon.fn/input-spec
                  [:or :int [:tuple :keyword :any]
                   {:db/valueType :db.type/ref
                    :description "Entity ref (eid or lookup ref)"}])
```
Bridge sees `:db/valueType` property → uses it directly. The Malli schema validates the value is either a Long (eid) or a lookup ref vector.

### Option B: Custom Malli type (cleaner)
Register a custom `:db/ref` type in Malli:
```clojure
;; In seon.schema setup
(def ref-schema [:or [:int {:min 1}] [:tuple :keyword :any]])

;; Usage
(schema/register! :seon.fn/input-spec ref-schema)
```
Bridge recognizes `[:or [:int ...] [:tuple ...]]` pattern → derives `:db.type/ref`.

**Recommendation:** Option A — explicit property is clearer and doesn't require pattern matching.

## Bugs Fixed (2026-03-05)

### Bug 1: trace.clj passes conn where keyword expected — FIXED
`persist-event!` now uses `(db/transact! :seon.flow [entity])`.

### Bug 2: ensure-schema! silently skips unmappable types — FIXED
Now throws ex-info with clear message.

### Bug 3: trace/ctx datalevin-schema not in merged schema — FIXED
Both added to `runtime-merged-schema`.

## Data Pipeline & Serialization

Data in Seon flows through multiple boundaries. A registered Seon spec must be valid at ALL of them:

```
Application code
  → Malli validation
  → Serialization (for flow channels / inter-JVM transfer)
  → Datalevin transact (writer step-fn)
  → LMDB storage
  → Datalevin pull (reader step-fn)
  → Deserialization
  → Malli validation (must still pass)
  → Application code (must get back equivalent data)
```

### Serialization Format: Research Needed

The inter-JVM channel (`seon.flow.harness.channel`) currently uses EDN (`pr-str`/`read-string`). Intra-JVM flow uses native Clojure data through core.async channels (no serialization).

**Candidate formats to evaluate:**

| Format | Type | Key Properties |
|--------|------|---------------|
| EDN | Text | Clojure-native, human-readable, gaps: no `byte[]`, float→double coercion |
| Nippy | Binary | 1:1 JVM state transfer, all Clojure types, metadata preserved, compression, **already used by Datalevin**, thaw transducer for data inspection/transformation |
| Fressian | Binary | Datomic's format, extensible, comparable fidelity to Nippy |
| Transit+JSON | Text+tags | Polyglot-oriented (designed for cross-platform, not JVM-to-JVM) |
| Transit+MessagePack | Binary+tags | Same as above but binary transport |

**Nippy is especially interesting** because:
- Already a Datalevin dependency (used at scale by XTDB, Datalevin, Carmine, etc.)
- Its thaw transducer maps naturally to flow step-fns for data inspection/transformation
- Preserves exact Java types including Float vs Double, byte[], metadata
- 12+ years mature, comprehensive type coverage
- `extend-freeze`/`extend-thaw` for custom types

**Research questions (don't assume answers — verify in REPL and source):**
- What exactly does Datalevin use Nippy for? Read `reference-code/datalevin/` for nippy references.
- What does Datalevin's own client-server protocol use? (port 8898 communication)
- How does Nippy handle nil? (core.async rejects nil as channel VALUE, but maps containing nil like `{:foo/bar nil}` flow through channels fine)
- What's the actual performance difference for our payload sizes?
- Could we use Nippy's transducer as a flow step-fn for validation/coercion?

### Reference code (READ THESE):
- `reference-code/nippy/src/taoensso/nippy.clj` — type spec at line ~94 shows ALL supported types including nil, float, bigint, bigdec, byte arrays, metadata, records
- `reference-code/nippy/src/taoensso/nippy/tools.clj` — integration patterns for 3rd-party libraries
- `reference-code/fressian/` — Datomic's serialization format (Java, not Clojure)

### Nil Semantics

This is a design question, not a solved problem.

**Facts:**
- core.async rejects `nil` as a channel value, but `{:foo nil}` on a channel is fine
- Datalevin throws "Cannot store nil as a value"
- In EAV model, retracting a datom removes it — no "null datom" concept
- Nippy type-id 3 is `:nil` — it serializes nil natively

**The question:** How should Seon model "this field has no value"?
- Absence (key not in map) — simplest, Datalevin-natural
- `[:maybe X]` with nil-stripping before transact — Malli-idiomatic
- Retraction as "set to nil" — preserves intent in transaction log
- Something else?

Research should explore what Datomic users do, what malli-datomic does, trade-offs.

## Recursive/Nested Structures

**Open question:** Can Malli specs describing nested data structures (maps within maps, vectors of maps) be recursively decomposed into separate Datalevin component entities?

Current bridge already handles one level: nested `[:map ...]` → `{:db/valueType :db.type/ref :db/isComponent true}` + flattened child attrs. But research is needed on:

- How deep can this go? Arbitrary nesting?
- How does `d/pull [*]` reconstruct nested structures? Does it handle N levels?
- Circular/recursive Malli schemas — possible? Useful?
- Performance: many small entities vs serialized nested data (e.g., Nippy bytes in a single attr)
- malli-datomic maps `:set`/`:map`/`:vector` all to `:db.type/ref` — is that always right?

## Custom Registration Function

**Goal:** A `seon.schema/register!` that validates specs are compatible with the full pipeline at registration time, not at transact time when it's too late.

**Research areas:**
- Walk the Malli schema tree, check every leaf is mappable to a Datalevin type
- Reject `:any`, `:some`, mixed-type enums at registration
- Verify EDN/Nippy/chosen-format roundtrip compatibility
- Verify atom compatibility (specs must work as atom validators)
- How do malli-datomic and spectomic handle validation? (see prior art below)

## Prior Art (READ THESE)

### malli-datomic (`reference-code/malli-datomic/`)
Malli → Datomic schema generation. Key source files:
- `src/blasterai/malli_datomic/datomic_schema_gen.cljc` — main conversion logic
- `src/blasterai/malli_datomic/spec_utils.cljc` — type detection helpers

Notable design choices:
- Maps `:set`/`:map`/`:vector`/`:sequential` all to `:db.type/ref`
- Keyword enums become `{:db/ident kw}` entities (Datomic pattern)
- Non-keyword enums emit a warning, not an error
- Copies `:db/*` properties from Malli entry options (like our bridge)
- Has `derive-value-type` that throws on unsupported types (no silent skip)
- Handles `:and` composites by extracting the atomic predicate
- Supports `:db/tupleType` and `:db/tupleAttrs` (Datomic tuples — Datalevin may not have these)

### spectomic (`reference-code/spectomic/`)
Spec → Datomic schema. Different approach — generates samples and infers types:
- `src/provisdom/spectomic/core.clj` — main logic
- Uses `class->datomic-type` map (Java class → Datomic type) — simpler than walking schema tree
- **Clever: generates 100 samples from the spec, checks all samples resolve to the same Datomic type.** If types are inconsistent, throws.
- Handles `s/nilable` by filtering nil samples
- Handles `s/coll-of` → cardinality-many
- Has `custom-type-resolver` extension point
- Maps are always `:db.type/ref`

**Key insight from spectomic:** Generative testing of the schema itself (not just data) — generate samples, verify they all map to one Datalevin type. This is a powerful validation strategy we could adopt.

## Implementation Plan

### Phase 1: Value-type gate in transact! (NEXT)
Add `validate-values!` after `validate-attrs!` in `db/transact!`:
- Look up Datalevin schema for each attr
- Check value's Java type matches expected type
- Throw clear error on mismatch
- Fast — just `instanceof` checks

### Phase 2: Eliminate hardcoded schemas
For each module (runtime, ingest, ctx, trace, repl):
1. Ensure all attrs have Malli `schema/register!` with correct types + `:db/valueType` where needed
2. Add `:db/unique`, `:db/cardinality`, `:db/isComponent` as Malli properties
3. Replace hardcoded `datalevin-schema` with `(db-schema/malli-map->datalevin-schema ...)` call
4. Run roundtrip tests

### Phase 3: Startup consistency check
- At boot, verify all registered Malli schemas derive valid Datalevin types
- Error on `:any`, `:some`, mixed enums
- Warn on types that need `:db/valueType` property

## Roundtrip Test Suite

**Location:** `test/seon/db/schema_roundtrip_test.clj`
**Status:** 36 tests, 411 assertions, all passing

Tests define the contract. Any bridge changes must keep all tests green.

Categories: leaf types (9), enums (3), schema derivation (4), maybe/nil (2), cardinality-many (3), component refs (2), edge cases (7), full entity (1), gap detection (4).

## Hardcoded Schemas to Eliminate

| File | Schema var | Attr count |
|------|-----------|-----------|
| `src/seon/runtime.clj` | `runtime-schema` | ~20 |
| `src/seon/graph/ingest.clj` | `datalevin-schema` | ~30 |
| `src/seon/ctx.clj` | `datalevin-schema` | 4 |
| `src/seon/flow/trace.clj` | `datalevin-schema` | ~10 |
| `src/seon/repl.clj` | `datalevin-schema` | 8 |
