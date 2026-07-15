---
type: issue
status: resolved
severity: blocker
tags: [issue, database, pod, architecture]
---

# Replica used the ambient cluster route for branch writes and feed replay

## Problem

A branch-qualified replica could open a target attachment through an explicit
writer route, then send transactions, KNN requests, replay pages, and feed
filters through the namespace-load `SEON_CLUSTER` route instead. The local
database branch and the logical writer route could therefore disagree, letting
simultaneous source and branch experiments cross-route work or reject valid
events.

## Evidence

Before commit `9edf26f1`, `seon.db.replica/RemoteWriter`,
`connection-coordinate`, and `knn-search!` read the ambient `database-name`
constant rather than the writer route installed in the connected database
configuration. `database-config` also accepted only an attachment and derived
the path and socket from namespace-load defaults. The observed mismatch was one
explicit target attachment paired with an unrelated default logical route.

A later live boot on the descriptor route failed in
`logs/operator/pod/62b1f669-225a-4133-9776-e0720dc0c293.log`: the config passed
to `d/connect` contained the route, but `connection-coordinate` read nil after
deref. The selected Datahike source explains the mismatch. Its non-streaming
`Connection` deref reloads the durable branch record
(`reference-code/datahike/src/datahike/connector.cljc:81-90`), whose persisted
config carries the self writer rather than connection-time remote routing
keys. Therefore neither `:writer :database-name` nor its remote backend is a
fact of an immutable database value.

## Owner

`seon.launch` owns the closed immutable launch composition. `seon.db.replica`
owns its exact consumption for ensure, local Datahike configuration, remote
writes, KNN, replay, and feed filtering. No caller or parallel route registry
may infer these values from process naming.

## Resolution

Resolved in commit `9edf26f1`. The replica now consumes the descriptor's exact
route, attachment, backend, database path, and source writer sockets. The
remote writer retains its configured logical route, and the feed/replay
coordinate derives that route from the connected database. Ensure rejects a
crossed route, attachment, backend, or path before local connection
publication while accepting a complete advanced head on the same attachment.

Focused descriptor/replica/client/blob proof passed 53 tests with 330
assertions and zero failures or errors at
`tmp/test-cljs-20260715-030245-80384.log`. After moving the blob claim to its
private process owner, the smallest affected client/blob selector passed 29
tests with 197 assertions and zero failures or errors at
`tmp/test-cljs-20260715-030704-86367.log`.

The follow-up fix makes `attach!` consume the same closed launch descriptor as
database open. It joins the descriptor-owned logical route and remote backend
with one immutable database point, and derives both feed socket paths from the
descriptor's writer owner. No route is recovered from durable Datahike config,
ambient process naming, connection metadata, or a second registry.
Focused replica/client proof passed 42 tests with 253 assertions and zero
failures or errors at `tmp/test-cljs-20260715-064157-4262.log`.

## Acceptance

- A branch ensure request uses its target route and attachment plus the source
  writer socket, backend, and physical database path.
- A crossed route, attachment, backend, or path fails before `d/connect` or
  feed publication.
- Remote transactions and KNN requests use the connected database's logical
  route, not `SEON_CLUSTER`.
- Replay and live feed filtering retain that same route and attachment.
- An observational reopen accepts a complete newer head on the same attachment;
  the immutable launch coordinate remains creation provenance, not a stale-head
  fence.
- A real non-streaming connected database may expose its durable self-writer
  config after deref while attachment still retains the descriptor route and
  remote backend.
