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

The existing page has one database route, a gzip feed, index-oriented shells,
and partial lazy detail. Its final coordinate, cursor/count API, transaction
history navigation, provenance presentation, reactive-unit ownership, and
grown-database budgets are not yet graduated. Detailed implementation waits on
the canonical lifecycle coordinate and the settled render-unit contract.

## Ordered work

1. Ground exact Datahike index, count, seek, history, and transaction behavior
   plus current Seon protocol/replica ownership.
2. Define stable entity, attribute, transaction, and temporal coordinates over
   `{database-id, branch, commit-id, t}`.
3. Implement bounded cursor pages and pay-for-open entity/ref/transaction/
   provenance/history details through the one render-unit engine.
4. Delete broad scans, eager details, duplicate feed logic, and page-specific
   cache/transition paths after parity.
5. Prove correctness and budgets on fresh and grown databases in the REPL,
   focused tests, server-side gzip SSE, and a real browser.

## Graduation

- Every list is index-cursor bounded and deterministic; no offset Datalog or
  whole-database materialization serves the page.
- Closed details issue no detail query, pull, history, render, or serialization.
- Entity refs, reverse refs, transactions, provenance, as-of, and history links
  round-trip through stable URLs and canonical database coordinates.
- Relevant writes morph only affected open units; unrelated writes do no work.
- Fresh, large, and historical databases meet explicit latency and memory
  budgets with browser and server-side feed evidence.
