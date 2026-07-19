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

Seon commit `6cf70cc7` pins that maintained source. The exact-source live build
could not graduate on 2026-07-19 because the host killed Tailwind CSS twice
with signal 9/exit 137: first during `bin/seon restart`, then during the single
allowed `bin/seon up` continuation. Both attempts had already prepared the new
CLJS dependency and compiled the self-host bootstrap. The supervisor left the
default watcher, writer, and pod cleanly absent. `bin/acme status --edn` also
reported its managed processes absent, so an old PID-1
`node out-acme/client/main.js` process was not supervisor-owned and was not
touched. Host RSS at the failure included unrelated 2.7–3.1 GB desktop,
virtualization, and backup processes. This is a host-resource blocker, not a
Shadow compile or focused-test failure; live preserved-pod re-advertisement
remains open.

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
