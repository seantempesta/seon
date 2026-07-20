---
type: issue
status: open
severity: blocker
tags: [issue, web, architecture]
---

# Data browser map sampler walks unbounded input

## Problem

`seon.render.value/sample*` retains at most `max-keys` map entries, but it
recursively samples every entry before ranking and truncating them. The result
is output-bounded while the work remains proportional to the complete input
map. Routing `/data`, generic fallback values, and every eval card through this
path would therefore expose the client or execution child to unbounded work
behind an API that claims bounded sampling.

## Evidence

The readiness audit in
`docs/prds/source-cleanup/research/data-browser-readiness-audit-2026-07-20.md`
identifies the map branch at `src/seon/render/value.cljs:366-382`. The shortest
falsifier is a large map whose values increment a counter when sampled: the
counter follows the map cardinality instead of an explicit traversal budget.
The already-recorded `opaque-marker` full-print defect is a second bounded-walk
prerequisite, but it has a distinct printing mechanism.

## Expected owner

`seon.render.value` owns one total sampler. Strengthen that mechanism before
schema-aware rendering, `/data`, or eval-card migration; do not add a second
browser or post-hoc output cap.

## Acceptance

- Map traversal is bounded by explicit work and output budgets before child
  values are recursively sampled.
- Stable paths, deterministic retained entries, and honest elision survive the
  early bound.
- Counter-bearing and poisoned values beyond the budget are never touched.
- The existing opaque summaries become non-materializing in the same Unit 0.
- Focused tests and live large-value paging prove the bound before Stage 1.5
  sends additional consumers through the sampler.
