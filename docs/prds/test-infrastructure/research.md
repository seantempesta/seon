# Test Infrastructure Research Report

**Date**: 2026-03-06
**Author**: Claude Opus 4.6 (research agent)
**Status**: Research complete, ready for PRD

---

## 1. Current State Audit

### 1.1 Test File Inventory

Seon has **68 test files** across the `test/` directory. Every file uses `clojure.test` as the foundation. There is no use of `expectations` or `defspec` from test.check directly.

| Test File | DB Pattern | Fixture Type | Notes |
|-----------|-----------|-------------|-------|
| `seon.db-test` | `tu/with-temp-conn` + fake manager + `*direct-mode*` | Per-test helper fn (`with-named-db`) | Manual setup/teardown |
| `seon.db.validation-test` | `tu/with-temp-conn` + fake manager + `*direct-mode*` | Per-test helper fn (`with-validation-conn`) | Same pattern as db-test |
| `seon.db.pipeline-test` | `tu/with-temp-conn` inside each test | None (per-test) | Generative roundtrip utility |
| `seon.db.schema-roundtrip-test` | `tu/with-temp-conn` inside each test | None (per-test) | Direct Datalevin, no `seon.db` |
| `seon.db.schema-test` | None (pure functions) | None | Tests bridge logic only |
| `seon.db.consistency-test` | None (pure functions) | None | Schema validation only |
| `seon.db.datalevin.writer-test` | `tu/with-temp-conn` per test | None (per-test) | Tests flow step function |
| `seon.runtime-test` | Manual temp dir + `d/create-conn` + fake manager | `use-fixtures :each` | Most boilerplate of any test (~45 lines) |
| `seon.ctx-test` | Manual temp dir + `d/create-conn` + fake manager | `use-fixtures :each` | Same heavy pattern as runtime-test (~37 lines) |
| `seon.graph.query-test` | Manual temp dir + `d/create-conn` + fake manager | `use-fixtures :once` | Populates graph once before all tests |
| `seon.health.workout-test` | Manual temp dir + `d/create-conn` + fake manager | `use-fixtures :each` | Uses dynamic var `*conn*` |
| `seon.flow.topology-test` | No DB | `use-fixtures :each` | Cleans promise atom between tests |
| `seon.flow.msg-test` | No DB | None | Pure schema + Nippy roundtrip tests |
| `seon.core-test` | No DB | None | Trivial port check |
| `seon.flow.*-test` (8 files) | Varies | Varies | Mix of patterns |
| `seon.dev.*-test` (12 files) | Varies | Varies | Dev tooling tests |
| `seon.graph.*-test` (5 files) | Various manual | Various | No standard pattern |
| `seon.web.*-test` (4 files) | Varies | Varies | Web handler tests |
| `seon.ai*-test` (5 files) | Varies | Varies | AI provider tests |
| `seon.repl*-test` (3 files) | Varies | Varies | REPL subsystem tests |
| Other (14 files) | Varies | Varies | Domain, orchestrator, render tests |

### 1.2 Current Test Infrastructure Components

#### `test/seon/test_utils.clj`

This is the core test utility file. It provides:

- **`with-temp-conn`** -- The primary isolation primitive. Creates a temp Datalevin directory, opens `d/create-conn` with `:flags (conj datalevin.constants/default-env-flags :nosync)` for speed, runs the test function with the connection, then closes the connection and deletes the temp directory.
- **`with-test-datalevin`** -- Builds a fake `*conn-manager*` mapping `:seon.ai` to a temp connection, binds `*direct-mode*` to true. Used only by AI tests.
- **`*init-db-size*`** -- Reduced to 10 MiB globally to prevent OOM from many test connections being created during a full test run.
- **Helper functions**: `gen-uuid`, `days-ago`, `days-from-now`.

#### `env/dev/clj/user.clj` (lines 280-370)

REPL-facing test functions:

- `run-tests` -- delegates to `seon.dev.test/test` (single ns) or `test-all` (all unit tests)
- `test-affected` -- dependency-aware testing via the code graph
- `test-gen` -- generative tests on schema-annotated functions
- `last-test-results` / `test-history` -- inspect stored results

#### `src/seon/dev/test.clj`

The structured test runner that powers REPL-based testing:

- Runs tests inside the live JVM by rebinding `clojure.test/report`
- For each namespace: `remove-ns` + `require :reload` to get fresh code
- Captures structured results: `::success`, `::test-count`, `::pass-count`, `::fail-count`, `::error-count`, `::failures` (with `::file`, `::line`, `::expected`, `::actual`)
- Stores results in an atom for later inspection (`last-results`, `results-history`)
- `safe-run-ns-tests` catches `Throwable` to prevent LMDB native crashes from killing the REPL session
- `test-all` finds all `*-test` namespaces, excludes `^:integration` metadata, runs each in isolation
- `test-gen` delegates to `seon.dev.verify/run-gen-tests` which uses Malli's `function-checker`

#### `.claude/seon-hook.edn`

The dev hook configuration:

- **Unit tests**: enabled, `block-on-fail: true`
- **Generative tests**: enabled, 10 iterations, **`block-on-fail: false`** (disabled due to pre-existing issue where some schemas produce nil/throw on generation)
- **AI review**: Gemini review with 60-second rate limiting
- **Compliance checking**: enabled, non-blocking

#### `tests.edn` (Kaocha Configuration)

```clojure
#kaocha/v1
{:tests [{:id :unit
          :test-paths ["test"]
          :ns-patterns [".*-test$"]
          :kaocha.filter/skip-meta [:integration]}
         {:id :integration
          :test-paths ["test"]
          :ns-patterns [".*-test$"]
          :kaocha.filter/focus-meta [:integration]}]
 :plugins [:kaocha.plugin/profiling
           :kaocha.plugin/capture-output]}
```

Two test suites (unit, integration), profiling plugin, capture-output plugin. Used only by `bin/test` CLI fallback.

### 1.3 What Works Well

1. **`with-temp-conn`** creates truly isolated Datalevin databases per test invocation. Uses `:nosync` for speed. Cleanup is reliable.
2. **`*direct-mode*`** cleanly bypasses the flow infrastructure, letting tests hit Datalevin directly without needing the infrastructure flow running.
3. **Pipeline test utility** (`assert-pipeline-roundtrip!` in `pipeline-test.clj`) is excellent -- generatively tests the full Malli schema -> Datalevin schema derivation -> entity generation -> transact -> pull -> validation roundtrip path. Includes constraint checking (no `:any`, no `[:maybe X]`, namespaced keys).
4. **Structured test results** from `seon.dev.test` give agents machine-readable pass/fail data with file/line information.
5. **10 MiB DB size cap** prevents OOM when many test connections are created.
6. **`safe-run-ns-tests`** catches `Throwable` so an LMDB crash in one test namespace does not kill the entire test run.

### 1.4 What Is Broken or Inconsistent

#### Problem 1: No Standard Fixture Pattern

Tests that need a database each reinvent the wheel. Here is the boilerplate from four representative files:

**`runtime-test.clj`** (lines 17-59, ~45 lines):

```clojure
(def ^:private test-dir (atom nil))
(def ^:private test-conn (atom nil))

(defn- temp-dir [] ...)
(defn- setup-datalevin! [] ...)   ;; d/create-conn + merged schema
(defn- teardown-datalevin! [] ...) ;; close + delete dir
(defn- fake-conn-manager [conn] ...) ;; maps :seon.runtime

(use-fixtures :each
  (fn [f]
    (let [conn (setup-datalevin!)]
      (try
        (binding [db/*direct-mode* true
                  db/*conn-manager* (fake-conn-manager conn)]
          (f))
        (finally (teardown-datalevin!))))))
```

**`ctx-test.clj`** (lines 16-44, ~37 lines):

```clojure
(def ^:private test-dir (atom nil))
(def ^:private test-conn (atom nil))

(defn- temp-dir [] ...)
(defn- setup-datalevin! [] ...)   ;; d/create-conn + ctx schema
(defn- teardown-datalevin! [] ...) ;; close + delete dir

(use-fixtures :each
  (fn [f]
    (setup-datalevin!)
    (let [fake-mgr {::dl-conn/connections (atom {:test-ctx {::dl-conn/connection @test-conn}})}]
      (binding [db/*direct-mode* true
                db/*conn-manager* fake-mgr]
        (try (f) (finally (teardown-datalevin!)))))))
```

**`graph/query-test.clj`** (lines 21-62, ~40 lines):

```clojure
(def ^:private test-conn (atom nil))
(def ^:private test-dir (atom nil))
(def ^:private test-mgr (atom nil))

(defn- make-temp-dir [] ...)
(defn- delete-dir [path] ...)
(defn with-populated-graph [f] ...) ;; d/create-conn + ingest schema + populate

(use-fixtures :once with-populated-graph)
```

**`health/workout-test.clj`** (lines 26-43, ~30 lines):

```clojure
(def ^:dynamic *conn* nil)

(defn with-temp-datalevin [f]
  (let [dir (str "tmp/test-workout-" (System/currentTimeMillis))
        conn (d/create-conn dir ingest/datalevin-schema)
        fake-mgr {::conn/port 0
                  ::conn/connections (atom {:seon.runtime {::conn/connection conn}})}]
    (try
      (binding [*conn* conn
                db/*direct-mode* true
                db/*conn-manager* fake-mgr]
        (f))
      (finally ...))))

(use-fixtures :each with-temp-datalevin)
```

Every file manually creates temp dirs, connections, fake managers, bindings, and cleanup. The structure is identical -- only the schema and db-name mapping differ.

#### Problem 2: Tests Can Hit the Production Database

The `seon.dev.test/test-all` function runs all test namespaces inside the live JVM. Tests that do NOT use `with-temp-conn` or manual fixtures will hit the **production Datalevin server** on port 8898. This causes:

- Data pollution between tests and production
- Crashes when test data violates production schema expectations
- Flaky tests that depend on production state
- The production database accumulating test garbage

#### Problem 3: Inconsistent db-name Mapping

The fake connection managers in different tests map different keywords:

| Test File | db-name Mapped | Conn Manager Key |
|-----------|---------------|------------------|
| `db-test` | `:seon` | `{:seon {::conn/connection conn}}` |
| `validation-test` | `:test` | `{:test {::conn/connection conn}}` |
| `runtime-test` | `:seon.runtime` | `{:seon.runtime {::conn/connection conn}}` |
| `ctx-test` | `:test-ctx` | `{:test-ctx {::conn/connection conn}}` |
| `graph/query-test` | `:seon.runtime` | `{:seon.runtime {::conn/connection conn}}` |
| `workout-test` | `:seon.runtime` | `{:seon.runtime {::conn/connection conn}}` |

This means tests cannot be composed. A function that calls `db/transact! :seon.runtime` will fail if the fixture only mapped `:test`. A test helper that works in `runtime-test` will break if used in `ctx-test`.

#### Problem 4: Generative Test Blocking Is Disabled

The hook has `block-on-fail: false` for generative tests (line 28-29 of `seon-hook.edn`):

```clojure
:generative {:enabled true
             :num-tests 10
             :block-on-fail false}
```

Comment says: "temporarily disabled due to pre-existing issue where `::xtdb-node` schema generates nil, causing all context.clj functions to fail."

This means schema violations discovered by generative testing are **silently tolerated**. The fix is not to disable blocking globally but to filter out functions whose schemas contain un-generable fields.

#### Problem 5: No Standard `*ctx*` Atom in Test Fixtures

Tests that need a `*ctx*` atom (the namespace-scoped reactive state) must create one manually using `ctx/create!` and `ctx/destroy!`. There is no standard fixture for this.

#### Problem 6: Schema Registration Side Effects

Test files register schemas at load time. For example, `validation-test.clj` (lines 17-48) registers 9 schemas:

```clojure
(schema/register! ::id [:int {:db/unique :db.unique/identity ...}])
(schema/register! ::name [:string {:min 1 :max 200 ...}])
;; ... 7 more
```

These persist in the global mutable registry (`seon.schema/*schemas` atom, which is a `defonce`) and survive namespace reloads. While unlikely to collide with production schemas (they use test-namespace-qualified keywords like `:seon.db.validation-test/id`), this is an implicit coupling.

---

## 2. Database Isolation Options

### 2.1 How Datalevin Creates Databases

From reading `reference-code/datalevin/src/datalevin/storage.clj` (line 1578):

```clojure
(defn open
  ([dir schema {:keys [kv-opts ...] :as opts}]
   (let [dir  (or dir (u/tmp-dir (str "datalevin-" (UUID/randomUUID))))
         lmdb (lmdb/open-kv dir kv-opts)]
     ...)))
```

When `dir` is `nil`, Datalevin creates a temp directory automatically using `u/tmp-dir`. When `dir` is a path, it opens or creates at that path. **There is no true in-memory mode** -- Datalevin always uses LMDB files on disk. However, with `:nosync` flag (which `test_utils.clj` already uses), writes are not fsynced to disk, making it nearly as fast as in-memory.

From `reference-code/datalevin/src/datalevin/conn.clj` (lines 42-46):

```clojure
(defn create-conn
  ([] (conn-from-db (db/empty-db)))              ;; nil dir -> auto temp dir
  ([dir] (conn-from-db (db/empty-db dir)))
  ([dir schema] (conn-from-db (db/empty-db dir schema)))
  ([dir schema opts] (conn-from-db (db/empty-db dir schema opts))))
```

`d/create-conn` with no args creates a fully isolated database with an auto-generated temp directory. `d/create-conn` with a dir and schema creates a database at that path.

From `reference-code/datalevin/src/datalevin/db.clj` (lines 596-603):

```clojure
(defn ^DB empty-db
  ([] (empty-db nil nil))
  ([dir] (empty-db dir nil))
  ([dir schema] (empty-db dir schema nil))
  ([dir schema opts]
   {:pre [(or (nil? schema) (map? schema))]}
   (validate-schema schema)
   (new-db (open-store dir schema opts))))
```

Schema must be a map (not a Malli schema). The `open-store` function dispatches to either a remote store (for `dtlv://` URIs) or a local store.

### 2.2 Option A: Per-Namespace Temp Directory (Current Approach, Standardized)

**How it works**: Each test namespace gets a fresh `d/create-conn` with a unique temp directory. A standardized fixture creates the connection, builds the fake `*conn-manager*` mapping **all commonly used db-names** to the same connection, binds `*direct-mode*`, and cleans up on exit.

**Pros**:

- Already proven -- each test file does this manually today
- Complete isolation -- no shared state between test namespaces
- Fast with `:nosync` flags (~20-50ms per connection create/destroy)
- No server dependency -- tests work even if Datalevin server is down
- No network overhead -- direct LMDB access

**Cons**:

- Each namespace creates/destroys a connection (LMDB files) -- overhead ~20-50ms per namespace
- With 68 test files, total overhead ~1-3 seconds (acceptable)
- Must merge schemas from all modules if a test crosses module boundaries

### 2.3 Option B: No-Args `d/create-conn` (Auto Temp Dir)

**How it works**: Use `(d/create-conn)` with no arguments. Datalevin creates an anonymous temp directory via `u/tmp-dir`.

**Pros**:

- Simplest possible creation -- one function call
- No need to generate temp dir names

**Cons**:

- No schema at creation time -- must use `d/update-schema` after
- Temp dir cleanup depends on OS temp dir pruning (not deterministic)
- We lose control of dir location for debugging failed tests
- Still need the fake conn-manager and binding boilerplate

### 2.4 Option C: Single Shared Test Server (Separate Port)

**How it works**: Start a second Datalevin server on a different port (e.g., 8899) dedicated to tests. Tests connect via TCP like production but to the test server.

**Pros**:

- Tests the full connection path (TCP, client protocol)
- Can test reconnection logic and connection pooling
- Closer to production topology

**Cons**:

- Complex setup -- need to manage a second server process
- Shared state between test namespaces (defeats isolation)
- Slower (TCP overhead vs direct LMDB access)
- Overkill for unit tests
- Requires coordination to avoid test interference

### 2.5 Option D: Per-Test `d/create-conn` with Automatic Schema Derivation

**How it works**: Like Option A, but the schema is automatically derived from all registered Malli entity schemas using `seon.db.schema/malli-map->datalevin-schema`. The `seon.db.schema/persisted-schemas` function already returns all registered entity schemas.

**Pros**:

- No manual schema specification per test
- Schema always matches current state of the codebase
- Can be combined with Option A's fixture pattern

**Cons**:

- Slightly slower (schema derivation on each fixture invocation) -- could be cached
- May include schemas the test doesn't need (no harm, just slightly larger LMDB)
- Requires all source namespaces to be loaded (which they are in the dev REPL)

### 2.6 Recommendation: Option A + D Combined

The recommended approach combines the proven temp-dir pattern (Option A) with automatic schema derivation (Option D):

1. Collect all persisted entity schemas from `seon.db.schema/persisted-schemas`
2. Derive Datalevin schemas via `malli-map->datalevin-schema` for each
3. Merge into a single Datalevin schema map
4. Cache the result (invalidate on schema registry change)
5. Use this merged schema in `d/create-conn`
6. Map all standard db-name keywords to the connection

---

## 3. Test Harness Design

### 3.1 The Core Problem

Every DB-using test file manually creates ~30-45 lines of boilerplate that is substantively identical. The differences between files are:

- Which Datalevin schema to use (each file picks a subset)
- Which db-name keyword to map in the fake conn-manager
- Whether to use `:once` or `:each` fixtures
- Whether to expose the raw connection via a dynamic var

### 3.2 Proposed Standardized Fixture: `with-test-db`

A single fixture function in `test_utils.clj`:

```clojure
(def ^:dynamic *test-conn*
  "Raw Datalevin connection available inside with-test-db fixtures.
   Use for direct d/transact!, d/pull, d/q when bypassing seon.db API."
  nil)

(defn with-test-db
  "Standard test fixture. Creates an isolated Datalevin database with all
   known entity schemas merged, maps all standard db-names to it, and
   binds *direct-mode*.

   Usage as :each fixture:
     (use-fixtures :each tu/with-test-db)

   Usage as :once fixture:
     (use-fixtures :once tu/with-test-db)

   Inside tests:
     - Use db/transact!, db/query etc. with any db-name keyword
     - Use *test-conn* for raw Datalevin access (d/transact!, d/pull, d/q)"
  [f]
  (with-temp-conn (merged-test-schema)
    (fn [conn]
      (let [all-db-names {:seon               {::conn/connection conn}
                          :seon.runtime       {::conn/connection conn}
                          :seon.ai            {::conn/connection conn}
                          :test               {::conn/connection conn}
                          :test-ctx           {::conn/connection conn}}
            fake-mgr {::conn/port 0
                      ::conn/connections (atom all-db-names)}]
        (binding [*test-conn* conn
                  db/*direct-mode* true
                  db/*conn-manager* fake-mgr]
          (f))))))
```

The `merged-test-schema` function would:

1. Call `seon.db.schema/persisted-schemas` to get all registered entity schemas
2. Derive Datalevin schema for each via `malli-map->datalevin-schema`
3. Merge into one map
4. Cache the result (defonce + atom, invalidated if schema count changes)

### 3.3 What This Eliminates

Each migrated test file would go from ~30-45 lines of boilerplate to:

```clojure
(use-fixtures :each tu/with-test-db)
```

The following per-file artifacts would be deleted:

- `test-dir` atom
- `test-conn` atom
- `test-mgr` atom
- `temp-dir` function
- `setup-datalevin!` function
- `teardown-datalevin!` function
- `fake-conn-manager` function
- `with-named-db` function
- `with-validation-conn` function
- `with-temp-datalevin` function

### 3.4 Fixture Hierarchy

| Level | When to Use | Example |
|-------|------------|---------|
| `(use-fixtures :each tu/with-test-db)` | Tests that need isolation between individual test functions (most common) | `runtime-test`, `ctx-test`, `validation-test` |
| `(use-fixtures :once tu/with-test-db)` | Tests that build up state across test functions and query it | `graph/query-test` (populates graph once, queries in multiple tests) |
| `(tu/with-temp-conn custom-schema (fn [conn] ...))` inline | Tests with custom synthetic schemas | `pipeline-test` (tests synthetic schemas, not production schemas) |

The standardized `with-test-db` works for both `:each` and `:once`. For `:once`, tests share a connection and must not conflict on identity attributes; for `:each`, each test gets a pristine database.

### 3.5 What About `*ctx*`?

For tests that need a `*ctx*` atom (reactive namespace state), add a composable companion:

```clojure
(def ^:dynamic *test-ctx*
  "ctx atom available inside with-test-ctx fixtures."
  nil)

(defn with-test-ctx
  "Fixture that creates a test ctx atom. Compose with with-test-db:
     (use-fixtures :each tu/with-test-db tu/with-test-ctx)"
  [f]
  (let [ctx-id (str "test-" (System/nanoTime))
        a (ctx/create! {::ctx/instance-id ctx-id
                        ::ctx/persist? false
                        ::ctx/sse-push? false})]
    (binding [*test-ctx* a]
      (try (f)
           (finally (ctx/destroy! {::ctx/instance-id ctx-id}))))))
```

### 3.6 Integrant Test System

For true integration tests that need the full system (flow infrastructure, SSE, etc.), the existing `user/test-prep!` function (line 41 of `user.clj`) already configures Integrant for a test profile. This is unused today but could be activated for a dedicated integration test suite.

However, most tests should use `with-test-db` and `*direct-mode*`. The flow infrastructure adds complexity (promise-based async, injection, timeouts) that is irrelevant to most unit tests.

---

## 4. Generative Testing Audit

### 4.1 Schema Registration Scale

Seon has **920 `schema/register!` calls across 68 source files**. This is extensive coverage. The schemas fall into these categories:

**Well-generating schemas (vast majority)**:

- Simple types: `:string`, `:int`, `:double`, `:boolean`, `:keyword`, `:uuid`, `:inst` -- all generate correctly
- Constrained types: `[:string {:min 1 :max 200}]`, `[:int {:min 0}]`, `[:double {:min 0.0 :max 1.0}]` -- generators respect constraints
- Enums: `[:enum :a :b :c]` -- generates uniformly from enum values
- Optional fields: `{:optional true} :string` -- generates both present (with valid value) and absent
- Collections: `[:set :keyword]`, `[:vector :string]` -- generates varied collections
- Maps: `[:map [:key :type] ...]` -- generates valid map entities

**Schemas with custom generators (11 occurrences)**:

1. **`:seon.flow/dynamic`** (wire protocol field, `seon.schema` line 59-66):

   ```clojure
   {:gen/schema [:or :int :string :keyword :boolean
                 [:vector :int] [:map-of :keyword :string]]
    :gen/fmap identity}
   ```

   Generates a union of basic types. Reasonable for wire protocol testing.

2. **`:seon.db/ref`** (entity reference, `seon.schema` lines 74-84):

   ```clojure
   {:gen/schema [:or [:int {:min 1}] [:tuple :keyword :string]]
    :gen/fmap identity}
   ```

   Generates positive ints (entity IDs) or `[:keyword "string"]` lookup refs. Lookup refs require target entities to exist at transact time.

3. **Throwing generators** (6 occurrences for un-generable runtime objects):
   - `seon.orchestrator.session` lines 96, 101: connection-manager, pool
   - `seon.ctx` lines 95, 100, 160: render-fn, channel, database connection
   - `seon.ns.lifecycle` lines 50, 55: ctx atom, render fn

   Pattern: `:gen/fmap (fn [_] (throw (ex-info "Cannot generate X" {})))`. Correct for runtime objects (atoms, channels, connections) but means any composite schema containing these fields fails generative testing.

### 4.2 Generator Quality Assessment of Entity Schemas

The `seon.db.schema/persisted-schemas` function returns all entity schemas registered for pipeline validation. Current count: 15+ schemas. Each has been verified by `assert-pipeline-roundtrip!` in `pipeline-test.clj`.

Entity schemas that **generate well**:

- `seon.ctx/ctx-entity-schema` -- all scalar fields, roundtrips 20/20
- `seon.repl/form-entity-schema` -- all scalar fields, roundtrips 20/20
- `seon.flow.trace/entity-schema` -- all scalar fields (tempid roundtrip), 20/20
- `seon.runtime/runtime-entity-schema` -- all scalar fields, roundtrips 20/20
- `seon.runtime/flow-snap-entity-schema` -- all scalar fields, roundtrips 20/20
- `seon.db.tx/entity-schema` -- all scalar fields (tempid roundtrip), 20/20
- `seon.graph.ingest/ns-entity-schema`, `spec-entity-schema`, `var-entity-schema`, `ns-dep-entity-schema` -- all roundtrip 20/20

Entity schemas that **need special handling for refs**:

- `seon.runtime/agent-run-entity-schema` -- has `:seon.agent.run/runtime` ref. Tested by stripping the ref field for generative testing, then testing refs manually with pre-created target entities.
- `seon.graph.ingest/fn-entity-schema` -- has `:seon.fn/input-spec` and `:seon.fn/output-spec` refs. Same pattern.
- `seon.graph.ingest/call-entity-schema` -- has `:seon.call/from-fn` and `:seon.call/to-fn` refs. Same pattern.

The pattern of "exclude refs from generative schema, test refs manually" is established and works well.

### 4.3 Schema Authoring Guidelines for Generator-Friendly Definitions

Based on reading `reference-code/malli/src/malli/generator.cljc` (particularly the min-max handling at lines 77-84, the collection generation at lines 97-111, and the map generation logic):

**DO: Use constrained types with meaningful bounds**

```clojure
[:string {:min 1 :max 200}]              ;; Not unbounded :string
[:int {:min 0}]                           ;; Not bare :int
[:double {:min 0.0 :max 1.0}]            ;; Not bare :double
```

**DO: Use `:gen/min` and `:gen/max` to narrow generation without changing validation**

```clojure
;; Validates any non-negative int, generates 1-100 for fast tests
[:int {:min 0 :gen/min 1 :gen/max 100}]
```

From malli source (lines 78-84): `:gen/min` and `:gen/max` override `:min` and `:max` for generation only. Malli validates that gen bounds are within schema bounds.

**DO: Use `:gen/elements` for bounded string domains**

```clojure
;; Instead of unbounded :string
[:string {:gen/elements ["seon.health" "seon.trading" "seon.graph"]}]
```

**DO NOT: Use `:gen/fmap (fn [_] (throw ...))` on fields within entity schemas that will be generatively tested.** Instead, exclude the field from the schema used for generation (as `pipeline-test` already does).

**DO NOT: Use `:any` or `:some` in persisted schemas.** These cannot generate meaningful values and are banned by the consistency check.

**DO: Use `[:enum ...]` for small fixed domains instead of `:keyword`**

```clojure
;; Better than :keyword - generates from known values
[:enum :running :stopped :crashed :paused]
```

### 4.4 How test.check Properties Should Integrate With Malli

From reading `reference-code/test.check/`:

**`quick-check`** (lines 59-229 of `check.cljc`) is the core function. It runs N trials, and on failure, enters `shrink-loop` (lines 242-295) which does a modified depth-first search of the shrink tree to find the smallest failing input. Returns a structured map with `:pass?`, `:fail`, `:shrunk`, `:seed`, etc.

**`defspec`** (from `clojure.test.check.clojure-test`, lines 75-98) is the macro that creates a `clojure.test` test var backed by `quick-check`:

```clojure
(defspec my-property 100
  (prop/for-all [x gen-x, y gen-y]
    (some-property x y)))
```

**`prop/for-all`** (from `properties.cljc`, lines 68-95) is the macro that creates a property from bindings and a body:

```clojure
(prop/for-all [entity (mg/generator my-entity-schema)]
  (let [pulled (roundtrip! conn entity)]
    (= (normalize entity) (normalize pulled))))
```

**Seon does NOT use `defspec` anywhere.** Instead, all generative tests use a manual loop:

```clojure
(doseq [sample (mg/sample ::schema {:size 20})]
  (is (some-property sample)))
```

**The manual loop lacks shrinking.** When a test fails, `doseq` + `mg/sample` gives you the raw failing input with no attempt to find a smaller reproducer. With `defspec` + `prop/for-all`, test.check automatically shrinks to the smallest failing case, which is invaluable for debugging.

**Malli's `function-checker`** (from `reference-code/malli/src/malli/generator.cljc`, lines 526-556) already wraps test.check internally:

```clojure
(defn function-checker
  ([?schema {::keys [=>iterations] :or {=>iterations 100} :as options}]
   ;; ...
   (fn [f]
     (let [{:keys [result shrunk]} (->> (prop/for-all* [input-generator] #(validate f %))
                                        (check/quick-check =>iterations))
           smallest (-> shrunk :smallest first)]
       ;; returns shrunk failure data
       ))))
```

So schema-based generative tests (via `user/test-gen`) already get shrinking. Only the manual `doseq` pattern in test files misses it.

### 4.5 Malli `check` Function

From `reference-code/malli/src/malli/generator.cljc` (lines 558-562):

```clojure
(defn check
  ([?schema f options]
   (let [schema (m/schema ?schema options)]
     (m/explain (m/-update-options schema
                  #(assoc % ::m/function-checker function-checker)) f))))
```

`mg/check` validates that a function conforms to its `:malli/schema` by generating inputs and checking outputs. Returns an explanation on failure, nil on success. This is what `seon.dev.verify` uses under the hood.

---

## 5. Reference Library Insights

### 5.1 test.check

**Source examined**: `reference-code/test.check/src/main/clojure/clojure/test/check.cljc`, `properties.cljc`, `clojure_test.cljc`

**Key insight**: test.check's primary value is **shrinking**, not random generation. The `shrink-loop` function (lines 242-295) does a modified depth-first search:

- If a shrunk value passes: continue searching siblings at this depth (but don't backtrack)
- If a shrunk value fails: search its children (go deeper)
- Returns the left-most failing example at the depth where a passing example was found

This finds minimal failing cases efficiently. Without it (as in Seon's `doseq` pattern), failures are reported at whatever random size test.check happened to generate, making debugging harder.

**`defspec` integration** (lines 75-98 of `clojure_test.cljc`):

```clojure
(defmacro defspec
  ([name options property]
   `(defn ~(vary-meta name assoc
                      ::defspec true
                      :test `(fn []
                               (assert-check (assoc (~name) :test-var (str '~name)))))
      ...)))
```

`defspec` creates a function that is also a `clojure.test` test (via `:test` metadata). It can be run standalone or via `clojure.test/run-tests`. The `*default-test-count*` is 100.

**Reporter integration**: `default-reporter-fn` (lines 26-57) reports via `clojure.test/report` with custom types (`:trial`, `:failure`, `:shrinking`, `:shrunk`, `:complete`). This means kaocha and Seon's custom reporter can capture test.check results.

**What Seon should adopt**:

1. Use `defspec` for new property-based tests that benefit from shrinking
2. Malli generators plug directly into `prop/for-all` via `(mg/generator schema)`
3. Keep the `assert-pipeline-roundtrip!` utility for the Datalevin pipeline (too complex for simple `defspec`)

### 5.2 expectations

**Source examined**: `reference-code/expectations/src/cljc/expectations.cljc`

The `expectations` library is an **alternative test framework**, not an extension of `clojure.test`. Key characteristics:

- Uses `expect` macro instead of `deftest`/`is`
- Has its own global state (`*report-counters*`, `run-tests-on-shutdown`)
- Custom reporting with ANSI colors
- `in` function for partial collection matching
- Automatic difference reporting via `clojure.data/diff`
- JUnit runner integration

**Assessment**: expectations is **not compatible with `clojure.test`**. Adopting it would require:

- Rewriting all 68 test files
- Replacing the structured test runner
- Replacing the dev hook test integration
- Replacing kaocha configuration

**Recommendation**: **Do NOT adopt.** The migration cost is enormous and the benefits (slightly better error messages, `in` for partial matching) are achievable with `clojure.test` assertion helpers. The `expectations` library is also less actively maintained than the `clojure.test` ecosystem.

### 5.3 kaocha

**Source examined**: `reference-code/kaocha/src/kaocha/plugin/hooks.clj`, `testable.clj`, `plugin.clj`, `plugin/randomize.clj`, `plugin/filter.clj`, `plugin/profiling.clj`

**What we currently use**:

- Two test suites (unit, integration) via `tests.edn`
- `:kaocha.plugin/profiling` -- reports slowest tests
- `:kaocha.plugin/capture-output` -- captures stdout/stderr per test

**What we are underusing**:

1. **`kaocha.plugin/hooks`** (reading `hooks.clj`): Configures pre/post hooks directly in `tests.edn`:
   - `:kaocha.hooks/pre-test` -- called before each testable (suite, namespace, or var)
   - `:kaocha.hooks/post-test` -- called after each testable
   - `:kaocha.hooks/wrap-run` -- wraps the entire test run function
   - `:kaocha.hooks/pre-load` / `:kaocha.hooks/post-load` -- around test loading

   We could use `:kaocha.hooks/wrap-run` to globally bind `*direct-mode*` and a fake conn-manager for the CLI test runner (`bin/test`). This would prevent accidental production DB access even from kaocha runs.

2. **`kaocha.plugin/randomize`**: Randomizes test execution order within a suite. Would detect order-dependent failures (tests that pass only when run after another test that sets up state).

3. **`kaocha.plugin/filter`** (already partially configured): We use `:kaocha.filter/skip-meta [:integration]` but could add more metadata tags:
   - `:db` -- tests that need a database
   - `:pure` -- tests that are pure functions (no DB, no side effects)
   - `:generative` -- tests that use Malli generators
   - `:slow` -- tests that take >1 second

4. **Custom test types**: kaocha's `testable.clj` supports custom test types via multimethods (`-load`, `-run`). We could create a `:seon/pipeline-test` type that automatically runs `assert-pipeline-roundtrip!` for all registered entity schemas. This would make pipeline testing declarative rather than requiring explicit test functions.

**What we should NOT use**:

- `kaocha.type/spec.test.check` -- for `clojure.spec`, not Malli
- `kaocha.plugin/orchestra` -- for `clojure.spec` instrumentation, not Malli

### 5.4 Malli Generator Best Practices

**Source examined**: `reference-code/malli/src/malli/generator.cljc` (full file, ~600 lines)

Key patterns from the source:

1. **`:gen/elements` for bounded domains** (not used in Seon):

   ```clojure
   [:string {:gen/elements ["Alice" "Bob" "Carol"]}]
   ```

   Generates from a fixed set. Much faster than random string generation.

2. **`:gen/min` / `:gen/max` for narrowing** (lines 77-84):

   ```clojure
   (let [{:keys [min max] gen-min :gen/min gen-max :gen/max} (m/properties schema options)]
     ;; gen-min/gen-max override min/max for generation only
     {:min (or gen-min min)
      :max (or gen-max max)})
   ```

   Validates that gen bounds are within schema bounds. Use to generate small values for fast tests while validating a wider range.

3. **`:gen/fmap` for derived values** (line 90):

   ```clojure
   (defn- gen-fmap [f gen] (or (-unreachable gen) (gen/fmap f gen)))
   ```

   Maps a function over generated values. Use for derived fields.

4. **`:gen/schema` for custom generator shape** (used by `:seon.flow/dynamic` and `:seon.db/ref`):
   Override the schema used for generation without changing validation.

5. **`-never-gen` for impossible schemas** (lines 58-66):
   When a schema cannot generate values, Malli provides a clean way to mark it unreachable. Parent schemas handle unreachable children gracefully:
   - `[:maybe M]` with unreachable M generates like `:nil`
   - `[:map [:a M]]` with unreachable M is itself unreachable
   - `[:map [:a {:optional true} M]]` with unreachable M generates like `[:map]`
   - `[:vector M]` with unreachable M generates like `[:= []]`

   Seon's "throw on generate" pattern is more aggressive. It could be replaced with `-never-gen` for cleaner behavior, but the throwing pattern does make the problem obvious immediately.

6. **Collection generation** (lines 97-111):

   ```clojure
   (defn- gen-vector [{:keys [min max]} g]
     (cond
       (-unreachable-gen? g) (if (zero? (or min 0)) (gen/return []) g)
       (and min (= min max)) (gen/vector g min)
       :else (gen/vector g (or min 0) (or max (+ (or min 0) 40)))))
   ```

   Default max collection size is `min + 40`. For faster tests, use `:gen/max` on collections:

   ```clojure
   [:vector {:gen/max 5} :keyword]  ;; generates vectors of 0-5 keywords
   ```

---

## 6. Recommendations

### Priority 1: Standardized `with-test-db` Fixture [HIGH IMPACT, LOW EFFORT]

**What to build**:

- `merged-test-schema` function in `test_utils.clj` that auto-derives Datalevin schema from all registered Malli entity schemas
- `with-test-db` fixture function that creates an isolated DB, maps all db-names, binds `*direct-mode*`
- `*test-conn*` dynamic var for raw connection access

**What to migrate**: All test files with manual DB fixture boilerplate (~10 files):

- `seon.runtime-test` (delete ~45 lines of setup/teardown)
- `seon.ctx-test` (delete ~37 lines)
- `seon.graph.query-test` (delete ~40 lines)
- `seon.health.workout-test` (delete ~30 lines)
- `seon.db-test` (simplify `with-named-db`)
- `seon.db.validation-test` (simplify `with-validation-conn`)
- `seon.db.datalevin.writer-test` (simplify manager creation)
- Other files with similar patterns

**Impact**: Eliminates ~300 lines of duplicated boilerplate. Makes it trivial to write new DB-backed tests.

**Estimated effort**: 1 agent task (~2-3 hours)

### Priority 2: Fix Generative Test Blocking [MEDIUM IMPACT, LOW EFFORT]

**What to fix**: `seon.dev.verify` should skip functions whose `:malli/schema` references types that have throwing generators (`:gen/fmap (fn [_] (throw ...))`) or are otherwise un-generable. This allows re-enabling `block-on-fail: true` in the hook.

**How**: Before running `function-checker`, inspect the schema for un-generable types. The types with throwing generators are:

- Schemas in `seon.orchestrator.session` (connection-manager, pool)
- Schemas in `seon.ctx` (render-fn, channel, db connection)
- Schemas in `seon.ns.lifecycle` (ctx atom, render fn)

Either detect the `:gen/fmap` throwing pattern or maintain an explicit skip-list of un-generable schema keys.

**Files affected**: `seon.dev.verify`, `seon-hook.edn`

**Estimated effort**: 1 small agent task (~1 hour)

### Priority 3: Adopt `defspec` for New Property Tests [MEDIUM IMPACT, LOW EFFORT]

**What to do**:

- Document `defspec` as the standard pattern for new property-based tests
- Convert one existing test as an example (e.g., one of the `doseq` loops in `schema-roundtrip-test`)
- Add to CONVENTIONS.md testing section

**Pattern**:

```clojure
(require '[clojure.test.check.clojure-test :refer [defspec]]
         '[clojure.test.check.properties :as prop]
         '[malli.generator :as mg])

(defspec string-roundtrip-property 50
  (prop/for-all [val (mg/generator :string)]
    (tu/with-temp-conn {:test/id {:db/valueType :db.type/long :db/unique :db.unique/identity}
                         :test/val {:db/valueType :db.type/string}}
      (fn [conn]
        (let [entity {:test/id 1 :test/val val}
              result (roundtrip! conn :test/id entity)]
          (= val (:test/val result)))))))
```

**Impact**: New property tests get automatic shrinking, better failure reporting, and seed-based reproducibility.

**Estimated effort**: Convention documentation + 1 example migration (~1 hour)

### Priority 4: Schema Registration Hygiene [MEDIUM IMPACT, LOW EFFORT]

**What to do**: Document the convention that test schemas must use test-namespace-qualified keywords. Most test files already do this correctly (e.g., `::id` in `validation-test.clj` expands to `:seon.db.validation-test/id`). The few that use bare keywords (e.g., `db-test.clj` registers `:name` and `:age` at lines 14-15) should be updated.

**Files to fix**:

- `seon.db-test` (lines 14-15): `(schema/register! :name :string)` and `(schema/register! :age :int)` -- these are un-namespaced and could collide

**Estimated effort**: Convention documentation + 1 small fix (~30 minutes)

### Priority 5: Kaocha Configuration Improvements [LOW IMPACT, LOW EFFORT]

**What to configure**:

- Add `:kaocha.plugin/randomize` to `tests.edn` to detect order-dependent failures
- Add `:kaocha.plugin/hooks` with `:kaocha.hooks/wrap-run` to globally bind `*direct-mode*` for CLI runs, preventing accidental production DB access

**Estimated effort**: 30 minutes configuration

### Priority 6: Test `*ctx*` Fixture [LOW IMPACT, LOW EFFORT]

**What to build**: A composable `with-test-ctx` fixture for tests that need reactive state. Can be composed with `with-test-db`:

```clojure
(use-fixtures :each tu/with-test-db tu/with-test-ctx)
```

**Estimated effort**: ~30 minutes, part of Priority 1

### Priority 7: Integration Test Profile [LOW IMPACT, HIGH EFFORT]

**What to build**: An Integrant test profile that starts a minimal system (Datalevin server + connection manager + flow infrastructure) for true integration tests that need to test the flow routing path.

**When needed**: Only when testing:

- `db/transact!` and `db/query` through the flow (non-`*direct-mode*`)
- The reply-router promise delivery path
- Connection reconnection after server restart

**Estimated effort**: 1 full agent task (~3-4 hours), complex due to Integrant lifecycle

### Priority 8: Pipeline Test as Kaocha Custom Type [LOW IMPACT, HIGH EFFORT]

**What to build**: A kaocha custom test type (`:seon/pipeline-test`) that automatically discovers all registered entity schemas and runs `assert-pipeline-roundtrip!` for each. This would make pipeline testing declarative -- adding a new entity schema would automatically get a pipeline test.

**Estimated effort**: 1 full agent task, requires understanding kaocha's `-load` and `-run` multimethods

---

## Appendix A: Files to Read Before Implementation

| File | Why |
|------|-----|
| `test/seon/test_utils.clj` | Core test utilities to extend |
| `test/seon/db/pipeline_test.clj` | Best generative test patterns |
| `src/seon/db.clj` | `*direct-mode*`, `*conn-manager*` |
| `src/seon/db/schema.clj` | `persisted-schemas`, `malli-map->datalevin-schema` |
| `src/seon/db/datalevin/conn.clj` | Connection manager structure |
| `src/seon/schema.clj` | Global registry, custom types |
| `src/seon/dev/verify.clj` | Generative test runner |
| `src/seon/dev/test.clj` | Structured test runner |

## Appendix B: Test Files to Migrate (Priority 1)

| File | Lines of Boilerplate | db-name Used |
|------|---------------------|-------------|
| `test/seon/runtime_test.clj` | ~45 | `:seon.runtime` |
| `test/seon/ctx_test.clj` | ~37 | `:test-ctx` |
| `test/seon/graph/query_test.clj` | ~40 | `:seon.runtime` |
| `test/seon/health/workout_test.clj` | ~30 | `:seon.runtime` |
| `test/seon/db_test.clj` | ~20 | `:seon` |
| `test/seon/db/validation_test.clj` | ~15 | `:test` |
| `test/seon/db/datalevin/writer_test.clj` | ~10 | `:test-db` |
| `test/seon/graph/ingest_test.clj` | ~20 (estimated) | `:seon.runtime` |
| `test/seon/ai_test.clj` | ~15 | `:seon.ai` |
| `test/seon/repl_test.clj` | ~15 (estimated) | varies |

**Total boilerplate to eliminate**: ~250-300 lines across 10 files.
