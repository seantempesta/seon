---
type: research
status: active
tags: [research, agent]
---

# KT3b — open small coder models on the KT3 eval (alternative-vehicle probe)

**Date:** 2026-07-12 · **Question:** does a stock small OPEN coder model,
run locally at $0, already clear the useful-match band that
needle-after-training is aiming for — and what does that imply for the
two-tier (needle speed tier + coder quality tier) vs vehicle-swap
question? · **Data:** the exact KT3 set —
`data/tune/acme-2026-07-12.jsonl` (214 held-out A1 rows) · **Scorer:**
`src-needle/scripts/kt3_score.clj` (KT3's bb/edamame mechanical scorer,
byte-identical, no new scoring invented) · **Driver:**
`src-needle/scripts/kt3b_coder_models.py` (mlx_lm 0.31.3, temp 0, single
process, ONE model loaded at a time) · **Raw runs:**
`src-needle/data/kt3b/` (gitignored; preds/scored/summary per
model×arm) · **Spend:** $0 (all local MLX, 128 GB machine).

Comparison anchors (KT3, [[kt3-signal-ceiling-2026-07-12.md]], recomputed
from its raw `scored-deepseek.json` where noted): DeepSeek-v4-pro useful
**0.261** overall / **0.291** substantive / **0.362** at coverage ≥.75;
next-form head-match **0.307** under the KT3b lens (KT3's own ad hoc
computation reported .313); per-kind credit at ≥.75: register .450 ·
query .436 · plan .323. Muse-spark-1.1 (50-row) 0.318.

## TL;DR

- **In needle's own serving shape — base model, raw transcript
  continuation, no instruction — every stock small coder fails hard:
  useful 0.083–0.121** (vs frontier 0.261), 42–63% of predictions
  contain no call at all, and the models mostly mimic the transcript's
  DISPLAY grammar (fake `⟹` results, fake event lines, comment spam)
  instead of emitting a next form. Scale does not rescue it: the 3B is
  WORSE than the 1.5B (parse .762 — it reproduces display markers more
  faithfully). A stock coder is NOT a drop-in vehicle for the
  continuation-shaped channel.
- **The same 1.5B model, instruct variant + k=3 fixed exemplars, TIES
  the frontier on identical rows: useful 0.265 vs DeepSeek 0.261 (n=211),
  0.383 vs 0.366 at coverage ≥.75, next-form head-match 0.319 vs
  0.307** — at 0.92 s/suggestion, locally, $0. KT3's coverage gradient
  reproduces in every arm (Pearson +.16–.37): the ceiling is the
  projection, not model capacity — KT3's verdict independently
  re-confirmed from the opposite end of the model-size axis.
- The tie has caveats (§Results): substantive-only 0.234 vs 0.291 (part
  of the tie is ns-move boilerplate credit the frontier never bothers
  emitting), parse .877 vs .986, and id-value fidelity is dirtier —
  value recall parity (.240 vs .250) but **4× the spurious ids (51 vs
  13)**, most leaked verbatim from the exemplars.
- **Band answer: a stock coder does NOT clear the band the projection
  itself doesn't support — nothing does (KT3's point) — but
  stock-1.5B+exemplars reaches the SAME coverage-gated ceiling as the
  frontier without any training spend.** That number (.265/.383) is now
  the strongest measured $0 baseline for the design's B3 ship gate
  ("$0 baseline loses"): needle-after-training must beat it.
- Latency is NOT the differentiator at turn cadence: 0.28 s (instr-zero)
  / 0.92 s (instr-few, uncached prefix) per suggestion. Needle's real
  remaining moats are prefill throughput (128k tok/s vs 5–35k — the
  per-keystroke re-render regime) and RAM (~50 MB vs 1.1–2.9 GB).
  Tradeoffs laid out in §Verdict; the two-tier vs vehicle-swap call is
  the owner's.

## Owner research pass — which open models verifiably trained on Clojure

Verification from primary sources (fetched 2026-07-12; the widely
circulated "92-language list with Clojure" for Qwen2.5-Coder is real but
its provenance needed care — the QwenLM GitHub repo was RENAMED, see
below):

| model | primary source | Clojure confirmed | volume (if published) |
|---|---|---|---|
| **Qwen2.5-Coder** | pre-rename `QwenLM/Qwen2.5-Coder` README §Basic Information: "✨ Supporting 92 coding languages" + explicit list — preserved verbatim in the pre-rename fork [huggingface/Qwen2.5-Coder](https://github.com/huggingface/Qwen2.5-Coder) (the original URL now 301-redirects to `QwenLM/Qwen3-Coder`); the tech report (2409.12186 §3.1.1) states the 92 count but does not enumerate | **YES** — `'clojure'` in the 92 list (also `common-lisp`, `emacs-lisp`, `racket`, `scheme`) | not published per-language |
| **Qwen3-Coder** | [QwenLM/Qwen3-Coder README](https://github.com/QwenLM/Qwen3-Coder): "Supporting 358 coding languages" + list | YES — `'Clojure'` (plus `'edn'` and `'wisp'`) | not published; **no small variant exists** (smallest is 30B-A3B MoE — out of scope) |
| **DeepSeek-Coder** | [deepseek-ai/DeepSeek-Coder README](https://github.com/deepseek-ai/DeepSeek-Coder) §Supported Programming Languages | YES — `'clojure'` (grep-confirmed on the raw README) | not published per-language |
| **The Stack v1 → StarCoder1** | [bigcode/the-stack](https://huggingface.co/datasets/bigcode/the-stack) file tree: `data/clojure/train-0000{0,1}-of-00002.parquet`; the StarCoder1 training subset [bigcode/starcoderdata](https://huggingface.co/datasets/bigcode/starcoderdata) ships `clojure/train-00000-of-00001.parquet` | YES | v1 dedup **≈ 416 MB** parquet (206.5 + 209.9 MB shards); starcoderdata training shard **≈ 182 MB** |
| **The Stack v2 → StarCoder2** | [bigcode/the-stack-v2](https://huggingface.co/datasets/bigcode/the-stack-v2) dataset-card config list (HF API): `{"config_name": "Clojure", "data_files": "data/Clojure/*.parquet"}` | YES | per-language stats live in the gated `language_stats.csv`; the StarCoder2 paper's per-language appendix tables don't extract from the PDF — volume not verifiable un-gated |
| **IBM Granite Code** | [Granite Code paper (2405.04324)](https://arxiv.org/abs/2405.04324) Appendix A (116 languages, ar5iv render) | YES — "…Clojure, CMake, COBOL, CoffeeScript, Common-Lisp…" | not published per-language |
| **Codestral** | [Mistral blog](https://mistral.ai/news/codestral/): "80+ languages", full list NOT published | **UNVERIFIED** (also 22B + non-open MNPL license — out of scope regardless) | — |

So the Qwen2.5-Coder matrix already covers a verified-Clojure model
(citation above), and **StarCoder2-3B** (The Stack v2,
presence-verified at the dataset level) is in the matrix as the second
verified pick — `mlx-community/starcoder2-3b-4bit`. No community
Clojure-finetuned completion model of relevant size surfaced in a
bounded search.

### Qwen3.5-0.8B (owner addition) — verified real, added to the matrix

- **Exists:** [Qwen/Qwen3.5-0.8B](https://huggingface.co/Qwen/Qwen3.5-0.8B)
  + [Qwen/Qwen3.5-0.8B-Base](https://huggingface.co/Qwen/Qwen3.5-0.8B-Base),
  the Qwen3.5 small-model series (0.8B/2B/4B/9B) announced 2026-03-02
  ([Qwen on X](https://x.com/Alibaba_Qwen/status/2028460046510965160));
  card citation dated February 2026. Positioning per the announcement:
  "0.8B / 2B → tiny, fast, great for edge device". Postdates the
  assistant knowledge cutoff; verified as described.
- **Context:** 262,144 tokens native.
- **FIM/completion:** NO FIM sentinels documented — chat/thinking modes
  only; the Base variant is a plain completion model (used here in the
  `cont` arm).
- **Languages:** "201 languages and dialects" is a NATURAL-language
  claim; no code-language list published; **Clojure not named**.
- **Architecture:** hybrid — Gated DeltaNet blocks interleaved with
  gated attention (`6 × (3×(GDN→FFN) → 1×(GatedAttn→FFN))`). Relevant
  to the needle thesis: linear-attention prefill economics.
- **MLX:** no mlx-community BASE quant existed; converted locally with
  `mlx_lm convert -q --q-bits 4` from `Qwen/Qwen3.5-0.8B-Base` →
  `src-needle/checkpoints/qwen3.5-0.8b-base-4bit` (424 MB, 4.508
  bits/weight; mlx_lm 0.31.3 has native `qwen3_5` support).

## Method

Everything held from KT3: the 214 rows, the scorer, temp 0, one greedy
sample per row. The arms (owner-corrected mid-task: **base-continuation
is the primary arm** — it matches needle's serving shape; instruct is
the anchor):

- **`cont` (primary, all base models)** — continuation framing. The
  prompt is the row's context with the cards bracket (KT3's exact
  bracket text) inserted immediately BEFORE the `;;; ┌─ transcript ─`
  section, and the transcript's closing bracket line removed, so the
  prompt ends exactly where the agent's next form is appended: after
  the last transcript line + one `\n`. Nothing else is appended — no
  instruction, no chat template, no cue text. Generation stops when the
  first top-level bracketed form balances (string/comment/char-literal-
  aware delimiter scanner — a STOP condition only; all parsing and
  scoring still go through edamame), or when a `^;;;` structure line
  appears (the transcript grammar reserves those for section brackets /
  event lines — a model writing one has ended its reply), or at the
  512-token cap (the design's decoder budget; targets p50 34 tok — a
  model that hasn't balanced one form in 512 tokens scores 0 either
  way). The prediction is the text up to the first balanced form within
  the reply region — the next-form target shape from KT3.
- **`instr-zero` (anchor)** — Qwen2.5-Coder-1.5B-Instruct, KT3's exact
  prompt (context + cards bracket + the same terse instruction), one
  user message through the chat template, full generation (2048 cap),
  KT3's fence-unwrap cleanup. Anchors directly against DeepSeek/Muse.
- **`instr-few`** — same instruct model, k=3 fixed exemplars as
  multi-turn chat (user = KT3 prompt, assistant = target forms
  verbatim), then the query row. Exemplars: seeded pick (seed 42) from
  coverage ≥.75 rows → **row ids [7, 19, 161]**, excluded from scoring
  in this arm (n=211). Exemplar targets: rows 7 and 19 are single
  `(in-ns 'my.kb)` turns; row 161 is a 4-call `my.plan`
  active!/done!/step!/list-open bundle.
- **`fim`** — Qwen2.5-Coder-1.5B **base** FIM sentinels:
  `<|fim_prefix|>{cont prompt}<|fim_suffix|><|fim_middle|>` (prefix =
  context+cards, empty suffix), same first-form stop.

Mechanical cleanup (all documented in the driver): instruct arms get
KT3's fence-unwrap + `<think>`-strip; cont/fim arms are truncated at the
first `^;;;` structure line then cut at the first balanced form;
detokenizer artifacts (`<|im_end|>` …) stripped everywhere. A prediction
that fails edamame scores 0 (KT3 rule, unchanged).

Models (all 4-bit, mlx-community checkpoints unless noted):
Qwen2.5-Coder-{0.5B, 1.5B, 3B} base + 1.5B-Instruct, StarCoder2-3B,
Qwen3.5-0.8B-Base (local conversion). One model loaded at a time, one
process per model (exit = unload), machine kept responsive throughout.

## Results

### Headline (all n=214 except instr-few n=211; KT3 anchors first)

| model | arm | parse | no-call preds | useful (F1) | substantive | nf head-match | useful ≥.5 |
|---|---|---|---|---|---|---|---|
| **DeepSeek-v4-pro (KT3)** | frontier zero | .986 | — | **.261** | .291 | .307 | .266 |
| **Muse-spark-1.1 (KT3, n=50)** | frontier zero | .98 | — | **.318** | .331 | — | .32 |
| Qwen3.5-0.8B-Base | cont | .883 | .631 | **.110** | .100 | .125 | .112 |
| Qwen2.5-Coder-0.5B | cont | .897 | .607 | **.085** | .091 | .115 | .084 |
| Qwen2.5-Coder-1.5B | cont | .953 | .551 | **.115** | .129 | .151 | .117 |
| Qwen2.5-Coder-1.5B | fim | .963 | .486 | **.120** | .137 | .156 | .121 |
| Qwen2.5-Coder-3B | cont | .762 | .556 | **.083** | .090 | .094 | .079 |
| StarCoder2-3B | cont | .944 | .421 | **.121** | .140 | .151 | .117 |
| Qwen2.5-Coder-1.5B-Instruct | instr-zero | .897 | .215 | **.152** | .152 | .177 | .150 |
| Qwen2.5-Coder-1.5B-Instruct | instr-few | .877 | .137 | **.265** | .234 | .319 | .223 |

Like-for-like on the identical 211 rows (exemplars removed from BOTH):
DeepSeek .261, instr-few **.265**, instr-zero .149. At coverage ≥.75 on
that subset: DeepSeek .366 (n=107), instr-few **.383**. Emission counts:
targets 484 calls; DeepSeek emitted 304, instr-zero 225, **instr-few
476** — the exemplars fixed frontier-style under-emission by teaching
the whole-turn target granularity (including the in-ns/require
boilerplate the frontier never bothers with; that boilerplate credit is
part of the overall-F1 tie, which is why substantive .234 still trails
DeepSeek's .291 by ~20%).

Base-continuation reading: the primary arm FAILS across every model.
42–63% of cleaned predictions contain no call; parse and no-call rates
barely improve with scale, and the 3B is worse than the 1.5B — inspection
shows it reproduces the transcript's display grammar (fake `⟹` result
lines, `⟨⚠ TRUNCATED …⟩` glyphs, fabricated `;;; ◀ from user` events,
comment spam) MORE faithfully, not less (appendix rows 9-sc/44-sc). FIM ≈
continuation (.120 vs .115) — the sentinels don't change what the model
wants to write. The instruct+chat channel, not raw continuation, is
where stock small coders function on this projection.

### Coverage tranches (useful mean; KT3's gradient reproduces everywhere)

| model/arm | <.25 (40) | .25–.75 (64) | ≥.75 (110) | Pearson |
|---|---|---|---|---|
| DeepSeek (KT3) | .105 | .183 | .362 | +.287 |
| Qwen3.5-0.8B-Base cont | .025 | .069 | .164 | +.237 |
| Qwen2.5-Coder-0.5B cont | .005 | .069 | .123 | +.203 |
| Qwen2.5-Coder-1.5B cont | .033 | .093 | .158 | +.198 |
| Qwen2.5-Coder-1.5B fim | .029 | .118 | .154 | +.190 |
| Qwen2.5-Coder-3B cont | .025 | .071 | .112 | +.158 |
| StarCoder2-3B cont | .029 | .114 | .159 | +.161 |
| 1.5B-Instruct instr-zero | .020 | .086 | .239 | +.295 |
| 1.5B-Instruct instr-few | .075 | .187 | **.383** (107) | +.373 |

The sub-.25-coverage rows are noise for every vehicle (.005–.075) —
KT2.5/KT3's context-gap finding holds independent of model family,
size, quantization, and framing. This is the strongest cross-model
confirmation yet that the projection, not the trainee, is the defect.

### Per form-kind mean credit (all rows; @coverage ≥.75 in parens)

| model/arm | register | query | plan | transact | defn | other |
|---|---|---|---|---|---|---|
| DeepSeek (KT3) | .162 (.450) | .304 (.436) | .209 (.323) | .125 (.333) | .065 (.115) | .189 (.403) |
| Qwen3.5-0.8B-Base cont | .088 (.300) | .155 (.212) | .012 (.020) | .000 | .161 (.222) | .069 (.093) |
| Qwen2.5-Coder-0.5B cont | .118 (.400) | .169 (.242) | .000 | .000 | .226 (.333) | .081 (.093) |
| Qwen2.5-Coder-1.5B cont | .088 (.300) | .212 (.303) | .020 | .125 (.333) | .194 (.278) | .082 (.126) |
| Qwen2.5-Coder-1.5B fim | .118 (.300) | .212 (.303) | .026 | .250 (.667) | .226 (.278) | .076 (.093) |
| Qwen2.5-Coder-3B cont | .000 | .162 (.197) | .043 | .000 | .065 | .037 |
| StarCoder2-3B cont | .059 (.200) | **.324 (.419)** | .022 | .125 (.333) | .129 (.167) | .064 (.093) |
| 1.5B-Instruct instr-zero | .029 (.100) | .167 (.207) | .094 (.109) | .000 | .000 | .111 (.230) |
| 1.5B-Instruct instr-few | .059 (.100) | **.302 (.419)** | **.305 (.372)** | .250 (.667) | .000 | .123 (.262) |

Readings:

- **query is the small models' strongest kind** — StarCoder2-3B base
  continuation and 1.5B-Instruct few-shot both hit .419 at high
  coverage, statistically at DeepSeek's .436. Datalog-query-by-copy
  works even at 1.5–3B.
- **plan needs the exemplars**: base models ~0, instr-zero .109,
  instr-few .372 at hi coverage — ABOVE DeepSeek's .323 (exemplar 161
  is a plan bundle; the `my.plan` idiom is few-shot-teachable).
- **register is the glaring reversal**: the frontier's best kind (.450)
  is the instruct arms' worst (.100). Register targets are per-attribute
  multi-form bundles (5–7 `schema/register!` calls); the 1.5B truncates
  to one or two even with exemplars. The v0 copy-kind bet (plan/
  transact/register) holds for a small model only on plan+transact.
- **defn ≈ 0 everywhere** (except trivial base-model def-echoes) —
  third independent confirmation of the v0 defn exclusion.
- transact n=8 remains too thin to read (the .667s are 3 rows).

### Id-value fidelity (KT3's separate lens, reproduced)

Pattern `"XXX-26……"` over rows whose targets carry such ids; the KT3b
lens reproduces KT3's DeepSeek value recall exactly (.250):

| arm | rows w/ ids | target ids | value recall | spurious ids emitted |
|---|---|---|---|---|
| DeepSeek (KT3) | 81 | 148 | .250 | 13 |
| 1.5B-Instruct instr-zero | 81 | 148 | .216 | 17 |
| 1.5B-Instruct instr-few | 80 | 146 | .240 | **51** |

(Spurious = predicted ids absent from that row's target; KT3's report
quoted 79 for DeepSeek under a broader definition — the numbers here are
one definition applied uniformly.) Value recall is at parity, but the
few-shot arm emits 4× the spurious ids — many leaked VERBATIM from
exemplar 161 (`mmR-2607112016`, `kpc-2607112000` appear in predictions
for unrelated rows — appendix rows 0 and 24). Key-only useful-match does
not see this; any serving path needs the design's
validate-ids-against-the-live-db gate, and exemplar ids would need
rewriting/stripping.

### Latency (per suggestion, measured wall on this machine; needle B1 anchor)

| model/arm | wall p50 | wall p90 | prompt tok p50 | prefill tok/s | gen tok p50 | decode tok/s | peak RAM | cap hits |
|---|---|---|---|---|---|---|---|---|
| needle 26M (B1, projected) | ~0.25s | — | 1024 | ~128,000 | ~34 | 428 | ~0.05 GB | — |
| Qwen3.5-0.8B-Base cont | 0.38s | 1.25s | 1056 | 19,894 | 92 | 522 | 1.81 GB | 94 |
| Qwen2.5-Coder-0.5B cont | 0.99s | 1.32s | 1034 | 35,247 | 512 | 542 | 1.13 GB | 109 |
| Qwen2.5-Coder-1.5B cont | 0.65s | 3.12s | 1034 | 10,677 | 86 | 276 | 1.92 GB | 78 |
| Qwen2.5-Coder-1.5B fim | 0.35s | 1.88s | 1037 | 11,267 | 49 | 315 | 1.92 GB | 60 |
| Qwen2.5-Coder-3B cont | 0.62s | 3.20s | 1034 | 4,958 | 57 | 179 | 2.71 GB | 74 |
| StarCoder2-3B cont | 0.71s | 3.47s | 1130 | 4,234 | 61 | 161 | 2.86 GB | 59 |
| 1.5B-Instruct instr-zero | **0.28s** | 0.54s | 1094 | 12,006 | 43 | 321 | 1.89 GB | 1 |
| 1.5B-Instruct instr-few | 0.92s | 1.50s | 4579 | 9,604 | 95 | 274 | 1.89 GB | 9 |

All sizes/speeds in tokens (model-native tokenizers). "Cap hits" = rows
that hit the token cap (512 cont/fim, 2048 instr) — for cont arms these
are the never-emitted-a-form rows; for instr-few the 9 are temp-0
repetition loops (appendix row 179: 8.2 s burning 2048 tokens). The
instr-few prompt is 4.6k tokens of which ~3.5k is the FIXED exemplar
prefix — mlx_lm prompt-caching would amortize it to ≈ instr-zero's
0.28 s; not measured here, noted as available headroom. The Qwen3.5
hybrid's prefill (19.9k tok/s at 0.8B) is ~2× the same-size transformer
per parameter — the GDN linear-attention economics are real, though
still 6× off needle's 128k.

## Verdict (honest; the decision is the owner's)

**Does a stock small coder already clear the useful-match band
needle-after-training is aiming for?** Two answers, one per framing:

1. **In needle's serving shape (raw continuation, no instruction): no,
   and it's not close.** 0.083–0.121 useful, half the predictions
   contain no form, and scale makes the display-grammar mimicry worse,
   not better. A stock base coder cannot be dropped behind the
   continuation channel.
2. **In the instruct+few-shot shape: it reaches the projection's
   ceiling — the same ceiling the frontier reaches.** 1.5B-Instruct +
   3 exemplars = .265 overall / .383 @≥.75 vs DeepSeek's .261/.366 on
   identical rows, at 0.92 s (≈0.3 s with prefix caching) and $0. Per
   KT3's gate that ceiling is still BELOW the <~30–40% band on the full
   set, because half the projections lack the ingredients — the
   context-gap report remains the blocking fix lane for EVERY vehicle.
   Nothing about KT3b reopens training spend before it.

**What this does to the two-tier vs vehicle-swap question** (tradeoffs
stated, not decided):

- **For vehicle-swap** (coder replaces needle): frontier-equal quality
  today with zero training; a verified-Clojure pretrain (§verification);
  robust tokenizer (no byte-fallback problem — no KT1-style envelope
  crisis); instruction + few-shot channels needle lacks; quality scales
  with the SAME context-gap fixes. Costs: 1.1–2.9 GB resident vs
  needle's ~50 MB; prefill 5–35k tok/s vs 128k — at turn-boundary
  cadence irrelevant, in the per-keystroke re-render regime decisive;
  no constrained-decoding path wired in mlx_lm serving today (the
  design treats Clojure-grammar constrained decoding as load-bearing —
  the 12.3% instruct parse failures and the id leakage are exactly what
  it would gate); register-kind multi-form bundles regress hard (.100
  vs .450).
- **For two-tier** (needle speed tier + coder quality tier): needle
  keeps the per-keystroke typeahead lane its prefill economics uniquely
  serve; the coder serves turn-boundary suggestions where 0.3–0.9 s is
  fine and its knowledge shows (query/plan at frontier level). The
  canonical JSONL already feeds multiple consumers by design (roadmap
  item 8 — the DG finetune), so a coder tier is a third consumer, not a
  new pipeline. Cost: two serving stacks to operate.
- **Either way, one immediate use is free:** the 1.5B+exemplars number
  IS the design's "$0 baseline" for the B3 ship gate.
  Needle-after-training now has a concrete bar to beat — **.265 overall
  / .383 at ≥.75 coverage / .319 next-form** — and if it can't, the
  coder tier is sitting right there.
- A stock coder could also plausibly serve as the **KT4
  oracle-injection surrogate** (cheap, local, instruction-followable) —
  noted, not scoped here.

**Qwen3.5-0.8B**: verified real (2026-03 release, hybrid GDN+attention,
262k context), converted and measured — .110 cont, mid-pack among bases
at a third the 3B's size, with 2× per-param prefill. Nothing on this
projection argues for it over Qwen2.5-Coder-1.5B; its interest is the
linear-attention prefill curve if the quality tier ever needs longer
contexts. No small Qwen3-Coder exists (smallest is 30B-A3B).

## Limitations

- Single greedy sample per row (temp 0), same as KT3 — pass@k would sit
  higher, and would not move the coverage gradient.
- All models 4-bit quantized (mlx-community / local convert). Quant
  costs some points vs fp16; direction unmeasured here. The Qwen3.5
  conversion is local (4.508 bits/weight), not an official release.
- The instr-few exemplar set is ONE seeded draw (seed 42, ids 7/19/161)
  — no variance estimate over exemplar choice; two of the three
  exemplars are trivial `(in-ns …)` turns, so the arm is if anything
  under-tuned. Exemplars share the acme scenario distribution with the
  eval rows — the .265 says "few-shot on in-distribution transcripts",
  not "generalizes to novel domains".
- cont/fim predictions are single-form by construction (the first-form
  stop) while targets are whole-turn bundles — recall is mechanically
  capped; the next-form head-match column is the control lens (bases
  fail on it too: .09–.16 vs .307 frontier).
- The `^;;;`-line reply boundary and 512-token cont cap are KT3b
  additions (documented in the driver); KT3's instruct arms needed
  neither. Both only bound the reply region — parsing/scoring are
  byte-identical to KT3.
- KT3's "79 spurious ids" used a broader definition than the id lens
  here; within this report one definition is applied uniformly to all
  three arms (and reproduces KT3's .250 DeepSeek value recall exactly).

## Appendix — 10 verbatim outputs

Rows chosen to match KT3's appendix where possible (same row ids). Eight
from the headline arm (1.5B-Instruct instr-few), two from the primary
base-continuation arm (StarCoder2-3B). All predictions are the cleaned
text scored by the scorer, verbatim; long outputs truncated where marked.

### Row 0 — coverage 0.33, instr-few useful 0.00

**Target:** `(in-ns …)` + `(require …)` + `(plan/reconcile! {:my.plan/markdown "# Expense tracker groundwork…"})`

**1.5B-Instruct few-shot (1.39 s, 237 tok):**

```text
(my.plan/plan! {:title "expense-tracker" 
               :my.plan/goal "An expense tracker that stores and retrieves expenses across sessions." 
               :children [{:my.plan/title "design-expense-schema" :my.plan/description "Define the structure for storing expenses."}
                          {:my.plan/title "store-expenses" :my.plan/description "Store three seed expenses in the expense tracker."}
                          {:my.plan/title "report-spent-per-category" :my.plan/description "Calculate the total spent per category."}]})
(my.plan/active! {:my.plan/id "mmR-2607112016"})
(my.plan/done! {:my.plan/id "mmR-2607112016"})
(my.plan/step! {:my.plan/title "design-expense-schema" 
                :description "Define the structure for storing expenses." 
                :my.plan/parent "mmR-2607112016"})
(my.plan/list-open {:seon.agent/id "kpc-2607112000"})
```

(The exemplar-leakage signature: `mmR-2607112016` / `kpc-2607112000` are
exemplar 161's ids, copied into an unrelated row.)

### Row 9 — coverage 0.18, instr-few useful 0.25

**Target:** `(in-ns 'my.kb)` + `(require …)` + five `(schema/register! :my.expense/… …)` forms

**1.5B-Instruct few-shot (0.51 s, 11 tok):**

```text
(in-ns 'my.kb)
```

(Where DeepSeek invented an `:expense/row` map shape from the same
missing-ingredient projection, the small model emits only the safe
boilerplate — under-commitment instead of confabulation.)

### Row 9 — StarCoder2-3B base continuation, useful 0.00

```text
⟹ ✗ READ ERROR — [line 1, col 3]
```

(The base-continuation failure mode in one line: the model continues the
transcript's DISPLAY grammar — a fabricated result marker — instead of
writing a form.)

### Row 23 — coverage 0.62, instr-few useful 0.61

**Target:** two `(my.plan/step! {…})` forms (seed expenses, totals per category)

**1.5B-Instruct few-shot (0.85 s, 61 tok):**

```text
(my.plan/step! {:my.plan/title "register expense schema" 
                :description "Register a new schema for :expense."
                :expect "A registered schema for :expense." 
                :from "mmR-2607112152"})
```

### Row 24 — coverage 1.0, instr-few useful 0.57

**Target:** `(my.plan/done! {:my.plan/id "QTQ-2607112152"})` + `(seon.schema/register! :expense [:map …])`

**1.5B-Instruct few-shot (1.12 s, 163 tok):**

```text
(seon.schema/register! :expense
  [:map [::date :string]
   [::amount :int]
   [::category :string]
   [::note {:optional true}]])
(my.plan/active! {:my.plan/id "QTQ-2607112152"})
(my.plan/done! {:my.plan/id "QTQ-2607112152"})
(my.plan/step! {:my.plan/title "capture-feed" 
                :description "Capture the current feed data and state." 
                :my.plan/parent "QTQ-2607112152"})
(my.plan/list-open {:seon.agent/id "kpc-2607112000"})
```

(Coverage-1.0 register copied near-exactly — same as DeepSeek's row 24 —
then exemplar-bundle padding appended, including the leaked
`kpc-2607112000`.)

### Row 28 — coverage 0.66, instr-few useful 0.00

**Target:** `(my.plan/list-open {:seon.agent/id "oOF-2607112331"})`

**1.5B-Instruct few-shot (0.71 s, 21 tok):**

```text
(my.agent.IHk-2607112331/root)
```

### Row 44 — coverage 0.66, instr-few useful 0.00

**Target:** `(seon.db/query {:query '[:find ?id ?title :where …]})`

**1.5B-Instruct few-shot (0.63 s, 24 tok):**

```text
(seon.agent.search/grep {:pattern "register!.*book" :max-results 20})
```

### Row 44 — StarCoder2-3B base continuation, useful 1.00

```text
; Inspect current inventory to see what book schema exists
(seon.db/query {:query '[:find?k :where [?e :seon.schema/key?k]] :args []})
```

(The key-only-scoring generosity example, exactly as in KT3's row 44:
head + arg-keys match, different `:where` graph — and note the fused
`?k` tokens a serving gate would have to catch.)

### Row 163 — coverage 0.8, instr-few useful 0.29

**Target:** `(my.plan/active! {:my.plan/id "MxL-2607112043"})` + `(seon.agent.lifecycle/complete "planned")`

**1.5B-Instruct few-shot (0.99 s, 92 tok):**

```text
(my.plan/active! {:my.plan/id "Nxt-2607112043"})
(my.plan/step! {:my.plan/title "seed" :my.plan/ref "seed"})
(my.plan/step! {:my.plan/title "render" :my.plan/ref "render"})
(my.plan/step! {:my.plan/title "verify" :my.plan/ref "verify"})
(my.plan/complete "planned")
```

(The wrong-id signature — `Nxt-` for `MxL-` — the same defect KT3 found
in DeepSeek's row 163.)

### Row 179 — coverage 0.0, instr-few useful 0.00

**Target:** the junk-target row (opens with `(which is incorrect)`).

**1.5B-Instruct few-shot (8.22 s, 2048 tok — hit the cap; excerpt, the
pattern repeats verbatim to the cap):**

```text
(my.plan/step! {:my.plan/title "store-five-books" 
                :my.plan/parent "ew1-2607120450"})
(my.plan/active! {:my.plan/id "ew1-2607120450"})
(my.plan/done! {:my.plan/id "ew1-2607120450"})
(my.plan/step! {:my.plan/title "store-five-books" 
                :my.plan/parent "ew1-2607120450"})
…
```

(The temp-0 repetition loop — the small-model failure mode the frontier
doesn't have; 9/211 few-shot rows hit it. A serving cap at the design's
512-token decoder budget contains it.)
