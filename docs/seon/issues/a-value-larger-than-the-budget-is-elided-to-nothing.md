---
type: issue
status: open
severity: friction
tags: [issue, render, print, wave/print-path]
created: 2026-09-15
---

# A value larger than the budget is elided to nothing

## Problem

When one scalar or one collection exceeds the presentation profile's token
budget, the value renderer emits an elision value that omits EVERYTHING:
no prefix of the content is shown, and on the MCP path there is often no
requery either. Two observations on default, 2026-09-15 23:4xZ:

- SCI evaluation mode, `(dir seon.test)` in `my.agents.root`: the whole
  reply was `{:seon.print/bound-by :seon.render.profile/token-budget,
  :seon.print/elision-unit :characters, :seon.print/omitted 5619,
  :seon.render.data/next-offset 0, :seon.render.data/path [],
  :seon.render.data/total 5619}` — 5619 of 5619 characters omitted, zero
  shown. An agent reading this learns nothing about `seon.test`.
- MCP `jvm` mode, a 3828-character string result: face `:seon.print/elided`,
  omitted 3828 of 3828, `:seon.print/requery-refusal "the value has no
  durable MCP artifact"`. The caller cannot see the value and cannot ask
  for it.

Ugly output is a defect by standing order; an elision that shows nothing is
the degenerate case of the class the n1-total-render lane owns (a floor hit
must be counted, never silent, and the elision value must let the reader
continue).

## Wanted

An elision never omits the whole value: the renderer shows the budgeted
prefix (for a string, its first N characters; for a collection, its first
children) and the elision value names the omitted remainder with a working
requery or a paged path. On the MCP path a value that exceeds the window is
staged as an artifact so the requery is honest, or the window is applied
per node rather than to the root. Regression: rendering a 4,000-character
string and a 200-row `dir` under the agent profile yields shown text whose
length is within the budget and non-empty, and whose elision value carries
a requery that returns the remainder. Owner: n1-mcp-bypass / n1-total-render
when their current slices land.
