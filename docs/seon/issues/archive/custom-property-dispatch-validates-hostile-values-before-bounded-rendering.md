---
type: issue
status: resolved
tags: [issue, rendering, schema]
severity: blocker
---

# Custom property dispatch validates hostile values before bounded rendering

## Evidence

The plan-value crossing exposed a missing sibling of the bounded printer and
bounded traversal fixes. `seon.render/block` calls `custom-render-selection`,
which calls `seon.schema/matching-shapes-in` before any custom or generic
renderer runs. Matching completely enumerates the top-level map keys and calls
the activated Malli validator. A recursive plan schema then validates every
root, child, and nested map before the bounded renderer receives control.

Malli 0.20.0, mirrored at revision
`80138076960e7820523b4cb932c5b5d1936d4e7f`, reduces across every vector child
and recursively validates nested maps. A million-root or million-child value
therefore performs work proportional to the hostile input, while a deeply
nested unary chain remains unbounded and risks stack exhaustion. Vector
`:max` properties do not bound depth, constructor caps do not protect literal
eval results, and shallow validation would invoke custom code on malformed
nested values.

## Expected owner

`src/seon/render.cljs` owns the one custom property-selection boundary.
`src/seon/render/value.cljc` already owns bounded sampling and the distinction
between complete values, which may use full matching and explanations, and
partial values, which receive bounded shape-only candidates. Strengthen that
one boundary rather than adding a plan-specific matcher or a second sampler.

## Acceptance

- Custom schema-property rendering and explicit per-value overrides run only
  after bounded sampling proves the whole original value complete.
- One-million-root and one-million-child values stay within the configured
  visit budget and never invoke custom code.
- A deeply nested unary chain stops at the configured depth without stack
  failure or custom invocation.
- A small valid recursive value validates and dispatches; a small malformed
  value does not invoke custom code and reports the bounded invalid result.
- Incomplete values use the universal bounded data projection with honest
  shape-only status and deterministic bytes.
- Instrumented tests assert work performed, not merely returned output size.

## Dependency evidence

- `src/seon/render.cljs` — `block` and `custom-render-selection`.
- `src/seon/schema.cljc` — complete key enumeration and
  `matching-shapes-in`.
- `src/seon/render/value.cljc` — bounded sampling and
  `schema-projection-in` completeness handling.
- `reference-code/malli/src/malli/core.cljc` at the mirrored revision above —
  map and collection validator traversal.

## Resolution

Commits `9887ab64` and `82349b58` make the one generic dispatch boundary
prepare one bounded value projection before decoding explicit overrides or
running schema matching. Only sampler-proven complete values may validate and
invoke custom code; incomplete values reuse the exact sampled tree in the
universal fallback with shape-only status.

Independent review accepted the implementation with no findings. The focused
dispatch proof passed 6 tests / 37 assertions and the value renderer suite
passed 69 tests / 444 assertions. A raw Malli control traversed all 200
recursive children, while million-root and million-child inputs each visited
at most 40 items, sampled exactly once, and invoked neither matching nor custom
code. A 10,000-deep chain stopped with an honest pruned marker.
