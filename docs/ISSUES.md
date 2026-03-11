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

