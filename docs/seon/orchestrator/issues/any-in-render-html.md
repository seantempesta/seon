---
type: issue
status: open
severity: architectural
---
# :any in Render/HTML Schemas

## Problem

`::typed-response`, `::render-request`, `::view-type-request` in `ns/view.clj` use `:any` for the value being rendered. The render system accepts anything by design, but the schema should express this more precisely -- perhaps a union of known renderable types, or at minimum `:some` with a clear rationale documented.

## Where

- `src/seon/ns/view.clj` — schema registrations for render-related types

## Acceptance Criteria

- No `:any` remains in render schemas
- Replaced with a concrete union of renderable types or a justified `:some` with documented rationale
- Render system continues to work for all existing view types
- Tests pass

## Related

- [[components/renderer]]
