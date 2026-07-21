---
type: research
status: active
tags: [research, agent]
---

# KT1 — tokenizer envelope on the real exported rows (2026-07-12)

**TL;DR — the KT1 kill condition FIRED, on the encoder criterion only.**
Against the 214 real A1-exported rows (`data/tune/acme-2026-07-12.jsonl`),
**95.3% of full encoder inputs (context + `<tools>` + cards) exceed the
1024-token envelope** — the threshold is ~25%. Even context ALONE exceeds
1024 for 67.3% of rows; the compact-cards variant still overflows 89.3%.
The other two criteria PASS comfortably: median target 76 tokens (≪512;
only 5 rows / 2.3% over), target byte-fallback 2.6% (≪~35%). The
tokenizer itself performs roughly as B1 predicted (2.14–2.22 chars/token
vs the 2.45 anchor); what failed is the FIT of the current profile render
— dominated by the profile budget having been denominated in `chars/4`
estimated tokens (~700 est ≈ ~1,270 needle tokens) plus ~15% of every
context burned on byte-fallback for Unicode demarcation glyphs. Kill vs
re-cap-and-remeasure is an owner decision; the arithmetic for both is
below.

## Method

- **Tokenizer**: the stock needle SentencePiece BPE-8192 byte-fallback
  model (`src-needle/checkpoints/needle.model`), loaded via the existing
  `seon_needle.tokenizer/load_tokenizer`. Tokenizer only — no model
  weights, single process, negligible memory.
- **Data**: `data/tune/acme-2026-07-12.jsonl` — all 214 rows
  (`{"context","cards","target","meta"}`); per-row ingredients coverage
  already in `meta`, not recomputed.
- **Encoder assembly** mirrors the KT2b probe's
  `build_encoder_input` (itself run.py's layout — query + `<tools>` +
  tool defs): `tokens(context) + 1 (the <tools> separator id) +
  tokens(cards joined with "\n")`. The `"\n"` join is the exporter's own
  card-join convention (`autocomplete.cljs` coverage haystack). Measured
  **untruncated** — run.py truncates to 1024, so overflow means silent
  card/context loss at serve/train time, which is exactly what this test
  must expose.
- **Decoder**: `tokens(target)` against the 512 envelope (training
  appends one EOS on top; counts here exclude it — a +1 that changes
  nothing).
- **Byte-fallback**: fraction of token ids for which SentencePiece
  `IsByte` is true, pooled over all rows and per-row.
- **Compact-cards variant — exactly what was done**: each card is the
  exporter's `compact-fn-head` string
  `(defn name "doc line 1" {:malli/schema <spec>} [arglist] …)`. The
  compaction strips the `{:malli/schema …}` metadata map with a
  string-aware balanced-brace scan, leaving
  `(defn name "doc line 1" [arglist] …)` — i.e. name + docstring line-1
  + param names (the arglist carries the param names, including map
  destructures). 0 of 462 distinct cards failed to strip. Example:
  - full: `(defn reconcile! "Reconcile your OPEN plan against ONE edited
    whole-plan document." {:malli/schema [:=> [:cat ::reconcile-request]
    ::reconcile-response]} [{::keys [tree markdown], agent-id
    :seon.agent/id, :as request}] …)`
  - compact: `(defn reconcile! "Reconcile your OPEN plan against ONE
    edited whole-plan document." [{::keys [tree markdown], agent-id
    :seon.agent/id, :as request}] …)`
- **Script**: `src-needle/src/seon_needle/kt1_envelope.py`
  (`.venv/bin/python -m seon_needle.kt1_envelope`); raw JSON at
  `src-needle/data/kt1/kt1_envelope.json` (gitignored — this file quotes
  it). Percentiles are nearest-rank.

## Encoder side (1024-token envelope)

| input | min | p25 | p50 | p75 | max | >1024 | fraction |
|---|---|---|---|---|---|---|---|
| context alone | 415 | 974 | 1089 | 1237 | 1481 | 144/214 | **67.3%** |
| context + sep + full cards | 841 | 1339 | 1480 | 1630 | 2249 | 204/214 | **95.3%** |
| context + sep + compact cards | 648 | 1199 | 1330 | 1452 | 1839 | 191/214 | **89.3%** |
| cards alone (full) | 177 | 305 | 370 | 450 | 873 | — | — |
| cards alone (compact) | 111 | 182 | 219 | 257 | 598 | — | — |

Compaction saves a median 151 card tokens (370 → 219, −41%) — real but
nowhere near enough: the context itself is the bulk of the overflow.

## Decoder side (512-token envelope)

| | min | p25 | p50 | p75 | max | >512 | fraction |
|---|---|---|---|---|---|---|---|
| target | 7 | 30 | 76 | 153 | 959 | 5/214 | 2.3% |

The 5 over-budget targets (all in the copy-heavy kinds the design
already flags as large):

| needle tokens | chars | turn-id | head |
|---|---|---|---|
| 959 | 3176 | dKf-2607120057 | `(my.plan/reconcile! {:my.plan/tree …` |
| 810 | 2889 | NYI-2607120045 | `(my.plan/reconcile! {:my.plan/tree …` |
| 564 | 1922 | xuo-2607112332 | `(my.plan/plan! {:my.plan/title …` |
| 529 | 1115 | pbS-2607120043 | `(defn register-book-schema [] …` |
| 516 | 1081 | jjm-2607120052 | `(defn register-book-schema [] …` |

## Byte-fallback (SentencePiece `IsByte`)

| corpus | pooled | per-row [min p25 p50 p75 max] |
|---|---|---|
| targets | **2.6%** | 0 · 0 · 0.9% · 2.9% · 9.4% |
| contexts | **15.2%** | 8.8% · 14.1% · 15.4% · 16.7% · 21.2% |

Targets are nearly byte-fallback-free — the 8192-BPE vocabulary handles
Clojure form text fine. Contexts burn a mean **164.7 byte-fallback
tokens per row (~15% of every context)**, and ~77% of those bytes are
the rendered projection's Unicode glyphs (9,329 non-ASCII chars across
the corpus → 27,232 UTF-8 bytes; each 3-byte glyph costs 3 byte
tokens). Top offenders (count across all 214 contexts): `—` ×1782, `─`
×1712, `…` ×967, `⟹` ×700, `⟨`/`⟩` ×931, `┌`/`└` ×856, `☐` ×360,
`«`/`»` ×606, `▶` ×229, `✗` ×228, `⚠` ×214, `→` ×159, `✓` ×147 — the
demarcation brackets, transcript arrows, and status glyphs.

## Chars/token vs the B1 anchor

| corpus | chars/token | vs B1's 2.45 |
|---|---|---|
| contexts | 2.14 | worse (glyph byte-fallback; B1 sampled raw `.cljs` source + a heredoc, not a rendered projection) |
| targets | 2.22 | slightly worse, same ballpark |

B1's planning number was mildly optimistic for rendered contexts; the
tokenizer is behaving as characterized, not worse in kind.

## Headroom arithmetic (what WOULD fit)

- Context budget implied by the cards (1024 − 1 − cards tokens), per
  row: full cards **[150 573 653 718 846]**; compact cards
  **[425 766 802 841 912]**.
- Current context median is 1089 needle tokens → a **~40% context cut**
  is needed to fit with full cards at medians, **~27%** with compact
  cards.
- Root-cause split of the overflow:
  1. **Budget-unit miscalibration (dominant).** The A1 profile caps are
     denominated in seon `chars/4` estimated tokens (contexts capped
     ≤~700 est, actual 206–678 est). At the real 2.14 chars/token,
     678 est ≈ 2,712 chars ≈ **~1,265 needle tokens** — 1.86× the
     est-token number. The envelope was never going to hold a
     700-est-token render.
  2. **Glyph byte-fallback (~15%).** ASCII-izing the demarcation/status
     glyphs would recover roughly 110–165 tokens per context (each
     3-byte glyph → 3 tokens today, ~1 after). This is a CONTENT change
     to context generation — owner-gated under the frozen-context rule
     — and is insufficient alone.
- Combined path that reaches the envelope at medians: compact cards
  (−151) + glyph ASCII-ization (−~110–165, owner-gated) + a
  needle-token-denominated context cap near ~800 (a further ~10–15% cut
  after glyphs) ≈ median assembly ~1024. Whether an ~800-needle-token
  context still holds the INGREDIENTS is exactly the open KT2.5
  question — coverage mean is already only .64 at the current size, so
  cutting context risks trading envelope fit for ingredient loss.

## Verdict (against design.md §Measurement thresholds)

| criterion | threshold | measured | result |
|---|---|---|---|
| full encoder inputs > 1024 | kill if >~25% | **95.3%** (204/214); 89.3% with compact cards; 67.3% context alone | **KILL fires** |
| median target | kill if > 512 | **76** (2.3% of rows over) | pass |
| target byte-fallback | kill if >~35% | **2.6%** | pass |

**Per the pre-registered decision rule, KT1 KILLS "keep the tokenizer
as-is": the real projections+cards do not fit the trained 1024 encoder
envelope — not marginally, by ~1.4–1.5× at the median.** Stated equally
plainly: the failure is not tokenizer inefficiency (chars/token landed
near B1's prediction; targets are nearly byte-fallback-free and fit the
decoder with room to spare). It is an envelope-fit failure with two
quantified causes — the profile cap was set in `chars/4` units worth
~1.86× more needle tokens than assumed, and ~15% of every context is
byte-fallback glyph overhead. Since no retokenize fallback exists (tied
embeddings), the decision fork is the owner's:

1. **Kill the vehicle** as the rule prescribes, or
2. **Re-cap and remeasure**: compact cards (mechanical, allowed) + a
   needle-token-denominated profile cap (caps are the allowed profile
   mechanism) + the owner-gated glyph ASCII-ization, then re-run KT1 and
   KT2.5 on a re-export — accepting the risk that an ~800-token context
   cannot hold enough ingredients (coverage already .64).

No context-generation change was made; this report is the evidence
package for that decision.

## Honest caveats

- **The cards join is an approximation.** Cards were joined with `"\n"`
  (the exporter's haystack convention) after the `<tools>` separator;
  the B2 training/serving encoding of cards is not yet settled. Any
  realistic encoding is within a few tokens of this; it cannot move a
  95% overflow.
- **Untruncated totals.** run.py truncates the assembly to 1024; the
  overflow reported here manifests at train/serve time as silent card
  (then context) truncation — the failure mode KT2b already observed
  with 16-tool menus (165/169 busted).
- **Over-512 target count: 5, not 7.** A1's report said "7 rows over
  the 512 decoder budget" from the `chars/4` estimate convention; the
  real tokenizer count on this file is 5 (and `chars/4` on this exact
  file gives 2 — the 7 does not reproduce here under either measure).
  The real number is what matters and it passes either way.
- Target counts exclude the +1 training EOS and the SentencePiece
  dummy-prefix nuance (consistent across all measurements).
- The compaction is an approximation of a real compact-card design
  (it keeps the full arglist destructure; a production compaction might
  dedupe by namespace or drop destructure detail — savings would be
  somewhat larger than measured here).
- The 214 rows are the real distribution but plan-heavy acme scenarios;
  A2 synthetic/gold data renders through the SAME profile, so the
  envelope pressure applies to it identically.
