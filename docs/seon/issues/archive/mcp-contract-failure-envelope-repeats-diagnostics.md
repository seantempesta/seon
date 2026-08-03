---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, mcp, repl, render]
---

# Render one bounded diagnostic in MCP contract-failure envelopes

## Problem

`eval_clj` repeated one contract violation as `:exception-message`, an escaped
`:text` copy, and blob metadata, and persisted a 22,488-byte blob for a
one-sentence violation.

## Evidence

The semantic owner was `seon.cluster/mcp-project`, before the stdio bridge in
`script/seon/dev/mcp.clj`. Its exception face admitted a full Throwable map,
added a second printed copy, and forced every exception through the blob path.

## Owner

The existing MCP projection over the shared admission and blob owners. There
is no MCP-specific exception renderer or second blob rule.

## Acceptance

A one-sentence contract violation appears once as one bounded useful message
and creates no blob. A genuinely oversized complete value uses the existing
blob owner and retains its digest/size evidence. Focused recurring proof checks
the whole envelope and byte/blob boundary.

## Resolution — 2026-08-03

Commit `e780f8b48` replaces the Throwable face with one admitted
class/message/first-party-frame summary and applies the existing inline ceiling
to that semantic value. Only a genuinely oversized summary becomes a blob.

`bin/test seon.cluster.mcp-test` passed 6 tests / 33 assertions. Independent
loaded-Var verification of `(seon.sci.kernel/invoke {})` measured 1,246 bytes,
one message occurrence, no `:seon.dev.mcp/text`, and no digest, size, or
retrievable fields. A 5,000-character exception remained windowed and
retrievable with digest
`6df94dd7da8522f7f93c63bb47cfbb911c5c0368263c37702e4e8f928332952c`;
direct artifact inspection recovered all 5,000 characters.
