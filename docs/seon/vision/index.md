---
type: vision
---

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

Tests don't need their own schema metadata. A test calls functions. Those functions have schemas. The graph connects them transitively. So "which tests exercise `::trading/position`?" is a graph traversal: schema -> functions that reference it -> tests that call those functions. The test is just a test. The graph does the bookkeeping.

This replaces file-based test selection ("this file changed, run its test file") with **schema-based test selection** ("this schema was touched, run every test that exercises it"). The blast radius is precise -- not "run all 500 tests" and not "guess which files are related" but "these 12 tests touch the changed schemas."

### The REPL as Sole Interface

Agents don't edit files. They eval forms in the REPL. The REPL pipeline:

1. Evaluate the form (compile + execute)
2. Validate the function's Malli schema
3. Transact function metadata into the graph (name, namespace, schemas, docstring, dependencies)
4. Persist the source form to disk as a regular `.clj` file
5. Run affected tests

The file system is a persistence format, not the source of truth. The graph database is the system. The REPL is the only interface agents need.

### Self-Referential

The system uses itself. The functions that discover other functions, route messages, and manage the graph -- they are themselves registered in the graph with spec'd inputs and outputs. An agent looking for "how do I query the graph?" discovers `seon.graph.query/functions-in-ns` through the same mechanism it would use to find a trading signal calculator.

### Progressive Enhancement

A namespace starts empty. When the system needs to render data from that namespace and no render function exists, a default renderer handles it. The agent is notified: "namespace X received a render request for schema Y but has no handler." The agent writes a compatible function. On eval, it enters the graph. Next request finds it automatically.

This applies universally -- rendering, event handling, data transformation, validation. Write a compatible function and it's discoverable immediately. No registration ceremony. The schema IS the registration.

### Constraints That Simplify

We control Seon. We can add constraints that make this tractable:

- **All public functions**: one map in, one map out, fully spec'd -- no exceptions
- **All schemas**: registered in the global Malli registry with namespaced keys
- **All evaluation**: through the REPL pipeline -- validates, persists, tests
- **All data**: namespaced keywords, concrete types, no `:any`
- **All cross-boundary calls**: through the flow topology

These constraints aren't limitations. They're what make universal function discovery possible. A system where every function has a known shape is a system that can compose itself.

---

## The Namespace

The namespace is the unit of ownership. One agent (human or AI) stewards one namespace. Everything they need -- context, tools, feedback -- is scoped to that namespace.

### What the Namespace Agent Sees

When a namespace agent starts, the system provides:

- **Their functions** -- every `defn` in the namespace with its Malli schema, docstring, and current test status
- **Their schemas** -- every registered schema in the namespace
- **Their dependencies** -- functions required in from other namespaces, with schemas
- **Their dependents** -- who calls their functions, so they know the blast radius of changes
- **Their tests** -- every test that exercises their schemas, with last-run results
- **Their notifications** -- problems reported by other agents, upstream schema changes, failing tests

All of this is derived from the graph. No special context-building code -- it's the same function discovery mechanism applied to the question "what do I need to know about namespace X?"

### What the Namespace Agent Does

Three things: write schemas, write functions, write tests. All vanilla Clojure.

```clojure
;; Register a schema -- standard Malli, namespaced keys
(schema/register! ::position
  [:map
   [::ticker :string]
   [::quantity :int]
   [::entry-price :double]])

;; Write a function -- standard defn with :malli/schema metadata
(defn value
  "Calculate position value."
  {:malli/schema [:=> [:cat ::value-request] ::value-response]}
  [{::keys [position price]}]
  {::value (* (::quantity position) price)})

;; Write a test -- standard deftest
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
- If any fail -> reject with clear error. The function is not compiled, not registered, not persisted.

**For `schema/register!`:**

- All types concrete and Datalevin-compatible?
- Namespaced keys throughout?
- Generator works? (can produce valid samples)
- If any fail -> reject.

**For `deftest`:**

- Register in the graph. Schema association is inferred automatically -- the graph knows which functions the test calls, which schemas those functions reference, and transitively which schemas the test exercises. No metadata needed on the test itself.

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

The eval pipeline discovers all functions matching this signature and runs them. To add a new constraint -- say, "function names must not exceed 40 characters" -- write a function that accepts `::eval/form` and returns `::constraint/result`. It's picked up automatically on next eval.

This means the system's quality standards are extensible without changing the pipeline. The pipeline doesn't know what the constraints are. It just discovers functions that match and runs them. Turtles all the way down.

### Notifications

When something goes wrong -- a test fails, an upstream schema changes, a dependent reports a type mismatch -- the namespace agent is notified through the same message routing. A notification is a spec'd map. The namespace either has a handler function for that notification shape or the agent is asked to deal with it.

This closes the feedback loop: agent writes code -> pipeline validates -> graph updates -> tests run -> if something breaks elsewhere -> that namespace's agent is notified -> they fix it -> their pipeline validates -> and so on.

---

## The Architecture

### Layer 1: Contracts & Discovery

Malli schema registry with namespaced keys. Schema introspection, function schemas via `:malli/schema` metadata. Schema-driven function discovery -- query the graph for functions by input/output schema shape. Composition hints where functions that chain are discoverable relationships in the graph.

### Layer 2: Agent Isolation

Each agent gets isolated nREPL, isolated Datalevin database, isolated logs. Pool-based JVM model with pre-warmed JVMs. Registry tracks running agents. Health checks detect orphaned resources.

### Layer 3: Verification

Dev hooks trigger on every Edit/Write. Automatic code reload, affected tests run automatically. Generative testing via Malli schemas. AI review for style/correctness. Hooks block on test failure.

### Layer 4: Observability

Observatory UI shows running agents. Agent logs with tool calls, results, errors. Health endpoint with component status. SSE-based live updates. Namespace introspection, schema browser, data viewer, live atom updates.

### Layer 5: Dynamic Context (The Cockpit)

Live system status, function typeahead, relevant context injection. Message-first namespace protocol -- every namespace is an actor, messages are Malli-spec'd maps, topology routes to most specific handler.

### Layer 6: Learning from History

All agent messages persisted. Session metadata (cost, duration, status). Flow event tracing. Session replay, pattern extraction, mistake tracking.

### Layer 7: Long-term Ownership

Persistent agents assigned to namespaces. Ownership handoff, evolution tracking, proactive maintenance. Progressive enhancement -- namespaces grow organically based on actual usage.

---

## Milestones

### M1: Graph-Complete Function Registry

Every public function with `:malli/schema` has its input and output schemas indexed in the graph as queryable data.

### M2: Schema-Based Function Discovery

Given an input shape and a desired output shape, find compatible functions. Composition chains are queryable as graph paths.

### M3: REPL-First Eval Pipeline

Agent evals a form in the REPL. The pipeline validates, transacts metadata, persists to disk, and runs affected tests -- all as one atomic operation.

### M4: Unified Dispatch

One discovery mechanism serves all use cases. Rendering, change notification, data transformation, event handling -- all are "find a function with compatible schemas."

### M5: Self-Describing System

The system's own infrastructure is registered in the same graph and discoverable by the same mechanism it provides.

---

## Why Not Just Use [X]?

### Why not just use Cursor/Copilot/etc?

They bolt onto existing codebases. No contracts, no history, no isolation. They're autocomplete, not ownership.

### Why not Python/TypeScript?

- **Python**: Dynamic, but mutable-by-default. No built-in spec system. Ecosystem churn.
- **TypeScript**: Types help, but object-oriented heritage. Build complexity. Node ecosystem churn.
- **Clojure**: Immutable, data-oriented, stable, REPL-native. The language is designed for what we're doing.

### Why not a hosted solution?

Local-first means: your data stays yours, no API rate limits, works offline, full control over the runtime.

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
