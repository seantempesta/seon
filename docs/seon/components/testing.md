---
type: component
status: production
tags: [component, schema]
---
# Testing Infrastructure

> How Seon tests are organized, run, and what's covered.

## Test Organization

**70 test files, ~819 `deftest` forms** across `test/seon/`.

Test files mirror source files with a `-test` suffix:

- `src/seon/ctx.clj` -> `test/seon/ctx_test.clj`
- `src/seon/db/schema.clj` -> `test/seon/db/schema_test.clj`

### Test Categories

| Category | Count | Description |
|----------|-------|-------------|
| Unit | ~60 files | Pure logic, temp Datalevin connections, `*direct-mode*` bindings |
| Integration | ~5 files | Tagged `^:integration`, excluded from `test-all`. Real JVMs, network, I/O |
| Generative / Pipeline | ~3 files | Malli generators, `assert-pipeline-roundtrip!`, schema->DB->pull verification |

**Integration test files** (excluded from default runs):

- `test/seon/flow/pool_integration_test.clj` — real JVM spawning
- `test/seon/flow/domain_integration_test.clj` — cross-namespace on real JVMs
- `test/seon/db/datalevin/backup_test.clj` — filesystem I/O
- `test/seon/polymarket/api_test.clj` — external API calls
- Individual `^:integration` deftests in `flow/pool_test.clj`

**Shared test utilities**: `test/seon/test_utils.clj`

- `with-temp-conn` — temporary Datalevin connection with `:nosync` for speed, auto-cleanup
- `with-test-datalevin` — fake conn-manager binding `db/*direct-mode*` + `db/*conn-manager*`
- `with-small-db-size` — fixture binding `dc/*init-db-size*` to 10 MiB (also set globally via `alter-var-root`)
- `gen-uuid`, `days-ago`, `days-from-now` — test data helpers

## Test Runner

All tests run inside the running JVM via nREPL. Never spawn a separate `clj -M:test` process.

### Core API (`seon.dev.test`)

| Function | Purpose |
|----------|---------|
| `(test 'seon.foo-test)` | Single namespace |
| `(test 'seon.foo-test/my-test)` | Single var |
| `(test ['seon.a-test 'seon.b-test])` | Multiple namespaces |
| `(test-all)` | All unit tests (excludes `^:integration`) |
| `(test-affected 'seon.foo)` | Namespace + its graph dependents |
| `(test-gen 'seon.foo)` | Generative tests on schema-annotated fns |

All functions return structured data maps (`::success`, `::test-count`, `::pass-count`, `::fail-count`, `::error-count`, `::failures`, `::duration-ms`). Results are stored in an atom — `(last-results)` and `(results-history n)` retrieve them.

Key behaviors:

- **Namespace isolation**: each ns is `remove-ns` + `require :reload` before running, preventing stale state
- **Crash resilience**: `test-all` wraps each namespace in `safe-run-ns-tests` catching `Throwable` (prevents LMDB native crashes from killing the REPL)
- **Integration exclusion**: `test-all` filters out namespaces with `^:integration` metadata

### Exposed via `user` namespace

```clojure
(user/run-tests 'seon.foo-test)              ;; delegates to test/test
(user/run-tests ['seon.foo-test 'seon.bar-test])
(user/run-tests)                              ;; delegates to test/test-all
(user/test-affected 'seon.foo)                ;; dependency-aware
(user/test-gen 'seon.foo)                     ;; generative

```

### Selective Execution (`seon.dev.test-select`)

Uses the [[components/code-graph]] to find affected test namespaces when a source file changes:

1. `affected-namespaces` — queries the graph for the changed ns + its dependents
2. `affected-test-namespaces` — appends `-test` to each, filters to existing classpath entries
3. `run-affected-tests!` — runs filtered test namespaces via `seon.dev.verify/run-unit-tests`

Falls back to just `ns-test` if the code graph DB is unavailable.

### Verification Layer (`seon.dev.verify`)

Called by the [[components/dev-tools|dev hook]] after every edit. Two phases:

1. **Unit tests** — `run-unit-tests` / `run-unit-tests-for-source` (derives `-test` suffix)
2. **Generative tests** — `run-gen-tests` uses `malli.generator/check` on all functions with `:malli/schema` metadata in a namespace. Syncs the Malli registry, collects function schemas via `mi/clj-collect!`, runs N random inputs per function.

Both return structured result maps with `::success`, counts, and formatted display via `format-unit-result` / `format-gen-result`.

### Fallback: `bin/test`

Shell script for when the REPL is down (~30s JVM startup). Runs via `clojure.test`.

## Test Patterns

### `db/*direct-mode*` Binding

19 test files bind `db/*direct-mode*` to `true`, bypassing the core.async flow infrastructure for synchronous DB access. This is the standard pattern for any test that touches the database.

```clojure
(binding [db/*direct-mode* true
          db/*conn-manager* fake-mgr]
  (f))

```

### Fixtures

Most test namespaces use `clojure.test/use-fixtures` with either:

- `:once` fixtures for expensive setup (temp DB connections)
- `:each` fixtures for per-test isolation

Common fixture: `tu/with-test-datalevin` — creates a temp Datalevin, binds `*direct-mode*` and `*conn-manager*`, cleans up on exit.

### Pipeline Roundtrip Testing

The `assert-pipeline-roundtrip!` utility in `test/seon/db/pipeline_test.clj` is the contract test for [[components/schema-system]] -> [[components/database]] integration:

1. Validates schema constraints (no `:any`, no `[:maybe X]`, namespaced keys only)
2. Derives Datalevin schema from Malli via the bridge
3. Generates N entities from Malli schema
4. Transacts to a temp Datalevin DB
5. Pulls entities back
6. Coerces for known transformations (vector->set for cardinality-many, strip `:db/id`)
7. Validates pulled entities against the original Malli schema
8. Asserts value equality

Also provides `assert-tempid-roundtrip!` for entities without a `:db/unique` identity key (trace events, tx metadata).

**29 deftest forms** in `pipeline_test.clj` cover: leaf types, optional keys, enums, cardinality-many (sets, vectors), component refs, non-component refs, complex mixed entities, constraint violation detection, and every registered entity schema (ctx, repl, trace, runtime, agent-run, flow-snap, tx, ingest ns/fn/spec/var/call/ns-dep).

### Malli Generative Testing

8 test files use `malli.generator`:

- `db/pipeline_test.clj` — entity roundtrips (main generative suite)
- `db/schema_roundtrip_test.clj` — Malli->Datalevin type mapping roundtrips
- `ai/claude_test.clj`, `ai/agent_test.clj`, `ai/gemini_test.clj`, `ai_test.clj` — AI function contract testing
- `health/metrics_test.clj` — health domain generators
- `flow/msg_test.clj` — flow message schema generators

## Coverage Map

| Component | Test Namespaces | Coverage |
|-----------|----------------|----------|
| [[components/schema-system]] | `db/schema_test` (9), `db/schema_roundtrip_test` (37), `db/pipeline_test` (29), `db/validation_test` (22) | **good** — type mapping, bridge, roundtrip, validation gate |
| [[components/database]] | `db_test` (7), `db/consistency_test` (14), `db/datalevin/writer_test` (7), `db/datalevin/backup_test` (4), `db/validation_test` (22), `db/pipeline_test` (29) | **good** — transact, query, validation, writer, backup |
| [[components/runtime]] | `runtime_test` (24) | **good** |
| [[components/system-lifecycle]] | `core_test` (2), `system/config_test` (5) | **partial** — config tested, but system start/stop has minimal coverage |
| [[components/context]] | `ctx_test` (28), `ctx/history_test` (5) | **good** |
| [[components/flow-topology]] | `flow/topology_test` (17), `flow/infrastructure_test` (11), `flow/integration_test` (15), `flow/status_test` (6), `flow/msg_test` (5), `flow/trace_test` (6) | **good** — topology, infrastructure, integration, status, messages, trace |
| [[components/harness]] | `flow/harness_test` (12), `flow/harness/bridge_test` (11), `flow/harness/channel_test` (6), `flow/pool_test` (20), `flow/pool_integration_test` (4), `flow/domain_integration_test` (8) | **good** — harness, bridge, channels, pool, real-JVM integration |
| [[components/code-graph]] | `graph/query_test` (8), `graph/scanner_test` (13), `graph/extract_test` (8), `graph/ingest_test` (5), `graph/analyzer_test` (4), `graph/context_test` (5) | **good** |
| [[components/renderer]] | `render_test` (28), `render/code_test` (15) | **good** |
| [[components/namespace-lifecycle]] | `ns/lifecycle_test` (14), `ns/routes_test` (6) | **good** |
| [[components/web-layer]] | `web/handlers_test` (5), `web/browser_test` (21), `web/sse/flow_test` (20), `web/reactive/actions_test` (2), `web/reactive/transform_test` (7) | **good** — handlers, browser, SSE, reactive |
| [[components/agent-system]] | `ai/agent_test` (35), `ai/agent/log_test` (9), `ai/claude_test` (22), `ai/gemini_test` (14), `ai/datalevin_test` (14), `ai_test` (27), `agent/env_test` (14), `orchestrator/session_test` (15) | **good** — extensive |
| [[components/dev-tools]] | `dev/hook_test` (19), `dev/verify_test` (7), `dev/test_select_test` (6), `dev/context_test` (10), `dev/clojure_replace_test` (27), `dev/lint_test` (8), `dev/suggestions_test` (4), `dev/analysis_test` (6), `dev/codebase_test` (9), `dev/review_test` (10), `dev/compliance_test` (8), `dev/repair_test` (5) | **good** — very thorough |

### Other Test Files (not mapped to components)

| Test File | Tests | Covers |
|-----------|-------|--------|
| `repl_test` | 6 | REPL form entity operations |
| `repl/context_test` | 4 | REPL context resolution |
| `repl/graduate_test` | 5 | REPL graduation workflow |
| `getting_started_test` | 8 | Getting-started tutorial namespace |
| `health/workout_test` | 14 | Health domain workout tracking |
| `health/metrics_test` | 5 | Health domain metrics |
| `polymarket/api_test` | 14 | Polymarket API (mostly `^:integration`) |
| `polymarket/analysis_test` | 13 | Polymarket analysis |
| `log_parsing_test` | 4 | Log parsing utilities |
| `hook_test_scratch_test` | 2 | Hook test scratch |

## What's NOT Tested

Source namespaces with **no corresponding test file**:

| Source Namespace | Why It Matters |
|-----------------|---------------|
| `seon.system` | Integrant system map assembly — tested indirectly via config_test but no dedicated tests |
| `seon.config` | Aero config loading — tested via `system/config_test` indirectly |
| `seon.logging` | Timbre/logback setup — no tests |
| `seon.schema` | Core schema registry — tested heavily via `db/schema_test` and `db/pipeline_test`, but no dedicated `schema_test.clj` at root level |
| `seon.db.datalevin.conn` | Connection manager with per-DB locking — no dedicated tests (critical concurrency code) |
| `seon.db.datalevin.server` | Datalevin server lifecycle — no tests |
| `seon.db.datalevin.reader` | Flow reader process — no dedicated tests (covered indirectly by flow tests) |
| `seon.db.tx` | Transaction metadata — schema tested in pipeline_test, but no dedicated tx_test |
| `seon.web.server` | HTTP server startup — no tests |
| `seon.web.routes` | Route table — no dedicated tests |
| `seon.web.html` | HTML rendering helpers — no tests |
| `seon.web.components` | UI components — no tests |
| `seon.web.tailwind` | Tailwind CSS generation — no tests |
| `seon.web.caddy` | Caddy reverse proxy config — no tests |
| `seon.web.brotli` | Brotli compression — no tests |
| `seon.web.logs` | Log viewing UI — no tests |
| `seon.web.flows` | Flow UI views — no tests |
| `seon.web.agents` | Agent UI views — no tests |
| `seon.web.namespace` | Namespace UI — no tests |
| `seon.flow.agent_runner` | Agent JVM runner — no dedicated tests (covered by pool integration) |
| `seon.dev.instrumentation` | Malli instrumentation lifecycle — no tests |
| `seon.ns.introspect` | Namespace introspection — no tests |
| `seon.ns.example` | Example namespace — no tests |
| `seon.health` | Health domain root — no tests |
| `seon.health.workout.render` | Workout renderer — no tests |
| `seon.render.default_page` | Default page renderer — no tests |
| `seon.render.example` | Example renderer — no tests |
| `seon.ui.viewer` | UI viewer — no tests |
| `seon.ai.claude.sdk` | Claude SDK wrapper — no tests |
| `seon.claude.exploration` | Exploration scratch — no tests |
| `seon.getting_started.render` | Getting-started renderer — no tests |

**Notable gaps**:

- `seon.db.datalevin.conn` — the connection manager with per-DB locking is critical concurrency infrastructure with no dedicated tests
- `seon.web.*` UI namespaces — the entire web view layer (components, logs, flows, agents, namespace views) has no unit tests
- `seon.dev.instrumentation` — Malli instrumentation lifecycle is untested
- `seon.flow.agent_runner` — agent JVM spawning logic only covered indirectly

## Key Files

| File | Purpose |
|------|---------|
| `src/seon/dev/test.clj` | Test runner — structured results, crash resilience |
| `src/seon/dev/verify.clj` | Unit + generative test orchestration for dev hook |
| `src/seon/dev/test_select.clj` | Graph-aware selective test execution |
| `test/seon/test_utils.clj` | Shared fixtures, temp DB helpers |
| `test/seon/db/pipeline_test.clj` | `assert-pipeline-roundtrip!` — the generative contract test |
| `test/seon/db/validation_test.clj` | Malli validation gate in `db/transact!` |
| `test/seon/db/schema_roundtrip_test.clj` | Malli->Datalevin type roundtrip properties |
