---
type: issue
status: open
severity: blocker
milestone: M3
tags: [issue, schema]
---
# Graph Missing Generalized Discovery API

## Problem

The code graph already stores function schemas: `:seon.fn/input-spec` and `:seon.fn/output-spec` as refs to `:seon.spec/*` entities with `contains-keys` and `optional-keys`. Output-key discovery works via `gq/functions-with-output-key` (used in production for renderer resolution).

What is missing is the generalized discovery API: `gq/functions-with-input-key` and a unified `gq/discover` that accepts arbitrary input/output key combinations. Without this, schema-based discovery beyond rendering is not possible.

## Where

- `src/seon/graph/query.clj` -- only has `functions-with-output-key`, needs `functions-with-input-key` and `discover`
- `src/seon/graph/ingest.clj` -- schema storage already works (input-spec, output-spec refs)

## Acceptance Criteria

- `gq/functions-with-input-key` finds functions whose input spec contains a given key
- `gq/discover` accepts both input-keys and output-key, returns ranked matches
- Ranking uses the same specificity algorithm as renderer discovery
- Tests cover the new query functions

## Related

- [[components/code-graph]]

## Status (2026-06-28 audit): valid but JVM-track is paused — defer until that track resumes.
