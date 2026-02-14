# PRD Plan: Super REPL — Federated Agent Runtime

## Context

Seon agents share a single JVM, meaning `defn` in the same namespace clobbers between agents. The original plan proposed namespace cloning with `::keyword` rewriting and per-instance Malli registries — but a separate-JVM exploration (`docs/ideas/separate-jvm-exploration.md`) proved that **separate JVMs eliminate all three hard problems for free**:

- `::keyword` resolves correctly — each JVM IS the real namespace
- Malli registry — each JVM has its own. No conflicts.
- `defn` clobbering — separate memory spaces. Impossible.
- Bonus: OS-level crash isolation, privilege separation, 186MB per agent

A pre-warmed JVM pool is already prototyped (`src/seon/flow/pool.clj`) with **158ms first acquire, 6ms subsequent, 57 concurrent agents on 16GB**.

The Super REPL becomes simpler: a form router that sends code to agent JVMs via nREPL, stores forms in Datalevin, runs analysis, and manages graduation to disk. No AST rewriting needed.

## Prerequisites (Do Not Proceed Without)

| Prerequisite | PRD | Key Remaining Work |
|-------------|-----|-------------------|
| **Datalevin as primary DB** | [`datalevin-migration`](docs/prds/datalevin-migration/prd.md) | Phase 0.3 (Schema Compiler), Phase 2 (Read Migration) |
| **Observatory on Datalevin** | [`namespace-ui`](docs/prds/namespace-ui/prd.md) Phase 1b.10 | Switch reads from XTDB → Datalevin |

## Architecture

```
Orchestrator JVM (3.4GB)
├── Datalevin Server (port 8898)
├── nREPL (port 7888)
├── HTTP/SSE Server (port 8080)
├── core.async.flow topology
│   ├── NS Owner: trading.*     (supervisor flow node)
│   ├── NS Owner: web.agents    (supervisor flow node)
│   └── Agent Proxy: .a13b      (flow node → nREPL to agent JVM)
├── Knowledge Graph (Datalevin)
├── Super REPL (form router)
└── JVM Pool Manager
    ├── Warm JVM #1 (186MB, idle, port 7900)
    ├── Warm JVM #2 (186MB, idle, port 7901)
    └── Warm JVM #3 (186MB, idle, port 7902)

Agent JVM (186MB each)
├── Clojure 1.12 + nREPL
├── Malli (own global registry)
├── core.async
├── Datalevin CLIENT (→ orchestrator:8898)
├── *ctx* atom (Datalevin-backed)
└── The real namespace (seon.trading.signals)
    ├── All defns live here
    ├── ::keywords resolve correctly
    └── Specs register in this JVM's Malli registry
```

### Core Concepts

**Super REPL**: A form router in the orchestrator. Receives forms from Claude Code agents (via MCP), sends to the right agent JVM via nREPL, stores forms in Datalevin, runs clj-kondo analysis. No AST rewriting — the agent JVM IS the real namespace.

**Agent JVM**: A minimal Clojure process (7 deps, 186MB). Connects to orchestrator's Datalevin server as a client. Has its own nREPL. The agent namespace is the REAL namespace — `::keywords`, Malli specs, and `defn`s all work naturally.

**JVM Pool**: Pre-warmed JVMs with Clojure + nREPL + core deps loaded. Acquire assigns a namespace, release recycles. Already prototyped — 158ms first acquire.

**Knowledge Graph**: Datalevin database of all namespaces, functions, specs, dependencies. Built from clj-kondo analysis. Updated incrementally as agents eval forms.

**Namespace Owner**: A core.async.flow process in the orchestrator (not an AI agent). Monitors error/health channels from agent proxy nodes. Triggers remediation agents when thresholds exceeded.

**Graduation**: When an agent's work is ready to merge, the Super REPL generates the namespace file from Datalevin-stored forms, writes to disk, git commits, reloads the orchestrator's copy, and verifies tests.

## Phases (Ordered for Incremental Testing)

### Phase 1: Agent JVM Pool (Production-Ready)
**Goal**: Reliable, production-ready agent JVM pool with lifecycle management.

**Already prototyped** in `src/seon/flow/pool.clj` and `src/seon/flow/agent_runner.clj`.

#### Phase 1a: Core Pool (DONE)
- [x] Fix concurrency: `acquire!` race condition → `LinkedBlockingQueue` for thread-safe acquisition
- [x] Parallel pool creation: spawn JVMs concurrently via futures
- [x] Auto-replenishment: when a JVM is acquired, spawn replacement in background via `replenish-pool!`
- [x] Health checks: periodic nREPL ping via `ScheduledExecutorService`, replaces unhealthy JVMs
- [x] Integrant component: `:seon/agent-pool` with suspend/resume for `(reset)` survival
- [x] Datalevin URI: passed to agent JVMs via `--datalevin-uri` CLI arg
- [x] Port allocation: atomic via `swap-vals!` to prevent race conditions
- [x] Unit tests + integration tests in `test/seon/flow/pool_test.clj`
- [x] Config in `system.edn` with `#ig/ref :seon/datalevin-server` dependency

#### Phase 1b: Production Hardening (DONE)
Three issues discovered during Integrant lifecycle testing:

**1b.1: Non-blocking pool startup**
- [x] `create-pool!` returns immediately with empty pool, JVMs spawn in background futures
- [x] `::warming?` flag in pool state atom, initially `true`, set to `false` when all JVMs spawned
- [x] `pool-warming?` and `await-warm` helper functions for callers
- [x] `pool-status` includes `::warming?` in response
- [x] Logs "Pool warming in background" at creation, "Pool ready" when warm

**1b.2: Blocking acquire with timeout**
- [x] `acquire!` accepts optional `::timeout-ms` -- uses `.poll(timeout, TimeUnit/MILLISECONDS)`
- [x] Without `::timeout-ms`, behavior is identical to before (non-blocking `.poll()`)
- [x] `acquire!!` convenience function blocks indefinitely via `.take`
- [x] Extracted `activate-jvm!` helper to avoid duplication between acquire! and acquire!!

**1b.3: Stale process cleanup on startup**
- [x] `cleanup-stale-agents!` checks TCP ports, finds PIDs via `lsof`, kills with `kill`
- [x] Safety check: only operates on ports in 7900-7999 range
- [x] Called at start of `create-pool!` before spawning any JVMs
- [x] 16 unit tests, 51 assertions, 0 failures (10 new tests for Phase 1b)

**Existing code**:
- `src/seon/flow/pool.clj` — Current pool implementation
- `src/seon/flow/agent_runner.clj` — Agent JVM entry point
- `bin/agent-runner` — Launch script
- `deps.edn` `:agent` alias — 7-dep minimal classpath
- `test/seon/flow/pool_test.clj` — Unit + integration tests

**Test (Phase 1b)**:
- Start Seon, verify pool warms in background (HTTP server available before pool ready)
- Kill Seon hard (`kill -9`), restart, verify stale agent JVMs cleaned up and new pool starts
- Exhaust pool, call `acquire!` with timeout, verify it blocks then returns when JVM released
- All existing tests still pass

### Phase 2: Knowledge Graph Foundation
**Goal**: Every namespace, function, spec, and dependency queryable in Datalevin.

**Build**:
- `seon.graph.analyzer` — Full project analysis:
  - Initial: `clj-kondo/run!` on `src/` with `:analysis true` (full project graph at startup)
  - Incremental: stdin analysis per form (proven in `seon.dev.lint`)
  - Extract: namespace-definitions, var-definitions, var-usages, namespace-usages
- `seon.graph.ingest` — Transform analysis → Datalevin entities:
  - Namespace entities (name, file, doc, status)
  - Function entities (name, ns, args, return spec, public?, line)
  - Dependency edges (from-ns, to-ns, type)
  - Var usage edges (from-fn, to-fn, line)
- `seon.graph.query` — Datalog query API:
  - `functions-by-spec` — "What functions accept this input shape?"
  - `dependents-of` — "Who depends on this namespace?"
  - `call-graph` — "What does this function call?"
  - `namespace-health` — Error rates, test failures

**Existing code**:
- `seon.dev.lint` — clj-kondo integration, stdin analysis via `with-in-str`
- `seon.dev.analysis` — File analysis functions
- `seon.ns.introspect` — Runtime namespace reflection
- `seon.schema` — Malli registry introspection

**Test**:
- Run full project analysis. Query "what depends on `seon.ai.claude`?" → verify complete dependency chain.
- Modify a function. Run incremental analysis. Verify graph updated correctly.
- Query "what functions accept `:seon.trading/position`?" → correct results.

### Phase 3: Super REPL Core
**Goal**: Agents eval forms through the Super REPL. Forms stored in Datalevin. Analysis runs automatically.

**Build**:
- `seon.repl.super` — Form routing middleware:
  - Receive form from agent (via MCP eval)
  - Route to agent's JVM via nREPL
  - Classify form type (defn, def, spec-registration, ns-declaration, expression)
  - Store source forms in Datalevin with metadata:
    ```clojure
    {:form/id (uuid)
     :form/namespace :seon.trading.signals
     :form/type :defn
     :form/name "ema"
     :form/source "(defn ema [period data] ...)"
     :form/agent-id "a13b"
     :form/version 3
     :form/analysis {...}
     :form/created-at (inst)}
    ```
  - Run clj-kondo stdin analysis on the form
  - Update knowledge graph with new/changed entities
  - Return eval result + any analysis warnings
- `seon.repl.graduate` — Graduation:
  - Query Datalevin for all current forms in a namespace (latest version each)
  - Assemble: ns-declaration + specs + functions (Rails-like convention)
  - Write to disk as `.clj` file
  - Git commit
  - Reload in orchestrator
  - Verify tests pass
  - Clean up agent JVM
- **MCP integration** — Modify `bin/mcp-server`:
  - If agent has pool JVM → route through Super REPL
  - If orchestrator → direct eval (current behavior)

**Existing code**:
- `bin/mcp-server` — MCP eval handler (Babashka, already routes by session)
- `seon.dev.lint` — clj-kondo stdin, edamame parsing
- `seon.graph.ingest` — Knowledge graph updates (Phase 2)

**Test**:
- Agent evals `(defn ema ...)` → form stored in Datalevin → knowledge graph updated.
- Agent modifies function → new version stored → old version preserved.
- Graduate namespace → file generated → `git diff` shows correct changes → tests pass.

### Phase 4: Agent-as-Flow-Node
**Goal**: Agent JVMs wrapped as core.async.flow processes with supervision.

**Build**:
- `seon.flow.proxy` — Flow process wrapping a remote agent JVM:
  - `describe`: `{:ins {:commands :messages} :outs {:results :errors :health}}`
  - `init`: Acquire from pool, assign namespace, setup ctx
  - `transform`: `[state [port value]] → [new-state {port [values]}]`
    - `:commands` port → eval form via nREPL on agent JVM
    - Capture errors → emit on `:errors` port
    - Periodic health → emit on `:health` port
  - Agent JVM knows nothing about flow — it just responds to nREPL evals
- `seon.flow.supervisor` — Namespace owner as flow process:
  - Monitors `:errors` and `:health` channels from proxy nodes
  - Tracks metrics (error rate, latency, throughput)
  - Triggers remediation: launch new agent when errors exceed threshold
  - Scales: add proxies when demand grows, release when idle
- `seon.flow.topology` — Flow graph management:
  - Register namespace owners
  - Wire proxy nodes to owners
  - Wire owners to each other (using dependency edges from knowledge graph)
  - Expose topology for Observatory visualization

**Existing code**:
- `seon.web.sse.flow` — Proven flow step function patterns
- `seon.ai.agent` — Agent registry
- `seon.ai.claude.sdk` — Process lifecycle management patterns

**Test**:
- Launch 2 agent proxy nodes. Verify forms flow through correctly.
- Inject error on one agent. Verify supervisor receives it and responds.
- Supervisor spawns remediation agent. Verify it acquires from pool and starts working.
- View topology in Observatory.

### Phase 5: Dynamic Cockpit via MCP
**Goal**: Agents query the knowledge graph for live, contextual information.

**Build**:
- New MCP tools in `bin/mcp-server`:
  - `query-graph` — Datalog queries against knowledge graph
  - `namespace-health` — Error rates, test status, recent failures
  - `agent-status` — Running agents, tasks, JVM ports
  - `suggest-context` — Task-aware: "you're writing specs → here are related schemas and consumers"
- Enhanced AGENT.md telling agents about these tools

**Test**:
- Agent calls `query-graph` to find functions matching a spec. Gets accurate results.
- Agent calls `namespace-health` to check if a dependency is healthy before depending on it.

### Phase 6: Inter-Agent Messaging
**Goal**: Typed, schema-validated messages between agents via flow channels and Datalevin.

**Build**:
- Message schemas: `:seon.msg/feature-request`, `:seon.msg/bug-report`, `:seon.msg/status-update`
- `seon.flow.mailbox` — Per-namespace message queue (Datalevin-backed)
- MCP tools: `check-mailbox`, `send-message`
- Supervisor routes messages between namespace owners

**Test**: Agent A sends feature request to B's namespace → supervisor evaluates → spawns agent → implemented → result flows back.

### Phase 7: Graph REPL (Future)
- Parse nested cross-namespace forms into execution DAGs
- Spec validation at namespace boundaries
- Multi-language dispatch (Python via libpython-clj, JS via GraalJS)

## Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `docs/prds/super-repl/prd.md` | Create | Full PRD from this plan |
| `src/seon/flow/pool.clj` | Modify | Production-ready pool (concurrency, health, Integrant) |
| `src/seon/flow/agent_runner.clj` | Modify | Datalevin client verification |
| `src/seon/graph/analyzer.clj` | Create | clj-kondo project + stdin analysis |
| `src/seon/graph/ingest.clj` | Create | Analysis → Datalevin entities |
| `src/seon/graph/query.clj` | Create | Datalog query API |
| `src/seon/repl/super.clj` | Create | Form routing, storage, analysis |
| `src/seon/repl/graduate.clj` | Create | Datalevin forms → file → git |
| `src/seon/flow/proxy.clj` | Create | Flow node wrapping agent JVM |
| `src/seon/flow/supervisor.clj` | Create | Namespace owner flow process |
| `src/seon/flow/topology.clj` | Create | Flow graph management |
| `src/seon/flow/mailbox.clj` | Create | Inter-agent messaging |
| `bin/mcp-server` | Modify | Route eval through Super REPL, add query tools |

## Existing Code to Leverage

| File | What We Reuse |
|------|---------------|
| `src/seon/flow/pool.clj` | JVM pool prototype (acquire!, release!, dispose!) |
| `src/seon/flow/agent_runner.clj` | Agent JVM entry point |
| `src/seon/dev/lint.clj` | clj-kondo stdin analysis, edamame parsing |
| `src/seon/dev/analysis.clj` | File analysis functions |
| `src/seon/ns/introspect.clj` | Runtime namespace reflection |
| `src/seon/schema.clj` | Malli registry, validation patterns |
| `src/seon/web/sse/flow.clj` | core.async.flow step function patterns |
| `src/seon/ai/claude/sdk.clj` | Process lifecycle management |
| `src/seon/orchestrator/session.clj` | Session lifecycle, Datalevin wiring |
| `src/seon/agent/ctx.clj` | Persisted context atoms |
| `src/seon/ai/agent.clj` | Agent registry, Observatory API |
| `src/seon/db/datalevin/conn.clj` | Datalevin connection manager |
| `bin/mcp-server` | MCP eval handler (session routing pattern) |

## Verification Plan (Incremental)

Each phase is independently testable:

1. **Pool**: Spawn 3 agents, same namespace, independent defns, no clobbering. Kill one, verify respawn.
2. **Knowledge Graph**: Full analysis → query dependencies → correct graph. Incremental update works.
3. **Super REPL**: Eval form → stored in Datalevin → knowledge graph updated. Graduate → correct file.
4. **Flow Nodes**: Proxy wraps agent JVM → forms flow through → errors route to supervisor.
5. **Cockpit**: Agent queries knowledge graph → discovers functions → uses them correctly.
6. **Messaging**: Agent A → message → B's supervisor → remediation agent → result.
7. **Observatory**: Full topology visible — JVM pool status, proxy nodes, supervisors, message flow.
