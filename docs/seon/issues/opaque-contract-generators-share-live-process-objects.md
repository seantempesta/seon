---
type: issue
status: open
severity: cleanup
tags: [issue, contracts, runtime, testing]
---

# Stop opaque contract generators from sharing live process objects

## Problem

Several opaque runtime schemas advertise generators backed by one delayed live
object for the lifetime of the JVM: a bound prepl socket, an http-kit server, a
core.async mult/channel, and an SCI context. Generating or mutating one sample
changes the sample every later contract check receives; the server generators
also bind real process sockets even though no cluster owns their lifecycle.

The same defect is already filed for Flow handles in
[[flow-generators-reuse-one-mutable-sample]]. This note owns the remaining
cluster, web, and SCI samples rather than widening the Flow owner silently.

## Evidence

- `src/seon/cluster.clj:71-75` returns one delayed `ServerSocket` forever.
- `src/seon/render/web.clj:88-97` returns one delayed bound http-kit server;
  `:109-114` returns one delayed mult/channel.
- `src/seon/sci/eval.clj:141-146` returns one delayed mutable SCI context.
- All are `defonce` process objects outside any cluster instance and have no
  per-sample teardown path.

## Owner

The named opaque schema predicates and their schema-driven generator policy.

## Acceptance

Opaque lifecycle objects are either explicitly nongenerative or generated
fresh under a cleanup scope that closes sockets, servers, channels, and
contexts as applicable. Invalidating one sample cannot affect the next sample
or any running cluster, and ordinary namespace loading binds no diagnostic
socket.
