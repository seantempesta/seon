---
type: issue
status: resolved
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

## Current state

Datahike commit `0070d507` implements the one cache/single-flight mechanism for
one, two, three, or more parsed database sources. It retains one-source key
compatibility, tags ordered multi-source identities by source symbol and
top-level argument position, removes every native database from the generic
argument key, requires every contributing generation at publication, advances
only the changed member during safe propagation, and lets release of any member
evict the completed bucket and detach the flight.

The exact four new behaviors pass 12 tests/66 assertions across PSS, HHT, and
specification configurations. The complete query-cache namespace passes
114/822; related single-flight, API specification, and capability tests pass
54/435. The issue remains open for the retained four-source case, full
dependency checkpoint, and measured one-source hit/allocation comparison.

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

## Resolution

Datahike commits `0070d507` and `caf52685` strengthen the one existing query
result cache and single-flight identity. Parsed source bindings now determine
an ordered composite of every committed database identity, while generic
arguments contain no database values. Publication is fenced by every member
generation, and closing any member evicts the completed bucket and detaches
its flight.

The finishing commit also caches the parsed query's source-binding derivation
and input count, keeps the legacy one-source argument shape, and touches an
exact LRU hit without replacing or reweighing unchanged cache content. The
cache's private get/put boundary accepts normalized identities only; it no
longer probes an ordinary identity vector with the native-database protocol.

Proof retained in `tmp/orchestrator/querycache-gate.log`:

- Four independent databases produce `#{["A" "B" "C" "D"]}`, with one miss,
  one exact hit, one composite key, no native database anywhere in retained
  cache entries, and zero snapshots after closing the second member's
  generation.
- The post-format focused checkpoint passes 210 tests / 1,347 assertions
  across persistent-set, hitchhiker-tree, and specification configurations.
- The complete current Clojure, specification, and Kabel matrices pass
  1,779 / 9,332, 874 / 4,609, and 16 / 81 respectively. The final historical
  compatibility fixture is independently tracked because latest released
  Datahike writes Konserve `0.9.363`, newer than this maintained fork's
  `0.9.359-seon.1`.
- A 7 × 200,000-hit committed one-source benchmark retains the historical
  `(rest args)` key shape. The patched samples allocate about 4,504 bytes/hit
  versus about 4,760 bytes/hit at the pre-multi-source parent, with both in the
  same low-microsecond latency band on this JVM.
