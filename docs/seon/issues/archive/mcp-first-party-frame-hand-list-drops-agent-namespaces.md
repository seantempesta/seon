---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, mcp, repl]
---

# Derive MCP first-party exception frames instead of prefix-listing them

## Problem

MCP exception projection classifies first-party frames with four string
prefixes. This is a hand-maintained classification rule and it already drops
agent-owned namespaces such as `my.agents.*` (and any real agent namespace
elsewhere in the program graph). The resulting envelope can omit the frame
that identifies the failing authored function while retaining only an outer
Seon or REPL frame.

## Evidence

- `script/seon/dev/mcp.clj:497-503` is the literal prefix list.
- `script/seon/dev/mcp.clj:505-530` filters the trace through that list and
  reports dropped frames only as a count.
- `test/seon/dev/mcp_bridge_test.clj:118-153` covers `seon.*`, `user$eval`, and
  `repl_context$eval`, exactly the listed cases; it has no authored namespace.
- `tmp/audit-20260801b/src/mcp_exception_probe.clj` projected a trace containing
  `my.agents.audit$explode`, `seon.audit$explode`, and `user$eval42`. The first
  frame disappeared and `frames-omitted` became one.

## Owner

The MCP exception-envelope projection, grounded in the program graph or source
inventory that defines first-party code.

## Acceptance

- Frame membership is derived from source/program provenance rather than
  namespace spelling.
- A compiled agent-owned namespace frame survives alongside core and REPL
  frames.
- Dependency/JDK frames remain omitted and the bounded-frame contract remains
  intact.

Resolved by `5a83efc2e`. Exception frame membership is derived from
first-party source provenance and the current prepl namespace. A
compiled agent-owned namespace regression passes.
