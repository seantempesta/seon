---
type: issue
status: open
severity: blocker
tags: [issue, render, performance, wave/render-acquisition-performance]
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

## Evidence — 2026-08-13 live-pull attribution

The isolated HEAD probe in
[the dated attribution report](../../prds/context-generation/research/live-pull-attribution-2026-08-13.md)
returned 198 acquisition members from a 78,974,355-byte database in
4,465–5,251 ms. It did not reproduce 24.2 seconds, but 4,228–5,006 ms of the
wall time was inside Datahike `pull-spec`; selector-plan generation was only
40 ms. The historical 1.22 GiB database was approximately 15.5 times larger
than this probe database, so the result supports content/graph-size scaling
without asserting a linear law.

Bounded counters attribute one 198-member acquisition to 1,775,664
`datahike.pull-api/pull-pattern-frame` calls and 1,770,912 `pull-attr` calls.
The specific inner owner is Datahike's per-entity execution of the schema-wide
recursive selector supplied by `seon.render.walk/root-acquisition`, not
selector parsing or downstream rendering. The issue remains open because one
HEAD acquisition is still seconds rather than interactive and the historical
24.2-second condition was not reproduced exactly.

## Evidence — 2026-08-13 the live path also pays a render-side storm

[The dated after-help diagnosis](../../prds/context-generation/research/live-pull-after-help-diagnosis-2026-08-13.md)
measured the same acquisition inside its real live consumer. Datahike
`pull-spec` cost 4.3–5.4 seconds whether or not the consumer carried a render
profile, confirming this note's attribution — but the surrounding live
derivation cost 276 seconds, because `seon.render/request-profile`
(`src/seon/render.clj:63-81`) re-derives the render profile through
`seon.config/effective`, which rebuilds the whole Malli schema projection per
call (`src/seon/config.clj:530-534`).

Any future timing of "the exact live path" required by this note's acceptance
must record whether the request carried `:seon.render/profile`; without that
condition the measurement is dominated by the projection rebuild rather than
by the pull this note owns.
