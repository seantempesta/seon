# Open Issues

**Read this file at the start of every session.** Every item here is unresolved. Work through them by priority. When an item is fixed, delete it — don't mark it done, don't move it to a "resolved" section. This file should shrink over time. If it's growing, something is wrong.

---

## How to Use This File

**Adding:** Any agent or orchestrator that discovers a problem adds it here with enough context to act on it. Include file, line, what's wrong, and why it matters.

**Format:** One `###` heading per issue. Keep it terse. The heading is the problem, the body is context.

**Priority:** Items at the top are highest priority. New items go at the bottom unless they block other work.

**Closing:** When fixed, delete the entire `###` block. Commit the deletion with the fix. No graveyard.

---

## Issues

### `:any` in wire protocol schemas (`flow/msg.clj`)

`::msg/args`, `::msg/payload`, `::msg/value` use `:any` because they carry arbitrary function arguments across the wire. This violates the "no `:any`" rule and means wire data is unvalidated. Needs a design decision: tagged unions, schema-per-message-type, or Nippy-opaque blobs with validation at the endpoints.

**File:** `src/seon/flow/msg.clj`

### `:any` in render/html schemas (`ns/view.clj`)

`::typed-response`, `::render-request`, `::view-type-request` use `:any` for the value being rendered. The render system accepts anything by design, but the schema should express this more precisely — perhaps a union of known renderable types, or at minimum `:some` with a clear rationale.

**File:** `src/seon/ns/view.clj`

### Functions missing `:malli/schema` metadata

Many public functions — especially in `ai/datalevin.clj` — lack `:malli/schema` and don't follow map-in/map-out. Until every public function is spec'd, the graph can't do schema-based discovery (VISION.md M1). This is the single biggest blocker to the core primitive.

### Convention audit: map-in/map-out compliance

Gemini review consistently flags functions using positional args in public APIs. Need a systematic pass to identify all non-compliant public functions and either convert them or make them private.

### `seon.graph.ingest` doesn't index function schemas

The graph indexes function names, arglists, docstrings, and dependencies — but not input/output Malli schemas. Without this, schema-based discovery (VISION.md M2) is impossible. The schema data is available via `malli.core/function-schemas` at runtime.

### Raw Datalevin connection in `flow/agent_runner.clj`

`agent_runner.clj:44` calls `datalevin.core/get-conn` directly via requiring-resolve. Should use `db/resolve-conn` like the rest of the codebase. Low priority — only used during agent JVM bootstrap.

**File:** `src/seon/flow/agent_runner.clj:44`

### Datalevin race condition: concurrent DB open → `mdb_page_dirty` crash

**Root cause identified.** `open-kv` in `datalevin/binding/cpp.clj:1576-1603` is not thread-safe for concurrent first-open of the same database. When multiple clients connect simultaneously and trigger `open-server-store` for a new DB:

1. Thread A creates `data.mdb`, starts writing VERSION file
2. Thread B sees `data.mdb` exists but VERSION is not yet visible → hits `:else` branch → "requires migration" ExceptionInfo
3. LMDB state corrupted → `mdb.c:5823: Assertion 'rc == 0' failed in mdb_page_dirty()` → server JVM crashes

Observed twice on 2026-03-06. The `seon-flow` DB (`:seon.flow` keyword → `seon-flow` → hex `73656F6E2D666C6F77`) was being created fresh at crash time (file birth times match crash timestamp exactly). Not a version migration issue — a concurrency bug in Datalevin's `open-kv`.

**Cascade:** Datalevin crash → infrastructure flow loses DB → all `db/transact!` and `db/query` fail → SSE pages error-loop "Infrastructure flow not running"

**Fix options:**
- Report upstream to Datalevin — `open-server-store` (`server.clj:1003`) should serialize per-DB-name opens
- Workaround: ensure all DBs are opened sequentially at startup before concurrent access begins (pre-warm connections)

**Files:** `reference-code/datalevin/src/datalevin/binding/cpp.clj:1576-1603`, `reference-code/datalevin/src/datalevin/server.clj:993-1018`
**Log:** `logs/datalevin.log` — search for `mdb_page_dirty` or `requires migration`

### Datalevin hacks that should be removed

Our codebase has accumulated workarounds for Datalevin instability. These mask root causes and should be replaced with proper solutions:

1. **Stale lock cleanup** (`server.clj:174-188`): Blindly deletes ALL `lock.mdb` files before starting. Lock files belong to the Datalevin server process — clients connect over TCP and never touch these files. If the server manages its own lifecycle correctly, this shouldn't be needed.

2. **Magic sleeps** (`user.clj:426,446`): `Thread/sleep 2000` "for LMDB to sync" after stopping. Should use proper shutdown coordination (wait for process exit, verify port released) instead of hoping 2 seconds is enough.

3. **Deprecated raw conn path** (`writer.clj:140`): Writer still accepts a raw Datalevin connection bypassing the connection manager. Should be removed — all access should go through the managed path.

**Goal:** Datalevin is a separate TCP service. Our client code should never touch its filesystem (no lock cleanup, no data dir manipulation). If Datalevin crashes, we reconnect — we don't clean up its internal state.

### Agent JVM port 7951 bind failure (Address already in use)

Agent pool tried to spawn JVM on port 7951 but it was already bound. Three rapid retries all failed with `BindException: Address already in use`. The pool didn't detect the existing process or pick a different port. May be a stale JVM from a previous crash that wasn't cleaned up.

**File:** `src/seon/flow/pool.clj` — port allocation logic
**Log:** `logs/app.log` — `Agent JVM stderr {:port 7951}`
