# PRD: Super REPL — Federated Agent Runtime

> **Evolution note:** The "Super REPL" concept has been realized and evolved into `seon.repl` (namespace rename pending). The flow-based agent isolation described here is now part of the **Unified Flow System** — see [`docs/prds/unified-flow/design.md`](../unified-flow/design.md) for the current architecture. This PRD is retained for historical context.

## Context

Seon agents share a single JVM, meaning `defn` in the same namespace clobbers between agents. Separate JVMs eliminate all three hard problems:

- `::keyword` resolves correctly — each JVM IS the real namespace
- Malli registry — each JVM has its own. No conflicts.
- `defn` clobbering — separate memory spaces. Impossible.
- Bonus: OS-level crash isolation, privilege separation, 186MB per agent

A pre-warmed JVM pool is built (`src/seon/flow/pool.clj`) with **158ms first acquire, 6ms subsequent, 57 concurrent agents on 16GB**.

The Super REPL is a form router: sends code to agent JVMs via nREPL, stores forms in Datalevin, runs analysis, manages graduation to disk. No AST rewriting needed.

## Agent Protocol

**Every agent implementing a phase MUST follow this protocol:**

1. **Read the full phase section** before writing any code. Understand the goal, files, existing code to reuse, and checklists.
2. **Reuse existing code** — functions listed in "Existing Code to Reuse" are proven and tested. Call them, don't rewrite them.
3. **Run all tests before committing**: `clojure -M:test -m kaocha.runner`
4. **Commit your work** with a descriptive message matching the template in your phase section.
5. **Update this PRD**: mark checklist items `[x]` as you complete them. Add notes about gotchas under your phase section.

## Architecture

```
Orchestrator JVM (3.4GB)
├── Datalevin Server (port 8898)
├── nREPL (port 7888)
├── HTTP/SSE Server (port 8080)
├── HTTP Server (port 8080) — thin proxy for /ns/ routes
│   ├── Static pages (dashboard, agents, flow monitor) — rendered in main JVM
│   └── /ns/:namespace — proxied through flow topology via topology/request!
│       └── Agent JVM returns domain data via TCP → orchestrator resolves renderer
├── core.async.flow topology (Phase 4)
│   ├── :ns/seon.test.alpha     (namespace-step process)
│   │   ├── :seon.flow.in/request   ← cross-ns calls
│   │   ├── :seon.flow.out/reply    → responses
│   │   ├── :seon.flow.out/error    → error monitor
│   │   ├── :seon.flow.out/event    → observability events
│   │   ├── ::flow/in-ports  ← TCP ← agent JVM replies
│   │   └── ::flow/out-ports → TCP → agent JVM requests
│   ├── :seon.flow/reply-router  (delivers promises for blocking callers)
│   └── :seon.flow/error-monitor (tracks rates, emits events)
├── Knowledge Graph (Datalevin)
├── Super REPL (form router — Phase 3, not yet built)
└── JVM Pool Manager
    ├── Warm JVM #1 (186MB, idle, port 7900)
    ├── Warm JVM #2 (186MB, idle, port 7901)
    └── Warm JVM #3 (186MB, idle, port 7902)

Agent JVM (186MB each)
├── Clojure 1.12 + nREPL (code loading only)
├── Malli (own global registry)
├── core.async mini-flow (Phase 4)
│   └── :seon.flow/bridge (bridge-step)
│       ├── ::flow/in-ports  ← TCP ← orchestrator requests
│       └── ::flow/out-ports → TCP → orchestrator replies
├── Datalevin CLIENT (→ orchestrator:8898)
├── *ctx* atom (Datalevin-persisted, SSE watcher for live UI push)
│   ├── Serializable keys saved via harness/persist-ctx!
│   ├── add-watch triggers render-and-push! to SSE clients
│   └── See datalevin-migration PRD Phase 3 for unified ctx spec
└── The real namespace (seon.trading.signals)
    ├── All defns live here
    ├── ::keywords resolve correctly
    └── Specs register in this JVM's Malli registry
```

### Core Concepts

**Datalevin as Source of Truth**: The Seon Datalevin database is the single source of truth for all code metadata — namespaces, functions, specs, and their relationships. Agent JVMs are ephemeral workspaces. When an agent's work is ready, it gets ingested into Datalevin and optionally graduated to disk. The system always queries Datalevin to understand what code exists, never runtime registries or file scans.

**Super REPL**: A form router in the orchestrator. Receives forms from Claude Code agents (via MCP), sends to the right agent JVM via nREPL, stores forms in Datalevin, runs analysis and updates the code index.

**Agent JVM**: A minimal Clojure process (7 deps, 186MB). Connects to orchestrator's Datalevin server as a client. Has its own nREPL.

**JVM Pool**: Pre-warmed JVMs with Clojure + nREPL + core deps loaded. Acquire assigns a namespace, release recycles. Already built — 158ms first acquire.

**Knowledge Graph**: Datalevin database of all namespaces, functions, specs, dependencies. Built from clj-kondo analysis.

**Namespace Owner**: A core.async.flow process in the orchestrator (not an AI agent). Monitors error/health channels from agent proxy nodes.

**Graduation**: When an agent's work is ready to merge, the Super REPL generates the namespace file from Datalevin-stored forms, writes to disk, git commits, reloads the orchestrator.

### Data Architecture

**Three tiers of Datalevin storage:**

1. **Master DB** (`seon`) — Orchestrator-owned. Stores the knowledge graph (namespaces, functions, dependencies, call graph), session registry, and system-wide metadata. All graph queries in `seon.graph.query` run against this DB.

2. **Namespace DBs** (`seon.trading`, `seon.health`, etc.) — One per domain namespace, created lazily via `conn/get-namespace-conn!`. Agents working in a namespace get a connection to its DB. This is where domain-specific data lives long-term: workout records, trading positions, health metrics. Data persists across agent sessions — a new agent picking up `seon.health` can see everything previous agents stored there.

3. **Agent `*ctx*` atom** (backed by namespace DB) — Each agent instance has a `*ctx*` atom scoped to its instance ID (not just namespace). Loaded from and persisted to Datalevin via `harness/persist-ctx!` and `harness/load-ctx!`. Multiple agents can work in the same namespace — each gets its own instance ID and own `*ctx*`. See [`datalevin-migration` PRD Phase 3](../datalevin-migration/prd.md) for the unified ctx persistence spec.

**Cross-namespace discovery**: Agents can query the master knowledge graph to find code and data structures across the entire system. Example: an agent building `seon.health.calories` can search for existing functions that produce `:health/workout` data, find them in `seon.health.tracking`, and reuse them directly. The graph stores Malli schemas, function signatures, and dependency edges — agents should always search before building.

**Agent environment**: Every agent JVM loads `seon.agent.env` at startup. This namespace provides:
- `(env/search "calories")` — Search knowledge graph for matching functions/schemas
- `(env/ctx-save!)` / `(env/ctx-load!)` — Persist/restore `*ctx*` to namespace DB
- `(env/ns-conn)` — Get connection to the agent's namespace DB
- `(env/related-schemas :health/workout)` — Find schemas that share keys with a given schema

This namespace is the agent's "toolkit" — updating it updates all future agents.

### Everything is a Flow

The web server is a thin proxy — namespace content rendering goes through the flow topology, not direct function calls.

**How it works:**
- HTTP request to `/ns/seon.trading` hits the main JVM's web server
- Web server calls `topology/request!` targeting the `seon.trading` namespace step
- The namespace step forwards to the agent JVM via TCP
- Agent JVM executes domain functions locally, returns **data maps** (not hiccup)
- Data travels back via TCP reply; orchestrator resolves the appropriate renderer via spec-driven resolution (see [`spec-driven-rendering` PRD](../spec-driven-rendering/prd.md))

**What this enables:**
- **Backpressure** — Queue cap (default 32) rejects overload with typed `:overload` errors
- **Multi-instance load balancing** — Multiple agent JVMs can serve the same namespace (future)
- **Crash isolation** — Agent JVM crash doesn't take down the web server
- **Observability** — Every request/reply emits flow events for monitoring
- **No UI deps in agent JVMs** — Agent JVMs stay minimal; rendering lives in the orchestrator

**What stays in the main JVM:**
- Monitoring infrastructure pages (dashboard, agents, flow monitor)
- Static pages that don't depend on namespace content
- The flow topology itself and its wiring
- **Renderer resolution** — The orchestrator's Datalevin code index discovers render functions automatically from `:malli/schema` metadata. Agent JVMs return domain data; the orchestrator finds the best renderer by input key specificity. See [`spec-driven-rendering` PRD](../spec-driven-rendering/prd.md) for the full algorithm.

---

## Phase 1: Agent JVM Pool — COMPLETED

**Commit**: `297d6d7 feat: production-ready agent JVM pool with lifecycle management`

Production-ready agent JVM pool with:
- [x] `LinkedBlockingQueue` for thread-safe acquisition
- [x] Parallel pool creation via futures
- [x] Auto-replenishment when JVMs are acquired
- [x] Health checks via `ScheduledExecutorService`
- [x] Integrant component with suspend/resume
- [x] Non-blocking startup with `::warming?` flag
- [x] Blocking acquire with `::timeout-ms`
- [x] Stale process cleanup on startup
- [x] 16 unit tests, 51 assertions, 0 failures

**Key files**:
- `src/seon/flow/pool.clj` — Pool implementation
- `src/seon/flow/agent_runner.clj` — Agent JVM entry point
- `bin/agent-runner` — Launch script
- `test/seon/flow/pool_test.clj` — Tests

**Notes**: See `docs/prds/super-repl/notes.md` for Phase 1 gotchas.

---

## Phase 2: Knowledge Graph Foundation — COMPLETED

**Commit**: `c482e83 feat: knowledge graph foundation with Datalevin storage`

**Goal**: Make every namespace, function, and dependency in the project queryable via Datalevin.

### Files to Create

| File | Purpose |
|------|---------|
| `src/seon/graph/analyzer.clj` | Full project analysis + incremental per-form analysis |
| `src/seon/graph/ingest.clj` | Transform clj-kondo analysis → Datalevin entities |
| `src/seon/graph/query.clj` | Datalog query API for the knowledge graph |
| `test/seon/graph/analyzer_test.clj` | Tests for analyzer |
| `test/seon/graph/ingest_test.clj` | Tests for ingest |
| `test/seon/graph/query_test.clj` | Tests for query |

### Existing Code to Reuse

**`seon.dev.analysis` (`src/seon/dev/analysis.clj`)**:
- `analyze-file` — Parses a file with clj-kondo, returns `{::var-definitions [...] ::var-usages [...] ::namespace-usages [...]}`. Call with `{::analysis/file-path "src/seon/foo.clj"}`.
- `callees-of` — What does a function call? `{::analysis/analysis result ::analysis/fn-name 'my-fn}`
- `callers-of` — Who calls a function? Same signature pattern.
- Uses `clj-kondo/run!` with `:analysis true` for arglists, var-usages, var-definitions.

**`seon.dev.lint` (`src/seon/dev/lint.clj`)**:
- `lint-source` — Runs clj-kondo on a **string** via `with-in-str`. Call with `{::lint/content "(defn foo ...)" ::lint/file-path "optional.clj"}`. Returns `{::lint/valid? bool ::lint/findings [...]}`. Use this for incremental per-form analysis.
- `syntax-error?` — Fast check via edamame. `{::lint/content "..."}` → boolean.

**`seon.db.datalevin.conn` (`src/seon/db/datalevin/conn.clj`)**:
- `get-master-conn!` — Get connection to master DB. Call with `{::conn/manager manager}`. The manager comes from `(:seon/connection-manager integrant.repl.state/system)`.
- `get-namespace-conn!` — Get namespace-specific DB. `{::conn/manager manager ::conn/namespace 'seon.graph}`.

**Datalevin transact/query patterns (from `seon.ai.datalevin`)**:
```clojure
;; Require datalevin
(require '[datalevin.core :as d])

;; Transact entity maps
(d/transact! conn [{:graph/type :namespace
                    :graph/name "seon.ai.claude"
                    :graph/file "src/seon/ai/claude.clj"}])

;; Query with Datalog
(d/q '[:find ?name
       :where
       [?e :graph/type :namespace]
       [?e :graph/name ?name]]
     @conn)

;; Query with parameters
(d/q '[:find ?e ?name
       :in $ ?target-ns
       :where
       [?e :graph/type :var-usage]
       [?e :graph/to-ns ?target-ns]
       [?e :graph/name ?name]]
     @conn "seon.ai.claude")

;; Pull full entity
(d/pull @conn '[*] entity-id)
```

**`seon.ns.introspect` (`src/seon/ns/introspect.clj`)**:
- `introspect` — Runtime namespace reflection. Returns `{:functions [...] :vars [...] :requires {...}}`.
- `list-seon-namespaces` — All loaded `seon.*` namespaces.

### Build Checklist

- [x] **`seon.graph.analyzer`** — Two modes:
  - [x] `analyze-project!` — Runs `clj-kondo/run!` on `["src/"]` with `{:analysis {:arglists true :var-usages true :var-definitions true}}`. Returns full analysis map. Use this at startup for initial graph population.
  - [x] `analyze-form` — Runs clj-kondo via `with-in-str` on a single form string. Returns analysis data. Use this for incremental updates when agents eval forms.
  - [x] `extract-entities` — Takes raw clj-kondo analysis output, extracts structured data: `{:namespaces [...] :functions [...] :var-usages [...] :namespace-usages [...]}`. Transform shapes should match what `ingest` expects.

- [x] **`seon.graph.ingest`** — Transform analysis → Datalevin entities:
  - [x] `ingest-analysis!` — Takes extracted entities + Datalevin conn, transacts them. Entity shapes:
    - Namespace: `{:graph/type :namespace :graph/name "seon.foo" :graph/file "src/seon/foo.clj" :graph/doc "..." :graph/status :active}`
    - Function: `{:graph/type :function :graph/name "my-fn" :graph/ns "seon.foo" :graph/arglists ["[x y]"] :graph/public? true :graph/line 42}`
    - Dependency edge: `{:graph/type :ns-dependency :graph/from-ns "seon.foo" :graph/to-ns "seon.bar" :graph/alias "bar"}`
    - Var usage edge: `{:graph/type :var-usage :graph/from-ns "seon.foo" :graph/from-var "my-fn" :graph/to-ns "seon.bar" :graph/name "other-fn" :graph/line 55}`
  - [x] `ingest-incremental!` — Same as above but for a single form's analysis. Should upsert (replace existing entities for same function/namespace).

- [x] **`seon.graph.query`** — Datalog query API:
  - [x] `dependents-of` — "Who depends on namespace X?" Query `:graph/type :ns-dependency` where `:graph/to-ns` matches.
  - [x] `dependencies-of` — "What does namespace X depend on?" Query where `:graph/from-ns` matches.
  - [x] `call-graph` — "What does function X call?" Query `:graph/type :var-usage` where `:graph/from-var` matches.
  - [x] `callers-of` — "Who calls function X?" Query where `:graph/name` and `:graph/to-ns` match.
  - [x] `functions-in-ns` — "What functions are defined in namespace X?"
  - [x] `search-functions` — "Find functions matching a name pattern" (substring match).

### Test Checklist

- [x] Run `analyze-project!` on `src/` — returns analysis with >0 namespaces, >0 functions
- [x] Run `analyze-form` on `"(defn ema [period data] (reduce + data))"` — returns valid analysis
- [x] Ingest full project analysis → query `dependents-of "seon.graph.analyzer"` → returns namespaces that require it
- [x] Ingest incremental form → query confirms new function appears in graph
- [x] `functions-in-ns "seon.graph.analyzer"` returns `analyze-project!`, `analyze-form`, `extract-entities`
- [x] `call-graph` for a known function returns its callees
- [x] All tests pass: `clojure -M:test -m kaocha.runner` (2 pre-existing flaky failures in nrepl port-conflict test)

### Commit Message Template

```
feat: knowledge graph foundation with Datalevin storage

- seon.graph.analyzer: full project + incremental form analysis
- seon.graph.ingest: clj-kondo analysis → Datalevin entities
- seon.graph.query: dependency, call graph, and function search queries
- Tests for all three namespaces
```

---

## Phase 3: Super REPL Core + Agent Environment — PARTIALLY COMPLETE

**Goal**: Agents eval forms through the Super REPL. Forms are stored in Datalevin with versioning. Analysis runs automatically. Agent JVMs get namespace-scoped databases and a shared environment namespace.

**Current state**: The Super REPL form router (`seon.repl.super`) is built — form classification, routing, Datalevin storage, and versioning all work. Agent environment (`seon.agent.env`) is built with graph queries, ctx persistence, schema discovery. What's NOT done: graduation to disk, MCP routing, dynamic context suggestions, and agent_runner modifications.

### Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `src/seon/repl/super.clj` | Create | Form router: receive → route → store → analyze → respond |
| `src/seon/repl/graduate.clj` | Create | Assemble Datalevin forms → .clj file → git commit |
| `src/seon/agent/env.clj` | Create | Agent environment: graph queries, ctx persistence, discovery |
| `src/seon/flow/agent_runner.clj` | Modify | Load `seon.agent.env`, connect to namespace DB (not port-named DB) |
| `bin/mcp-server` | Modify | Route pool-assigned sessions through Super REPL |
| `test/seon/repl/super_test.clj` | Create | Tests |
| `test/seon/repl/graduate_test.clj` | Create | Tests |
| `test/seon/agent/env_test.clj` | Create | Tests |

### Existing Code to Reuse

**`seon.flow.pool` (`src/seon/flow/pool.clj`)**:
- `acquire!` / `acquire!!` — Get a warm JVM from pool. `(pool/acquire! pool {::pool/namespace 'seon.trading.signals})`. Returns `{::pool/port 7901 ...}`.
- `release!` — Return JVM to pool.
- `nrepl-eval!` — Eval code on agent JVM. `(pool/nrepl-eval! port "(+ 1 2)")`.
- `pool-status` — Check pool state.

**`seon.graph.ingest` (from Phase 2)**:
- `ingest-incremental!` — Update knowledge graph after each form eval.

**`seon.graph.analyzer` (from Phase 2)**:
- `analyze-form` — Get analysis for a single form string.

**`bin/mcp-server` routing pattern**:
The MCP server (`bin/mcp-server`) is a Babashka script. It routes `eval` calls by `session_id`:
- `session_id = "orchestrator"` → eval on port 7888 (orchestrator nREPL)
- `session_id = "a1b2"` (4-char hex) → looks up nREPL port via `seon.orchestrator.session/get-session-port` on the orchestrator, then evals on that port

**To add Super REPL routing**: Add a check in `execute-eval` (around line 433) — if the session has a pool-assigned JVM, route through Super REPL instead of direct nREPL eval. The Super REPL wraps the eval with form storage + analysis.

### Build Checklist

- [x] **`seon.repl.super`** — Form routing:
  - [x] `eval-form!` — Main entry point. Receives `{:form/source "(defn ema ...)" :form/namespace 'seon.trading.signals :form/agent-id "a13b"}`. Steps:
    1. Classify form type (defn, def, ns, require, expression) by parsing with edamame
    2. Route to agent JVM via `pool/nrepl-eval!`
    3. Store form in Datalevin:
       ```clojure
       {:form/id (random-uuid)
        :form/namespace "seon.trading.signals"
        :form/type :defn
        :form/name "ema"
        :form/source "(defn ema [period data] ...)"
        :form/agent-id "a13b"
        :form/version 3  ;; incremented per name+namespace
        :form/created-at (Instant/now)}
       ```
    4. Run `graph/analyze-form` on the source
    5. Run `graph/ingest-incremental!` with analysis results
    6. Return eval result + any analysis warnings
  - [x] `classify-form` — Parse form and return type. Use `edamame.core/parse-string` to get the form as data, check `(first form)` for `'defn`, `'def`, `'ns`, `'require`, etc.
  - [x] `current-forms` — Query Datalevin for all current forms in a namespace (latest version of each named form). Used by graduation.
  - [x] `form-history` — Query all versions of a specific form.

- [ ] **`seon.repl.graduate`** — Graduation to disk:
  - [ ] `graduate!` — Takes namespace symbol. Steps:
    1. Query `super/current-forms` for all forms
    2. Sort: ns-declaration first, then requires, then defs, then defns
    3. Assemble into a `.clj` file string with proper formatting
    4. Write to `src/{ns-path}.clj`
    5. Git commit with message "feat: graduate {namespace} from Super REPL"
    6. Reload in orchestrator via `(require 'ns :reload)`
    7. Return `{:file-path "..." :form-count N}`
  - [ ] `preview` — Same as graduate but returns the file content without writing.

- [x] **`seon.agent.env`** — Agent environment (loaded into every agent JVM):
  - [ ] Provides unified `*ctx*` per instance (not per namespace). Multiple agents in the same namespace each get their own instance ID and `*ctx*`.
  - [x] `search` — Query the knowledge graph from an agent JVM. Wraps `seon.graph.query/search-functions`. `(env/search {::env/conn conn ::env/pattern "calories"})` → matching functions across all namespaces.
  - [ ] `ns-conn` — Get this agent's namespace DB connection. Uses the namespace the agent was assigned (not a port-based throwaway DB).
  - [x] `ctx-save!` / `ctx-load` — Instance-scoped context persistence to Datalevin with `seon.ctx/*` schema. Round-trips EDN-serializable data keyed by instance-id.
  - [x] `related-schemas` — Find Malli schemas that share keys with a given schema. Queries the knowledge graph.
  - [x] `who-produces` — "What functions return data matching this shape?" Graph query via fn/output-spec refs.
  - [x] `who-consumes` — "What functions accept data matching this shape?" Graph query via fn/input-spec refs.

- [ ] **`agent_runner.clj` modifications**:
  - [ ] Creates instance-scoped ctx with shared `::conn` injection on startup. Each agent instance gets its own `*ctx*` atom, but the Datalevin `::conn` is shared per namespace.
  - [ ] Change Datalevin URI to use **namespace name** instead of port number. Currently: `agent-7901`. Should be: `seon.trading.signals` (the assigned namespace). This gives each namespace a persistent DB across agent sessions.
  - [ ] `(require 'seon.agent.env)` at startup so all agents have the toolkit available.
  - [ ] Wire `*ctx*` persistence: if namespace DB has a saved ctx for this instance, load it into `*ctx*` on startup.

- [ ] **Domain functions with `:malli/schema` metadata** — Agent JVM functions should have Malli schema annotations so the spec-driven scanner can discover them. Rendering is handled by the orchestrator, not the agent JVM. See [`spec-driven-rendering` PRD](../spec-driven-rendering/prd.md) for the render function convention (`.render` companion namespaces in the orchestrator).

- [ ] **`seon.repl.super` dynamic context**:
  - [ ] After `eval-form!` stores and analyzes a form, compute **relevant context** to return alongside the eval result. If the agent just defined a function that takes `:health/workout`, search the graph for other functions that produce or consume that shape. Return these suggestions in the eval response so the agent sees them without having to ask.
  - [ ] `suggest-context` — Given a form's analysis, query the graph for related functions, schemas, and namespace dependencies. Return a compact summary suitable for injecting into the agent's context.

- [ ] **`bin/mcp-server` modification**:
  - [ ] In `execute-eval`, add a branch: if session is a pool session (check via orchestrator query), route through `seon.repl.super/eval-form!` instead of raw nREPL eval
  - [ ] The routing check: eval code on orchestrator that checks if this session_id has a pool-assigned JVM. If yes, return port + super-repl flag.

### Test Checklist

- [ ] `eval-form!` with `(defn ema [period data] ...)` → form stored in Datalevin, knowledge graph updated, eval result returned
- [ ] `eval-form!` same function twice → version increments (v1, v2)
- [x] `current-forms` returns latest version of each form
- [x] `classify-form` correctly identifies defn, def, ns, require, expression
- [ ] `graduate!` a namespace with 3 forms → correct .clj file generated, git diff shows expected content
- [ ] `preview` returns file content without side effects
- [ ] `env/search` from agent JVM returns matching functions from knowledge graph
- [ ] `env/ctx-save!` + `env/ctx-load!` round-trip: save ctx, restart agent, load ctx, verify data
- [ ] Agent JVM connects to namespace DB (not port-named DB)
- [ ] `suggest-context` returns relevant functions when agent defines a function using known schema keys
- [ ] All tests pass: `clojure -M:test -m kaocha.runner`

### Commit Message Template

```
feat: Super REPL core with form routing, agent environment, and graduation

- seon.repl.super: form classification, routing, Datalevin storage, versioning, dynamic context
- seon.repl.graduate: namespace assembly and graduation to disk
- seon.agent.env: agent toolkit with graph queries, ctx persistence, cross-ns discovery
- agent_runner: namespace-scoped Datalevin DB, auto-load seon.agent.env
- MCP server routing for pool-assigned sessions
- Tests for form lifecycle, graduation, and agent environment
```

---

## Phase 4: Namespace Harness — Flow-Routed Agent Isolation — COMPLETED

**Commits**: `f477651` through `29adfbe` (6 commits)

**Goal**: Namespace-level process isolation via core.async.flow, with TCP socket channels as flow in-ports/out-ports bridging orchestrator and agent JVMs. Blocking request/reply, backpressure, observability events, transparent cross-namespace calls, real pool JVM integration.

Detailed design: [`flow-buildout.md`](flow-buildout.md)
Visualization plan: [`flow-viz-plan.md`](flow-viz-plan.md)

### What Was Built

Dual-flow model: orchestrator flow topology + agent JVM mini-flow, connected by TCP socket channels wired as flow in-ports/out-ports.

- **Message envelope schemas** (`seon.flow.msg`) — Malli-registered request, reply, and event schemas with version key from day one. Error taxonomy: `:execution`, `:timeout`, `:overload`, `:serialization`, `:not-found`.
- **TCP channel adapter** (`seon.flow.harness.channel`) — Bidirectional TCP-to-core.async adapter with length-prefixed EDN framing. `start-server!` / `connect!` API.
- **Bridge step function** (`seon.flow.harness.bridge`) — Agent JVM mini-flow process. Receives requests via TCP, resolves and calls local functions, returns reply envelopes. Reverse channel for cross-ns calls (`remote-call!`).
- **Namespace step function** (`seon.flow.harness`) — Orchestrator-side flow process. Queue cap (32 dev default) with typed `:overload` error path. Emits observability events. `start-namespace-jvm!` acquires pool JVM, starts TCP, loads bridge via nREPL.
- **Reply router and topology** (`seon.flow.topology`) — `build-topology!` wires namespace processes + reply router + event/error sinks. `request!` provides blocking cross-namespace function calls. Cycle detection at build time (DFS). Cross-ns relay go-loops for agent-to-agent calls.
- **Proxy namespaces** (`seon.flow.harness.proxy`) — `proxy-ns!` creates namespaces in agent JVMs with proxy vars that route calls through the flow. Agents write normal Clojure: `(nutrition/metabolic-rate {::weight 80})` — routing is invisible.
- **Flow observability** (`seon.flow.registry`, `seon.flow.status`) — Central flow registry, on-demand status collection via `flow/ping`, throughput tracking, error accumulation. `(user/flow-status)` REPL helper for AI agent diagnostics.
- **Real pool JVM integration** — `start-namespace-jvm!` acquires JVM, loads bridge code via nREPL, starts request-reply loop. Tested with lifting + nutrition domains on separate JVMs.

### Key Files

| File | Purpose |
|------|---------|
| `src/seon/flow/msg.clj` | Message envelope schemas (source of truth) |
| `src/seon/flow/harness.clj` | Namespace-step, ctx persistence, JVM lifecycle |
| `src/seon/flow/harness/bridge.clj` | Agent JVM bridge + reverse channel for cross-ns calls |
| `src/seon/flow/harness/channel.clj` | TCP socket to core.async channel adapter |
| `src/seon/flow/harness/proxy.clj` | Transparent proxy namespace generation |
| `src/seon/flow/topology.clj` | Flow wiring + reply router + cycle detection + cross-ns relay |
| `src/seon/flow/registry.clj` | Central flow registry |
| `src/seon/flow/status.clj` | Status collector with throughput + error tracking |
| `test/seon/flow/integration_test.clj` | Domain tests with mock channels (14 tests) |
| `test/seon/flow/pool_integration_test.clj` | Real pool JVM tests (3 tests) |
| `test/seon/flow/domain_integration_test.clj` | Full domain tests on real JVMs (7 tests) |

### Build Checklist

- [x] Message envelope schemas with Malli registration and version key
- [x] TCP channel adapter (length-prefixed EDN, server + client)
- [x] Bridge step-fn (agent JVM side: execute-local + error taxonomy)
- [x] Namespace step-fn (orchestrator side: queue cap, observability events)
- [x] Reply router + topology wiring + event/error sinks
- [x] `topology/request!` blocking cross-namespace calls
- [x] `*ctx*` persistence (serializable keys saved, non-serializable warned)
- [x] Overload path (queue cap exceeded returns typed `:overload` error)
- [x] Observability events emitted on start/ok/error/overload/timeout
- [x] Transparent proxy namespaces — agents call remote fns with normal Clojure syntax
- [x] Reverse channel + cross-ns relay for agent-to-agent calls
- [x] Cycle detection at topology build time (DFS)
- [x] Real pool JVM integration (`start-namespace-jvm!` + `stop-namespace-jvm!`)
- [x] Domain integration tests (lifting + nutrition on real JVMs, 7 scenarios)
- [x] Flow registry + status collector + `user/flow-status` REPL helper
- [x] 75+ flow tests, 300+ assertions, 0 failures (667 total suite)

### Notes

- `::flow/in-ports` and `::flow/out-ports` are merged into process inputs/outputs after `init` returns (flow/impl.clj:261-263). They are not visible to other flow processes — they are the boundary between flow and external channels.
- nREPL is used only for code loading into agent JVMs. All runtime data flows through TCP socket channels.
- Queue cap default (32) lives in `system.edn` under `:seon/flow-defaults`. Per-namespace overrides go in namespace code, not global config.
- Flow state (harness bookkeeping) is distinct from `*ctx*` (agent-facing atom). Agents interact with `*ctx*`; they never see flow internals.

---

## Phase 5: Dynamic Context + MCP Cockpit — DEFERRED

**Status**: Infrastructure exists (`seon.graph.context` builds topological context from Datalevin) but not wired into agent launch or MCP tools. Deferred until spec-driven rendering pipeline is complete — context rendering should use the same `find-renderer` resolution as HTML rendering (`:seon.render/ai` format).

**Goal**: Agents receive proactive, relevant context as they work — not just tools they have to call. The Super REPL watches what agents are doing and injects useful information (related code, schemas, data structures) into their context window. MCP tools provide explicit query access as a complement.

**Key insight**: The goal is **dynamic context, not a rolling list of messages**. When an agent evals a form, the response should include relevant discoveries from the knowledge graph. When an agent starts working in a namespace, they should see what's already available across the system.

**Infrastructure ready**: The flow harness (Phase 4) already emits observability events (`:start`, `:ok`, `:error`, `:overload`, `:timeout`) on `:seon.flow.out/event`. The context engine can subscribe to these events to track namespace health and agent activity in real time. The topological context builder (`seon.graph.context`) can already do recursive pull + toposort + text rendering — it just needs to be wired in.

### Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `src/seon/repl/context.clj` | Create | Context engine: compute relevant context from graph + eval history + flow events |
| `bin/mcp-server` | Modify | Add `query_graph`, `namespace_health`, `agent_status` tools |
| `.claude/AGENT.md` | Modify | Tell agents about dynamic context and MCP tools |
| `test/seon/repl/context_test.clj` | Create | Tests |

### Existing Code to Reuse

- `seon.graph.query` (Phase 2) — Datalog query API
- `seon.repl.super` (Phase 3) — `suggest-context` hook point after each eval
- `seon.agent.env` (Phase 3) — Agent-side search and discovery
- `seon.flow.msg` (Phase 4) — `::msg/event` schema for observability events
- `seon.flow.topology` (Phase 4) — Flow topology provides event streams per namespace
- `bin/mcp-server` tool registration pattern — see `tools` vector and `execute-tool-sync` dispatch

### Build Checklist

- [ ] **`seon.repl.context`** — Context engine:
  - [ ] `compute-context` — Given a form's analysis (what keys it uses, what namespaces it touches), query the graph for: related functions, Malli schemas with overlapping keys, cross-namespace data producers/consumers. Return a compact summary.
  - [ ] `session-context` — When an agent starts a session, compute initial context: what exists in their namespace, what data structures are available system-wide, what other agents are working on nearby namespaces.
  - [ ] `typeahead-suggest` — Given a partial function name or keyword, return matches from the knowledge graph.
- [ ] `query_graph` MCP tool — accepts Datalog query, runs against knowledge graph
- [ ] `namespace_health` MCP tool — error rates from flow events, test status, recent failures for a namespace
- [ ] `agent_status` MCP tool — running agents, tasks, JVM ports
- [ ] Update AGENT.md with documentation on both proactive context and explicit query tools

### Test Checklist

- [ ] Agent defines function using `:health/workout` → dynamic context returns related functions from other namespaces
- [ ] Agent starts session in `seon.trading` → session context shows existing trading functions and available schemas
- [ ] Agent calls `query_graph` → gets accurate dependency results
- [ ] `namespace_health` returns meaningful metrics (sourced from flow observability events)
- [ ] All tests pass: `clojure -M:test -m kaocha.runner`

### Commit Message Template

```
feat: dynamic context engine + MCP cockpit tools

- seon.repl.context: proactive context computation from knowledge graph + flow events
- query_graph, namespace_health, agent_status MCP tools
- Updated AGENT.md with context and tool documentation
```

---

## Phase 6: Inter-Agent Messaging

**Goal**: Typed, schema-validated messages between agents via flow channels and Datalevin.

**Infrastructure ready**: Cross-namespace function calls already work via `topology/request!` (Phase 4). Messaging builds on top of this — `request!` handles synchronous RPC; messaging adds asynchronous, Datalevin-persisted communication (feature requests, bug reports, status updates) that agents can check at their own pace.

### Files to Create

| File | Purpose |
|------|---------|
| `src/seon/flow/mailbox.clj` | Per-namespace message queue (Datalevin-backed) |
| `test/seon/flow/mailbox_test.clj` | Tests |

### Build Checklist

- [ ] Message schemas: `:seon.msg/feature-request`, `:seon.msg/bug-report`, `:seon.msg/status-update`
- [ ] `seon.flow.mailbox` — Per-namespace message queue (Datalevin-backed)
- [ ] MCP tools: `check_mailbox`, `send_message`
- [ ] Route async messages through flow topology (complement to synchronous `request!`)

### Test Checklist

- [ ] Agent A sends feature request to B's namespace → persisted in Datalevin → B retrieves it
- [ ] All tests pass: `clojure -M:test -m kaocha.runner`

### Commit Message Template

```
feat: inter-agent messaging with schema-validated mailboxes

- seon.flow.mailbox: Datalevin-backed per-namespace message queues
- MCP tools for check_mailbox and send_message
- Message schemas for feature requests, bug reports, status updates
```

---

## Phase 7: Graph REPL (Future)

**Goal**: Parse nested cross-namespace forms into execution DAGs with spec validation at boundaries.

### Build Checklist

- [ ] Parse nested cross-namespace forms into execution DAGs
- [ ] Spec validation at namespace boundaries
- [ ] Multi-language dispatch (Python via libpython-clj, JS via GraalJS)

*Detailed design TBD when Phases 2-6 are complete.*

---

## Prerequisites (Do Not Proceed Without)

| Prerequisite | PRD | Key Remaining Work | Blocking? |
|-------------|-----|-------------------|-----------|
| **Datalevin Read Migration** | [`datalevin-migration`](../datalevin-migration/prd.md) Phase 2 | Migrate reads from XTDB to Datalevin | **Yes** — required for Super REPL Phase 3 |
| **Schema Compiler** | [`datalevin-migration`](../datalevin-migration/prd.md) Phase 0.3 | Auto-generate Datalevin schema from Malli | Nice-to-have, not blocking |
| **Observatory on Datalevin** | [`namespace-ui`](../namespace-ui/prd.md) Phase 1b.10 | Switch reads from XTDB → Datalevin | No — can proceed in parallel |

## Related PRDs

- **[`datalevin-migration`](../datalevin-migration/prd.md)** — Database layer: Datalevin as primary DB, ctx persistence spec, schema compiler
- **[`namespace-ui`](../namespace-ui/prd.md)** — Presentation layer: namespace introspection views, design system, reactive UI components
- **[`spec-driven-rendering`](../spec-driven-rendering/prd.md)** — Renderer resolution: Datalevin code index discovers render functions from `:malli/schema` metadata, replaces per-namespace render convention
