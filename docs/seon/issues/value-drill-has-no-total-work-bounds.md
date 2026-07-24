---
type: issue
status: open
tags: [web, rendering, issue]
severity: blocker
---

# Value drill has no total work bounds

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — web slice 2.** The config half is landed; route/renderer
effective limits and zero-work proof belong to the JVM value-browser cut.

## Problem

The planned value route needs bounded paths and paging, but configuration has
no maximum path length or total realization budget. Existing depth and page
size dials are not substitutes: reusing depth makes legitimate repeated drills
stop arbitrarily, while reusing page size makes every nonzero offset invalid.

## Evidence

The maintained value configuration bounds depth, keys, items, string size, and
shape candidates. It does not bound path elements, encoded path bytes, offset,
or `offset + page-size`. A child asked for a large offset could therefore walk
unbounded input before retaining one bounded page.

Unit 1D now supplies the configuration half of the boundary: the existing
render-policy section and flat singleton carry independent positive caps for
32 decoded segments, 4,096 raw encoded bytes, and 1,024 total realized items.
The focused config proof covers closed-map rejection, Datahike attribute
derivation, absent/shipped fallback parity, and independent manifest
overrides. The issue remains open because the renderer-owned public limit
schemas, shared effective-limit normalizer, and instrumented parent/child
zero-work proofs are later dependency-ordered units.

## Owner

The value-browser configuration contract owns path length, encoded request
size, and total realization limits. Parent route parsing and child sampling
must independently enforce the same resolved maxima.

## Acceptance

- Config registers and documents separate maximum path segments, encoded path
  bytes, and total realized items.
- Parent and child reject an over-limit request before lookup or realization.
- Every page touches at most `offset + page-size + 1` items and requires
  `offset + page-size` within the total budget.
- Explicit values override defaults without weakening hard maxima.
- Focused config, sampler, protocol, and route tests prove the bounds.

## Triage — 2026-07-23

DISSOLVES into the post-cutover U10 value-drill graduation unit, which owns the
remaining renderer/route effective-limit and zero-work proofs.
