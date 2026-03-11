# Seon Vision

## The Thesis

AI agents will write most software. The question isn't *if* but *how well*.

Current approaches are broken: agents bolt onto codebases designed for humans, hallucinate interfaces, have no memory, step on each other, and ship broken code. The result is increasingly fragmented codebases with mounting technical debt.

**Seon is infrastructure for AI agents to write reliable software.**

Not a framework. Not a library. A complete codebase architecture where agents can:
- Discover functions by their contracts (not hallucinate them)
- Learn from history (not repeat mistakes)
- Own code long-term (not just complete tasks)
- Compose safely (not break each other's work)

The personal domains (trading, health, finance) are test cases. The infrastructure is the product.

---

## Why This Might Work

### The Right Language

Clojure isn't a limitation - it's a requirement:

| Property | Why It Matters for Agents |
|----------|---------------------------|
| **Stable APIs** | 10-year-old docs still valid. No API churn to track. |
| **Data-oriented** | Maps in, maps out. No hidden object state. |
| **Homoiconic** | Code is data. Agents can manipulate programs as data structures. |
| **REPL-driven** | Interactive development matches agent workflow: try, see, iterate. |
| **Immutable default** | Outputs depend only on inputs. No spooky action at a distance. |

McCarthy designed Lisp for AI. Maybe the killer app was always agents writing Lisp.

### The Right Database

Datalevin is an embedded Datalog database on LMDB:
- **Datomic-compatible** - EAV datoms, pull API, Datalog queries
- **Embedded** - No separate process, fast local storage
- **Agent isolation** - Each agent gets namespace-scoped database via connection manager
- **ACID transactions** - Reliable writes with proper isolation

Agents query data with Datalog: "What functions return this schema?" "Which namespaces depend on this one?" Session history and messages persist across restarts.

### The Right Contracts

Malli schemas with fully namespaced keys create machine-readable contracts:

```clojure
;; Every function advertises its interface
{:malli/schema [:=> [:cat ::analyze-request] ::analyze-response]}

;; Every key is globally unique and queryable
:seon.trading/position  ; Not just :position
:seon.health/metric     ; No ambiguity
```

"What functions accept `:seon.trading/position`?" becomes a database query, not a hallucination.

---

## The Core Primitive

One function. One map in, one map out. Both fully spec'd with Malli. Registered in the graph.

Everything else is composition of this primitive.

### Function Discovery

The graph knows every public function's input and output schemas. Given a data shape, query for all functions that accept it. Given a desired output shape, query for all functions that produce it. Given both, find functions that transform one into the other.

This is the **same operation regardless of intent**:

| Intent | Input Shape | Output Shape |
|--------|------------|--------------|
| Render data as HTML | `::trading/position` | `::html/fragment` |
| React to a data change | `::db/tx-report` | `::notification` |
| Transform data | `::raw/csv-row` | `::trading/position` |
| Handle user action | `::ui/click-event` | `::ui/response` |
| Validate data | `::trading/position` | `::validation/result` |
| Test a function | `::trading/position` | `::test/result` |

No separate rendering system, subscription system, test runner, or event system. One discovery mechanism. Functions that match are functions that work.

### Tests Are Functions Too

Tests don't need their own schema metadata. A test calls functions. Those functions have schemas. The graph connects them transitively. So "which tests exercise `::trading/position`?" is a graph traversal: schema → functions that reference it → tests that call those functions. The test is just a test. The graph does the bookkeeping.

This replaces file-based test selection ("this file changed, run its test file") with **schema-based test selection** ("this schema was touched, run every test that exercises it"). The blast radius is precise — not "run all 500 tests" and not "guess which files are related" but "these 12 tests touch the changed schemas."

Immediate feedback: agent evals a function → graph knows the affected schemas → traverses to all tests for those schemas → runs exactly those tests → reports results. Seconds, not minutes.

### The REPL as Sole Interface

Agents don't edit files. They eval forms in the REPL. The REPL pipeline:

1. Evaluate the form (compile + execute)
2. Validate the function's Malli schema
3. Transact function metadata into the graph (name, namespace, schemas, docstring, dependencies)
4. Persist the source form to disk as a regular `.clj` file
5. Run affected tests

The file system is a persistence format, not the source of truth. The graph database is the system. The REPL is the only interface agents need.

### Self-Referential

The system uses itself. The functions that discover other functions, route messages, and manage the graph — they are themselves registered in the graph with spec'd inputs and outputs. An agent looking for "how do I query the graph?" discovers `seon.graph.query/functions-in-ns` through the same mechanism it would use to find a trading signal calculator.

### Progressive Enhancement

A namespace starts empty. When the system needs to render data from that namespace and no render function exists, a default renderer handles it. The agent is notified: "namespace X received a render request for schema Y but has no handler." The agent writes a compatible function. On eval, it enters the graph. Next request finds it automatically.

This applies universally — rendering, event handling, data transformation, validation. Write a compatible function and it's discoverable immediately. No registration ceremony. The schema IS the registration.

### Constraints That Simplify

We control Seon. We can add constraints that make this tractable:

- **All public functions**: one map in, one map out, fully spec'd — no exceptions
- **All schemas**: registered in the global Malli registry with namespaced keys
- **All evaluation**: through the REPL pipeline — validates, persists, tests
- **All data**: namespaced keywords, concrete types, no `:any`
- **All cross-boundary calls**: through the flow topology

These constraints aren't limitations. They're what make universal function discovery possible. A system where every function has a known shape is a system that can compose itself.

---

## The Namespace

The namespace is the unit of ownership. One agent (human or AI) stewards one namespace. Everything they need — context, tools, feedback — is scoped to that namespace.

### What the Namespace Agent Sees

When a namespace agent starts, the system provides:

- **Their functions** — every `defn` in the namespace with its Malli schema, docstring, and current test status
- **Their schemas** — every registered schema in the namespace
- **Their dependencies** — functions required in from other namespaces, with schemas
- **Their dependents** — who calls their functions, so they know the blast radius of changes
- **Their tests** — every test that exercises their schemas, with last-run results
- **Their notifications** — problems reported by other agents, upstream schema changes, failing tests

All of this is derived from the graph. No special context-building code — it's the same function discovery mechanism applied to the question "what do I need to know about namespace X?"

### What the Namespace Agent Does

Three things: write schemas, write functions, write tests. All vanilla Clojure.

```clojure
;; Register a schema — standard Malli, namespaced keys
(schema/register! ::position
  [:map
   [::ticker :string]
   [::quantity :int]
   [::entry-price :double]])

;; Write a function — standard defn with :malli/schema metadata
(defn value
  "Calculate position value."
  {:malli/schema [:=> [:cat ::value-request] ::value-response]}
  [{::keys [position price]}]
  {::value (* (::quantity position) price)})

;; Write a test — standard deftest
(deftest value-test
  (testing "calculates position value"
    (is (= {::value 1500.0}
           (value {::position {::ticker "AAPL" ::quantity 10 ::entry-price 150.0}
                   ::price 150.0})))))
```

Nothing exotic. The agent writes normal Clojure. The enforcement is in the eval pipeline, not in the syntax.

### The Eval Pipeline

When a form is eval'd through the REPL, the pipeline enforces constraints before accepting it:

**For `defn`:**
- Schema present? (`:malli/schema` metadata required for public functions)
- Schema concrete? (no `:any`, no `[:maybe X]`, all types Datalevin-compatible)
- Schema serializable? (roundtrips through Nippy and Datalevin without loss)
- Map-in/map-out? (single map argument, map return, namespaced keys)
- If any fail → reject with clear error. The function is not compiled, not registered, not persisted.

**For `schema/register!`:**
- All types concrete and Datalevin-compatible?
- Namespaced keys throughout?
- Generator works? (can produce valid samples)
- If any fail → reject.

**For `deftest`:**
- Register in the graph. Schema association is inferred automatically — the graph knows which functions the test calls, which schemas those functions reference, and transitively which schemas the test exercises. No metadata needed on the test itself.

**If all pass:**
1. Compile and execute the form
2. Transact metadata into the graph
3. Persist source to disk
4. Run affected tests (discovered by schema, not by file)
5. Report results immediately

### Constraint Enforcement Is Function Discovery

The constraints above are not hard-coded in the pipeline. Each constraint is a function with a spec'd input and output:

```
Input shape: ::eval/form (the form being evaluated + its metadata)
Output shape: ::constraint/result (pass/fail + explanation)
```

The eval pipeline discovers all functions matching this signature and runs them. To add a new constraint — say, "function names must not exceed 40 characters" — write a function that accepts `::eval/form` and returns `::constraint/result`. It's picked up automatically on next eval.

This means the system's quality standards are extensible without changing the pipeline. The pipeline doesn't know what the constraints are. It just discovers functions that match and runs them. Turtles all the way down.

### Notifications

When something goes wrong — a test fails, an upstream schema changes, a dependent reports a type mismatch — the namespace agent is notified through the same message routing. A notification is a spec'd map. The namespace either has a handler function for that notification shape or the agent is asked to deal with it.

This closes the feedback loop: agent writes code → pipeline validates → graph updates → tests run → if something breaks elsewhere → that namespace's agent is notified → they fix it → their pipeline validates → and so on.

---

## The Architecture

### Layer 1: Contracts & Discovery

**What exists now:**
- Malli schema registry with namespaced keys
- Schema introspection (`schemas-in-namespace`, `registered?`)
- Function schemas via `:malli/schema` metadata

**What's next:**
- **Schema-driven function discovery** - Query the graph for functions by input/output schema shape. "What accepts `::trading/position`?" is a Datalog query against `seon.graph`, not a grep. The graph already indexes functions — adding schema-based lookup closes the loop.
- **Composition hints** - Functions that chain (output schema of A matches input schema of B) are discoverable relationships in the graph
- **Usage examples** - Auto-generated from test cases and REPL history

**Success state:** Agent asks "how do I calculate a trading signal?" → system returns relevant functions with signatures, examples, and composition patterns. No hallucination needed — the answer is in the graph.

### Layer 2: Agent Isolation

**What exists now:**
- Each agent gets isolated nREPL (own port, own REPL state)
- Each agent gets isolated Datalevin database (namespace-scoped via connection manager)
- Each agent gets isolated log files
- Pool-based JVM model: pre-warmed JVMs with isolated nREPL + Datalevin connections
- `seon.flow.pool` manages `claim!`/`release-session!` lifecycle
- Registry tracks running agents
- Health checks detect orphaned resources

**What's next:**
- **Namespace ownership model** - Declare which agent owns which namespace
- **Cross-agent communication** - Via schemas and database, not shared state
- **Conflict detection** - Alert when agents touch the same code

**Success state:** Multiple agents work in parallel on different namespaces without interference. Ownership is explicit and enforced.

### Layer 3: Verification

**What exists now:**
- Dev hooks trigger on every Edit/Write
- Automatic code reload into running system
- Affected namespace tests run automatically
- Generative testing via Malli schemas
- AI review (Gemini) for style/correctness
- Hooks block on test failure
- REPL-first test system (`seon.dev.test`) with structured results (maps, not text)
- Dependency-aware testing (`seon.dev.test-select`) uses code graph for smart test selection
- Kaocha config with unit/integration suite split (494 unit tests, 13 integration tests)

**What's next:**
- **Semantic diff** - Did behavior change, not just syntax?
- **Regression detection** - Compare outputs before/after
- **Review learning** - Track which reviews caught real issues

**Success state:** Agents can move fast because verification is automatic. Bad changes never land.

### Layer 4: Observability

**What exists now:**
- Observatory UI shows running agents
- Agent logs with tool calls, results, errors
- Health endpoint with component status
- SSE-based live updates

**What's next (namespace-ui):**
- **Namespace introspection** - View any namespace's functions, vars, atoms
- **Schema browser** - Navigate all registered schemas with cross-references
- **Data viewer** - Expand/collapse nested structures
- **Live atom updates** - REPL change → browser update in <100ms
- **Reactive subscriptions** - Replace broadcast `refresh-all!` with targeted, schema-aware notifications. When `transact!` succeeds, fingerprint the tx-report by entity type and attributes. Namespaces register interest in specific schema shapes. A `subscription-router` flow process matches tx-reports against registrations and injects notifications only to interested namespace processes.

**Success state:** You can see the entire system state at a glance. Agents can too. UI updates are targeted — only the parts that care about the changed data re-render.

### Layer 5: Dynamic Context (The Cockpit)

**What exists now:**
- Static context in CLAUDE.md, AGENT.md
- Message history grows until summarized

**What's next:**
- **Live system status** - Health, running agents, recent errors always visible
- **Function typeahead** - As agent types, show matching functions with docs
- **Relevant context injection** - System surfaces what agent needs, not everything
- **Sliding window** - Recent messages + live dashboard, not growing scroll
- **Message-first namespace protocol** - Every namespace is an actor. Messages are Malli-spec'd maps. The topology routes messages to the most specific handler function in that namespace. When no handler exists, smart defaults apply and the namespace's agent is notified. See "The Reactive Loop" below.

**Success state:** Agent context is a cockpit with instruments, not a growing scroll of text. Information flows in based on what's relevant now. Namespaces respond to events automatically when handlers exist, and agents fill the gaps.

### Layer 6: Learning from History

**What exists now:**
- All agent messages persisted to Datalevin
- Session metadata (cost, duration, status)
- Flow event tracing for cross-JVM calls

**What's next:**
- **Session replay** - Re-run agent sessions to understand decisions
- **Pattern extraction** - "When agents do X, Y usually follows"
- **Mistake tracking** - "This approach failed 3 times before"
- **Cross-namespace analytics** - "Function X is called by 5 namespaces"

**Success state:** Agents get smarter over time. The system learns which approaches work.

### Layer 7: Long-term Ownership

**What exists now:**
- Agents complete tasks and exit
- No persistent agent identity

**What's next:**
- **Persistent agents** - Agent assigned to `seon.trading.signals` long-term
- **Ownership handoff** - Graceful transfer when agent context expires
- **Evolution tracking** - "This namespace has been modified 47 times by 3 agents"
- **Proactive maintenance** - Agents notice issues and fix them unprompted
- **Progressive enhancement** - Namespaces start minimal with smart defaults. When the system sends `:minimize` to a namespace and there's no handler, the default behavior runs (e.g., icon in taskbar) and the agent is notified. The agent can then write a `minimize` handler at leisure. On next eval, the router picks it up automatically. Namespaces grow organically based on actual usage, not speculative feature lists.

**Success state:** Namespaces have stewards. Code evolves based on usage. Agents maintain, not just build. New functionality emerges from actual demand, not upfront design.

---

## Progress

### Done ✓

| Component | Description |
|-----------|-------------|
| Agent orchestration | Launch, monitor, interrupt agents via REPL |
| Resource isolation | Isolated nREPL, Datalevin, logs per agent |
| Dev hooks | Tests + AI review on every edit |
| Observatory UI | Watch agent progress, view logs |
| Health system | Component checks, orphan cleanup |
| Schema registry | Malli schemas queryable at runtime |
| Message persistence | All messages saved to Datalevin |
| SSE infrastructure | Real-time UI updates |
| REPL-first test system | `seon.dev.test` + `seon.dev.test-select` with structured results |
| DB write coordination | `seon.db` + `seon.db.datalevin.writer` flow step-fn |
| Ctx unification | Single `seon.ctx` system (`seon.agent.ctx` deleted) |
| Timbre migration | 5 files switched from clojure.tools.logging |
| Agent robustness | Pool-based JVM model, health checks, orphan cleanup |

### In Progress

| Component | PRD | Status |
|-----------|-----|--------|
| Unified Flow System | [`unified-flow`](docs/prds/unified-flow/design.md) | Phase 0 (doc alignment) |
| Namespace UI vision | [`namespace-ui`](docs/prds/namespace-ui/prd.md) | Vision complete |
| Observatory polish | [`observatory-polish`](docs/prds/observatory-polish/prd.md) | Active |

### Next Up

| Component | PRD | Purpose |
|-----------|-----|---------|
| Data viewer | [`data-viewer`](docs/prds/data-viewer/prd.md) | Expand/collapse nested data |
| Schema browser | [`schema-viewer`](docs/prds/schema-viewer/prd.md) | Navigate schemas with cross-refs |
| Live updates | [`live-updates`](docs/prds/live-updates/prd.md) | REPL → browser <100ms |
| Dashboard | [`dashboard-polish`](docs/prds/dashboard-polish/prd.md) | Information-dense system view |
| Custom renderers | [`custom-renderers`](docs/prds/custom-renderers/prd.md) | Domain-specific UI |

### Future (No PRD Yet)

| Component | Purpose |
|-----------|---------|
| Function index | Query functions by input/output schema |
| Dynamic cockpit | Live context instead of growing scroll |
| Session replay | Learn from agent history |
| Namespace ownership | Persistent agent assignment |
| Cross-agent coordination | Safe parallel work |

---

## Milestones

Evergreen architectural goals. Agents should check current system state against these and propose work that moves closer.

### M1: Graph-Complete Function Registry

Every public function with `:malli/schema` has its input and output schemas indexed in the graph as queryable data. Not just the function name and arglists — the actual schema shapes, decomposed into their component types.

**How to check:** Query the graph for functions that accept a specific schema. If the results are complete and accurate, M1 is done.

### M2: Schema-Based Function Discovery

Given an input shape and a desired output shape, find compatible functions. Given just an input shape, find all possible transformations. Composition chains (A→B, B→C discoverable as A→C) are queryable as graph paths.

**How to check:** Ask the system "what can I do with a `::trading/position`?" and get back a useful, complete list of functions — renderers, transformers, validators — without separate queries per concern.

### M3: REPL-First Eval Pipeline

Agent evals a form in the REPL. The pipeline validates the schema, transacts metadata into the graph, persists to disk, and runs affected tests — all as one atomic operation. No file editing. No post-edit hooks. The REPL IS the write interface.

**How to check:** An agent evals `(defn ...)` → immediately queries the graph → finds the function with full schema metadata. The `.clj` file on disk reflects the change. Tests ran.

### M4: Unified Dispatch

One discovery mechanism serves all use cases. Rendering, change notification, data transformation, event handling — all are "find a function with compatible schemas." Expressing interest in data changes = having a function that accepts that change shape. The system finds and calls it.

**How to check:** Write a function that accepts `::db/tx-report` with certain attributes → it gets called when matching transactions occur. No separate subscription API. Same mechanism that finds renderers finds subscribers.

### M5: Self-Describing System

The system's own infrastructure — graph queries, routing, discovery, REPL eval — is registered in the same graph and discoverable by the same mechanism it provides. An agent bootstraps by discovering the discovery functions.

**How to check:** An agent with no prior knowledge queries the graph for "functions that accept a schema shape" and discovers the discovery API itself. Turtles all the way down.

---

## Why Not Just Use [X]?

### Why not just use Cursor/Copilot/etc?

They bolt onto existing codebases. No contracts, no history, no isolation. They're autocomplete, not ownership.

### Why not Python/TypeScript?

- **Python**: Dynamic, but mutable-by-default. No built-in spec system. Ecosystem churn.
- **TypeScript**: Types help, but object-oriented heritage. Build complexity. Node ecosystem churn.
- **Clojure**: Immutable, data-oriented, stable, REPL-native. The language is designed for what we're doing.

### Why not a hosted solution?

Local-first means:
- Your data stays yours
- No API rate limits
- Works offline
- Full control over the runtime

### Why build the infrastructure instead of domains?

The infrastructure IS the product. Without schema discovery, temporal history, and verified isolation, agents just create more technical debt. The domains prove the infrastructure works.

---

## The Bet

This is a bet that:

1. AI agents will write most code within 5 years
2. Current approaches (bolt-on assistants) won't scale
3. Purpose-built infrastructure dramatically improves agent reliability
4. Clojure's properties are uniquely suited to this problem
5. The investment in infrastructure pays off as agents get more capable

If wrong: interesting Clojure project with good architecture.
If right: the foundation for how software gets built.

---

## Related Documents

| Document | Purpose |
|----------|---------|
| `CLAUDE.md` | Operational instructions + condensed vision |
| `CONVENTIONS.md` | Code patterns that enable agent discoverability |
| `docs/prds/namespace-ui/prd.md` | UI/observability vision |
| `docs/prds/namespace-ui/design-system.md` | Visual design philosophy |
