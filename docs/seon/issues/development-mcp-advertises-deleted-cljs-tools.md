---
type: issue
status: open
severity: friction
tags: [issue, tooling, mcp, repl]
---

# Remove deleted CLJS tools from the development MCP

## Problem

The development MCP still advertises four CLJS control tools after the CLJS
build and pod were deleted. Each tool can only return a guiding error value,
so client discovery presents unavailable capabilities as callable tools.

The two client registrations also retain CLJS-era naming:
`.mcp.json` calls the server `seon_cljs`, and both clients launch
`bin/mcp-server-cljs`.

## Evidence

- `script/seon/dev/mcp.clj:517-569` advertises `eval_cljs`,
  `create_session`, `stop_session`, and `reload_deps` beside the live
  `eval_clj`, `list_sessions`, and `runtime_status` tools.
- `script/seon/dev/mcp.clj:571-588` dispatches all four dead tools directly to
  `cljs-off-error`.
- `.mcp.json:3-5` and `.codex/config.toml:3-4` retain different server names
  and the same CLJS-named launcher.
- A newline-delimited JSON-RPC `initialize` plus `tools/list` probe against
  `bin/mcp-server-cljs` returned those dead tools in the advertised schema.

## Owner

The one development MCP implementation in `script/seon/dev/mcp.clj`, its thin
launcher, and the Claude/Codex registrations.

## Acceptance

- Tool discovery advertises only live JVM operations.
- The launcher and both client registrations use one JVM-neutral name.
- After the required client restart, `initialize`, `tools/list`,
  `runtime_status`, and a cluster-qualified `eval_clj` succeed.
- No active development guidance or registration names `eval_cljs` or a CLJS
  MCP server.
