---
type: issue
status: open
tags: [issue, render, context]
---

# `:seon.render.value/options` is declared schema but never read

`resources/seon/schema/render_value.edn` declares the per-call
presentation bounds with shipped defaults (max-depth 3, max-collection
8, max-map-visits 32, max-string 80, shape-sample 8, width 72 — "the
proven presentation bounds from the quarry"), but
`src/seon/render/value.cljc` never reads any `:seon.render.value/max-*`
option: the implementation windows and clips using the ADMISSION caps
directly (`display-value` pages by
`:seon.config.eval.result/max-collection`). The flexible
presentation layer the schema promises — small smart summaries by
default, wider on request — is unwired, and presentation is coupled to
the safety maxima (which ruling #25 is raising ~100×, making the
coupling acute).

Fix at the one owner: `prepare`/`admitted-view` accept
`:seon.render.value/options` merged over the schema defaults, and the
window/clip sizes derive from those options — admission caps remain
only the outer safety bound. The caps-blob wave's step 2 decouples the
page size; this issue covers the REST of the options map.

Acceptance: a unit rendered with no options shows the schema defaults;
the same unit with explicit options widens; admission caps still cap
both; regression covers the class.
