---
type: issue
status: open
severity: blocker
tags: [issue, architecture, database, agent, flow]
---

# Fence runtime membership against the latest database value

## Problem

The runtime advertisement can accept resumable agent IDs derived from an older
database value after the `seon.db` session has already cached a newer value.
The accepted vector drives synchronous runtime discovery and startup hosting,
so stale acceptance can omit or retain an agent until another membership
transaction happens to refresh it.

## Evidence

- `src/seon/client.cljs:340-364` accepts a completed membership query when its
  database object is `identical?` to client-owned `::advertisement-db`.
- `src/seon/db.cljs:268-280` caches every received `:db-after` before invoking
  an addressed interest handler. The session cache can therefore advance
  independently of `::advertisement-db`.
- `src/seon/db/writer.clj:2226-2326` sends a selective interest report only
  when committed datoms match the interest's attributes. An unrelated
  transaction can advance the session database value without starting another
  membership query.
- A concrete failing order is a deferred membership query at T1, an unrelated
  session advance to T2, then completion of T1. The client still sees T1 in
  `::advertisement-db` and accepts the stale vector.
- `src/seon/client.cljs:366-390` separately copies the membership attributes,
  while `src/seon/db/writer.clj:2123-2136` and maintained Datahike already
  derive selective dependencies from the query form. This duplication makes
  the stale-result surface easier to drift.

The complete source-grounded cut is
[[../../prds/database-authority-mesh/research/membership-quiescence-single-owner-audit-2026-07-17]].

## Owner

The existing runtime advertisement occurrence in `seon.client`, backed by the
one `seon.db` session value and the one resumable-agent query in `seon.derive`.
Do not add a sequence counter, coordinate cache, membership registry, copied
attribute list, or compatibility function.

## Acceptance

- A deferred T1 query cannot change the accepted IDs after `db/db` returns T2,
  even when the T2 transaction changed no membership attribute.
- Reverse T1/T2 completion accepts only the vector derived from the session's
  latest database value.
- Birth, termination, and reconnect resynchronization converge through one
  scalar keyed listener.
- The listener and projection use one query definition; no second membership
  attribute list remains.
- Explicit database input to the resumable-ID facade is used directly, while
  omission acquires the current database value once.
