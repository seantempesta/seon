---
type: issue
status: open
severity: blocker
tags: [issue, database, flow]
---

# Key multi-source query sharing by every database value

## Problem

The maintained Datahike query result cache and identical-query single-flight
derive their owning cache scope from only the first query database. Any later
database source remains inside the generic argument key as a native `DB`
record. A cached or in-flight multi-source query can therefore retain another
connection's immutable indexes after that connection generation is released,
and releasing that later database cannot address the entry or flight.

This also makes cache-key equality and hashing depend on an opaque native
database record instead of Datahike's exact committed-value identity. Seon's
authority cannot preserve Datahike's supported `:in $a $b` behavior safely by
merely rehydrating database descriptors into the existing query arguments.

## Evidence

- `reference-code/datahike/src/datahike/query.cljc:4244-4267` obtains one
  `db-cache-key` from `(first args)` and places `(rest args)`, including any
  additional database values, directly into `cache-key`.
- `reference-code/datahike/src/datahike/query.cljc:4297-4309` uses only that
  first database scope for single-flight and completed-result publication.
- `reference-code/datahike/src/datahike/query.cljc:2524-2555` releases entries
  and flights only when their owning connection/generation appears in the
  outer database key.
- Maintained tests prove that multiple database sources are public behavior:
  `query_planner_test.clj:1037-1052` joins two independent databases, and
  `api_test.cljc:387-394` joins a current database with its `since` value.

## Owner

Datahike's existing query result cache and single-flight identity. Normalize
all database inputs to their exact cache identities inside that one mechanism;
do not add a Seon cache, a transport cache, or a second multi-source query
path.

## Acceptance

- Queries with one, two, and four database inputs preserve Datahike result
  semantics and compute once for identical exact input values.
- The cache and single-flight key contains exact identities for every database
  input and no native `DB`, temporal wrapper, connection, index, or store.
- Changing any input database value prevents sharing with the old result.
- Releasing any participating connection generation fences publication,
  detaches its active callers, and evicts every completed entry that references
  that generation without disturbing unrelated queries.
- Current plus `as-of`, `since`, and history combinations are either keyed by
  their complete supported identities or remain explicitly uncacheable.
- Failure, cancellation, clear, reconnect, and release leave zero retained
  multi-source database values or flights.
- Single-database query hit latency and allocation do not regress materially.
