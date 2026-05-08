---
title: Qwen3.6-35B-A3B — official model card benchmark tables
source-url: https://huggingface.co/Qwen/Qwen3.6-35B-A3B
secondary-source: https://qwen.ai/blog?id=qwen3.6-35b-a3b
retrieved: 2026-05-08
fetched-via: WebFetch
---

# Qwen3.6-35B-A3B — published benchmarks

Released April 2026. Multimodal (text + image + video) MoE: 35B total / 3B
active. 256K native context, extensible to ~1M with YaRN. Hybrid Gated-DeltaNet
+ Gated-Attention layers (10× alternating block).

## Architecture (verbatim from card)

- Hidden dim 2048, 40 layers (10× alternating Gated DeltaNet + Gated Attention)
- Token embedding 248,320 (padded)
- Gated DeltaNet: 32 V-heads, 16 QK-heads, 128 head dim
- Gated Attention: 16 Q-heads, 2 KV-heads, 256 head dim
- MoE: 256 total experts, 8 routed + 1 shared activated
- Multi-Token Prediction (MTP) training
- License: Apache 2.0

## Benchmark table — Language (verbatim from model card)

| Benchmark | Qwen3.5-27B | Qwen3.6-35B-A3B | Gemma4-31B |
|-----------|-------------|-----------------|------------|
| **Coding Agent** |
| SWE-bench Verified | 75.0 | 73.4 | 52.0 |
| SWE-bench Pro | 51.2 | 49.5 | 35.7 |
| Terminal-Bench 2.0 | 41.6 | 51.5 | 42.9 |
| **Knowledge** |
| MMLU-Pro | 86.1 | 85.2 | 85.2 |
| C-Eval | 90.5 | 90.0 | 82.6 |
| **STEM & Reasoning** |
| GPQA | 85.5 | 86.0 | 84.3 |
| AIME26 | 92.6 | 92.7 | 89.2 |

## Benchmark table — Vision Language (verbatim)

| Benchmark | Qwen3.5-27B | Qwen3.6-35B-A3B |
|-----------|-------------|-----------------|
| MMMU | 82.3 | 81.7 |
| RealWorldQA | 83.7 | 85.3 |
| MMBench EN | 92.6 | 92.8 |
| OmniDocBench | 88.9 | 89.9 |
| RefCOCO (avg) | 90.9 | 92.0 |
| VideoMMU | 82.3 | 83.7 |

## NOT published on the official Qwen3.6-35B-A3B card

- **BFCL** (function-calling) — no number
- **tau-bench / tau2-bench** (multi-turn agent) — no number
- **AgentBench** — no number
- **AIME25 / AIME24** — only AIME26 reported
- **HumanEval / LiveCodeBench / MATH** — none on the language table
- **MMLU (vanilla)** — only MMLU-Pro and C-Eval
- **MMMLU / multilingual / ArabicMMLU** — none

Community evaluation PR (`/Qwen/Qwen3.6-35B-A3B/discussions/3`) added eval-results
YAML for AIME_2026, GPQA, HLE, HMMT_FEB_2026, MMLU-PRO, SWE-BENCH_PRO,
SWE-BENCH_VERIFIED, TERMINAL-BENCH-2.0. From web-search snippet: GPQA 73.4
(diamond?), MMLU-Pro 86, HMMT Feb 2026 85.2 — these conflict with the model
card's GPQA 86.0 / MMLU-Pro 85.2. Likely the search snippet was reading a
**different mode** (Instruct vs Thinking) or was mis-extracted; the model-card
numbers above are the authoritative figures.

## Notes for cross-reference

- The card explicitly markets it as "Agentic Coding Power" — but does NOT
  publish BFCL or tau-bench on the official surface. This is a real gap for
  the agent's primary axis (tool-call / multi-turn agentic) — we have to compare
  Qwen3.6 to Qwen3-Coder-Next on agent benchmarks via the **Qwen3-Next**
  precursor numbers (see file 02), which is the closest published proxy.
- Qwen3.6 is **multimodal natively** — the only one in this comparison set with
  vision. For an information-agent like the agent's Phase 0, this is upside but
  not load-bearing today.
- Sampling defaults differ by mode: Thinking (general) T=1.0/top_p=0.95/top_k=20/
  presence_penalty=1.5; Instruct T=0.7/top_p=0.80/top_k=20/presence=1.5.
