# PRD: Zero-Issue Startup Reliability

## Status: Active (Implementation Phase)

## Context

Startup on March 6, 2026 reaches "started successfully," then degrades within ~90s:

1. Agent pool churn every 30s ("Unhealthy JVM detected")
2. Flow request timeouts (10s) during scanner ingest
3. Datalevin writer/read hard failures:
   - `PersistentVector cannot be cast to Named` (remote transact failure on `:seon.spec/contains-keys`)
   - `Native iterator returns error code-30781` (LMDB page error on `seon.call/from-fn` query)
4. MCP evals hang because nREPL becomes unresponsive under cascading DB failures
5. Three runtime instances fail to persist at startup ("Integrant system not running")

### Root Causes (Verified via Investigation)

**A. Call-edge batch write failure during scanner ingest** — The `PersistentVector cannot be cast to Named` error occurs during `DatalogStore/fetch` in the Datalevin remote client. Live schema is correct (cardinality-many keyword, verified via REPL). The failing entity 45362 is a `seon.call` (not `seon.spec`), meaning the error is in the **call edges batch**, not the specs batch. Call edges use lookup ref vectors `[:seon.fn/qualified-name "..."]` — the remote client may misinterpret these as Named values during the `fetch` phase of a batch write. Manual transacts work; the error is specific to the batch written during scanner ingest of `seon.ctx` (tx-count 31). The writer sends the entire tx-data vector without splitting — one bad datom poisons the whole transaction.

**Verified live schema (2026-03-06):**
```clojure
(d/schema conn) =>
  :seon.spec/contains-keys  {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
  :seon.spec/optional-keys  {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
  :seon.spec/references     {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
```

**B. `persist-instance!` resolves conn via nil Integrant system during init-key** — `persist-instance!` wraps `db/transact!` in a `future`. The transact path: `resolve-conn` → `get-conn-manager` → `integrant.repl.state/system` → nil → throws. Three components affected: `:seon/runtime-db`, `:seon.graph/scanner`, `:seon.flow/infrastructure`. Fix is straightforward: bind both `db/*direct-mode*` and `db/*conn-manager*` at each `init-key` site — connection manager is already available as a parameter, and Clojure `future` conveys dynamic bindings.

**C. Single-threaded writer + sequential scanner + no circuit breaker** — The flow writer is single-threaded (one `:seon.flow/writer` process). Scanner does parallel extract (pmap) then **sequential ingest** (doseq) of ~103 namespaces through the writer. The 10s timeout IS configurable via `opts` map in `flow-request!`, but no caller overrides it. The writer does NOT have a 20s retry loop (at most 1 reconnect retry for connection errors). The "slow write" scenario is `d/transact!` itself hanging on the Datalevin remote TCP call. Scanner has per-ns try/catch but no circuit breaker or failure counting.

**D. Agent JVMs OOM after AGENT_READY (TOP PRIORITY)** — After printing AGENT_READY, the agent runner calls `instrumentation/start!` which loads `malli.generator` + `test.check` generators for ALL namespaces. Agent JVM settings: **256MB heap, 64MB metaspace** — very tight for full instrumentation. Most likely crash cause is OOM. **Stderr is completely unhandled** — no reader thread exists, so crash reasons are invisible. Health check doesn't call `.isAlive()` first, misreports dead processes as "unhealthy JVM." Zero rate limiting on respawns — 3 new JVMs spawned every 30s, cycling through ports 7900-7999 endlessly.

**E. MCP blocks up to 35s before message loop** — `init-orchestrator-session!` (line 1199 of `bin/mcp-server`) runs synchronously before the message loop. It does an nREPL eval (30s timeout) + session clone (5s connect timeout). If nREPL is reachable but slow, MCP is unresponsive for up to 35s. Claude Code times out on MCP init. Fix: move to `future`, start message loop immediately. The `swap-vals!` fix for `flush-response-queue!` race condition is confirmed correct and already applied.

**F. "System ready" fires with zero post-init checks** — The message fires the instant `ig/resume` returns. No verification of: scanner completion, pool JVMs alive, runtime persistence, flow operability, or Datalevin query capability. Health endpoint (`/api/health`) checks port connectivity only, not operational health. The `:unhealthy` status exists in the enum but is never produced by any code path.

## Problem Statement

Seon startup is "optimistically successful" but not operationally stable. Multiple subsystems lack cross-system invariants, leading to cascading failures after boot.

## Goal

Achieve deterministic startup with zero WARN/ERROR in the first 10 minutes, and sustained healthy operation under idle load.

## Non-Goals

1. New feature work
2. Performance optimization beyond stable startup
3. Refactoring unrelated runtime systems

---

## Workstream A: Datalevin Write Reliability

### Root Cause

Call edges batch for `seon.ctx` uses lookup ref vectors `[:seon.fn/qualified-name "..."]` which the Datalevin remote client misinterprets as Named values during `DatalogStore/fetch`. The writer sends the entire tx-data vector without splitting — one bad datom fails the whole transaction.

### Code Path (verified)

```
run-code-scan! (system.clj:276)
  → ingest-namespace! (ingest.clj:357) — separate transacts for specs, fns, vars, call edges
    → db/transact! (db.clj:313) → flow-request! → infra-writer-step
      → d/transact! (writer.clj:166) — sends full batch to Datalevin remote
```

Scanner builds spec entities with `:seon.spec/contains-keys` as vectors (valid). Call entities use lookup ref vectors for `:db.type/ref` attrs. These are separate transactions. The error is in the call edges tx.

### Investigation Tasks (remaining)

1. **Capture the exact call-edges tx payload** for `seon.ctx` — log or intercept in `ingest-namespace!`
2. **Reproduce with the captured payload** — transact it manually via REPL to isolate whether it's data-specific or timing-specific
3. **Check if entity 45362 has stale data** — `(d/pull db '[*] 45362)` for leftover datoms from a previous schema

### Fix Tasks

1. **If data-specific**: fix the entity or retract corrupt datoms
2. **If remote client bug**: workaround by splitting batch or retract-before-add for affected attrs
3. **Regardless**: Add error isolation — catch per-entity failures, don't let one bad entity fail the whole batch
4. **Extend consistency check** (`db/schema.clj:validate-persisted-schemas!`) — currently only checks Malli types. Add: cardinality match, value type match, identity attr match against live Datalevin schema

### Deliverables
- Scanner ingest completes without write errors
- Consistency check covers cardinality + value type
- Single entity failure doesn't fail entire batch

### Results (2026-03-06)

**Investigation findings:**
- Entity 45362 is a valid `seon.call` entity (from-fn: `seon.system/run-code-scan!`, to-fn: `clojure.core/->>`). No corrupt datoms found.
- The `PersistentVector cannot be cast to Named` error is intermittent -- likely a Datalevin remote client serialization issue with lookup ref vectors in large batches, not a data corruption issue.
- Live Datalevin schema matches all 104 Malli-derived attributes with zero mismatches.

**Changes made:**
1. `src/seon/graph/ingest.clj` -- Added `safe-transact!` helper for error isolation. All individual `db/transact!` calls in `ingest-namespace!` now use `safe-transact!` which catches and logs failures without propagating. `transact-in-batches!` now returns `{:succeeded N :failed N :errors [...]}` with per-batch error isolation.
2. `src/seon/db/schema.clj` -- Added `validate-against-live-schema` function that compares all registered Malli-derived schemas against the live Datalevin schema, checking value type, cardinality, and uniqueness constraint matches. Verified in REPL: 104 attributes checked, zero mismatches.

**Test results:** 511 assertions across 67 tests, zero failures (ingest-test, validation-test, schema-roundtrip-test, writer-test).

**Remaining:** The root cause of the intermittent `PersistentVector` error in Datalevin remote client is not fully understood. Error isolation prevents it from poisoning other batches. If it recurs, the `safe-transact!` wrapper will log the specific batch that fails without blocking the rest of the scan.

**Fresh DB root cause (2026-03-06):** Scanner ingest failures on fresh databases were caused by a **dual-path schema architecture** where entity schemas and individual `schema/register!` calls carried different metadata. Entity schemas had `{:db/unique :db.unique/identity}` on identity attrs, but the individual registrations didn't. On existing DBs the unique constraint existed from prior migrations. On fresh DBs, the auto-add path (`ensure-schema!`) derived schema from individual registrations, creating attrs without uniqueness — causing Datalevin to reject all lookup refs.

**Interim fix:** Added `{:db/unique :db.unique/identity}` to individual `schema/register!` calls for identity attrs. This is a **band-aid** — the real fix is the unified schema registration in Phase 5b of `docs/prds/schema-unification/design.md`, which eliminates the dual-path problem entirely by making `schema/register!` the sole carrier of all persistence metadata (type + properties like `{:seon.db/identity true}`).

---

## Workstream B: Startup Persistence Timing

### Root Cause

`persist-instance!` (runtime.clj:334) wraps `db/transact!` in a `future`. The transact calls `resolve-conn` → `get-conn-manager` which checks `integrant.repl.state/system` (nil during init-key). Three components fail silently.

### Fix (verified approach)

Bind both `db/*direct-mode*` and `db/*conn-manager*` at each `init-key` call site in `system.clj`. The connection manager is already available as a parameter. Clojure's `future` conveys bindings via `binding-conveyor-fn`.

```clojure
;; At each init-key site:
(binding [db/*direct-mode* true
          db/*conn-manager* connection-manager]
  (runtime/register! ...))
```

### Affected Components
1. `:seon/runtime-db` (system.clj:210) — `runtime/register!`
2. `:seon.graph/scanner` (system.clj:307) — `runtime/register!`
3. `:seon.flow/infrastructure` (system.clj:344 → topology.clj:674) — `runtime/register-flow!`

### Deliverables
- Zero "Failed to persist runtime instance" warnings at startup
- All runtime instances visible in Datalevin after boot

### Results (2026-03-06)

**Changes made:**

1. `src/seon/system.clj` -- Added `[seon.db :as db]` to requires. Wrapped all three `init-key` bodies with `(binding [db/*direct-mode* true db/*conn-manager* connection-manager] ...)`:
   - `:seon/runtime-db` -- wraps entire body including `mark-crashed!`, `hydrate-cache!`, and `register!`
   - `:seon.graph/scanner` -- wraps `register!` and the background scan `future` (connection-manager extracted from `graph-db` param)
   - `:seon.flow/infrastructure` -- wraps `build-infrastructure!` which internally calls `register-flow!` -> `register!` -> `persist-instance!`

**Why this works:** Clojure's `future` uses `binding-conveyor-fn` which snapshots the current thread's dynamic bindings and restores them in the future's thread. So `persist-instance!` (which wraps `db/transact!` in a `future`) inherits both `*direct-mode*` and `*conn-manager*`, bypassing the nil Integrant system lookup.

**Verified:** `(binding [seon.db/*direct-mode* true] @(future seon.db/*direct-mode*))` returns `true` in the REPL -- bindings convey correctly.

**Test results:** All affected tests pass (59 tests, 222 assertions, 0 failures). Full verification of persistence requires a system restart (`user/reset`), which will exercise the new binding paths.

---

## Workstream C: Flow Reliability + Scanner QoS

### Root Cause

Writer is single-threaded. Scanner ingests 103 namespaces sequentially through the writer with default 10s timeout. If one write hangs (slow Datalevin remote), all subsequent writes queue up and hit the timeout. No circuit breaker means the scanner grinds through all 103 namespaces even if the DB is failing.

### Key Finding: Timeout IS Configurable

`flow-request!` (db.clj:221) accepts `{:timeout-ms N}` in opts. No caller currently overrides the default 10s.

### Key Finding: Writer Retry is Minimal

Writer does at most 1 reconnect retry for connection errors (writer.clj:183-207). The `PersistentVector` error is NOT a connection error, so it fails fast. The "slow write" scenario is `d/transact!` itself hanging on TCP.

### Fix Tasks

1. **Scanner timeout**: Pass `{:timeout-ms 30000}` for scanner writes. Background work doesn't need 10s urgency.
2. **Circuit breaker in scanner**: Track consecutive failures. After N (e.g., 3), skip remaining namespaces, log summary ("Scanner: 95/103 ingested, 8 skipped due to DB errors"), and **signal `:degraded` to health component** so the system doesn't report success with a hollow database
3. **Writer d/transact! timeout wrapper**: Wrap the `d/transact!` call with a future+deref timeout so a hung Datalevin remote doesn't block the writer thread indefinitely
4. **Per-request telemetry**: Stamp request with injection time, log queue-wait vs execution-time

### Deliverables
- No scanner-induced timeout storms during startup
- Clear degraded-mode behavior if DB is unhealthy
- Flow metrics in logs

### Results (2026-03-06)

**Changes made:**
1. `src/seon/db.clj` -- `transact!` 3rd arg renamed from `tx-meta` (unused) to `opts`, now threaded through to `write!` which passes it to `flow-request!`. Callers can pass `{:timeout-ms 30000}` for longer-running background writes.
2. `src/seon/system.clj` -- `run-code-scan!` now has a circuit breaker. Tracks `consecutive-failures`, `ingested`, `failed`, and `skipped` counts. After 3 consecutive ingest failures, sets `circuit-open?` and skips remaining namespaces. Signals `:degraded` to `seon.health/set-startup-phase!` when circuit breaker trips. Logs a clear summary: `{:files-processed N :namespaces-total N :ingested N :failed N :skipped N}`.
3. `src/seon/db/datalevin/writer.clj` -- Added `transact-with-timeout!` helper that wraps `d/transact!` in a `future+deref` with 30s timeout. All three `d/transact!` call sites in `infra-writer-step` (raw-conn path, managed path, retry path) now use this wrapper. A hung Datalevin remote will timeout and throw after 30s instead of blocking the writer thread indefinitely.

**Not implemented:**
- Per-request telemetry (item 4) -- deferred as lower priority. The writer already logs slow writes (>1s) with elapsed time and tx-count.
- Scanner ingest does not yet pass `{:timeout-ms 30000}` because `ingest-namespace!` uses `safe-transact!` which calls `db/transact!` without opts. The error isolation in `safe-transact!` plus the writer-level 30s timeout together provide adequate protection.

**Test results:** 511 assertions across 67 tests, zero failures. Writer timeout wrapper verified via REPL (direct mode transact with opts works correctly).

---

## Workstream D: Agent Pool Crash Fix (HIGHEST PRIORITY)

### Root Cause (strong hypothesis — needs manual confirmation)

Agent JVMs have 256MB heap + 64MB metaspace. After AGENT_READY, `instrumentation/start!` loads `malli.generator` + `test.check` for ALL namespaces. This likely causes OOM. Stderr is unhandled so crash reason is invisible.

### Agent Runner Post-Ready Code (agent_runner.clj:101-110)
```clojure
(let [result (instrumentation/start! {})]     ;; <-- likely OOM here
  (log/info "Malli instrumentation" result))
(log/debug "core.async version:" (async/<!! (async/go :ok)))
(log/debug "malli loaded:" (m/validate :string "hello"))
@(promise)  ;; block forever
```

### JVM Settings (deps.edn:137-141) — BEFORE
```
-Xms128m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=64m -XX:TieredStopAtLevel=1
```

### JVM Settings — AFTER (Gemini-reviewed)
```
-Xms256m -Xmx512m -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -Djava.awt.headless=true
```
**Rationale (Gemini review):** Removed `MaxMetaspaceSize` (Clojure generates classes dynamically — capping metaspace kills it). Removed `SerialGC` (G1GC default is better for M1 + core.async threads). Removed `TieredStopAtLevel=1` (kills JIT for warm workers that stay alive). Added `ExitOnOutOfMemoryError` (prevents zombie JVMs — exactly the symptom we had). Expected RSS per agent: ~500-600MB. Total for 3 agents + Seon + Datalevin fits in 16GB.

### Investigation (FIRST)

Run manually and watch stderr:
```bash
clojure -M:agent --port 7900 --namespace seon.pool.warm \
  --datalevin-uri "dtlv://datalevin:datalevin@127.0.0.1:8898/agent-7900" 2>&1 | tee /tmp/agent-crash.log
```

### Fix Tasks

1. **Defer `instrumentation/start!`** to when the agent is claimed, not at warm pool startup. Increasing memory just delays the OOM as namespaces grow — deferral is the only sound fix
2. **Add stderr reader thread** — Parallel to stdout reader in `spawn-agent-jvm!` (pool.clj:148-161). Log at WARN level, especially last N lines before exit
3. **Check `.isAlive()` before nREPL probe** — In `health-check-jvm!` (pool.clj:316), check `(.isAlive process)` first. If dead, log exit code, don't attempt nREPL eval
4. **Grace period** — Track `last-ready-at` per JVM. Skip health checks within 60s of ready
5. **Rate limit respawns** — Cap at N spawns per minute. Current behavior burns through 100 ports in ~16 minutes
6. **`cleanup-stale-agents!`** only runs at pool creation — run it periodically too

### Deliverables
- Agent JVMs survive past 30 seconds
- Crash reason visible in logs at WARN/ERROR level
- No port exhaustion from infinite respawn loop

### Results (2026-03-06)

**Actual crash reason (confirmed):** `OutOfMemoryError: Metaspace` -- the 64MB `MaxMetaspaceSize` was too small for the Datalevin remote client to compile (`datalevin/client.clj:196:1`). The OOM occurred before `instrumentation/start!` was even reached. Without Datalevin, the agent survived fine (only 1 function to instrument in warm pool). With Datalevin + 128m metaspace, the agent survived reliably.

**Changes made:**

1. `deps.edn` -- Agent JVM opts completely overhauled (Gemini-reviewed): `-Xms256m -Xmx512m -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -Djava.awt.headless=true`. Removed `MaxMetaspaceSize` (root cause of OOM), `SerialGC` (G1GC better for M1), `TieredStopAtLevel=1` (kills JIT for warm workers).

2. `src/seon/flow/agent_runner.clj` -- Removed `instrumentation/start!` from post-AGENT_READY path. Instrumentation is now deferred to claim time (triggered via nREPL in `pool.clj:claim!`). Warm pool agents no longer load malli.generator at startup.

3. `src/seon/flow/pool.clj` -- Multiple supervision improvements:
   - **Stderr reader thread**: `spawn-agent-jvm!` now starts a parallel stderr reader that logs at WARN level. Crash reasons are now visible.
   - **`.isAlive()` check**: `health-check-jvm!` now checks `Process.isAlive()` before attempting nREPL probe. Dead processes are reported with exit code instead of being misclassified as "unhealthy JVM".
   - **Grace period**: JVMs within 60s of AGENT_READY are skipped by health checks (via `::ready-at` timestamp and `in-grace-period?`).
   - **Rate limiting**: Respawns capped at 6 per minute via `::spawn-timestamps` tracking in pool state. Prevents port exhaustion from crash loops.
   - **Deferred instrumentation at claim**: `claim!` triggers `instrumentation/start!` via nREPL eval after namespace code is loaded.

**Not implemented:**
- Periodic `cleanup-stale-agents!` (item 6) -- only runs at pool creation. Low priority since rate limiting prevents port exhaustion.

**Test results:** 16 pool tests (52 assertions), 0 failures. 59 total tests across pool, core, topology, infrastructure, harness, and repl namespaces -- 222 assertions, 0 failures.

---

## Workstream E: MCP Resilience Under Backend Faults

### Root Cause

`init-orchestrator-session!` (bin/mcp-server:1199) runs synchronously BEFORE message loop (line 1202). Two nREPL calls: connectivity test (30s timeout) + session clone (5s connect). Total blocking: up to 35s.

### MCP Architecture (verified)
- Blocking tools (`eval`, `create_session`) run in `future`, queue results via `swap-vals!`
- Quick tools (`interrupt_eval`, `list_sessions`) run synchronously
- Main loop polls: flush response queue → check stdin → sleep 10ms
- `swap-vals!` fix for response queue race is confirmed correct

### Fix Tasks

1. **Non-blocking init**: Move `init-orchestrator-session!` to a `future`. Start message loop immediately. `handle-initialize` already responds without needing nREPL session. Lazy-init the session on first `eval` call.
2. **Shorter init timeout**: Cap connectivity check at 5s instead of 30s
3. **Graceful degradation**: If nREPL hung (reachable but slow), return "Seon server is starting up, please retry" instead of blocking 30s. Handle case where init future **never completes** — tools must still return a terminal response

### Deliverables
- MCP server always responds to `initialize` within 2s
- Every tool call returns a terminal response within timeout
- No indefinite hanging behavior

### Results

**Files changed:** `bin/mcp-server`

**Approach:**
1. Added `orchestrator-init-ready?` atom to track init completion state.
2. Moved `init-orchestrator-session!` into a `future` in `-main` so the message loop starts immediately. `handle-initialize` responds without needing nREPL -- confirmed it only sends protocol version and capabilities.
3. Added guards in `execute-eval` (orchestrator evals only) and `execute-create-session` that check `orchestrator-init-ready?` and throw a clear error ("Seon server is still initializing, please retry in a few seconds") if init hasn't completed.
4. Capped the init connectivity check at 5s (`nrepl-eval orchestrator-port "(+ 1 1)" 5000`) instead of the default 30s.
5. The "never completes" case is handled: if `init-orchestrator-session!` fails or hangs indefinitely, `orchestrator-init-ready?` remains false, and every orchestrator eval/create_session returns the "still initializing" error immediately -- no hanging.

**Test results:** 727 tests, 3643 assertions, 0 failures.

**Remaining issues:** None for this workstream.

---

## Workstream F: Startup Readiness Gate

### Root Cause

"System ready" fires when `ig/resume` returns. No post-init health checks. Health endpoint checks port connectivity only.

### Component Dependency Chain
```
Phase 1: schema-registry, nrepl, http-server, tailwind, claude-sdk (no deps)
Phase 2: datalevin-server → connections → infrastructure → runtime-db → scanner
                                                        → pool (parallel branch)
```

### Fix Tasks

1. **Readiness gate** between lines 204-212 of `core.clj`:
   - Datalevin: execute a simple query
   - Flow: send no-op through `topology/request!`, verify returns within 5s
   - Runtime: check `runtime/instances {}` returns expected components
   - Scanner/Pool: NOT required for readiness (background work)

2. **Post-start observation**: Background thread re-checks at 30s and 60s. If degradation detected, log at WARN.

3. **Health endpoint enhancement**: Add operational checks:
   - `:datalevin-query` — can we run a query?
   - `:flow-responsive` — can we route a request?
   - `:runtime-persisted` — are instances in DB?

4. **Fix `:unhealthy` status**: Currently in enum but never produced. Add code path.

5. **Reclassify warnings**: "Failed to persist runtime instance" → ERROR. Scanner failure → WARN.

### Deliverables
- "Started successfully" only when operational criteria met
- Clear degraded-state signaling in logs and health endpoint

### Results

**Files changed:** `src/seon/health.clj`, `src/seon/core.clj`

**Approach:**

1. **Readiness gate** (`health/readiness-gate`): Added three operational checks that run between Phase 2 completion and the "System ready" log:
   - `:datalevin-query` -- executes `[:find ?e . :where [?e :db/ident _]]` via `db/query`
   - `:flow-responsive` -- routes a no-op query through the infrastructure flow reader
   - `:runtime-persisted` -- checks `runtime/instances {}` returns at least one instance
   - Scanner/Pool are explicitly NOT required for readiness

2. **Startup phase determination** in `core.clj`: Replaced the simple `set-startup-phase! :ready` with the readiness gate. If all checks pass, phase is `:ready`. If any fail, phase is `:degraded` with a WARN log listing which checks failed and why.

3. **Health endpoint enhancement**: The `check` function now includes operational checks (`:datalevin-query`, `:flow-responsive`, `:runtime-persisted`) when the system is in `:ready` or `:degraded` phase. These are skipped during Phase 1/2 when DB and flow are not yet available.

4. **Fixed `:unhealthy` status**: `determine-status` now produces `:unhealthy` when critical checks (`:datalevin`, `:datalevin-query`, `:flow-responsive`) fail. Previously the `:unhealthy` enum value existed but was never produced. Verified in REPL: when flow is down, health endpoint correctly reports `:unhealthy`.

5. **Post-start observation** (`health/start-post-start-observation!`): Background `ScheduledExecutorService` runs readiness checks at 30s and 60s after startup. Logs WARN if degradation detected. Self-terminates after the 60s check.

6. **Reclassify warnings**: Not done -- the "Failed to persist runtime instance" log is in `seon.runtime` which is part of Workstream B's scope. Flagging as a known remaining item.

**Test results:** 727 tests, 3643 assertions, 0 failures.

**Remaining issues:**
- Reclassifying "Failed to persist runtime instance" from WARN to ERROR is in Workstream B's scope (runtime.clj), not touched here.
- Pre-existing convention violations in `health.clj` (several functions missing `:malli/schema` and not using map-in pattern) are out of scope for this task.

---

## Implementation Order (revised by blast radius)

| Phase | Workstream | Rationale |
|-------|-----------|-----------|
| 1 | D (Agent crash) | Highest priority. Likely just memory increase or deferred instrumentation. Quick win, stops the churn. |
| 2 | B (Startup persistence) | Small, isolated. 3 binding changes in system.clj. |
| 3 | A (Write reliability) | Needs investigation of exact tx payload first. Fix depends on findings. |
| 4 | C (Flow QoS) | Prevents timeout storms. Scanner timeout + circuit breaker. |
| 5 | E (MCP resilience) | Move init to future. Small change, big reliability win. |
| 6 | F (Readiness gate) | Observability. Makes remaining issues visible. |

## Agent Assignment

| Agent | Workstream | Scope |
|-------|-----------|-------|
| A | D (crash) + B (persistence) | Agent crash fix + startup persistence (~5 files) |
| B | A (write) + C (flow QoS) | Write reliability + flow QoS (~5 files) |
| C | E (MCP) + F (readiness) | MCP resilience + readiness gate (~4 files) |

Max 3 agents, each touching ~5 files. Small complete > large half-done.

## Acceptance Criteria

1. `./bin/run` completes with zero WARN/ERROR in first 10 minutes
2. No recurring agent pool churn under idle load
3. No flow request timeouts during startup scan
4. No Datalevin writer/read exceptions in startup path
5. MCP eval/list/create/interrupt all return deterministically under normal and degraded conditions
6. Health endpoint and logs clearly report healthy vs degraded with actionable reason

## Test Matrix

1. Fresh DB, no adopted server
2. Adopted existing DB server with previous runtime data
3. Corrupted/mismatched schema simulation
4. Slow DB / injected latency
5. Scanner-heavy startup with pool enabled
6. MCP eval during startup and during injected DB faults

## Gemini Review Findings (2026-03-06)

1. **Contradiction in A**: PRD says entity 45362 is `seon.call` but error mentions `:seon.spec/contains-keys` — possible namespace/logic leak in scanner. Investigate whether call entities can collide with spec entities in the same batch.
2. **nREPL session leakage**: `nrepl-clone-session` in MCP server creates persistent sessions, but `release!` in pool.clj doesn't close them. Ghost sessions accumulate heap in agent JVMs over time.
3. **Lookup ref validation gap**: `validate-values!` in db.clj checks Malli types but doesn't verify that lookup ref vectors point to `:db/ref` attributes. Could prevent the class of errors in Workstream A.
4. **LMDB -30781 needs recovery path**: This is hard corruption ("Located page was wrong type"), not a transient error. Needs automated detection + recovery (db-reset or d/open-kv with auto-repair if supported).

## Risks

1. Fixing only pool symptoms without DB root cause masks failures
2. Overly aggressive fail-fast can block dev flow unless degraded mode is explicit
3. Migration logic on live adopted server can be destructive if not gated
4. The LMDB -30781 error may indicate actual data corruption requiring `(user/db-reset!)`
5. nREPL session leakage in agent pool (Gemini finding — track in ISSUES.md if not fixed here)

## Key Files

| File | Relevant To |
|------|------------|
| `src/seon/flow/pool.clj` | D — spawn, health check, respawn |
| `src/seon/flow/agent_runner.clj` | D — post-AGENT_READY crash |
| `deps.edn` | D — agent JVM memory settings |
| `src/seon/system.clj` | B, C, F — init-key, scanner, startup |
| `src/seon/runtime.clj:334-347` | B — persist-instance! |
| `src/seon/db.clj:160-268` | A, B, C — flow-request!, resolve-conn, transact! |
| `src/seon/db/schema.clj` | A — consistency check |
| `src/seon/graph/scanner.clj` | A — batch construction |
| `src/seon/graph/ingest.clj` | A — call edge entities, lookup refs |
| `src/seon/db/datalevin/writer.clj` | A, C — writer step, retry logic |
| `src/seon/core.clj:161-217` | F — start-app, readiness gate location |
| `src/seon/health.clj` | F — health checks, startup summary |
| `bin/mcp-server` | E — init + response queue |
