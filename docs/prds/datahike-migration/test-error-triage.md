---
type: prd
status: active
tags: [prd, database]
---

# Test-suite error triage — datahike migration cleanup

**Captured:** 2026-05-14 09:15–09:17 UTC, live orchestrator REPL.
**Scope:** triage of the 14 errors in the `(user/run-tests)` baseline. No code changes. Each cluster verified in-REPL.

The headline finding: **none of the 14 errors is actually a datahike-migration regression in the strict sense.** The pipeline tests (cluster 2) and the workout integration test (cluster 3) both run **against `datalevin.core/transact!` via `seon.test-utils/with-temp-conn`** — i.e. they exercise the legacy datalevin path directly, not the live datahike flow. The root cause for both is a March-2026 commit (`b534279` "feat: shape graph phase 1+2") that added new `:db.type/ref` keys to `seon.graph.ingest/fn-entity-schema` (`:seon.fn/input-shape`, `:seon.fn/output-shape`) **without** updating the tests that consume the schema. The migration is innocent here. Cluster 1 is a Malli-instrumentation schema-tightening regression independent of the DB engine.

The encouraging implication: the datahike flow itself is not breaking anything visible in this baseline. The 14 errors describe pre-existing test debt that happens to surface now.

## Classification summary

| Cluster | Tests | Root cause (1 sentence) | Class | Recommended scope |
|---|---|---|---|---|
| 1. `seon.orchestrator.session-test` (12 errors) | all 12 tests that pass `::namespace` to `start-agent-session!` | The function's Malli schema requires `:string`, but the tests still pass Clojure symbols (e.g. `'test.start`); instrumentation rejects the call before the body's `->ns-string` coercion runs. | `:schema-drift` | one-line fix (widen schema) or one-file fix (pre-coerce in tests). |
| 2. `seon.db.pipeline-test/ingest-fn-entity-pipeline-test` (1 error) | only that var | The test's "schema without refs" filter excludes `:seon.fn/input-spec` / `:seon.fn/output-spec` but not `:seon.fn/input-shape` / `:seon.fn/output-shape`; Malli's generator emits random `[:keyword "string"]` vectors for those ref fields, which datalevin interprets as lookup-refs and rejects because the random keyword isn't `:db/unique`. | `:schema-drift` | one-line fix (extend the filter to four keys). |
| 3. `seon.health.workout-test/find-renderer-integration-test` (1 error) | only that var | The fixture transacts `(::extract/specs graph)` and `(::extract/functions graph)` but not `(::extract/shapes graph)` / `(::extract/entries graph)`; functions now carry `:seon.fn/input-shape` / `:seon.fn/output-shape` lookup-refs (since `b534279`) whose targets never exist in the test DB. | `:fixture-or-setup` | one-file fix (transact shapes + entries in the fixture, in the right order). |

All 14 errors are independent of one another. Clusters 2 and 3 share a common ancestor (the same `b534279` commit added the `:input-shape` / `:output-shape` schema entries) but the fixes don't have to land together. Cluster 1 is unrelated to clusters 2 and 3.

## Cluster 1 — `seon.orchestrator.session-test` (12 errors)

### Root cause (verified)

The Malli schema for `:seon.orchestrator.session/namespace` is `[:string {:min 1 :description "Agent namespace symbol, stored as string"}]` (`session.clj:41-42`). The function `start-agent-session!` is instrumented with `[:=> [:cat ::start-agent-session-request] ::start-agent-session-response]` (`session.clj:299`). The function body **does** coerce symbol → string via the private `->ns-string` helper (`session.clj:271-277`), but that runs after the instrumentation guard.

All 12 failing test sites pass symbols:

```clojure
(session/start-agent-session!
  {::session/node *test-node*
   ::session/namespace 'test.start          ;; symbol
   ::session/pool nil})

```

Verified in-REPL (`(seon.schema/schema-definition :seon.orchestrator.session/namespace)` returns `[:string {:min 1, ...}]`; a fresh `(user/run-tests 'seon.orchestrator.session-test)` produced 12 errors with the exact `expected [:string {:min 1, ...}], got test.nrepl.sid (symbol)` shape).

The docstring at `session.clj:293` still says **"::namespace - Required. Clojure namespace (symbol or string)"** — the *docs* think both shapes are valid; the *schema* doesn't. Classic schema/caller disagreement.

The `recover-sessions-test` is the one var in `session-test` that doesn't call `start-agent-session!` directly (it uses `runtime/register!` + `session/recover-sessions!`) and accordingly is not in the 12 — that matches the baseline.

### Class

`:schema-drift` — schema tightened, callers/docs not updated.

### Recommended fix path (one-line, or one-file)

Three honest options, in order of how I'd weigh them:

1. **Widen the schema** to accept symbol-or-string: change `:seon.orchestrator.session/namespace` to `[:or [:string {:min 1}] :symbol]`, and let the existing `->ns-string` keep doing its job. **Files touched:** `src/seon/orchestrator/session.clj` (one line at L41-42). **Trade-off:** keeps the symbol convenience for callers, matches the docstring at L293.
2. **Pre-coerce in callers** — change every test call site (and any production caller) to pass `"test.start"` instead of `'test.start`. **Files touched:** `test/seon/orchestrator/session_test.clj` (12 sites). **Trade-off:** preserves the strict schema; cosmetically uglier for namespace-shaped data; doesn't match the docstring (would also need a docstring fix).
3. **Hoist the coercion above instrumentation** — register a `:malli/decode/normalize` transformer or call `->ns-string` in a wrapping public fn. **Trade-off:** more machinery; only worth it if other entrypoints have the same shape problem.

I'd recommend option 1: the docstring already promised symbol-or-string, the helper is already written, instrumentation should bless what callers actually do. **Estimated scope: one-line fix.** Can land independently.

### Smell

- `seon.orchestrator.session/datalevin-manager` is registered with a `:gen/fmap` hack to make property tests not generate it — and the field is dead on the current boot. Already flagged in `remaining.md` smell #7 / cluster 2. Cleaning that up at the same time as fixing the namespace schema would tighten the `start-agent-session-request` shape further (one fewer optional dead field).

## Cluster 2 — `seon.db.pipeline-test/ingest-fn-entity-pipeline-test` (1 error)

### Root cause (verified)

The test (`pipeline_test.clj:730-743`) builds `schema-without-refs` by removing `#{:seon.fn/input-spec :seon.fn/output-spec}` from `fn-entity-schema`, then runs the generative roundtrip. But `fn-entity-schema` (`graph/ingest.clj:138-141`) now declares **four** `:seon.db/ref` keys:

```clojure
[:seon.fn/input-spec {:optional true} :seon.db/ref]
[:seon.fn/output-spec {:optional true} :seon.db/ref]
[:seon.fn/input-shape {:optional true} :seon.db/ref]
[:seon.fn/output-shape {:optional true} :seon.db/ref]

```

The last two were added in `b534279` (Mar 2026 "feat: shape graph phase 1+2") — same commit that added the shape graph at all. The test filter wasn't updated.

`malli.generator/generate` produces things like `{:seon.fn/input-shape [:nu ""] :seon.fn/output-shape 40739 ...}` for the ref slots (verified in-REPL — generator output captured). When `d/transact!` sees `[:keyword "string"]` it tries lookup-ref resolution, hits `datalevin.db/entid` at `db.clj:769`, and throws `:lookup-ref/unique` because the random keyword isn't a `:db/unique` attribute.

Pipeline test runs through `datalevin.core/transact!` via `tu/with-temp-conn` — the failure is on the datalevin side. **This is not a datahike regression.** It's pre-existing test debt that was probably masked by generator seed luck.

### Class

`:schema-drift` — the schema gained new ref keys; the test's "skip ref keys" list didn't.

### Recommended fix path

Extend the filter in `pipeline_test.clj:735-736` from `#{:seon.fn/input-spec :seon.fn/output-spec}` to `#{:seon.fn/input-spec :seon.fn/output-spec :seon.fn/input-shape :seon.fn/output-shape}`. The `ingest-fn-spec-ref-pipeline-test` already covers spec-ref roundtripping manually; a parallel `ingest-fn-shape-ref-pipeline-test` could be added at the same time to cover shape-refs against actually-persisted shape entities.

**Files touched:** `test/seon/db/pipeline_test.clj`. **Estimated scope: one-line fix** (plus optional new test case for symmetry with the existing ref test).

Independent — can land without cluster 1 or 3.

### Smell

- The generative-test pattern silently filters ref keys to avoid this class of failure. That's brittle: every time someone adds a ref-typed field to a `*-entity-schema`, this test breaks. A more robust fix would teach `malli.generator` (or the test utility) to skip `:seon.db/ref` keys by their **type** rather than relying on a hand-maintained allow/deny list. Not blocking; worth raising as future work.

## Cluster 3 — `seon.health.workout-test/find-renderer-integration-test` (1 error)

### Root cause (verified)

`extract-graph-from-file` returns 9 keys (`graph/extract.clj:641-649`):

```
::ns-name ::namespaces ::functions ::specs ::vars
::call-edges ::ns-deps ::shapes ::entries

```

The fixture (`workout_test.clj:191-197`) transacts only `::specs` and `::functions`:

```clojure
(d/transact! *conn* (vec (::extract/specs graph)))
(d/transact! *conn* (vec (::extract/functions graph)))

```

Each function entity now carries `:seon.fn/input-shape [:seon.shape/id "shape:..."]` and `:seon.fn/output-shape [:seon.shape/id "shape:..."]` lookup-refs (added by `b534279`). The shape entities are present in `(::extract/shapes graph)` but never transacted. Datalevin can't resolve the lookup-ref → "Nothing found for entity id [:seon.shape/id ...]" at `datalevin.db/entid_strict` (`db.clj:791`).

Verified in-REPL: `seon.graph.ingest/datalevin-schema` correctly marks `:seon.shape/id` as `:db.unique/identity`, so the schema is fine. Only the fixture's transact-set is incomplete.

### Class

`:fixture-or-setup` — test setup misses two of the four graph collections.

### Recommended fix path

Transact `(::extract/entries graph)` and `(::extract/shapes graph)` before the functions:

```clojure
(d/transact! *conn* (vec (::extract/specs graph)))
(d/transact! *conn* (vec (::extract/entries graph)))   ;; shapes ref entries
(d/transact! *conn* (vec (::extract/shapes graph)))    ;; functions ref shapes
(d/transact! *conn* (vec (::extract/functions graph)))

```

Order matters because shapes contain entry-refs and functions contain shape-refs. Worth confirming entry/shape internal ordering by re-running the test after the change.

**Files touched:** `test/seon/health/workout_test.clj`. **Estimated scope: one-file fix** (4 added lines). Independent — can land without cluster 1 or 2.

### Smell

- `extract-graph` returns 9 keys; the fixture knows about 2. Any test that wants a fully-populated test DB needs to do this dance correctly. A `tu/transact-full-graph!` helper that takes a graph map and transacts in the right dependency order would prevent the next agent from hitting this same wall. Not blocking — note it as a follow-up if/when the migration cluster picks up.

## Incidental smells (not in the 14 errors)

While digging through the surrounding code I noticed a few things consistent with `remaining.md`'s existing smell list, plus a couple new ones. None are blocking the triage; flagging per the seon CLAUDE.md "Report Code Smells" rule.

1. **`seon.runtime/persist!` quietly fails inside `recover-sessions-test`.** That test passes, but its run emits `WARN Failed to persist runtime instance {:namespace "test.orphan", :error "Connection manager not available -- is the system running?"}`. The test only asserts the in-memory `runtime/instance` lookup, so the silent persist failure doesn't trip a `is` assertion. Same root cause as `remaining.md` smells #1 / #3 / #6 — `:seon.db.datalevin/connections` is not in `system.edn`, so any path that still expects it silently no-ops. The test is technically green but the runtime registry's `:persist!` half is broken on the current boot. Worth noting because once cluster 1 is fixed and somebody re-runs the suite they'll see those WARN lines and wonder.
2. **The test file `seon.orchestrator.session-test` passes `::session/node *test-node*` as a request key**, but `start-agent-session-request` doesn't declare `::node` — and `*test-node*` is bound to `nil` (`test_utils.clj:19-21`). Malli's `:map` is open by default so the extra key doesn't fail validation, but the test is shipping `{::session/node nil}` to a function that ignores it. This is a vestigial fixture API — `with-test-node` is documented as a "legacy fixture stub" in `test_utils.clj:23-26`. If anyone touches cluster 1, dropping `::session/node` from the test calls at the same time would tighten the contract.
3. **`pipeline_test.clj` and `workout_test.clj` both directly `(:require [datalevin.core :as d])`** and run against `with-temp-conn` (which creates a real datalevin store). These tests are *useful* as Malli↔datalevin bridge tests, but they're not exercising the datahike code path at all. If/when the migration deletes datalevin proper (cluster 4 in `remaining.md`), these test files break wholesale. The migration plan probably needs an explicit decision: either (a) port `with-temp-conn` + these tests to datahike `:memory`, (b) delete them once the datahike-side bridge gets equivalent generative tests, or (c) keep them as long as `reference-code/datalevin` is around. `prd.md` doesn't explicitly mention them.
4. **`test_utils.clj:38` does `(alter-var-root #'dc/*init-db-size* (constantly 10))` at top-level** — i.e. side effect on require. Combined with the inability of the existing `with-small-db-size` fixture to fully isolate this, the var stays at 10 MiB globally as long as `seon.test-utils` is loaded. Probably intentional, but it means the side effect is permanent for the session, not test-scoped. Note for whoever ports to datahike.

## Open questions for the orchestrator

1. **Cluster 1 — widen schema vs. coerce in tests vs. hoist coercion?** I recommend widening the schema (option 1 above), but the SoT decision is yours. Picking influences whether the fix is in `session.clj` or in `session_test.clj`.
2. **Cluster 1 cleanup pairing.** While in `session.clj`, do you want the agent to also pull out the dead `::datalevin-manager` field, the dead `(when datalevin-manager ...)` block, and the `::datalevin-manager` schema reg (per `remaining.md` cluster 2 / smell #7)? Or keep cluster-1 minimal and let the datalevin scrub agent own that? Both work; coupling them saves a second pass.
3. **Cluster 3 — fixture in test file or shared helper?** A `tu/transact-graph!` helper in `seon.test-utils` would make the next test (or the next agent porting these to datahike) immune to this. Worth asking before the fix agent lands a local-only change.
4. **Datalevin direct-use tests in `pipeline_test` and `workout_test`.** Do these stay (cluster-4 work pulls them along), get ported to datahike `:memory` first, or get deleted in favor of datahike-side bridge tests? Open question regardless of the triage.
5. **Is the green test suite the success bar, or is "datahike-flow exercises everything" the bar?** Today, fixing all 14 gets you `success true` on `(user/run-tests)`, but it does **not** mean the datahike flow has full test coverage — clusters 2 and 3 will still be testing the legacy datalevin path after the fix. If the migration's intent is to leave datahike as the sole DB, the bar deserves to be higher than green.

## Top-3 most-confident next fixes

1. **Cluster 2 (one-line):** add `:seon.fn/input-shape` and `:seon.fn/output-shape` to the filter in `pipeline_test.clj:735-736`. Highest confidence; the bug is fully understood and the fix is mechanical.
2. **Cluster 3 (one-file, 4 lines):** transact entries + shapes between specs and functions in `workout_test.clj:191-197`. Same dependency-order pattern as `ingest-fn-spec-ref-pipeline-test` already does for specs.
3. **Cluster 1 (one-line in source):** widen `:seon.orchestrator.session/namespace` to `[:or [:string {:min 1}] :symbol]` (or `:any` if you want to defer the schema discussion). Restores the symbol-or-string promise the docstring already makes.

If all three land independently, the suite goes from 14 errors to 0 with three small, reviewable patches and no migration risk.
