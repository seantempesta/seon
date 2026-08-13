---
type: issue
status: open
severity: friction
tags: [issue, render, database, schema]
---

# `seon.db/diff`'s render bypasses `seon.print/fit` and declares no HTML producer

Found while researching the general diff
([general-diff-and-render-2026-08-13.md](../../prds/sci-execution-runtime/research/general-diff-and-render-2026-08-13.md) §4).

`resources/seon/schemas/seon.db.diff.edn:16-24` declares the diff result map
with `{:seon.render/ai seon.db/render-diff-ai}`. Three defects follow from
how that producer is written (`src/seon/db.clj:1638-1663`):

1. **No `:seon.render/html` producer is declared**, so every delta renders
   through the floor renderer in the web UI while the agent surface gets a
   purpose-built one. Both projections are meant to be the same block.
2. **The producer hand-builds its string and never crosses
   `seon.print/fit`.** It emits one line per changed row with every changed
   attribute spelled out, so a wide change set is unbounded output from the
   one function whose purpose is to bound output. This is the bounded-output
   law applied to the renderer that most needs it.
3. **The elision is a single token estimate for the whole result**
   (`(tokens/estimate (pr-str result))`), not an elision value. The ruled
   shape carries count, path, next offset, and requery identity per omitted
   slot; a bare "approximately N tokens" tells the agent nothing it can act
   on except the requery id it already has.

Fix all three at the one producer: render through `seon.print/fit`, emit a
real elision value for the omitted rows, and declare a
`seon.db/render-diff-html` beside the `/ai` key. One regression asserting a
bounded render over a deliberately wide delta kills the class.

Note for whoever takes this: the research note recommends replacing the
`:added`/`:removed`/`:changed` slots with an identity-rooted edit script. If
that lands first, this fix belongs on the new producer and is smaller — the
headline becomes three counters and the body one line per edit.
