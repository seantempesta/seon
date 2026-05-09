---
type: research
status: completed
tags: [prd, research, database, schema]
---
# Nil Semantics Research Findings

## Executive Summary

In Seon's data pipeline, "no value" must be modeled as **key absence** at the Datalevin boundary. Datalevin (and all EAV databases) have no concept of a null datom -- an attribute either exists or it does not. This creates a semantic gap with Malli's `[:maybe X]`, which validates both `nil` and `X` as values, and with application code that uses `{:key nil}` as a natural representation of "no value."

The critical finding: **naive nil-stripping before transact is lossy for updates**. If an entity already has `:foo "hello"` and the application wants to clear it by sending `{:foo nil}`, stripping nils silently turns "clear this field" into "leave this field unchanged." A correct solution must distinguish between "I did not mention this field" (leave unchanged) and "I explicitly set this field to nil" (retract it).

---

## 1. How Datalevin Handles "No Value"

### Findings (all verified in REPL on Datalevin 0.9.x)

**A. Absence means "leave unchanged" on transact:**

```clojure
;; Transact entity with :test/foo and :test/bar
(d/transact! conn [{:test/id 1 :test/foo "hello" :test/bar "world"}])
;; => {:db/id 1, :test/id 1, :test/foo "hello", :test/bar "world"}

;; Transact same entity WITHOUT :test/foo
(d/transact! conn [{:test/id 1 :test/bar "updated"}])
;; => {:db/id 1, :test/id 1, :test/foo "hello", :test/bar "updated"}
;;    ^^ :test/foo PERSISTS. Absence does NOT retract.

```

**B. `d/pull` omits absent attributes entirely (no nil):**

```clojure
(d/transact! conn [{:test/id 2 :test/bar "only-bar"}])
(d/pull @conn '[*] [:test/id 2])
;; => {:db/id 2, :test/id 2, :test/bar "only-bar"}
;; :test/foo is NOT in the map at all. Not nil, not false -- absent.

(contains? result :test/foo) ;; => false
(:test/foo result)           ;; => nil (Clojure's default for missing keys)

```

Pull with explicit attribute selectors also omits absent attributes:

```clojure
(d/pull @conn '[:test/id :test/foo :test/bar] [:test/id 2])
;; => #:test{:id 2, :bar "only-bar"}
;; :test/foo still absent, even though explicitly requested.

```

**C. Explicit retraction removes a datom:**

```clojure
;; Retract with value specified
(d/transact! conn [[:db/retract [:test/id 1] :test/foo "hello"]])
;; :test/foo is now absent from entity 1

;; Retract WITHOUT value (retract all values for this attr on this entity)
(d/transact! conn [[:db/retract [:test/id 1] :test/foo]])
;; Also works -- retracts whatever value :test/foo has

```

**D. Cardinality-many: empty vector is a no-op, not a retraction:**

```clojure
(d/transact! conn [{:test/id 1 :test/tags [:a :b :c]}])
(d/transact! conn [{:test/id 1 :test/tags []}])
;; Tags are STILL [:a :b :c]. Empty vector does nothing.

;; To remove all values:
(d/transact! conn [[:db/retract [:test/id 1] :test/tags]])
;; Now entity has no :test/tags attribute at all.

```

**E. Transacting nil throws:**

```clojure
(d/transact! conn [{:test/id 1 :test/bar nil}])
;; => Exception: "Cannot store nil as a value at {:test/id 1, :test/bar nil, ...}"

```

### Implications

- Datalevin transactions are **additive by default**: omitting a key preserves its current value.
- The ONLY way to remove a value is explicit `[:db/retract eid attr]` or `[:db/retract eid attr value]`.
- `d/pull` never returns nil values -- absent means absent.
- Any roundtrip through Datalevin will convert `{:key nil}` to key-absence.

---

## 2. How Datomic Users Model "No Value"

### malli-datomic (`reference-code/malli-datomic/`)

**Finding:** malli-datomic has **zero handling for `[:maybe X]`**. The `derive-value-type` function in `datomic_schema_gen.cljc` has no case for `:maybe`. Passing a `[:maybe :string]` schema would throw `"Unsupported data type"` with `{:type :maybe}`.

The library focuses purely on schema derivation (Malli type -> Datomic `:db/valueType`). It does not address nil stripping, retraction, or optional field semantics. Users are expected to handle nil themselves.

**Source:** `reference-code/malli-datomic/src/blasterai/malli_datomic/datomic_schema_gen.cljc`, `derive-value-type` function (lines 41-85). Grep for "maybe" returns zero relevant hits.

### spectomic (`reference-code/spectomic/`)

**Finding:** spectomic handles `s/nilable` in two places:

1. **Form-level:** `find-type-via-form` (line 143-144) recognizes `clojure.spec.alpha/nilable` and recurses into the inner spec, stripping the nilable wrapper. This is exactly what Seon's `schema->datalevin-attr` does for `:maybe`.

2. **Sample-level:** `sample-types` (line 48-49) filters nil samples with `(filter some?)`. The comment says "we need to remove nils for the cases where a spec is nilable." The generator approach (`such-that` on line 66-73) also explicitly filters: `(and (some? s) ...)`.

**Source:** `reference-code/spectomic/src/provisdom/spectomic/core.clj`, lines 43-49 and 143-144.

### Community Consensus (from Gemini search)

The Datomic/Datalevin community pattern is **"Absence is Nil"**:

- There is no "null datom." An attribute either has a value or it does not exist.
- `[:maybe X]` / `s/nilable` is used in Malli/Spec for application-level optionality.
- Before transact: strip nil values from entity maps.
- On pull: absent keys are naturally nil when destructured.
- For "clear a field" (update to nil): must issue explicit `[:db/retract eid attr]`.
- No prior art library handles the "update to nil -> retract" conversion automatically.

---

## 3. How Malli Models Optionality

### `[:maybe X]` Semantics (verified in REPL)

```clojure
(m/validate [:maybe :string] nil)    ;; => true
(m/validate [:maybe :string] "foo")  ;; => true
(m/validate [:maybe :string] 42)     ;; => false

```

`[:maybe X]` means "X or nil." It validates the **value**, not the key's presence.

### `{:optional true}` Semantics (verified in REPL)

```clojure
(m/validate [:map [:foo {:optional true} :string]] {})           ;; => true  (absent)
(m/validate [:map [:foo {:optional true} :string]] {:foo "x"})   ;; => true  (present)
(m/validate [:map [:foo {:optional true} :string]] {:foo nil})   ;; => false (nil fails :string)

```

`{:optional true}` means "key may be absent." If present, the value must match the schema. Nil is NOT a valid string.

### Combined `{:optional true} + [:maybe X]` (verified in REPL)

```clojure
(def schema [:map [:foo {:optional true} [:maybe :string]]])
(m/validate schema {})             ;; => true  (key absent)
(m/validate schema {:foo nil})     ;; => true  (key present, value nil)
(m/validate schema {:foo "hello"}) ;; => true  (key present, value string)
(m/validate schema {:foo 42})      ;; => false (wrong type)

```

This is the **three-way optionality**: absent, nil, or value. It is the most permissive combination.

### Critical Distinction: Required `[:maybe X]` vs `{:optional true}`

```clojure
;; Required [:maybe :string] -- key MUST be present, value can be nil
(m/validate [:map [:foo [:maybe :string]]] {})          ;; => false (key required!)
(m/validate [:map [:foo [:maybe :string]]] {:foo nil})   ;; => true

;; Optional :string -- key can be absent, but if present must be string
(m/validate [:map [:foo {:optional true} :string]] {})           ;; => true
(m/validate [:map [:foo {:optional true} :string]] {:foo nil})   ;; => false

```

**This matters for Datalevin roundtrip:** Required `[:maybe :string]` requires `{:foo nil}` to validate, but Datalevin pull returns `{}` (key absent). The pulled entity would FAIL Malli validation for required `[:maybe X]` schemas.

### Generator Behavior (verified in REPL, 100 samples each)

| Schema | Key Absent | Key Present + nil | Key Present + Value |
|--------|-----------|------------------|-------------------|
| `{:optional true} :string` | ~42% | 0% | ~58% |
| `[:maybe :string]` (required) | 0% | ~50% | ~50% |
| `{:optional true} [:maybe :string]` | ~53% | ~20% | ~27% |

Generators produce nil values for `[:maybe X]` schemas. Without nil-stripping, generative tests that transact to Datalevin will crash.

### Implications

- **For Datalevin-persisted fields:** Use `{:optional true}` (key may be absent), NOT required `[:maybe X]` (requires nil value present). This matches what pull returns.
- **For application-only fields:** `[:maybe X]` is fine since no Datalevin roundtrip.
- **For generative tests:** Any schema with `[:maybe X]` will produce nil values ~50% of the time. Must strip before transact.
- **The current codebase has ~39 uses of `[:maybe X]` WITHOUT `{:optional true}`** -- these are "required key, nullable value" which cannot roundtrip through Datalevin.

---

## 4. Current Seon Codebase Patterns for Nil Handling

### Nil-stripping Locations

Only **one place** in production code strips nils before Datalevin:

1. **`seon.ai.datalevin/remove-nil-values`** (lines 212-217): Private helper used by `entity->datalevin-session` and `entity->datalevin-message`. Comment: "Datalevin throws NPE when trying to transact nil values. This is defensive -- source should avoid nils, but belt-and-suspenders."

One place in **test code**:

1. **`seon.db.schema-roundtrip-test/strip-nils`** (line 22): Helper for roundtrip tests. Used by `roundtrip!` helper and `full-entity-roundtrip-test`.

### No Centralized Nil Handling

`seon.db/transact!` does **no nil-stripping**. The writer step-fn (`seon.db.datalevin.writer/infra-writer-step`) does **no nil-stripping**. Nil values in transaction data will crash at the Datalevin layer.

### `[:maybe ...]` Usage Scale

- **109 occurrences** of `[:maybe ...]` across 24 source files
- **526 occurrences** of `{:optional true}` across 43 source files
- **70 occurrences** combine both `{:optional true}` + `[:maybe ...]`
- **~39 occurrences** use `[:maybe ...]` without `{:optional true}` (required key, nullable value)

Most `[:maybe ...]` usage is in `dev/` namespace schemas (review, hook, lint, context) which are NOT persisted to Datalevin. The domain-relevant ones are in `orchestrator/session.clj`, `ctx.clj`, `runtime.clj`, `ai/gemini.clj`, and `ai/datalevin.clj`.

### Retraction Patterns

The codebase uses retraction in exactly one domain: `seon.graph.ingest`. It uses `[:db/retractEntity eid]` to retract entire stale entities (functions, specs, vars that no longer exist in a namespace scan). It does NOT use `:db/retract` to clear individual attributes.

No code anywhere converts "nil value" to "retract attribute."

---

## 5. Nil Interaction with core.async Flow

### Findings

**Maps with nil values flow through channels without issue:**

```clojure
(let [ch (async/chan 1)]
  (async/>!! ch {:foo nil :bar "hello"})
  (async/<!! ch))
;; => {:foo nil, :bar "hello"}
;; :foo key present, value nil, passes through channel intact.

```

**Flow step-fn nil conventions:** Step-fns return `[new-state output-map-or-nil]`. The `nil` in this context means "no output messages" -- it is the output map itself being nil, completely unrelated to nil values inside entity data maps.

**No step-fns inspect or filter nil values within entity maps.** The writer step-fn (`infra-writer-step`) passes `tx-data` straight to `d/transact!` without any nil processing.

**No sentinel usage of nil in maps.** The flow infrastructure does not use nil-valued map entries as signals or control flow markers.

### Implications

- The flow layer is **nil-transparent** for map values. `{:foo nil}` enters a channel and exits unchanged.
- Any nil-stripping or nil-to-retraction conversion must happen BEFORE data enters the flow (in `db/transact!`) or as a dedicated step-fn/middleware.
- There are no backward compatibility concerns with adding nil handling -- no existing flow code depends on nil values being present or absent in entity maps.

---

## 6. Three Design Options -- Trade-off Analysis

### Option A: Absence = No Value (Simple, Current Direction)

**Rule:** Absent key means "no value." Before transact, strip all nil-valued keys. On pull, absent key naturally returns nil when destructured.

**Malli contract:**

```clojure
;; Datalevin-persisted field that may have no value:
[:foo {:optional true} :string]

;; NOT this (would fail validation on pull result):
[:foo [:maybe :string]]

```

| Layer | Behavior | Notes |
|-------|----------|-------|
| **Application code** | `(:foo entity)` returns nil for absent key | Natural Clojure |
| **Malli validation** | `{:optional true}` allows absent key | Matches pull output |
| **Flow channels** | Maps may or may not contain the key | Transparent |
| **db/transact!** | strip-nils removes `{:foo nil}` entries | New: centralized |
| **Datalevin** | Key absent = no datom | Natural EAV |
| **d/pull** | Absent key omitted from result map | Natural |

**Retraction problem:** To clear a field that currently has a value, the application must explicitly issue `[:db/retract eid :attr]`. Simple nil-stripping turns "clear field" into "leave unchanged."

**Solutions to the retraction problem:**

- Convention: callers use `(db/retract! :seon.runtime [:entity/id "x"] :entity/foo)` explicitly.
- Sentinel: use a special value like `::db/retract` to signal "clear this field."

**Pros:**

- Simplest mental model. Absence is the only representation of "no value."
- Matches what Datalevin actually does. No impedance mismatch.
- No coercion layers on read. Pull output is directly usable.
- `{:optional true}` is well-understood in Malli.

**Cons:**

- Cannot distinguish "I didn't mention this field" from "clear this field" in a transaction map.
- Required `[:maybe X]` schemas (39 existing uses) would need to change to `{:optional true} X`.
- Merge semantics differ: `(merge old-entity update-map)` preserves old values for absent keys, which is usually desired but means you can't "clear" via merge.

**Where bugs can hide:**

- Caller forgets to issue retraction when clearing a field. Old value persists silently.
- Generative tests produce nil values; if nil-stripping is in the test helper but not in production `transact!`, tests pass but production crashes.

### Option B: `[:maybe X]` with Nil-Stripping + Retraction Layer

**Rule:** `[:maybe X]` in schema means "X or nil." A nil-stripping layer before transact handles new entities. For updates, nil-valued keys on known entities become retractions.

**Malli contract:**

```clojure
;; Datalevin-persisted field that may have no value:
[:foo {:optional true} [:maybe :string]]

;; Application schema (for function signatures, not storage):
[:foo [:maybe :string]]

;; Output schema (what pull returns + hydration):
[:foo {:optional true} [:maybe :string]]

```

| Layer | Behavior | Notes |
|-------|----------|-------|
| **Application code** | `{:foo nil}` means "no value" | Explicit nil |
| **Malli validation** | `[:maybe X]` validates nil | Natural |
| **Flow channels** | `{:foo nil}` flows through | Transparent |
| **db/transact!** | nil -> strip (new) or retract (update) | New: smart middleware |
| **Datalevin** | No nil stored; retraction for clears | Natural EAV |
| **d/pull** | Absent key omitted | Mismatch with `[:maybe X]` |
| **Hydration layer** | Absent key -> `{:foo nil}` for `[:maybe]` fields | New: on read |

**Roundtrip:**

```
Write: {:foo nil} -> strip nil -> transact (no datom)
Read:  pull (absent) -> hydrate -> {:foo nil}

```

**Pros:**

- Preserves Malli-idiomatic `[:maybe X]` semantics.
- Nil as explicit "clear this field" intention.
- Roundtrip preserves `{:foo nil}` through hydration layer.
- More natural for application code that passes form data with nil fields.

**Cons:**

- Requires two new layers: nil-to-retraction on write, hydration on read.
- The hydration layer needs the Malli schema to know which absent keys should become nil.
- Two representations of "no value" in the system (`{:foo nil}` in app, absent key in DB).
- More complex to reason about. Which representation am I looking at?
- Performance: hydration layer must process every pulled entity.

**Where bugs can hide:**

- Hydration applied inconsistently (some code paths get hydrated maps, others get raw pulls).
- Schema and hydration out of sync (schema says `[:maybe X]` but hydration not updated).
- The retraction layer must distinguish new entities (strip nils) from updates (retract), which requires knowing whether the entity already exists.

### Option C: Absence for EAV, Nil for Application (Mixed Boundary)

**Rule:** In Datalevin, only absence. In application/flow code, nil values allowed. A coercion boundary translates between the two at transact and pull.

This is essentially Option B with a more explicit architectural framing. The "boundary" is explicitly modeled as a pair of functions:

```clojure
;; Write boundary (before transact)
(defn prepare-for-storage [malli-schema entity]
  ;; Strip nils for new entities
  ;; Convert nils to retractions for updates
  ;; Validate against schema
  )

;; Read boundary (after pull)
(defn hydrate-from-storage [malli-schema pulled-entity]
  ;; Add nil for absent [:maybe X] fields
  )

```

| Layer | Behavior |
|-------|----------|
| Application | `{:foo nil}` natural |
| Write boundary | nil -> strip/retract |
| Datalevin | Absence only |
| Read boundary | Absent -> nil for `[:maybe]` fields |
| Application | `{:foo nil}` restored |

**Pros:**

- Most flexible. Each layer uses its natural representation.
- Clear architectural boundary.
- Nippy serialization preserves nil natively (type-id 3).

**Cons:**

- Most complex. Three representations: explicit nil, absence, and the boundary functions.
- Every DB interaction must go through boundaries (already true via `db/transact!` and `db/pull-by-name`).
- Must maintain boundary functions as schemas evolve.

**Where bugs can hide:**

- All the same as Option B, plus: if anyone bypasses the boundary (direct `d/pull`), they get unhyrated maps.

---

## Open Questions

### 1. Which schemas actually need Datalevin roundtrip?

The 109 `[:maybe ...]` occurrences are across 24 files, but most are in `dev/` namespaces that are never persisted. Before choosing a strategy, audit which `[:maybe ...]` schemas are on the Datalevin path. If it is only a handful, Option A (simplest) may be sufficient.

### 2. Is the "update to nil" use case real?

The critical difference between options is how "clear a field" works. If Seon rarely or never needs to clear an attribute value on an existing entity (as opposed to retracting the entire entity), then Option A's retraction gap is theoretical. Check: does any domain code currently need to "clear" a field? Or is retract-entity the normal pattern (as in `graph/ingest`)?

### 3. Input vs output schema distinction

Seon currently uses single schemas for both input validation (before transact) and output validation (after pull). Options B and C require different schemas for input and output, or a hydration layer. Is this acceptable complexity? Could the schema registry track `{:persisted? true}` to auto-derive the right variant?

### 4. Generative test strategy

Regardless of option chosen: generative tests for Datalevin-persisted schemas MUST handle nil values from `[:maybe X]` generators. Options:

- Strip nils in test helper (current approach in `roundtrip-test`)
- Use custom generators that never produce nil for persisted fields
- Use `{:optional true}` instead of `[:maybe X]` for persisted fields (nil never generated for the value, only key absence)

### 5. Interaction with Nippy serialization

Nippy serializes nil natively (type-id 3). For inter-JVM communication via Nippy, `{:foo nil}` roundtrips perfectly. The nil-stripping concern is only at the Datalevin boundary. If Seon moves to Nippy for flow channels, nil values in maps remain transparent through the entire flow until Datalevin.
