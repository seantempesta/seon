---
type: issue
status: open
severity: architectural
---
# :any in Wire Protocol Schemas

## Problem

`::msg/args`, `::msg/payload`, `::msg/value` in `flow/msg.clj` use `:any` because they carry arbitrary function arguments across the wire. This violates the "no `:any`" rule and means wire data is completely unvalidated. Needs a design decision: tagged unions, schema-per-message-type, or Nippy-opaque blobs with validation at the endpoints.

## Where

- `src/seon/flow/msg.clj` — schema registrations for `::args`, `::payload`, `::value`

## Acceptance Criteria

- No `:any` remains in `flow/msg.clj` schemas
- Wire messages are validated with a concrete schema (tagged union, per-type dispatch, or equivalent)
- Existing flow tests continue to pass
- Serialization roundtrip (Nippy) still works for all message types

## Related

- [[components/flow-topology]]
