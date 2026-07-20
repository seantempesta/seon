---
type: issue
status: resolved
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

The implementation grounding pass found two coupled escape hatches that must
close in the same safety unit:

- map elision is injected as the reserved
  `:seon.render.value/elided-keys` key inside the sampled user map, so a real
  user key with that name collides with renderer metadata and cannot retain an
  honest navigation path; and
- the datom opaque marker copies `:v` without sampling, allowing one large or
  opaque datom value to bypass the traversal and printing bounds.

The bounded map projection therefore needs a metadata wrapper with a vector of
original `[key sampled-value]` entries, rather than a reserved key injected
into user data. Datom `:v` must pass through the same bounded child-value
projection. These are direct extensions of honest omission and totality, not a
second browser mechanism.

## Expected owner

`seon.render.value` owns one total sampler. Strengthen that mechanism before
schema-aware rendering, `/data`, or eval-card migration; do not add a second
browser or post-hoc output cap.

## Acceptance

- Map traversal is bounded by explicit work and output budgets before child
  values are recursively sampled.
- Stable paths, deterministic retained entries, and honest elision survive the
  early bound.
- A user key equal to any renderer metadata attribute remains an ordinary,
  drillable key; renderer metadata lives outside the user entry collection.
- Datom values cannot bypass the child traversal/string/opaque bounds.
- Counter-bearing and poisoned values beyond the budget are never touched.
- The existing opaque summaries become non-materializing in the same Unit 0.
- Focused tests and live large-value paging prove the bound before Stage 1.5
  sends additional consumers through the sampler.

## Resolution

Resolved on 2026-07-20 by `d42a88de`. Map sampling inspects one explicit
candidate window plus an unsampled tail sentinel, recursively touches only
that window, and reports exact counted-map omission or honest `:more` for an
unknown tail. Elided maps carry a separate entry vector, so every user key is
data rather than renderer metadata. Opaque, collection, and huge scalar keys
become safe labels and force a partial view; no host key crosses into the
ordinary HTML projection. Datom values use the same child sampler.

Opaque records and JavaScript objects are never printed. Ordinary bounded
printing uses a capped CLJS writer with identity sentinel and early
string/keyword/symbol interception. The million-entry counted and uncounted
fixtures measure visits and recursive touches, poison the first value beyond
the work budget, and prove it remains untouched. A logical 100 MiB custom
printer is never invoked. Focused proof passed `seon.render.value-test` at 39
tests / 132 assertions and `seon.render-test` at 6 tests / 25 assertions, both
with zero compiler warnings. Live paging remains the later integrated Stage
1.5 transport gate, not evidence required to establish this local work bound.
