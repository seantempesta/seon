# Data Ingestion API Reference

Quick reference for `ml-options.data.ingest` namespace.

## OCC Symbol Functions

### `parse-occ-symbol`

Parse an OCC option symbol into components.

```clojure
(parse-occ-symbol occ-symbol)
```

**Parameters:**
- `occ-symbol` - String in OCC format (e.g., "AAPL231215C00185000")

**Returns:**
- Map with `:ticker`, `:expiry`, `:type`, `:strike`, `:raw-symbol`
- `nil` if invalid

**Example:**
```clojure
(parse-occ-symbol "AAPL231215C00185000")
;; => {:ticker "AAPL"
;;     :expiry #inst "2023-12-15T00:00:00"
;;     :type :call
;;     :strike 185.0
;;     :raw-symbol "AAPL231215C00185000"}
```

---

### `build-occ-symbol`

Build an OCC symbol from components.

```clojure
(build-occ-symbol ticker expiry type strike)
```

**Parameters:**
- `ticker` - String (e.g., "AAPL")
- `expiry` - Inst (java.util.Date)
- `type` - Keyword (`:call` or `:put`)
- `strike` - Double

**Returns:**
- String in OCC format

**Example:**
```clojure
(build-occ-symbol "AAPL" #inst "2023-12-15" :call 185.0)
;; => "AAPL231215C00185000"
```

---

## Transformation Functions

### `databento-row->option-quote`

Transform a Databento row to internal schema.

```clojure
(databento-row->option-quote row)
```

**Parameters:**
- `row` - Map with Databento fields (`:symbol`, `:bid_px`, `:ask_px`, etc.)

**Returns:**
- Map conforming to OptionQuote schema
- `nil` if symbol is invalid

**Example:**
```clojure
(databento-row->option-quote
  {:symbol "AAPL231215C00185000"
   :bid_px 5.25
   :ask_px 5.35
   :iv 0.35
   :delta 0.55
   :volume 1000})
;; => {:xt/id #uuid "..."
;;     :asset/ticker "AAPL"
;;     :option/id "AAPL231215C00185000"
;;     :option/strike 185.0
;;     :option/type :call
;;     :option/expiry #inst "2023-12-15"
;;     :quote/bid 5.25
;;     :quote/ask 5.35
;;     :quote/iv 0.35
;;     :greeks/delta 0.55
;;     :market/volume 1000}
```

---

### `csv-row->option-quote`

Transform a CSV row to internal schema.

```clojure
(csv-row->option-quote row)
```

**Parameters:**
- `row` - Map with string keys from CSV headers

**Returns:**
- Map conforming to OptionQuote schema
- `nil` if symbol is invalid

**Example:**
```clojure
(csv-row->option-quote
  {"symbol" "AAPL231215C00185000"
   "bid" "5.25"
   "ask" "5.35"
   "iv" "0.35"})
```

---

## Validation Functions

### `validate-quote`

Validate a single quote against the schema.

```clojure
(validate-quote quote)
```

**Parameters:**
- `quote` - Quote map to validate

**Returns:**
```clojure
{:valid? boolean
 :quote map-or-nil
 :errors explanation-or-nil}
```

**Example:**
```clojure
(validate-quote
  {:xt/id (java.util.UUID/randomUUID)
   :asset/ticker "AAPL"
   :option/id "AAPL231215C00185000"
   :option/strike 185.0
   :option/type :call
   :option/expiry #inst "2023-12-15"
   :quote/bid 5.25
   :quote/ask 5.35})
;; => {:valid? true :quote {...} :errors nil}
```

---

### `validate-quotes`

Validate a batch of quotes.

```clojure
(validate-quotes quotes)
```

**Parameters:**
- `quotes` - Sequence of quote maps

**Returns:**
```clojure
{:valid [valid-quotes]
 :invalid [{:quote map :errors explanation}]
 :stats {:total int :valid int :invalid int}}
```

**Example:**
```clojure
(validate-quotes [quote1 quote2 quote3])
;; => {:valid [quote1 quote2]
;;     :invalid [{:quote quote3 :errors {...}}]
;;     :stats {:total 3 :valid 2 :invalid 1}}
```

---

## Ingestion Functions

### `ingest-options-batch!`

Ingest a batch of option quotes into XTDB.

```clojure
(ingest-options-batch! node quotes)
(ingest-options-batch! node quotes opts)
```

**Parameters:**
- `node` - XTDB node
- `quotes` - Sequence of option quote maps
- `opts` - (Optional) Options map:
  - `:valid-time` - Inst for bitemporal valid-time
  - `:skip-validation?` - Boolean (default: false)
  - `:on-error` - Function called with validation errors

**Returns:**
```clojure
{:tx-result xtdb-tx-result
 :stats {:total int :valid int :invalid int}
 :errors [validation-errors]}
```

**Example:**
```clojure
(ingest-options-batch! db-node quotes
  {:valid-time #inst "2023-12-15T16:00:00"
   :on-error (fn [errors]
               (println "Failed:" (count errors)))})
```

---

### `ingest-from-file!`

Load options data from a file and ingest into XTDB.

```clojure
(ingest-from-file! node file-path)
(ingest-from-file! node file-path opts)
```

**Parameters:**
- `node` - XTDB node
- `file-path` - Path to data file
- `opts` - (Optional) Options map:
  - `:format` - `:edn`, `:json`, or `:databento` (auto-detected from extension)
  - `:transformer` - Custom transformer function
  - `:valid-time` - Inst for bitemporal valid-time
  - `:batch-size` - Number of records per transaction (default: 1000)
  - `:on-error` - Function called with validation errors

**Returns:**
```clojure
{:total-ingested int
 :stats {:total int :valid int :invalid int}
 :errors [all-validation-errors]}
```

**Example:**
```clojure
(ingest-from-file! db-node
  "resources/sample-data/databento-sample.edn"
  {:format :databento
   :valid-time #inst "2023-12-15T16:00:00"
   :batch-size 1000
   :on-error (fn [errors]
               (println "Errors:" (count errors)))})
;; => {:total-ingested 10
;;     :stats {:total 10 :valid 10 :invalid 0}
;;     :errors []}
```

---

### `ingest-databento-file!`

Convenience function for ingesting Databento files.

```clojure
(ingest-databento-file! node file-path opts)
```

**Parameters:**
- Same as `ingest-from-file!`
- Automatically uses `databento-row->option-quote` transformer

**Example:**
```clojure
(ingest-databento-file! db-node
  "data/options-2023-12-15.edn"
  {:valid-time #inst "2023-12-15T16:00:00"})
```

---

### `ingest-csv-file!`

Convenience function for ingesting CSV files.

```clojure
(ingest-csv-file! node file-path opts)
```

**Status:** Not yet implemented. Requires `clojure.data.csv` dependency.

---

## Data Structures

### Databento Row Format

```clojure
{:symbol "AAPL231215C00185000"    ; OCC symbol (required)
 :ts_event 1702656000000          ; Timestamp (optional)
 :bid_px 5.25                     ; Bid price (required)
 :ask_px 5.35                     ; Ask price (required)
 :bid_sz 100                      ; Bid size (optional)
 :ask_sz 150                      ; Ask size (optional)
 :volume 1250                     ; Volume (optional)
 :iv 0.35                         ; Implied volatility (optional)
 :delta 0.55                      ; Delta (optional)
 :gamma 0.05                      ; Gamma (optional)
 :vega 15.2                       ; Vega (optional)
 :theta -0.15                     ; Theta (optional)
 :aggressor "buy"}                ; Aggressor side (optional)
```

### OptionQuote Schema

```clojure
{:xt/id #uuid "..."                ; UUID (auto-generated if not provided)
 :asset/ticker "AAPL"              ; Stock ticker (required)
 :option/id "AAPL231215C00185000"  ; OCC symbol (required)
 :option/strike 185.0              ; Strike price (required)
 :option/type :call                ; :call or :put (required)
 :option/expiry #inst "2023-12-15" ; Expiration date (required)
 :quote/bid 5.25                   ; Bid price (required)
 :quote/ask 5.35                   ; Ask price (required)
 :quote/iv 0.35                    ; Implied volatility (optional)
 :greeks/delta 0.55                ; Delta (optional)
 :greeks/gamma 0.05                ; Gamma (optional)
 :greeks/vega 15.2                 ; Vega (optional)
 :greeks/theta -0.15               ; Theta (optional)
 :market/volume 1000               ; Volume (optional)
 :market/aggressor :buy}           ; :buy or :sell (optional)
```

---

## Common Workflows

### 1. Parse and Validate Symbol

```clojure
(when-let [parsed (parse-occ-symbol "AAPL231215C00185000")]
  (println "Ticker:" (:ticker parsed))
  (println "Strike:" (:strike parsed))
  (println "Type:" (:type parsed)))
```

### 2. Transform and Ingest Single Row

```clojure
(let [quote (databento-row->option-quote databento-row)]
  (when quote
    (ingest-options-batch! db-node [quote]
      {:valid-time #inst "2023-12-15T16:00:00"})))
```

### 3. Batch Transform and Ingest

```clojure
(let [quotes (keep databento-row->option-quote databento-rows)]
  (ingest-options-batch! db-node quotes
    {:valid-time #inst "2023-12-15T16:00:00"
     :on-error (fn [errors]
                 (doseq [err errors]
                   (println "Error:" (:errors err))))}))
```

### 4. File Ingestion with Validation

```clojure
(let [result (ingest-from-file! db-node "data/options.edn"
               {:format :databento
                :valid-time #inst "2023-12-15T16:00:00"
                :batch-size 1000})]
  (println "Ingested:" (:total-ingested result))
  (println "Failed:" (count (:errors result))))
```

### 5. Custom Transformer

```clojure
(defn my-transformer [row]
  (-> row
      (assoc :symbol (str (:ticker row) (:suffix row)))
      databento-row->option-quote))

(ingest-from-file! db-node "data/custom.edn"
  {:transformer my-transformer})
```

---

## Error Handling Patterns

### 1. Log All Errors

```clojure
{:on-error (fn [errors]
             (doseq [err errors]
               (println "Quote:" (:quote err))
               (println "Errors:" (:errors err))))}
```

### 2. Count Errors

```clojure
{:on-error (fn [errors]
             (println "Total errors:" (count errors)))}
```

### 3. Store Errors for Later Analysis

```clojure
(def error-log (atom []))

{:on-error (fn [errors]
             (swap! error-log into errors))}
```

### 4. Fail Fast on Errors

```clojure
{:on-error (fn [errors]
             (throw (ex-info "Validation failed"
                             {:errors errors})))}
```

---

## Performance Tips

1. **Adjust batch size** based on data volume:
   ```clojure
   {:batch-size 5000}  ; Larger for historical data
   {:batch-size 100}   ; Smaller for real-time
   ```

2. **Skip validation** for trusted sources (use with caution):
   ```clojure
   {:skip-validation? true}
   ```

3. **Wait for transactions** when sequencing operations:
   ```clojure
   (require '[ml-options.db.node :as node])
   (let [result (ingest-options-batch! db-node quotes)]
     (node/await-tx db-node (:tx-result result)))
   ```

4. **Use custom transformers** to avoid double-processing:
   ```clojure
   {:transformer (comp add-custom-fields databento-row->option-quote)}
   ```

---

## See Also

- [Full Documentation](data-ingestion.md)
- [Implementation Summary](INGESTION_SUMMARY.md)
- [Schema Reference](../src/ml_options/db/schema.clj)
- [Transaction Helpers](../src/ml_options/db/transactions.clj)
