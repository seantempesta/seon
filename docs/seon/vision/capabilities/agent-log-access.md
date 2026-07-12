---
type: capability
status: partial
tags: [vision, agent]
---
# Agent Log Access

Agents can query and search their own logs through safe REPL functions and a web UI. Web UI exists; agent-facing REPL API not built.

## What Exists

- `/logs` route registered in `web/routes.clj` (GET + POST SSE + POST filter)
- Full log viewer UI with SSE updates, level filtering, and refresh
- `web/logs.clj` with `parse-log-line` for structured log parsing

## Gaps

- Four agent-safe REPL functions (tail, search, errors, context) not implemented
- Log viewer is human-facing only — agents cannot access logs programmatically from the REPL

## Related

- Components: [[components/web-layer]], [[components/dev-tools]]
- PRDs: `prds/logging-system/prd`
