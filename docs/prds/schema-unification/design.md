# Schema Unification Design

**Status:** Active (Updated 2026-03-05)
**Goal:** Malli is the SINGLE source of truth. No hardcoded Datalevin schemas. The bridge derives everything.

## Problem

Seon maintains two parallel schema systems that diverge, causing LMDB crashes:
1. **Malli schemas** (`seon.schema/register!`) -- define types for validation
2. **Datalevin schemas** (hardcoded maps in runtime.clj, ingest.clj, ctx.clj, repl.clj, trace.clj) -- define storage types

15 LMDB assertion failures in 3 days. Root cause: bad data bypasses Malli and crashes Datalevin's C layer (e.g., String where Keyword expected causes `(namespace value)` to throw an Error through LMDB's JNI layer).

---

## Settled Decisions

These are no longer open questions. Research is complete; see `research/` directory for evidence.

### 1. Data Constraints

All data flowing through the pipeline must be **fully spec'ed maps with namespaced keywords**:

- **No `:any`, no `:some`** -- every field has a concrete Datalevin-compatible type
- **No `[:maybe X]` on persisted schemas** -- use `{:optional true} X` instead
  - Key present = has a value of type X
  - Key absent = no value
  - No third state. No nil values in entity maps at the Datalevin boundary.
- **All keys are fully namespaced keywords** -- `:seon.runtime/status`, never `:status`
- **One schema per entity** -- what you write is what you read back. No input/output distinction.
- **Retraction is explicit** -- to clear a field, use `[:db/retract eid :attr]`. Absence in a transact map means "leave unchanged."

Rationale: These constraints make the nil problem disappear. No nil-stripping layers, no coercion, no hydration. Pull output directly matches Malli schema. See `research/nil-semantics-findings.md`.

### 2. Serialization: Nippy for Inter-JVM

Replace EDN (`pr-str`/`read-string`) in `seon.flow.harness.channel` with Nippy (`fast-freeze`/`fast-thaw`):

- **Same format Datalevin uses** on its wire protocol (port 8898)
- **Complete type fidelity** -- Float preserved, byte[] works, metadata preserved
- **3.7x faster** than EDN for typical payloads (~16us vs ~58us)
- **Already a dependency** via Datalevin
- Current EDN has 3 confirmed data corruption paths: byte[] not serializable, Float->Double coercion, no metadata

See `research/serialization-findings.md` for full evidence.

### 3. Validation via Malli at transact! Boundary

Add Malli validation in `db/transact!` before data reaches Datalevin:

- Use `m/validate` and `m/explain` -- do NOT write custom type-checking
- Each entity map validated against its registered Malli schema
- Clear error messages via `m/explain` on failure
- Fast-path: skip validation if schema has `{:db/skip-validation true}` (for bulk imports)

This is the missing gate. Currently `transact!` checks attrs are registered but does NOT validate values.

### 4. Refs: `:db/valueType` Property on Malli Schemas

Non-component refs use explicit `:db/valueType :db.type/ref` in Malli schema properties:

```clojure
(schema/register! :seon.call/from-fn
                  [:int {:db/valueType :db.type/ref
                         :description "Entity ref to :seon.fn entity"}])
```

The bridge already copies `:db/*` properties from Malli entry options. This works today. Component refs (nested `:map`) are auto-derived by the bridge as before.

### 5. Generative Pipeline Tests

Adopt spectomic's insight: generate N samples from a Malli schema, verify ALL survive the full pipeline:

```
Generate value -> Malli validate -> strip absent optionals -> transact -> pull -> Malli validate
```

This becomes the contract test and the feedback loop for agents developing schemas.

---

## Complete Type Mapping: Malli <-> Datalevin

### Verified Roundtrip Types (from test suite)

| Malli Type | Datalevin Type | Java Type | Roundtrips? | Notes |
|---|---|---|---|---|
| `:string` / `string?` | `:db.type/string` | `String` | Yes | Empty strings OK |
| `:int` / `int?` | `:db.type/long` | `Long` | Yes | Datalevin coerces via `(long v)` |
| `:double` / `double?` | `:db.type/double` | `Double` | Yes | NaN rejected (AssertionError); Inf OK |
| `:float` / `float?` | `:db.type/float` | `Float` | Lossy | Malli generates Double; Datalevin coerces, precision loss |
| `:boolean` / `boolean?` | `:db.type/boolean` | `Boolean` | Yes | |
| `:keyword` / `keyword?` | `:db.type/keyword` | `Keyword` | Yes | Namespaced keywords preserved |
| `:symbol` / `symbol?` | `:db.type/symbol` | `Symbol` | Yes | |
| `:uuid` / `uuid?` | `:db.type/uuid` | `UUID` | Yes | |
| `:inst` / `inst?` | `:db.type/instant` | `Date` | Yes | |

### Types Needing Custom Malli Schemas

| Datalevin Type | Java Type | Malli Schema | Generator |
|---|---|---|---|
| `:db.type/bigint` | `BigInteger` | `[:fn {:db/valueType :db.type/bigint} #(instance? BigInteger %)]` | Custom |
| `:db.type/bigdec` | `BigDecimal` | `[:fn {:db/valueType :db.type/bigdec} #(instance? BigDecimal %)]` | Custom |
| `:db.type/bytes` | `byte[]` | `[:fn {:db/valueType :db.type/bytes} bytes?]` | Custom |
| `:db.type/ref` | `Long` or `[kw val]` | `[:int {:db/valueType :db.type/ref}]` | N/A |

### Composite Type Mappings

| Malli Pattern | Datalevin Schema | Notes |
|---|---|---|
| `[:enum :a :b :c]` | `{:db/valueType :db.type/keyword}` | All same type -> infer. Mixed types -> reject at registration. |
| `[:enum "x" "y"]` | `{:db/valueType :db.type/string}` | String enum |
| `[:enum 1 2 3]` | `{:db/valueType :db.type/long}` | Long enum |
| `[:vector X]` / `[:set X]` | `{:db/valueType <type-of-X> :db/cardinality :db.cardinality/many}` | Pull returns vector, not set. Coerce on read if needed. |
| Nested `[:map ...]` | `{:db/valueType :db.type/ref :db/isComponent true}` + flattened child attrs | Component entities. Pull `[*]` returns nested map. |

### Types We Reject

| Malli Type | Why |
|---|---|
| `:any` | Too broad. Datalevin needs specific types. **Banned.** |
| `:some` | Same -- non-nil but untyped. **Banned.** |
| `[:maybe X]` (persisted) | Use `{:optional true} X` instead. Nil values don't exist in Datalevin. |
| `[:enum :a "b"]` | Mixed-type enum has no single Datalevin type. **Reject at registration.** |
| `:nil` | Datalevin cannot store nil. |
| `:fn` / `:=>` | Functions are not data. |

---

## Key Behavioral Findings (from REPL testing)

### Absence Semantics in Datalevin
- Absent key in transact map means "leave unchanged" (NOT retract)
- `d/pull` omits absent attributes entirely (key not in map, not nil)
- To remove a value: explicit `[:db/retract eid attr]` required
- Empty vector for cardinality-many is a no-op, not a retraction
- Transacting nil throws "Cannot store nil as a value"

### Cardinality-Many
- Datalevin stores as individual datoms (one per value)
- `d/pull` returns a **vector** (not a set)
- Both vector and set inputs work at transact time

### Refs
- **Component refs** (`{:db/isComponent true}`): `d/pull [*]` returns full nested entity
- **Non-component refs**: `d/pull [*]` returns `{:db/id N}` (just entity ID in map)
- **Lookup refs**: `[:identity-attr value]` resolved to entity ID at transact time
- **Target must exist**: lookup ref to nonexistent entity fails

### Keywords in Datalevin
- Stored as-is (namespace + name bytes via `key-sym-bytes`)
- `clojure.core/namespace` and `clojure.core/name` called on the value
- **If value is not Named (String, Vector, etc.), LMDB crashes** -- this is our #1 bug source

### NaN
- `Double/NaN` causes `AssertionError` in `datalevin.bits/encode-double`
- Must reject before transact (Malli validation catches this)

### Float
- Malli `:float` generator produces `Double`, not `Float`
- Datalevin coerces via `(float v)` -- works but loses precision
- Roundtrip: `Double` in -> `Float` out

---

## Bugs Fixed (2026-03-05)

### Bug 1: trace.clj passes conn where keyword expected -- FIXED
`persist-event!` now uses `(db/transact! :seon.flow [entity])`.

### Bug 2: ensure-schema! silently skips unmappable types -- FIXED
Now throws ex-info with clear message.

### Bug 3: trace/ctx datalevin-schema not in merged schema -- FIXED
Both added to `runtime-merged-schema`.

---

## Implementation Plan

### Phase 1: Validation Gate in transact! (DONE)

Added Malli validation to `db/transact!`:
- For each entity map, validates each attribute's value against its registered Malli schema
- Uses `m/validate` (fast boolean) first, only calls `m/explain` on failure
- Throws `ex-info` with `:attr`, `:expected-schema`, `:actual-value`, `:malli-explanation`
- Skips `:db/*` system attributes and vector tuples (`[:db/add ...]`, `[:db/retract ...]`)
- 21 tests, 57 assertions in `test/seon/db/validation_test.clj`
- Fixed `:seon.ctx/namespace` Malli schema (was `:symbol`, should be `:string` per actual usage)

### Phase 2: Generative Pipeline Test (DONE)

Built `assert-pipeline-roundtrip!` in `test/seon/db/pipeline_test.clj`:
- Takes a Malli `:map` schema, generates N entities, derives Datalevin schema via bridge
- Transacts each entity, pulls it back, validates against Malli, asserts value equality
- Pre-flight validation: rejects `:any`, `[:maybe X]`, unnamespaced keys
- Handles known Datalevin transformations: cardinality-many dedup/reorder (set comparison), empty collections (absent on pull), component ref `:db/id` stripping
- 13 tests, 56 assertions covering: leaf types, optional keys, enums, cardinality-many (set + vector), component refs, non-component refs, complex mixed entities, constraint violations
- Utility is reusable for Phase 3: each module's schema can be validated with one call

### Phase 3: Eliminate Hardcoded Schemas (IN PROGRESS)

For each module (runtime, ingest, ctx, trace, repl):
1. Ensure all attrs have Malli `schema/register!` with correct types
2. Add `:db/unique`, `:db/cardinality`, `:db/isComponent`, `:db/valueType` as Malli properties where needed
3. Replace hardcoded `datalevin-schema` with `(db-schema/malli-map->datalevin-schema ...)` call
4. Run generative pipeline tests to verify

#### Phase 3a: ctx.clj and repl.clj (DONE)

**ctx.clj** (4 attrs):
- Added `ctx-entity-schema` Malli :map schema with all 4 persisted attrs
- Replaced hardcoded `datalevin-schema` with `(db-schema/malli-map->datalevin-schema ctx-entity-schema)`
- Derived schema is identical to the previous hardcoded one (verified in REPL)
- Pipeline roundtrip test: 20 entities generated and roundtripped successfully

**repl.clj** (8 attrs):
- Added `form-entity-schema` Malli :map schema with all 8 persisted attrs
- `:form/name` is `{:optional true}` because expressions/requires have no name
- Replaced hardcoded `datalevin-schema` with `(db-schema/malli-map->datalevin-schema form-entity-schema)`
- Derived schema is identical to the previous hardcoded one (verified in REPL)
- Pipeline roundtrip test: 20 entities generated and roundtripped successfully
- Fixed smell: `:form/created-at` Malli registration was `:any`, now `:inst`

**Pipeline test utility improvement:**
- `roundtrip-one-entity!` now handles non-string identity keys (e.g., UUID)
  by using the generated value instead of synthetic `"gen-N"` strings

**Code smells flagged (not yet fixed):**
- `::form-name` (`:seon.repl/form-name`) is `[:maybe :string]` -- acceptable for function return values (nil for expressions) but not for persisted data. The persisted attr `:form/name` correctly uses `{:optional true} :string`.
- `::result` (`:seon.repl/result`) is `:any` -- acceptable for function return values (nREPL eval can return anything). Not persisted.

#### Phase 3c: runtime.clj (DONE)

**runtime.clj** (23 attrs across 3 entity types):
- Added `runtime-entity-schema` Malli :map schema (8 attrs, 5 optional)
- Added `agent-run-entity-schema` Malli :map schema (10 attrs, 8 optional including ref)
- Added `flow-snap-entity-schema` Malli :map schema (5 attrs, all required)
- Replaced hardcoded `runtime-schema` with `(merge (db-schema/malli-map->datalevin-schema ...))` of all 3 schemas
- Derived schema is identical to the previous hardcoded one (verified in REPL, 23 attrs, 0 diff)
- Pipeline roundtrip tests: 60 entities generated and roundtripped across 3 entity types
- First ref conversion: `:seon.agent.run/runtime` uses `{:db/valueType :db.type/ref}` on entity entry
- Manual ref test verifies lookup-ref transact and pull roundtrip for agent-run -> runtime linkage
- Key finding: `:db/*` properties must be on the :map entry, not the schema/register! call. The bridge reads entry-level properties via `(m/properties entry-schema)`, not schema-level properties.

**Code smells found:**
- None. All callers pass types consistent with the schemas. The `build-tx-map` function correctly uses `cond->` for optional fields, matching the `{:optional true}` annotations.

#### Phase 3d: ingest.clj (DONE)

**ingest.clj** (37 attrs across 6 entity types):
- Added 6 Malli entity schemas: `ns-entity-schema`, `fn-entity-schema`, `call-entity-schema`, `ns-dep-entity-schema`, `spec-entity-schema`, `var-entity-schema`
- Replaced hardcoded 37-attr `datalevin-schema` with `(merge (db-schema/malli-map->datalevin-schema ...))` of all 6 schemas
- Derived schema is identical to the previous hardcoded one (verified in REPL, 37 attrs, 0 diff)
- Pipeline roundtrip tests: 80 entities generated across 4 entity types (ns, fn, spec, var)
- Tempid roundtrip test for ns-dep entities (no identity key)
- Manual ref tests for fn-to-spec refs and call graph refs (lookup ref transact + pull)
- 7 new tests, 48 new assertions in `test/seon/db/pipeline_test.clj`

**Smells fixed:**
- 4 `:any` refs replaced with `:seon.db/ref` — a custom Malli type defined in `seon.schema` that accepts `pos-int?` (entity IDs) and `[keyword value]` (lookup refs). All 5 ref attrs use it (1 in runtime, 4 in ingest).
- All `inst?` registrations changed to `:inst` across ingest, runtime, ai, and trace.
- Bridge (`db/schema.clj`) recognizes `:seon.db/ref` → `{:db/valueType :db.type/ref}`.

**Stress test results (2026-03-05):**
- 5179 functions + 14212 call edges ingested via real clj-kondo analysis
- Lookup refs resolved to entity IDs, Datalog joins across refs work
- Nippy roundtrip verified on real entities, flow messages, and edge case types (byte[], Float, Instant, metadata)
- TCP channel bidirectional roundtrip verified with 100-entity bulk payload
- **Open research**: Unbounded `pull [*]` on 14K+ entities causes `NegativeArraySizeException` in Datalevin's TCP protocol. 14K entities is not large — investigate root cause in `reference-code/datalevin/src/datalevin/protocol.clj` and `server.clj`. May be a buffer sizing bug we can fix or work around.

**Phase 3 is now COMPLETE.** All 5 modules (ctx, repl, trace, runtime, ingest) have been unified.

### Phase 4: Nippy Inter-JVM Channel (COMPLETE)

Replaced EDN serialization in `seon.flow.harness.channel` with Nippy:
- `nippy/fast-freeze` for write, `nippy/fast-thaw` for read
- Same length-prefixed TCP protocol, binary payload instead of UTF-8 EDN
- Removed tagged literal machinery from `seon.flow.msg` (print-method, edn-readers, read-edn)
- Updated bridge.clj serialization roundtrip check from EDN to Nippy
- Updated tests: msg_test.clj (EDN roundtrip -> Nippy roundtrip + type fidelity test), channel_test.clj (type fidelity test)
- Instant vs Date audit: all Datalevin-bound Instants pass through `coerce-instants` in `ai/datalevin.clj`, correctly converting to `java.util.Date`. No production bug found.
- 734 tests, 3714 assertions, 0 failures

### Phase 5: Startup Consistency Check (COMPLETE)

At boot, verify all registered Malli schemas derive valid Datalevin types:
- Error on `:any`, `:some`, `:nil`, mixed enums
- Error on `[:maybe X]` in persisted schema positions
- Recurse into nested `:map` (component refs) and collections (`:vector`, `:set`)
- **Only validates persisted schemas** -- wire protocol schemas (`seon.flow.msg`) are excluded because they flow through Nippy over TCP, not Datalevin.

**Implementation:**
- `seon.db.schema/register-entity-schema!` -- explicit registration for persisted entity schemas
- `seon.db.schema/validate-persisted-schema` -- validate a single schema, returns violation vector
- `seon.db.schema/validate-persisted-schemas!` -- validate all registered schemas, throws on violation
- Each module (ctx, repl, trace, runtime, ingest, ai/datalevin, db/tx) calls `register-entity-schema!` at load time
- Integrant component `:seon.db.schema/consistency-check` runs after `:seon.schema/registry`, before DB operations
- 15 entity schemas registered across 7 modules, all passing validation
- 13 new tests, 56 assertions in `test/seon/db/consistency_test.clj`
- 727 tests, 3647 assertions, 0 failures across full test suite

### Phase 5a: Wire Protocol Fixes (IN PROGRESS)

Fix settled-decision violations in `seon.flow.msg`:
- `::created-at` `[:fn inst?]` -> `:inst` (all timestamps use `:inst`)
- `::error-data` `[:maybe :map]` -> `:map` with `{:optional true}` on entries
- `bridge.clj` conditionally assoc `::error-data` only when `(ex-data e)` is non-nil

### Phase 6: Wire Protocol Dynamic Validation (Eliminate `:any`)

**Problem:** Wire protocol schemas use `:any` for `::args`, `::value`, `::payload` because the types aren't known at schema-definition time. But they ARE known at runtime.

**Insight:** Every function called through the wire protocol has a `:malli/schema` that specifies its argument and return types. Every payload key is a namespaced keyword with a registered Malli schema. We can validate dynamically against the registry instead of statically declaring `:any`.

**Approach:** At the message send/receive boundary, recursively validate content fields against the Malli registry:
- `::args` -- resolve `::fn` var -> get `:malli/schema` -> validate args against input `:cat`
- `::value` -- same function -> validate against return schema
- `::payload` -- walk the map, validate each value against `(schema/resolve key)`
- If a key or function has no registered schema, **throw** -- that's a bug, not an `:any`

The envelope schema remains structural (correct keys, types for `::id`, `::type`, etc.). Content validation is dynamic at the boundary.

**Files:** `src/seon/flow/msg.clj`, `src/seon/flow/harness/bridge.clj`, `src/seon/flow/harness/channel.clj`

### Phase 7: render.clj `::html` Type

Replace `::html :any` with a concrete type. HTML output is Hiccup -- vectors, strings, keywords, maps. Define a Hiccup schema or use the same registry-validation approach as Phase 6 (if the value is a map with namespaced keys, validate each against the registry).

**File:** `src/seon/render.clj`

### Phase 8: Rename `seon.ai.datalevin` -> `seon.ai.store` + Convention Audit

**Problem:** `seon.ai.datalevin` names the implementation detail (Datalevin) instead of the responsibility (AI storage). The namespace doesn't touch `datalevin.core` -- it goes through `seon.db` exclusively. Additionally, ~15 `dl-*` functions violate conventions: positional args instead of map-in/map-out, missing `:malli/schema`, defensive nil-stripping instead of clean data.

**Scope:**
1. Rename namespace: `seon.ai.datalevin` -> `seon.ai.store`
2. Rename all `dl-*` functions to drop the prefix (e.g., `dl-get-session` -> `get-session`)
3. Add `:malli/schema` to all public functions
4. Convert public functions to map-in/map-out pattern
5. Fix entity schema violations: `inst?` -> `:inst` on lines 131-132, 148
6. Remove `remove-nil-values` -- callers should pass clean data
7. Remove `coerce-instants` -- callers should pass `java.util.Date` consistently
8. Update all consumers: `seon.ai`, `seon.ai.claude`, `seon.web.agents`
9. Rename test file: `seon.ai.datalevin-test` -> `seon.ai.store-test`

**Consumers (4 files):**
- `src/seon/ai.clj` -- uses `requiring-resolve` for lazy loading
- `src/seon/ai/claude.clj` -- uses `requiring-resolve` for real-time persistence
- `src/seon/web/agents.clj` -- direct require
- `test/seon/ai/datalevin_test.clj` -- test file

---

## Hardcoded Schemas to Eliminate

| File | Schema var | Attr count |
|------|-----------|-----------|
| `src/seon/runtime.clj` | `runtime-schema` | ~20 |
| `src/seon/graph/ingest.clj` | `datalevin-schema` | ~30 |
| `src/seon/ctx.clj` | `datalevin-schema` | 4 |
| `src/seon/flow/trace.clj` | `datalevin-schema` | ~10 |
| `src/seon/repl.clj` | `datalevin-schema` | 8 |

---

## Roundtrip Test Suite

**Location:** `test/seon/db/schema_roundtrip_test.clj`
**Status:** 36 tests, 411 assertions, all passing

Tests define the contract. Any bridge changes must keep all tests green.

Categories: leaf types (9), enums (3), schema derivation (4), maybe/nil (2), cardinality-many (3), component refs (2), edge cases (7), full entity (1), gap detection (4).

---

## Research Reports

| Report | Key Finding |
|--------|------------|
| `research/serialization-findings.md` | Nippy is the clear choice. Datalevin uses it on wire. EDN has 3 data corruption paths. 3.7x faster. |
| `research/nil-semantics-findings.md` | Absence = no value. No `[:maybe X]` for persisted. `{:optional true}` only. Naive nil-stripping is lossy for updates. |

### Datalevin Large Query Overflow (Research)

**Symptom:** `NegativeArraySizeException` when doing unbounded `pull [*]` on 14K+ entities over Datalevin TCP.

**Root cause (from previous agent):** Signed 32-bit int overflow in `protocol.clj:196` -- the message length field uses `.putInt`/`.getInt` (max ~2.14 GB). When serialized payload exceeds `Integer.MAX_VALUE`, the int wraps negative, and `getInt()` returns a negative number used as array size.

**Not entity-count-related** -- it's about serialized payload size. 14K entities with `pull [*]` likely produces a large Nippy payload.

**Open questions:**
1. **Nippy compression** -- `fast-freeze` (what Datalevin uses) skips compression for speed. Standard `nippy/freeze` uses LZ4 compression by default. Can we configure Datalevin to use compressed serialization? Or does Datalevin's protocol use its own serialization layer independent of Nippy?
2. **Batched pulls** -- workaround: split large pulls into batches (e.g., 1000 entities at a time). Does Datalevin support query pagination natively?
3. **PR to Datalevin** -- change `.putInt`/`.getInt` to `.putLong`/`.getLong` in protocol.clj? Breaking wire protocol change -- would need Datalevin maintainer buy-in.
4. **Impact assessment** -- does anything in Seon actually pull 14K+ entities in production? If only the code scanner does this, we can batch there specifically.

## Prior Art

### malli-datomic (`reference-code/malli-datomic/`)
- Schema derivation only, no serialization opinions
- Maps `:set`/`:map`/`:vector`/`:sequential` to `:db.type/ref`
- Copies `:db/*` properties from Malli entry options (same as our bridge)
- Throws on unsupported types (no silent skip)
- Has zero `[:maybe]` support

### spectomic (`reference-code/spectomic/`)
- Generates 100 samples, infers types from Java classes
- Filters nil samples for `s/nilable` (same as our "strip nils" approach)
- Key insight: **generative testing of the schema itself** -- verify all samples map to one Datalevin type
