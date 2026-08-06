---
type: research
status: complete
tags: [research, agent, database]
---

# R9 — namespaces block token budget, measured live

Question: is a fresh agent's ~28k-token `:namespaces` block the
configured intent, a config gap (compact selection not engaged), or a
code gap?

## Method

Live default cluster, 2026-07-20. Rendered
`seon.agent.ctx.namespaces/namespaces-block` for fresh agent
`crisp-needles-travel` via `eval_cljs`; segmented the returned
`:seon.render/ai` text on the `;;; ┌─ namespace <x> ─` brackets;
per-segment `seon.ai.tokens/estimate`.

## Result: 28,145 tokens, all of it already COMPACT

Total block: 112,583 chars = 28,145 estimated tokens. Segment sum
28,136 (segmentation is complete). Every namespace section is a
reader-commented compact card (schema records + one-line fn
signatures); the only full-source section is the agent's own home ns
stub (182 tokens). Compact selection IS engaged — there is no config
gap and no code gap in selection.

Per-card tokens, descending:

| ns | tokens | of which `; schema` lines |
|---|---|---|
| seon.agent.web | 3,908 | 1,307 |
| seon.db | 3,725 | 2,035 |
| my.plan | 3,630 | 2,558 |
| seon.agent.fs | 2,736 | 2,032 |
| my.blob | 2,596 | 1,878 |
| seon.agent.shell | 2,089 | 1,475 |
| my.canvas | 1,506 | — |
| seon.agent.search | 1,448 | — |
| seon.schema | 1,389 | — |
| my.kb | 1,003 | — |
| seon.agent.message | 926 | — |
| my.ui | 908 | — |
| seon.agent.lifecycle | 564 | — |
| my.skills | 454 | — |
| my.data | 438 | — |
| my.ns | 432 | — |
| home-ns stub + preamble | 384 | — |

Across the whole block, `; schema ` lines are 15,284 of 28,145 tokens
(54%).

## Why 17 cards

The default toolbelt (`:seon.eval/home-requires` in
`config/system.edn`, 16 namespaces) wires every fresh agent's home ns;
the block renders one compact card per required ns (its documented
three-rule model). 28k is therefore the summed compact surface of the
configured toolbelt — the configured intent, with two honest levers:

1. **Manifest, but not recommended as a budget fix**: a
   `:seon.agent.ctx/token-cap` on the `:namespaces` block CLIPS text
   mid-card (`seon.agent.ctx` cap semantics) — it would truncate
   arbitrary cards, not select. Trimming `:seon.eval/home-requires`
   changes the agents' wired capability surface, not just the render.
2. **Code (compact-card density)**: schema records are 54% of the
   block. Rendering each card's schemas as names-only (or only schemas
   referenced by that card's fn signatures) would take a fresh agent
   from ~28.1k to ~13-16k tokens with zero capability change. This is
   a `render-one-ns-compact-row` change in
   `src/seon/agent/ctx/namespaces.cljs`.

No change made — owner decision requested between: accept 28k as
intent, thin compact-card schema density (est. ~13k result), or trim
the default toolbelt.
