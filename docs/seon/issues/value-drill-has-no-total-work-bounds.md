---
type: issue
status: open
tags: [web, rendering, issue]
severity: blocker
---

# Value drill has no total work bounds

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
