---
type: issue
status: open
severity: friction
tags: [issue, mcp, operator, wave/dev-mcp]
---

# MCP session loss claims a restart without process evidence

## Problem

The MCP `session-lost` response says a cluster restarted even when its recorded
process identity remains unchanged. The diagnostic does not distinguish a
lost prepl session from a process replacement.

## Evidence

On 2026-09-15, `reaching-verify` and `reaching-final` sessions returned
`Named CLJ session ended with the cluster restart.` Fresh sessions still read
the running verification futures from `user`. `bin/seon status` continued to
report default pid 69622, generation 997f66f8-1102-4127-a9c3-833db941320d;
its process start remained 2026-09-15T19:25:43Z. No lane lifecycle action was
performed. This establishes session loss, not its cause.

## Owner

`script/seon/dev/mcp.clj`, the session lifecycle diagnostic.

## Acceptance

Report a restart only after observing a changed process identity. Otherwise
name the lost session and request reconnection without asserting a cause.
