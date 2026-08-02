---
type: reference
status: abandoned
tags: [reference, web, history]
---

# Historical Hyperlith comparison

> Historical dependency research only. Its atom-watch, full-view refresh, and
> route recommendations are not Seon's web architecture. Use
> [[datastar-quick-reference]].

The Hyperlith review established two durable lessons: derive presentation from
server state, and keep SSE delivery simple enough that reconnect can repaint.
Fresh Seon applies those lessons through database `listen!`, one Flow render
proc, complete page snapshots, stable block identities, equality suppression,
and per-tab Datastar morphs. It does not use Hyperlith's `defview`,
`refresh-all!`, atom watches, or full-page-on-every-change mechanism.

The detailed 2025 comparison and copied framework examples were deleted after
the chosen implementation diverged. Git preserves them. Current evidence is in
`src/seon/render/web.clj`; dependency source remains under
`reference-code/hyperlith/` for archaeology.
