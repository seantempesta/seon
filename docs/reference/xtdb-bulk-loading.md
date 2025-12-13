# XTDB Bulk Loading and Data Export/Import Guide

**Version**: 2.1.0-rc0 | **Last Updated**: 2025-11-29

---

## Overview

This guide covers two primary use cases:
1. **Export/Import**: Moving existing XTDB data between databases (e.g., testing compaction fixes)
2. **Bulk Loading**: Ingesting large datasets from external sources

XTDB v2 uses an LSM (Log-Structured Merge) tree architecture with multi-level compaction. For datasets over 1M records, proper strategy is critical to avoid compaction issues.

---

## Export/Import Existing Data

### Method 1: export-snapshot (XTDB v2.1+ Native - For Backup/Restore Only)

The `export-snapshot` function creates a consistent point-in-time snapshot by copying:
- Block metadata files
- Table block metadata
- Active trie files (L0, L1, L2, etc.)

**Important Limitation:** `export-snapshot` is designed for backup/restore of a complete XTDB system, not for creating a standalone queryable database. The exported files reference specific blocks and transaction IDs from the original database.

**Use Cases:**
- ✓ Backup before upgrades
- ✓ Disaster recovery
- ✓ Migrating entire database to new hardware
- ✗ Testing compaction in isolation (use file-level copy instead)
- ✗ Creating test databases from production (snapshot isn't independently queryable)

**Advantages:**
- Fast (file copy, not query-based)
- Transaction-consistent
- Preserves compaction state

**Disadvantages:**
- Exported data is NOT immediately queryable without original transaction log
- Designed for full system restore, not data extraction

#### Export Process (For Backup)

```clojure
(require '[xtdb.export :as export])
(require '[xtdb.api :as xt])

;; Export from running node
(def export-result
  (export/export-snapshot!
    {:log [:local {:path "data/xtdb/log"}]
     :storage [:local {:path "data/xtdb/objects"}]}
    "xtdb"  ; database name
    {:dry-run? false}))

;; Result:
;; {:block-idx 123
;;  :export-time #inst "2025-11-29T..."
;;  :export-dir #<Path data/xtdb/objects/.../exports/b...>
;;  :tables 2
;;  :file-count 42
;;  :dry-run? false}

;; Export directory structure:
;; data/xtdb/objects/.../exports/b123_20251129T123456Z/
;;   version_0/
;;     blocks/       - Block catalog
;;     tables/       - Table metadata
;;       option-greeks/
;;         l0/        - Level 0 files
;;         l1/        - Level 1 files
;;         l2/        - Level 2 files
```

#### Import Process

```clojure
(require '[xtdb.node :as xtn])

;; Start new node pointing to exported snapshot
(def import-node
  (xtn/start-node
    {:log [:in-memory {:epoch 1}]  ; New transaction log
     :storage [:local {:path "data/xtdb/objects/.../exports/b123_20251129T123456Z"}]
     :compactor {:threads 4}}))

;; Verify data loaded
(require '[ml-options.db.node :as node])

(node/query import-node
  '(-> (from :option-greeks [xt/id])
       (aggregate {:count (count xt/id)})))
;; => [{:count 1989270}]

;; All temporal history is preserved
(node/query import-node
  '(-> (from :option-greeks {:for-valid-time :all-time}
             [xt/id xt/valid-from xt/valid-to])
       (limit 5)))
```

### Method 2: Query-Based Export/Import (Works with v2.0 and v2.1)

For full control or when `export-snapshot` isn't available:

#### Export All Data

```clojure
(require '[xtdb.api :as xt])
(require '[clojure.java.io :as io])
(require '[clojure.edn :as edn])

;; Export ALL temporal data from option-greeks
(defn export-all-records
  [node table output-file]
  (let [records (xt/q node
                  (format "SELECT * FROM %s FOR ALL VALID_TIME FOR ALL SYSTEM_TIME"
                          (name table)))]
    (with-open [w (io/writer output-file)]
      (binding [*out* w]
        (prn {:table table
              :count (count records)
              :exported-at (java.time.Instant/now)
              :records records})))
    (count records)))

;; Usage
(def record-count
  (export-all-records node
                      :option-greeks
                      "data/option-greeks-export.edn"))
;; Exports ~2M records with full temporal history
```

**Important:** This exports ALL versions of each record (system-time + valid-time).

#### Import from EDN Export

```clojure
(defn import-from-edn
  [node edn-file]
  (with-open [r (java.io.PushbackReader. (io/reader edn-file))]
    (let [data (edn/read r)
          records (:records data)
          batch-size 1000]
      (doseq [batch (partition-all batch-size records)]
        ;; Reconstruct documents with temporal metadata
        (let [docs (mapv #(hash-map
                           :xt/id (:xt/id %)
                           :xt/valid-from (:xt/valid-from %)
                           :xt/valid-to (:xt/valid-to %)
                           ;; ... rest of fields
                           ) batch)]
          (xt/execute-tx node
            [[:put-docs {:into (:table data)} docs]])))
      (count records))))
```

**Limitations:**
- Slower than `export-snapshot` (query + re-insert)
- May not preserve system-time exactly
- Triggers new compaction cycles

### Method 3: File-Level Copy (RECOMMENDED for Testing Compaction)

For testing compaction fixes or different configurations on existing data:

```bash
# 1. Stop the node first!
# (Close in REPL or kill process)

# 2. Create timestamped backup
DATE=$(date +%Y%m%d-%H%M%S)
cp -r data/xtdb "data/xtdb.backup.$DATE"

# 3. Verify backup
du -sh data/xtdb*
# data/xtdb/               15G
# data/xtdb.backup.20251129-120000/   15G

# 4. Start node with DIFFERENT compaction settings
# In REPL:
(def test-node
  (xtn/start-node
    {:log [:local {:path "data/xtdb/log"}]
     :storage [:local {:path "data/xtdb/objects"}]
     :compactor {:threads 8}}))  ; Try different thread count

# 5. Monitor compaction
# tail -f logs/xtdb.log | grep compaction

# 6. Restore if needed
# rm -rf data/xtdb
# mv data/xtdb.backup.20251129-120000 data/xtdb
```

**Advantages:**
- Simple and reliable
- Fast (file system copy)
- Immediately queryable
- Can test different compaction configurations
- Full temporal history preserved

**Disadvantages:**
- Node must be stopped for consistent backup
- Disk space intensive (full copy)

**Use Cases:**
- ✓ Testing compaction bug fixes
- ✓ Testing different compaction thread counts
- ✓ Before/after performance comparisons
- ✓ Safe experimentation with production data

---

## Testing Compaction with Existing Data

**Scenario:** Test XTDB v2.1.0-rc0 compaction fix on existing 2M records without re-fetching from ThetaData.

### RECOMMENDED: File-Level Copy + Different Compaction Settings

```bash
# 1. Stop any running XTDB nodes
# (Close in REPL: (.close node))

# 2. Create backup
cp -r data/xtdb data/xtdb.v2.1.0-rc0.compaction-test.$(date +%Y%m%d)

# 3. Verify backup size (should match original)
du -sh data/xtdb data/xtdb.v2.1.0-rc0.compaction-test.*
```

```clojure
;; 4. Start node with different compaction configuration
(require '[xtdb.node :as xtn])

(def test-node
  (xtn/start-node
    {:log [:local {:path "data/xtdb/log"}]
     :storage [:local {:path "data/xtdb/objects"}]
     :compactor {:threads 8}}))  ; Test with 8 threads (was 2)

;; 5. Monitor compaction in separate terminal
;; tail -f logs/xtdb.log | grep -E "compaction|compacted|error"

;; 6. Verify data is accessible immediately
(require '[ml-options.db.node :as node])

(node/query test-node
  '(-> (from :option-greeks [xt/id])
       (aggregate {:count (count xt/id)})))
;; => [{:count 1989270}]  (should be immediate)
```

**Why this works:**
- Existing data is immediately queryable
- Transaction log is preserved
- Can test different compaction thread counts
- No data loss risk (have backup)
- Tests real compaction workload

### Option B: Trigger Full Re-Compaction (Tests Compaction Fix)

```clojure
(require '[xtdb.compactor :as c])

;; 1. Reset compaction to L0 (XTDB v2.1+ only)
;; From shell (node STOPPED):
;; xtdb reset-compactor xtdb --dry-run  # Preview
;; xtdb reset-compactor xtdb             # Actually reset

;; 2. Start node with aggressive compaction
(def test-node
  (xtn/start-node
    {:log [:local {:path "data/xtdb/log"}]
     :storage [:local {:path "data/xtdb/objects"}]
     :compactor {:threads 8}}))

;; 3. All data is now in L0, will be recompacted
;; Monitor: tail -f logs/xtdb.log | grep "compacted"

;; 4. Force compaction (optional - auto-triggered by default)
(c/compact-all! test-node #xt/duration "PT10M")
```

**This tests:**
- v2.1.0-rc0 compaction bug fixes
- Different compaction thread counts
- Memory usage under heavy compaction

---

## Verifying Data Integrity After Import/Export

```clojure
;; 1. Record count (should match exactly)
(node/query node
  '(-> (from :option-greeks [xt/id])
       (aggregate {:count (count xt/id)})))
;; Original: 1,989,270
;; After import: should be 1,989,270

;; 2. No duplicates
(node/query node
  '(-> (from :option-greeks [xt/id])
       (aggregate {:cnt (count xt/id)} xt/id)
       (where (> cnt 1))))
;; => [] (empty)

;; 3. Temporal boundaries preserved
(node/query node
  '(-> (from :option-greeks [quote/timestamp])
       (aggregate {:min (min quote/timestamp)
                   :max (max quote/timestamp)})))
;; Should match original: 2025-05-28 to 2025-11-27

;; 4. Sample random records (compare checksums or values)
(node/query node
  '(-> (from :option-greeks [xt/id asset/ticker greeks/delta quote/iv])
       (where (= xt/id "AAPL241220C00150000_20251127T220000Z"))
       (limit 1)))
;; Verify values match original

;; 5. Temporal queries still work
(node/query node
  '(-> (from :option-greeks [xt/id])
       (aggregate {:count (count xt/id)}))
  {:current-time #inst "2025-10-01T00:00:00Z"})
;; Should return count as of October 1st
```

---

## Bulk Loading from External Sources

See `src/ml_options/data/bulk_load.clj` for reference implementation.

### Key Principles

1. **Batch Size**: 500-1000 docs per transaction
2. **Parallelism**: 4-8 concurrent API fetches
3. **Compaction During Load**:
   - Disable (threads=0) for initial bulk load
   - Enable moderate (threads=4) for incremental loads
4. **Memory**: `MaxDirectMemorySize >= heap_size / 2`
5. **Monitoring**: Watch logs for compaction errors

### Example

```clojure
(require '[ml-options.data.bulk-load :as bulk])
(import '[java.time LocalDate])

;; Start node with compaction disabled
(def node
  (xtn/start-node
    {:log [:local {:path "data/xtdb/log"}]
     :storage [:local {:path "data/xtdb/objects"}]
     :compactor {:threads 0}}))  ; Disable during load

;; Run bulk load
(def result
  (bulk/resilient-bulk-load!
    node
    ["SPY" "AAPL"]
    (LocalDate/of 2025 5 28)
    (LocalDate/of 2025 11 27)))

;; Enable compaction after load
(.close node)
(def node
  (xtn/start-node
    {:log [:local {:path "data/xtdb/log"}]
     :storage [:local {:path "data/xtdb/objects"}]
     :compactor {:threads 8}}))  ; Aggressive compaction
```

---

## Performance Monitoring

### Compaction Logs

```bash
tail -f logs/xtdb.log | grep -E "compaction|compacted|error"

# Expected output:
# 10:15:23.456 [compactor-1] INFO xtdb.compactor - compacted 'option-greeks' l01-rc-abc123 -> l1-rc-def456 (12345 rows, 234ms)
```

### Prometheus Metrics

```bash
curl http://localhost:8080/metrics | grep compactor

# Key metrics:
# compactor_job_timer_seconds_count - Total compaction jobs
# compactor_job_timer_seconds_sum - Total time in compaction
# compactor_jobs_available - Queue depth (should be 0-2)
```

### JVM Memory

```bash
# Find process
jps | grep clojure

# Heap usage
jmap -heap <pid> | grep -A 10 "Heap Configuration"

# GC activity
jstat -gcutil <pid> 1000

# Expected:
# S0     S1     E      O      M     CCS    YGC     YGCT    FGC    FGCT
# 0.00  95.23  62.45  45.67  96.12  95.34   123    1.234     5   0.567
```

---

## Troubleshooting

### Issue: NoSuchElementException During Compaction

```
ERROR: error running compaction job: public/option_greeks/l01-rc-b12c
java.util.NoSuchElementException at xtdb.arrow.FixedWidthVector.loadPage
```

**Fixed in v2.1.0-rc0**. If still occurs:
1. Increase heap: `-Xmx32g`
2. Increase direct memory: `-XX:MaxDirectMemorySize=16g`
3. Reduce compactor threads: `{:threads 2}`
4. Emergency: `xtdb reset-compactor xtdb` (resets to L0)

### Issue: Data Count Mismatch After Import

```clojure
;; Original: 1,989,270
;; Imported: 1,500,000 (WRONG!)
```

**Causes:**
1. Didn't query `FOR ALL VALID_TIME` during export
2. Partial transaction during export
3. Corrupted export file

**Solution:** Re-export with proper temporal query or use `export-snapshot`.

---

## Complete Export/Import Test Script

```clojure
(ns test.export-import
  (:require [xtdb.export :as export]
            [xtdb.node :as xtn]
            [ml-options.db.node :as node]
            [clojure.java.io :as io]))

(defn test-export-import
  "Test export/import preserves all data correctly."
  []
  ;; 1. Export snapshot
  (println "Exporting snapshot...")
  (def export-result
    (export/export-snapshot!
      {:log [:local {:path "data/xtdb/log"}]
       :storage [:local {:path "data/xtdb/objects"}]}
      "xtdb"))

  (println "Exported:" (:file-count export-result) "files")
  (println "Export dir:" (:export-dir export-result))

  ;; 2. Create test database directory
  (println "\\nCreating test import database...")
  (.mkdirs (io/file "data/xtdb-import-test/log"))
  (.mkdirs (io/file "data/xtdb-import-test/objects"))

  ;; 3. Copy export to test location
  (println "Copying export to test location...")
  (let [src (:export-dir export-result)
        dest (io/file "data/xtdb-import-test/objects")]
    (.copyDirectory (org.apache.commons.io.FileUtils.)
                    (.toFile src)
                    (io/file dest "snapshot")))

  ;; 4. Start node pointing to imported data
  (println "\\nStarting import node...")
  (def import-node
    (xtn/start-node
      {:log [:in-memory {:epoch 1}]
       :storage [:local {:path "data/xtdb-import-test/objects/snapshot"}]
       :compactor {:threads 4}}))

  ;; 5. Verify data
  (println "\\nVerifying data integrity...")

  (let [count-result (node/query import-node
                       '(-> (from :option-greeks [xt/id])
                            (aggregate {:count (count xt/id)})))
        actual-count (:count (first count-result))
        expected-count 1989270]

    (println "Record count:" actual-count)
    (assert (= expected-count actual-count)
            (str "Count mismatch! Expected " expected-count " got " actual-count)))

  (let [dups (node/query import-node
               '(-> (from :option-greeks [xt/id])
                    (aggregate {:cnt (count xt/id)} xt/id)
                    (where (> cnt 1))))]
    (println "Duplicates:" (count dups))
    (assert (empty? dups) "Found duplicate IDs!"))

  (let [sample (node/query import-node
                 '(-> (from :option-greeks [xt/id asset/ticker greeks/delta])
                      (where (= asset/ticker "AAPL"))
                      (limit 5)))]
    (println "Sample AAPL records:" (count sample))
    (assert (= 5 (count sample)) "Failed to query sample data"))

  (println "\\n✓ All verification checks passed!")

  ;; 6. Cleanup
  (.close import-node)

  {:success true
   :export export-result
   :verified true})

;; Run the test
(comment
  (test-export-import))
```

---

## References

- [XTDB v2.1 Configuration](../reference-code/xtdb/docs/src/content/docs/ops/config.md)
- [XTDB Export Source](../reference-code/xtdb/core/src/main/clojure/xtdb/export.clj)
- [PRD: XTDB Production Config](PRD-xtdb-production-config.md)
