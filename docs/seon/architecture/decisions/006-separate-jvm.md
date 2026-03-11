---
type: decision
status: implemented
date: 2026-02-14
---

# ADR 006: Separate JVM Processes for Agent Isolation

## Context

The original agent isolation plan proposed creating namespace clones (e.g., `seon.trading.signals.a13b`) within a shared JVM, with a Super REPL that rewrites `::keywords` and manages per-instance Malli registries. Sean asked: "could an orchestrator treat it as a process it's spinning up and feeding data to?"

## Decision

**Separate JVMs per agent.** Each agent gets its own JVM process with isolated nREPL, Malli registry, and Datalevin client connection. The namespace cloning approach is abandoned.

## Rationale

### Problems That Disappear

| Problem | Shared JVM (Complex) | Separate JVM (Free) |
|---------|----------------------|---------------------|
| `::keyword` resolves to instance NS | AST walk + rewrite | Each JVM IS the real namespace |
| Malli global registry conflicts | Per-instance local registries | Each JVM has its own registry |
| `defn` clobbering between agents | Namespace clones via `create-ns` | Separate memory spaces |
| Agent crashes orchestrator | Can't prevent OOM/System.exit | OS-level isolation |
| Privilege separation | Not possible | Agent JVM only has its deps |

### Measured Results

| Metric | Agent JVM | Main Seon JVM | Ratio |
|--------|-----------|---------------|-------|
| RSS Memory | 186 MB | 3,409 MB | 18x smaller |
| Deps count | 7 | 30+ | Much fewer |
| JVM flags | 4 basic | 10+ | Simpler |

Pre-warmed JVM pool eliminates startup latency:

- Cold start: 3,484ms
- Pool acquire (first use): 158ms (22x faster)
- Pool acquire (subsequent): 6ms (580x faster)

### Architecture

- Agent JVMs connect to Datalevin server over TCP (port 8898) -- no local database needed
- nREPL over TCP is the transport layer (already proven by MCP server)
- `seon.flow.pool` manages claim!/release-session! lifecycle
- Pool of 2-3 pre-warmed JVMs at ~186MB each

## Consequences

**Benefits:**

- True OS-level isolation between agents
- 18x smaller memory footprint per agent (~57 concurrent agents possible on 16GB)
- No namespace cloning complexity
- Agents can `add-libs` at runtime for dynamic deps
- Clean crash semantics -- agent dies, process dies, resources freed

**Costs:**

- ~570MB for 3-JVM warm pool
- Cross-JVM communication adds nREPL latency (~11ms per eval)
- Pool management complexity (replenishment, health checks, concurrency safety)
