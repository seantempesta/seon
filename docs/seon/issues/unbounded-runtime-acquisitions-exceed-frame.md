---
type: issue
status: open
tags: [agent, database, issue]
severity: friction
---

# Unbounded runtime acquisitions exceed the negotiated frame

## Evidence

The 2026-07-22 64 KiB live checkpoint exposed three consumers that still
assume a multi-megabyte database response:

- execution-child program/config preparation returned `v must satisfy
  IVector` before any eval because it called `subvec` on the absent results of
  a `frame-too-large` response;
- the namespaces block discarded the same top-level error and rendered
  `Namespace selected member failed` with nil data; and
- the warnings block discarded the error and rendered
  `Warning acquisition failed. nil`.

The exact frozen responses are hundreds of kilobytes: about 422,059
characters for program/config, 683,063 characters for selected namespace
rows, and 550,399 Transit bytes for the first warnings acquisition. All three
succeed after restoring the 4 MiB writer, which masks rather than fixes the
defect.

Detailed evidence lives in [[live-turn-frame-defect-2026-07-22]],
[[live-namespaces-render-defect-2026-07-22]], and
[[live-warnings-render-defect-2026-07-22]].

## Expected owner

The q22 convergence boundary in the SCI execution-runtime program: reuse one
bounded `index-page` plus `pull-many` acquisition recipe across the existing
execution, namespaces-context, and warnings-context owners. Preserve one
frozen database value and each consumer's current final data shape.

Every consumer must also preserve the complete top-level database error before
reading member results.

## Acceptance

- Multi-page acquisitions equal the current successful 4 MiB result shapes.
- Every page uses the same immutable database value and stays below the
  supported 64 KiB floor.
- Top-level frame failures remain legible and never become nil or a secondary
  collection exception.
- A fresh 64 KiB `/agents/run` drive has evals greater than zero, no context
  render failures, and no turn error.

## Triage — 2026-07-23

DISSOLVES into P4 loop migration plus the cutover/U12 acceptance: the named
execution-child acquisition is deleted, and resumable database steps and
surviving context consumers must pass the bounded restart drive.

## Fresh initialization evidence — 2026-07-23

The schema-admission fresh-cluster proof exposed a fourth unbounded consumer:
the pod sends the complete compiled program in one `ensure-database`
initialization request. After precommit core-schema validation was corrected,
fresh clusters `schemagate-fix2c` and `schemagate-fix2d` both failed before
pod readiness with `:seon.db.protocol.error/frame-too-large` at the fixed
4 MiB ceiling. Both used an absent cluster path and the committed default
artifact; reducing a few bytes in the admission walker did not change the
result.

The initialization path needs the same frame-safe, basis-coherent principle as
the read consumers, but its write-side protocol must be designed by the
database initialization/protocol owner. Raising the hard ceiling or deleting
program facts is not an admissible fix.

## Paged initialization resolution — 2026-07-23

Fresh initialization now uses ordered ordinary ensure requests at protocol
version 14. Page zero contains the fixed bootstrap schema closure; later schema
rows are dependency-ordered and row-paged, followed by bounded attribute,
program, and initial-data pages plus one completion page. The fixed transport
ceiling remains 4 MiB.

Every page has a deterministic durable transaction receipt and content
fingerprint. The database initialization singleton stays `in-progress` across
process death, blocks external bare ensure and acquire, and becomes `complete`
only after every predecessor receipt and bounded stale-program cleanup.

Evidence:

- current compiled corpus: 6,934 program rows;
- 10× synthetic corpus: 69,340 rows across 1,092 pages;
- largest measured Transit request: 337,203 bytes against 4,194,304 bytes;
- writer large-page/N-page population parity: exact;
- file-backed writer death/restart: partial acquire rejected before and after
  death, then deterministic page replay completed with one receipt per page;
- fresh `initpage` reset: no `frame-too-large` response and initialization
  acquire completed.

Pod readiness is currently blocked after initialization by the independent
public core-predicate acquisition defect recorded in
[[paged-initialization-misses-public-core-predicate-binding]], not by database
framing or seed state.
