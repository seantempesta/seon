---
type: capability
status: complete
tags: [vision, flow]
---
# Unified Agent Context System

A single context system provides live state to agents and UI. Each namespace has an atom backed by Datalevin persistence and SSE push. Reserved keys are enforced, values are validated per-key against Malli schemas, and changes propagate to connected clients in real time.

## What Exists

- `ctx.clj` is the sole context system (legacy systems removed)
- Atom + Datalevin persistence + SSE push
- Reserved keys enforced by validator
- Per-key Malli validation, debounced persistence
- Client-targeted SSE push on atom changes

## Gaps

None.

## Related

- Components: [[components/context]]
- PRDs: [[prds/refinement/prd]]
