---
type: research
status: active
tags: [agent, context, flow, render]
---

# Dynamic, cache-aware, detail-gradient transcript — MEASURED (2026-06-28)

Supersedes the tier-numbers proposal in
[[transcript-eviction-2026-06-28]] (which proposed a recency-WEIGHTED budget
walk but never measured clip values, and — critically — would have BUST the
prompt cache every turn). This doc replaces the budget-walk with a
**discrete age-band gradient + frozen clips**, and backs every number with the
standalone prototype `tiered_transcript_proto.cljs` applied to the REAL `root`
transcript on the live default pod. All sizes are TOKENS
(`seon.ai.tokens/estimate`, chars/4), never chars.

## TL;DR

1. **Seon DOES use Anthropic prompt caching today** — but the transcript is
   ENTIRELY in the uncached volatile tail. The whole transcript reprocesses
   every turn (~21.8k tok for `root`).
2. **The budget-walk in the prior design is cache-hostile.** A cumulative
   token-budget makes an old event's tier depend on the sizes of all NEWER
   events → one new event re-tiers old ones → cache bust. Replace it with a
   **discrete age-band gradient**: tier = f(age-in-events), so an aged-out
   event's clipped form FREEZES (pure fn of event + tier) and never changes.
3. **Measured (real `root`, 146 events, baseline 21,843 tok):** the recommended
   config **A (6 full / 10 light / 16 pointer / rest summary)** renders the
   whole transcript in **3,718 tok (-83%)**, of which **1,673 tok is a FROZEN,
   append-only, byte-stable prefix** and only **2,045 tok is the volatile recent
   window** that reprocesses each turn.
4. **Cache placement:** put the frozen `:transcript-history` sub-block into the
   EXISTING cached prefix (priority ≤ `stable-priority-max`, contiguous after
   `:namespaces`); keep the recent window + readline volatile (priority 100).
   No new Anthropic breakpoint needed — the existing 2-breakpoint split already
   caches everything above the boundary. History (1,673) + namespaces (7,813) =
   a **9,486-tok cached prefix**, far over the 4,096-tok Opus cache minimum.

## Does seon use prompt caching? YES — two breakpoints, transcript uncached

`src/seon/ai/anthropic.cljs` `request-params` (ns docstring "PROMPT CACHING",
task #34) splits the assembled ctx at `seon.agent.ctx/stable-boundary` via
`split-context` and emits `:system` as TWO `cache_control {:type "ephemeral"}`
blocks:

- block 1 = the soul / `effective-system-prompt`;
- block 2 = the STABLE ctx prefix (every section with
  `:seon.agent.ctx/priority` ≤ `stable-priority-max` = 20, i.e. through
  `:namespaces`).

`:messages` carries ONLY the volatile tail (everything below the boundary) as
the user message. That uses **2 of the 4 allowed breakpoints** — 2 remain.

**The volatile boundary today:** `stable-boundary` falls at the
stable→volatile priority transition (≤ 20 stable; `:live-tile` 35, `:warnings`
40, `:open-todos` 45, `:inventory` 97, **`:transcript` 100** are all volatile).
So **the entire transcript rides AFTER the breakpoint, uncached** — it is the
volatile tail, reprocessed in full every turn. That is the ~21.8k waste this
design recovers. (Caveat from the docstring: a prefix under 4,096 tok silently
won't cache on Opus — see placement below.)

## The cache-stability mechanism (frozen clips + discrete boundary)

### Why the prior budget-walk busts the cache

The prior design walked newest→oldest accumulating a token budget and assigned a
tier by which budget band the cumulative cost fell into. That makes an old
event's tier a function of **the sizes of every newer event**. Add one new eval
(or re-reference a value, inflating the tail) and the budget boundary shifts,
re-tiering events deep in the history → their rendered bytes change → the cached
prefix is invalidated **every turn**. A token-savings design that defeats the
cache can NET-COST tokens (every turn pays full cache-write price).

### The fix: tier = f(age-in-events), frozen on aging

Assign each event a tier from its **age measured in discrete events** (how many
events are newer than it), NOT from a cumulative byte budget:

- The render at a tier is a **pure function of the event alone** (clip its
  components to fixed per-tier caps). It does not read any neighbor.
- An event's tier only changes when a band BOUNDARY crosses it. Boundaries sit
  at fixed event-counts from the tail, so they advance by exactly the number of
  new events that arrived — in DISCRETE steps (per-event, or per-K if quantized).
- Once an event is older than the last band, its tier is terminal (`:summary`)
  and its bytes are **frozen forever** — independent of how many newer events
  exist.

**Append-only prefix.** Walking from the oldest event, the first byte-change per
turn is at the `:pointer`→`:summary` boundary (one event drops a tier). Every
event OLDER than that boundary is already `:summary` and unchanged. So the
contiguous byte-stable prefix = the `:summary` run, and as events accrue it only
GROWS from its newer end (each turn ~1 newly-frozen event appends ~15 tok). A
monotonically-growing prefix is the ideal Anthropic cache shape: last turn's
cached prefix is still a prefix of this turn's.

**Proven on real data** (prototype, three accruing turn-snapshots of `root` at
140 / 143 / 146 events, config A):

| turn (events) | frozen prefix (events) | s(n) is exact prefix of s(n+1)? |
|---|---:|---|
| 140 | 108 | — |
| 143 | 111 | ✓ (s140 ⊏ s143) |
| 146 | 114 | ✓ (s143 ⊏ s146) |

The frozen `:summary` lines are byte-identical and append-only across turns.

### Cache-breakpoint placement (no new breakpoint needed)

The existing single `stable-boundary` already creates the cache split. We only
need the frozen `:transcript-history` to land in the STABLE prefix and the
recent window in the volatile tail:

```
[system 1: soul]                         ← cache_control (existing)
[system 2: …:namespaces  +  :transcript-history(frozen)]  ← cache_control (existing breakpoint moves down)
─── stable-boundary ───
[user: :live-tile :warnings :open-todos :inventory  :transcript-recent  readline]   ← volatile, reprocessed
```

Concretely: give `:transcript-history` a priority ≤ `stable-priority-max` (raise
`stable-priority-max` to e.g. 21 and seed history at 21 so it renders just after
`:namespaces`), and keep `:transcript-recent` at priority 100. The small
volatile blocks (live-canvas/warnings/todos/inventory, ~few-k tok) stay below the
boundary, as today. The cached prefix becomes `namespaces (7,813) +
history (1,673) = 9,486 tok` — over the 4,096 Opus minimum, so it actually
caches. A spare breakpoint (2 remain) could later split soul/namespaces from
history for partial-hit resilience, but is not required for the win.

(Reading order note: the small volatile blocks render between the two transcript
halves. Both halves remain oldest→newest internally and are self-bracketed
sections, so the chronology still reads top-to-bottom; the agent's live cursor
sits at the very end on the readline as today.)

## The detail gradient (finer near "now")

Not 3 hard tiers — a recency gradient with FINER bands at the tail, decaying with
age. Each band is a fixed number of events; the clip level deepens with each
band; everything past the last band is the frozen summary head:

| Tier | what survives | value body | ≈ tok/event | deref handle |
|---|---|---|---:|---|
| **`:full`** (tail) | everything (today's render) | full (≤16k cap) | ~70 (mixed) | yes — `result/<id>` |
| **`:light`** | narration(≤240) + source(≤300) | clipped to ≤400 chars | ~72 | yes — `result/<id>` |
| **`:pointer`** | narration(≤140) + source(≤160) | **dropped → shape hint** `;=> map ~24 tok ; result/<id> — deref to expand` | ~56 | yes — pointer |
| **`:summary`** (head, frozen) | one line: `; · <verb…60> ;=> <shape>` | folded into the line | ~15 | no (re-run to recompute) |

The gradient is what makes the design honest: the agent keeps FULL detail for
the last few evals (the steering surface), a legible clipped history with the
`result/<id>` deref handle one keystroke away for the mid band, and a dense
frozen one-liner index for everything older. A re-reference of a clipped
`result/<id>` emits a NEW recent eval that renders `:full` in the tail — the
gradient un-clips on demand, for free.

Sample rendered output (real `root` events, config A):

```
; :pointer  →  ; ns-publics check
               (ns-publics 'my.agent.root)
               ;=> map ~0 tok ; result/hNB-2606281714 — deref to expand

; :summary  →  ; in my.agent.root
               ; · (message/user ;=> map ~24 tok
```

## MEASURED — clip values × cache-stability × detail (real `root`, 21,843 tok baseline)

Prototype applied to `root`'s 146 real events (133 evals + 13 msgs). Bands are
`[count clip]` newest-first; everything older than the last band → frozen
`:summary`.

| Config | bands | total tok | **frozen prefix** | volatile tail | n frozen | cut |
|---|---|---:|---:|---:|---:|---:|
| baseline (all `:full`) | — | 21,843 | 0 | 21,843 | 0 | 0% |
| **A (recommended)** | 6f / 10l / 16p | **3,718** | **1,673** | **2,045** | 114 | **-83%** |
| B | 8f / 16l / 24p | 4,796 | 1,439 | 3,357 | 98 | -78% |
| C (generous) | 12f / 20l / 32p | 5,822 | 1,195 | 4,627 | 82 | -73% |
| E (4-band, finer) | 6f / 8l / 12p / 20p | 4,386 | 1,471 | 2,915 | 100 | -80% |
| F (max-savings) | 10f, rest summary | 2,461 | 1,965 | 496 | 136 | -89% |
| D (no summary head) | 8f, rest pointer | 9,226 | 0 | 9,226 | 0 | -58% |

Config A per-tier breakdown: `:full` 6 ev / 420 tok · `:light` 10 ev / 723 tok ·
`:pointer` 16 ev / 902 tok · `:summary` 114 ev / 1,673 tok.

**Reading the trade:**

- **Token savings:** every gradient config cuts 73–89%. Even the generous C is
  -73%.
- **Cache stability:** the frozen prefix (the cache-HIT portion that reprocesses
  for free) is largest when more events reach `:summary` — A (1,673 / 114 ev) and
  F (1,965 / 136 ev) dominate. D (no summary tier) has a **zero** frozen prefix —
  it busts the cache, exactly the prior-design failure; **a `:summary` head is
  mandatory for cache stability.**
- **Detail near now:** A/B/C/E keep a `:pointer` band, so the last ~32–64 events
  retain the `result/<id>` deref handle; F drops straight from full→summary at
  event 11, losing the deref handle abruptly (max-savings profile only).

**Cross-turn steady state (config A):** ~1,673 tok cached prefix HIT every turn;
~2,045 tok volatile tail reprocessed; ~1 event/turn (~15 tok) appended to the
frozen prefix on the cache write. Versus today: 21,843 tok reprocessed, 0 cached.

### Recommended config: **A — `[[6 :full] [10 :light] [16 :pointer]]`**

Best joint optimum of token-savings (-83%, 3,718 total) × cache-stability (1,673
frozen, 114 events append-only) × detail-near-now (6 full + 10 light + 16 pointer
= 32 events keep the deref gradient before the frozen one-liner index). E is a
near-tie if a 4th band is wanted; F is the `:minimal`-profile max-savings variant;
C the `:generous` profile for agents that lean hard on recent history.

## Config knobs (the `SEON_RENDER_TRANSCRIPT_*` family)

All in `seon.config`'s typed env surface (`env-int`), alongside the existing
`SEON_RENDER_*_CAP` family — configurable + tunable per cluster via
`SEON_PROFILE` / `config/system.edn`, no hardcoded magic:

| env var | default | meaning |
|---|---:|---|
| `SEON_RENDER_TRANSCRIPT_FULL_BAND`    | 6  | # newest events rendered `:full` |
| `SEON_RENDER_TRANSCRIPT_LIGHT_BAND`   | 10 | # next events rendered `:light` |
| `SEON_RENDER_TRANSCRIPT_POINTER_BAND` | 16 | # next events rendered `:pointer` (deref handle kept) |
| `SEON_RENDER_TRANSCRIPT_LIGHT_VALUE_CAP`   | 400 | value-body char cap at `:light` |
| `SEON_RENDER_TRANSCRIPT_LIGHT_SOURCE_CAP`  | 300 | source char cap at `:light` |
| `SEON_RENDER_TRANSCRIPT_POINTER_SOURCE_CAP`| 160 | source char cap at `:pointer` |
| `SEON_RENDER_TRANSCRIPT_NARRATION_CAP`     | 240 | narration char cap in clipped tiers |
| `SEON_RENDER_TRANSCRIPT_SUMMARY_VERB_CAP`  | 60  | source-head char cap on the frozen `:summary` line |

Retained: `SEON_RENDER_TRANSCRIPT_TOKEN_CAP` (6,000) as a final hard ceiling on
the whole transcript (a safety net under the band gradient); `SEON_RENDER_RESULT_CAP`
(16,384) still caps the `:full` value body. A band size of 0 disables that tier;
all-zero bands degrade to "everything `:summary`" (the leanest possible).

These are pure render-knobs (no stored state) — the gradient is recomputed every
render, self-healing, derive-don't-store compliant (the tier is a fn of position,
never a stored `:evicted`/`:tier` datom).

## Core impl spec (file:line)

1. **`src/seon/config.cljs`** (after the `SEON_RENDER_*_CAP` family, ~L229–261):
   add the 8 `transcript-*-band` / `*-cap` `env-int` reads above (typed,
   `:malli/schema [:=> [:cat] :int]`, same shape as `eval-render-cap`).
2. **`src/seon/agent/ctx.cljs`**: add `shape-hint` (cheap type+size probe over a
   `:seon.eval/result-edn` string — REUSE the value-renderer's existing skeleton
   probe, do NOT render the value full to measure it). Extend
   `format-eval-row` (L567) with a `tier` arg (`:full | :light | :pointer |
   :summary`): `:full` = today; `:light`/`:pointer` clip source/stdout/narration
   to the new caps and (at `:pointer`) replace the value body with
   `;=> <shape-hint> ; result/<id> — deref to expand`; `:summary` returns the
   one-line index. Raise `stable-priority-max` (L1832) to 21 and seed a
   `:transcript-history` block at priority 21 (cached prefix) in
   `default-seed-blocks` (L1599), keeping `:transcript` (recent+readline) at 100.
3. **`src/seon/agent/ctx/transcript.cljs`**: add `assign-tiers` (discrete
   age-band → tier) + the frozen `render-tiered` dispatch (the prototype's two
   fns, promoted with `:malli/schema`). Split `transcript-block` (L424) into
   `transcript-history-block` (events older than `Σbands`, frozen `:summary`/the
   tail of `:pointer`) and `transcript-recent-block` (the `Σbands` newest events,
   gradient `:full`/`:light`/`:pointer` + masthead + readline). Thread the band
   sizes from `seon.config`. The HTML twin (L541) stays full (the inspector isn't
   token-bound).
4. **`src/seon/ai/anthropic.cljs`**: NO change required — the existing
   `split-context` 2-breakpoint machinery caches everything ≤ `stable-priority-max`
   automatically, so moving `:transcript-history` into that priority band is
   enough. (Optional later: spend a 3rd breakpoint to isolate history for
   partial-hit resilience.)

## Reproduce

`docs/prds/agent-fsm/research/tiered_transcript_proto.cljs` — paste into a live
pod CLJS session (`mcp__seon_cljs__eval`, session "default"); the `(comment …)`
block at the bottom regenerates every table and the byte-stability proof against
the live `root` transcript. Read-only; touches no Core ns.

## Validation owed (U lane, live DeepSeek drive)

The mechanism is measured; the WORDING + band sizes are presentation to A/B on
real drives: does the lean transcript still let an agent (1) resume after a
restart from the frozen summary index, (2) re-reference a `:pointer`
`result/<id>` when it needs the value, (3) steer from the `:full` tail? If agents
don't re-reference pointers, widen the `:light` band or strengthen the "deref to
expand" nudge. Drive on the shared pod with a long-lived agent before flipping the
default on.
