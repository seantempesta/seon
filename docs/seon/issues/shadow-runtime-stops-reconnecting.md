---
type: issue
status: open
severity: friction
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

### Remaining MCP timing gaps

The source fix makes eventual recovery possible, but the MCP call does not yet
wait on the same timing contract:

- Shadow schedules the next websocket attempt after 5,000 milliseconds.
- `execute-agent-eval` tries at most eleven probes separated by 200
  milliseconds, so it gives up after about two seconds—before Shadow's first
  scheduled reconnect.
- The default session's `get-or-create-session!` probes advertisements once.
  When none exist, it throws before `execute-eval` enters its advertised retry
  loop. The stale-session path has the same hole because
  `retry-with-fresh-session!` calls that one-shot function outside a catch.
- Cluster-pinned `create_session` also probes once. That is acceptable as an
  explicit creation operation if its error remains actionable, but it should
  not be confused with the self-healing `eval_cljs` contract.
- `pin-session!` documents `nil` on failed selection but returns the cloned
  nREPL session without checking `nrepl-select`'s result. A runtime disconnect
  between enumeration and selection can therefore cache a session that was
  never pinned. Later evaluation usually detects it as stale, but the false
  success adds another retry cycle and weakens the function's stated contract.

The next implementation should have one bounded runtime-acquisition function
used by default and agent-targeted eval. Its deadline must cover at least one
Shadow reconnect interval, re-probe current port files and advertisements each
attempt, preserve ambiguity as an immediate error, and validate the pivot
before publishing a session. Named sessions should report that their REPL state
was lost and require explicit recreation; they must not silently become a new
stateful session.

Focused falsifiers require no live lifecycle operations:

```bash
bin/seon test operator seon.dev.mcp-test
```

Add deterministic test clocks/redefs where advertisement probes return empty
until after 5,000 milliseconds, then return one replacement client ID. The
default and cluster-qualified evals must each evaluate once on that replacement
ID. A separate selection test must return `nil`, close the cloned nREPL session,
and publish no cache entry when `nrepl-select` does not return `:selected`.

Those deterministic falsifiers now pass. The one existing eval boundary uses a
6,500-millisecond runtime-reconnect deadline for default and agent-targeted
calls, rereads advertisements every 200 milliseconds, and preserves immediate
ambiguity failure. Named sessions remain one-shot because silently replacing
one would lose intentional REPL state. `pin-session!` now publishes only after
Shadow returns `:selected` and otherwise closes its cloned session. Focused
proof passes 21 tests/67 assertions. Exact watcher-outage/re-advertisement proof
on the pinned artifact remains before closure.

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
- Default and agent-targeted eval wait through at least one five-second Shadow
  reconnect interval without creating divergent retry loops.
- A failed runtime selection never publishes an unpinned nREPL session.
- Focused Shadow and Seon MCP tests pass.
