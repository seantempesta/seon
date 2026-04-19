---
type: research
status: completed
tags: [research, archive, agent]
---

# Complete Isolation Research: Separate JVM Per Agent

Research conducted: 2026-01-04

## Executive Summary

Complete isolation (Option B) is **feasible but expensive**. Running separate JVMs per agent namespace requires approximately **1.5-2.5GB RSS per agent** with XTDB, or **512MB-800MB** with aggressive optimization (in-memory XTDB, minimal heap). The main trade-off is **memory consumption vs true isolation**.

**Recommendation**: Start with shared infrastructure (Option A) for most use cases. Reserve complete isolation for agents that:
- Need to run different code versions
- Require memory isolation (untrusted workloads)
- Work on long-running, critical tasks where crash isolation matters

---

## 1. Minimum JVM Memory for Seon

### Current Production Configuration

The running Seon server with full stack:

```
JVM Flags: -Xms4g -Xmx8g -XX:MaxDirectMemorySize=4g
RSS (Resident Set Size): ~3.7GB

```

This is for a fully loaded XTDB with data, all namespaces, and the HTTP/nREPL servers.

### Memory Tiers for Clojure Applications (Java 21)

Based on current research and Clojure runtime characteristics:

| Tier | Total RAM (RSS) | Recommended Heap (`-Xmx`) | Use Case |
| :--- | :--- | :--- | :--- |
| **Absolute Minimum** | ~80-128MB | 32-64MB | CLI tools, background scripts |
| **Microservice Floor**| ~256MB | 128-160MB | Simple API, small internal tools |
| **Standard Production**| ~512MB-1GB | 256-512MB | Web apps with DB, caching, libraries |
| **Seon Agent (Min)** | ~800MB | 512MB | nREPL + in-memory XTDB |
| **Seon Agent (Standard)** | ~1.5-2GB | 1GB | nREPL + persistent XTDB + HTTP |
| **Seon Orchestrator** | ~3-4GB | 2-4GB | Full stack, multiple attached DBs |

### Key Memory Drivers

1. **Clojure Metaspace**: Clojure generates classes at runtime. Reserve at least **64-100MB** for Metaspace (`-XX:MaxMetaspaceSize`).

2. **XTDB Arrow Buffers**: XTDB v2 uses Apache Arrow for columnar storage. The `memory-cache` setting controls this:
   - Default: 50% of MaxDirectMemorySize
   - Minimum viable: ~64MB for light workloads
   - Configurable via `:memory-cache {:max-size-bytes 67108864}` (64MB)

3. **nREPL + CIDER**: The nREPL middleware stack adds ~50-100MB overhead.

4. **Direct Memory**: Required for Arrow buffers. Set explicitly via `-XX:MaxDirectMemorySize`.

### Recommended Low-Memory Agent JVM Flags

For an agent instance with ~1GB total target:

```bash
java -XX:+UseSerialGC \
     -Xmx512m \
     -Xms512m \
     -XX:MaxMetaspaceSize=96m \
     -XX:MaxDirectMemorySize=256m \
     -XX:TieredStopAtLevel=1 \
     --add-opens=java.base/java.nio=ALL-UNNAMED \
     --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
     --add-opens=java.base/java.lang=ALL-UNNAMED \
     -Dio.netty.tryReflectionSetAccessible=true \
     --enable-native-access=ALL-UNNAMED \
     -jar seon.jar

```

**Flag explanations:**
- `-XX:+UseSerialGC`: Lowest overhead GC for < 1GB heaps
- `-XX:TieredStopAtLevel=1`: Disables C2 JIT, saves 30-50MB, slightly slower runtime
- `-XX:MaxDirectMemorySize=256m`: Limits Arrow buffer pool

---

## 2. XTDB Memory Optimization

### XTDB v2 Memory Components

XTDB v2 memory usage consists of:

1. **Memory Cache** (Arrow buffers): Configurable, defaults to 50% of direct memory
2. **Disk Cache** (optional): For remote storage, not needed for local
3. **Compactor**: Background threads, 1-2 threads minimum

### Minimal XTDB Configuration for Agents

```clojure
;; In-memory mode (fastest, no persistence)
{:storage :in-memory
 :memory-cache {:max-size-bytes 67108864}  ; 64MB
 :compactor {:threads 0}}                   ; Disable for in-memory

;; Persistent but minimal
{:storage [:local {:path "data/agent-ns"}]
 :memory-cache {:max-size-bytes 134217728}  ; 128MB
 :compactor {:threads 1}}

```

### XTDB In-Memory Mode

For agent isolation, in-memory XTDB is viable if:
- Agent work is short-lived (data doesn't need persistence across restarts)
- Agent syncs to orchestrator DB periodically
- Crash recovery is acceptable (restart from orchestrator state)

**Advantages:**
- No disk I/O overhead
- Faster startup
- Lower memory footprint (no compaction overhead)
- Simpler cleanup

**Disadvantages:**
- Data lost on crash
- Cannot query historical data after restart
- Limited by available RAM

### Memory Cache Configuration

The `MemoryCache` class in XTDB accepts:
- `maxSizeBytes`: Absolute limit in bytes
- `maxSizeRatio`: Ratio of MaxDirectMemorySize (default 0.5)

For agents, set explicit bytes rather than ratio:

```clojure
:memory-cache {:max-size-bytes 134217728}  ; 128MB explicit

```

---

## 3. Startup Time

### Current Cold Start (Full Stack)

Measured startup time for Seon with full stack:
- **Clojure namespace loading**: ~3-4 seconds
- **XTDB initialization**: ~2-3 seconds (empty, local storage)
- **HTTP server**: ~100ms
- **nREPL server**: ~200ms
- **Total cold start**: ~6-8 seconds

### Optimization Strategies

#### A. Project Leyden / AppCDS (Recommended)

Java 25 LTS includes AOT class loading that can speed up startup by **30-40%**:

```bash
# Generate CDS archive
java -Xshare:dump -XX:SharedClassListFile=classlist.txt -XX:SharedArchiveFile=app.jsa

# Use CDS archive
java -Xshare:on -XX:SharedArchiveFile=app.jsa -jar app.jar

```

Expected improvement: 6-8s -> 4-5s

#### B. GraalVM Native Image

Compiles Clojure to native binary. Dramatic startup improvement but significant trade-offs.

**Performance:**
- Startup: **10-50ms** (vs 6-8s for JVM)
- Memory: **20-40MB** RSS (vs 800MB+ for JVM)

**Trade-offs:**
- No dynamic code loading (no REPL in the binary)
- Requires reflection configuration
- Build time: 3-10 minutes
- Debugging is harder

**When to use:**
- CLI tools
- Serverless functions
- If REPL is not needed in the agent

**Clojure-specific requirements:**
- Use `clj-easy/graal-build-time` library
- Enable direct linking: `-Dclojure.compiler.direct-linking=true`
- Generate reflection configs via native-image-agent

#### C. Project CRaC (Checkpoint/Restore)

"Hibernate" a running JVM to disk, restore instantly.

**Performance:**
- Restore time: **50-100ms**
- Retains JIT compilation benefits

**Trade-offs:**
- Linux-only (requires CRIU)
- Checkpoint files contain memory (security concern)
- Requires coordination with XTDB (connections, file handles)

**Not recommended for Seon** due to XTDB's stateful nature and macOS requirement.

#### D. Warm JVM Pool (Not Recommended)

Legacy tools like Nailgun/Drip are deprecated. Modern alternatives:
- **JBang**: Good for scripts, not for server applications
- **Pre-warmed containers**: Docker with warm JVMs, but complex

### Startup Time Summary

| Strategy | Cold Start | Warm Start | Complexity |
| :--- | :--- | :--- | :--- |
| Baseline JVM | 6-8s | N/A | Low |
| AppCDS | 4-5s | N/A | Low |
| GraalVM Native | 10-50ms | N/A | High |
| CRaC | N/A | 50-100ms | High |
| Pre-warmed Pool | N/A | <1s | High |

**Recommendation**: Start with baseline, add AppCDS if startup becomes a bottleneck.

---

## 4. Port Management

### Port Allocation Scheme

For multiple isolated agents, use a deterministic port allocation:

```
Orchestrator:
  HTTP: 8080
  nREPL: 7888

Agent Ports:
  Base HTTP: 8100
  Base nREPL: 7900

  Agent 0 (trading):    HTTP 8100, nREPL 7900
  Agent 1 (health):     HTTP 8101, nREPL 7901
  Agent 2 (finance):    HTTP 8102, nREPL 7902
  ...
  Agent N:              HTTP 8100+N, nREPL 7900+N

```

### nREPL: Unix Sockets vs TCP

**Unix Socket Advantages:**
- File-based permissions (more secure)
- No port collision
- ~50% faster for high-throughput messaging
- Easy cleanup: just delete the socket file

**Unix Socket Disadvantages:**
- Limited editor support (CIDER excellent, Calva/Cursive partial)
- Cannot connect remotely

**Recommendation for agents:**
Use Unix sockets if using CIDER, TCP ports otherwise:

```clojure
;; Unix socket approach
:nrepl-server {:socket "data/agents/trading-abc123/nrepl.sock"}

;; TCP approach
:nrepl-server {:port (+ 7900 agent-number)
               :bind "127.0.0.1"}

```

### HTTP Port Strategy

**Option A: Port per agent** (simpler)
- Each agent gets its own HTTP port
- Agent connects directly: `http://localhost:8101`
- Easier debugging, direct access

**Option B: Reverse proxy** (more complex)
- Single port (8080) with path routing
- `/agent/trading/` -> agent on 8100
- More complex setup, but cleaner external interface

**Recommendation**: Port per agent for simplicity during development.

---

## 5. Process Supervision

### Tool Comparison (2025)

| Feature | Foreman | Overmind | Process Compose |
| :--- | :--- | :--- | :--- |
| **Language** | Ruby | Go | Go |
| **Interactive Debugging** | No | Yes (via `connect`) | No |
| **Individual Restart** | No | Yes | Yes |
| **UI/TUI** | Basic logs | Tmux-based | Full TUI Dashboard |
| **Health Checks** | No | Basic | Advanced |

### Recommendation: Overmind

For Seon agent management, **Overmind** is the best fit:

1. **Interactive debugging**: Run `overmind connect trading` to connect to agent's nREPL
2. **Individual restart**: `overmind restart trading` without affecting other agents
3. **Clean process tree**: Each agent in its own tmux session
4. **Simple Procfile**:

```procfile
# Procfile.agents
orchestrator: ./bin/run --profile dev
trading: ./bin/run-agent --namespace trading --http-port 8100 --nrepl-port 7900
health: ./bin/run-agent --namespace health --http-port 8101 --nrepl-port 7901

```

### Alternative: Process Compose

For more complex deployments (10+ agents), **Process Compose** offers:
- Dependency ordering (start DB before agents)
- Health checks (don't route to unhealthy agent)
- TUI dashboard for monitoring

### Log Aggregation

Each agent should log to a separate file:

```
logs/
  orchestrator.log
  agents/
    trading-abc123.log
    health-def456.log

```

Use `tee` or logging configuration to split stdout:

```bash
./bin/run-agent --namespace trading 2>&1 | tee logs/agents/trading.log

```

---

## 6. Container Isolation (Alternative)

### Docker vs Podman Memory Overhead

| Metric | Docker | Podman |
| :--- | :--- | :--- |
| **Idle Memory (No Containers)** | ~140-180MB | **0 MB** |
| **Memory with 10 Containers** | ~420-550MB | ~280-350MB |
| **Memory Per Container** | ~25-30MB | ~20-25MB |

**Podman advantages:**
- Daemonless architecture (no background process)
- Uses `crun` (C) vs `runc` (Go) - more memory efficient
- Zero idle footprint
- Better for Mac M-series (native ARM)

### Container-Based Agent Isolation

```yaml
# docker-compose.yml for agents
services:
  orchestrator:
    image: seon:latest
    ports:
      - "8080:8080"
      - "7888:7888"
    volumes:
      - ./data/xtdb:/app/data/xtdb

  agent-trading:
    image: seon:latest
    command: ["--profile", "agent", "--namespace", "trading"]
    ports:
      - "8100:8080"
    volumes:
      - ./data/agents/trading:/app/data/xtdb
    deploy:
      resources:
        limits:
          memory: 1G

```

### When to Use Containers

**Use containers if:**
- Deploying to Kubernetes
- Need strict memory limits per agent
- Want to run different Seon versions per agent
- Need network isolation

**Don't use containers if:**
- Development on a laptop (overhead adds up)
- Frequent code changes (rebuild overhead)
- Shared filesystem access needed

---

## 7. Tradeoffs: Option A vs Option B

### Memory Comparison

| Configuration | Option A (Shared) | Option B (Isolated) |
| :--- | :--- | :--- |
| 1 orchestrator | 3-4GB | 3-4GB |
| + 1 agent | +200MB | +1.5GB |
| + 2 agents | +400MB | +3GB |
| + 5 agents | +1GB | +7.5GB |
| **Total (5 agents)** | **~5GB** | **~11GB** |

### Startup Time Comparison

| Scenario | Option A | Option B |
| :--- | :--- | :--- |
| First agent | ~100ms (DB attach) | ~6-8s (JVM cold start) |
| Nth agent | ~100ms | ~6-8s |

### Isolation Guarantees

| Failure Mode | Option A | Option B |
| :--- | :--- | :--- |
| Agent OOM | May affect orchestrator | Contained to agent |
| Agent infinite loop | Shared CPU | Contained (can set limits) |
| Agent crashes | Other agents unaffected* | Other agents unaffected |
| Code version conflict | Not possible | Possible |

*In Option A, agents share a JVM but use separate XTDB databases.

---

## 8. Recommendations

### Default: Option A (Shared Infrastructure)

For most agent workloads, shared infrastructure is sufficient:
- Agents work on isolated databases (via ATTACH DATABASE)
- Shared JVM is more memory efficient
- Faster agent startup (100ms vs 6-8s)
- Simpler deployment

### When to Use Option B (Complete Isolation)

Use complete isolation for:

1. **Long-running critical agents**: Agents that run for hours and cannot tolerate disruption
2. **Memory-intensive workloads**: Large data processing that might OOM
3. **Version testing**: Testing code changes in isolation before merge
4. **Untrusted workloads**: If agents run untrusted code (unlikely for Seon)

### Hybrid Approach

Consider a hybrid where:
- Most agents use shared infrastructure (Option A)
- Specific namespaces get isolated JVMs (Option B)

```clojure
{:agent/id "trading-abc123"
 :agent/namespace :namespace/trading
 :agent/isolation-mode :complete  ; <- Only this agent is isolated
 :agent/port 8100
 :agent/nrepl-port 7900}

```

The orchestrator tracks which agents are shared vs isolated and routes accordingly.

---

## 9. Showstoppers and Concerns

### Showstopper: Memory

On a 16GB development machine:
- Orchestrator: 4GB
- 5 isolated agents: 7.5GB (1.5GB each)
- macOS overhead: ~2GB
- **Remaining**: ~2.5GB for IDE, browser, etc.

This is tight. More than 5 concurrent isolated agents becomes impractical on a 16GB machine.

### Concern: Startup Time

6-8 second cold start per agent is acceptable for long-running agents but problematic for quick, ephemeral tasks. Consider:
- Using in-memory XTDB for short-lived agents
- Pre-warming a pool of ready agents
- Using shared infrastructure for quick tasks

### Concern: Coordination Complexity

With isolated JVMs, the orchestrator must:
- Track process PIDs
- Monitor health
- Manage port allocation
- Route nREPL commands to correct instance
- Aggregate logs

This is significantly more complex than shared infrastructure.

---

## Appendix: Test Commands

### Check Current Seon Memory

```bash
ps -o pid,rss,vsz,comm -p $(pgrep -f "seon.runner")

```

### Start Minimal Seon (untested)

```bash
JVM_OPTS="-Xms256m -Xmx512m -XX:MaxDirectMemorySize=128m -XX:+UseSerialGC" ./bin/run

```

### Monitor Multiple Processes

```bash
watch -n 1 "ps aux | grep java | grep -v grep | awk '{print \$11, \$6/1024\"MB\"}'"

```
