---
type: research
status: completed
tags: [research, archive, schema]
---

# Schema Generator Test Failures - Research Findings

## Executive Summary

The test `ml-options.db.schema-test/custom-generators-produce-valid-data` fails because the **generator produces UUIDs but the schema expects strings** for `:xt/id`.

**Root Cause**: Generator bug, not schema bug. Fix the generator.

---

## Root Cause Analysis

### The Mismatch

| Component | What it does | Location |
|-----------|--------------|----------|
| **Schema** | Defines `:xt/id` as `:string` | `src/ml_options/db/schema.clj:75` |
| **Generator** | Produces `(java.util.UUID/randomUUID)` | `test/ml_options/generators.clj:135` |
| **Production** | Uses deterministic string IDs | `src/ml_options/data/thetadata.clj:169-170` |

### Production ID Format

In production, `:xt/id` values are deterministic strings:

```clojure
;; thetadata.clj:169-170
xt-id (when (and occ-symbol quote-instant)
         (str occ-symbol "-" (.toString quote-instant)))

;; Example: "AAPL20250117C00230000-2024-11-27T15:56:58.017Z"

```

Format: `"{OCC_SYMBOL}-{ISO_TIMESTAMP}"`

### Why Schema Uses String

The schema is **correct** - `:xt/id` should be a string because:
1. Production always generates deterministic string IDs
2. String IDs enable idempotent ingestion (same data = same ID)
3. Transaction builders have fallback but prefer deterministic IDs

### Why Generator Uses UUID

The generator is **incorrect** - line 135 in `generators.clj`:

```clojure
{:xt/id (java.util.UUID/randomUUID)  ;; BUG: Should be string
 :asset/ticker ticker
 ...}

```

This was likely a shortcut during initial development that was never corrected.

---

## Schema Definitions

### OptionQuote Schema (schema.clj:75)

```clojure
(def OptionQuote
  [:map {:closed true}
   [:xt/id :string]  ;; <-- Expects string
   [:asset/ticker [:string {:min 1 :max 5 ...}]]
   [:option/id :string]
   ...])

```

### IVSurface Schema (schema.clj:115)

Same pattern - `:xt/id :string`

### TradingSignal Schema (schema.clj:129)

Different - uses `:signal/id :uuid` intentionally (separate concern)

---

## Transaction Builder Handling

The `put-option-quote` function (transactions.clj:91-98) can handle both:

```clojure
(defn put-option-quote
  [quote]
  (let [id (or (:xt/id quote)                    ;; Use provided ID
               (make-option-quote-id quote)       ;; Or generate deterministic
               (str (UUID/randomUUID)))]          ;; Fallback to UUID string
    ...))

```

This explains why production works even though tests fail - the transaction builder converts UUIDs to strings as a fallback.

---

## Recommended Fix

**Fix the generator** in `test/ml_options/generators.clj`:

### Current Code (Broken)

```clojure
(def gen-valid-option-quote
  (gen/let [ticker gen-ticker
            occ gen-occ-symbol
            ...]
    {:xt/id (java.util.UUID/randomUUID)  ;; BUG
     :asset/ticker ticker
     ...}))

```

### Fixed Code

```clojure
(def gen-valid-option-quote
  (gen/let [ticker gen-ticker
            occ gen-occ-symbol
            quote-instant gen-expiry-instant  ;; Add timestamp generator
            ...]
    {:xt/id (str occ "-" (.toString quote-instant))  ;; Deterministic string
     :asset/ticker ticker
     ...}))

```

### Why This Fix Is Best

1. **Mirrors production** - Tests validate actual ID format used
2. **No schema changes** - Schema is correct as-is
3. **Property tests catch real issues** - Generator produces valid data
4. **Single source of truth** - ID format matches thetadata.clj

---

## Related Issues

### Missing ID Generator Helper

Consider extracting a dedicated `gen-option-quote-id` to avoid duplicating the ID format logic:

```clojure
(def gen-option-quote-id
  "Generate deterministic option quote ID in production format."
  (gen/let [occ gen-occ-symbol
            timestamp gen-expiry-instant]
    (str occ "-" (.toString timestamp))))

```

Then use in `gen-valid-option-quote`:

```clojure
{:xt/id gen-option-quote-id
 ...}

```

### IVSurface Generator

If there's a generator for IVSurface, check it has the same issue.

---

## Code References

| File | Line | Description |
|------|------|-------------|
| `src/ml_options/db/schema.clj` | 75 | OptionQuote schema with `:xt/id :string` |
| `test/ml_options/generators.clj` | 135 | **BUG**: Generator produces UUID |
| `src/ml_options/data/thetadata.clj` | 169-170 | Production ID format |
| `src/ml_options/db/transactions.clj` | 91-98 | Transaction builder with fallback |

---

## Conclusion

This is a **legitimate schema validation failure**, not a false positive. The generator violates the schema contract. Fix the generator to produce valid deterministic string IDs matching the production format.
