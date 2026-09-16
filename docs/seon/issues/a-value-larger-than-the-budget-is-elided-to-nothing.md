---
type: issue
status: resolved
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

## Generated-read-identities observation, 2026-09-16

The MCP JVM return carrying the first 40 Juniper prompt lines elided all
8,290 characters at offset zero. This time it supplied a retrievable artifact
digest, so the observed residual is whole-string omission, not missing requery.
Exact bytes are preserved in
[the opening artifact](../../prds/steward-platform/research/generated-read-identities-2026-09-16-opening.txt).

## Resolved 2026-09-16 — an elision never omits its whole subject

Fixed at the one clipping spot, `src/seon/print.cljc`:

- `fit-text` keeps `string-limit` characters as `::prefix`, sets
  `:seon.render.data/next-offset` to how many it showed, and counts only the
  remainder;
- `enrich-node`'s `::truncated-string` branch carries the text admission
  already kept;
- `fit`'s search floors structurally at one child, one level, one character,
  halving the string limit instead of jumping to zero;
- `render-elision-ai` surfaces `::prefix` and `::requery-refusal`, and reports
  a character cut's sizes in estimated tokens.

Live on `default` pid 95853, SCI evaluation mode:
`[(apply str (repeat 3000 "ab"))]` now shows a 1,638-character prefix with
`:seon.print/elision-unit :tokens`, `omitted 1363` of `total 1875` and
`next-offset 511`.

Regressions: `seon.render.value-test/an-oversized-string-shows-its-prefix-not-only-a-count`
and `seon.render.value-test/an-agent-facing-cut-reports-its-size-in-estimated-tokens`;
`seon.print-test` and `seon.render.web-test` had asserted the defective bytes
and now assert the floor.

The MCP-path half of this note (a value that exceeds the window with no
durable artifact) is unchanged by this slice: it is the same floor applied at
the MCP window, and the return now at least carries a prefix wherever the
value renderer produced the cut.

Evidence:
[the landing note](../../prds/steward-platform/research/dir-elision-floor-2026-09-16.md).
