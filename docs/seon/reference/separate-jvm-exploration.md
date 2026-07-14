---
type: reference
status: active
tags: [reference, database]
---
# Separate JVM Agent Isolation — Exploration Results

**Date:** 2025-02-14
**Context:** Forked from the Super REPL PRD planning conversation to explore whether separate JVM processes per agent could replace the namespace-instance-cloning approach.

**Fork point:** The original plan proposed creating namespace clones (e.g., `seon.trading.signals.a13b`) within a shared JVM, with a Super REPL that rewrites `::keywords` and manages per-instance Malli registries. Sean asked: "could an orchestrator treat it as a process it's spinning up and feeding data to?"

**Conclusion: Separate JVMs are dramatically simpler AND provide stronger guarantees. The namespace cloning approach should be abandoned in favor of this.**

---

## Key Finding: The Shared JVM Problems Disappear

The original plan identified three hard problems with namespace cloning in a shared JVM:

| Problem | Shared JVM Solution (Complex) | Separate JVM Solution (Free) |
|---------|-------------------------------|------------------------------|
| `::keyword` resolves to instance namespace | AST walk + rewrite via edamame | Doesn't happen — each JVM IS the real namespace |
| Malli global registry conflicts | Per-instance local registries, merge on graduation | Each JVM has its own registry. No conflicts. |
| `defn` clobbering between agents | Namespace clones via `create-ns` + `refer` | Separate memory spaces. Impossible to clobber. |
| Agent crashes orchestrator | Can't prevent OOM/infinite loop/System.exit | OS-level isolation. Agent crash = process dies. |
| Privilege separation | Not possible — all code on same classpath | Agent JVM only has its namespace's deps. Can't access web server, database, orchestrator code. |

---

## Architecture: What We Verified

### 1. core.async.flow Is Threads, Not Processes

**Source:** Read actual flow source in `reference-code/core.async/`

- Flow "processes" are JVM threads from an ExecutorService
- Channels are in-memory Java objects (can't cross JVM boundaries)
- Data is shared by reference (no serialization)
- Flow has a `ProcLauncher` protocol — the plug point for custom process types
- Flow supports `::flow/in-ports` and `::flow/out-ports` for external channel injection

**Implication:** Flow can't natively do cross-JVM communication, but the abstraction is clean enough to extend. Two approaches:

1. **RemoteProcLauncher** — Implement ProcLauncher protocol to spawn JVM subprocesses, bridge channels over nREPL
2. **Proxy flow node** (simpler) — Regular flow process whose `transform` does nREPL communication to the agent JVM. Agent JVM knows nothing about flow.

### 2. Database Access Across JVMs

**Note (2026-05):** The original exploration assumed Datalevin's client-server model. The core runs two tracks. On the **paused JVM main-app track** `[JVM track — paused]`, Datahike is embedded (in-process LMDB) — there is no TCP server to connect to. On the **active CLJS pod track**, the pod does not embed Datahike at all: it forwards writes over a Unix socket to the central `wire-server` datahike writer (file-backed datahike at `data/clusters/default/store`), and reads are local lazy db values.

**Implication for separate-JVM isolation:** `[JVM track — paused]` On the JVM track, agent JVMs cannot share a single embedded Datahike instance directly. Cross-JVM database access must route through the orchestrator's flow topology (the orchestrator owns the embedded Datahike; agents send query/transact requests via nREPL or core.async.flow channels and receive replies). This is heavier than the original ~50ms TCP-client estimate, but the flow path is already how `seon.db` works inside a single JVM. (The active pod track sidesteps this entirely by centralizing writes in `wire-server`.)

### 3. nREPL Already Works Cross-Process

**Source:** `bin/mcp-server` (Babashka) already connects to nREPL over TCP using bencode

- MCP server is a separate process (Babashka) that connects to orchestrator nREPL on port 7888
- Uses `SocketChannel` + bencode protocol
- Session management works over TCP
- This pattern is battle-tested — every Claude Code agent already uses it

**Implication:** No new IPC mechanism needed. nREPL over TCP is the transport layer.

### 4. XTDB Is the Memory Hog — Remove It

**Source:** `deps.edn` analysis + `complete-isolation.md` research doc

With XTDB + Arrow + Netty:

- 1.5-2GB per agent JVM
- 6-8s startup
- Requires `--add-opens` JVM flags for Java 21

Without XTDB (lightweight DB client only):

- **186MB measured** (prototype agent JVM)
- ~2-3s startup
- No special JVM flags needed
- No direct memory allocation (no Arrow buffers)

### 5. Dynamic Dependency Loading Works

Clojure 1.12 (which we're on) has `clojure.repl.deps/add-libs`:

```clojure
(add-libs '{org.clojure/data.csv {:mvn/version "1.1.0"}})

```

Loads Maven artifacts at runtime into the running JVM. Agent can request new deps without restart, isolated to that JVM.

---

## Prototype: Measured Results

Created and tested a minimal agent JVM. Files:

- `src/seon/flow/agent_runner.clj` — Entry point: nREPL + DB client (originally Datalevin; see note above) + ctx atom
- `src/seon/test/hello.clj` — Test namespace with Malli validation
- `bin/agent-runner` — Launch script
- `deps.edn` `:agent` alias — `:replace-deps` with only 7 deps

### Measurements

| Metric | Agent JVM | Main Seon JVM | Ratio |
|--------|-----------|---------------|-------|
| **RSS Memory** | **186 MB** | 3,409 MB | **18x smaller** |
| Deps count | 7 | 30+ | Much fewer |
| JVM flags | 4 basic flags | 10+ (incl. `--add-opens`) | Simpler |
| Heap limit | 256 MB | 8 GB | Constrained by design |

**Agent deps (`:replace-deps`):**

- `org.clojure/clojure` 1.12.0
- `org.clojure/core.async` 1.9.829-alpha2
- `metosin/malli` 0.17.0
- `datalevin/datalevin` (local from reference-code; the core has since migrated to Datahike — see Section 2 note)
- `nrepl/nrepl` 1.3.0
- `com.taoensso/timbre` 6.5.0
- `cheshire/cheshire` 5.13.0

**Agent JVM flags:**

```
-Xms128m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=64m -XX:TieredStopAtLevel=1

```

### Capacity Estimate (16GB dev machine)

- Orchestrator: ~3.4 GB
- Each agent: ~186 MB
- macOS overhead: ~2 GB
- **Available for agents: ~10.6 GB → ~57 concurrent agents**

vs. the shared-JVM model where agents are ~200MB each but can crash the whole system.

---

## Revised Super REPL Architecture

The Super REPL plan should be updated to reflect:

### What Changes

1. **No namespace cloning** — Each agent gets its own JVM. The namespace IS the real namespace in that JVM. No `seon.trading.signals.a13b` instances.

2. **No `::keyword` rewriting** — `::position` resolves correctly because the agent IS `seon.trading.signals` in its own runtime.

3. **No per-instance Malli registry** — Each JVM has its own global Malli registry. No conflicts possible.

4. **Super REPL becomes simpler** — It's a form router that:
   - Receives forms from agents (or orchestrator)
   - Sends them to the right agent JVM via nREPL
   - Stores them in Datahike (knowledge graph)
   - Runs clj-kondo analysis (stdin, no files needed)
   - On graduation: writes forms to disk as real `.clj` files, git commits

5. **Privilege separation** — Agent JVMs can't access orchestrator code, web server, or other namespaces' databases. They only have their namespace's deps.

6. **Dynamic deps** — Agents can `add-libs` at runtime to pull in new dependencies without restarting.

### What Stays The Same

1. **Knowledge Graph (Phase 1)** — clj-kondo analysis → Datahike. Still needed for querying dependencies, functions, specs.

2. **Agent-as-Flow-Node (Phase 3)** — Orchestrator wraps agent JVMs as flow nodes. The proxy pattern works: flow process in orchestrator whose `transform` does nREPL eval on the agent.

3. **Dynamic Cockpit (Phase 4)** — MCP tools for agents to query the knowledge graph.

4. **Inter-Agent Messaging (Phase 5)** — Messages via Datahike + flow channels in orchestrator.

5. **Graduation** — Generate namespace file from Datahike forms, git commit, verify tests.

6. **Eval-only model** — Agents get code via eval (Super REPL), not file editing. Forms stored in Datahike.

### New Phase 2: Agent JVM Runner

Replace the old "Namespace Instances" phase with:

- `seon.flow.agent-runner` — Already prototyped. Minimal JVM entry point.
- `seon.flow.launcher` — Orchestrator-side: spawn agent JVMs via `clojure.java.process/start`, wait for `AGENT_READY` signal, track PID/port.
- `seon.flow.proxy` — Flow process that wraps a remote agent JVM. `transform` sends forms via nREPL, receives results.
- **deps.edn generation** — Given a namespace, query knowledge graph for transitive deps, generate `:replace-deps` map. Only load what's needed.

---

## Existing Research to Reference

- `docs/archive/agent-isolation/research/complete-isolation.md` — Earlier research on JVM isolation. Assumed XTDB (expensive). The lightweight-DB approach explored here changes the numbers dramatically.
- `src/seon/experimental/ns_instance.clj` — Namespace cloning prototype (historical — file no longer exists). Still useful as reference for understanding var resolution, but the separate-JVM approach makes this unnecessary.
- `src/seon/web/sse/flow.clj` — Proven core.async.flow pattern (aggregator → broadcaster). Use as template for agent proxy flow nodes.
- `src/seon/ai/claude/sdk.clj` — Already spawns subprocesses (Claude Code CLI). Pattern for process lifecycle management.

---

## Open Questions

1. **Startup time** — We measured RSS but not startup. Run the prototype and check the `AGENT_READY startup_ms=` output. Expected: 2-4s.

2. **nREPL eval latency** — How much overhead does nREPL add per eval? For interactive agent work, <10ms per eval is fine.

3. **Cross-JVM DB access from agent JVM** `[JVM track — paused]` — Originally planned as a Datalevin client connection; on the JVM track (embedded Datahike), this needs to route through the orchestrator's flow topology instead. Not yet verified end-to-end. (The active CLJS pod track instead centralizes writes in `wire-server` over a Unix socket.)

4. **Per-namespace deps generation** — How do we automatically determine what deps a namespace needs? clj-kondo analysis gives us `require` chains, but mapping those to Maven coordinates needs work.

5. **Hot code reload in agent JVM** — Without the dev hook, how does the agent reload code? Just re-eval via nREPL? Or do we need a lightweight reload mechanism?

---

## Startup Optimization Research

**Date:** 2026-02-14
**Baseline measurements** (Java 21.0.5 Temurin, macOS ARM64, M-series):

| Metric | Value | Notes |
|--------|-------|-------|
| Wall-clock startup | **3,484ms** | From process start to port listening |
| Internal startup_ms | **26-30ms** | From `-main` entry to AGENT_READY |
| RSS memory | **186 MB** | Consistent across runs |
| Warm vs cold | No difference | ~3.5s both times (OS disk cache helps) |
| `clojure` CLI overhead | ~200ms | Classpath computation; cacheable |
| JVM + Clojure loading | ~3.3s | The real bottleneck |

The 3.3s breaks down roughly as: JVM bootstrap (~300ms) + Clojure runtime init (~1s) + loading nrepl/malli/core.async/timbre/cheshire (~2s).

### Ranked Optimization Techniques

#### 1. Pre-warmed JVM Pool (BEST: eliminates startup from critical path)

**How:** Keep 2-3 agent JVMs pre-started and idle. They boot with Clojure runtime + nREPL + core deps loaded but no specific namespace. When a task arrives, send an nREPL eval to `(require 'seon.target.ns)` and assign work.

**Realistic improvement:** Effective 0ms startup (50-100ms for namespace require). The 3.5s cost is paid once at pool creation, not per-agent.

**Memory cost:** ~186MB per idle JVM. With 3 standby agents = 558MB. On a 16GB machine with 10.6GB available, this is 5% of agent budget.

**Implementation complexity:** LOW. The launcher already spawns JVM processes. Add a pool manager that keeps N warm, assigns on demand, replenishes after use.

**REPL compatible:** YES. Full nREPL, full dynamic Clojure.

**Downsides:** Memory for idle JVMs. Need a replenishment strategy (spawn replacement after assigning one). Slightly more complex orchestrator code.

**Verdict: Implement this first. It completely eliminates the startup latency problem.**

#### 2. AOT Compilation + Uberjar (GOOD: ~50% reduction)

**How:** Use `tools.build` to AOT-compile `seon.flow.agent-runner` and its dependency tree into bytecode. Package as an uberjar. Skip `clojure` CLI classpath computation.

**Realistic improvement:** 3.5s down to ~1.5-2.0s. AOT avoids recompiling Clojure source to bytecode at boot. The uberjar avoids classpath resolution.

**Implementation complexity:** LOW. Add a `build.clj` target, `(:gen-class)` is already in agent_runner.clj. One-time build step.

**REPL compatible:** YES. AOT only affects initial load. nREPL still works, `require :reload` still works. Direct-linking is separate (see below).

**Downsides:** Need to rebuild uberjar when agent deps change. Stale AOT classes can cause subtle bugs if source changes without recompile.

**Verdict: Easy win. Combine with AppCDS for maximum effect.**

#### 3. AppCDS (Application Class Data Sharing) (GOOD: additional ~30% on top of AOT)

**How:** Java 21 supports dynamic CDS archives. Run the agent once with `-XX:ArchiveClassesAtExit=agent.jsa`, then use `-XX:SharedArchiveFile=agent.jsa` on subsequent runs. The JVM maps pre-parsed class metadata from disk instead of re-parsing thousands of .class files.

**Realistic improvement:** ~30% reduction on top of AOT. Combined AOT+AppCDS: 3.5s down to ~0.8-1.2s.

**Implementation:**

```bash
# Training run (once, after building uberjar):
java -XX:ArchiveClassesAtExit=agent.jsa -jar target/agent.jar --port 9999
# Production runs:
java -XX:SharedArchiveFile=agent.jsa -jar target/agent.jar --port $PORT

```

**Implementation complexity:** LOW. Two extra JVM flags. The `.jsa` file is ~30-50MB and can be shared across all agent JVMs.

**REPL compatible:** YES. CDS only affects class loading, not runtime behavior.

**Downsides:** Need to regenerate `.jsa` when deps change. Training run must exercise typical code paths. Archive is platform-specific (can't share between x86/ARM).

**Verdict: Implement alongside AOT. The combination is the best non-pool approach.**

#### 4. Deferred Namespace Loading (MODERATE: ~400ms improvement)

**How:** Move heavy requires (malli, core.async, datahike) out of the top-level `ns` form. Use `requiring-resolve` or `delay` blocks. Print AGENT_READY after nREPL starts but before all deps are loaded. Background-load the rest.

**Current agent_runner.clj requires:** nrepl.server, taoensso.timbre, clojure.core.async, malli.core. Of these, malli and core.async are the heaviest. The DB client is already deferred via `requiring-resolve`.

**Realistic improvement:** ~400ms. The AGENT_READY signal fires faster, but the agent isn't fully functional until background loading completes.

**Implementation complexity:** LOW. Change `(:require ...)` to use `requiring-resolve` in delays.

**REPL compatible:** YES.

**Downsides:** First eval that touches malli/async will have a latency spike. Agent appears ready but isn't fully loaded. Adds complexity to reason about.

**Verdict: Worth doing if sub-second matters. The pre-warmed pool makes this less important.**

#### 5. Direct Linking (MINOR: ~3% startup, ~10% steady-state)

**How:** `-Dclojure.compiler.direct-linking=true` replaces Var indirection with static method calls. Requires AOT to take effect.

**Realistic improvement:** Negligible for startup (~3%). Better for steady-state execution (~5-15% faster hot paths).

**REPL compatible:** NO for redefined vars. If agent redefines a function in namespace A, callers in namespace B still see the old version. Use `^:redef` metadata on vars that need redefinability.

**Implementation complexity:** TRIVIAL (one JVM flag), but requires understanding the implications.

**Downsides:** Breaks the core REPL-driven workflow. Since our agents use nREPL to eval and iterate, this is a real problem. Functions defined at startup are fine, but anything the agent redefines at runtime won't propagate through direct-linked call sites.

**Verdict: Skip for agent JVMs. The REPL incompatibility is a dealbreaker for our use case. Could use for a hypothetical "production frozen" agent mode later.**

#### 6. Tiered Compilation Tuning (ALREADY OPTIMAL)

**Current:** `-XX:TieredStopAtLevel=1` (C1 compiler only, no C2 optimization).

This is correct for our use case. Agent JVMs are relatively short-lived and startup-sensitive. C2 compilation takes CPU time and doesn't help until code has run thousands of times.

`-XX:+UseCompressedOops` is enabled by default on 64-bit JVMs with heaps under 32GB, so no change needed.

`-XX:+UseSerialGC` is correct for small heaps (256MB). G1/ZGC overhead isn't justified.

**Verdict: No changes needed. Current flags are optimal.**

#### 7. GraalVM JIT (NOT RECOMMENDED for now)

**How:** GraalVM CE for JDK 21 includes the Graal JIT compiler (LibGraal), which can replace the C2 compiler. Enable with `-XX:+UseJVMCICompiler`.

**Startup impact:** Minimal. The Graal JIT helps steady-state performance, not startup. It actually adds ~100ms to startup due to loading the JVMCI compiler.

**Native Image:** Would give sub-100ms startup but is incompatible with nREPL (reflection, dynamic class loading, runtime compilation). Not viable for our REPL-driven agents.

**Implementation complexity:** MODERATE. Need to install GraalVM JDK instead of Temurin. Classpath and dep compatibility can be fiddly.

**Verdict: Not worth it. We're using TieredStopAtLevel=1 so C2/Graal JIT never kicks in anyway. If agents become long-lived compute-heavy processes, revisit.**

#### 8. Project Leyden (FUTURE: not ready)

Java 24+ has early-access "condensers" that pre-compute class initialization and JIT profiles at build time. This could theoretically give Clojure sub-500ms startup without AOT/GraalVM.

**Status as of 2026:** Still in development. JDK 24 has preliminary support but it's not stable for production use, and Clojure's dynamic class loading patterns are among the harder cases for Leyden to handle.

**Current verdict:** the forecast date has passed; Seon now targets Java 26,
while process isolation remains a deployment boundary rather than a reason to
revive the retired JVM agent application.

### Recommended Implementation Plan

**Phase 1 (this week): Pre-warmed JVM pool**

- Modify `seon.flow.launcher` to maintain a pool of 2-3 warm agent JVMs
- Each warm JVM has Clojure + nREPL + core deps loaded, awaiting namespace assignment
- Effective startup: ~50ms (just the namespace require)
- Memory cost: ~558MB for 3 standby agents

**Phase 2 (next week): AOT + AppCDS uberjar**

- Add `build.clj` target for agent uberjar with AOT
- Generate AppCDS archive as part of build
- Update `bin/agent-runner` to use uberjar + CDS
- Reduces cold start from 3.5s to ~1.0s (for when pool is exhausted)

**Phase 3 (if needed): Deferred loading**

- Move malli/core.async to lazy requires
- Only worth doing if Phase 1+2 aren't sufficient

**Combined effect:** Pool gives instant startup for normal load. AOT+AppCDS gives ~1s startup when pool needs replenishment. Total agent capacity unchanged (186MB each).

---

## Phase 1 Results: Pre-Warmed JVM Pool (Prototype)

**Date:** 2026-02-14
**Implementation:** `src/seon/flow/pool.clj`

### Measured Performance

| Operation | Time | Notes |
|-----------|------|-------|
| **Cold start** (baseline) | 3,484ms | `./bin/agent-runner` from scratch |
| **Pool creation** (2 JVMs) | ~6.5s | Sequential spawn, one-time cost |
| **First acquire** (fresh namespace) | **158ms** | create-ns + refer-clojure + require malli/async/json + eval forms |
| **Re-acquire** (deps already loaded) | **6ms** | Namespace create + form eval only |
| **Bare nREPL eval** (no requires) | **11ms** | create-ns + refer-clojure + require + defn + validate |
| **Subsequent evals** (same JVM) | **<1ms** | Everything cached |
| **Release** (reset namespace) | <5ms | remove-ns + in-ns back to warm |

### Memory

| JVM | RSS |
|-----|-----|
| Pool JVM (idle) | 186-190 MB |
| Pool JVM (after use) | 190-195 MB |
| 3-JVM pool total | ~570 MB |

### Speedup Summary

| Scenario | Cold Start | Warm Pool | Speedup |
|----------|-----------|-----------|---------|
| First task | 3,484ms | 158ms | **22x** |
| Subsequent tasks (same JVM) | 3,484ms | 6ms | **580x** |

### How It Works

1. `create-pool!` spawns N agent JVMs via `clojure -M:agent`, each on a unique port
2. Each JVM starts with Clojure + nREPL + malli/core.async/cheshire/timbre loaded
3. `acquire!` picks an idle JVM, connects via nREPL, creates namespace, evals forms
4. `release!` removes the namespace and returns JVM to idle state
5. `dispose!` kills a JVM and spawns a replacement

### API

```clojure
(require '[seon.flow.pool :as pool])

(def p (pool/create-pool! {::pool/size 3 ::pool/base-port 7900}))

(def agent (pool/acquire! p {::pool/namespace 'seon.trading.signals
                              ::pool/forms ['(defn ema [period data] ...)]}))
;; => {::pool/port 7900 ::pool/pid 12345 ::pool/setup-ms 158 ...}

(pool/release! p agent)   ; recycle
(pool/dispose! p agent)   ; kill + respawn
(pool/pool-status p)      ; {::pool/idle 2 ::pool/active 1 ...}
(pool/shutdown! p)        ; kill all

```

### Open Items for Production

1. **Parallel pool creation** -- spawn JVMs concurrently to cut pool startup from 6.5s to ~3.5s
2. **Auto-replenishment** -- when a JVM is acquired, spawn a replacement in the background
3. **Health checks** -- periodically ping idle JVMs to detect crashes
4. **Integrant component** -- wrap pool as a system component for clean lifecycle
5. **Concurrency safety** -- current acquire! has a race condition (two callers could get same JVM); use compare-and-swap or a queue
