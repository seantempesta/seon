---
type: research
status: complete
tags: [research, prd, database, flow]
---

# Multi-database session lifetime

## Recommendation

Keep one ambient default database acquired for the lifetime of the persistent
Bun session. Acquire every additional named database lazily and idempotently
through the existing `db` function:

```clojure
(db)
(db {:seon.db/database-name "experiment-17"})

```

Both return an ordinary database descriptor. Repeating either call on the same
session returns a fresh head descriptor but does not add another owning
reference. Add Datahike's established `release` function for secondary named
databases:

```clojure
(release experiment-db)

```

`release` removes that session's one acquisition by the descriptor's
`:db-name`; repeating it returns `false`. Closing the Bun session releases all
of its remaining acquisitions, including the default. Do not expose another
public connection value, do not acquire and release around every request, and
do not wait for JavaScript garbage collection or lexical-scope inference.

This is the smallest interface that satisfies all competing requirements:

- `db` remains the established operation that obtains a database value;
- `release` is Datahike's established resource-lifetime name;
- database values remain the ordinary maps already settled by
  [[datomic-client-database-value-seam-2026-07-16]];
- the default remains ambient and cheap for ordinary Seon code;
- multi-source `query` receives several ordinary database values directly;
- unused experiment databases can relinquish indexes, stores, query caches,
  writers, and secondary indexes before the Bun process exits; and
- disconnect remains the unconditional cleanup boundary.

The one deliberate restriction is that public `release` applies only to a
secondary database. The session's ambient default stays acquired until the
session closes. This prevents ordinary omitted-database calls from changing
meaning halfway through a session and eliminates reacquire churn on the hottest
database.

## Dependency ledger

| Owner | Selected source | Lifetime fact |
|---|---|---|
| Maintained Datahike | `reference-code/datahike` at `670cd1ada40462cb5927f0dc687f6b3a95f9e13f` | `connect` shares one connection by identity and counts owners; final `release` drains and closes the generation. |
| Datahike connection registry | `src/datahike/connections.cljc:3-127` | An existing active connection increments `:count`; zero references marks the connection releasing; concurrent release joins one completion. |
| Datahike connector | `src/datahike/connector.cljc:275-424,438-541` | First open connects the store, restores the database and indexes, opens a query-cache generation, and creates the writer. Final release closes cache/report scopes, drains the writer, closes secondary indexes, releases the store, and deletes the connection. |
| Datahike release tests | `test/datahike/test/connector_release_test.clj:31-268` | Concurrent first connects perform one store open and return the identical connection; each caller owns one reference; duplicate/final release behavior and drain ordering are executable. |
| Datahike remote client | `src/datahike/remote.cljc:27-119`, `http/client.clj:152-188` | Remote connections and database values point at one remote peer. API calls are independent HTTP requests, and arguments from different peers are rejected. This does not define persistent multi-database session ownership. |
| Datahike pod | `src/datahike/codegen/pod.clj:91-111,374-400`; `test/datahike/test/pod_test.clj:60-106,350-369` | The pod explicitly caches database values, exposes `release-db`, tracks their parent connection, and tests release. Its optional `with-db` macro is lexical convenience, not a prerequisite for correct cleanup. |
| Seon registry | `src/seon/db/registry.clj:597-830,1970-1992` | A transport session acquires one named database idempotently. Different sessions acquire Datahike references; requests can use only names acquired by that session. |
| Seon writer | `src/seon/db/writer.clj:2155-2163,2392-2452,3128-3189` | Each physical UDS session owns a set of `[database-name attachment]` acquisitions. Disconnect first cancels and awaits its requests, then releases every acquisition. |
| Seon UDS server | `src/seon/db/transport/uds.clj:363-415,740-810,900-981` | A socket owns exactly one writer transport-connection object. Every normal, failed, forced, or server-shutdown close converges on its cleanup callback. |

## Source-grounded cost model

### An already-open database is shared cheaply

Datahike's `reserve-connection-opening!` increments the existing entry's count
when connection identity, acquisition configuration, and physical store key
match. It returns the already-published connection object; the store-open path
does not run. The concurrent-first-connect test instruments
`konserve.store/connect-store` and proves two callers cause exactly one physical
open, receive the identical connection, and leave a count of two.

Seon's registry adds a second useful level of idempotence. The first acquire of
one database by one physical UDS session records that session in the entry.
Repeating it does not call Datahike `connect` or increment Datahike's count.
Therefore repeated `(db {:seon.db/database-name name})` can be a normal head
lookup with no connection or index churn.

### Final release is intentionally expensive

When Datahike's reference count reaches zero, release performs all of these
actions:

1. close the query-cache generation and committed-report scope;
2. stop new writer admission and await accepted writes;
3. close every resource-owning secondary index;
4. release the Konserve store; and
5. delete and mark the connection released.

A later connect must open the store, read the stored database, restore primary
and secondary index state, create a new cache generation, and create a writer.
The exact cost depends on backend and working set, but the source proves the
structural churn. Query-cache reuse across that boundary is zero because the
old generation is closed before writer drain.

This makes acquire-per-request unsuitable. If another session happens to own
the database, each request merely increments and decrements a count under the
connection registry. If it is the last owner, every request performs the full
close-and-reopen path and discards shared query results.

### Session-only ownership retains too much

Keeping every database until socket close maximizes cache reuse but makes
memory proportional to every experiment database ever touched by that Bun
process. Each distinct Datahike connection retains its database roots, store
cache, query-cache generation, writer, committed-report state, and any
secondary index resources. A long-lived controller that briefly inspects many
clusters would prevent dormant database eviction indefinitely.

Explicit secondary release provides the useful middle: hot databases remain
open across requests; a caller can deterministically retire a dormant one.

## Public behavior

### `db`

`(db)` returns the latest value of the session's ambient default. The default
is acquired during session initialization and remains acquired until socket
close.

`(db {:seon.db/database-name name})` does this atomically for a secondary name:

1. if the session does not own `name`, acquire it once;
2. if it already owns `name`, do not increment another reference; and
3. return the latest ordinary database descriptor.

Two calls may return different immutable values after a transaction while the
session still owns only one connection reference. Database-value identity and
connection ownership must not be conflated.

### `release`

`(release database)` uses `:db-name` only to select the session acquisition.
The complete descriptor is still validated as a Seon database value; its old
commit does not need to be the current head. One session owns a named database,
not one connection reference per immutable value.

Return `true` when this call removed the session acquisition and `false` when
the secondary name was already absent. Duplicate release is therefore safe and
observable. Releasing the default returns an ordinary error explaining that it
is session-owned. Closing the session remains the default's release operation.

Before removing an acquisition, stop admitting new requests from that session
whose parsed database source set contains the name, then await or cancel those
already admitted through their existing terminal owner. Only then invoke the
registry release. This is the per-database form of the writer's existing
disconnect order, not a new lifetime mechanism.

If Datahike's final cleanup fails, return the existing cleanup-required error
and retain the registry's failed identity. Never report success or immediately
reopen over unproved cleanup.

### Cross-database query

A query may use descriptors for the default and any number of acquired
secondary databases:

```clojure
(query '[:find ?a ?b
         :in $a $b
         :where
         [$a ?ea :person/email ?a]
         [$b ?eb :person/email ?b]]
       (db)
       experiment-db)

```

The query request records its complete source-name set before admission.
Release of any member waits for that request's terminal state. Identical
descriptors are resolved once per request. The authority does not create
another connection reference per source or per query; the session acquisitions
already keep all containing database values alive.

After successful `release`, a retained descriptor for that secondary database
fails with the existing not-acquired error until an explicit named `db` call
acquires it again. There is no hidden reacquire merely because a descriptor was
passed to `query`; otherwise stale application data could silently recreate a
dormant database and defeat explicit memory control.

## Duplicate, shared, and disconnect cases

| Case | Required result |
|---|---|
| Repeated named `db` on one Bun session | Fresh head descriptor; one session acquisition; no Datahike count increment after the first. |
| Same database acquired by two Bun sessions | One shared Datahike connection object with two owning references. Releasing one keeps the other live. |
| Repeated `release` on one secondary | First returns `true`; later calls return `false`; Datahike release runs once. |
| Two releases race on one session | The session acquisition is claimed once; both observe deterministic removed/already-absent results. |
| Final release overlaps an accepted write or query | Stop admission, await terminal work, then close. Datahike already joins concurrent final releasers and drains accepted writes. |
| Session disconnect | Cancel and await that session's requests, release every acquisition exactly once, and retain no public cleanup obligation. |
| Bun crashes | UDS close follows the same disconnect cleanup; no client finalizer is required. |
| Two branch routes share one physical store | They remain distinct Datahike connections and cache generations. Datahike shares the physical store's write-hook atom, while release closes only the selected connection's writer, secondary indexes, cache generation, and store handle. |
| Database becomes dormant | Explicit secondary `release` permits final close when no other session owns it. Session-only policy would retain it indefinitely. |

## Rejected alternatives

### Public `connect`, then `db`, then `release`

This most literally copies Datahike, but `connect` would have to return either
an opaque host-like connection or a second ordinary identifier. The Bun socket
already is the persistent ownership boundary, and the application only needs
database values. Adding a public connection value introduces another noun,
another value to route through agent code, and another failure mode without
enabling anything that named `db` cannot do.

Keep `connect` and connection reference counting internal to the authority.

### Request-scoped temporary acquisition

This makes simple reads self-contained, but it hides ownership and makes cache
retention depend on whether another unrelated session is connected. On the
last reference it closes and reopens all database resources for successive
requests. It also prevents a caller from intentionally keeping several
databases hot for repeated cross-source queries.

Request-scoped materialization remains correct for an old retained commit
inside an already-acquired database: load that immutable value for one request
and release it in `finally`. That is database-value lifetime, not named
database connection lifetime.

### Session lifetime only

This is safe and simple for the default, but not for secondary experiment
databases. It offers no deterministic dormant eviction and makes a single
long-lived Bun controller retain every database it has ever observed.

### A macro for automatic release

Datahike's pod supplies `with-db` only as lexical convenience around its
explicit `release-db`. Seon's calls are asynchronous and descriptors can be
stored, passed to another function, and reused across many requests. A macro
cannot infer the last interested asynchronous consumer and would make dynamic
multi-source query construction harder. Explicit `release` plus unconditional
socket cleanup is both simpler and more reliable.

## Compatibility fixtures required before implementation graduates

- one default plus two secondary databases on one physical Bun session;
- repeated named `db` proves one acquisition and no Datahike reference growth;
- cross-database query preserves source order and runs while all acquisitions
  remain live;
- releasing either secondary blocks new dependent requests, waits for an
  admitted multi-source query, and closes only after it reaches a terminal
  state;
- another session keeps the same database and query-cache generation live;
- final release closes the generation and a later named `db` creates exactly
  one new generation;
- duplicate and racing release are idempotent;
- disconnect and abrupt socket close release every remaining database after
  request cancellation;
- cleanup failure remains registered as cleanup-required; and
- the ambient default cannot be explicitly released and continues to satisfy
  omitted-database operations until disconnect.
