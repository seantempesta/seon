---
type: issue
status: open
severity: blocker
tags: [issue, render, context, performance]
---

# Cold root pull is slower than the four-query floor

## Problem

The W2 schema-derived root acquisition enters `seon.db` exactly once, but the
isolated cold pull takes 1,795.387292 ms in the acceptance fixture. The prior
four-query acquisition floor is 46.0 ms, so the replacement is 39.0301585×
slower on cold acquisition even though unchanged acquisition is read-free.

## Evidence

The clock started after fixture and SCI request construction and surrounded
only `seon.render.walk/root-acquisition`. The result contained one member. The
door counter independently proved the operation sequence was exactly
`[:pull]`; projection acquisition issued no hidden query.

Full measurement context is recorded in
[W2 change-flow acceptance evidence](../../prds/sci-execution-runtime/research/w2-change-flow-acceptance-2026-08-12.md).

## Acceptance

- Profile selector construction, Datahike pull execution, and result indexing
  separately on the seeded cluster.
- Preserve the concrete forward/reverse selector, one-read membership oracle,
  component evidence, and exact invalidation semantics.
- Record a cold root-only sample at or below the 46 ms four-query floor without
  using elapsed time as a correctness verdict.

## Owner

Render acquisition performance after W2.

## 2026-08-12 post-ambient-resolution measurement

The hypothesis that W2's 1,795.387292 ms cold pull was dominated by ambient
schema resolution is falsified. A fresh JVM and fresh in-memory branch used the
recorded W2 fixture, constructed the request before the clock, and timed only
`seon.render.walk/root-acquisition`:

`{:seon.render.walk/cold-pull-ms 1673.052083,
  :seon.render.walk/member-count 1,
  :seon.render.walk/four-query-floor-ms 46.0}`

That is 122.335209 ms (6.8%) faster than the recorded before value, but still
36.37× the four-query floor. The ambient conversion did not collapse this
cost; the residue is real and this issue remains open for its own profile.
