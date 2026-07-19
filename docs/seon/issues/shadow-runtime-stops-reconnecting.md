---
type: issue
status: active
severity: reliability
tags: [issue, cljs, mcp, component]
---

# Shadow runtime stops reconnecting

## Problem

A live Bun pod can remain HTTP-ready while disappearing from Shadow's
`repl-runtimes`. The MCP adapter then finds no runtime advertisement even
though `bin/seon status` reports the pod ready. Restarting the complete system
restores the tool, but ordinary development tooling must recover without
restarting the pod.

## Evidence

Vendored Shadow commit `615430b3` stops scheduling websocket reconnects after
more than three socket errors in
`shadow.cljs.devtools.client.shared/remote-close`. A watcher outage long enough
to cross that limit leaves the healthy JavaScript process permanently detached.
The pod's HTTP readiness probe is independent, so it cannot distinguish this
state.

Maintained Shadow commit `c98bf60f` removes that terminal state. Every close
schedules the existing five-second reconnect, so retry work stays bounded while
the number of attempts does not. Its focused Node test passes two tests and six
assertions, including attempts well beyond the former cutoff. Seon's MCP
regression proves that an agent session re-resolves and pins the replacement
Shadow client ID; the focused MCP gate passes 18 tests and 57 assertions.

## Owner

Shadow owns the runtime websocket lifecycle. Seon's existing MCP adapter owns
database-derived runtime discovery and replacement-client repinning. No second
heartbeat or runtime registry is needed.

## Acceptance

- A runtime continues bounded websocket reconnect attempts after more than
  three failures.
- Seon resolves the runtime's new Shadow client ID after reconnection.
- The Bun pod process identity remains unchanged across a watcher outage and
  return.
- A cluster-qualified `eval_cljs` succeeds after the runtime re-advertises.
- Focused Shadow and Seon MCP tests pass.
