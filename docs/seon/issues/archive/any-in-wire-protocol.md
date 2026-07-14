---
type: issue
status: resolved
severity: cleanup
milestone: M2
tags: [issue, schema, architecture]
---
# :any in Wire Protocol Schemas

## Resolution

**Resolved.** `::msg/args`, `::msg/payload`, `::msg/value` in `flow/msg.clj` now use `:seon.flow/dynamic` — a custom Malli type defined in `schema.clj` (lines 59-66) with a concrete predicate (`some?`) and a working generator. The `:any` has been replaced with a typed dynamic container. Verified by M2 verifier 2026-03-11.

## Where

- `src/seon/flow/msg.clj` — `::args` uses `[:vector :seon.flow/dynamic]`, `::payload` uses `[:map-of :keyword :seon.flow/dynamic]`, `::value` uses `:seon.flow/dynamic`
- `src/seon/schema.clj` — `:seon.flow/dynamic` custom type definition

## Related

- [[components/flow-topology]]
