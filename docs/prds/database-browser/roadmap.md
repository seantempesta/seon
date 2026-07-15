---
type: prd
status: planned
tags: [prd, database, web]
---

# Database browser roadmap

## Outcome

`/data` makes entities, references, transactions, provenance, and bitemporal
history navigable directly from bounded Datahike index cursors while closed
details do no expensive work.

## Current state

The existing page has a cheap shell, the canonical normalized gzip feed,
installed-schema navigation, bounded AEVT attribute pages, URL-owned view
state, and exact read-result replay. It has no entity/ref/transaction/
provenance/history navigation yet, and its whole browser remains one render
unit. Cursor Slice B has replaced the raw tuple URL contract: continuation
links and feed keys carry the same opaque token, cursor requests first resolve
their sealed coordinate through `seon.db/at-coordinate`, and retained pages
open as frozen non-live feeds while malformed or unavailable points render a
typed error without an index read.

The complete `{database-id, branch, commit-id, t}` coordinate, retained-commit
resolver, pure shared render-unit lifecycle, and cursor Slice A have now
landed. `seon.db.browser/index-page` is the one bounded EAVT/AEVT/AVET current
or history mechanism. Its size-bounded Transit/base64url continuation seals
the full coordinate, projection, index, normalized prefix, direction, and
five-component last datom; malformed, moving, or mismatched continuations fail
as data before an index read. No retained cursor contains a database value.
The later reactive unit migration still waits on the shared reverse-candidate
boundary, currently blocked on a public Datahike query dependency projection.

A read-only 2026-07-14 default-cluster probe observed basis `536870929` and
15,851 datoms. The initial compressed feed emitted immediately. Slice B now
classifies selected Datahike's `:dh.ref` implicit schema as system data and
captures one immutable database value for `/data` first paint; the debug
feed's separate foreign-database replay defect remains open.
[[research/database-browser-source-audit-2026-07-14]] grounds the exact index,
seek, history, count, coordinate, cursor, unit, deletion, and acceptance
constraints. Detailed implementation waits on the canonical lifecycle
coordinate and settled render-unit contract.

The focused Slice A checkpoint passed four tests and 52 assertions with zero
failures or errors. It covers forward and reverse EAVT/AEVT/AVET paging,
current and assertion/retraction history positions, malformed/mismatched/
moving-coordinate failures, immutable continuation replay, and typed double,
UUID, instant, native BigInt, and bytes boundaries. The checkpoint also found
that one cardinality-many Datahike CLJS attribute collapses two distinct native
BigInts above `Number.MAX_SAFE_INTEGER`; that dependency defect is preserved in
[[datahike-cljs-cardinality-many-collapses-large-bigints]].

The focused Slice B checkpoint passed ten tests and 72 assertions with zero
final failures or errors. The database selectors prove grouping, all current
and history index orders, coordinate mismatch rejection, scalar boundaries,
and reactive replay. The web selectors prove an exact opaque feed key, one
replayable first-paint snapshot, retained-coordinate resolution before a
frozen feed opens, and malformed-token rendering without database resolution.
Retained logs are `tmp/test-cljs-data-browser-slice-b-20260715.log` for the
first eight selectors, `tmp/test-cljs-20260715-024811-61004.log` for the
corrected retained-point fixture, and
`tmp/test-cljs-20260715-024825-61613.log` for malformed-token isolation. The
checkpoint exposed separate runner defects recorded in
[[test-cljs-multiple-exact-selectors-run-zero-tests]] and
[[changed-test-interruption-orphans-test-runner]].

## Research evidence

- [[research/database-browser-source-audit-2026-07-14]] — selected dependency
  ledger, live baseline, exact current gaps, coordinate/cursor constraints,
  deletion map, ordered slices, and acceptance matrix.
- [[research/coordinate-bound-cursor-contract-2026-07-15]] — settled coordinate
  reconciliation, Transit cursor envelope, Datahike current/history seek edge,
  narrow two-slice ownership, deletion boundary, and falsifiable tests.

## Ordered work

1. **Coordinate and pure lifecycle prerequisites complete:** consume their
   settled contracts. The later reverse candidate index is separately blocked
   on a public Datahike query-dependency projection. Mirror exact
   persistent-sorted-set `0.4.137` source only before count implementation.
2. **Cursor Slice A and web Slice B complete:** one database value now flows
   through `/data`, dependency system attrs are classified correctly, and the
   web adapter consumes the opaque coordinate-bound cursor without a second
   parser or mutable retained database value.
3. Add bounded EAVT entity/outbound-ref and AVET reverse-ref units, with raw
   rendering only for the already selected bounded value.
4. Add backward transaction navigation, exact transaction metadata,
   user/process provenance, and a closed capped effective-datom unit.
5. Add five-component assertion/retraction history and frozen as-of links;
   expose valid time only through a fully specified `seon.db` boundary.
6. Add a public Datahike slice-count only where wrapper-correct, counted
   semantics are proven; counts never gate pagination.
7. Prove fresh/grown/current/history budgets, focused behavior, server-side
   gzip SSE, real-browser navigation, and final-close cleanup; delete the
   superseded whole-browser/cursor/classifier paths after parity.

## Graduation

- Every list is index-cursor bounded and deterministic; no offset Datalog or
  whole-database materialization serves the page.
- Closed details issue no detail query, pull, history, render, or serialization.
- Entity refs, reverse refs, transactions, provenance, as-of, and history links
  round-trip through stable URLs and canonical database coordinates.
- Relevant writes morph only affected open units; unrelated writes do no work.
- Fresh, large, and historical databases meet explicit latency and memory
  budgets with browser and server-side feed evidence.
- Per-attribute or historical counts are shown only when maintained-index,
  wrapper-correct behavior is proven; an unavailable count never causes a
  scan.
