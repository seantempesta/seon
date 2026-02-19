> **Status: ARCHIVED** — XTDB ops config, not core infra

> **Status: ARCHIVED** — XTDB ops config, not core infra

# PRD: XTDB v2.1.0-rc0 Production Configuration for Large Workloads

**Status**: Ready for Implementation
**Version**: 2.0
**Last Updated**: 2025-11-29
**Target XTDB Version**: 2.1.0-rc0 (recommended) or 2.0.0 (with workarounds)

---

## BREAKING CHANGES in v2.1.0-rc0

Before proceeding, note these **breaking changes** from v2.0.0:

| Change | v2.0.0 | v2.1.0-rc0 |
|--------|--------|------------|
| Cache config | Nested under storage | **Top-level** `:memory-cache`, `:disk-cache` |
| CLI flags | `--playground-port`, `--compact-only` | **Removed** - use subcommands |
| HTTP server | Deprecated | **Removed entirely** |
| CLI structure | Flags | **Git-style subcommands**: `node`, `playground`, `compactor` |

### New CLI Commands (v2.1.0-rc0)
```bash
# Start node (was: java -jar xtdb.jar)
xtdb node -f config.yaml

# Start playground
xtdb playground --port 3000

# Dedicated compactor (NEW)
xtdb compactor -f config.yaml

# Reset compaction to L0 (NEW - emergency recovery)
xtdb reset-compactor <db-name>

# Export snapshot for DR (NEW)
xtdb export-snapshot <db-name> -f config.yaml
```

---

## Executive Summary

This document provides a comprehensive configuration guide for running XTDB v2 with large datasets (10M+ records). We address the `NoSuchElementException` in `FixedWidthVector.loadPage` that occurred during bulk ingestion of 7.6M option records, analyze root causes, and provide production-ready configuration recommendations.

### Problem Statement

During bulk ingestion of 7.6M option records into the `option_greeks` table:

```
SEVERE: error running compaction job: public/option_greeks/l01-rc-b12c
java.util.NoSuchElementException
  at java.base/java.util.ArrayList.removeFirst(ArrayList.java:569)
  at xtdb.arrow.FixedWidthVector.loadPage$xtdb_core(FixedWidthVector.kt:132)
  at xtdb.compactor.SegmentMerge.merge(SegmentMerge.kt:149)
```

**Error Location**: `FixedWidthVector.kt:132` calling `ArrayList.removeFirst()` with empty list  
**Context**: Compaction of Arrow-formatted data during merge operations  
**Timing**: After ~2M records; recurring with subsequent bulk loads  
**Thread**: `DefaultDispatcher-worker-8` (compactor thread pool)

---

## Root Cause Analysis

### 1. The Immediate Error

In `FixedWidthVector.loadPage()` (line 131-137):

```kotlin
final override fun loadPage(nodes: MutableList<ArrowFieldNode>, buffers: MutableList<ArrowBuf>) {
    val node = nodes.removeFirst() ?: throw IllegalStateException("missing node")
    validityBuffer.loadBuffer(buffers.removeFirst() ?: throw IllegalStateException("missing validity buffer"))
    dataBuffer.loadBuffer(buffers.removeFirst() ?: throw IllegalStateException("missing data buffer"))
    
    valueCount = node.length
}
```

**Root Cause**: The `nodes` or `buffers` list is being exhausted during segment merge operations. This occurs when:
- Arrow metadata is malformed or incomplete
- Buffer pages aren't properly aligned during compaction
- Memory pressure causes incomplete page writes
- Concurrency issues in the merge queue

### 2. Why This Happens Under Load

XTDB v2 uses LSM (Log-Structured Merge) tree compaction:

1. **Transaction log**: Incoming data written to Level 0 (L0)
2. **Compaction cycle**: L0 files merged into L1, L1 into L2, etc.
3. **Arrow serialization**: Each level uses Apache Arrow columnar format
4. **Memory management**: Segments merged in-memory using `BufferAllocator` (Arrow's memory pool)

**Why large datasets trigger this**:
- **Heap pressure**: Default JVM heap may be insufficient for 7.6M records + intermediate merge buffers
- **Direct memory exhaustion**: Arrow allocates off-heap memory; insufficient `MaxDirectMemorySize` forces spillover
- **Incomplete buffer allocation**: Under memory pressure, Arrow may return partial buffers
- **Concurrency**: Default compactor threads compete for memory; poor allocation ordering causes list misalignment

### 3. XTDB v2.0.0 Known Issues

Based on XTDB GitHub issues:
- **Issue #4231**: "IAE in xtdb.compactor" - Arrow vector operations fail during merge
- **Arrow compatibility**: Earlier v2.0.0 releases had edge cases with `DenseUnionVector` and `ListVector` row copying
- **Recommended**: Upgrade to **v2.1.0+** (late 2024) which includes compaction fixes

---

## Recommended Configuration

### Configuration Strategy

The following configuration addresses the three layers:

1. **JVM Memory**: Sufficient heap and direct memory for compaction
2. **Arrow Configuration**: Proper memory allocation and pooling
3. **Compactor Tuning**: Thread count, queue limits, and timeout settings
4. **Monitoring**: Visibility into memory usage and compaction progress

### A. JVM Configuration

#### Heap Size

**Formula**: `heap = base_data_size * 3 + 2GB`

For your workload (7.6M records ≈ 1-2GB raw data):

```bash
# Minimum (development/testing)
-Xms4g -Xmx8g

# Recommended for 7.6M+ records
-Xms16g -Xmx32g

# For 100M+ records
-Xms64g -Xmx128g
```

**Rationale**:
- **1x data size**: L0 level
- **1x data size**: Intermediate merge buffers
- **1x data size**: Query result caches + overflow
- **+2GB**: JVM overhead, string interning, class metadata

#### Direct Memory Size

Arrow allocates off-heap memory for columnar data. Configure via:

```bash
-XX:MaxDirectMemorySize=16g
```

**Formula**: `MaxDirectMemorySize >= heap_size / 2`

For a 32GB heap: `MaxDirectMemorySize=16g`

#### GC Tuning

Use G1GC (default in JDK 21+) with tuning for compaction:

```bash
# JDK 21+ (preferred)
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:InitiatingHeapOccupancyPercent=35

# JDK 17
-XX:+UseG1GC
-XX:+ParallelRefProcEnabled
-XX:MaxGCPauseMillis=200
```

#### Full JVM Example

```bash
# For prod with 10M+ records
java \
  -Xms16g -Xmx32g \
  -XX:MaxDirectMemorySize=16g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:InitiatingHeapOccupancyPercent=35 \
  -XX:+ParallelRefProcEnabled \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -Dio.netty.tryReflectionSetAccessible=true \
  --enable-native-access=ALL-UNNAMED \
  -cp "..." \
  clojure.main -m nrepl.cmdline
```

### B. XTDB Configuration (v2.1.0-rc0)

Update `src/ml_options/system.clj` to handle new v2.1.0-rc0 config structure:

```clojure
(defmethod ig/init-key :ml-options/xtdb-node
  [_ {:keys [storage memory-cache disk-cache compactor]}]
  (log/info "Starting XTDB node..." {:storage storage :compactor compactor})
  (require '[xtdb.node :as xtn])
  (require '[xtdb.api :as xt])
  (let [start-node (resolve 'xtn/start-node)
        base-path (if (map? storage) (:path storage) (str storage))
        config {:log [:local {:path (io/file base-path "log")}]
                :storage [:local {:path (io/file base-path "objects")}]}
        ;; v2.1.0-rc0: Cache config is now top-level (breaking change)
        config (cond-> config
                 memory-cache (assoc :memory-cache memory-cache)
                 disk-cache (assoc :disk-cache disk-cache)
                 compactor (assoc :compactor compactor))
        node (start-node config)]
    (log/info "XTDB node started" {:compactor compactor})
    node))
```

Update `resources/system.edn` with v2.1.0-rc0 structure:

```edn
{;; XTDB v2.1.0-rc0 Node
 :ml-options/xtdb-node
 {:storage #profile {:dev   {:type :local :path "data/xtdb"}
                     :test  :in-memory
                     :prod  {:type :local :path #or [#env XTDB_DATA_PATH "data/xtdb"]}}

  ;; v2.1.0-rc0: Cache config moved to top-level (BREAKING CHANGE from v2.0)
  :memory-cache #profile {:dev  {}
                          :test {}
                          :prod {:max-size-ratio 0.5}}

  :disk-cache #profile {:dev  nil
                        :test nil
                        :prod {:path "/var/lib/xtdb/remote-cache"}}

  :compactor #profile {:dev  {:threads 2}
                       :test {:threads 1}
                       :prod {:threads #or [#env XTDB_COMPACTOR_THREADS 8]}}}}
```

### C. Compactor Configuration Details

#### Threads

**Default**: `min(available_processors / 2, 1)` (conservative)

**Recommended**:

```yaml
# For single-node development
threads: 2

# For multi-core production (16 cores)
threads: 8

# For dedicated compaction nodes
threads: 12
```

**Guideline**: `threads = available_processors / 2` (leave headroom for query/ingest threads)

**Environment variable**: `XTDB_COMPACTOR_THREADS=8`

#### File Size Target

Control L0→L1 compaction thresholds (defaults to 128MB):

```clojure
;; Configure in start-node config (requires v2.1+)
{:storage [:local {:path ...}]
 :compactor {:threads 8
             :file-size-target 268435456}} ;; 256MB
```

**Rationale for larger file sizes during ingestion**:
- Reduces compaction frequency during bulk load
- Fewer merge operations = less memory churn
- After ingestion, can compact aggressively

### D. Bulk Ingestion Configuration

Disable or limit compaction during bulk ingestion:

```clojure
;; Before bulk load - reduce compactor aggressiveness
(require '[xtdb.node :as xtn])

;; Option 1: Start compactor-less node (v2.1+)
(def node (xtn/start-node
  {:log [:local {:path "data/xtdb/log"}]
   :storage [:local {:path "data/xtdb/objects"}]
   :compactor {:threads 0}})) ;; Disable

;; Do bulk ingestion...

;; Option 2: Let it compact, but with optimal settings
(def node (xtn/start-node
  {:log [:local {:path "data/xtdb/log"}]
   :storage [:local {:path "data/xtdb/objects"}]
   :compactor {:threads 4
               :file-size-target 536870912}})) ;; 512MB files
```

### E. Monitoring Configuration

Enable detailed logging for compaction visibility:

```bash
# Environment variables for logging
export XTDB_LOGGING_LEVEL=DEBUG
export XTDB_LOGGING_LEVEL_COMPACTOR=DEBUG
export XTDB_LOGGING_LEVEL_INDEXER=INFO
```

Configure `logback.xml` for file output:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/xtdb.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
      <fileNamePattern>logs/xtdb.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
      <maxFileSize>500MB</maxFileSize>
      <maxHistory>30</maxHistory>
    </rollingPolicy>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <logger name="xtdb" level="DEBUG"/>
  <logger name="xtdb.compactor" level="DEBUG"/>
  
  <root level="INFO">
    <appender-ref ref="FILE"/>
  </root>
</configuration>
```

---

## Version Upgrade: v2.0.0 → v2.1.0-rc0

### Why Upgrade to v2.1.0-rc0

The `NoSuchElementException` in `FixedWidthVector.loadPage` is **directly fixed** in v2.1.0-rc0:

1. **SegmentMerge improvements**: Better buffer validation before `removeFirst()` calls
2. **Arrow memory handling**: Enhanced allocator tracking and error recovery
3. **New recovery tools**: `reset-compactor` command for emergency recovery without data loss
4. **Concurrency fixes**: Deterministic testing caught and fixed merge queue issues

### v2.1.0-rc0 Release Highlights

From [GitHub Release Notes](https://github.com/xtdb/xtdb/releases/tag/v2.1.0-rc0):

- **Multi-database support** (future-proofing)
- **Dedicated compactor nodes** (`xtdb compactor` command)
- **Emergency recovery** (`xtdb reset-compactor`)
- **Snapshot export** (`xtdb export-snapshot`)
- **Storage format improvements** (more efficient compaction)

### deps.edn Update

```clojure
;; Old (v2.0.0)
com.xtdb/xtdb-api {:mvn/version "2.0.0"}
com.xtdb/xtdb-core {:mvn/version "2.0.0"}

;; New (v2.1.0-rc0)
com.xtdb/xtdb-api {:mvn/version "2.1.0-rc0"}
com.xtdb/xtdb-core {:mvn/version "2.1.0-rc0"}
```

### Migration Steps

1. **Backup data**: `cp -r data/xtdb data/xtdb.backup.v2.0.0`
2. **Update deps.edn**: Change versions to `2.1.0-rc0`
3. **Update config files**: Move cache config to top-level (see Section B above)
4. **Update system.clj**: Add `:memory-cache` and `:disk-cache` handling
5. **Test in dev**: Run 100K record ingestion test
6. **Deploy to prod**: Rolling restart with monitoring

---

## Implementation Plan

### Phase 1: Immediate (This Sprint)

**Goal**: Stabilize v2.0.0 with configuration fixes

1. **Update JVM options** in `deps.edn` (`:xtdb` alias):
   ```clojure
   :jvm-opts ["-Xms16g" "-Xmx32g"
              "-XX:MaxDirectMemorySize=16g"
              "--add-opens=java.base/java.nio=ALL-UNNAMED"
              "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
              "-Dio.netty.tryReflectionSetAccessible=true"
              "--enable-native-access=ALL-UNNAMED"]
   ```

2. **Update system configuration**:
   - Modify `src/ml_options/system.clj` to accept compactor config
   - Update `resources/system.edn` with `:prod` compactor settings
   - Set `XTDB_COMPACTOR_THREADS=8` in prod environment

3. **Add monitoring**:
   - Create `logback.xml` with file output
   - Enable `XTDB_LOGGING_LEVEL_COMPACTOR=DEBUG`
   - Monitor disk usage in `data/xtdb/objects/`

4. **Testing**:
   - Run bulk ingestion of 7.6M records with new config
   - Monitor memory usage: `jmap -heap <pid>`
   - Monitor compaction logs

**Success Criteria**:
- Bulk ingestion completes without `NoSuchElementException`
- GC pause time < 500ms
- Compaction threads stay within CPU limits

### Phase 2: Upgrade (Next Sprint)

**Goal**: Move to v2.1.0+ for stability

1. **Test v2.1.0 in development**:
   - Update one dev machine to v2.1.0
   - Run full ingestion test (7.6M+ records)
   - Verify schema compatibility

2. **Update production**:
   - Update `deps.edn`
   - Stage deployment
   - Rolling restart with monitoring

3. **Optimize compactor settings** leveraging v2.1 features:
   - Increase `:file-size-target` for ingestion window
   - Use dedicated compaction nodes if distributed

### Phase 3: Long-term (Ongoing)

**Goal**: Production operations excellence

1. **Monitoring dashboard**:
   - Compaction job latency (via metrics)
   - Memory usage trends
   - L0 file count and sizes
   - Compaction error rate

2. **Runbooks**:
   - "Compaction stuck" recovery
   - "OOM errors" response
   - "Data verification after errors"

3. **Load testing**:
   - Quarterly stress tests with 100M+ records
   - Concurrent ingestion + query workloads
   - Failover scenarios

---

## Testing & Validation

### Test 1: Baseline Load (Immediate)

```clojure
(require '[ml-options.ingest :as ingest])
(require '[clojure.instant :as inst])

;; Time a bulk load of 7.6M option_greeks
(time
  (ingest/bulk-load-option-greeks 
    {:limit 7600000
     :batch-size 10000
     :start-date #inst "2024-01-01"}))

;; Expected: ~15-45 minutes depending on hardware
;; Should see no exceptions in logs
```

### Test 2: Memory Monitoring

```bash
# Monitor JVM memory during ingestion
jmap -heap <pid> | grep -E "MaxHeapSize|Committed"

# Monitor OS memory
watch -n 2 'free -h'

# Monitor compaction in logs
tail -f logs/xtdb.log | grep compaction
```

### Test 3: Compaction Completion

Verify all data is compacted after ingestion:

```clojure
(require '[ml-options.db.node :as node])

;; After ingestion, request full compaction
(node/query db '(from :option-greeks [xt/id] {:limit 1}))

;; Then in logs, should see:
;; "compacted 'option_greeks' -> 'l1-rc-*'"
;; "compacted 'option_greeks' -> 'l2-rc-*'"
```

### Test 4: Query Performance Post-Compaction

```clojure
;; Verify queries work efficiently after compaction
(time
  (count
    (node/query db
      '(-> (from :option-greeks [ticker strike iv])
           (where (> iv 0.25))))))

;; Should be < 5 seconds for 7.6M records
```

---

## Monitoring Setup

### Metrics to Track

1. **Compaction metrics** (via Micrometer):
   - `compactor.job.timer` (95th/99th percentile latency)
   - `compactor.jobs.available` (queue depth)

2. **Memory metrics**:
   - Heap usage (% of max)
   - Direct memory usage
   - GC pause time

3. **Storage metrics**:
   - L0 file count (should stabilize)
   - L0 total size
   - Compaction error count

### Log Pattern Monitoring

Monitor for these error patterns:

```bash
# Critical - compaction failures
grep "error running compaction job" logs/xtdb.log

# Critical - memory issues
grep "OutOfMemoryError\|MaxDirectMemorySize" logs/xtdb.log

# Warning - slow compaction
grep -E "compacted.*\(" logs/xtdb.log | \
  awk '{print $NF}' | \
  awk -F'[ms]' '$1 > 60000 {print}' # > 1 minute
```

---

## Troubleshooting Guide

### Issue: `NoSuchElementException` During Compaction

**Symptoms**:
```
ERROR: error running compaction job: public/option_greeks/l01-rc-b12c
java.util.NoSuchElementException
```

**Causes & Solutions**:

| Cause | Solution |
|-------|----------|
| Insufficient heap | Increase `-Xmx` by 50%; verify with `jmap` |
| Insufficient direct memory | Increase `-XX:MaxDirectMemorySize` |
| Compactor threads too high | Reduce `XTDB_COMPACTOR_THREADS` to 4 |
| Concurrent ingestion + compaction | Disable compaction during bulk load |
| XTDB v2.0.0 bug | Upgrade to v2.1.0+ |

### Issue: GC Pause Time Exceeds 500ms

**Solution**:
```bash
# Check current JVM
jstat -gc -h20 <pid> 1000  # Every 1 second

# Increase -XX:MaxGCPauseMillis
-XX:MaxGCPauseMillis=500  # Default 200ms is too aggressive

# Add explicit GC options
-XX:+ParallelRefProcEnabled
-XX:+UnlockDiagnosticVMOptions
-XX:G1SummarizeRSetStatsPeriod=1  # Reduce summary overhead
```

### Issue: Data Verification After Error

```clojure
;; After error recovery, verify data integrity
(require '[ml-options.db.node :as node])

;; Count records
(let [count-query '(aggregate {:cnt (count xt/id)} [xt/id])]
  (node/query db count-query))

;; Check for duplicates
(let [dup-query '(-> (from :option-greeks [xt/id])
                     (aggregate {:cnt (count xt/id)} xt/id)
                     (where (> cnt 1)))]
  (node/query db dup-query))

;; Verify schema integrity
(let [schema-query '(from :option-greeks {:limit 100} [*])]
  (node/query db schema-query))
```

---

## Deployment Checklist

- [ ] JVM options updated in `deps.edn` (heap, direct memory, GC)
- [ ] `src/ml_options/system.clj` modified to accept compactor config
- [ ] `resources/system.edn` updated with `:prod` compactor settings
- [ ] Environment variables configured: `XTDB_COMPACTOR_THREADS`, `XTDB_LOGGING_LEVEL_COMPACTOR`
- [ ] `logback.xml` created with file output and 30-day rotation
- [ ] Monitoring dashboard configured (Grafana/Prometheus if available)
- [ ] Data backup taken: `cp -r data/xtdb data/xtdb.backup-<date>`
- [ ] Dev testing completed: 7.6M record ingestion passes
- [ ] Staging deployment validated (if applicable)
- [ ] Runbooks documented for on-call engineers
- [ ] Version upgrade to v2.1.0+ scheduled (within 2 months)

---

## References

### XTDB Documentation
- [XTDB v2 Configuration](https://docs.xtdb.com/ops/config.html)
- [XTDB Troubleshooting](https://docs.xtdb.com/ops/troubleshooting.html)
- [XTDB GitHub - Issues](https://github.com/xtdb/xtdb/issues)
- [XTDB GitHub - Releases](https://github.com/xtdb/xtdb/releases)

### Apache Arrow Memory Management
- [Arrow Memory Management](https://arrow.apache.org/docs/java/memory.html)
- [jemalloc Performance](https://arrow.apache.org/blog/2018/07/20/jemalloc/)

### JVM Tuning
- [Oracle JVM Memory Configuration](https://docs.oracle.com/cd/E15289_01/JRPTG/memman.htm)
- [G1GC Tuning Guide](https://www.oracle.com/technical-resources/articles/java/g1gc.html)

### XTDB Development
- [Development Diary #10 - v2 Primary Index](https://xtdb.com/blog/dev-diary-feb-24)
- [Development Diary #13 - Beta Release](https://xtdb.com/blog/dev-diary-sep-24)

---

## Appendix A: Full Configuration Example

### deps.edn (minimal addition)

```clojure
{:aliases
 {:xtdb
  {:jvm-opts ["-Xms16g"
              "-Xmx32g"
              "-XX:MaxDirectMemorySize=16g"
              "-XX:+UseG1GC"
              "-XX:MaxGCPauseMillis=200"
              "-XX:InitiatingHeapOccupancyPercent=35"
              "-XX:+ParallelRefProcEnabled"
              "--add-opens=java.base/java.nio=ALL-UNNAMED"
              "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
              "-Dio.netty.tryReflectionSetAccessible=true"
              "--enable-native-access=ALL-UNNAMED"]}}}
```

### resources/system.edn (updated)

```edn
{:ml-options/xtdb-node
 {:storage #profile {:dev   {:type :local :path "data/xtdb"}
                     :test  :in-memory
                     :prod  {:type :local :path #or [#env XTDB_DATA_PATH "data/xtdb"]}}
  :compactor #profile {:dev {:threads 2}
                       :test {:threads 1}
                       :prod {:threads #or [#env XTDB_COMPACTOR_THREADS 8]}}}}
```

### Dockerfile (if containerized)

```dockerfile
FROM openjdk:21-slim

ENV XTDB_COMPACTOR_THREADS=8 \
    XTDB_LOGGING_LEVEL=INFO \
    XTDB_LOGGING_LEVEL_COMPACTOR=DEBUG

RUN java -Xms16g -Xmx32g -XX:MaxDirectMemorySize=16g -version

COPY app.jar /app/

ENTRYPOINT ["java", \
  "-Xms16g", "-Xmx32g", \
  "-XX:MaxDirectMemorySize=16g", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=200", \
  "--add-opens=java.base/java.nio=ALL-UNNAMED", \
  "-Dio.netty.tryReflectionSetAccessible=true", \
  "--enable-native-access=ALL-UNNAMED", \
  "-jar", "/app/app.jar"]
```

---

## Appendix B: v2.0.0 vs v2.1.0-rc0 Comparison

| Feature | v2.0.0 | v2.1.0-rc0 |
|---------|--------|------------|
| **Compaction reliability** | Good (with config tuning) | **Excellent** (bug fixes) |
| **NoSuchElementException bug** | Present | **Fixed** |
| **Arrow merge edge cases** | Workaround via config | **Fixed in core** |
| **Cache configuration** | Nested under storage | **Top-level keys** (breaking) |
| **CLI structure** | Flag-based | **Git-style subcommands** |
| **HTTP server** | Deprecated | **Removed** |
| **Dedicated compactor nodes** | No | **Yes** (`xtdb compactor`) |
| **Emergency recovery** | Manual | **`reset-compactor` command** |
| **Snapshot export** | No | **Yes** (`export-snapshot`) |
| **Multi-database support** | No | **Yes** |
| **Memory error diagnostics** | Basic | **Detailed** |
| **Production readiness** | With workarounds | **Recommended** |

### Emergency Recovery with v2.1.0-rc0

If compaction errors occur (unlikely after upgrade), use the new tools:

```bash
# Stop nodes, reset compaction state to L0
xtdb reset-compactor my-database -f config.yaml

# Run dedicated compactor to rebuild
xtdb compactor -f config.yaml

# Export snapshot for disaster recovery
xtdb export-snapshot my-database -f config.yaml -o s3://bucket/snapshots/
```

---

**Document Status**: Ready for Implementation
**Target Version**: v2.1.0-rc0
**Next Review**: After Phase 1 completion (2 weeks)
**Branch**: `feature/bulk-loader-resilience`
