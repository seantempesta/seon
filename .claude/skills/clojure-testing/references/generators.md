# Test Generators

Domain-specific data generators using Malli and test.check.

## Option Greeks Generator

Location: `test/ml_options/generators.clj`

The option-greeks generator produces valid test data for the `:option-greeks` table.

### Key Requirements

1. **`:xt/id` must be a string** - Schema expects string, not UUID
2. **ID format must match production** - `"{OCC_SYMBOL}-{ISO_TIMESTAMP}"`
3. **Timestamps must be valid** - Use `gen-historical-instant` for realistic dates

### Production ID Format

From `src/ml_options/data/thetadata.clj`:
```clojure
(str occ-symbol "-" (.toString quote-instant))
;; Example: "AAPL250117C00230000-2024-11-27T15:56:58.017Z"
```

### Generator Pattern

```clojure
(defn gen-option-greeks []
  (gen/let [occ (gen-occ-symbol)
            quote-instant (gen-historical-instant)]
    {:xt/id (str occ "-" (.toString quote-instant))
     :asset/ticker (occ->ticker occ)
     ;; ... other fields
     }))
```

### Common Mistakes

| Mistake | Problem | Fix |
|---------|---------|-----|
| `(UUID/randomUUID)` | Schema expects string | Use `(str occ "-" timestamp)` |
| Hardcoded dates | Not realistic | Use `gen-historical-instant` |
| Missing fields | Schema validation fails | Include all required fields |

## OCC Symbol Generator

Generates valid OCC option symbols:

```clojure
(defn gen-occ-symbol []
  (gen/let [ticker (gen/elements ["AAPL" "SPY" "NVDA" "TSLA"])
            exp-date (gen-expiration-date)
            right (gen/elements ["C" "P"])
            strike (gen/choose 50 500)]
    (format "%-6s%s%s%08d" ticker exp-date right (* strike 1000))))
```

Format: `AAPL  250117C00150000` (ticker padded to 6 chars)

## Time Generators

```clojure
;; Historical instant (past year)
(defn gen-historical-instant []
  (gen/let [days-ago (gen/choose 1 365)
            hour (gen/choose 9 16)
            minute (gen/choose 0 59)]
    (-> (java.time.Instant/now)
        (.minus days-ago java.time.temporal.ChronoUnit/DAYS)
        ;; ... adjust to market hours)))

;; Expiration date (YYMMDD format)
(defn gen-expiration-date []
  (gen/let [months-out (gen/choose 1 12)]
    ;; Generate 3rd Friday of month...))
```
