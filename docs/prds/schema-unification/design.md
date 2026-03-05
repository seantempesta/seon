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
