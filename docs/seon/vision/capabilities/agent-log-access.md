---
type: capability
status: not-started
tags: [vision, agent]
---
# Agent Log Access

Agents can query and search their own logs through safe REPL functions and a web UI. Log parsing exists but the agent-facing API and web route are not wired.

## What Exists

`web/logs.clj` exists with `parse-log-line` for structured log parsing. No further implementation.

## Gaps

- Four agent-safe REPL functions (tail, search, errors, context) not implemented
- No `/logs` route registered
- No web UI for log browsing

## Related

- Components: [[components/web-layer]], [[components/dev-tools]]
- PRDs: [[prds/logging-system/prd]]
