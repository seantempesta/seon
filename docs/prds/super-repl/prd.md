# PRD: Super REPL — Federated Agent Runtime

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

**Super REPL**: A form router in the orchestrator. Receives forms from Claude Code agents (via MCP), sends to the right agent JVM via nREPL, stores forms in Datalevin, runs clj-kondo analysis.

**Agent JVM**: A minimal Clojure process (7 deps, 186MB). Connects to orchestrator's Datalevin server as a client. Has its own nREPL.

**JVM Pool**: Pre-warmed JVMs with Clojure + nREPL + core deps loaded. Acquire assigns a namespace, release recycles. Already built — 158ms first acquire.

**Knowledge Graph**: Datalevin database of all namespaces, functions, specs, dependencies. Built from clj-kondo analysis.

**Namespace Owner**: A core.async.flow process in the orchestrator (not an AI agent). Monitors error/health channels from agent proxy nodes.

**Graduation**: When an agent's work is ready to merge, the Super REPL generates the namespace file from Datalevin-stored forms, writes to disk, git commits, reloads the orchestrator.

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

## Phase 2: Knowledge Graph Foundation

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

## Phase 3: Super REPL Core

**Goal**: Agents eval forms through the Super REPL. Forms are stored in Datalevin with versioning. Analysis runs automatically.

### Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `src/seon/repl/super.clj` | Create | Form router: receive → route → store → analyze → respond |
| `src/seon/repl/graduate.clj` | Create | Assemble Datalevin forms → .clj file → git commit |
| `bin/mcp-server` | Modify | Route pool-assigned sessions through Super REPL |
| `test/seon/repl/super_test.clj` | Create | Tests |
| `test/seon/repl/graduate_test.clj` | Create | Tests |

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

- [ ] **`seon.repl.super`** — Form routing:
  - [ ] `eval-form!` — Main entry point. Receives `{:form/source "(defn ema ...)" :form/namespace 'seon.trading.signals :form/agent-id "a13b"}`. Steps:
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
  - [ ] `classify-form` — Parse form and return type. Use `edamame.core/parse-string` to get the form as data, check `(first form)` for `'defn`, `'def`, `'ns`, `'require`, etc.
  - [ ] `current-forms` — Query Datalevin for all current forms in a namespace (latest version of each named form). Used by graduation.
  - [ ] `form-history` — Query all versions of a specific form.

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

- [ ] **`bin/mcp-server` modification**:
  - [ ] In `execute-eval`, add a branch: if session is a pool session (check via orchestrator query), route through `seon.repl.super/eval-form!` instead of raw nREPL eval
  - [ ] The routing check: eval code on orchestrator that checks if this session_id has a pool-assigned JVM. If yes, return port + super-repl flag.

### Test Checklist

- [ ] `eval-form!` with `(defn ema [period data] ...)` → form stored in Datalevin, knowledge graph updated, eval result returned
- [ ] `eval-form!` same function twice → version increments (v1, v2)
- [ ] `current-forms` returns latest version of each form
- [ ] `classify-form` correctly identifies defn, def, ns, require, expression
- [ ] `graduate!` a namespace with 3 forms → correct .clj file generated, git diff shows expected content
- [ ] `preview` returns file content without side effects
- [ ] All tests pass: `clojure -M:test -m kaocha.runner`

### Commit Message Template

```
feat: Super REPL core with form routing and graduation

- seon.repl.super: form classification, routing, Datalevin storage, versioning
- seon.repl.graduate: namespace assembly and graduation to disk
- MCP server routing for pool-assigned sessions
- Tests for form lifecycle and graduation
```

---

## Phase 4: Agent-as-Flow-Node

**Goal**: Wrap agent JVMs as core.async.flow processes with supervision and error handling.

### Files to Create

| File | Purpose |
|------|---------|
| `src/seon/flow/proxy.clj` | Flow process wrapping a remote agent JVM |
| `src/seon/flow/supervisor.clj` | Namespace owner flow process — monitors proxies |
| `src/seon/flow/topology.clj` | Flow graph management and wiring |
| `test/seon/flow/proxy_test.clj` | Tests |
| `test/seon/flow/supervisor_test.clj` | Tests |
| `test/seon/flow/topology_test.clj` | Tests |

### Existing Code to Reuse

**`seon.web.sse.flow` (`src/seon/web/sse/flow.clj`)** — Proven flow step function patterns. Study how flow processes are defined with `describe`, `init`, and `transform`.

**`seon.flow.pool` (`src/seon/flow/pool.clj`)** — Pool acquisition/release for proxy nodes.

**`seon.ai.agent` (`src/seon/ai/agent.clj`)** — Agent registry pattern. Proxy nodes should register similarly.

**`seon.ai.claude.sdk` (`src/seon/ai/claude/sdk.clj`)** — Process lifecycle management patterns.

**`seon.repl.super` (from Phase 3)** — Form routing through Super REPL.

### Build Checklist

- [ ] **`seon.flow.proxy`** — Flow process wrapping a remote agent JVM:
  - [ ] `describe` returns `{:ins {:commands :messages} :outs {:results :errors :health}}`
  - [ ] `init` — Acquire JVM from pool, assign namespace, setup ctx
  - [ ] `transform` — `[state [port value]] → [new-state {port [values]}]`:
    - `:commands` port → eval form via Super REPL on agent JVM
    - Capture errors → emit on `:errors` port
    - Periodic health → emit on `:health` port
  - [ ] Agent JVM knows nothing about flow — it just responds to nREPL evals

- [ ] **`seon.flow.supervisor`** — Namespace owner as flow process:
  - [ ] Monitors `:errors` and `:health` channels from proxy nodes
  - [ ] Tracks metrics: error rate, latency, throughput
  - [ ] Triggers remediation: launch new agent when errors exceed threshold
  - [ ] Scales: add proxies when demand grows, release when idle

- [ ] **`seon.flow.topology`** — Flow graph management:
  - [ ] Register namespace owners
  - [ ] Wire proxy nodes to owners
  - [ ] Wire owners to each other (using dependency edges from knowledge graph)
  - [ ] Expose topology for Observatory visualization

### Test Checklist

- [ ] Launch 2 agent proxy nodes → verify forms flow through correctly
- [ ] Inject error on one agent → verify supervisor receives it and responds
- [ ] Supervisor spawns remediation agent → verify it acquires from pool
- [ ] View topology data (structured map, not UI yet)
- [ ] All tests pass: `clojure -M:test -m kaocha.runner`

### Commit Message Template

```
feat: agent-as-flow-node with proxy, supervisor, and topology

- seon.flow.proxy: flow process wrapping remote agent JVMs
- seon.flow.supervisor: namespace owner with error monitoring
- seon.flow.topology: flow graph management and wiring
- Tests for proxy, supervisor, and topology
```

---

## Phase 5: Dynamic Cockpit via MCP

**Goal**: Agents query the knowledge graph for live, contextual information via MCP tools.

### Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `bin/mcp-server` | Modify | Add `query_graph`, `namespace_health`, `agent_status`, `suggest_context` tools |
| `.claude/AGENT.md` | Modify | Tell agents about new MCP tools |
| `test/seon/graph/query_test.clj` | Modify | Add integration tests for MCP queries |

### Existing Code to Reuse

- `seon.graph.query` (Phase 2) — Datalog query API
- `bin/mcp-server` tool registration pattern — see `tools` vector and `execute-tool-sync` dispatch

### Build Checklist

- [ ] `query_graph` MCP tool — accepts Datalog query, runs against knowledge graph
- [ ] `namespace_health` MCP tool — error rates, test status, recent failures for a namespace
- [ ] `agent_status` MCP tool — running agents, tasks, JVM ports
- [ ] `suggest_context` MCP tool — task-aware: "you're writing specs → here are related schemas"
- [ ] Update AGENT.md with tool documentation

### Test Checklist

- [ ] Agent calls `query_graph` → gets accurate dependency results
- [ ] `namespace_health` returns meaningful metrics
- [ ] All tests pass: `clojure -M:test -m kaocha.runner`

### Commit Message Template

```
feat: dynamic cockpit with knowledge graph MCP tools

- query_graph, namespace_health, agent_status, suggest_context MCP tools
- Updated AGENT.md with tool documentation
```

---

## Phase 6: Inter-Agent Messaging

**Goal**: Typed, schema-validated messages between agents via flow channels and Datalevin.

### Files to Create

| File | Purpose |
|------|---------|
| `src/seon/flow/mailbox.clj` | Per-namespace message queue (Datalevin-backed) |
| `test/seon/flow/mailbox_test.clj` | Tests |

### Build Checklist

- [ ] Message schemas: `:seon.msg/feature-request`, `:seon.msg/bug-report`, `:seon.msg/status-update`
- [ ] `seon.flow.mailbox` — Per-namespace message queue (Datalevin-backed)
- [ ] MCP tools: `check_mailbox`, `send_message`
- [ ] Supervisor routes messages between namespace owners

### Test Checklist

- [ ] Agent A sends feature request to B's namespace → supervisor evaluates → result flows back
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

| Prerequisite | PRD | Key Remaining Work |
|-------------|-----|-------------------|
| **Datalevin as primary DB** | [`datalevin-migration`](docs/prds/datalevin-migration/prd.md) | Phase 0.3 (Schema Compiler), Phase 2 (Read Migration) |
| **Observatory on Datalevin** | [`namespace-ui`](docs/prds/namespace-ui/prd.md) Phase 1b.10 | Switch reads from XTDB → Datalevin |
