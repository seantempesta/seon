---
type: research
status: complete
tags: [research, database, agent, flow]
---

# Membership and quiescence single-owner audit

## Decision

Finish membership and quiescence as two ordered cuts through the existing
`seon.db` session. Do not add a membership registry, copied database position,
result envelope, ambient connection, or a second lifecycle path.

The membership implementation committed at `077cdb75` correctly replaced
coordinate-shaped listener results with a scalar listener key and native
`:db-after` values. Two details remain unsettled:

- a completed membership query is compared with a client-owned object reference
  rather than the session's latest ordinary database value; and
- the listener maintains a second list of attributes beside the query that
  actually defines resumable agents.

Quiescence has a simpler retained seam. `seon.agent.run/quiescence-work!`
already returns the exact ordinary database value used to prove that current
runs and running turns are empty. `seon.client` discards that value and still
uses removed coordinate, request-key, and result-envelope contracts. Carry the
returned database value into the terminal turn pulls, then keep it internal.
The process lifecycle response does not need a database coordinate.

This audit describes current source at
`256a7afa8a4b1b47b3720246dd230f554fcc9423`. It changes no source, test, or
lifecycle behavior.

## Dependency ledger

| Dependency or owner | Selected source | Retained constraint |
|---|---|---|
| Seon database session | `src/seon/db.cljs:268-288, 600-656, 1062-1130` | The session caches each received `:db-after` before invoking an interest handler. `db` returns the cached ordinary database value. `listen!` returns one scalar key; `unlisten!` returns a boolean or direct error. |
| Membership derivation | `src/seon/derive.cljs:232-253` | `resumable-agent-ids` is the one query-backed derivation over an explicit ordinary database value. |
| Agent facade | `src/seon/agent.cljs:304-348` | `armable-agent-ids` already accepts an optional `:seon.db/db`; `resumable-agent-ids!` should use the same application-facing convention instead of forcing another current database read. |
| Runtime advertisement | `src/seon/client.cljs:307-465` | One keyed interest and one accepted vector feed the synchronous process advertisement. Owner identity and pending asynchronous work remain process-local lifecycle facts, not database authority. |
| Quiescence acquisition | `src/seon/agent/run.cljs:319-385` | `quiescence-work!` acquires one current database value, executes the current-run and running-turn queries against it, and returns that same value with ordinary result vectors or a direct error. |
| Run close | `src/seon/agent/run.cljs:686-751` | `close-run!` uses a database-value fence and returns a native transaction report or direct error. It owns the run close and agent-pointer retract transaction. |
| Portable lifecycle response | `src/seon/runtime/lifecycle.cljc:1-29` | One closed Malli schema is shared by the pod and Babashka operator. The successful response carries durable drain results and optional process generation. |
| Operator quiescence | `script/seon/dev/process.clj:1435-1505` | The operator validates the closed response, HTTP status, `:seon.client/quiesced?`, and managed process generation. It does not consume the database coordinate. |
| Web lifecycle transport | `src/seon/web/serve.cljs:1541-1569` | The loopback handler awaits the one client lifecycle function and serializes its result unchanged. It owns no database or quiescence semantics. |
| Maintained Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae` | Datahike owns keyed listener replacement and query attribute dependency derivation. Seon should consume those mechanisms rather than repeat them. |

### Datahike source evidence

`reference-code/datahike/src/datahike/core.cljc:199-217` associates a callback
under one opaque key, replaces the callback when the same key is reused, returns
that scalar key, and removes it with `unlisten!`.

`reference-code/datahike/src/datahike/query.cljc:2705-2721` exposes the pure
`query-attribute-dependencies` operation. It conservatively returns the concrete
attributes that can affect a query, or `:all` when the query cannot be narrowed
safely.

The Seon writer consumes that exact operation in
`src/seon/db/writer.clj:2123-2136`. It indexes query interests by those derived
attributes in lines 2055-2072 and sends committed reports only when transaction
datoms match them in lines 2226-2326. No client-maintained attribute list is
needed when the listener receives the same query form used for the projection.

## Single retained owners

### Latest database value and listener ownership

`seon.db` remains the only owner of the session's latest database values and
interest registrations. `session-event!` caches an event's `:db-after` before
looking up and invoking its addressed handler. An unrelated transaction can
therefore advance the session's cached database value without starting a
membership refresh: the writer delivers the membership event only when a
membership query dependency changed.

That behavior is correct. The membership projection must compare its input with
the current session value when its asynchronous query completes. It must not
create a second notion of which database value is current.

### Membership query and application facade

`seon.derive` owns the resumable-agent query and the sorting of its result.
Keep one query definition there and use it both for `db/query` and the selective
`db/listen!` request. `seon.agent/resumable-agent-ids!` remains the application
facade and accepts an optional `:seon.db/db`, matching the existing
`armable-agent-ids` convention.

`seon.client` owns only the process occurrence: one owner identity, scalar
listener key, pending attach or refresh work, and the last accepted vector
needed by the synchronous advertisement. It does not own the query semantics,
an attribute list, a database-value cache, or a sequence counter.

### Quiescence work and lifecycle response

`seon.agent.run/quiescence-work!` owns the coherent read. It already returns:

- `:seon.db/db`;
- `:seon.agent.run/current-runs`; and
- `:seon.agent.run/running-turns`.

The client closes only current runs that have no running turn, re-reads the same
owner until both vectors are empty, and uses the database value from that final
empty result for terminal turn pulls. `close-run!` remains the only run-close
writer.

`seon.runtime.lifecycle` owns the external result shape. The final database
value is used internally to classify terminal turns; it is not transport
evidence required by the operator. Canonical restore database positions remain
private writer/operator administration and are not reintroduced into the
application database facade.

## Current membership defect

`src/seon/client.cljs:340-364` records the database value under
`::advertisement-db`. When a query resolves, it accepts the result if the owner
is still identical and the query's database object is `identical?` to that
client field.

This does not prove that the query ran against the session's latest database
value. A concrete failing order is:

1. A matching transaction starts a membership query at database value T1.
2. Before that query resolves, an unrelated transaction advances the session
   cache to T2.
3. Because the second transaction changes no membership dependency, it starts
   no membership query and leaves `::advertisement-db` at T1.
4. The T1 query resolves and is accepted even though `db/db` now returns T2.

The defect is not fixed by another counter or copied database marker. After
the query resolves, accept the vector only while the listener owner is current
and the queried database value equals the current value returned by `db/db`.
At this point `db/db` is a cached process-local read. Remove
`::advertisement-db`.

`src/seon/client.cljs:366-390` also maintains
`runtime-advertisement-datom-patterns` separately from the query literal in
`src/seon/derive.cljs:248-252`. Replace the patterns with one query interest so
Datahike derives its dependencies from the same form that computes the result.

## Current quiescence contradictions

### Terminal turn pulls use removed request keys

`src/seon/client.cljs:2227-2265` accepts a coordinate and sends
`::db/coordinate`, `::db/pull-pattern`, and `::db/refs` to `db/pull-many`. The
current closed request shape in `src/seon/db.cljs:96-104` is
`::db`, `::selector`, and `::eids`. Errors are direct values carrying
`:seon.error/message`; `:seon.db/error` is retired.

The function should accept the final ordinary database value and send:

```clojure
{::db/db database
 ::db/selector [:seon.agent.turn/id :seon.agent.turn/status]
 ::db/eids (mapv (fn [turn-id] [:seon.agent.turn/id turn-id]) turn-ids)}

```

Input alignment and terminal `:done`/`:error` classification remain valuable
domain checks.

### Drain ignores direct errors and native reports

`src/seon/client.cljs:2282-2336` destructures `quiescence-work!` without first
checking for a direct error. It then tests `close-run!` results through the
removed `:seon.db/ok?` envelope. A successful native transaction report has no
such key, so the current code loses successful close evidence.

Check both the initial and refreshed work values for `:seon.error/message`
before destructuring them. A close result with `:seon.error/message` is a
failure only if the refreshed result still contains that run; this preserves
the existing concurrent-close behavior. A native report is successful close
evidence. When the refreshed work is empty, pass its `:seon.db/db` to every
terminal turn pull.

### Client and lifecycle still accumulate an unused coordinate

`src/seon/client.cljs:2338-2427` accumulates `::db/coordinate`, accepts a
`capture-coordinate?` argument, falls back to removed `db/head-coordinate`, and
emits `:seon.db.coordinate/coordinate` in the successful response.

Delete coordinate accumulation, the boolean argument, and the head read.
Planned quiesce and ordinary stop use the same owner teardown. The process
generation and drained run, turn, and host IDs remain.

`src/seon/runtime/lifecycle.cljc:9-24` is the only production consumer that
requires the coordinate in the portable response. The Babashka operator does
not read it, and the web handler only transports the result. Remove the import
and field while keeping the map closed.

## Ordered implementation cuts

### Cut 1: finish membership against the session database value

Wait until the current config/startup owner commits and releases
`src/seon/client.cljs`. Then:

1. Keep one resumable-agent query definition in `seon.derive`.
2. Make `seon.agent/resumable-agent-ids!` accept optional `:seon.db/db` and
   acquire the current value only when omitted.
3. Register the client interest with that query, not a copied datom-pattern
   vector.
4. Refresh from native transaction and resynchronization `:db-after` values.
5. Accept a completed query only while its owner is current and its database
   value equals the session's cached latest value.
6. Delete `::advertisement-db` and the copied membership attribute list.
7. Preserve one scalar listener key, one attach owner, pending asynchronous
   work, and the accepted ID vector.

This cut does not add a compatibility function or rename any function with a
version suffix.

### Cut 2: finish quiescence with the final database value

Coordinate ownership of `src/seon/client.cljs` and the dirty web test before
starting. Then:

1. Check every `quiescence-work!` result as ordinary data before destructuring.
2. Consume native `close-run!` reports and direct errors.
3. Carry the database value from the final empty-work result into
   `settled-turns!`.
4. Use the current `pull-many` request keys and direct error convention.
5. Remove coordinate accumulation, `capture-coordinate?`, and the removed
   `head-coordinate` fallback.
6. Remove the coordinate from the one closed lifecycle response and all direct
   fixtures in the same commit.
7. Keep the web endpoint a transport-only owner and keep the operator's
   generation/status validation unchanged.

The client implementation and lifecycle schema must not land separately: a
successful client result must satisfy the one portable schema at every
coherent commit.

### Cut 3: integrated proof

Run focused membership and quiescence proof first. Then coordinate one
source-frozen complete CLJS, writer, operator, and live restart checkpoint.
The final source search must find no coordinate or database-envelope
assumptions in membership or application quiescence. Remaining database
coordinates are confined to private writer/operator administration until that
separate contract is replaced.

## Shortest falsifiers

### Membership

1. Start a deferred membership query at T1, advance the session cache to T2
   through an unrelated change, then resolve T1. The accepted ID vector must
   not change.
2. Resolve T2 before T1. Only the T2 vector may be accepted.
3. Capture the listener request. It contains the exact membership query under
   `::db/query` and no `::db/datom-patterns`.
4. Agent birth and termination update the vector after their accepted
   `:db-after`. A reconnect resynchronization converges through the same
   handler and scalar key.
5. `(agent/resumable-agent-ids! {:seon.db/db database})` does not call `db/db`
   and passes that exact value to the derivation. Omission calls `db/db` once.
6. An event arriving during initial attachment settles the advertisement to
   the session's current database value before startup consumes the vector.

### Quiescence

1. A direct error from the first or refreshed `quiescence-work!` fails before
   any result destructuring or further close.
2. One idle current run returns a native close report; the refreshed work is
   empty and the run ID appears in the result without any `:seon.db/ok?` key.
3. A previously running turn becomes terminal. `pull-many` receives the exact
   final empty-work database value under `::db/db`, plus `::db/selector` and
   `::db/eids`. No current-head request occurs.
4. A direct pull error, lost input alignment, or nonterminal status fails
   closed.
5. The successful lifecycle response validates without a coordinate. Adding a
   coordinate or any other key fails because the schema remains closed.
6. The web endpoint writes the same coordinate-free EDN only after quiescence
   resolves. The operator accepts it and continues to reject HTTP-status and
   process-generation contradictions.

## Minimal coherent path sets

### Membership

- `src/seon/derive.cljs`
- `src/seon/agent.cljs`
- `src/seon/client.cljs`
- `test/seon/agent/multiagent_test.cljs`
- `test/seon/client_advertisement_test.cljs`

### Quiescence

- `src/seon/client.cljs`
- `src/seon/runtime/lifecycle.cljc`
- `test/seon/client_quiescence_test.cljs` — new focused client drain proof;
  there is currently no client quiescence test
- `test/seon/runtime/lifecycle_test.cljc`
- `test/seon/web/serve_test.cljs`
- `test/seon/dev/process_test.clj`

No production change is presently required in `script/seon/dev/process.clj`,
`src/seon/web/serve.cljs`, `src/seon/agent/run.cljs`, or
`test/seon/agent/run_test.cljs`. The operator and web source already transport
or validate the retained result without reading a coordinate, and the run test
already proves that one database value reaches both quiescence query members.
Add one of these paths only if implementation evidence exposes a real contract
mismatch.

## Dependency order and overlaps

The current client startup still contains retired
`db/assert-preconditions!`, `::db/coordinate`, and `:seon.db/ok?` assumptions at
`src/seon/client.cljs:2007-2047`. Those belong to the earlier config/startup
publication cut. Membership and quiescence must follow that owner and must not
patch around it.

At audit persistence, other agents have uncommitted work in
`src/seon/config.cljs`, `src/seon/web/serve.cljs`, and additional source/test
paths. The membership cut requires a coherent handoff of `src/seon/client.cljs`.
The quiescence web fixture requires a coherent handoff of
`test/seon/web/serve_test.cljs`; the production web handler should remain
unchanged unless new evidence requires otherwise.
