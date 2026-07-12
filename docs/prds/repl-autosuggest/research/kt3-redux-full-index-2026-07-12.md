---
type: research
status: draft
tags: [research, agent]
---

# KT3-redux — the fair ceiling test (full index, exemplars, scoring v2)

**HALTED 2026-07-12 (owner stop order) — display defects; re-run pending
display fix v3.** Mid-matrix the owner ruled the v2 display under test
defective on three counts: (1) cards over-compacted — the
`{:malli/schema …}` map was stripped by the KT1 compaction, so **the arg
contract is missing from every card** (models could only infer arg keys
from arglist destructuring); (2) **stale deleted-fn cards** ride in the
index as distractors; (3) **glyph decoration** (`⟹`, `┌─`, `◀`, `…`)
taxes every model. Numbers below are measurements ON THAT DEFECTIVE
DISPLAY — kept as method + baseline evidence, not as the fair ceiling.
The re-run uses the v3 export (spec-bearing cards, ASCII render,
stale-card filter). Run state: `src-needle/data/kt3redux/STATUS.md`.

**Date:** 2026-07-12 · **Supersedes the headlines of**
[[kt3-signal-ceiling-2026-07-12.md]] / [[kt3b-coder-models-2026-07-12.md]]
methodologically (scoring v2 + controls); their predictions are rescored
below under the new lens · **Data:** `data/tune/acme-2026-07-12-v2.jsonl`
(213 rows, eval-only; 3 exemplar rows excluded from EVERY arm ⇒ all arms
score the identical 210 rows — Muse included, first-class full run, no
subsample) · **Scorer:** `src-needle/scripts/kt3_score.clj` extended in
place (bb/edamame — a real reader, no regexes on code; legacy array mode
byte-stable) · **Driver:** `src-needle/scripts/kt3_redux.py` · **Raw
runs:** `src-needle/data/kt3redux/` (gitignored; preds/scored/
scored-next/summary per arm, `verify.json`, `STATUS.md`) · **Spend:**
≤ $3.6 nominal (DeepSeek $0.80 across 5 arms + think-smoke, 60-84%
prefix-cache hit; Muse $1.96; locals $0).

## TL;DR (partial matrix, defective display — read as method + baselines)

- **Index-presence gap CLOSED by construction:** full 168-fn index +
  each row's non-index cards ⇒ every one of the 213 rows' target heads
  is grounded in cards alone (`verify.json` flagged 0; the v2 report's
  3/144 misses are gone).
- **On this display, the full index did NOT beat the tiny curated menu —
  for any model, under any of four layouts.** DeepSeek: row-cards .252
  vs full-index best .202 (sandwich); the curated 4-card menu carries
  selection signal (nf head-match .404 rowcards vs .319-.335 full-index)
  that 168 flat cards dilute. The 1.5B-instruct few-shot arm outright
  COLLAPSED under the index (.071 vs .270 on its 4-card rescore —
  exemplar-parroting + 42 temp-0 repetition loops). The owner's NIAH
  point stands as the open question: with spec-less, glyph-taxed,
  stale-polluted cards this measures OUR display, not their attention —
  hence the halt and v3 re-run rather than a negative conclusion.
- **At high coverage the full index shines exactly where v0 bets:**
  DeepSeek full-index @cov≥.75 register mean-credit **.85** (rowcards
  .55; KT3's old ceiling .45), plan .42, query .44. The index hurts
  low/mid-coverage rows (noise amplification), not the copy-kinds.
- **The owner's exemplar fix works on the base arm** (in-setup delta,
  identical display/scoring): cont-bare .067 → cont-few **.112** (+67%
  rel.; no-call .70 → .39; nf head .084 → .160). Still floor-noise vs
  the instruct channel — but the format fix measurably moves a raw base
  model, confirming the direction for needle's continuation-shaped
  serving.
- **Scoring v2 found real signal v1 threw away:** the `⟹`
  reply-boundary reclean alone took the base cont arm .042 → .112
  (parse .52 → .79) — models emitted one good form then fabricated
  display grammar that killed the read. Decomposition: the dominant
  failure is **wrong-fn selection + missing emission, NOT
  hallucination** (hallucinated heads ≤ 3% with the index in context;
  spurious ids are ~97% grounded-in-context wrong picks, not invented
  strings).
- **Layout sensitivity is real but second-order on this display**
  (DeepSeek: plain .197 · sandwich .202 · structured .183 · retrieve
  .177) — no layout closes the card-set gap, but retrieve-then-emit
  reshapes credit INTO the copy-kinds (register .485, plan .423 — the
  best plan number measured on any arm) at a precision cost. Re-test
  on v3.
- **Qwen3.5-2B verified real and ready** (2B dense hybrid GDN 3:1, 262k
  ctx, unified VL, Apache-2.0; both variants converted to 4-bit MLX and
  generation-smoked) — never ran: stop order landed first.
- **DeepSeek thinking arm verified mechanically** (3-row smoke: content
  non-empty, reasoning separate, 1-3k reasoning tok/row, 14-38 s/row) —
  full run deferred to v3; the arm stores FULL raw reasoning traces
  (owner: candidate data-generation recipe for the finetune set).

## The three owner corrections (what changed vs KT3/KT3b)

1. **Full function index in every arm's context.** All 168 agent-surface
   fns (`src-needle/data/fn-index.json`, the KT2b dump) rendered as
   compact cards — name + docstring line-1 + arglists (fully-qualified
   destructuring keys from the program graph) — grouped under
   `; ── <ns> ──` headers in ONE cards bracket (~5.8k est / 7.4k
   DeepSeek-tokenized), PLUS the row's own cards that are NOT index fns
   (the "extras": 542 across 208/213 rows) under a
   `; ── this session's additional functions ──` divider, so no row
   loses a card the 4-card runs had. **Verification (the v2 report's
   3/144 misses):** with index + extras, every row's target-bundle
   heads ground in the cards alone — `verify.json` `flagged_rows: []`,
   `context_only_rows: []` (grounding = index ∪ extras ∪ the bundle's
   own defn names ∪ clojure.core ∪ special forms/interop — all
   computed, no name lists).
2. **Base models get in-document exemplars** (`cont-few`): ONE document —
   the index bracket, then k=3 complete exemplar episodes (pre-transcript
   sections + extras cards + open transcript + the turn's ACTUAL forms
   appended in transcript position + the close line), then the live
   row's doc ending open after the last transcript line. Exemplars:
   seed 42 over coverage ≥ .75 AND `bundle_forms ≥ 2` (the floor is the
   computed fix for KT3b's two trivial `(in-ns …)` exemplars) → rows
   **[20, 55, 108]**, excluded from scoring in EVERY arm. `cont-bare` =
   same document minus episodes (the delta control). Generation runs to
   the model's own reply boundary — the first `^;;;` structure line or
   `^⟹` result line (both reserved by the transcript grammar for
   non-agent content; verified: no target contains either) — or the
   1024 cap.
3. **Scoring v2 — find signal everywhere** (extended `kt3_score.clj`;
   legacy array mode byte-stable): PRIMARY = set-union best-match F1
   over the turn's FULL form set (`target_bundle`) — per target call,
   the unused same-fn prediction with max arg-key overlap,
   order-insensitive; credit/F1 formulas unchanged from KT3. SECONDARY
   = the same predictions vs the next-form `target` + the
   first-substantive head-match lens. Every prediction decomposes:
   right-args · wrong-arg-KEYS (missing/extra by name, or
   ns-qualification mismatch on a shared name; `::`-auto-resolved keys
   are wildcards) · wrong-arg-VALUES (shared keys, printed values
   differ) · wrong-fn (grounded non-target) · hallucinated-fn (head
   absent from index ∪ cards ∪ context ∪ the prediction's own defs ∪
   clojure.core ∪ specials/interop) · missing. Confusion pairs =
   leftover targets × leftover predictions zipped in reading order. Id
   lens = id-shaped string VALUES from parsed forms; spurious splits
   grounded-in-context vs invented.

Documented framing deviations from KT3: the fixed index block renders
FIRST (DeepSeek prefix-cached 84% of prompt tokens); the instruction
gains ONE granularity sentence ("Emit ALL the forms the turn needs…" —
the primary lens is the whole turn; KT3 measured silent under-emission);
local few-shot carries the index once in the system message. Layout
sweep (owner NIAH direction): `sandwich` (one-line computed situation
summary before the index, full situation after, instruction last),
`structured` (one-line ns TOC + `; ## ns (n fns)` headers), `retrieve`
(two-step: a `;; relevant: fn…` comment line then forms — the comment is
free scratch; the reader scores only forms).

## Arms × completion state (stop order 2026-07-12)

| # | arm | state |
|---|---|---|
| 1 | deepseek/instr + rowcards control + sandwich/structured/retrieve layouts | **complete** (5 × 210) |
| 1t | deepseek/instr-think (thinking ON, 12k cap, full reasoning saved) | 3-row smoke only |
| 2 | muse/instr (full index) | **complete** (210, first-class) |
| 3 | qwen25c-1.5b-instruct instr-few + instr-zero (full index) | **complete** |
| 4 | qwen25c-1.5b-base cont-few / cont-bare | complete / **partial 165/210** |
| 5 | qwen35-0.8b-base cont-few/bare | never started |
| 6 | qwen35-2b-base cont / qwen35-2b instr-few | never started (checkpoints ready) |
| — | 1.5b instr-few-rowcards (collapse attribution), 1.5b layout sweep, muse best-layout, muse thinking | never started |
| — | needle-extended | *pending* (extension-train lane) |

Sibling-lane anchors (the LoRA lane's `lora_rescore_4card.py`, reusing
this driver): the OLD KT3/KT3b 4-card predictions rescored under scoring
v2 — `deepseek/instr-4card`, `1.5b-instruct/instr-{few,zero}-4card`
(+ their `qwen25c-1.5b-lora/instr-zero-4card`). Old-instruction preds,
new lens: the like-for-like card-set baseline.

## Qwen3.5 family verification (owner question: does a ~2B exist?)

**Yes — [Qwen/Qwen3.5-2B](https://huggingface.co/Qwen/Qwen3.5-2B) +
[Qwen/Qwen3.5-2B-Base](https://huggingface.co/Qwen/Qwen3.5-2B-Base)**
(HF cards + `config.json`, fetched 2026-07-12; the small tier is
0.8B / 2B / 4B — no "9B" surfaced; above sit 27B dense and the MoE
35B-A3B / 122B-A10B).

- **Architecture:** 2B dense (no MoE fields), hidden 2048, 24 layers,
  3:1 `linear_attention` (Gated DeltaNet, 16 heads × 128) :
  `full_attention` (gated, 8 Q / 2 KV × 256) — the same hybrid as the
  0.8B. Vocab 248,320, tied embeddings, 1-layer MTP head.
- **Context:** 262,144 native ("extensible up to 1,010,000").
- **Modalities — the owner's "possibly vision" CONFIRMED:**
  `Qwen3_5ForConditionalGeneration` with a full `vision_config`
  (24-deep tower, image/video token ids) — image-text-to-text unified
  VL, early-fusion trained (the 0.8B config carries the same shape —
  the whole small tier is VL).
- **Thinking:** supported, non-thinking by default; the card warns the
  2B is "more prone to entering thinking loops" than larger siblings.
- **Release/license:** Feb 2026 card citation (family announced
  2026-03-02); Apache 2.0.
- **MLX:** no community quant existed; converted locally
  (`mlx_lm convert -q --q-bits 4`, mlx_lm 0.31.3 native `qwen3_5`) →
  `src-needle/checkpoints/qwen3.5-2b-base-4bit` + `qwen3.5-2b-4bit`
  (~1.0 GB each, ~4.5 bits/weight), text tower generation-smoked.
  Gotcha: `convert` needs the COMPLETE snapshot — a weights-only HF
  cache dies at save time (`IncompleteSnapshotError`); `hf download
  <repo>` first.

## Results (defective-display measurements — baselines for the v3 re-run)

### Headline — bundle set-union F1 primary; next-form secondary

All rows n=210 (identical set) unless noted; `-4card` = sibling rescores
of OLD predictions (old instruction, old 4-card menus) under scoring v2.

| tag | arm | parse | no-call | **bundle useful** | recall | precision | substantive | nf useful | nf head |
|---|---|---|---|---|---|---|---|---|---|
| deepseek | instr (full index) | 1.000 | .010 | **.197** | .238 | .222 | .219 | .183 | .319 |
| deepseek | instr-sandwich | .986 | .024 | **.202** | .252 | .227 | .224 | .170 | .324 |
| deepseek | instr-structured | .990 | .019 | **.183** | .222 | .210 | .201 | .167 | .293 |
| deepseek | instr-retrieve | 1.000 | .019 | **.177** | .261 | .175 | .200 | .143 | .335 |
| deepseek | instr-rowcards (4 cards, new instr) | .976 | .029 | **.252** | .318 | .289 | .287 | .233 | **.404** |
| deepseek | instr-4card (rescore, old instr) | .986 | .014 | **.260** | .241 | .347 | .290 | .254 | .309 |
| muse | instr (full index) | .981 | .024 | **.176** | .240 | .165 | .185 | .152 | .309 |
| qwen25c-1.5b-instruct | instr-few (full index) | .910 | .110 | **.071** | — | — | .080 | .063 | .106 |
| qwen25c-1.5b-instruct | instr-few-4card (rescore, n=207) | .879 | .126 | **.270** | — | — | .239 | .259 | .326 |
| qwen25c-1.5b-instruct | instr-zero (full index) | .924 | .119 | **.161** | — | — | .158 | .149 | .160 |
| qwen25c-1.5b-instruct | instr-zero-4card (rescore) | .895 | .219 | **.152** | — | — | .151 | .146 | .176 |
| qwen25c-1.5b-base | cont-few (exemplars + index) | .786 | .386 | **.112** | .114 | .148 | .125 | .102 | .160 |
| qwen25c-1.5b-base | cont-bare (index, no exemplars; n=165) | .842 | .703 | **.067** | .059 | .088 | .078 | .058 | .084 |
| deepseek | instr-think | 3-row mechanical smoke — full run deferred to v3 | | | | | | | |
| qwen35-0.8b · qwen35-2b (both framings) · needle-extended | — | *pending v3 re-run* | | | | | | | |

(Recall/precision dashes: those summaries predate the field's addition;
per-row values exist in the scored files — regenerate on the v3 pass
rather than churn halted evidence.)

Readings, display caveat attached:

- **Card-set effect dominates layout effect** (DeepSeek): rowcards .252
  vs full-index {.177–.202} across four layouts; recall carries the
  selection story — rowcards .318 vs full-index .222–.261. The new
  granularity instruction itself is ~free on F1 (rowcards .252 vs
  old-instruction 4card .260) while trebling emission (793 vs 308
  calls) — trading precision for recall (+.077) and nf-head (+.095 →
  .404).
- **The 1.5B few-shot collapse is the sharpest cliff:** .270 (4 cards)
  → .071 (168 cards). Mechanism, from the decomposition:
  exemplar-parroting (`message/user "ready"` emitted on ~every row —
  two of the three exemplar bundles contain it) + 42/210 temp-0
  repetition loops (7,518 predicted calls; precision annihilated).
  Zero-shot 1.5B is FLAT under the index (.161 vs .152) — the collapse
  is the index × few-shot interaction, not the index alone. The
  attribution control (instr-few-rowcards: same exemplars, 4 cards)
  never ran — first item for v3.
- **Muse (first-class full run, owner's quality pick):** .176 under the
  full index — below DeepSeek's .197 on identical rows/layout; register
  credit .000 (Muse answers with `plan/tree`-style orientation probes —
  its confusion column below). Its best-layout and higher-thinking arms
  never ran (v3).

### Coverage tranches (bundle useful mean; KT3's gradient reproduces)

| tag/arm | <.25 (40) | .25–.75 (64) | ≥.75 (106) | Pearson |
|---|---|---|---|---|
| deepseek/instr | .078 | .149 | .272 | +.258 |
| deepseek/instr-sandwich | .074 | .162 | .275 | +.277 |
| deepseek/instr-retrieve | .110 | .158 | .214 | +.146 |
| deepseek/instr-rowcards | .103 | .216 | .331 | +.297 |
| deepseek/instr-4card (rescore) | .105 | .183 | .365 | +.291 |
| muse/instr | .093 | .181 | .204 | +.132 |
| 1.5b-instruct/instr-zero | .028 | .102 | .246 | +.274 |
| 1.5b-instruct/instr-few | .013 | .018 | .126 | +.232 |
| 1.5b-base/cont-few | .046 | .087 | .152 | +.162 |
| 1.5b-base/cont-bare (n=165) | .028 | .046 | .095 | +.113 |

Fourth consecutive confirmation: sub-.25-coverage rows are noise for
every vehicle and every card regime — the context-gap fix lane
(owner-gated) travels unchanged through all of this.

### Per form-kind mean credit — all rows (@ coverage ≥ .75)

| tag/arm | register | query | plan | transact | defn | other |
|---|---|---|---|---|---|---|
| deepseek/instr | .397 (**.850**) | .298 (.438) | .285 (.420) | .188 (.333) | .097 (.111) | .089 (.196) |
| deepseek/instr-rowcards | .250 (.550) | **.492 (.682)** | .285 (.479) | .250 (.333) | .161 (.167) | .191 (.339) |
| deepseek/instr-retrieve | **.485** (.650) | .211 (.292) | **.423 (.601)** | .250 (.333) | .000 | .136 (.214) |
| deepseek/instr-sandwich | .412 | .297 | .313 | .250 | .032 | .096 |
| muse/instr | .000 | .333 (.375) | .276 (.223) | .125 (.333) | .000 | .129 (.229) |
| 1.5b-instruct/instr-zero | .059 | .152 | .087 | .000 | .000 | .108 |
| 1.5b-base/cont-few | .059 (.200) | .279 (.370) | .037 | .000 | .129 (.167) | .119 (.226) |

The v0 copy-kind bet survives the index at high coverage — DeepSeek's
register **.85 @≥.75** is the best number measured in any KT3-series
arm (the granularity instruction fixed the multi-form register bundles
KT3b flagged as truncated). `retrieve` moves plan to .423/.601 — the
best plan numbers measured — by spending a scratch line on selection
first. defn ≈ 0 everywhere: fourth confirmation of the v0 defn
exclusion.

### Decomposition — what actually goes wrong

Target outcomes (of 481 target calls) · prediction outcomes (of the
arm's emitted calls; wrong-fn includes nested core heads like `do`/`map`
inside emitted forms — uniform across arms):

| tag/arm | right-args | wrong-arg-keys | wrong-arg-values | missing | wrong-fn | hallucinated | spurious ids grounded/invented |
|---|---|---|---|---|---|---|---|
| deepseek/instr | 27 (6%) | 21 (4%) | 48 (10%) | 385 (80%) | 710 (86%) | 21 (3%) | 80/2 |
| deepseek/instr-rowcards | 40 (8%) | 23 (5%) | 61 (13%) | 357 (74%) | 617 (78%) | 52 (7%) | 83/2 |
| deepseek/instr-retrieve | 37 (8%) | 26 (5%) | 51 (11%) | 367 (76%) | 1094 (89%) | 18 (1%) | 130/6 |
| muse/instr | 30 (6%) | 29 (6%) | 41 (9%) | 381 (79%) | 808 (88%) | 5 (1%) | 57/1 |
| 1.5b-instruct/instr-zero | 18 (4%) | 10 (2%) | 25 (5%) | 428 (89%) | 303 (84%) | 4 (1%) | 45/3 |
| 1.5b-instruct/instr-few | 12 (2%) | 0 | 16 (3%) | 453 (94%) | 7488 (100%) | 2 | 96/18 |
| 1.5b-base/cont-few | 13 (3%) | 11 (2%) | 28 (6%) | 429 (89%) | 616 (92%) | 0 | 20/1 |

- **Hallucination is a solved problem WITH the index in context** (≤3%
  of emitted heads frontier, ≤1% small models; base cont-bare WITHOUT
  exemplars: 19% — the index alone doesn't ground a base model, index +
  exemplars does: 0%). The failure mass is `missing` (74-94%) and
  `wrong-fn` selection — this task is selection + emission, not
  vocabulary.
- **Id fidelity:** spurious ids are ~97% GROUNDED — real ids copied
  from the context, wrong entity — not invented strings. DeepSeek
  full-index recalls 47/146 target ids (.32; KT3's old lens .25),
  retrieve 59/146 (.40). The serving gate stays
  validate-against-live-db (grounded-but-wrong passes any lexical
  check). KT3b's exemplar-leakage reproduces small: 20 of instr-few's
  114 spurious ids are exemplar ids verbatim.

### The "what to tune" tables

Per-fn arg-key error clusters (matched pairs, deepseek/instr) — these
are the DISPLAY-FIX targets, since v2 cards carried no schema:

| fn | n matched | dominant errors |
|---|---|---|
| `db/query` | 21 | `:query` VALUE wrong 13/21 (different `:where` graphs — KT3's row-44 generosity, now quantified); `:find`/`:where` values 3 each |
| `schema/register!` | 14 | keys fine; failures live in VALUE shape (map-style vs per-attr registration) |
| `plan/active!` / `plan/done!` | 8 + 8 | `:id` VALUE wrong 3/8 each (grounded-but-wrong plan ids — the id-gate case) |
| `plan/document` | 7 | `:root?` MISSING 3/7 (a flag not inferable from a spec-less card) |
| `plan/plan!` | 5 | missing `:children`/`:expect`/`:goal`/`:pace`; **invented `:steps` key 3/5** — exactly the defect a spec-bearing card kills |
| `plan/step!` | 4 | missing `:expect` 3/4; `:title`/`:description` values paraphrased |

Fn-confusion matrix, top pairs — target → predicted where the target
went unmatched (`/x` = bare head):

| deepseek/instr | n | muse/instr | n | 1.5b instr-zero | n |
|---|---|---|---|---|---|
| `/in-ns` → `plan/active!` | 11 | `/in-ns` → `/require` | 13 | `/in-ns` → `/require` | 12 |
| `/in-ns` → `plan/plan!` | 9 | `/in-ns` → `plan/tree` | 12 | `db/query` → `search/grep` | 6 |
| `/defn` → `schema/register!` | 5 | `db/query` → `plan/tree` | 8 | `/in-ns` → `db/installed-schema` | 5 |
| `/map` → `schema/register!` | 5 | `plan/done!` → `plan/tree` | 7 | `db/query` → `db/pull` | 4 |
| `plan/done!` → `schema/register!` | 4 | `plan/done!` → `plan/list-open` | 6 | `plan/step!` → `plan/active!` | 3 |
| `plan/tree` → `/def` | 4 | `schema/register!` → `plan/tree` | 6 | `/defn` → `plan/active!` | 3 |

Readings: about half the frontier "confusions" are ns-move boilerplate
the models skip (a serving layer prepends `in-ns`/`require` — cheap,
and substantive-F1 already nets it out). The REAL confusions are
behavioral stances, not lookalike names: Muse PROBES (`plan/tree`,
`list-open`) where the target acts; the 1.5B reaches for lookup fns
(`search/grep`, `db/pull`, `installed-schema`) where the target
queries; DeepSeek jumps to plan bookkeeping where the target moves
namespaces. Within-`my.plan` done!/active!/step! confusion is the
id-and-lifecycle ambiguity the `:suggest` serving gate must arbitrate
against live plan state.

### Base-with-exemplars vs base-bare (the owner's format fix, in-setup)

Identical display/scorer/rows (bare = the halted 165-row partial):

| arm | parse | no-call | bundle useful | nf head |
|---|---|---|---|---|
| 1.5b-base cont-bare | .842 | .703 | .067 | .084 |
| 1.5b-base cont-few | .786 | .386 | **.112** (+67% rel.) | **.160** (+90% rel.) |

In-document exemplars nearly halve no-call refusals and double
next-form head-match — "show it what you want" is real on a raw base
model. It does NOT reach the 4-card instruct channel (.270) on this
display. Display-grammar mimicry persists but is now mechanically
contained: the `⟹` reply-boundary rule (cont arms only, uniform,
target-verified safe) moved cont-few .042 → .112 by cutting fabricated
result lines off otherwise-correct forms.

### Latency (per suggestion; API arms include network, 4-6-way concurrency)

| arm | wall p50 | wall p90 | prompt tok p50 | prefill tok/s | decode tok/s | peak RAM | cap hits |
|---|---|---|---|---|---|---|---|
| deepseek/instr | 2.51s | 4.83s | 7,367 | — | — | — | 0 |
| deepseek/instr-rowcards | 2.19s | 4.62s | 996 | — | — | — | 0 |
| deepseek/instr-think (smoke) | 27.3s | 38.2s | 7,433 | — | — | — | 0 |
| muse/instr | 2.23s | 3.47s | 6,696 | — | — | — | 0 |
| 1.5b-instruct/instr-zero | 2.84s | 4.58s | 7,039 | 3.8k | 45 | 1.97 GB | 2 |
| 1.5b-instruct/instr-few | 3.55s | 40.9s | 8,949 | 3.6k | 48 | 1.97 GB | 42 |
| 1.5b-base/cont-few | 11.2s | 16.9s | 8,853 | 4.3k | 78 | 1.92 GB | 129 |

The full index costs the 1.5B its KT3b latency edge (0.28-0.92s →
2.8-11s): +7k prompt tokens at ~4k tok/s prefill (machine under heavy
multi-lane load — KT3b measured 10-12k solo) plus repetition-loop
tails. The thinking arm's 27s p50 already brackets its serving
question: whatever v3 accuracy it buys, it is a data-generation recipe,
not a suggestion tier. Needle's 128k tok/s prefill thesis is untouched.

## Verdict — halted; what stands, what v3 must answer

Halted on the owner's display ruling before arms 5-6 and the
layout/thinking completions; nothing here is the fair ceiling. What
STANDS regardless of display: the verification machinery (0 ungrounded
rows), scoring v2 + decomposition (the tune tables are precisely the
signal the v3 display fix targets — invented `:steps`, missing
`:root?`/`:expect` are spec-less-card defects by construction), the
exemplar direction on base models, the granularity-instruction finding
(recall +.08, nf-head +.095 for free), Qwen3.5-2B verification + ready
checkpoints, and the reply-boundary cleanup rule. The v3 re-run's
must-answer list: (1) full-index vs curated menu on spec-bearing ASCII
cards — the NIAH question, now unconfounded; (2) the 1.5B few-shot
collapse attribution (instr-few-rowcards control); (3) arms 5-6
(0.8B/2B, both framings); (4) DeepSeek long-thinking + Muse high-effort
as data-generation candidates; (5) the layout sweep on the fixed
display.

## Limitations

- Everything measured on the defective v2 display (spec-less cards,
  stale distractor cards, glyph tax) — the halt reason; read numbers as
  method baselines, not ceilings.
- Single greedy sample per row (temp 0), as in KT3/KT3b.
- `-4card` rescore rows are OLD predictions (old instruction, old
  exemplars [7,19,161], KT3b chat framing) — comparisons against them
  bundle those deltas; the rowcards control isolates the card set for
  DeepSeek only (new instruction, n=210).
- The 1.5B few-shot collapse confounds index-in-system-message with the
  new exemplar pick [20,55,108] (two of three bundles contain
  `message/user "ready"` — the parroted form). The attribution control
  never ran.
- cont-bare is a 165/210 partial (halt landed mid-arm); the cont-few
  delta is direction-reliable, the exact number will move a little.
- Wrong-fn counts include nested core heads (`do`, `map`, …) inside
  emitted forms — KT3's call-collection rule, inflating the wrong-fn
  denominator uniformly across arms.
- API latency includes network under concurrency; local prefill was
  measured on a heavily shared machine (other lanes training).

## Appendix — verbatim outputs (KT3/KT3b's appendix rows)

Three arms per row: `deepseek-instr` (full index), `1.5b-instruct
instr-few` (full index — the collapse arm), `1.5b-base cont-few`
(exemplar-continuation). Cleaned text as scored; ⟨…⟩ marks display
truncation only. Raw files: `src-needle/data/kt3redux/preds-*.jsonl`.

### Row 0 — coverage 0.33, bundle 3 forms (in-ns · require · `plan/reconcile!`)

**deepseek-instr (useful 0.00):** invents a `plan/plan!` tree with an
invented `:my.plan/steps` key — the spec-less-card signature:

```text
(my.plan/plan! {:my.plan/title "Expense tracker groundwork"
                :my.plan/steps [{:my.plan/title "Design a structured shape for expenses …"
                                 :my.plan/expect "one probe expense stores and reads back intact"} …]})
```

**1.5b instr-few (useful 0.00):** exemplar parroting:

```text
(seon.agent.message/user "ready")
(my.plan/step! {:my.plan/title "Design expense record shape", :my.plan/parent [:my.plan/id "XWs-2607112118"]})
(seon.agent.message/user "ready")
…
```

**1.5b cont-few (useful 0.00):** `(seon.agent.message/user "work")`

### Row 9 — coverage 0.18, 7 forms (5 × `schema/register!` `:my.expense/*`)

**deepseek-instr (useful 0.50):** right fn, right per-attr style, wrong
ns prefix, plan-bookkeeping preamble:

```text
(plan/active! {:my.plan/id "wZw-2607112119"})
(schema/register! :my.kb.expense/date :inst)
(schema/register! :my.kb.expense/amount-cents :int)
(schema/register! :my.kb.expense/category :string)
(schema/register! :my.kb.expense/note :string)
…
```

**1.5b instr-few (useful 0.17):** `message/user` parrot + `done!`.
**1.5b cont-few (useful 0.00):** invents a `schema/defn` head.

### Row 23 — coverage 0.62, two `plan/step!` forms

**deepseek-instr (useful 0.31):** one `step!`, paraphrased `:title`,
missing `:expect` — the plan/step! tune-table row in one example.
**instr-few 0.24** (step! + parrot) · **cont-few 0.00**.

### Row 24 — coverage 1.0, `plan/done!` + `register!` `:expense` map

**deepseek-instr (useful 0.67):** the map-style register reproduced
near-exactly (`:expense/date` vs the target's bare `:date` — key-name
mismatch counted); `done!` dropped. **1.5b instr-few (useful 0.29,
2048-tok cap):** correct register, then temp-0 loops on it verbatim to
the cap. **1.5b cont-few (useful 0.57):** register + done! both present
— the best small-model row; the exemplars taught the bundle shape.

### Row 28 — coverage 0.66, `plan/list-open`

All three arms 0.00 — DeepSeek emits `plan/tree` (probe-for-act
confusion), instr-few parrots, cont-few emits a fabricated event line
(cut at boundary → no-call).

### Row 44 — coverage 0.66, one `db/query` (find plan ids/titles)

**deepseek-instr (useful 1.00 — the documented key-only generosity):**

```text
(seon.db/query {:query '[:find ?e ?id ?title :where [?e :my.plan/id ?id] [?e :my.plan/title ?title]]})
```

(the closest-to-target semantics any arm produced — still missing the
agent join). **instr-few 0.00** (grep parrot) · **cont-few 1.00** (query
copied from a context echo — same generosity).

### Row 71 — coverage 0.07, 7-form register+transact probe bundle

**deepseek-instr (useful 0.15):** a plausible 3-register + transact
bundle with INVENTED `:my.team.member/*` attrs — the coverage-gap
signature (KT3's row-9 pattern). Both 1.5B arms 0.00.

### Row 86 — coverage 0.0, `(my.agent…/lowest-offset-person)`

**deepseek-instr (useful 1.00):** `(lowest-offset-person)` — the
agent-defined fn found via extras/context. **instr-few 0.17**
(done!-parrot) · **cont-few 1.00:** emitted the correct call then a
fabricated `⟹ #‹fn›` result line — the boundary rule cut the
fabrication and kept the form (pre-rule this row scored 0.00).

### Row 163 — coverage 0.8, `plan/active!` + `lifecycle/complete`

**deepseek-instr (useful 1.00):** both forms — but `:my.plan/id
"Pfw-2607112043"`, a GROUNDED-but-wrong id (the id-gate case; KT3 saw
the same defect on this row). **instr-few 0.40:** active! with wrong id
`Nxt-…` + parrot padding. **cont-few 0.00.**

### Row 179 — coverage 0.0, junk-lineage row (`register-book-schema` defn)

DeepSeek 0.00 (`plan/document`), instr-few 0.00 (repetition loop to the
cap — same as KT3b), cont-few 0.00.
