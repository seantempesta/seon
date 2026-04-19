---
type: research
status: completed
tags: [research, archive]
---

# CPU Spike Investigation Report

**Date:** 2026-02-01
**Incident Time:** ~15:55-16:03 (local time, UTC+7)
**Severity:** Critical - required kill -9 of JVM
**Investigator:** Agent 3666

## Executive Summary

The system experienced a severe CPU spike (load 154, JVM at 698% CPU, XTDB latency 18 seconds) requiring a hard kill of the JVM. The root cause appears to be related to **agent message persistence to XTDB** during large file reads, possibly combined with residual stress from earlier test runs.

## Timeline

| Time (UTC) | Time (Local) | Event |
|------------|--------------|-------|
| 08:23:28 | 15:23 | Agent d778 launched for namespace isolation research |
| 08:34:58 | 15:34 | Agent d778 completed successfully ($4.30, 11.4 min) |
| 08:59:23 | 15:59 | Agent 5ac0 launched for Sci research |
| 09:00:05 | 16:00 | Agent 5ac0 adds Sci git submodule |
| 09:02:47 | 16:02 | Agent 5ac0 starts reading Sci source files |
| 09:03:15 | 16:03 | Agent 5ac0 makes 3 parallel Read tool calls (last log entry) |
| ~09:03:30 | ~16:03 | System becomes unresponsive |
| - | ~16:03 | Agent killed (manually) |
| - | ~16:03 | JVM still at 698% CPU after agent termination |
| 09:03:30 | 16:03 | Shutdown signal received (kill -9) |
| 09:04:36 | 16:04 | System restarted |

## Evidence

### 1. Agent 5ac0 Log (logs/agents/5ac0.log)

The agent log shows 20 lines, ending abruptly:

```
2026-02-01T09:02:47Z | TOOL     | Read | sci/core.cljc
2026-02-01T09:02:59Z | TOOL     | Read | sci/impl/vars.cljc
2026-02-01T09:03:15Z | TOOL     | Read | sci/impl/interop.cljc

```

No results were logged for these Read operations. The agent appears to have gotten stuck during/after initiating these reads.

### 2. XTDB Logs (logs/xtdb.2026-01-31.0.log.gz)

Evidence of previous XTDB stress from Jan 31:

```
2026-01-31 14:40:26,971 - 14:40:29,483
~30+ XTDB node creations in ~3 seconds
Each: node creation -> pgwire start -> pgwire stop

```

This rapid node creation (likely from tests) may have left the XTDB data directory in a stressed state.

### 3. System State at Incident

- **Agent showed as running** but had 0 evals
- **JVM at 698% CPU** even after killing agent process
- **XTDB latency: 18 seconds** (normally <100ms)
- **Load average: 154** (normally <5)
- Required kill -9 to recover

### 4. Code Analysis

The message persistence path in `seon.ai.claude/launch-agent!`:
1. Every SDK message is persisted via `persist-message!`
2. Messages include full content (file contents for Read tools)
3. XTDB transactions are synchronous
4. No timeout or circuit breaker on persistence

From `seon.ai.claude.clj:389-410`:

```clojure
(when (persistable-message-type? msg-type)
  (try
    (persist-message! {...})
    (catch Exception e
      (Thread/sleep 100)  ; Simple retry after 100ms
      (persist-message! {...}))))  ; No limit on retries

```

## Root Cause Analysis

### Primary Hypothesis: XTDB Persistence Overload

1. Agent 5ac0 read 3 large Sci source files in parallel
2. Each Read result triggers message persistence to XTDB
3. Large message content (file contents) creates large transactions
4. Multiple concurrent transactions overwhelm XTDB's indexing

### Contributing Factors

1. **Previous XTDB stress**: 30+ rapid node creations on Jan 31 may have fragmented the data directory
2. **No backpressure**: Message persistence has no circuit breaker
3. **Long-running session**: System had been running since morning
4. **408MB XTDB data**: Significant data accumulation requiring indexing

### Why Killing Agent Didn't Help

The agent process (Claude Code CLI) was killed, but:
1. The JVM's XTDB indexing threads continued processing
2. XTDB may have been in a compaction or recovery loop
3. GC pressure from large message content in memory
4. Arrow/Netty direct memory may have been exhausted

## Recommendations

### Immediate (Before Next Agent Launch)

1. **Clear stale data**: Delete old agent databases and compact XTDB

```bash
rm -rf data/namespaces/seon.experimental.*

```

2. **Add XTDB health monitoring**: Check latency before allowing new agent launches

```clojure
(defn xtdb-healthy? [node]
  (let [start (System/currentTimeMillis)]
    (db/q node "SELECT 1" [])
    (< (- (System/currentTimeMillis) start) 1000)))

```

### Short-Term (This Week)

3. **Add message persistence circuit breaker**:

```clojure
;; Skip persistence if XTDB is slow
(when (and (persistable-message-type? msg-type)
           (< (xtdb-latency node) 500))
  (persist-message! ...))

```

4. **Limit persisted content size**:

```clojure
;; Truncate large content in messages
(defn truncate-content [msg max-size]
  (update msg ::ai/content #(if (> (count %) max-size)
                              (str (subs % 0 max-size) "...[truncated]")
                              %)))

```

5. **Add agent launch health check**:

```clojure
(defn pre-launch-health-check [node]
  (let [health (health/deep-check {::health/node node})]
    (when (= :unhealthy (::health/status health))
      (throw (ex-info "System unhealthy, cannot launch agent" health)))))

```

### Medium-Term (This Month)

6. **Async message persistence**: Don't block agent execution on XTDB writes

```clojure
(future (persist-message! ...))  ; Non-blocking

```

7. **XTDB compaction schedule**: Run compaction during idle periods

8. **Resource limits per agent**:
   - Max file size for Read operations
   - Max message size for persistence
   - Max messages per minute

9. **Auto-kill stuck agents**: If no activity for N minutes, terminate

```clojure
;; In health check
(when (> (minutes-since-activity agent) 5)
  (interrupt! agent))

```

### Long-Term

10. **Separate XTDB nodes**: Message persistence on dedicated node

11. **Message sampling**: Don't persist every message, sample for debugging

12. **Better observability**:
    - XTDB transaction latency metrics
    - Memory pressure alerts
    - CPU throttling when load > threshold

## Files Changed

None - investigation only.

## Files to Monitor

- `src/seon/ai/claude.clj` - Message persistence logic
- `src/seon/health.clj` - Health check functions
- `logs/xtdb.*.log*` - XTDB activity
- `data/xtdb/` - Database size and fragmentation

## Testing Recommendations

Before running agents with large file reads:
1. Check `(health/deep-check {::health/node node})`
2. Verify XTDB latency < 500ms
3. Ensure adequate memory headroom (< 80% heap used)
4. Have terminal ready with `pkill -9 -f "clojure.*seon"`

## Conclusion

The CPU spike was likely caused by XTDB becoming overwhelmed with message persistence during large file reads. The lack of backpressure and circuit breakers allowed the system to enter an unrecoverable state. Implementing the recommended safeguards will prevent similar incidents.

---

*Report generated by Agent 3666 on 2026-02-01*
