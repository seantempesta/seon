---
type: issue
status: open
severity: friction
tags: [issue, mcp, render, bounds]
created: 2026-09-25
---

# The MCP value window inherits the agent token budget

## Evidence

Default pid 67823, HEAD 428125c44, JVM-mode probe of `seon.cluster/mcp-valf`
with `seon.config/defaults` on `{:rows (vec (range 50000)) :text <262,145 x>}`
(the body of `seon.cluster.mcp-test/nested-bulk-is-bounded-by-the-shared-value-window`):
the projected EDN is 25,130 bytes (631 ms) against the test's 8,192-byte window.
The row vector is cut at 16 children; the string elision keeps a
`:seon.print/prefix` of 24,000 characters, `:seon.print/bound-by
:seon.render.profile/token-budget`.

## Cause

`seon.cluster/mcp-project` (src/seon/cluster.clj, the `profile` binding) builds
the MCP profile as `(assoc (render/agent-render-profile effective)
:seon.render.profile/id :seon.render.profile/mcp)`, so it carries
`:seon.config.render.agent/token-budget 15000` (config/default.edn:100), the
agent's per-value budget, not a window sized for an MCP reply. No config dial
declares an MCP window; the 8,192 bound exists only as a literal in
test/seon/cluster/mcp_test.clj:121, :140, :149.

## Fix direction (cluster.clj owner)

Give the MCP profile its own declared token budget (one dial beside
`:seon.config.render.agent/token-budget`, shipped in config/default.edn with the
measured byte/token ratio), and have mcp_test read that dial instead of the
8,192 literal. The whole value remains retrievable by `:seon.blob/digest`
through `get_value`. Unprobed: mcp_test.clj:172 (elisions losing their paths).
