# Options Data Ingestion

This document describes the data ingestion pipeline for options market data into XTDB.

## Overview

The `ml-options.data.ingest` namespace provides utilities for:

1. **OCC Symbol Parsing** - Parse standardized option symbols into components
2. **Data Transformation** - Convert external data formats to internal schema
3. **Validation** - Validate data against Malli schemas before insertion
4. **Batch Ingestion** - Insert data with proper bitemporal valid-time

## OCC Symbol Format

The Options Clearing Corporation (OCC) symbol format is the standard for option identifiers:

```
ROOT + YYMMDD + C/P + STRIKE*1000
```

### Components

- **ROOT**: Underlying ticker symbol (1-5 uppercase letters)
- **YYMMDD**: Expiration date (2-digit year, month, day)
- **C/P**: Option type (C = Call, P = Put)
- **STRIKE**: Strike price × 1000, padded to 8 digits

### Examples

| OCC Symbol | Ticker | Expiry | Type | Strike |
|------------|--------|---------|------|--------|
| `AAPL231215C00185000` | AAPL | 2023-12-15 | Call | $185.00 |
| `SPY240119P00470000` | SPY | 2024-01-19 | Put | $470.00 |
| `TSLA240126C00250000` | TSLA | 2024-01-26 | Call | $250.00 |

## Usage

### Parsing OCC Symbols

```clojure
(require '[ml-options.data.ingest :as ingest])

;; Parse a symbol
(ingest/parse-occ-symbol "AAPL231215C00185000")
;; => {:ticker "AAPL"
;;     :expiry #inst "2023-12-15T00:00:00"
;;     :type :call
;;     :strike 185.0
;;     :raw-symbol "AAPL231215C00185000"}

;; Build a symbol from components
(ingest/build-occ-symbol "AAPL" #inst "2023-12-15" :call 185.0)
;; => "AAPL231215C00185000"
```

### Transforming Databento Data

```clojure
;; Transform a single Databento row
(def databento-row
  {:symbol "AAPL231215C00185000"
   :bid_px 5.25
   :ask_px 5.35
   :iv 0.35
   :delta 0.55
   :gamma 0.05
   :vega 15.2
   :theta -0.15
   :volume 1000})

(ingest/databento-row->option-quote databento-row)
;; => {:xt/id #uuid "..."
;;     :asset/ticker "AAPL"
;;     :option/id "AAPL231215C00185000"
;;     :option/strike 185.0
;;     :option/type :call
;;     :option/expiry #inst "2023-12-15T00:00:00"
;;     :quote/bid 5.25
;;     :quote/ask 5.35
;;     :quote/iv 0.35
;;     :greeks/delta 0.55
;;     :greeks/gamma 0.05
;;     :greeks/vega 15.2
;;     :greeks/theta -0.15
;;     :market/volume 1000}
```

### Validating Quotes

```clojure
;; Validate a single quote
(def quote
  {:xt/id (java.util.UUID/randomUUID)
   :asset/ticker "AAPL"
   :option/id "AAPL231215C00185000"
   :option/strike 185.0
   :option/type :call
   :option/expiry #inst "2023-12-15"
   :quote/bid 5.25
   :quote/ask 5.35})

(ingest/validate-quote quote)
;; => {:valid? true
;;     :quote {...}
;;     :errors nil}

;; Validate a batch
(ingest/validate-quotes [quote1 quote2 quote3])
;; => {:valid [quote1 quote2]
;;     :invalid [{:quote quote3 :errors {...}}]
;;     :stats {:total 3 :valid 2 :invalid 1}}
```

### Batch Ingestion

```clojure
(require '[ml-options.db.node :as node])

;; Start XTDB node
(def db-node (node/start-node {:storage-type :memory}))

;; Ingest a batch of quotes
(def quotes
  [(ingest/databento-row->option-quote row1)
   (ingest/databento-row->option-quote row2)])

(ingest/ingest-options-batch! db-node quotes
  {:valid-time #inst "2023-12-15T16:00:00"
   :on-error (fn [errors]
               (println "Validation failed:" errors))})
;; => {:tx-result {...}
;;     :stats {:total 2 :valid 2 :invalid 0}
;;     :errors []}
```

### File Ingestion

```clojure
;; Ingest from an EDN file
(ingest/ingest-from-file! db-node
  "resources/sample-data/databento-sample.edn"
  {:format :databento
   :valid-time #inst "2023-12-15T16:00:00"
   :batch-size 1000
   :on-error (fn [errors]
               (println "Errors:" (count errors)))})
;; => {:total-ingested 10
;;     :stats {:total 10 :valid 10 :invalid 0}
;;     :errors []}

;; Convenience function for Databento files
(ingest/ingest-databento-file! db-node
  "data/options-2023-12-15.edn"
  {:valid-time #inst "2023-12-15T16:00:00"})
```

## Databento Field Mapping

The following table shows how Databento fields map to the internal schema:

| Databento Field | Internal Schema Field | Type | Required |
|-----------------|----------------------|------|----------|
| `symbol` | `option/id` | string | ✓ |
| `ts_event` | (used for valid-time) | timestamp | - |
| `bid_px` or `bid` | `quote/bid` | double | ✓ |
| `ask_px` or `ask` | `quote/ask` | double | ✓ |
| `iv` | `quote/iv` | double | - |
| `delta` | `greeks/delta` | double | - |
| `gamma` | `greeks/gamma` | double | - |
| `vega` | `greeks/vega` | double | - |
| `theta` | `greeks/theta` | double | - |
| `volume` | `market/volume` | int | - |
| `aggressor` | `market/aggressor` | keyword | - |

Additional fields from OCC symbol parsing:
- `asset/ticker` - Extracted from symbol
- `option/strike` - Extracted from symbol
- `option/type` - Extracted from symbol (`:call` or `:put`)
- `option/expiry` - Extracted from symbol

## Bitemporal Ingestion

XTDB v2 supports bitemporality, allowing you to record both:
- **Valid time**: When the fact was true in the real world
- **Transaction time**: When the fact was recorded in the database

This is crucial for the "Oracle" pattern - training ML models with a frozen T0 environment.

```clojure
;; Ingest with explicit valid-time
(ingest/ingest-options-batch! db-node quotes
  {:valid-time #inst "2023-12-15T16:00:00"})

;; Later, query as of that valid-time
(require '[ml-options.db.node :as node])
(def db (node/as-of db-node #inst "2023-12-15T16:00:00"))
(node/query db '{:find [ticker strike]
                 :where [[e :asset/ticker ticker]
                         [e :option/strike strike]]})
```

## Schema Reference

The `OptionQuote` schema (from `ml-options.db.schema`):

```clojure
[:map {:closed true}
 [:xt/id :uuid]                                  ; Required
 [:asset/ticker :string]                         ; Required - "AAPL"
 [:option/id :string]                            ; Required - OCC symbol
 [:option/strike double?]                        ; Required - Strike price
 [:option/type [:enum :call :put]]               ; Required
 [:option/expiry inst?]                          ; Required
 [:quote/bid double?]                            ; Required
 [:quote/ask double?]                            ; Required
 [:quote/iv {:optional true} double?]            ; Optional - Implied volatility
 [:greeks/delta {:optional true} double?]        ; Optional - Delta
 [:greeks/gamma {:optional true} double?]        ; Optional - Gamma
 [:greeks/vega {:optional true} double?]         ; Optional - Vega
 [:greeks/theta {:optional true} double?]        ; Optional - Theta
 [:market/volume {:optional true} :int]          ; Optional - Volume
 [:market/aggressor {:optional true}             ; Optional - Trade side
  [:enum :buy :sell]]]
```

## Error Handling

The ingestion pipeline provides detailed error reporting:

```clojure
(def result
  (ingest/ingest-from-file! db-node "data/bad-data.edn"
    {:on-error (fn [errors]
                 (doseq [err errors]
                   (println "Quote:" (:quote err))
                   (println "Errors:" (:errors err))))}))

;; Check results
(:stats result)
;; => {:total 100 :valid 95 :invalid 5}

(:errors result)
;; => [{:quote {...} :errors {:errors [...]}}]
```

## Performance Considerations

1. **Batch Size**: Default is 1000 records per transaction. Adjust based on your needs:
   ```clojure
   {:batch-size 5000}  ; Larger batches for bulk loading
   {:batch-size 100}   ; Smaller batches for real-time ingestion
   ```

2. **Validation**: Skip validation for trusted data sources:
   ```clojure
   {:skip-validation? true}  ; Use with caution!
   ```

3. **Transaction Await**: Wait for transactions to be indexed:
   ```clojure
   (require '[ml-options.db.node :as node])
   (let [result (ingest/ingest-options-batch! db-node quotes)]
     (node/await-tx db-node (:tx-result result)))
   ```

## Example: Complete Ingestion Pipeline

```clojure
(ns myapp.data-pipeline
  (:require [ml-options.data.ingest :as ingest]
            [ml-options.db.node :as node]
            [tick.core :as t]))

(defn ingest-daily-options
  "Ingest a day's worth of options data."
  [db-node date file-path]
  (let [;; Set valid-time to market close (4:00 PM ET)
        valid-time (-> date
                       (t/at (t/time "16:00"))
                       (t/in (t/zone "America/New_York"))
                       t/inst)
        ;; Ingest with error handling
        result (ingest/ingest-from-file! db-node file-path
                 {:format :databento
                  :valid-time valid-time
                  :batch-size 1000
                  :on-error (fn [errors]
                              (println "Failed to ingest"
                                       (count errors)
                                       "quotes"))})]

    ;; Log results
    (println "Ingested" (:total-ingested result) "quotes")
    (println "Stats:" (:stats result))

    ;; Wait for transaction to complete
    (when-let [tx-result (:tx-result result)]
      (node/await-tx db-node tx-result))

    result))

;; Usage
(def db-node (node/start-node {:storage-type :file
                                :path "data/xtdb"}))

(ingest-daily-options db-node
                      (t/date "2023-12-15")
                      "data/options-2023-12-15.edn")
```

## Testing

Run the test suite:

```bash
clojure -M:test -m kaocha.runner ml-options.data.ingest-test
```

Or in the REPL:

```clojure
(require '[clojure.test :refer [run-tests]])
(require 'ml-options.data.ingest-test)
(run-tests 'ml-options.data.ingest-test)
```

## Future Enhancements

- [ ] CSV ingestion support (requires `clojure.data.csv`)
- [ ] JSON ingestion support (requires `cheshire` or `jsonista`)
- [ ] Streaming ingestion for real-time data
- [ ] Parallel batch processing
- [ ] Data deduplication
- [ ] Schema evolution support
- [ ] Compression for historical data

## Related Documentation

- [XTDB v2 Documentation](https://docs.xtdb.com/v2/)
- [Malli Schema Documentation](https://github.com/metosin/malli)
- [OCC Symbol Specification](https://www.theocc.com/Company-Information/Documents-and-Archives/Options-Symbology-Initiative)
- [Databento API Documentation](https://databento.com/docs)
