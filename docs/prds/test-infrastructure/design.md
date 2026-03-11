# PRD: Test Infrastructure Unification

**Date**: 2026-03-06
**Status**: Draft
**Depends on**: Schema unification (mostly complete)
**Research**: `docs/prds/test-infrastructure/research.md`

---

## Problem

Tests are unreliable, boilerplate-heavy, and dangerous:

1. **Tests crash production.** Tests without manual fixtures hit the live Datalevin server on port 8898, polluting data and causing LMDB crashes.
2. **300 lines of duplicated boilerplate.** 10+ test files each have 30-45 lines of identical temp-dir/conn/manager/binding setup. Every new DB-backed test copies this.
3. **Inconsistent db-name mapping.** Tests map different keywords (`:seon`, `:test`, `:test-ctx`, `:seon.runtime`) making them non-composable.
4. **Generative testing is toothless.** `block-on-fail: false` in the hook because 6 schemas throw on generation. Schema violations are silently tolerated.
5. **No shrinking.** All property tests use `doseq` + `mg/sample` — failures report full-size generated data instead of minimal reproducing cases.
6. **No concurrent safety.** Multiple agents running tests simultaneously can collide on shared state.

## Goals

1. **Complete data isolation.** Tests write to ephemeral temp directories, production writes to `data/datalevin/`. No shared files, no shared server, no shared state. This is the primary goal — everything else supports it.
2. **One line to get a full test environment.** `(use-fixtures :each tu/with-test-db)` gives you isolated DB, conn manager, direct mode. No boilerplate.
3. **All persisted schemas generate.** No throwing generators. `block-on-fail: true` everywhere.
4. **Automatic shrinking.** `defspec` as the standard for new property-based tests.
5. **Concurrent-safe.** Multiple agents can run the full test suite simultaneously — each gets its own temp directory.

## Non-Goals

- Integration test profile (Integrant test system) — future work
- Custom kaocha test types — future work
- Migrating away from clojure.test — we stay with clojure.test

---

## Design

### 1. Complete Data Isolation: Test vs Production

**The root problem:** Tests and production share the same Datalevin server, the same LMDB files, and the same namespaced keywords. A test writing `:seon.runtime/status :stopped` to the production DB is indistinguishable from a real state change. Tests without manual fixtures silently corrupt production data and cause LMDB crashes.

**The fix:** Tests NEVER touch the production Datalevin server. Complete separation at every level:

| | Production | Tests |
|---|-----------|-------|
| **Data directory** | `data/datalevin/` (server-managed) | `tmp/test-<nanotime>/` (per-test, ephemeral) |
| **Access method** | `d/get-conn` via TCP to port 8898 | `d/create-conn` direct local LMDB |
| **Connection manager** | Integrant component (real) | Fake map bound via `*conn-manager*` |
| **Flow routing** | Infrastructure flow (writer/reader) | Bypassed via `*direct-mode* true` |
| **Lifecycle** | Persists across restarts | Created before test, deleted after |
| **Concurrent safety** | Single writer flow serializes | Each test run gets unique temp dir |

**How it's enforced:**
- `with-test-db` binds `*direct-mode*` + `*conn-manager*` — all `db/transact!` and `db/query` calls route to the temp connection, never the server
- Kaocha `wrap-direct-mode` hook ensures `bin/test` CLI runs also bind `*direct-mode*` globally
- No code path from test code can reach port 8898

**Single merged connection per test:** Each test creates one temp Datalevin connection with all registered entity schemas merged. A fake `*conn-manager*` maps every db-name to this single connection. This works because all attributes use namespaced keywords — `:seon.runtime/status` and `:seon.fn/name` coexist in one LMDB without collision. The separation between "databases" in production is an organizational convention, not a data isolation requirement.

**Dynamic db-name handling:** In production, every namespace can have its own database (`:seon.trading`, `:seon.health`, etc.). The test conn-manager must handle arbitrary db-names, not a fixed set.

**Lazy connection creation:** The test fixture should NOT eagerly create a Datalevin connection. Many tests (pure functions, schema validation, flow topology) don't need a DB at all. The connection should be created on first access and shared for all subsequent db-names within that test.

Note on the 10MB `*init-db-size*`: this is the LMDB **mmap reservation**, not actual memory. A test writing 5 entities uses ~100KB of real space. The cap exists because the default is 1000MB per connection, and 68 of those exhausts direct buffer memory. With lazy creation, most test runs create far fewer connections.

```clojure
(defn with-test-db
  "Standard test fixture. Lazy DB creation, any db-name resolves to test connection.

   The Datalevin connection is created on first db/transact! or db/query call,
   not at fixture setup. Tests that don't touch the DB pay zero LMDB cost.

   Usage:
     (use-fixtures :each tu/with-test-db)   ;; fresh DB per test
     (use-fixtures :once tu/with-test-db)   ;; shared DB across tests"
  [f]
  (let [;; Lazy: connection created on first access, reused for all db-names
        test-dir (atom nil)
        test-conn (atom nil)
        get-or-create!
        (fn []
          (or @test-conn
              (let [dir (str "tmp/test-" (System/nanoTime))
                    conn (d/create-conn dir (merged-test-schema)
                                        {:kv-opts fast-kv-opts})]
                (reset! test-dir dir)
                (reset! test-conn conn)
                conn)))
        ;; Fake manager: any db-name keyword resolves to the lazy test connection
        fake-mgr {::conn/port 0
                  ::conn/connections
                  (reify clojure.lang.IDeref
                    (deref [_]
                      ;; Return a map-like thing where every key resolves to the test conn.
                      ;; get-or-create-connection! does (get @connections ns-key) as first check.
                      ;; We intercept that deref to return a defaulting lookup.
                      (reify clojure.lang.ILookup
                        (valAt [_ k] (let [c (get-or-create!)]
                                       {::conn/connection c}))
                        (valAt [_ k nf] (let [c (get-or-create!)]
                                          {::conn/connection c})))))}]
    (binding [*test-conn* (delay (get-or-create!))  ;; deref to force creation
              db/*direct-mode* true
              db/*conn-manager* fake-mgr]
      (try
        (f)
        (finally
          (when-let [c @test-conn]
            (when-not (d/closed? c) (d/close c)))
          (when-let [d @test-dir]
            (delete-dir! d)))))))
```

**Key properties:**
- **Lazy:** No LMDB created until a test actually reads/writes. Pure-function tests pay zero cost.
- **Any db-name:** The `ILookup` reify returns the test connection for any keyword. No fixed list.
- **One connection:** All db-names share a single LMDB — namespaced keys prevent collisions.
- **Cleanup:** Connection closed and temp dir deleted in `finally`, even if test throws.
- **`*test-conn*`:** Exposed as a `delay` — deref when you need raw access, nil-cost if unused.

The `reify ILookup` approach is simpler than an Atom proxy. It works because `get-or-create-connection!` in `conn.clj` does `(get @connections ns-key)` as its fast path — our reify intercepts the `@connections` deref and the `get` lookup, returning the test connection before the TCP fallback is ever reached.

The implementing agent should verify this works with `conn/get-conn!`'s actual code path and adjust if needed. The requirement is: **any keyword passed to `db/transact!` or `db/query` as a db-name must resolve to the lazy test connection, never TCP.**

```clojure
;; What tests write today (30-45 lines per file):
(def ^:private test-dir (atom nil))
(def ^:private test-conn (atom nil))
(defn- temp-dir [] ...)
(defn- setup-datalevin! [] ...)
(defn- teardown-datalevin! [] ...)
(defn- fake-conn-manager [conn] ...)
(use-fixtures :each (fn [f] ...30 lines of binding...))

;; What tests will write:
(use-fixtures :each tu/with-test-db)
```

#### `merged-test-schema` (memoized)

Derives a single Datalevin schema map from all Malli entity schemas:

```clojure
(defn merged-test-schema
  "Derive and cache a merged Datalevin schema from all registered entity schemas.
   Recomputes only when the persisted schema count changes."
  []
  (let [entities (db-schema/persisted-schemas)]
    (reduce-kv
      (fn [acc _name malli-schema]
        (merge acc (db-schema/malli-map->datalevin-schema malli-schema)))
      {}
      entities)))
```

Cache with `defonce` + atom. Invalidate when schema count changes (rare — only on code reload).

#### `with-test-db` fixture

```clojure
(def ^:dynamic *test-conn*
  "Raw Datalevin connection inside with-test-db. For d/transact!, d/pull, d/q."
  nil)

;; See "Lazy connection creation" section above for full with-test-db implementation.
;; Summary: lazy DB on first access, any db-name keyword, cleanup in finally.
```

**Key properties:**
- ~~Uses existing `with-temp-conn`~~ *(Verification note: the lazy design above does NOT use `with-temp-conn`. It implements its own lazy creation. This is correct -- lazy is better than eager. Ignore this bullet.)*
- Maps all known db-names to same connection (no collisions due to namespaced keys)
- Dynamic db-names (like `:seon.trading`) work via the conn manager's `get-or-create-connection!` — but in tests, code should use the standard names
- Each invocation gets a unique temp directory — concurrent-safe by construction
- `*test-conn*` exposed for tests that need raw `d/transact!` or `d/q`

#### `with-test-ctx` companion fixture

For tests needing a `*ctx*` atom (reactive namespace state):

```clojure
(def ^:dynamic *test-ctx* nil)

(defn with-test-ctx
  "Composable fixture for tests needing a ctx atom.
   Usage: (use-fixtures :each tu/with-test-db tu/with-test-ctx)"
  [f]
  (let [ctx-id (str "test-" (System/nanoTime))
        a (ctx/create! {::ctx/instance-id ctx-id
                        ::ctx/persist? false
                        ::ctx/sse-push? false})]
    (binding [*test-ctx* a]
      (try (f)
           (finally (ctx/destroy! {::ctx/instance-id ctx-id}))))))
```

### 2. Generator-Friendly Schema Policy

**Problem:** 6 schemas use `(throw (ex-info ...))` in their generators for runtime objects (atoms, channels, connections). This breaks generative testing globally.

**Solution:** Replace throwing generators with metadata-based exclusion.

#### New metadata: `:seon/runtime-type`

For schemas that represent runtime objects (atoms, channels, connections, functions):

```clojure
;; Before (throws, breaks generative testing):
(schema/register! ::connection
  [:any {:description "Datalevin connection"
         :gen/fmap (fn [_] (throw (ex-info "Cannot generate" {})))}])

;; After (generates a stub, marked as non-persistent):
(schema/register! ::connection
  [:any {:seon/runtime-type true
         :description "Datalevin connection (atom wrapping DB)"
         :gen/fmap (fn [_] (atom {}))}])
```

**Rules:**
- `:seon/runtime-type true` marks a schema as a runtime object
- Runtime types MUST provide a `:gen/fmap` that returns a **stub** (not throw)
- Stubs: `(atom {})` for atoms, `(a/chan 1)` for channels, `(fn [& _])` for fns
- `seon.dev.verify` skips functions whose schemas reference runtime types
- Persisted entity schemas MUST NOT contain runtime types (enforced by `validate-persisted-schemas!`)

#### Schema authoring guidelines for generator quality

Add to CONVENTIONS.md:

| Pattern | Good | Bad |
|---------|------|-----|
| Bounded strings | `[:string {:min 1 :max 200}]` | `:string` (unbounded) |
| Narrow generation | `[:int {:min 0 :gen/max 100}]` | `:int` (generates huge values) |
| Fixed domains | `[:string {:gen/elements ["seon.foo" "seon.bar"]}]` | `:string` for namespace names |
| Small collections | `[:vector {:gen/max 5} :keyword]` | `[:vector :keyword]` (up to 40 items) |
| Enums over keywords | `[:enum :running :stopped :crashed]` | `:keyword` for status fields |
| Runtime objects | `[:any {:seon/runtime-type true :gen/fmap (fn [_] (atom {}))}]` | Throwing `:gen/fmap` |

### 3. `defspec` for Property-Based Tests

**Problem:** All property tests use `doseq` + `mg/sample` which lacks shrinking. When a test fails, you get a large random input with no help finding the minimal failure.

**Solution:** Adopt `defspec` from `clojure.test.check.clojure-test` as the standard for new property tests.

```clojure
(require '[clojure.test.check.clojure-test :refer [defspec]]
         '[clojure.test.check.properties :as prop]
         '[malli.generator :as mg])

;; Before:
(deftest roundtrip-test
  (tu/with-temp-conn schema
    (fn [conn]
      (doseq [entity (mg/sample ::my-entity {:size 20})]
        (is (= entity (roundtrip! conn entity)))))))

;; After:
(defspec roundtrip-property 50
  (prop/for-all [entity (mg/generator ::my-entity)]
    (tu/with-temp-conn schema
      (fn [conn]
        (= (normalize entity) (normalize (roundtrip! conn entity)))))))
```

**Benefits:**
- Automatic shrinking to minimal failing case
- Seed-based reproducibility (`:seed` in failure output)
- Integrates with `clojure.test/run-tests` and kaocha
- `mg/generator` bridges Malli schemas to test.check generators

**Convention:** New property-based tests use `defspec`. Existing `doseq` loops may be migrated opportunistically but are not required to change.

### 4. Kaocha Configuration

Update `tests.edn`:

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
           :kaocha.plugin/capture-output
           :kaocha.plugin/randomize
           :kaocha.plugin/hooks]
 :kaocha.plugin.randomize/randomize? true
 :kaocha.hooks/wrap-run
 [seon.test-utils/wrap-direct-mode]}
```

New additions:
- **`randomize`** — shuffles test order to detect hidden dependencies
- **`hooks/wrap-run`** — binds `*direct-mode*` globally for CLI runs, preventing accidental production DB access from `bin/test`

```clojure
;; In test_utils.clj
(defn wrap-direct-mode
  "Kaocha hook: wrap entire test run in *direct-mode* binding."
  [run]
  (fn [testable test-plan]
    (binding [db/*direct-mode* true]
      (run testable test-plan))))
```

### 5. Generative Test Blocking

Re-enable `block-on-fail: true` in `.claude/seon-hook.edn` after:
1. All throwing generators replaced with stubs
2. `seon.dev.verify` filters functions with `:seon/runtime-type` schemas

Update hook config:
```clojure
:generative {:enabled true
             :num-tests 10
             :block-on-fail true}  ;; was false
```

---

## Implementation Phases

### Phase 0: Dependency Upgrades [done]

**Files:** `deps.edn` (3 occurrences of Malli, 1 of test.check)

Upgraded:
- **Malli** 0.17.0 → 0.20.0 (all 3 aliases: main, test, agent)
- **test.check** 1.1.1 → 1.1.3
- **Kaocha** already at latest (1.91.1392)

Relevant new capabilities:
- `:gen/min`/`:gen/max` now work on `:+` and `:*` sequence schemas (Malli 0.19.0)
- Primitive type hint instrumentation emits warning instead of throwing (Malli 0.18.0)
- Reduced allocation churn in `shrink-int` (test.check 1.1.2)
- No breaking changes affect us (we don't use `m/parse` or `json-transformer`)

Requires JVM restart to pick up new jar versions.

### Phase 1: Unified Fixture [high impact, low effort]

**Files:** `test/seon/test_utils.clj`

1. Add `merged-test-schema` (memoized, derives from `persisted-schemas`)
2. Add `*test-conn*` dynamic var
3. Add `with-test-db` fixture
4. Add `with-test-ctx` companion fixture
5. Add `wrap-direct-mode` kaocha hook

**Verification:** Write a test that uses `with-test-db` and calls `db/transact!` with multiple db-names.

### Phase 2: Migrate Existing Tests [high impact, medium effort]

**Files:** ~10 test files (see Appendix A)

For each file:
1. Replace manual fixture boilerplate with `(use-fixtures :each tu/with-test-db)`
2. Replace `@test-conn` / `*conn*` with `tu/*test-conn*`
3. Delete all setup/teardown functions, temp-dir atoms, fake-manager builders
4. Verify all tests still pass

**Verification:** `(user/run-tests)` — all green.

### Phase 3: Generator-Friendly Schemas [medium impact, low effort]

**Files:** `src/seon/schema.clj`, 6 files with throwing generators

1. Add `:seon/runtime-type` support to schema registry
2. Replace all throwing `:gen/fmap` with stub generators
3. Add `generatable?` predicate to schema.clj
4. Update `seon.dev.verify` to filter non-generatable functions
5. Re-enable `block-on-fail: true` in hook

**Verification:** `(user/test-gen)` passes for all generatable functions.

### Phase 4: defspec Adoption [medium impact, low effort]

**Files:** CONVENTIONS.md, 1-2 test files as examples

1. Add test.check to project dependencies (if not already present)
2. Document `defspec` pattern in CONVENTIONS.md
3. Convert one existing `doseq` loop (e.g., in `schema-roundtrip-test`) as exemplar
4. Write one new `defspec` test demonstrating shrinking

**Verification:** Run the `defspec` test, force a failure, observe shrinking output.

### Phase 5: Kaocha Hardening [low impact, low effort]

**Files:** `tests.edn`

1. Add `randomize` and `hooks` plugins
2. Add `wrap-direct-mode` hook
3. Run full suite via `bin/test` — verify no order-dependent failures

**Verification:** `bin/test` passes with randomized order.

### Phase 6: Schema Authoring Guidelines [low impact, low effort]

**Files:** CONVENTIONS.md, CLAUDE.md

1. Add generator quality guidelines (bounded strings, `:gen/max`, `:gen/elements`)
2. Add `:seon/runtime-type` documentation
3. Add `defspec` examples

---

## Appendix A: Test Files to Migrate (Phase 2)

| File | Boilerplate Lines | db-name Used |
|------|-------------------|-------------|
| `test/seon/runtime_test.clj` | ~45 | `:seon.runtime` |
| `test/seon/ctx_test.clj` | ~37 | `:test-ctx` |
| `test/seon/graph/query_test.clj` | ~40 | `:seon.runtime` |
| `test/seon/health/workout_test.clj` | ~30 | `:seon.runtime` |
| `test/seon/db_test.clj` | ~20 | `:seon` |
| `test/seon/db/validation_test.clj` | ~15 | `:test` |
| `test/seon/db/datalevin/writer_test.clj` | ~10 | `:test-db` |
| `test/seon/graph/ingest_test.clj` | ~20 | `:seon.runtime` |
| `test/seon/ai_test.clj` | ~15 | `:seon.ai` |
| `test/seon/repl_test.clj` | ~15 | varies |

**Total boilerplate eliminated:** ~250-300 lines.

## Appendix B: Schemas with Throwing Generators (Phase 3)

| File | Schema | Line | Stub |
|------|--------|------|------|
| `seon.orchestrator.session` | `::datalevin-manager` | `session.clj:94` | `(atom {})` |
| `seon.orchestrator.session` | `::pool` | `session.clj:99` | `{}` |
| `seon.ctx` | `::render-fn` | `ctx.clj:93` | `(fn [& _])` |
| `seon.ctx` | `::channel` | `ctx.clj:98` | `(a/chan 1)` |
| `seon.ctx` | `:seon.agent/db` | `ctx.clj:158` | `(atom {})` |
| `seon.ns.lifecycle` | `::ctx-atom` | `lifecycle.clj:48` | `(atom {})` |
| `seon.ns.lifecycle` | `::render-fn` | `lifecycle.clj:53` | `(fn [& _])` |

> **Verification note (2026-03-06):** Original table had two name errors.
> `::connection-manager` was corrected to `::datalevin-manager` (see `session.clj:94`).
> `::db-connection` was corrected to `:seon.agent/db` (see `ctx.clj:158` -- note this
> is an explicit fully-qualified keyword, not auto-namespaced with `::`).
> Line numbers added for implementer reference.

## Appendix C: Reference Sources

| Source | What We Learned |
|--------|----------------|
| `reference-code/test.check/` | `defspec` + shrinking algorithm, `prop/for-all` with Malli generators |
| `reference-code/malli/src/malli/generator.cljc` | `:gen/min`, `:gen/max`, `:gen/elements`, `-never-gen` |
| `reference-code/kaocha/src/kaocha/plugin/` | `randomize`, `hooks/wrap-run` |
| `reference-code/expectations/` | Do NOT adopt (incompatible with clojure.test) |
| `reference-code/datalevin/src/datalevin/storage.clj` | `d/create-conn` with nil dir creates auto-temp |
| Gemini review | Virtual Cluster approach, metadata-based exclusion, map over protocol |

---

## Verified Claims (2026-03-06)

Verification agent (Claude Opus 4.6) read all referenced source code and tested
key assumptions in the running REPL. Results below.

### CRITICAL: `reify IDeref`/`ILookup` for `::connections` WILL CRASH

**Status: INCORRECT -- must be fixed before implementation.**

The PRD (Section 1, lines 91-102) proposes a `reify IDeref` wrapping a
`reify ILookup` for `::conn/connections`. This will crash with a
`ClassCastException` because `get-or-create-connection!` in `conn.clj:230,235,241,255,261,266`
calls `(swap! connections ...)` on the `::connections` value. `swap!` requires
`clojure.lang.IAtom`, which a bare `reify` does not implement.

**REPL proof:**
```clojure
(let [fake (reify clojure.lang.IDeref
             (deref [_] (reify clojure.lang.ILookup
                          (valAt [_ k] :value)
                          (valAt [_ k nf] :value))))]
  (swap! fake assoc :foo :bar))
;; => ClassCastException: cannot be cast to class clojure.lang.IAtom
```

**Fix:** Use a real `atom` wrapping a "defaulting map" that returns the test
connection entry for any key. The defaulting map implements `IPersistentMap`
so `(assoc m k v)` is a no-op (returns `this`), and `ILookup` so `(get m k)`
always returns the test connection entry. This way `@connections` returns the
defaulting map (fast path hits), and `swap!` succeeds as a no-op if the slow
path is ever reached.

```clojure
;; Verified working approach:
(let [default-entry {::conn/connection conn}
      defaulting-map (reify
                       clojure.lang.ILookup
                       (valAt [_ k] default-entry)
                       (valAt [_ k nf] default-entry)
                       clojure.lang.IPersistentMap
                       (assoc [this k v] this)
                       (assocEx [this k v] this)
                       (without [this k] this)
                       clojure.lang.Associative
                       (containsKey [_ k] true)
                       (entryAt [_ k] (clojure.lang.MapEntry. k default-entry))
                       clojure.lang.IPersistentCollection
                       (count [_] 1)
                       (cons [this o] this)
                       (empty [_] {})
                       (equiv [_ o] false)
                       clojure.lang.Seqable
                       (seq [_] nil)
                       Iterable
                       (iterator [_] (.iterator []))
                       clojure.lang.IFn
                       (invoke [_ k] default-entry)
                       (invoke [_ k nf] default-entry))]
  (atom defaulting-map))
```

This is more code than the original reify but it is proven to work with
`get-or-create-connection!`'s actual code paths (fast path `get @connections`,
slow path `swap! connections assoc`). The implementing agent should extract
this into a `defn- make-defaulting-conn-atom [conn]` helper.

**Why the fast path is sufficient:** With the defaulting map, `(get @connections ns-key)`
always returns an entry, so `get-or-create-connection!` takes the fast path (line 221-248)
and never reaches the `swap!` on the slow path. The `swap!` no-op is a safety net only.

### `transact!` calls `resolve-conn` BEFORE checking `*direct-mode*`

**Status: IMPORTANT -- implementers must understand this.**

See `db.clj:295-320`. `transact!` unconditionally calls `resolve-conn` (line 316)
to get a connection for `ensure-schema!` (line 319), THEN calls `write!` (line 320)
which checks `*direct-mode*`. This means the fake conn-manager MUST return a real
Datalevin connection (not a stub) because `ensure-schema!` calls `d/schema` and
potentially `d/update-schema` on it.

The PRD's lazy `d/create-conn` approach handles this correctly -- the test connection
IS a real Datalevin connection. Just noting that a purely-stub conn-manager would not
work here.

### Merged schema feasibility

**Status: VERIFIED -- works correctly.**

REPL test with all 17 persisted schemas:
- `persisted-schemas` returns 17 schemas
- `malli-map->datalevin-schema` merges to 110 attributes with no conflicts
- `d/create-conn` with the merged schema succeeds
- `d/transact!` and `d/q` work correctly on the merged connection
- No attribute collisions between namespaces (namespaced keywords prevent this)

### `with-temp-conn` claim

**Status: INCONSISTENT -- PRD text contradicts its own code.**

Line 171 says "Uses existing `with-temp-conn` (proven, handles cleanup)" but the
actual `with-test-db` code (lines 67-112) implements its own lazy creation and does
NOT call `with-temp-conn`. This is correct behavior (lazy is better than eager), but
the text claim should be removed or corrected. The implementing agent should use the
lazy approach from lines 67-112, not `with-temp-conn`.

### `wrap-run` kaocha hook signature

**Status: VERIFIED -- correct.**

Verified against `reference-code/kaocha/src/kaocha/plugin/hooks.clj:90-91`:
```clojure
(wrap-run [run test-plan]
  (reduce #(%2 %1) run (:kaocha.hooks/wrap-run test-plan)))
```

Each hook function receives `run` (1 argument) and must return a new `run` function.
The returned function takes `(testable test-plan)`. The PRD's proposed signature is
exactly correct:
```clojure
(defn wrap-direct-mode [run]
  (fn [testable test-plan]
    (binding [db/*direct-mode* true]
      (run testable test-plan))))
```

### `test.check` dependency

**Status: VERIFIED -- already available.**

`org.clojure/test.check 1.1.1` is in `deps.edn` under the `:test` alias (line 90).
It is also available in the dev REPL (transitively through Malli).
`clojure.test.check.clojure-test` loads successfully in the running REPL.
No dependency changes needed for `defspec`.

### `seon.dev.verify` already handles throwing generators

**Status: VERIFIED -- but `block-on-fail` still needs fixing.**

`verify.clj:258-274` already catches `:malli.generator/no-generator` exceptions
and returns `nil` (skip, not failure). All 7 throwing generators use this exact
exception type. So `run-gen-tests` already gracefully skips un-generable functions.

The remaining issue is whether the hook (`.claude/seon-hook.edn:29`) should have
`block-on-fail: true`. Given the skip logic works, it should be safe to re-enable.
The implementing agent should:
1. Verify in the REPL that `(run-gen-tests ...)` for a namespace with throwing
   generators returns `{::success true}` (skips, no failures)
2. If confirmed, flip `block-on-fail` to `true`
3. If there are OTHER generator failures beyond the 7 throwing ones, fix those first

### Hook config comment is stale

**Status: NOTED -- minor cleanup.**

`.claude/seon-hook.edn:22-24` references `::xtdb-node` which no longer exists
(XTDB was replaced by Datalevin). The comment should be updated to reference the
actual 7 throwing generators identified in Appendix B.

### `ctx/create!` and `ctx/destroy!` signatures for `with-test-ctx`

**Status: VERIFIED -- correct.**

- `ctx/create!` takes a map with `::ctx/instance-id` (required), `::ctx/persist?`,
  `::ctx/sse-push?` (see `ctx.clj:457-484`). With `::persist? false` and
  `::sse-push? false`, no DB access occurs. The PRD's fixture is correct.
- `ctx/destroy!` takes `{::ctx/instance-id id}` (see `ctx.clj:613-622`). Correct.

### `connection-closed?` check in fast path

**Status: VERIFIED -- safe.**

`get-or-create-connection!` (line 222) calls `connection-closed?` on the
`::connection` from the entry. Since the test connection is a real
`d/create-conn` result, `datalevin.conn/closed?` works correctly on it.
No issue here.

---

## Warnings for Implementing Agent

1. **Test the defaulting-map approach end-to-end.** The reify needs all the
   interfaces listed above (`ILookup`, `IPersistentMap`, `Associative`,
   `IPersistentCollection`, `Seqable`, `Iterable`, `IFn`). Missing any will
   cause `AbstractMethodError` or `ClassCastException` in unexpected places.
   Test with `(db/transact! :any-random-keyword [...])` inside `with-test-db`.

2. **`ensure-schema!` may call `d/update-schema`.** The first `transact!` call
   in a test may trigger `ensure-schema!` which compares Malli-derived schema
   against the connection's live schema. Since the merged schema includes all
   attributes, this should be a no-op. But if a test registers NEW schemas at
   load time (like `validation-test.clj` does), those attributes won't be in
   the merged schema, and `ensure-schema!` will call `d/update-schema`. This
   is fine -- just noting it as a potential source of "why did my test touch
   the DB during setup."

3. **`graph/query-test.clj` uses `:once` fixture.** It populates graph data
   once and queries across multiple tests. The unified `with-test-db` works
   for `:once` but the implementing agent should verify that data persists
   across test functions within the namespace (same connection, not reset).

4. **Two test schemas in `persisted-schemas` are test-only.** The registry
   contains `"test.overwrite"` and `"test.idempotent"` which come from test
   files. These are harmless in the merged schema but the implementing agent
   should be aware they exist.

5. **`ctx-test.clj` uses `:test-ctx` as db-name.** With the new unified fixture,
   all db-names map to the same connection, so `:test-ctx` will work. But the
   test may also directly reference `@test-conn` -- the implementing agent
   should replace those with `@tu/*test-conn*` (force the delay) or
   `(deref tu/*test-conn*)`.

6. **Lazy `*test-conn*` is a `delay`, not an atom.** The PRD binds it as
   `(delay (get-or-create!))`. Tests that need the raw connection should use
   `@tu/*test-conn*` to force it. Tests that don't need it pay zero cost.
   But be careful: `@tu/*test-conn*` triggers DB creation on first deref,
   so it must happen inside the `with-test-db` binding scope.
