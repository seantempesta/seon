---
type: issue
status: completed
tags: [issue, agent, context]
---

# Findings section renders OPEN plan rows as settled facts → confabulation

## Resolution (2026-07-06)

Fixed in `seon.agent.ctx.findings`: `finding-eids` now scans `[?e ?a ?v]`
and sweeps OUT any entity carrying a lifecycle-STATUS datom (predicate
`lifecycle-status?` — an attr whose local name is `status` holding a keyword
state). Work-tracking rows (`:my.plan/status`) never enter findings; they have
their own status-aware plan section. The discriminator is a single COMPUTED
convention over the attribute's local name — NOT a namespace allowlist: any
future `my.<domain>/status` is covered with zero edits, and genuine knowledge
enums (`:my.kb/confidence`) are untouched (local name is `confidence`, not
`status`). Live-verified: an `:open` plan step titled "User has been told…"
no longer appears in findings while the `my.kb` fact still does. Covered by
`test/seon/agent/ctx/findings_test.cljs`.

Found 2026-07-06 by byte-level observation of a live drive (cluster
`mad-drive`, child `aCb-2607061951`, run `dBM`, turns `RNt`/`goE`). HIGH
severity — affects every agent.

## What happens

`seon.agent.ctx.findings/findings-block` selects entities via `user-attr?`
(`src/seon/agent/ctx/findings.cljs:62`) — the net = **any entity with a
non-`seon.*` namespaced attribute**. That sweeps in `my.plan/*` rows
alongside genuine `my.kb` facts. `row-line` (`findings.cljs:119`) then
renders `"; " ns " #" eid ": " <longest-string-attr>` — for a plan row the
longest string is `:my.plan/title`, and **`:my.plan/status` is not rendered
at all**.

Result: an `:open` plan step titled in perfect/declarative tense
("Subagent has messaged me the sum…", "User has been told the value")
renders byte-identically to a verified completed fact — under the header
*"stored findings — your accumulated knowledge… CONSULT these BEFORE
re-researching."*

Observed render (aCb's prompt, rows are ROOT's `:open` steps 3026/3027):

```
; my.plan #3027: User has been told the exact sum value
; my.plan #3026: Subagent has messaged me the sum 1..100 computed by its sub-subagent
```

At render time #3026/#3027 were `:open`. aCb read this as "work already
done in a previous session" and confabulated for ~4 turns before a direct
`:my.plan/status` query self-corrected it.

## Why it's a bug, not disposition

The agent read the context faithfully; the context lied. Plan rows are
WORK-TRACKING with a lifecycle status, NOT settled knowledge — and plan has
its own dedicated, status-aware section + verbs. They do not belong in
"accumulated knowledge."

## Fix direction

Preferred: `findings-block` surfaces KNOWLEDGE (`my.kb`), not work-tracking
— exclude `my.plan/*` from the sweep. The deeper smell is the blind
`user-attr?` net ("anything non-`seon.*`"): a fact is something WITHOUT an
open lifecycle status. A structural rule (exclude entities carrying a
non-terminal lifecycle-status attr) is better than a name list. If plan
rows must appear as "artifacts I created," `row-line` MUST render
`:my.plan/status` inline so an `:open` step can never read as settled fact.

Related: [[plan-frontier-hides-open-root-with-done-children]] (same
plan-renders-incompletely family). Surfaced in the same drive as the
missing-wake-orientation design gap (see the multi-agent context memory).
