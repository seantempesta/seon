---
type: defect
status: open
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
