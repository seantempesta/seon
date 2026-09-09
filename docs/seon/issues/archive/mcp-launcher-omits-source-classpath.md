---
type: issue
status: resolved
severity: blocker
tags: [issue, mcp, operator, wave/dev-mcp]
---

# MCP launcher omits the source classpath

## Problem

The MCP tools cannot load their tooling dependency `seon.id`.

## Evidence

Hook-async observed `runtime_status` return `Could not locate seon/id.bb,
seon/id.clj or seon/id.cljc on classpath.` on 2026-09-08. `bin/mcp-server:6`
includes only script and resources, whereas the repaired `bin/seon` includes src.
The existing hook classpath issue records the operator repair, not this launcher.

## Owner

`bin/mcp-server`.

## Acceptance

The launcher loads the MCP namespace and a selected default JVM evaluation answers.


## Resolution

`ef424c37c` adds src to the launcher classpath. A new `bin/mcp-server`
process handled an actual MCP tools/call to default JVM `(+ 1 1)` and
returned 2 in 1 ms on 2026-09-09T05:13Z. The hook gate passed 9 tests /
72 assertions with zero failures or errors.
