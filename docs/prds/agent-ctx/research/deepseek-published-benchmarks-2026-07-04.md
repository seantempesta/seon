---
type: research
status: active
tags: [research, agent]
---

# DeepSeek-V4-Pro published benchmarks vs our agentic dev numbers

Sanity-banding comparison for the eval lane: are our small-n, thinking-off,
agentic-harness dev numbers in a plausible range for this model?

## Model identity — what `deepseek-v4-pro` is

The API model name `deepseek-v4-pro` on api.deepseek.com maps directly to
**DeepSeek-V4-Pro** (marketing name unchanged), released 2026-04-24: a 1.6T
total / 49B activated MoE hybrid thinking/non-thinking model with 1M context,
open-weights on HuggingFace (`deepseek-ai/DeepSeek-V4-Pro`, MIT). From the
DeepSeek API change log ([api-docs.deepseek.com/news/news260424](https://api-docs.deepseek.com/news/news260424)):

> deepseek-v4-pro: 1.6T total / 49B active params … Both models support 1M
> context & dual modes (Thinking / Non-Thinking) … deepseek-chat &
> deepseek-reasoner will be fully retired and inaccessible after Jul 24th,
> 2026, 15:59 (UTC Time). (Currently routing to deepseek-v4-flash
> non-thinking/thinking)

The model supports **three reasoning effort modes** — "Non-Think", "Think
High", "Think Max" (technical report Table 2). "DeepSeek-V4-Pro-Max" in
report tables = the same model at Max reasoning effort, NOT a separate
checkpoint. Our runs send `{"thinking":{"type":"disabled"}}` → the official
**Non-Think** column is the right comparison.

Primary source: **DeepSeek-V4 technical report** — *DeepSeek-V4: Towards
Highly Efficient Million-Token Context Intelligence*,
[arxiv.org/pdf/2606.19348](https://arxiv.org/pdf/2606.19348) (Tables 1, 6, 7).
Official eval config for knowledge/reasoning benches (report §5.3.1):

> For reasoning and knowledge tasks, we set the temperature to 1.0 and the
> context window to 8K, 128K, and 384K tokens for the Non-think, High, and
> Max modes, respectively.

## Our harness (framing — NOT a leaderboard config)

Our numbers came from an **agentic harness**, not a bare completion
benchmark: the model runs inside a Seon agent loop (~19k-token REPL-centric
system context, multi-turn, tool/eval access), the answer is extracted from
its final reply per each bench's own answer contract. n = 10–15 dev
samples/bench (±~0.2 wide 95% CIs), temp 0.7, max_tokens 4096, thinking
explicitly disabled. Purpose: sanity-banding, not leaderboard claims.

## TL;DR comparison table

| Bench | Our dev number (n, CI note) | Published thinking-OFF | Published thinking-ON | Config delta | Source |
|---|---|---|---|---|---|
| GSM8K | .730 (n=15, k=3; 95% CI ≈ .51–.90) | **not reported** for V4 chat model (bench retired from frontier chat evals) | not reported | ours: agentic 0-shot extract, temp 0.7; closest official: V4-Pro-**Base** 8-shot EM **92.6** (base model, few-shot completion — weak comparator) | V4 report Table 1, arxiv.org/pdf/2606.19348 |
| MMLU (0-shot ours) | .800 (n=15; CI ≈ .60–.93) | **not reported** for V4 chat model; nearest official non-think: MMLU-Pro (EM) **82.9** (harder bench) | MMLU-Pro **87.1** (High) / **87.5** (Max) | ours plain-MMLU 0-shot agentic; official plain MMLU exists only for V4-Pro-Base, 5-shot EM **90.1** (perplexity-style) | V4 report Tables 1 & 7 |
| ARC-Challenge | .867 (n=15; CI ≈ .68–.97) | **not reported anywhere in the V4 report** (bench dropped) | not reported | closest DeepSeek number: **V3-Base** ARC-C 25-shot EM **95.3** (perplexity-based, Dec 2024 model — clearly-marked caveat) | V3 report, arxiv.org/pdf/2412.19437 |
| GPQA-Diamond | .700 (n=10; CI ≈ .39–.90) | **72.9** (Pass@1, Non-Think, temp 1.0) | **89.1** (High) / **90.1** (Max) | temp 1.0 vs our 0.7; bare Q&A vs our agentic loop; otherwise like-for-like mode | V4 report Table 7 |

## Per-bench detail

### GPQA-Diamond — the one clean, mode-matched comparison

V4 report **Table 7** ("Comparison among different sizes and modes of
DeepSeek-V4 series. 'Non-Think', 'High', and 'Max' denote reasoning
effort"), DeepSeek-V4-Pro columns:

> GPQA Diamond (Pass@1): Non-Think **72.9** | High **89.1** | Max **90.1**

(Column assignment cross-checked: the Pro-Max column of Table 7 reproduces
the DS-V4-Pro-Max column of Table 6 exactly — 87.5 / 57.9 / 84.4 / 90.1 /
37.7 / 93.5 / 3206 / 95.2 / 89.8 / 38.3 / 90.2.)

Official config: temp 1.0, 8K context in Non-Think mode, Pass@1. Our .700
(n=10) vs published 72.9 non-think is essentially exact.

### MMLU

- **No plain-MMLU number for the V4 chat model in any official source.**
  Official plain MMLU exists only for the base model: Table 1, "MMLU (EM),
  5-shot: DeepSeek-V4-Pro-Base **90.1**" (also MMLU-Redux 5-shot 90.8,
  MMLU-Pro 5-shot 73.5 for base).
- The chat-model knowledge row is MMLU-Pro (a strictly harder bench):
  Non-Think **82.9**, High **87.1**, Max **87.5** (Table 7). Since plain
  MMLU runs several points above MMLU-Pro for the same model, a plausible
  implied plain-MMLU non-think band is roughly high-80s.

### GSM8K

- Only reported for **base** models: Table 1, "GSM8K (EM), 8-shot:
  DeepSeek-V4-Pro-Base **92.6**" (V3.2-Base 91.1, V4-Flash-Base 90.8).
- **No chat-model GSM8K, in either thinking mode** — DeepSeek (like other
  frontier labs) has dropped GSM8K from instruct evals as saturated. No
  secondary leaderboard with a credible V4-Pro non-think GSM8K was found.

### ARC-Challenge

- **Absent from the entire V4 report** (grep-verified over the full text)
  and from the V4-Pro model card benchmarks.
- Closest DeepSeek datapoint, caveat clearly marked — a *different, older
  model*, base, few-shot, perplexity-scored: DeepSeek-**V3**-Base,
  "ARC-Challenge (EM), 25-shot: **95.3**" (ARC-Easy 98.9), V3 technical
  report Table 3, with the method note:

  > we adopt perplexity-based evaluation for datasets including … ARC-Easy,
  > ARC-Challenge …

  Perplexity-scored 25-shot base numbers are not comparable to a 0-shot
  generative agentic answer-extraction run; treat 95.3 as an upper anchor
  showing the bench is near-saturated for models of this class.

### Official word on thinking-off degradation

The V4 report quantifies it directly in Table 7 (V4-Pro, Non-Think → Max):

- GPQA-Diamond 72.9 → 90.1 (**−17.2 pts with thinking off**)
- MMLU-Pro 82.9 → 87.5 (−4.6)
- HLE 7.7 → 37.7; LiveCodeBench 56.8 → 93.5; HMMT — → 95.2

Narrative quote (§5.3.2):

> Notably, both models demonstrate improved results on knowledge benchmarks
> when allocated higher reasoning effort.

So: knowledge-recall benches (MMLU-family) degrade modestly with thinking
off (~5 pts); reasoning-heavy benches (GPQA, math, code) degrade severely
(15–40+ pts). No official statement gives GSM8K/ARC deltas.

## What this means for our numbers

- **gpqa_diamond .700 — squarely plausible.** Published non-think is 72.9;
  we measured .700 at n=10. This is the strongest sanity anchor because it's
  the only bench with an official thinking-off number, and we land on it.
  It also confirms our `thinking: disabled` flag is actually taking effect
  (thinking-on would predict ~.89–.90).
- **mmlu .800 — plausible, mildly low.** No official plain-MMLU chat
  number; implied band ~high-80s. .800 (12/15) is inside the CI. The gap,
  if real, is consistent with agentic-harness losses (answer-contract
  extraction, 19k system-context distraction) rather than a model anomaly.
- **arc_challenge .867 — plausible.** No V4 number exists at all; the bench
  is near-saturated (~95 for a 2024 base model). 13/15 at 0-shot generative
  in an agent loop is within noise of that band.
- **gsm8k .730 — the outlier; suspect the harness before the model.** The
  only published anchor (base, 8-shot, 92.6) plus general saturation of
  GSM8K suggests a bare non-think chat run should score ≥.90. .730 sits at
  the low edge of the CI (upper ≈ .90). Per the standing "0/low scores →
  suspect context first" rule, the likely culprits are the answer-extraction
  contract (boxed/format mismatches in a multi-turn agent reply) or the
  k=3 aggregation, not the model. Worth eyeballing the failing transcripts
  before drawing any capability conclusion.
- **Config deltas to keep in mind everywhere:** official evals use temp 1.0
  (we use 0.7 — minor), bare single-turn Q&A (we run a ~19k-token multi-turn
  agent loop — attention tax), and 8K non-think context (we cap output at
  4096 tokens — irrelevant for these benches without thinking).

Overall verdict: with thinking off, temp 0.7, and the agentic harness, our
dev numbers are in a plausible band for `deepseek-v4-pro` on 3 of 4 benches;
GSM8K alone warrants a transcript audit.

## Source quality notes

- **Official:** V4 technical report (arxiv.org/pdf/2606.19348, Tables 1/6/7)
  — the only source with explicit Non-Think vs Think splits; HuggingFace
  model card `deepseek-ai/DeepSeek-V4-Pro` (mirrors report tables); DeepSeek
  API change log (api-docs.deepseek.com/news/news260424) for the model-name
  mapping and dual-mode default.
- **Official, older model (caveated fallback):** DeepSeek-V3 technical
  report (arxiv.org/pdf/2412.19437) for ARC-Challenge; DeepSeek-V3.2 report
  (arxiv.org/pdf/2512.02556) checked — reports MMLU-Pro/GPQA thinking-mode
  only, no GSM8K/ARC chat splits.
- **Secondary (consulted, not load-bearing):** artificialanalysis.ai
  (Intelligence Index 44, throughput/pricing), llm-stats.com, OpenRouter,
  DeepInfra blog — none add thinking-off numbers for these four benches.
- **Gaps, stated honestly:** no official thinking-off (or any chat-model)
  numbers exist for GSM8K, plain MMLU, or ARC-Challenge on V4-Pro; those
  rows are anchored by base-model/older-model numbers and clearly marked.

## GSM8K outlier audit (2026-07-04) — every failing execution, classified

Per the "suspect the harness/context first" rule, EVERY failing execution in
`evals/runs/2026-07-03-first-dev-pass/gsm8k.jsonl` was audited against the
frozen dev samples (question + gold recovered from the pinned HF cache,
revision `cc7b047b…`) and the acme cluster's turn-capture blobs
(`data/clusters/acme/blobs/` — the run drove long-lived acme, so every
agent's rendered prompts/transcripts survive on disk). Run shape: 41
executions (15 samples × k=3, epoch 3 truncated at 11 by the acme OOM), 4
flakes excluded as-run (1 `solve_timeout`, 1 `run_error`, 2 `harness_error`),
37 scored, 27 pass → mean 27/37 = **.730**.

### Per-fail table (10 failing executions, 6 samples)

| Sample (failed epochs) | Gold | Our answer | Class | Evidence |
|---|---|---|---|---|
| `875bab2d` (e1,e2,e3) | 150 | **240** | (a) label noise | "Marin and his neighbor Nancy **each eat 4** apples a day … 30 days." Gold's own rationale computes "4 **+ 1** = 5 apples" per day → 150. Correct math is 2 × 4 × 30 = 240 — our answer, deterministic across all 3 epochs. |
| `eb422e6a` (e1,e2) | 360 | **1800** | (a) label noise | "stapling from 8:00 AM until **11:00 PM**" at 120 reports/hour. Gold's rationale reads it as 11 **AM** ("From 8am - 11am … 3 hours" → 360). 15 h × 120 = 1800 — our answer, both epochs. |
| `90a2b650` (e1,e2) | 170 | **140** | (a) ambiguous gold | "sold half … then sold another **1/4 of his land**": gold takes 1/4 of the *remaining* 40 sqm (10 sqm → $90 residual → 170); the model takes 1/4 of *his* (original 80 sqm) land (20 sqm → $60 residual → 140). Both readings defensible; the model was consistent across epochs. |
| `4237339d` (e1; e2,e3 pass) | 16 | *(empty)* | (c) behavioral miss | Transcript (blob `78ef7362…`): pure prose narration ending "Final answer:" with **nothing after it** — no forms, no `message/user`, no number ever produced over 3 turns; run closed `:no-forms`. |
| `5602e6ac` (e2; e1/e3 flaked) | 4 | *(empty)* | (c) delivery miss (math RIGHT) | Transcript (blob `7e483c06…`): the model derives x = 4 correctly and writes "ANSWER: 4" **repeatedly** — but as raw markdown parsed as code (READ ERRORs, "(17 - x) not defined" loops), never `(message/user …)`. 11 turns, 238 s, zero reply delivered. The completion really was empty — **not** a scoring-extraction bug. |
| `c7e0bdd1` (e2; e1 pass) | 10 | "ANSWER: 4 blue and 6 red shoe boxes" | (c) composition miss (math RIGHT) | Transcript (blob `94232109…`): computed 4 blue + 6 red correctly but replied the split, never the asked total (10). `match(numeric=True)` extracts 6. No extractor could yield 10 without doing arithmetic — not class (b). |

**Class (b) — answer-extraction/contract misses: ZERO.** Every empty answer
was genuinely an empty reply (verified in the transcripts); every non-empty
answer was extracted faithfully from the reply text. The
`catalog.swap_generate` contract fix (first dev pass) is doing its job — no
further extraction fix is warranted in `src-inspect-ai`, so none was made.

### Corrected-mean scenarios

| Scenario | Computation | Mean |
|---|---|---|
| As-run (ledger row, stands) | 27/37 | **.730** |
| Extraction fixed | no (b) exists → unchanged | .730 |
| Noisy/ambiguous golds EXCLUDED (drop `875bab2d`, `eb422e6a`, `90a2b650` — 7 fail executions) | 27/30 | **.900** |
| Noisy/ambiguous golds credited as correct (our answer is the right one under the natural reading) | 34/37 | **.919** |
| Arithmetic-only view | 0 of 10 failing executions contain wrong arithmetic | ~1.0 on math; the 3 residual fails are agentic reply-discipline |

Archived contrast runs (never appended to the ledger): the pre-contract-fix
`gsm8k-no-answer-contract.jsonl` scored .500 (22/44 — the format-contract
drop added fails on 5 samples the final run passes), and
`gsm8k-contaminated.jsonl` .400 (hot-reload contamination) — both strictly
worse and both explained by harness defects since fixed.

### Verdict — .730 is explained; no capability gap

**Yes.** The .730 is NOT a model math deficit: not one failing execution
shows wrong arithmetic. 7 of 10 failing executions are the three known
label-noise/ambiguous golds (on all of which our answer is correct under the
natural reading — two of the golds are outright wrong in their own
rationales); excluding or crediting them puts the mean at **.900–.919**,
squarely on the ≥.90 saturation anchor this doc predicted. The remaining 3
fails are the familiar agentic-harness shape — the model under-weights a
stated contract (deliver the answer via a user message / as the single
requested number) — the same pattern as the mmlu prose-answer and planning
discipline findings, and a context-content A/B lever, not a bench defect.
Per bench convention and the append-only ledger rule, the .730 row STANDS
unamended; future runs inherit the (unchanged) extraction and this audit as
the attribution baseline.

Provenance note (rode along with this audit): ledger rows now self-describe
the model — `scorecard.append_row` fills `model_id` / `model_thinking` /
`model_temperature` / `model_config_source` from the pod's documented
defaults (`deepseek-v4-pro`, thinking disabled, temp 0.7), explicitly marked
as NOT runtime-reported until the pod exposes its resolved provider row on
`/agents/run` (ask filed in [[coordination]]).
