---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, mcp, repl]
---

# Remove deleted CLJS tools from the development MCP

## Resolution

The one development MCP now advertises exactly three live JVM operations:
`eval_clj`, `list_sessions`, and `runtime_status`. The four error-only CLJS
tools and their dispatch arms were deleted rather than retained as compatibility
responses.

The launcher is `bin/mcp-server`, and both `.mcp.json` and
`.codex/config.toml` register it under the name `seon`. Active REPL guidance now
uses the resulting `mcp__seon__*` names. The two active CLJS/pod REPL guides
were deleted because every operation they taught is gone.

## Evidence

- The focused MCP bridge gate initializes the server, lists exactly the three
  JVM tools, and proves a deleted tool name follows the unknown-tool path.
- A newline-delimited JSON-RPC probe against `bin/mcp-server` initialized,
  listed tools, reported live cluster status, and evaluated a cluster-qualified
  JVM form.
- Repository registration and active-guidance searches find no CLJS-named MCP
  launcher or server.

## Operator note

Already-running Codex and Claude clients retain the old stdio registration and
tool schemas. Restart the client task after this change; the server process
cannot update a client's loaded tool schema in place.
