---
type: issue
status: resolved
severity: friction
tags: [issue, sci, context, database]
---

# `:seon.sci.admit/capped?` is computed and structurally dropped

Resolved by ruling #25 Steps 4–6. Every result receipt stores its true
serialized character count; oversized receipts also store their blob digest
and a bounded inline window. `seon.render.transcript/capped-result?` derives
capping as `result-size > (count result-edn)` without persisting a boolean, and
the transcript appends one loud trailing `; CAPPED:` line naming the stored
window size, full size, and digest.

The regression in `test/seon/render/transcript_test.clj` transacts a receipt
with no `:seon.sci.admit/capped?` attribute and proves both the derivation and
the rendered line. `test/seon/cluster/loop_test.clj` proves the closed terminal
request carries result EDN, blob digest, and true size together.
