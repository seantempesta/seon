---
type: issue
status: open
severity: blocker
tags: [issue, render, context, performance]
---

# Explain the 24-second live root pull of 189 members

## Problem

The keystone lane measured 24.2 seconds for a live-path root pull containing
189 members. That observation is distinct from the resolved recursive
selector-parse allocation collapse: the integration gate showed the former
stall/OOM class absent, but did not time this exact live request.

## Evidence

The 2026-08-12 `bin/test --all` attempt at `12d9dcee8` loaded all 124 test
namespaces and advanced 1,176 of 1,178 selected rows without a recursive pull
stall or OOM. `cold-root-pull-records-an-informational-latency-sample`
completed in 42 ms and the root-pull suite returned. The gate nevertheless
cannot explain or supersede the keystone measurement because its recorded
sample did not contain 189 members and did not surround the same live path.

The prior defect and its compiled-plan evidence remain archived at
[[cold-root-pull-is-slower-than-the-four-query-floor]]. This note owns only
the unexplained 24.2-second, 189-member observation.

## Owner

Render acquisition performance for the exact live root path, including the
consumer work after the compiled database pull.

## Acceptance

- Reproduce the 189-member request with the same live entry point and record
  the immutable database value and render profile used.
- Time root plan acquisition, database pull, membership indexing, render
  function calls, admission/printing, and package construction separately.
- Name the active owner from those measurements; do not infer that the
  resolved selector parser is responsible.
- The exact live path completes below its declared interactive latency bound
  without an elapsed-time correctness verdict or a second acquisition path.
