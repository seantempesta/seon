---
type: defect
status: resolved
severity: cleanup
tags: [mcp, admission, cleanup]
---

# The MCP envelope still reports the retired `capped?` key

`seon.sci.admit/capped?` was deleted from admission by the `storage-bound`
lane (2026-09-07, `3518903dd`): a value is stored faithfully or marked
`:seon.eval/missing`; nothing is "capped". The `eval_clj` MCP envelope
still emits `"seon.sci.admit/capped?": null` on every result (observed
2026-09-07 late evening on `juniper-context`), which is a stored nil in the
tool's output and a key with no writer.

## To do

Delete the key from the MCP result envelope (`src/seon/dev/mcp.clj` or its
owner) and its schema; if a caller wants "was anything omitted", the
answer is `:seon.eval/missing` on the evaluation or an elision node in the
rendered value, never a boolean.

## Resolved

`storage-bound-repair-2` (2026-09-07) deleted the four remaining writers —
`src/seon/cluster.clj` (the evaluation face, the MCP refusal envelope, the
projected artifact envelope) and `src/seon/sci/kernel.clj`'s
failure-admission envelope — so the key appears in no result the tool
returns. `script/seon/dev/mcp.clj`'s tail-elision enrichment had been gated
on the same retired key and therefore never fired; it is now keyed on the
`:seon.sci.admit/elided` SENTINEL it actually looks for. The tests that
asserted the stored `false`/`true` now assert its absence
(`seon.db-test`, `seon.render.value-test`, `seon.cluster.mcp-test`).
