---
type: issue
status: resolved
severity: blocker
tags: [issue, architecture, database, agent, flow]
---

# Fence runtime membership against the latest database value

## Problem

The runtime advertisement could accept resumable agent IDs derived from an
older database value after the `seon.db` session had already cached a newer
value. The accepted vector drives synchronous runtime discovery and startup
hosting, so stale acceptance could omit or retain an agent until another
membership transaction happened to refresh it.

## Evidence

- The prior `src/seon/client.cljs:340-364` accepted a completed membership
  query when its database object was `identical?` to client-owned
  `::advertisement-db`.
- `src/seon/db.cljs:268-280` caches every received `:db-after` before invoking
  an addressed interest handler. The session cache can therefore advance
  independently of a selective membership refresh.
- `src/seon/db/writer.clj:2226-2326` sends a selective interest report only
  when committed datoms match the interest's attributes. An unrelated
  transaction can advance the session database value without starting another
  membership query.
- The failing order was a deferred membership query at T1, an unrelated
  session advance to T2, then completion of T1. The client still saw T1 in its
  copied state and accepted the stale vector.
- The prior client also copied the membership attributes separately from the
  query, although `src/seon/db/writer.clj:2123-2136` and maintained Datahike
  already derive selective dependencies from the query form.

The complete source-grounded cut is
[[../../../prds/database-authority-mesh/research/membership-quiescence-single-owner-audit-2026-07-17]].

## Owner

The existing runtime advertisement occurrence in `seon.client`, backed by the
one `seon.db` session value and the one resumable-agent query in `seon.derive`.
No sequence counter, coordinate cache, membership registry, copied attribute
list, or compatibility function is retained.

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

## Resolution

Resolved by `77789812`. `seon.derive` now owns one shared resumable-agent query,
`seon.agent/resumable-agent-ids!` accepts an optional explicit database value,
and `seon.client` accepts an asynchronous result only when its database value
equals the session's cached latest value while the same listener owner remains
current. The client-owned database cache and copied datom patterns were
deleted.

Focused proof passed under one coordinated source freeze:

- `seon.agent.multiagent-test`: 4 tests, 25 assertions
  (`tmp/test-cljs-20260717-033952-85289.log`)
- `seon.client-advertisement-test`: 5 tests, 23 assertions
  (`tmp/test-cljs-20260717-034358-340.log`)
- `seon.client-initialization-test`: 7 tests, 20 assertions
  (`tmp/test-cljs-20260717-034414-2196.log`)
