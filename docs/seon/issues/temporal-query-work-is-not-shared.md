---
type: issue
status: open
severity: friction
tags: [issue, database, flow]
---

# Share exact temporal query work in Datahike

## Problem

Queries over an exact earlier transaction share the containing Datahike value's
immutable primary indexes, but they bypass both completed-result caching and
single-flight. Several agents asking the same query at the same coordinate
therefore repeat query computation even though the full coordinate is already
an exact immutable identity.

## Evidence

- `datahike.db/AsOfDB` deliberately has no committed cache context.
- `datahike.query/acquire-direct!` is selected for temporal wrappers, so an
  identical historical query never joins the existing Datahike flight.
- [[../../prds/database-authority-mesh/research/strict-temporal-coordinate-seam-2026-07-16]]
  proves that Seon resolves one containing commit and exact `t`; no second
  coordinate or Seon cache is required.

## Owner

Datahike's existing committed query cache and single-flight identity. The
temporal extension should use the existing connection, generation, commit, and
transaction facts rather than adding a Seon-side cache or another name for a
database point.

## Acceptance

- Two and 32 identical queries at one earlier `t` compute once and return the
  same cache/resource evidence semantics as an immutable raw value.
- Different `t`, commit, connection generation, query, or arguments never
  share work.
- Final release, cancellation, reconnect, cache clear, failure, and retry leave
  no retained temporal entry or flight.
- Current raw-value query latency and allocation do not regress.
- A benchmark demonstrates that the added identity/bookkeeping wins for the
  expected historical-query workload before the optimization graduates.
