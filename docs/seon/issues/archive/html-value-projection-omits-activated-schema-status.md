---
type: issue
status: resolved
severity: blocker
tags: [issue, schema, rendering]
---

# Add activated schema status to the HTML value projection

## Problem

`seon.render.value/render-html-data` returns the bounded structural skeleton,
but it does not derive schema labels, validation status, or invalid-value
explanation data from the activated schema projection. Every downstream value
browser would otherwise need to repeat matching or expose a schema-blind tree.

## Evidence

- `src/seon/render/value.cljs` currently returns only `eval-id`, `summary`,
  `truncated?`, and `tree` from `render-html-data`.
- Unit 1A commit `284cbabf` provides activated-only `candidate-shapes`,
  `matching-shapes`, and `explain-shape`, including a 32-visit diagnostic cap.
- [[../../prds/source-cleanup/research/schema-aware-value-projection-boundary-2026-07-20]]
  requires one sample pass, strict completeness, ordered status rows, and an
  invalid-only explanation without a second raw-value walk.

## Owner

`seon.render.value/render-html-data` owns the one plain-data projection. It
consumes the activated `seon.schema` APIs and Malli's explanation projections;
no renderer-local registry, cache, database read, or UI mechanism belongs here.

## Resolution

Commit `cac9e660` extends the existing `render-html-data` projection in place.
It samples once, reuses that identical skeleton and strict completeness fact,
and consumes only activated `candidate-shapes`, `matching-shapes`, and
`explain-shape`. Complete matches emit ordered `:valid` rows; a complete
near-match emits one primary `:invalid` row with Malli `humanize` and
`error-value` data; incomplete evidence emits only `:shape-only` rows.

The focused `seon.render.value-test` gate passed 44 tests / 176 assertions.
Its logical million-entry map bounded the sampler to 33 entry enumerations and
the independent schema-input pass to 32, left the poisoned value beyond the
window unrealized, visited exactly 32 indexed schema references, and retained
an exact omitted-key count. Seven independent marker probes made validation
and explanation counters remain zero. Existing opaque 100 MiB logical-printer
and ordinary huge-string capped-writer falsifiers passed in the same gate.

## Acceptance

- One sample pass returns the identical skeleton and preserves existing fields.
- Complete values emit every ordered valid match, or one primary invalid
  candidate with humanized and error-value explanation data.
- Incomplete values emit only ordered `:shape-only` candidates and never
  validate or explain.
- Candidate visits stay at or below 32 and million-entry traversal, poison-tail,
  opaque-printer, and capped-writer tests assert work rather than output size.
- Repeated renders are deterministic and every omission marker remains honest.
