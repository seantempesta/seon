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
unit. The exposed `[entity value tx]` cursor is neither opaque nor tied to a
database coordinate.

The complete `{database-id, branch, commit-id, t}` coordinate, retained-commit
resolver, and pure shared render-unit lifecycle have now landed. Cursor
hardening can proceed without the reverse candidate index or count API. A
continuation freezes the exact point selected by its first page; stale or
mismatched cursors fail as data before an index read. The later reactive unit
migration still waits on the shared reverse-candidate boundary, currently
blocked on a public Datahike query dependency projection.

A read-only 2026-07-14 default-cluster probe observed basis `536870929` and
15,851 datoms. The initial compressed feed emitted immediately, but the
default domain navigator incorrectly included selected Datahike's `:dh.ref`
implicit system schema. Initial read capture also dereferences the replica
twice, sharing the foreign-database replay defect already found in debug.
[[research/database-browser-source-audit-2026-07-14]] grounds the exact index,
seek, history, count, coordinate, cursor, unit, deletion, and acceptance
constraints. Detailed implementation waits on the canonical lifecycle
coordinate and settled render-unit contract.

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
2. Harden the current navigator: thread one db value, classify dependency
   system attrs correctly, and replace the raw tuple with a bounded versioned
   cursor tied to coordinate/index/prefix/direction.
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
