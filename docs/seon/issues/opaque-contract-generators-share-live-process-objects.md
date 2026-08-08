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

The same defect was filed for Flow handles in
[[archive/flow-generators-reuse-one-mutable-sample]], now resolved. This note
owns the remaining cluster, web, and SCI samples rather than widening the Flow
owner silently.

## Evidence

- ~~`src/seon/cluster.clj:71-75` returns one delayed `ServerSocket`
  forever.~~ **Fixed in `ac831976b`**: `socket-server-generator` constructs a
  fresh UNBOUND `ServerSocket` per generation, so ordinary loading and ordinary
  generation bind no port and open no descriptor.
- `src/seon/render/web.clj:91-100` returns one delayed bound http-kit server;
  `:112-117` returns one delayed mult/channel. **STILL OPEN** — the file was
  owned by a running lane during the 2026-08-07 census cleanup pass.
- `src/seon/sci/eval.clj:155-160` returns one delayed mutable SCI context.
  **STILL OPEN** — same reason;
  `test/seon/custody_stability_test.clj:190,204` also names it.
- The remaining two are `defonce` process objects outside any cluster instance
  and have no per-sample teardown path.

## Owner

The named opaque schema predicates and their schema-driven generator policy.

## Acceptance

Opaque lifecycle objects are either explicitly nongenerative or generated
fresh under a cleanup scope that closes sockets, servers, channels, and
contexts as applicable. Invalidating one sample cannot affect the next sample
or any running cluster, and ordinary namespace loading binds no diagnostic
socket.

`seon.public-contract-test/lifecycle-generators-make-a-fresh-sample-each-generation`
is the standing regression; the two remaining samples join its table when
their owners land.
