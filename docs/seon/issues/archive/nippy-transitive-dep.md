---
type: issue
status: resolved
severity: cleanup
tags: [issue, flow]
---
# Nippy is a transitive dependency only

## Problem

`taoensso.nippy` is used directly in `src/seon/flow/harness/bridge.clj` and `src/seon/flow/harness/channel.clj` (fast-freeze/fast-thaw for TCP wire protocol), but is not declared in `deps.edn`. It arrives transitively via Datahike or Timbre.

The `:agent` alias also lacks Nippy — agent JVMs use bridge.clj for cross-namespace calls.

If the transitive path changes (version bump, dep swap), the harness TCP protocol breaks silently at runtime.

## File Refs

- `deps.edn` — no `com.taoensso/nippy` entry
- `src/seon/flow/harness/bridge.clj` — `[taoensso.nippy :as nippy]`
- `src/seon/flow/harness/channel.clj` — `[taoensso.nippy :as nippy]`

## Acceptance Criteria

- `com.taoensso/nippy` explicitly declared in `deps.edn` and `:agent` alias

## Severity

friction

## Resolution (2026-06-28 audit)

Closed RESOLVED per `docs/seon/issues-audit-2026-06-28.md`:
`com.taoensso/nippy 3.4.2` is now an explicit dependency in `deps.edn:37`.
