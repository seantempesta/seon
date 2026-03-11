# Transfer Prompt: Test Infrastructure Overhaul

## Your Mission

You are overhauling Seon's test infrastructure. This is a **long, careful refactor** — go slowly, understand the system deeply, and build solutions that are general. The tests aren't just testing code — **building the test harness IS building the agent harness.** Every test that exercises the harness proves another part of the system works.

Many existing tests will need to be rewritten to take on this new perspective. That's expected and intentional. Don't rush it.

## The Big Picture

There are two kinds of tests in Seon:

### 1. Pure Function Tests (auto-generated)

Every public function with a `:malli/schema` that takes and returns data (no side effects, no DB, no ctx) should have a **generative test generated automatically** from its schema. No human writes these tests. The system:

- Discovers all pure functions via the code graph
- Generates inputs from the Malli schema
- Calls the function
- Validates the output against the return schema
- Uses `defspec` for automatic shrinking on failure

This already partially exists in `seon.dev.verify/run-gen-tests` and `user/test-gen`. The work is making it comprehensive, fixing un-generable schemas, and ensuring all pure functions are covered.

### 2. Stateful Tests (integration, uses the harness)

Any test that touches the database, ctx, render functions, or lifecycle is an **integration test**. These tests get a full agent harness — the same environment a live agent namespace gets, but with local LMDB instead of TCP. The test doesn't know or care that it's not talking to the real Datalevin server.

These tests exercise the harness itself. When you write a test that creates a ctx, transacts data, queries it back, and checks the result — you're not just testing business logic, you're proving the harness works. **This is not overkill — this is the point.** The harness is the product. Tests are how we know it works.

## Core Design: Shared Harness, Different Transport

```
Agent:  create-harness! → ctx/create! → inject-vars! → d/get-conn (TCP)   → flow routing
Test:   create-harness! → ctx/create! → inject-vars! → d/create-conn (local) → *direct-mode*
```

Same `create-harness!` function. Same ctx with validation. Same `*ctx*` and `*conn*` vars injected. Same schema enforcement. The only difference is the plumbing underneath.

### What the harness provides (same for agents and tests)

| Resource | Agent | Test |
|----------|-------|------|
| ctx atom (validation, schema, reserved keys) | `ctx/create!` | `ctx/create!` (same code, `::persist? false`) |
| `*ctx*` dynamic var | `lifecycle/inject-vars!` | `lifecycle/inject-vars!` (same code) |
| `*conn*` dynamic var | resolved from conn-manager | resolved from fake conn-manager |
| DB connections | TCP to Datalevin server | local temp LMDB (`d/create-conn`) |
| Write routing | infrastructure flow | `*direct-mode*` bypass |
| Schema validation | Malli instrumentation | Malli instrumentation (same) |
| Render functions | discovered from graph | available for direct testing |

## Code Graph → Test Dependency Mapping

Seon already has a code graph (`seon.graph.ingest`, `seon.graph.query`). It knows which functions exist, what they call, and their schemas. We should use this to:

### Only re-run tests that matter

When a function changes, find all tests that exercise it (directly or transitively) and only run those. `user/test-affected` already does namespace-level dependency tracking. We need function-level granularity:

1. **Map test functions → production functions they exercise.** The graph already tracks call relationships. A test that calls `runtime/register!` exercises `runtime/register!` plus everything it calls (db/transact!, ctx/create!, etc.).

2. **On code change, traverse the call graph backward.** Find all test functions that transitively call the changed function. Run only those tests.

3. **This replaces "run all tests on every change."** A change to `seon.health.workout/calculate-calories` should only run workout-related tests, not the full suite.

### Research needed (for the implementing agent)

Read these files to understand the existing graph:

- `src/seon/graph/ingest.clj` — how the code graph is built (functions, calls, specs, dependencies)
- `src/seon/graph/query.clj` — how to query the graph (functions with output key, callers, callees)
- `src/seon/dev/test.clj` — how `test-affected` works today (namespace-level)

Questions to answer:

- Does the graph already track which test functions call which production functions?
- Can we extend `test-affected` to work at function granularity instead of namespace granularity?
- How do we map a `deftest` to the production functions it exercises? Static analysis (the graph) or runtime tracing?
- What about indirect dependencies? A test that calls `db/transact!` exercises the validation pipeline, the schema bridge, the conn-manager — not just the function under test.

## Auto-Generated Generative Tests

### For pure functions

A pure function is one whose `:malli/schema` references only data types (no atoms, channels, connections, ctx). The system should:

1. **Discover** all public functions with `:malli/schema` metadata
2. **Classify** each as pure or stateful (inspect schema for `:seon/runtime-type` markers)
3. **Generate** a `defspec`-based property test for each pure function:
   - Generate inputs from the input schema via `mg/generator`
   - Call the function
   - Validate the return against the output schema via `m/validate`
   - If validation fails, test.check shrinks to minimal failing input
4. **Run** these as part of the test suite — no human writes or maintains them

This is a leveled-up version of `user/test-gen`. The difference: `test-gen` uses Malli's `function-checker` internally. We want `defspec` tests that integrate with clojure.test and kaocha, show up in test reports, and get shrinking.

### For stateful functions

Stateful functions (those that touch DB, ctx, or other resources) need human-written integration tests using the harness. These tests:

- Create a harness with `with-test-harness`
- Set up state (transact data, populate ctx)
- Call the function
- Assert on the result AND the side effects (did the DB change? did ctx update?)

## Read These First (In Order)

1. **`docs/prds/test-infrastructure/design.md`** — The full PRD with verified claims, warnings, and implementation phases
2. **`docs/prds/test-infrastructure/research.md`** — Deep research across all test files and reference library source code
3. **`CLAUDE.md`** — Project conventions, especially Data Rules, Schema Registration, Testing sections
4. **`CONVENTIONS.md`** — API patterns, function contracts

## Key Source Files to Understand

### The Agent Harness (what tests mirror)

- `src/seon/ctx.clj` — ctx atom: `create!` (line 458), `destroy!` (line 615), reserved keys, validation, persistence
- `src/seon/ns/lifecycle.clj` — `inject-vars!` (line 274) creates `*ctx*` and `*conn*` in namespaces
- `src/seon/orchestrator/session.clj` — `start-agent-session!` (line 253) — the full agent environment setup
- `src/seon/flow/agent_runner.clj` — how agent JVMs get their environment

### The Database Layer

- `src/seon/db.clj` — `*direct-mode*` (line 130), `*conn-manager*` (line 162), `resolve-conn`, `transact!`, `ensure-schema!`
- `src/seon/db/datalevin/conn.clj` — `get-or-create-connection!` (line 208) — the code path the fake conn-manager must satisfy
- `src/seon/db/schema.clj` — `persisted-schemas`, `malli-map->datalevin-schema` — for deriving merged test schemas

### The Code Graph

- `src/seon/graph/ingest.clj` — how the graph is built
- `src/seon/graph/query.clj` — querying functions, callers, callees
- `src/seon/dev/test.clj` — `test-affected` (namespace-level dependency-aware testing)

### Current Test Infrastructure (what you're replacing)

- `test/seon/test_utils.clj` — `with-temp-conn`, `with-test-datalevin`, current helpers
- `test/seon/runtime_test.clj` — worst boilerplate example (~45 lines of fixture setup)
- `test/seon/ctx_test.clj` — another heavy boilerplate example
- `test/seon/db/pipeline_test.clj` — best generative test patterns (keep these)

### Reference Code (read the source, not just docs)

- `reference-code/test.check/src/main/clojure/clojure/test/check/` — `defspec`, `prop/for-all`, shrinking
- `reference-code/malli/src/malli/generator.cljc` — generator internals, `:gen/min`, `:gen/max`, `:gen/elements`

## Architecture Decisions (Already Made)

### 1. Lazy Local Datalevin (not eager, not TCP)

- Test harness creates a temp `d/create-conn` **on first DB access**, not at fixture setup
- Pure function tests pay zero LMDB cost
- Uses `:nosync` flags for speed, 10MB map size to prevent OOM
- Unique temp directory per test invocation — concurrent-safe

### 2. Any-db-name conn-manager (not fixed list)

- Every namespace can have its own DB — the fake conn-manager handles arbitrary keywords
- Use `atom` wrapping a "defaulting map" (reify `ILookup` + `IPersistentMap`) — see Verified Claims in PRD
- **WARNING:** A plain `reify IDeref` WILL CRASH — `get-or-create-connection!` calls `swap!` which needs `IAtom`. Use a real atom.

### 3. `transact!` calls `resolve-conn` BEFORE `*direct-mode*`

- `ensure-schema!` needs a real Datalevin connection, not a stub
- The lazy temp conn handles this correctly

### 4. Merged schema from all registered entity schemas

- `persisted-schemas` + `malli-map->datalevin-schema` → merge. Verified: 17 schemas, 110 attrs, no conflicts. Memoize.

### 5. ctx works in tests already

- `ctx/create!` with `{::persist? false, ::sse-push? false, ::validate? true}` is test-ready
- `ctx/destroy!` handles all cleanup. No changes needed to ctx.clj.

## Implementation Strategy: Go Slow, Build the Harness

This is a long refactor. Each phase should be a separate task with its own commit.

### Phase 0: Understand the system (research only)

Before writing any code:

- Read `start-agent-session!` end-to-end. Trace every function call.
- Read `ctx/create!` and understand every option.
- Read `inject-vars!` and understand how `*ctx*` and `*conn*` get into namespaces.
- Read the code graph (`ingest.clj`, `query.clj`). Understand what it tracks.
- Read `seon.dev.verify` — how does `run-gen-tests` work? What does `function-checker` do?
- Test everything in the REPL. Create a session and manually build a harness.

### Phase 1: Shared harness builder

Extract the shared logic from `start-agent-session!` into a `create-harness!` function that works for both agents and tests. The harness provides: ctx atom, `*ctx*`/`*conn*` vars, schema validation, DB connections. The difference is just transport (TCP vs local LMDB, flow vs direct mode).

Verify by: creating a harness in the REPL, transacting data, querying it back, testing ctx validation.

### Phase 2: Auto-generated generative tests

Build the machinery to automatically generate `defspec` tests for all pure functions:

- Discover functions via the schema registry and code graph
- Classify as pure vs stateful (does the schema reference runtime types?)
- Generate property tests: generate inputs → call function → validate output
- Fix the 7 throwing generators (Appendix B of PRD) — replace with stubs, mark `:seon/runtime-type true`
- Re-enable `block-on-fail: true`

### Phase 3: Harness-based integration test for one namespace

Pick a real namespace (e.g., `seon.health.workout` or `seon.ctx`) and write integration tests that use the full harness:

- Create harness → transact test data → call functions → assert on DB state and ctx
- The test should feel like "being an agent" — same environment, same capabilities
- Verify render functions work by populating ctx and calling them directly

### Phase 4: Code graph → test dependency mapping

Extend `test-affected` to function-level granularity:

- Map each test function to the production functions it exercises
- On code change, find affected tests via graph traversal
- Build `user/test-for` that takes a function var and returns relevant tests

### Phase 5: Migrate existing tests

One file at a time. For each test file, decide:

- Is this testing a pure function? → Should be auto-generated, delete the manual test
- Is this testing stateful behavior? → Rewrite using the harness
- Commit after each file. Report what you learned.

### Phase 6: Kaocha integration

Configure kaocha randomize, wrap-run hook for `*direct-mode*`, and integrate auto-generated tests as a custom test type.

## Dependency Versions (Updated)

- Malli: **0.20.0** (upgraded from 0.17.0 — `:gen/min`/`:gen/max` on sequence schemas)
- test.check: **1.1.3** (upgraded from 1.1.1 — shrinking perf improvement)
- Kaocha: **1.91.1392** (already current)

JVM restart required to pick up new versions.

## What Success Looks Like

1. **Shared harness** — `create-harness!` works for both agents and tests. Changes to one benefit the other.
2. **Auto-generated tests** — every pure function with `:malli/schema` gets a generative test for free. No human writes them.
3. **Harness-based integration tests** — stateful tests create a full agent environment. Testing the code = testing the harness.
4. **Function-level test targeting** — change a function, only run its tests. Uses the code graph.
5. **Complete data isolation** — tests use temp LMDB, never touch production. Concurrent-safe.
6. **All schemas generate** — `block-on-fail: true`, no exceptions.

## What to Report

After each phase, report:

- What you learned about the system (this is as important as the code changes)
- What worked, what didn't, what surprised you
- Any code smells or inconsistencies found (fix if understood, flag if uncertain)
- Test counts: total, pass, fail
- How the harness and tests evolved together
