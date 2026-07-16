---
title: Route direct-interest source audit
type: research
status: complete
tags: [research, prd, database, flow, cljs, web]
---

# Route direct-interest source audit

## Decision

Migrate routing as one async-outer, pure-inner slice. Keep one literal route
query beside the router and use that same form for both the coordinate-pinned
projection request and the writer-owned selective interest:

```clojure
[:find [(pull ?route
               [:seon.route/pattern
                :seon.route/method
                :seon.route/handler
                :seon.route/middleware]) ...]
 :where [?route :seon.route/pattern]]
```

This is smaller and safer than maintaining a second vector of four datom
patterns. A direct probe against the pinned Datahike source returned exactly:

```clojure
#{:seon.route/handler
  :seon.route/pattern
  :seon.route/method
  :seon.route/middleware}
```

Datahike derives those dependencies once when the interest is installed. The
writer indexes the interest by attribute and sends only matching committed
datoms; it does not rerun the route query for every transaction. One unrelated
commit therefore does no route query, projection, or reitit compilation.

No database-authority protocol extension is required for this slice.

## Dependency ledger

| Mechanism | Selected source | Interior seam used |
|---|---|---|
| Datahike | `reference-code/datahike` at `d21abadb9412f1b828b02ddb3c08ddc81d57c595` | `query-attribute-dependencies` merges literal where attributes and pull-selector attributes |
| Database protocol | `src/seon/db/protocol.cljc` | coordinate-pinned query, query-derived listen, request-ID unlisten and cancel, event coordinate |
| JVM authority | `src/seon/db/writer.clj` | one committed-report source per database scope and attribute-indexed interests |
| Bun session | `src/seon/db/transport/uds.cljs` | multiplexed requests, bounded event coalescing, deadlines, close-once behavior |
| Router | `src/seon/web/router.cljs` | pure `projection->routes`, discardable compiled-handler cache, late handler resolution |
| Startup | `src/seon/client.cljs`, `src/seon/web/serve.cljs` | seed routes before HTTP admission; await the initial projection before binding |

The decisive Datahike source is
`reference-code/datahike/src/datahike/query.cljc:2640-2695`. Pull attributes
are included in the dependency set. The writer consumes that set at
`src/seon/db/writer.clj:1897-1939`, indexes it at `:1840-1847`, and selects
matching interests without query execution at `:2052-2083`.

## Minimal acquisition

The current `route-projection` performs an installed-schema read followed by a
query with a pull pattern supplied as an input. Replace both with one direct
query using the literal selector above:

```clojure
{:seon.db/query      route-query
 :seon.db/args       []
 :seon.db/coordinate exact-coordinate}
```

`seon.db` supplies the process-owned database name, attachment, request ID,
deadline, and normal resource limits. The resolved query body is the ordinary
vector of route maps. Stable sorting remains a pure local transformation, and
`projection->routes` remains unchanged. Missing optional middleware stays an
absent map key; no installed-schema request is needed.

A single query should not use `execute-many`. There is one independent result,
so a batch would add a member envelope and head-of-line machinery without
creating parallel work.

## Direct interest lifecycle

Keep the existing application-level listener key `::routes`; do not expose a
second identity to the router. `seon.db` maps that key to the session-assigned
protocol request ID and owns event dispatch. The direct listener request uses
the same `route-query` as `:seon.db/query` interest data.

`router/attach!` should become async but retain its zero-argument call shape.
It resolves to the existing `seon.db` success response only after:

1. the process session has acquired the database attachment;
2. `seon.db/listen!` has installed `::routes` and returned its acknowledgement
   coordinate;
3. the route query has completed at exactly that acknowledgement coordinate;
4. the completion still matches the active listener, requested coordinate,
   and current session generation; and
5. the compiled handler cache has accepted the pure projection.

`web.serve/start!` awaits `router/attach!` before binding the HTTP listener.
Cold startup already seeds routes before `web.serve/start!`, so this moves the
existing readiness boundary rather than adding another phase. The warm-start
path awaits the same idempotent attachment.

Subscribe before querying. Query-then-subscribe has a commit gap. The listen
acknowledgement is already the exact database coordinate after interest
installation, making it the correct initial read point; a separate
resolve-head request is unnecessary.

The Bun transport does not await an async event handler. The `on-event!`
callback must therefore do only a synchronous state transition: retain the
event's coordinate as the desired route coordinate and schedule acquisition
outside the callback. This keeps native socket delivery nonblocking.

Both a datoms event and a resynchronization event mean “refresh at this exact
coordinate.” The route controller need not reconstruct changes from datoms.
The interest already proves relevance, while resynchronization deliberately
omits datoms.

`router/detach!` becomes async. If the shared database session remains open it
awaits `db/unlisten! {::db/key ::routes}`; the writer guarantees no later event
after that acknowledgement. Closing the physical session already removes its
interests, so whole-session shutdown must not reconnect merely to unlisten.

## Cache and completion fences

Continue to use `!router-state`; it is already the one owner of discardable
route compilation. Retain only ordinary values there: config, projection,
compiled handler, desired coordinate, and accepted coordinate. Do not retain a
database value, Promise, socket, or Datahike result wrapper.

When coordinate C1 arrives while C0 is running:

- synchronously replace the desired coordinate with C1;
- best-effort cancel C0 when the facade provides its request identity;
- start at most one replacement acquisition for the latest desired coordinate;
- reject C0's completion because its coordinate is no longer desired; and
- accept C1 only while the listener still exists and the session generation
  still matches.

Correctness comes from registry membership plus coordinate and session fences,
not from cancellation succeeding. This avoids a tombstone or waiter registry.
Under repeated events, retain only the latest desired coordinate, matching the
transport's one-event-per-interest coalescing.

The compiled-handler cache key remains projection plus injected runtime config.
The coordinate is a completion fence, not part of semantic reitit identity:
two coordinates with equal route projections reuse the same compiled handler.

## Disconnect and reconnect

`seon.client` owns reconnection. On close it invalidates the current session
generation once; the transport rejects pending requests and the writer removes
all interests owned by that physical connection. Any old event callback or
query completion is ignored before it can mutate router state.

After reconnect:

1. reacquire the database attachment;
2. install the same query-derived `::routes` interest;
3. use its acknowledgement coordinate for one route query;
4. accept only the new session generation's completion; and
5. resume HTTP use of the replaced cache.

Do not replay transaction reports and do not maintain a process-global latest
coordinate. The interest acknowledgement plus one exact recomputation restores
the only state the router needs.

## Focused test replacement

Remove the route/router tests' fresh local Datahike connections. They prove the
mechanism being deleted and would force `datahike.api` into the Bun test bundle.

Keep these pure `seon.route-test` assertions:

- the exact seeded route maps;
- qualified handler symbols and same-origin middleware; and
- pure Malli-to-Datahike schema derivation while that bridge remains in scope.

Replace the local round-trip test with writer/protocol evidence that route maps
remain ordinary Transit values and the direct query returns native symbols.

Rewrite `seon.web.router-test` around stubbed async `seon.db` functions and
ordinary route vectors:

- listen happens before query, and the query uses the listen ack coordinate;
- the exact query form is shared by acquisition and interest;
- initial attach completes before the HTTP handler is admitted;
- a matching event at C1 causes one query at C1 and one cache update;
- an injected irrelevant event causes no query defensively;
- C1 arriving during C0 makes the late C0 completion unable to replace C1;
- equal projections at different coordinates reuse handler identity;
- resynchronization refreshes once at its coordinate;
- detach targets `::routes`, awaits unlisten, and ignores late completion; and
- a new session generation accepts its own result while old close/event/result
  callbacks are inert.

Retain and focus existing boundary tests:

- `seon.db.protocol-test/selective-interests-use-request-identity-and-closed-data`;
- `seon.db.writer-interest-test/physical-connections-receive-only-matching-committed-datoms`;
- add a writer-interest assertion that `route-query` derives exactly the four
  routing attributes and an unrelated commit emits no event; and
- retain UDS event coalescing, resynchronization, close-once, deadline, and
  out-of-order correlation tests.

The checkpoint gate is the focused route, router, protocol, writer-interest,
UDS-session, and client-runtime tests. It must not open a local CLJS Datahike
connection.

## Unsettled seam

There is no unsettled wire-protocol requirement. Query, listen, unlisten,
cancel, acknowledgement coordinates, resynchronization, and physical-session
cleanup already exist.

One consumer-facade decision must be settled during the in-place `seon.db`
rewrite: an ordinary `db/query` Promise hides the session-assigned request ID,
while best-effort supersession wants to name it for `cancel`. The route slice
remains bounded and correct without mid-flight cancel because coordinate and
session fences reject stale completion. Do not expose UDS or make callers
assign wire IDs merely for this slice. Either let the direct facade internally
cancel a superseded operation owned by the same listener key, or defer route
query cancellation until a general cancellable-request seam is justified by
the larger render/turn consumers.

That is an API ownership question, not a reason to change the protocol or add
a second route mechanism.
