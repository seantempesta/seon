---
type: issue
status: resolved
severity: friction
tags: [issue, mcp, render]
---

# `mcp__seon__get_value` returns `seon.sci.admit/elided` for a large string at the drilled path, with no way to page it

## Problem

Drilling a stored eval blob to a path whose value is one large string
(`[1 :seon.error/data-edn]`, a 4,975-character print-node EDN string,
cluster `ctxprobe`, 2026-09-03) returns
`{:seon.render.value/window "seon.sci.admit/elided", :shown 0, :total nil}`
— the tool elides the string and offers no offset paging over characters,
so the value is unreachable through the tool that exists to reach it. The
same door output elsewhere windows strings to ZERO characters
(`""… 36 more characters of 36; requery by … offset 0`) — a requery that
returns the same nothing. Absence-as-signal at a diagnostic surface: the
orchestrator had to bypass the tool with a raw JVM `subs`.

## Owner

`seon.cluster/mcp-get-value` / `mcp-project` (src/seon/cluster.clj) and the
string bound in `seon.print` (`:seon.config.eval.result/max-string`,
print.cljc:847) as applied by the MCP projection.

## Acceptance

A string at a drilled path pages by character offset with the tool's
existing `offset`; no window ever shows zero characters of a non-empty
string (a string budget below one line is a floor hit that is counted and
shows the head); one regression per claim.

## Resolution

Resolved by `d5b212f88`. `seon.print/fit` now cuts collection breadth and
depth before reducing strings, and its string limit stops at the printer's
72-character line width. The MCP projection applies that fit profile to every
result rather than only blob-backed results. Stored strings page directly by
character offset, clamping an at-or-past-end request to the last character so
a non-empty string never returns an empty window.

`bin/test seon.print-test seon.cluster.mcp-test` passed 29 tests and 139
assertions. A cluster freshly reforked from isolated-root `current-src` commit
`6a9c610f-8513-5365-abb1-863f7e5ae3aa` rendered the 116-row `seon.db` function
query with readable `"([database request])"` and `"([value])"` strings while
eliding breadth. The same live MCP surface returned `"i"`, `shown 1`, and
`beyond-end? true` for a 4,975-character stored string requested at offset
4,975.
