---
title: Qwen3-Coder-Next-80B-A3B — technical report benchmark tables
source-url: https://arxiv.org/html/2603.00729v1
secondary-source: https://qwen.ai/blog?id=qwen3-coder-next
hf-card: https://huggingface.co/Qwen/Qwen3-Coder-Next-80B-A3B-Instruct (returned 401 on retrieval)
retrieved: 2026-05-08
fetched-via: WebFetch (arxiv HTML)
---

# Qwen3-Coder-Next-80B-A3B — published benchmarks

80B total / 3B active. Hybrid attention/MoE. Late-2025 / early-2026 release.
arXiv 2603.00729. Code-specialized — agentic coding, tool-use loop.

## Table 3 — SWE-Bench Verified (verbatim)

| Model | Size | SWE-Agent | MiniSWE-Agent | OpenHands |
|-------|------|-----------|---------------|-----------|
| Claude-Opus-4.5 | ? | 78.2 | 77.8 | 79.0 |
| Claude-Sonnet-4.5 | ? | 76.0 | 68.4 | 74.6 |
| DeepSeek-V3.2 | 671A37 | 70.2 | 67.2 | 72.6 |
| GLM-4.7 | 358A32 | 74.2 | 70.4 | 70.6 |
| MiniMax-M2.1 | 230A10 | 74.8 | 70.4 | 71.0 |
| Kimi-K2.5 | 1000A32 | 73.2 | 70.8 | – |
| **Qwen3-Coder-Next** | **80A3** | **70.6** | **71.1** | **71.3** |

## Table 4 — SWE-Bench Multilingual & SWE-Bench Pro (verbatim)

| Model | Size | SWE-Bench Multilingual | SWE-Bench-Pro (SWE-Agent) | SWE-Bench-Pro (MiniSWE-Agent) |
|-------|------|------------------------|---------------------------|-------------------------------|
| Claude-Opus-4.5 | ? | 71.7 | 51.6 | 50.2 |
| Claude-Sonnet-4.5 | ? | 67.2 | 50.5 | 43.0 |
| DeepSeek-V3.2 | 671A37 | 62.3 | 46.0 | 32.4 |
| GLM-4.7 | 358A32 | 63.7 | 45.1 | 39.4 |
| MiniMax-M2.1 | 230A10 | 66.2 | 40.8 | 39.1 |
| Kimi-K2.5 | 1000A32 | 63.7 | 47.3 | 42.8 |
| **Qwen3-Coder-Next** | **80A3** | **62.8** | **42.7** | **38.7** |

## Table 5 — Terminal-Bench 2.0 (verbatim)

| Model | Size | Terminus2-xml | Terminus2-json | ClaudeCode | QwenCode |
|-------|------|---------------|-----------------|------------|----------|
| Claude-Opus-4.5 | ? | 58.4 | 57.3 | 53.9 | 51.7 |
| Claude-Sonnet-4.5 | ? | 51.7 | 51.7 | 41.6 | 37.1 |
| DeepSeek-V3.2 | 671A37 | 34.8 | 39.3 | – | – |
| GLM-4.7 | 358A32 | 44.9 | 37.1 | – | 31.5 |
| MiniMax-M2.1 | 230A10 | – | 32.6 | 42.7 | 39.3 |
| Kimi-K2.5 | 1000A32 | 38.8 | 49.4 | 09.0 | 27.5 |
| **Qwen3-Coder-Next** | **80A3** | **34.2** | **36.2** | **30.9** | **25.8** |

## Table 6 — Function-Level & Competitive Programming (verbatim)

| Model | EvalPlus | MultiPL-E | CRUXEval | LiveCodeBench (v6) | OJBench | Codeforces |
|-------|----------|-----------|----------|-------------------|---------|-----------|
| Qwen3-Coder-480B-A35B | 86.66 | 88.00 | 92.13 | 44.93 | 14.98 | 1800 |
| Qwen3-Next | 89.00 | 89.00 | 94.81 | 51.79 | 20.04 | 1875 |
| **Qwen3-Coder-Next** | **86.56** | **88.23** | **95.88** | **58.93** | **23.01** | **2100** |

## Table 8 — General Knowledge & Reasoning (verbatim)

| Model | MMLU | MMLU-Redux | MMLU-Pro | GPQA | SuperGPQA |
|-------|------|-----------|----------|------|-----------|
| Qwen3-Next | 87.87 | 91.14 | 80.89 | 73.54 | 58.70 |
| **Qwen3-Coder-Next** | **87.73** | **91.18** | **80.52** | **74.49** | **57.45** |

## Table 9 — Competitive Math (verbatim)

| Model | HMMT25 Feb | HMMT25 Nov | AIME24 | AIME25 |
|-------|-----------|-----------|--------|--------|
| Qwen3-Next | 54.27 | 68.07 | 82.92 | 69.64 |
| **Qwen3-Coder-Next** | **70.21** | **75.57** | **89.01** | **83.07** |

## Tables not extracted

- **Table 2** — Tool-Call Format Following (not retrieved; need re-fetch)
- **Table 7** — Full-Stack Development (not retrieved)
- **BFCL** — NOT in the visible tables of arxiv 2603.00729v1
- **tau-bench / tau2-bench** — NOT in the visible tables
- **HumanEval (vanilla)** — replaced by EvalPlus / MultiPL-E
- **AIME26** — not reported (Qwen3.6 reports 92.7; not directly comparable since different exam years)
- **ArabicMMLU / MMMLU** — NOT in the visible tables; multilingual signal here is via SWE-Bench Multilingual (62.8) which is *coding* multilingual, not natural-language.

## Variant note: Qwen3-Coder-30B-A3B-Instruct

The 30B-A3B Coder variant exists on HF (`Qwen/Qwen3-Coder-30B-A3B-Instruct`) but
its model card does NOT publish benchmark numbers — it points to the Qwen3-Coder
blog and GitHub. No verified comparable scores located today.

## Variant note: Qwen3-Coder-480B-A35B-Instruct (flagship)

Model card has minimal benchmarks — only SWE-Bench Pro 38.7, Terminal-Bench 2.0
23.9*, EvasionBench 78.16. The arxiv table (above) gives Qwen3-Coder-480B-A35B's
function-level / competitive-programming numbers — and notably, Qwen3-Coder-Next
(80B-A3B) **beats Qwen3-Coder-480B-A35B on every column** of Table 6:
LiveCodeBench v6 (58.93 vs 44.93), Codeforces (2100 vs 1800), CRUXEval, OJBench.
The arxiv tables show Qwen3-Coder-Next has succeeded its larger sibling on
agentic + competitive coding axes despite 12× fewer total params and 12× fewer
active params.
