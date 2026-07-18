---
type: issue
status: resolved
severity: friction
tags: [issue, mcp, cljs, component]
---

# Default CLJS session retains a replaced Shadow port

## Problem

After `bin/seon cluster reset default` replaced the watcher and pod, the MCP
`eval_cljs` default session retained its old Shadow nREPL port. Its next eval
returned `Connection refused` even though a cluster-qualified agent eval
immediately discovered and reached the replacement runtime.

## Evidence

`nrepl-eval` normalized a socket connection failure to an ordinary nREPL error
map. `stale-runtime?` recognized disconnected JavaScript runtimes and wedged
sessions, but not that transport result, so `execute-eval` rendered the error
instead of invoking its existing fresh-session recovery.

The focused regression closes a real ephemeral server socket, evaluates
against the replaced port, and proves that the returned transport failure is
classified for reconnection. The complete focused MCP gate passes 16 tests and
51 assertions.

## Owner

The one development MCP adapter's existing default CLJS session lifecycle in
`script/seon/dev/mcp.clj`.

## Acceptance

- A replaced Shadow port enters the existing fresh-session recovery path.
- The default session re-discovers this MCP server's own cluster and remains
  pinned to its unique runtime.
- Named and cluster-qualified session semantics do not change.
- The focused MCP gate passes.

## Resolution

Resolved by `a42dc6ae`. `nrepl-eval` now identifies transport failure as data,
and the existing stale-runtime decision includes that failure. No port-message
substring or second reconnection mechanism was added.
