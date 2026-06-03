---
type: prd
status: active
tags: [prd, database]
---

# Test-suite baseline — datahike migration cleanup

**Captured:** 2026-05-14 15:58:42 ICT (08:58 UTC), via running orchestrator REPL on `:7888`.
**Runner:** `(user/run-tests)` → `seon.dev.test/test-all` (custom orchestration; per-ns isolation via `safe-run-ns-tests`).
**Suite scope:** unit tests only — namespaces with `^:integration` metadata are excluded by `test-all`.
**System state at capture:** Integrant boot complete; HTTP `:8080`, Caddy `:3030`, nREPL `:7888`, datahike flow on `:memory` across 5 namespaces, 3-JVM agent pool warm. `user/status` reports `:unhealthy` only because datalevin (`:8898`) is intentionally not running and tailwind is not started — irrelevant to in-REPL unit tests.

## Overall counts

| Metric                     | Value     |
|----------------------------|-----------|
| Test namespaces discovered | 77        |
| Test namespaces clean      | 74        |
| Test namespaces with errors| 3         |
| Total tests (`deftest`s)   | 825       |
| Total passing assertions   | 3922      |
| Failures (`is` mismatches) | 0         |
| Errors (uncaught throws)   | 14        |
| Overall success            | `false`   |
| Wall-clock duration        | 108.5 s   |

Result stored at `(:r-1110 @user/repl-orchestrator)` (`baseline-result` in user ns at capture time; ephemeral if REPL restarts).

## Per-namespace breakdown

The custom aggregator (`seon.dev.test/aggregate-results`) does not retain per-namespace pass/fail counts in its return value — only aggregated counts plus a flat `::failures` vector. Per-namespace breakdown below is derived from the failure vector; the 74 clean namespaces each ran cleanly with no errors and no failed assertions.

| Namespace                              | Errors |
|----------------------------------------|--------|
| `seon.orchestrator.session-test`       | 12     |
| `seon.db.pipeline-test`                | 1      |
| `seon.health.workout-test`             | 1      |
| (74 others)                            | 0      |

If per-namespace pass counts are needed later, re-run a specific ns with `(user/run-tests 'seon.foo-test)` — single-ns invocation returns its own counts before aggregation.

## Failure clusters

### Cluster 1 — `seon.orchestrator.session-test` (12 errors)

**Root cause:** Test file passes `::session/namespace` as a Clojure symbol (e.g. `'test.id.format`, `'test.start`); the function spec on `seon.orchestrator.session/start-agent-session!` requires `[:string {:min 1 :description "Agent namespace symbol, stored as string"}]`. Malli instrumentation throws before the function body executes.

Every error frame is `instrumentation.clj:320` (the guard, not the test). The actual test sites live in `test/seon/orchestrator/session_test.clj`. Failing vars:

- `session-id-format-test`
- `start-agent-session-test`
- `stop-agent-session-test`
- `get-agent-session-test`
- `list-agent-sessions-test`
- `get-session-port-test`
- `get-session-port-includes-nrepl-session-id-test`
- `set-nrepl-session-id-test`
- `activity-tracking-test`
- `start-session-registers-in-runtime-test`
- `stop-session-unregisters-from-runtime-test`
- `list-sessions-includes-observability-test`

**Sample error (truncated):**

```
== INVALID INPUT ==
x (seon.orchestrator.session/start-agent-session! #:seon.orchestrator.session{:node nil, :namespace test.nrepl.sid, :pool nil})

-- What went wrong --
  Arg 0 > :seon.orchestrator.session/namespace — expected [:string {:min 1, ...}], got test.nrepl.sid (symbol)

```

**Note:** `session.clj` lines 272–365 explicitly contain a `coerce-namespace` helper (docstring: "Coerce a namespace input (symbol or string) to its canonical string form."). The schema was tightened to `:string`-only after the coercion was either removed or moved downstream of the instrumentation guard. Tests are the canary, not the bug; either the schema should accept symbol-or-string, the tests should pre-coerce, or the coercion should sit upstream of instrumentation.

Not obviously related to the datalevin→datahike migration on its face, but worth confirming when triaging since the test file imports `seon.test-utils/with-test-node` and the file's docstring mentions nREPL pool integration is in a sibling file.

### Cluster 2 — `seon.db.pipeline-test/ingest-fn-entity-pipeline-test` (1 error)

```
clojure.lang.ExceptionInfo: Lookup ref attribute should be marked as :db/unique:
  [:QAC3 "YGp34hvbzBku6DuJFkLsrGMtecyg6f"]
  {:error :lookup-ref/unique, :entity-id [:QAC3 "YGp34hvbzBku6DuJFkLsrGMtecyg6f"]}

```

Throws from `db.clj:769`. This is the kind of failure most directly in the datahike-migration line of fire: datahike enforces `:db/unique` on lookup-ref attributes (datalevin's enforcement may have been more permissive, or `:QAC3` may have lost a schema attribute somewhere in the migration). High suspicion this is a real migration regression — investigate first when picking up datahike cleanup.

### Cluster 3 — `seon.health.workout-test/find-renderer-integration-test` (1 error)

```
clojure.lang.ExceptionInfo: Nothing found for entity id
  [:seon.shape/id "shape:seon.health.workout/add-set-request"]
  {:error :entity-id/missing, :entity-id [:seon.shape/id "shape:seon.health.workout/add-set-request"]}

```

Throws from `db.clj:791`. The test expects a `seon.shape/id` entity that doesn't exist in the test DB. Either (a) the shape is no longer registered under that name, (b) the test fixture isn't loading the workout namespace's schema, or (c) the migration changed how `:seon.shape/id` is indexed. The error path is the same `db.clj` neighborhood as cluster 2 — both probably share machinery worth eyeballing together.

## Observations and caveats

- **No hangs, no timeouts.** Full run completed in 108.5 s; the 600 s budget was way over.
- **No assertion failures.** Every non-pass is an uncaught exception. The `seon.dev.test/test-all` separation of `fail` vs `error` is intact — these are *errors*, not red `is` checks.
- **`safe-run-ns-tests` did not catch a namespace-level crash.** All 14 errors are per-test exceptions reported by `clojure.test`, not "namespace crashed:" wrappers. Encouraging — no Throwable-level instability.
- **Stale system state caveat:** the running JVM has been up since 2026-05-14 08:48Z and has hosted multiple SSE Flow restarts (visible in stdout during the test run, hundreds of `SSE Flow started/stopped` lines). The SSE flow restarts are normal test cycling and don't appear to affect results, but if a future re-baseline shows different numbers from a fresh boot, suspect REPL accretion.
- **`bin/test` not exercised.** Tests ran in-REPL per `CLAUDE.md` guidance. Fallback path untested.
- **No datalevin coupling observed.** Datalevin daemon is not running (`:not-running` in `user/status`) and 74 of 77 namespaces passed cleanly. The two `db.clj`-pathway errors (clusters 2 and 3) are the most likely datalevin→datahike artifacts. The session-test cluster (cluster 1) is a schema-tightening regression independent of the DB engine.
- **Confidence:** high that the counts are accurate; high that the three clusters describe the full failure surface; medium-high that cluster 2 is a real migration regression worth tackling first; lower confidence on whether cluster 1 was introduced by the migration or by an unrelated tightening.

## How to reproduce

```clojure
;; In the orchestrator REPL:
(def r (user/run-tests))
(select-keys r [:seon.dev.test/test-count :seon.dev.test/pass-count
                :seon.dev.test/fail-count :seon.dev.test/error-count
                :seon.dev.test/duration-ms :seon.dev.test/success])
;; Drill into a cluster:
(user/run-tests 'seon.db.pipeline-test)
(user/run-tests 'seon.health.workout-test)
(user/run-tests 'seon.orchestrator.session-test)

```
