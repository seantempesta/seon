---
type: issue
status: open
severity: cleanup
tags: [issue, flow, lifecycle]
---

# Delete the monitor graph's command-proc passthrough

## Problem

`seon.flow/monitor-graph` implements the `Graph` protocol's `command-proc`
arity by delegating to `flow.graph/command-proc` on the wrapped graph. That
method is declared in the protocol but is **not implemented** by
`create-flow`'s reify, and it is not exposed in the public
`clojure.core.async.flow` namespace at all. Calling it throws
`AbstractMethodError`.

The arity is dead code that reads like a supported capability, which is worse
than its absence: a future lifecycle surface could reasonably reach for it.

## Evidence

- `src/seon/flow.clj:835-836` — the delegating arity (was `:623-624` before the
  namespace grew; re-confirmed present 2026-08-07).
- `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl/graph.clj:24-25`
  — `command-proc` declared in the protocol.
- `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:87-197`
  — the reify implements every other method and omits this one.
- Live probe (`tmp/flow_control_probe.clj`, OpenJDK 26.0.1, core.async
  `v1.10.874-alpha3`):
  `java.lang.AbstractMethodError: Receiver class
  clojure.core.async.flow.impl$create_flow$reify__9812 does not define or
  inherit an implementation of the resolved method 'command_proc'`.
- Full transcript:
  `docs/prds/sci-execution-runtime/research/flow-control-protocol-2026-07-31.md`.

## Owner

`src/seon/flow.clj`.

## Acceptance criteria

The arity is deleted, or it returns a flat `:seon.error` value naming the
unimplemented dependency method. A `reify` that must satisfy the protocol
compiles either way; nothing first-party calls it, so deletion of the body in
favour of an explicit refusal is the honest shape.
