---
type: issue
status: open
severity: friction
tags: [issue, mcp, tooling, render, elision]
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
