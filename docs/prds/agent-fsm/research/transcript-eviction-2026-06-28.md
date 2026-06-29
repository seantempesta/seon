---
type: research
status: active
tags: [agent, context, flow, render]
---

# Transcript eviction / token-budget policy — the #1 stable-context lever (2026-06-28)

> SUPERSEDED by [[transcript-dynamic-cache-aware-2026-06-28]] (measured + cache-aware).
> The recency-WEIGHTED budget walk proposed below is cache-HOSTILE (an old event's
> tier depends on newer events' sizes → cache bust every turn). The successor
> replaces it with a discrete age-band gradient + frozen clips, and backs the clip
> values with measurements on the real `root` transcript. Read this for the
> load-bearing/evictable analysis; take the policy + numbers from the successor.

Read-only design (no live LLM drive) for an eviction / token-budget policy on the
agent's `:transcript` block — the single biggest token sink in the stable context
(~20,315 tok for the long-lived `root` agent, **unbounded** today,
`:seon.render/clip :none`). Grounded in `src/seon/agent/ctx/transcript.cljs`,
`seon.agent.ctx/format-eval-row`, the `SEON_RENDER_*_CAP` family in
`src/seon/config.cljs`, and the result-stash mechanism in `src/seon/eval.cljs`.
All numbers are TOKENS (`seon.ai.tokens/estimate`, chars/4) — never chars.

## TL;DR — the proposed policy in 4 lines

1. **Three recency tiers, derived at render time** (no stored flags): newest events
   render FULL (the steering surface), a middle band renders CLIPPED (source +
   narration stay, big values collapse to a `result/<id>` pointer + shape hint),
   the old head collapses to ONE rolling summary line.
2. **Old-value → pointer is the core dedup**: an old eval's value body (up to
   result-body-render-cap = 16,384 chars ≈ 4,096 tok) drops to a ~10-20 tok
   `;=> <type>(<size>) ; result/<id> — live, deref to expand`. The value is STILL
   in the live var; echoing it verbatim in an old turn is pure waste.
3. **Budget = the retained `transcript-token-cap` (6,000), turned ON** as a
   recency-weighted walk (FULL ≤ ~3,500 tok, CLIPPED ≤ ~2,000, HEAD ≤ ~500).
4. **Pins**: masthead, live readline, the resume marker, and any UNANSWERED (NEW)
   inbound always render full regardless of age.

**Estimated savings:** `root` transcript 20,315 → ~6,000 tok = **~14,300 tok/turn
saved** on a long-lived agent (~70% of the block, ~49% of the whole non-`:namespaces`
stable budget of ~29k). **Lean/young agents see ZERO change** — the policy is a
no-op until the 6k budget is exceeded (exactly today's behaviour below the cap).

## How the transcript renders today (the cost structure)

`transcript-block` (`transcript.cljs:424`) = `masthead` + N time-ordered EVENT rows
+ live `readline`. Every past event is byte-stable (time from fixed stored `:at`).
There is **no clipping** — `:seon.render/clip :none`, all events render, forever.

Each EVAL row (`eval->renderable` → `seon.agent.ctx/format-eval-row`,
`ctx.cljs:567`) is composed of FOUR independently-capped components:

| Component | Source | Cap (chars) | ≈ tok | Dereferenceable? |
|---|---|---:|---:|---|
| preamble (narration / `;` prose) | `:seon.eval/narration` | eval-render-cap 1,500 | ≤375 | no |
| form-ln (echoed source) | `:seon.eval/source` | eval-render-cap 1,500 | ≤375 | no |
| out-ln (captured stdout) | `:seon.eval/output` | eval-render-cap 1,500 | ≤375 | no |
| **result-ln (the VALUE body)** | `:seon.eval/result-edn` | **result-body-render-cap 16,384** | **≤4,096** | **YES — `result/<id>`** |

The value body is by far the largest line item (an order of magnitude over the
others) AND it is the ONE component the agent can recover for free: each successful
eval auto-binds its value as a live var `result/<id>` on `globalThis`
(`eval.cljs:786`), and `format-eval-row` already trails every value line with
` ; result/<id>` as the live handle. Message rows (`message->renderable`) cap at
message-render-cap 4,000 chars (≈1k tok) each.

**Key confirmation (the dedup is temporal, not spatial):** there is NO separate
"result-stash" CONTEXT BLOCK — `default-seed-blocks` (`ctx.cljs:1599`) has no such
entry. The value is rendered exactly ONCE, in its own eval row; the `result/<id>`
var is dereferenced by the agent re-typing `result/<id>`, which produces a NEW
recent eval whose value renders fresh. So the redundancy isn't "same value in two
blocks" — it's "an OLD turn still carries a 4k-token value the agent could
re-reference in one keystroke." Old verbatim values are the waste.

## Load-bearing vs evictable

**Load-bearing — must stay FULL:**

- **The masthead + the live readline** (`readline`, `transcript.cljs:359`) — tiny,
  the readline is the only moving line + carries the cursor/steering. Always full.
- **The newest events — the steering surface.** The agent's last 1-2 turns: what it
  just did, the values it is actively working with, the most recent inbound it must
  answer. This is the working set; clipping it breaks the loop.
- **Any UNANSWERED (NEW) inbound message** — the open steering item. Pinned full
  regardless of age (the `::new?` flag already exists, `transcript.cljs:473`).
- **The resume marker** (`resume-marker-line`) — one line, marks where live vars die.

**Evictable — clip progressively with age:**

- **OLD eval VALUE bodies (the big win).** Recoverable via `result/<id>`; an old
  verbatim value is redundant. Collapse to a pointer + shape hint.
- **OLD stdout** — rarely matters once the turn is past; drop to first line, then
  drop entirely.
- **OLD echoed source** — useful for "what did I do" reconstruction, so keep it
  through the middle tier; only fold into the summary at the old head.
- **OLD narration/prose** — cheap and load-bearing for the NARRATIVE ("I added a
  todo, then queried the schema"); keep through the middle tier, count-only at head.

## The proposed policy — three recency tiers, derived at render time

A pure function of the already-ordered event list + `tokens/estimate`. Walk events
**newest → oldest**, accumulate token cost, and assign a render DETAIL level by which
budget band the cumulative cost falls into. No stored `:evicted` datom, no tier
attr — recomputed every render, self-healing (new events push old ones down the
tiers automatically; a re-reference floats a value back to Tier 1 as a fresh eval).

### Tier 1 — FULL (steering surface) · budget ≤ ~3,500 tok

Newest events. Render EXACTLY as today (full source, full stdout, full value body at
result-body-render-cap, full narration). At a typical mixed eval cost this is the
last ~8-15 evals + recent messages — comfortably the active turn plus the prior
one or two.

### Tier 2 — CLIPPED (recent tail) · budget ≤ ~2,000 tok

Older-but-relevant events. Keep the NARRATIVE skeleton, drop the bulky payload:

- **value body → pointer + shape hint** (the dedup): `;=> <type>(<size>) ;
  result/<id> — live, deref to expand`, where the hint is derived cheaply from the
  stored `:seon.eval/result-edn` WITHOUT rendering it full (e.g. `map 7 keys`,
  `vec 340 items`, `string ~1.2k tok`). ~10-20 tok vs up to 4,096.
- **stdout → first line only** (or drop).
- **source → full** (cheap, load-bearing for "what I did").
- **narration → full** (cheap, the narrative thread).

Because each clipped event costs ~30-80 tok instead of up to ~5k, this 2,000-tok
band covers DOZENS of older events — the agent keeps a long, legible history of
WHAT it did with the heavy values one keystroke away.

### Tier 3 — SUMMARIZED HEAD (old head) · hard cap ~500 tok

Everything older than the budget collapses to ONE rolling summary line (or a small
handful), e.g.:

```
; [earlier in this session] 14 evals · 3 queries, 2 transact!, 1 error · result vars EVLa…EVLz still live (deref any to expand) · 2 messages
```

Just enough that the agent knows history exists and how to reach it. Inbound message
HEADLINES (who/when/first clause) survive here even when eval payloads don't —
conversational continuity outweighs old eval bodies. PRIOR-session evals
(`::prior?`, dead vars) live here by definition (they're oldest); their values are
not var-recoverable, so the summary leans on "re-run a form to recompute" (the
resume marker already says this).

### Always-pinned (outside the tier budget)

Masthead, readline, resume marker, and any `::new?` UNANSWERED inbound render full
regardless of where the recency walk would place them.

## The clip schedule (per component, by tier)

| Component | Tier 1 FULL | Tier 2 CLIPPED | Tier 3 HEAD |
|---|---|---|---|
| value body | full (16,384 cap) | **pointer + shape hint** | folded into summary count |
| source | full | full | dropped (counted) |
| stdout | full | first line | dropped |
| narration | full | full | dropped (counted) |
| inbound msg | full | full | headline only |

## Budget numbers — grounded in the measured costs

- Total transcript cap = **6,000 tok** — the retained `transcript-token-cap`
  default (`config.cljs:263`, `SEON_RENDER_TRANSCRIPT_TOKEN_CAP`), turned ON. This
  is a ~70% cut from root's current 20,315.
- Tier split inside the 6k: FULL ~3,500 / CLIPPED ~2,000 / HEAD ~500. These are the
  tuning knobs; propose them as three new `seon.config` reads
  (`transcript-tier-full-cap` / `-clipped-cap`, HEAD = remainder) so the U lane can
  A/B them via `SEON_PROFILE` without a code change — same pattern as the existing
  cap family.
- `result-body-render-cap` STAYS 16,384 (Tier-1 values can be large + citable); the
  tiering bounds total cost, so the big cap no longer implies unbounded growth.
- No new caps needed for source/stdout — they already cap at eval-render-cap 1,500;
  the tiering just drops/clips them by age.

## Estimated savings at typical lifetimes

| Agent lifetime | Transcript today | With policy | Saved/turn |
|---|---:|---:|---:|
| young (< ~6k transcript) | < 6,000 | unchanged | **0** (no-op below cap) |
| `root` (measured) | 20,315 | ~6,000 | **~14,300** |
| long task agent (grows unbounded) | 30k-60k+ | ~6,000 | **24k-54k+** |

For `root`, the non-`:namespaces` stable budget drops from ~29,200 to ~14,900 tok
(~49%). Combined with the #42 `:namespaces` render-trim, the two levers roughly
halve the whole always-on prompt. Crucially the policy is **bounded** — a long task
agent that runs for hours stops growing the prompt linearly; it plateaus at ~6k.

## Reactive / derive-don't-store compliance

The entire policy is a render-time fold over `(ordered-events …)` + `tokens/estimate`.
No stored tier, no `:evicted` flag, no "mark as seen". Recompute every render →
self-healing: as events arrive, the recency walk re-tiers automatically; if the
agent re-references a clipped `result/<id>`, that emits a NEW recent eval whose value
renders full in Tier 1 — **re-reference is un-evict, for free.** This is exactly the
reactive-context doctrine: the surface (a full value) appears when needed (recent /
re-referenced) and vanishes when not (aged out), with nothing to clear.

## Lane split (file:line — U presentation vs Core context-engine)

Per the agent-fsm charter ("a change that alters the agent's CONTEXT is a Core
change even when the UI lane requested it; don't fix a context bug in a render fn"),
the **eviction MECHANISM is Core** — it changes what the agent SEES. U owns the
tuning numbers/wording + the live-drive validation.

**Core (the mechanism):**

- `src/seon/agent/ctx/transcript.cljs:424` `transcript-block` — add the
  newest→oldest recency walk that assigns a detail level per event before rendering;
  the tier budgets; the head-summary fold. (Today's `body` reduce at
  `transcript.cljs:489` is where the per-event render happens — the walk wraps it.)
- `src/seon/agent/ctx/transcript.cljs:160` `eval->renderable` — thread the
  per-event detail level into the delegate.
- `src/seon/agent/ctx.cljs:567` `format-eval-row` — add a detail-level arg
  (`:full | :clipped | :summary`); at `:clipped` emit the value→pointer + shape
  hint INSTEAD of the `cap-result-body` body (the shape hint is a cheap derive over
  `:seon.eval/result-edn` — reuse the type/size probe that the value-renderer
  already has; do NOT render the value full just to measure it).
- `src/seon/config.cljs:263` `transcript-token-cap` — flip the transcript from
  `:seon.render/clip :none` to enforce this; add `transcript-tier-full-cap` /
  `-clipped-cap` knobs alongside the existing `SEON_RENDER_*_CAP` family.

**U (presentation / tuning — validate by live DeepSeek drive):**

- The exact tier budgets (3,500 / 2,000 / 500) and the pointer/head-summary WORDING
  — presentation decisions to A/B against real drives (does the lean transcript
  still let the agent resume / recall / steer?). These live in the same
  transcript.cljs file but are the tuning surface, not the mechanism.
- Confirm via drive that an agent reliably RE-REFERENCES a clipped `result/<id>`
  when it needs the value (the policy's load-bearing assumption). If agents don't
  re-reference, the pointer line needs a stronger nudge (or Tier-2 keeps a longer
  value preview).

## Open questions for the implementer

- **Shape-hint deriver**: reuse `seon.render`'s value skeleton probe (it already
  computes type + size for the structural renderer) rather than a second one — one
  mechanism. Confirm it can run on the stored `:seon.eval/result-edn` string cheaply
  (it's a pr-str string; may need a guarded `edn/read-string` like
  `read-error-envelope`).
- **Message tiering**: messages cap at 4,000 chars each. Should Tier-2 messages
  clip to a headline too, or only evals? Proposal: evals clip first (bigger, more
  numerous); messages clip only at Tier 3 — conversational continuity is higher
  value per token.
- **Prior-session head**: prior evals have dead vars, so their values can't pointer.
  They're oldest → Tier 3 by construction; the summary should NOT imply their vars
  are live. The `::prior?` flag is already threaded.
